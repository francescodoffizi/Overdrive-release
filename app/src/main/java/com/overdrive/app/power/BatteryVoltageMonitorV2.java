package com.overdrive.app.power;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PowerManager;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * 12V-voltage-driven MCU sleep/wake state machine.
 *
 * <p>Direct port of the sibling-app routine. The contract:
 *
 * <ul>
 *   <li>Listens for {@code AbsBYDAutoOtaListener.onBatteryPowerVoltageChanged(double)}
 *       on the runtime {@code BYDAutoOtaDevice} (12V battery voltage in volts).</li>
 *   <li>Hysteresis: voltage {@code > 12.5V} after a period of being below it
 *       counts as "high" — schedules an MCU sleep request after {@code 15 min}.
 *       Voltage {@code <= 12.0V} after MCU was sleeping counts as "low recover" —
 *       wakes the MCU and re-arms the cycle.</li>
 *   <li>Holds a {@code PARTIAL_WAKE_LOCK} tagged {@code "RemoteMonitorWakeLock"}
 *       for up to {@code 600s} during the wake-recover window.</li>
 *   <li>Re-arm cadence is {@code 60s}. The MCU sleep request is deferred by
 *       {@code 15 min} from the last wake event so the head unit has a chance
 *       to do work before the next sleep.</li>
 * </ul>
 *
 * <p>This replaces the older 45-second MCU pulse loop in
 * {@code AccSentryDaemon.startChargingMaintenance}, which woke the MCU every
 * 45 s while ACC=OFF — counterproductive on a parked car (no alternator load,
 * just drains the 12V faster). The new model lets the MCU stay asleep when
 * the 12V is healthy and only intervenes on sustained low voltage.
 *
 * <p>Lifecycle: {@link #startMonitor(Context)} from {@code enterSentryMode},
 * {@link #stopMonitor()} from {@code exitSentryMode}.
 */
public final class BatteryVoltageMonitorV2 {

    private static final String TAG = "BatteryVoltageMonitorV2";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Voltage above which we consider 12V healthy enough to allow MCU sleep. */
    private static final double SLEEP_ALLOW_VOLTAGE = 12.5;

    /** Voltage at-or-below which we wake the MCU to recover. */
    private static final double WAKE_TRIGGER_VOLTAGE = 12.0;

    /** Re-arm cadence for the periodic monitor tick. */
    private static final long REARM_INTERVAL_MS = 60_000L;

    /** Defer interval for the next MCU sleep request after a wake. */
    private static final long MCU_SLEEP_DEFER_MS = 15 * 60_000L;

    /** Wake-lock acquisition window. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 600_000L;

    /** Wake-lock tag — kept to match the sibling-app trace for log-correlation. */
    private static final String WAKE_LOCK_TAG = "RemoteMonitorWakeLock";

    // Handler messages
    private static final int MSG_SCHEDULE_MONITOR = 1;
    private static final int MSG_DEFERRED_MCU_SLEEP = 3;
    private static final int MSG_WAKEUP_LOOP = 7;

    private static volatile boolean running = false;
    private static volatile boolean isWakeupMcu = true;
    private static volatile double lastPowerVoltage = -1.0;
    private static volatile double highPowerVoltage = -1.0;
    private static volatile long lastSleepTime = 0L;

    private static HandlerThread handlerThread;
    private static Handler handler;
    private static Object otaListener;       // AbsBYDAutoOtaListener instance
    private static Object powerListener;     // AbsBYDAutoPowerListener instance
    private static PowerManager.WakeLock wakeLock;

    /**
     * Caller-supplied context kept for re-acquiring the wake-lock during
     * {@link #forceWake()}. Don't reach into another daemon's static
     * accessor — V2 must work in whichever process boots it.
     */
    private static volatile Context appContext;

    private BatteryVoltageMonitorV2() {}

    /**
     * "Keep USB powered while parked" toggle (surveillance.keepUsbPowerOnAccOff,
     * default true). Mirrors {@code AccSentryDaemon.isKeepUsbPowerOnAccOff()}
     * EXACTLY — same key, same safe-default-true on any read failure — so the
     * two subsystems can never disagree about the user's intent. Read fresh on
     * each sleep decision (cheap, mtime-gated loadConfig; the value can't change
     * mid-park anyway since the daemon reads it once at ACC-OFF setup).
     *
     * <p>WHY THIS GATES MCU SLEEP: on the affected hardware the USB-bridged SD
     * reader's power rail follows the MCU/AP wake state. When the user asks to
     * keep USB powered, this monitor MUST NOT issue its voltage-driven MCU sleep
     * (the healthy-battery {@code doMcuSleep} path) — doing so drops USB/SD power
     * ~15 min into a park even though the toggle is ON. This restored the
     * pre-v22.2 behaviour where parked USB/SD stayed powered. The low-voltage
     * {@link #forceWake} recovery and the separate {@code SocCutoffMonitor}
     * (&le;10% SoC) remain the battery-safety floor regardless of this toggle.
     */
    private static boolean isKeepUsbPowerOnAccOff() {
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            if (s == null) return true;
            return s.optBoolean("keepUsbPowerOnAccOff", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * True when the user has selected DiLink 4 (byd_apa) camera mode.
     *
     * <p>Reads the config directly rather than calling into {@code AccSentryDaemon}
     * — this monitor must work in whichever process boots it, exactly as
     * {@link #isKeepUsbPowerOnAccOff()} above already does. Mirrors
     * {@code AccSentryDaemon.isDilink4CameraMode()}: same section, same key, same
     * fail-closed default so a read glitch can never suppress MCU sleep on the
     * legacy fleet (which would silently change their battery behaviour).
     */
    private static boolean isDilink4CameraMode() {
        try {
            org.json.JSONObject c = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("camera");
            if (c == null) return false;
            return "dilink4".equalsIgnoreCase(c.optString("cameraMode", "default"));
        } catch (Throwable t) {
            return false;
        }
    }

    public static synchronized void startMonitor(Context context) {
        // GATE (defense-in-depth): in "Vehicle ON only" mode this voltage-driven
        // MCU sleep/wake monitor — a purely post-vehicle-OFF battery-management loop
        // that holds its own recovery-window wakelock — must never arm. Today the only
        // caller is AccSentryDaemon's SentrySetup worker, which G1 already skips in
        // onOnly; this guard makes the monitor self-safe against any future/respawn
        // caller. Placed before the `running` check so no wakelock is ever acquired.
        if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
            logger.info("startMonitor: onOnly mode — voltage-driven keep-awake/MCU-sleep monitor disabled");
            return;
        }
        if (running) {
            logger.info("startMonitor: already running");
            return;
        }
        appContext = context;
        // Hand the context to McuPowerHal so its sentry-mode writes
        // (BYDAutoSpecialDevice 1901/1902) can resolve the device.
        McuPowerHal.setAppContext(context);
        running = true;
        logger.info("startMonitor: BEGIN");

        handlerThread = new HandlerThread("BatteryVoltageMonitorV2");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), BatteryVoltageMonitorV2::onMessage);

        registerOtaListener();
        registerPowerListener();

        // Seed the loop: ask current voltage + MCU status so we don't sit
        // idle waiting for a callback that may not fire for a minute.
        seedFromCurrentReadings();

        // Arm the recurring monitor tick.
        scheduleMonitor(REARM_INTERVAL_MS);

        // Acquire the recovery-window wake-lock.
        acquireWakeLock(context);
    }

    public static synchronized void stopMonitor() {
        if (!running) return;
        running = false;
        logger.info("stopMonitor: END");

        try { releaseWakeLock(); } catch (Throwable ignored) {}
        try { unregisterOtaListener(); } catch (Throwable ignored) {}
        try { unregisterPowerListener(); } catch (Throwable ignored) {}

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        handler = null;
        otaListener = null;
        powerListener = null;
        cachedOtaDevice = null;
        appContext = null;
    }

    // ── Public callback hook ────────────────────────────────────────

    /**
     * Entry point for live voltage callbacks from {@code BydDataCollector}'s
     * OTA listener hub. The collector already registers the (subclassed)
     * {@code AbsBYDAutoOtaListener} once and dispatches to its own
     * {@code onOtaCallback}; we piggyback on that registration instead of
     * trying to subclass an abstract class via {@code Proxy} (which throws).
     *
     * <p>No-op when the monitor isn't running. Cheap to call from every
     * voltage tick — the collector's onOta hub fires at the rate the HAL
     * delivers, no extra throttling needed here.
     */
    public static void notifyBatteryPowerVoltage(double voltage) {
        if (!running) return;
        onBatteryPowerVoltageChanged(voltage);
    }

    // ── Voltage / MCU state machine ─────────────────────────────────

    private static void onBatteryPowerVoltageChanged(double voltage) {
        if (!running) return;
        lastPowerVoltage = voltage;
        if (voltage > highPowerVoltage) {
            highPowerVoltage = voltage;
        }
        logger.info("onBatteryPowerVoltageChanged: " + voltage
                + " V  (high=" + highPowerVoltage + ")");

        // High-voltage path: schedule MCU sleep after the defer window.
        if (allowSleep(voltage)) {
            scheduleDeferredMcuSleep();
        }

        // Low-voltage path: wake MCU and reset the high-water mark.
        if (shouldWake(voltage)) {
            forceWake();
        }
    }

    private static boolean allowSleep(double voltage) {
        return voltage >= highPowerVoltage && voltage > SLEEP_ALLOW_VOLTAGE;
    }

    private static boolean shouldWake(double voltage) {
        return System.currentTimeMillis() - lastSleepTime >= REARM_INTERVAL_MS
                && voltage <= WAKE_TRIGGER_VOLTAGE;
    }

    private static void scheduleDeferredMcuSleep() {
        // DiLink 4 gate. The reference app's DEFAULT flameout mode is 0 → V1
        // (kh/a.c() switching on vj/a.m(), which defaults the
        // key.flameout.wakeup.mode preference to 0), and V1 (kh/b) has NO sleep
        // path at all — its entire ACC-off behaviour is one unconditional
        // "-1442840502 = 1" hold. We ported V2 (kh/d) instead and wired it up as
        // if it were the shipping default, so on byd_apa we would, after 15 min of
        // healthy 12 V, request an MCU sleep that collapses the very AVM/ISP rail
        // the pano cameras feed from — black-framing surveillance for the rest of
        // the park, which is exactly the reported symptom.
        //
        // Suppress the healthy-battery sleep on dilink4 to match V1. The battery
        // floor is unaffected: the low-voltage forceWake() recovery and
        // SocCutoffMonitor (<=10% SoC self-shutdown) still run. Every other
        // variant keeps the full V2 hysteresis model byte-for-byte.
        if (isDilink4CameraMode()) {
            if (handler != null) handler.removeMessages(MSG_DEFERRED_MCU_SLEEP);
            logger.info("scheduleDeferredMcuSleep: SUPPRESSED — dilink4 mirrors reference-app "
                    + "V1 (no MCU sleep; AVM/ISP rail must stay held for pano cameras)");
            return;
        }
        // Keep-USB-powered gate. When the user opted to keep USB powered while
        // parked, we never schedule the healthy-battery MCU sleep — that sleep
        // would drop the USB/SD rail. The low-voltage forceWake() path and the
        // SoC cutoff monitor still protect the 12V battery. Read fresh so a
        // toggle flip on a later ACC-OFF cycle takes effect.
        if (isKeepUsbPowerOnAccOff()) {
            // Cancel any sleep already armed from before the toggle was read
            // (defensive — the value can't change mid-park, but a pending
            // message from a prior tick must not survive).
            if (handler != null) handler.removeMessages(MSG_DEFERRED_MCU_SLEEP);
            logger.info("scheduleDeferredMcuSleep: SUPPRESSED — Keep-USB-powered is ON "
                    + "(MCU stays awake so USB/SD rail holds; low-voltage recovery + SoC cutoff still active)");
            return;
        }
        if (!isWakeupMcu) {
            // We already issued a sleep request this session — don't re-issue
            // until {@link #forceWake} flips the flag back to true.
            return;
        }
        if (handler == null) return;
        // Only arm if no sleep is already pending. Re-arming on every poll
        // would push the timer out indefinitely while voltage stays in the
        // sleep-allow range (steady-state) — meaning the deferred sleep
        // would never actually fire on a parked car with a healthy battery.
        if (handler.hasMessages(MSG_DEFERRED_MCU_SLEEP)) {
            return;
        }
        Message msg = handler.obtainMessage(MSG_DEFERRED_MCU_SLEEP);
        handler.sendMessageDelayed(msg, MCU_SLEEP_DEFER_MS);
        logger.info("scheduleDeferredMcuSleep: in " + MCU_SLEEP_DEFER_MS + " ms");
    }

    private static void doMcuSleep() {
        if (!running) return;
        // Belt-and-suspenders to the dilink4 gate in scheduleDeferredMcuSleep: a
        // sleep message queued before that gate was evaluated must ALSO be refused
        // at execution time, or the AVM/ISP rail still collapses mid-park. Same
        // execution-time-honouring pattern as the keep-USB check below.
        if (isDilink4CameraMode()) {
            logger.info("doMcuSleep: SKIPPED — dilink4 never sleeps the MCU "
                    + "(queued sleep cancelled at execution)");
            return;
        }
        // Belt-and-suspenders to scheduleDeferredMcuSleep's gate: if a sleep
        // message was already queued before the toggle was evaluated (e.g. a
        // tick that fired in the 35s window before this monitor started), do
        // NOT execute it while Keep-USB-powered is ON. The scheduling gate
        // normally prevents us reaching here, but a queued message must also
        // be honoured at execution time so the USB/SD rail is never dropped.
        if (isKeepUsbPowerOnAccOff()) {
            logger.info("doMcuSleep: SKIPPED — Keep-USB-powered is ON (queued sleep cancelled at execution)");
            return;
        }
        boolean ok = McuPowerHal.requestMcuSleep();
        // sentry-mode mirror — sleep variant
        boolean sentryOk = McuPowerHal.requestSentrySleep();
        lastSleepTime = System.currentTimeMillis();
        isWakeupMcu = false;
        highPowerVoltage = -1.0;
        logger.info("doMcuSleep: power=" + ok + " sentry=" + sentryOk);
    }

    private static void forceWake() {
        if (!running) return;
        boolean ok = McuPowerHal.requestMcuWake();
        boolean sentryOk = McuPowerHal.requestSentryWake();
        isWakeupMcu = true;
        logger.info("forceWake: power=" + ok + " sentry=" + sentryOk);
        // Re-arm the wake-lock window since we just had to recover.
        // Use the caller-supplied context — V2 may run in any daemon process.
        if (appContext != null) acquireWakeLock(appContext);
        // Re-arm the wakeup loop tick.
        if (handler != null) {
            handler.removeMessages(MSG_WAKEUP_LOOP);
            handler.sendMessageDelayed(
                    handler.obtainMessage(MSG_WAKEUP_LOOP), REARM_INTERVAL_MS);
        }
    }

    // ── Handler loop ────────────────────────────────────────────────

    private static boolean onMessage(Message msg) {
        switch (msg.what) {
            case MSG_SCHEDULE_MONITOR:
                if (running) {
                    seedFromCurrentReadings();
                    scheduleMonitor(REARM_INTERVAL_MS);
                }
                return true;
            case MSG_DEFERRED_MCU_SLEEP:
                doMcuSleep();
                return true;
            case MSG_WAKEUP_LOOP:
                if (running) {
                    seedFromCurrentReadings();
                    handler.removeMessages(MSG_WAKEUP_LOOP);
                    handler.sendMessageDelayed(
                            handler.obtainMessage(MSG_WAKEUP_LOOP), REARM_INTERVAL_MS);
                }
                return true;
            default:
                return false;
        }
    }

    private static void scheduleMonitor(long delayMs) {
        if (handler == null) return;
        handler.removeMessages(MSG_SCHEDULE_MONITOR);
        handler.sendMessageDelayed(
                handler.obtainMessage(MSG_SCHEDULE_MONITOR), delayMs);
    }

    /**
     * Cached OTA device resolved from {@link #appContext}. We do NOT go
     * through {@code BydDataCollector.getInstance()} for this — that's
     * the cam_daemon process's collector and returns a fresh empty
     * instance with no devices in any other process (e.g. acc_sentry).
     */
    private static volatile Object cachedOtaDevice;

    private static Object resolveOtaDevice() {
        if (cachedOtaDevice != null) return cachedOtaDevice;
        if (appContext == null) {
            logger.debug("resolveOtaDevice: no appContext");
            return null;
        }
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.ota.BYDAutoOtaDevice");
            java.lang.reflect.Method getInstance = cls.getMethod(
                    "getInstance", android.content.Context.class);
            cachedOtaDevice = getInstance.invoke(null, appContext);
            if (cachedOtaDevice != null) {
                logger.info("resolveOtaDevice: " + cachedOtaDevice.getClass().getName());
            }
        } catch (Throwable t) {
            logger.debug("resolveOtaDevice failed: " + t.getMessage());
        }
        return cachedOtaDevice;
    }

    private static void seedFromCurrentReadings() {
        try {
            Object ota = resolveOtaDevice();
            if (ota == null) {
                logger.debug("seed: ota device unresolved (process-local resolve failed)");
                return;
            }
            Object v = BydDeviceHelper.callGetter(ota, "getBatteryPowerVoltage");
            if (v instanceof Number) {
                double voltage = ((Number) v).doubleValue();
                onBatteryPowerVoltageChanged(voltage);
            } else {
                logger.debug("seed: getBatteryPowerVoltage returned " + v);
            }
        } catch (Throwable t) {
            logger.debug("seed: getBatteryPowerVoltage failed: " + t.getMessage());
        }
    }

    // ── BYD listener registration via reflection ────────────────────

    private static void registerOtaListener() {
        // Live voltage callbacks land via BydDataCollector.onOtaCallback,
        // which fans out to {@link #notifyBatteryPowerVoltage}. No direct
        // listener registration here — Proxy can't implement an abstract
        // class, and we don't want to duplicate the collector's listener.
        logger.info("registerOtaListener: piggybacking on BydDataCollector OTA hub");
    }

    private static void unregisterOtaListener() {
        // No direct listener; nothing to release.
    }

    private static void registerPowerListener() {
        // Mirrors sibling-app m8240m().registerListener(mPowerListener) and
        // mPowerListener.onMcuStatusChanged(getMcuStatus()) seed.
        // Resolves the device directly from appContext — same rationale as
        // resolveOtaDevice (cross-process safe).
        //
        // {@link #isWakeupMcu} tracks "did WE issue a sleep request this
        // session" — NOT the MCU's current physical state. We start true
        // (haven't issued one yet) regardless of what getMcuStatus reads.
        // Otherwise: daemon boots into a session where MCU is auto-slept by
        // the BCM, isWakeupMcu starts false, every {@link #scheduleDeferredMcuSleep}
        // call early-returns, and we never issue our sentry-mode sleep
        // request — leaving the MCU in BCM's vanilla sleep, not our
        // deeper sentry-mode sleep with keys 1901/1902.
        try {
            if (appContext == null) return;
            Class<?> cls = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice");
            java.lang.reflect.Method getInstance = cls.getMethod(
                    "getInstance", android.content.Context.class);
            Object power = getInstance.invoke(null, appContext);
            if (power == null) return;
            Object status = BydDeviceHelper.callGetter(power, "getMcuStatus");
            if (status instanceof Number) {
                int s = ((Number) status).intValue();
                logger.info("registerPowerListener: getMcuStatus=" + s
                        + " (isWakeupMcu retained as true — flag tracks our intent, not MCU physical state)");
            }
        } catch (Throwable t) {
            logger.debug("registerPowerListener: " + t.getMessage());
        }
    }

    private static void unregisterPowerListener() {
        // No-op — we only seed-read getMcuStatus, no listener attached above.
    }

    // ── WakeLock ────────────────────────────────────────────────────

    private static synchronized void acquireWakeLock(Context context) {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            logger.info("acquireWakeLock: " + WAKE_LOCK_TAG
                    + " (" + WAKE_LOCK_TIMEOUT_MS + "ms)");
        } catch (Throwable t) {
            logger.warn("acquireWakeLock failed: " + t.getMessage());
        }
    }

    private static synchronized void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    // resolveDevice(...) by collector-field is intentionally absent — V2
    // resolves devices directly from appContext (process-local). Going
    // through BydDataCollector.getInstance() returns a fresh empty collector
    // in any process other than cam_daemon.
}
