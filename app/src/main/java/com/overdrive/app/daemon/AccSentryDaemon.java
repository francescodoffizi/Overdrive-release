package com.overdrive.app.daemon;

import android.content.Context;
import android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener;
import android.hardware.bydauto.power.AbsBYDAutoPowerListener;
import android.hardware.bydauto.power.BYDAutoPowerDevice;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import com.overdrive.app.daemon.proxy.Safe;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatteryPowerData;
import com.overdrive.app.monitor.BatteryVoltageData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.VehicleDataListener;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.Socket;

/**
 * ACC Sentry Daemon - runs as shell user (UID 2000) via ADB shell.
 *
 * RESPONSIBILITIES:
 * 1. ACC state monitoring via BYD bodywork service
 * 2. Screen control (input keyevent) - MUST run as UID 2000
 * 3. Surveillance enable/disable via IPC to CameraDaemon
 * 4. MCU wake-up to keep hardware powered during sentry mode
 * 5. Backlight control and blocker activity management
 *
 * NOTE: Whitelisting and ACC Lock acquisition is handled by SentryDaemon (UID 1000).
 * This daemon focuses on ACC state detection and sentry mode management.
 */
public class AccSentryDaemon {

    private static final String TAG = "AccSentryDaemon";
    private static DaemonLogger logger;

    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** com.overdrive.app */
    private static String APP_PACKAGE_NAME() { return Safe.s("3Is1Ze/xWL6dkFvd9bF+deUGK/HqnInkSi6jinpc6s8="); }
    /** accmodemanager */
    private static String SERVICE_ACCMODEMANAGER() { return Safe.s("tr877WU3+MV4zFtCjanWUw=="); }
    /** byd_datacached */
    private static String SERVICE_BYD_DATACACHE() { return Safe.s("JQiIxMJxYlF8spk2fIi8Sg=="); }
    /** bg_datacache */
    private static String SERVICE_BG_DATACACHE() { return Safe.s("m84QJmAGTQpH+XP36MaDpA=="); }
    /** svc wifi enable */
    private static String CMD_WIFI_ENABLE() { return Safe.s("GzzLDvODRsKARkPOXEZeIA=="); }
    /** /data/local/tmp */
    private static String PATH_DATA_LOCAL_TMP() { return Safe.s("vuaMjrmBGBFh07qqnUuL8w=="); }
    /** /data/local/tmp/telegram_config.properties */
    private static String PATH_TELEGRAM_CONFIG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzwQSn0P1N0jxHygc8N+4Ft+9mlR8XQ+WvEw0ktanrtNx"); }

    // Power levels from BYDAutoBodyworkDevice
    private static final int POWER_LEVEL_OFF = 0;
    private static final int POWER_LEVEL_ACC = 1;
    private static final int POWER_LEVEL_ON = 2;
    private static final int POWER_LEVEL_OK = 3;

    // MCU Status codes
    private static final int MCU_STATUS_SLEEPING = 0;
    private static final int MCU_STATUS_ACTIVE = 1;
    private static final int MCU_STATUS_ACC_OFF = 2;
    private static final int MCU_STATUS_DEEP_SLEEP = 3;

    private static volatile boolean running = true;
    private static volatile boolean inSentryMode = false;
    private static int lastPowerLevel = -1;
    private static int lastMcuStatus = -1;
    // Set to true once a bodywork listener has been successfully registered
    // with the BYD HAL. The slow-retry thread spins until this flips true,
    // and the periodic ACC heartbeat is gated on it as well — there's no
    // point publishing state if the daemon never received an event source.
    private static volatile boolean bodyworkRegistered = false;
    // Heartbeat thread for periodic ACC state republish (covers the wedge
    // where CameraDaemon restarts mid-drive and misses our edge-only IPC).
    private static Thread accHeartbeatThread = null;
    // Last accOff value the heartbeat actually published. -1 = nothing
    // published yet; 0 = ACC ON; 1 = ACC OFF. Heartbeat short-circuits
    // when its tick would re-publish the same state, because each IPC
    // re-runs CameraDaemon.onAccStateChanged side-effects (cleanupDoorLockGate,
    // surveillanceEnabled reset, DB write, OEM recalc) that aren't fully
    // idempotent. Edge handlers in onPowerLevelChanged still notify
    // unconditionally — they're the authoritative state delta. See
    // prior-audit "Heartbeat triggers full CameraDaemon.onAccStateChanged
    // side-effects every 30s".
    private static volatile int lastHeartbeatPublishedAccOff = -1;
    // Counter of consecutive heartbeat ticks that hit the dedup
    // short-circuit (state unchanged since last publish). When this
    // reaches HEARTBEAT_FORCE_REPUBLISH_TICKS we publish anyway, so a
    // CameraDaemon process restart mid-drive (which resets its in-process
    // lastDispatchedAccIsOff cache) resyncs within ~5 min instead of
    // waiting indefinitely for the next bodywork edge. CameraDaemon's
    // own onAccStateChanged dedup (CameraDaemon.java:2802-2809) drops
    // the no-op IPC when the consumer is already in sync, so the
    // periodic republish is cheap when not needed. See prior-audit
    // "Heartbeat refuses to republish ACC ON after CameraDaemon
    // process restart".
    private static volatile int heartbeatDedupRunLength = 0;
    // Dropped from 10 (~5min) to 2 (~1min) per prior-audit "Heartbeat
    // dedup creates 5-minute pano wedge on CameraDaemon mid-drive
    // restart". CameraDaemon's onAccStateChanged dedup drops the no-op
    // IPC when state already matches, so a 1min republish is cheap in
    // steady state but caps the post-restart resync window at 60s
    // instead of 5min — pano stays armed for at most one extra minute
    // of staleness before the heartbeat force-republishes.
    private static final int HEARTBEAT_FORCE_REPUBLISH_TICKS = 2;
    // Dedicated single-thread executor for IPC dispatch — keeps
    // sendSurveillanceCommandRaw retry sleeps off the BYD HAL listener
    // thread (callbacks are single-threaded and a stalled listener
    // would drop subsequent ACC edges). See prior-audit "notifyAccState
    // blocks BYD HAL listener thread up to 11s on retry".
    private static final java.util.concurrent.ExecutorService accNotifyExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AccSentryNotifyIPC");
            t.setDaemon(true);
            return t;
        });
    // Thread for the 10-second loop
    private static Thread systemKeepAliveThread = null;
    // Interval from  (C0004a0)
    private static final long SYSTEM_KEEPALIVE_INTERVAL_MS = 10000;

    // Surveillance IPC
    private static final int SURVEILLANCE_IPC_PORT = 19877;
    private static volatile boolean surveillanceEnabled = false;

    // MCU wake timestamp (for voltage-triggered wake cooldown)
    private static volatile long lastMcuWakeTime = 0;
    
    // ==================== ACTIVE VOLTAGE RECOVERY (REPLACED) ====================
    //
    // Replaced by com.overdrive.app.power.BatteryVoltageMonitorV2 +
    // com.overdrive.app.power.McuPowerHal. The 45 s "wake the MCU on every
    // pulse" model was net-negative on a parked car — no alternator load,
    // and each pulse drew the 12 V it was meant to preserve. The new model
    // gates MCU wake/sleep on a 12.0 V / 12.5 V hysteresis with a 60 s
    // re-arm and a 15 min sleep-defer window.
    //
    // Kept as commented references only; no live code paths use these.
    // private static Thread mcuChargingThread = null;
    // private static final long MCU_CHARGE_PULSE_INTERVAL_MS = 45000;

    // Context for BYD device access
    private static Context appContext;

    /** Process-local app context. Returns null before main() initialises it. */
    public static Context getAppContext() { return appContext; }

    // WakeLock for guaranteed CPU cycles. volatile + synchronized accessors
    // (acquireWakeLock/releaseWakeLock) because on dilink4 TWO HAL listeners
    // (BYDAutoBodyworkDevice + BYDAutoPowerDevice) fire enter/exitSentryMode
    // concurrently on independent binder threads — an unsynchronized check-then-act
    // here would let both create + acquire a non-ref-counted lock and orphan one.
    private static volatile PowerManager.WakeLock wakeLock;

    // Original screen timeout (saved before sentry mode)
    private static String originalScreenTimeout = "60000";
    
    // Daemon start time for uptime tracking
    private static long startTime = 0;
    
    // Handler for periodic status checks
    private static android.os.Handler statusHandler = null;

    // ==================== CENTRALIZED MCU POWER HELPER ====================
    // Cached BYDAutoPowerDevice instance to avoid repeated reflection
    private static BYDAutoPowerDevice cachedPowerDevice = null;
    
    // ==================== SPECIAL HARDWARE CONFIG (USB/POWER) ====================
    // Cached BYDAutoSpecialDevice for peripheral power control
    private static Object cachedSpecialDevice = null;
    
    // Magic config IDs from BYD malware analysis (C1310c class)
    // These control the BCM's peripheral power rail behavior
    // Per the DiCarServer feature catalog (dev/byd-property-bus/dicarserver_feature_catalog.txt)
    // these two are the vehicle's own SENTRY-MODE flags, not power-rail holds:
    //   782237711 = 0x2EA0000F SPECIAL_LOCAL_CTL_ENTER_SENTRY_MODE_SET
    //   782237728 = 0x2EA00020 SPECIAL_SENTRY_MODE_SET
    // The names below are kept (widely referenced) but the trailing comments are corrected:
    // holding sentry mode is what stops the MCU cutting the 5V/modem rails, which is why the
    // effect reads as a rail hold. Values unchanged.
    private static final int SPECIAL_CONFIG_REMOTE_POWER_MODE = 782237711;  // 0x2EA0000F enter sentry mode
    private static final int SPECIAL_CONFIG_DATA_MODULE_POWER = 782237728;  // 0x2EA00020 sentry-mode state
    
    /**
     * Get or create the cached BYDAutoPowerDevice instance.
     * Uses PermissionBypassContext for BYD hardware access.
     */
    private static BYDAutoPowerDevice getPowerDevice() {
        if (cachedPowerDevice != null) return cachedPowerDevice;
        if (appContext == null) return null;
        
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            cachedPowerDevice = BYDAutoPowerDevice.getInstance(permissiveContext);
        } catch (Exception e) {
            log("Failed to get BYDAutoPowerDevice: " + e.getMessage());
        }
        return cachedPowerDevice;
    }
    
    /**
     * Candidate FQNs for BYDAutoSpecialDevice, probed in order.
     *
     * <p>TWO PACKAGES EXIST IN THE WILD. The Escort reference app
     * (com.yrdata.escort_auto, flavor bydSofaPro) imports the BARE
     * {@code android.hardware.special.BYDAutoSpecialDevice} — confirmed in its
     * raw dex string table, not just a decompiler artifact:
     * <pre>
     *   classes2.dex:  /Landroid/hardware/special/BYDAutoSpecialDevice;
     * </pre>
     * Corroborating: the BYD SDK javadoc bundled in this repo under {@code doc/}
     * lists 20 {@code android.hardware.bydauto.*} packages and NO {@code special}
     * one — consistent with the class living outside the {@code bydauto} subtree.
     *
     * <p>We previously hardcoded ONLY the {@code .bydauto.special.} variant. When
     * that FQN is absent, {@code Class.forName} throws, this resolver returns null,
     * and EVERY {@link #setSpecialConfig} write becomes a silent no-op — which
     * silently disabled the esco sentry keep-alive pair (1901/1902) AND the OEM 409
     * camera/ISP power vote on any trim using the bare package. That is exactly the
     * "pano cameras unreachable with ACC off" symptom: the AVM/ISP rail is never
     * actually held, so post-ACC-OFF AVMCamera frames come back all-zero.
     *
     * <p>The previously-working FQN is tried FIRST, so any unit that resolved before
     * resolves identically now (same class, same instance, zero behaviour change).
     *
     * <p><b>The bare fallback is gated to DiLink 4.</b> It would be wrong to call the
     * extra candidate "strictly additive because the old path already returned null":
     * {@link #setSpecialConfig} is also reached from the FLEET-WIDE writes in
     * {@link #configurePeripheralPower} (782237711 / 782237728) and
     * {@link #applySentryIspPowerVote} (the 409 pair), which run on every variant. On
     * a legacy trim that ships only the bare class, all of those are currently inert
     * no-ops; making them start landing would newly drive BCM peripheral-power and
     * camera/ISP flags on hardware they have never been exercised against. Gating
     * keeps the legacy write set byte-identical while DiLink 4 gets the real device.
     */
    private static final String[] SPECIAL_DEVICE_CLASS_CANDIDATES = {
        // Tried first: preserves bit-exact behaviour on every trim that already
        // resolved this class (the 90% legacy fleet).
        "android.hardware.bydauto.special.BYDAutoSpecialDevice",
        // Escort/DiLink4 reality — verified in raw dex (see above). DiLink 4 ONLY.
        "android.hardware.special.BYDAutoSpecialDevice",
    };

    /** Number of leading {@link #SPECIAL_DEVICE_CLASS_CANDIDATES} probed on non-dilink4. */
    private static final int SPECIAL_DEVICE_LEGACY_CANDIDATE_COUNT = 1;

    /**
     * Get the BYDAutoSpecialDevice instance via reflection.
     * This device controls hidden BCM configuration for peripheral power.
     *
     * <p>Probes {@link #SPECIAL_DEVICE_CLASS_CANDIDATES} in order and caches the
     * first FQN whose {@code getInstance(Context)} returns non-null. Logs which
     * FQN won so a field log tells us unambiguously whether the rail writes are
     * landing at all — previously the failure was a single easy-to-miss line and
     * every downstream write no-oped in silence.
     */
    private static Object getSpecialDevice() {
        if (cachedSpecialDevice != null) return cachedSpecialDevice;
        if (appContext == null) return null;

        Context permissiveContext;
        try {
            permissiveContext = new PermissionBypassContext(appContext);
        } catch (Throwable t) {
            log("Failed to wrap context for BYDAutoSpecialDevice: " + t.getMessage());
            return null;
        }

        // Non-dilink4 probes ONLY the historically-resolved FQN so its write set is
        // byte-identical to before this change (see SPECIAL_DEVICE_CLASS_CANDIDATES).
        int limit = isDilink4CameraMode()
                ? SPECIAL_DEVICE_CLASS_CANDIDATES.length
                : SPECIAL_DEVICE_LEGACY_CANDIDATE_COUNT;
        for (int i = 0; i < limit; i++) {
            String fqn = SPECIAL_DEVICE_CLASS_CANDIDATES[i];
            try {
                Class<?> clazz = Class.forName(fqn);
                Method getInstance = clazz.getMethod("getInstance", Context.class);
                Object device = getInstance.invoke(null, permissiveContext);
                if (device == null) {
                    log("BYDAutoSpecialDevice [" + fqn + "] getInstance returned null — trying next");
                    continue;
                }
                cachedSpecialDevice = device;
                log("BYDAutoSpecialDevice acquired via " + fqn);
                return cachedSpecialDevice;
            } catch (ClassNotFoundException e) {
                log("BYDAutoSpecialDevice [" + fqn + "] not present — trying next");
            } catch (Exception e) {
                log("BYDAutoSpecialDevice [" + fqn + "] resolve failed: " + e.getMessage());
            }
        }
        // Every probed candidate failed: all setSpecialConfig writes will no-op.
        // Log loudly — this is the difference between "sentry keep-alive armed"
        // and "silently doing nothing all night".
        log("ERROR: BYDAutoSpecialDevice unavailable (" + limit + " candidate(s) probed) — "
            + "sentry keep-alive (1901/1902) and 409 ISP vote will NOT be applied");
        return null;
    }
    
    /**
     * Sets a hidden BYD configuration value via BYDAutoSpecialDevice.
     * Used to keep USB/Peripherals powered during Sleep.
     * 
     * @param configId The magic config ID (e.g., 782237711)
     * @param value The value to set (typically 0=OFF, 1=ON)
     */
    private static void setSpecialConfig(int configId, int value) {
        Object device = getSpecialDevice();
        if (device == null) {
            log("Cannot set Special Config - device unavailable");
            return;
        }
        
        try {
            // 1. Create the Value Object (BYDAutoEventValue)
            Class<?> valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object valueObj = valueClass.newInstance();
            
            // 2. Set the integer value
            java.lang.reflect.Field intValueField = valueClass.getField("intValue");
            intValueField.setInt(valueObj, value);
            
            // 3. Set the value type (1 = Integer) - may be needed on some models
            try {
                java.lang.reflect.Field typeField = valueClass.getField("valueType");
                typeField.setInt(valueObj, 1);
            } catch (Exception ignored) {
                // Field might not exist on older SDKs
            }
            
            // 4. Call set(int[] ids, BYDAutoEventValue value)
            Class<?> deviceClass = device.getClass();
            Method setMethod = deviceClass.getMethod("set", int[].class, valueClass);
            int[] ids = { configId };
            Object rc = setMethod.invoke(device, ids, valueObj);

            // Log the RAW result code, not just "set to: N". BYD's contract is
            // rc==0 for success; a non-zero code means the HAL REJECTED the write.
            // The previous log line was emitted unconditionally after a
            // no-exception invoke, so a rejected write was indistinguishable from
            // an applied one — which is how a completely inert sentry keep-alive
            // could look healthy in a field log.
            if (rc instanceof Integer && ((Integer) rc) != 0) {
                log("Special Config [" + configId + "] <- " + value
                    + " REJECTED by HAL (rc=" + rc + ")");
            } else {
                log("Special Config [" + configId + "] set to: " + value
                    + " (rc=" + rc + ")");
            }
        } catch (Exception e) {
            log("Failed to set Special Config [" + configId + "]: " + e.getMessage());
        }
    }
    
    /**
     * Sets a hidden BYD configuration value via BYDAutoPowerDevice.
     * Used for power hold/release signals (e.g., -1442840502).
     * 
     * @param configId The power config ID
     * @param value The value to set
     */
    private static void setPowerConfig(int configId, int value) {
        BYDAutoPowerDevice device = getPowerDevice();
        if (device == null) {
            log("Cannot set Power Config - device unavailable");
            return;
        }
        
        try {
            Class<?> valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object valueObj = valueClass.newInstance();
            
            java.lang.reflect.Field intValueField = valueClass.getField("intValue");
            intValueField.setInt(valueObj, value);
            
            try {
                java.lang.reflect.Field typeField = valueClass.getField("valueType");
                typeField.setInt(valueObj, 1);
            } catch (Exception ignored) {}
            
            Method setMethod = device.getClass().getMethod("set", int[].class, valueClass);
            int[] ids = { configId };
            setMethod.invoke(device, ids, valueObj);
            
            log("Power Config [" + configId + "] set to: " + value);
        } catch (Exception e) {
            log("Failed to set Power Config [" + configId + "]: " + e.getMessage());
        }
    }

    /**
     * Toggles the "Remote Surveillance" power flags in the Gateway/BCM.
     * Matches the secondary reference app C1310c implementation exactly:
     *
     * DISABLE path:
     *   - SpecialDevice 782237711 = 0 (sentry keep-alive OFF)
     *   - SpecialDevice 782237728 = 2 (allow sleep — value is 2, NOT 0)
     *   - PowerDevice  -1442840502 = 0 (release power hold)
     *
     * ENABLE path (MCU status 1 or 10):
     *   - SpecialDevice 782237711 = 1 (sentry keep-alive ON)
     *   - SpecialDevice 782237728 = 1 (Modem/USB rail ON)
     *   - PowerDevice  -1442840502 = 1 ON dilink4 ONLY — esco kh/C6861d.java:344
     *     writes this on its sentry wake path. Without it the byd_apa MCU
     *     drops the AVM/ISP rail seconds after ACC OFF and any subsequent
     *     AVMCamera frames are all-zero. Legacy secondary-reference path skips this write
     *     (untouched, bit-exact 90% fleet behaviour).
     *
     * ENABLE path (MCU needs wake):
     *   - wakeUpMcu() loop, then signals + dilink4 power hold are set when MCU is ready.
     *
     * <p><b>ALL writes here are UNCONDITIONAL — identical regardless of the "Keep USB
     * powered" toggle.</b> The SpecialDevice rail writes do NOT gate USB-port power on
     * DiLink 3.0 (proven on-device). The toggle's ONLY effect is gating {@link
     * #performSystemWakeUp()} in {@link #enterSentryMode()}: the AP wake state is the
     * real USB lever (USB VBUS follows wakefulness). This method behaves the same in
     * both toggle states.
     *
     * @param enable true to keep peripherals powered, false to restore stock behavior
     */
    private static void configurePeripheralPower(boolean enable) {
        log("Configuring Peripheral Power (USB/Data): " + (enable ? "ON" : "OFF"));

        if (!enable) {
            // DISABLE — restore stock, allow MCU to cut power
            setSpecialConfig(SPECIAL_CONFIG_REMOTE_POWER_MODE, 0);  // Sentry keep-alive OFF
            setSpecialConfig(SPECIAL_CONFIG_DATA_MODULE_POWER, 2);  // Allow sleep (value=2, NOT 0)
            setPowerConfig(-1442840502, 0);                         // Release power hold (PowerDevice, not SpecialDevice)
            applyEscoSentrySpecialConfig(false);                    // dilink4-only esco-parity disable
            applySentryIspPowerVote(false);                         // release OEM 409 camera/ISP power vote (fleet-wide)
        } else {
            // ENABLE — check MCU state first. ALL peripheral rail writes below are
            // UNCONDITIONAL and identical regardless of the "Keep USB powered" toggle.
            // The toggle's ONLY effect is gating performSystemWakeUp() in enterSentryMode
            // (the AP wake state is the real USB lever on DiLink 3.0; the SpecialDevice
            // rail writes do NOT gate USB-port power here — proven on-device).
            int mcuStatus = getMcuStatus();
            log("MCU status for peripheral power: " + mcuStatus);

            if (mcuStatus == 1 || mcuStatus == 10) {
                // MCU is in normal standby — use signal-based path
                setSpecialConfig(SPECIAL_CONFIG_REMOTE_POWER_MODE, 1);  // Sentry keep-alive ON
                setSpecialConfig(SPECIAL_CONFIG_DATA_MODULE_POWER, 1);  // Modem/USB rail ON
                applyEscoSentrySpecialConfig(true);                    // dilink4-only esco-parity enable
                applyEscoMcuPowerHold(true);                           // dilink4-only McuStatus=1
                applySentryIspPowerVote(true);                         // OEM 409 camera/ISP power vote (fleet-wide)
            } else {
                // MCU needs active wake — use wakeUpMcu() then retry
                log("MCU not ready (status=" + mcuStatus + "), waking up and retrying...");
                wakeUpMcu();
                // Retry after 1 second to allow MCU to stabilize
                new Thread(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    int retryStatus = getMcuStatus();
                    log("MCU status after wake: " + retryStatus);
                    if (retryStatus == 1 || retryStatus == 10) {
                        setSpecialConfig(SPECIAL_CONFIG_REMOTE_POWER_MODE, 1);
                        setSpecialConfig(SPECIAL_CONFIG_DATA_MODULE_POWER, 1);
                        applyEscoSentrySpecialConfig(true);
                        applyEscoMcuPowerHold(true);
                        applySentryIspPowerVote(true);
                    } else {
                        // One more attempt
                        wakeUpMcu();
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                        setSpecialConfig(SPECIAL_CONFIG_REMOTE_POWER_MODE, 1);
                        setSpecialConfig(SPECIAL_CONFIG_DATA_MODULE_POWER, 1);
                        applyEscoSentrySpecialConfig(true);
                        applyEscoMcuPowerHold(true);
                        applySentryIspPowerVote(true);
                        log("Forced peripheral power enable after second wake attempt");
                    }
                }).start();
            }
        }
    }

    // ==================== ESCO-PARITY MCU POWER HOLD (DILINK 4) ====================

    // PowerDevice eventId 0xAA00004A = -1442840502. On byd_apa firmware the
    // MCU governs the AVM/ISP power rail; without this set=1 write on the
    // sentry-wake path the rail collapses post ACC OFF and the AVMCamera
    // handle delivers all-zero buffers (size <= 1.9 KB encoded H.264).
    //
    // Esco kh/C6861d.java m30178I (line 344) writes intValue=1 on wake and
    // m30176G writes intValue=0 on sleep. We already write 0 on disable
    // (configurePeripheralPower DISABLE branch). The matching set=1 was
    // missing on the enable branches, by mistake.
    //
    // Gated to dilink4 — legacy secondary-reference path stays bit-exact unchanged.
    private static final int ESCO_MCU_POWER_HOLD_ID = -1442840502;

    private static void applyEscoMcuPowerHold(boolean enable) {
        if (!isDilink4CameraMode()) return;
        if (enable) {
            log("[esco-parity] McuStatus = ON (PowerDevice -1442840502 = 1)");
            setPowerConfig(ESCO_MCU_POWER_HOLD_ID, 1);
        }
        // Disable path is already covered in configurePeripheralPower's
        // DISABLE branch via setPowerConfig(-1442840502, 0); no separate
        // call needed here. Kept symmetric for future callers.
    }

    // ==================== V1-PARITY UNCONDITIONAL POWER HOLD (DILINK 4) ====================

    /**
     * V1-parity MCU power hold: write {@code -1442840502 = 1} IMMEDIATELY and
     * UNCONDITIONALLY on the ACC-OFF path, with no MCU-status precondition.
     *
     * <p><b>Why this exists.</b> We ported the reference app's
     * BatteryVoltageMonitor<b>V2</b> (its {@code kh/d}) and wired it up as if it
     * were the shipping behaviour. It is not. The reference app selects its
     * monitor at runtime from the {@code key.flameout.wakeup.mode} preference
     * ({@code kh/a.c()}), and that preference <b>defaults to 0 → V1</b>
     * ({@code vj/a.m()} returns {@code d("key.flameout.wakeup.mode", 0)}).
     *
     * <p>V1 ({@code kh/b}) is drastically simpler than what we implemented — its
     * whole ACC-off behaviour is a single unconditional write:
     * <pre>
     *   BYDAutoEventValue v; v.intValue = 1;
     *   powerDevice.set(new int[]{-1442840502}, v);   // "wakeUp"
     * </pre>
     * and it <b>never sleeps the MCU at all</b> — no voltage hysteresis, no
     * 15-minute deferred sleep, no {@code getMcuStatus} gate.
     *
     * <p>Our equivalent write ({@link #applyEscoMcuPowerHold}) only fires from
     * inside {@link #configurePeripheralPower}'s branches, all of which are
     * predicated on {@code getMcuStatus()} reading 1 or 10 (or on a wake-retry
     * succeeding). When the MCU reports any other status the hold was never
     * written, so on DiLink 4 the AVM/ISP rail could collapse right after ACC OFF
     * and every subsequent AVMCamera frame came back all-zero.
     *
     * <p>Idempotent (it is the same value the status-gated path writes when it
     * does run), cheap (one binder write), and gated to
     * {@code cameraMode=dilink4} so the legacy fleet's sequence is byte-identical.
     */
    private static void applyV1ParityMcuPowerHold() {
        if (!isDilink4CameraMode()) return;
        log("[v1-parity] unconditional MCU power hold (PowerDevice -1442840502 = 1) "
            + "— no MCU-status precondition, mirrors reference-app default mode 0 (V1)");
        setPowerConfig(ESCO_MCU_POWER_HOLD_ID, 1);
    }

    // NOTE: the matching "never sleep the MCU on dilink4" gate lives in
    // BatteryVoltageMonitorV2.isDilink4CameraMode() — that class deliberately
    // reads the config itself rather than calling across into this daemon
    // (it must work in whichever process boots it), exactly as it already does
    // for isKeepUsbPowerOnAccOff().

    // ==================== ESCO-PARITY SENTRY KEYS (DILINK 4) ====================

    // Esco's BatteryVoltageMonitorV2 sentry keep-alive IDs. Different magic
    // numbers from our 782237711 / 782237728 (which are secondary-reference-derived, kept
    // additive and unchanged for legacy fleet). On byd_apa firmware the
    // AVMCamera HAL gates frame production on these specific BYD-internal
    // peripheral-power flags being held active; without them the producer
    // surface delivers all-zero pixels post ACC OFF.
    //
    // ENABLE  (esco kh/C6861d.java m30171B "sentry wakeUp"): [1901]=1, [1902]=1
    // DISABLE (esco kh/C6861d.java m30170A "sentry sleep"):  [1901]=0, [1902]=2
    //
    // Gated to cameraMode=dilink4. Legacy cars don't read or write these.
    private static final int ESCO_SENTRY_KEY_1 = 1901;
    private static final int ESCO_SENTRY_KEY_2 = 1902;

    private static void applyEscoSentrySpecialConfig(boolean enable) {
        if (!isDilink4CameraMode()) return;
        if (enable) {
            log("[esco-parity] sentry wakeUp: SpecialDevice [1901]=1, [1902]=1");
            setSpecialConfig(ESCO_SENTRY_KEY_1, 1);
            setSpecialConfig(ESCO_SENTRY_KEY_2, 1);
        } else {
            log("[esco-parity] sentry sleep: SpecialDevice [1901]=0, [1902]=2");
            setSpecialConfig(ESCO_SENTRY_KEY_1, 0);
            setSpecialConfig(ESCO_SENTRY_KEY_2, 2);
        }
    }

    // ==================== SENTRY ISP / CAMERA POWER VOTE (409) ====================

    // The OEM BYD Sentry Mode app (com.byd.sentrymode) casts a dedicated
    // camera/ISP power vote on its sentry-arm path that NONE of our existing
    // keep-alive writes cover. The secondary-reference keys (782237711/782237728) hold the
    // 5V/modem/USB rails; the esco keys (1901/1902) and MCU hold (-1442840502)
    // hold the byd_apa AVM rail on dilink4. But the OEM's "409" pair is the
    // sentry-mode CAMERA/ISP power request specifically. Without it, on certain
    // trims the AVM ISP rail power-gates after ~30-35 min of inactivity and
    // AVMCamera frames go all-black — recovering only when the OEM Sentry app
    // (or anything) re-casts this vote. That recovery-on-OEM-event is exactly
    // the symptom we set out to fix; replicating the vote ourselves makes our
    // feed self-sustaining without depending on the OEM app being armed.
    //
    // OEM source (decompiled com.byd.sentrymode):
    //   AutoApiManager.set409Value(v):       SpecialDevice[0x4090103E] = v
    //   AutoApiManager.set409SentryState(v): SpecialDevice[0x4090103C] = v
    //   SentryModeFuncRequest:451   set409Value(1); set409SentryState(1);  (arm)
    //   AutoApiManager:976-977      set409Value(0); set409SentryState(0);  (exit)
    //   The OEM gates set409Value on isMcuWake()/wakeUpMcu(); our callers
    //   (configurePeripheralPower / keep-alive) only fire this after the MCU is
    //   already confirmed awake, so no extra wake is needed here.
    //
    // Applied FLEET-WIDE (NOT gated to dilink4): the OEM writes these from the
    // generic AutoApiManager with no car-type gate, and the black-frame symptom
    // spans multiple models — gating to dilink4 would miss affected legacy
    // trims. setSpecialConfig is best-effort, so an unsupported key on a given
    // trim is a logged no-op; this is safe additive coverage for legacy too.
    // 0x4090103E = 1083183166, 0x4090103C = 1083183164.
    private static final int SENTRY_ISP_NEED_409_SET   = 0x4090103E;
    private static final int SENTRY_ISP_WORK_STATE_SET = 0x4090103C;

    private static void applySentryIspPowerVote(boolean enable) {
        int v = enable ? 1 : 0;
        log("[sentry-isp] 409 camera/ISP power vote " + (enable ? "ON" : "OFF")
            + ": SpecialDevice[0x4090103E]=" + v + " [0x4090103C]=" + v);
        setSpecialConfig(SENTRY_ISP_NEED_409_SET, v);
        setSpecialConfig(SENTRY_ISP_WORK_STATE_SET, v);
    }
    
    /**
     * Get current MCU status.
     * @return MCU status code, or -1 if unavailable
     */
    private static int getMcuStatus() {
        BYDAutoPowerDevice device = getPowerDevice();
        if (device == null) return -1;
        
        try {
            return device.getMcuStatus();
        } catch (Exception e) {
            log("getMcuStatus error: " + e.getMessage());
            return -1;
        }
    }
    
    /**
     * Wake up the MCU. Returns true on success.
     */
    private static boolean wakeUpMcu() {
        BYDAutoPowerDevice device = getPowerDevice();
        if (device == null) {
            log("wakeUpMcu: No power device available");
            return false;
        }
        
        try {
            int result = device.wakeUpMcu();
            return result == 0;
        } catch (Exception e) {
            log("wakeUpMcu error: " + e.getMessage());
            return false;
        }
    }
    
    // Lock file for singleton enforcement
    private static final String LOCK_FILE = "/data/local/tmp/acc_sentry_daemon.lock";
    private static java.io.RandomAccessFile lockFileHandle;
    private static java.nio.channels.FileLock fileLock;

    public static void main(String[] args) {
        int myUid = android.os.Process.myUid();

        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)
            .withFileLog(true)
            .withConsoleLog(true));

        logger = DaemonLogger.getInstance(TAG, PATH_DATA_LOCAL_TMP());
        
        // CRITICAL: Acquire singleton lock FIRST - exit if another instance is running
        if (!acquireSingletonLock()) {
            log("ERROR: Another AccSentryDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }

        log("=== ACC Sentry Daemon Starting ===");
        log("UID: " + myUid + " (expected: 2000 shell)");
        log("PID: " + android.os.Process.myPid());

        // Initialize unified config so calls into isSurveillanceEnabled() and
        // getSurveillanceSchedule() see the on-disk config (and trigger legacy
        // migration if needed) when AccSentryDaemon starts before CameraDaemon.
        // Idempotent — CameraDaemon also calls this.
        try {
            com.overdrive.app.config.UnifiedConfigManager.init();
        } catch (Exception e) {
            log("UnifiedConfigManager.init() failed: " + e.getMessage());
        }

        // SIGKILL recovery: clear cross-process screen-deterrent flags. Both
        // are normally cleared on the next ACC OFF (enterSentryMode lines
        // ~900-907) so this is mostly defensive — but if the daemon was
        // killed mid-exitSentryMode between the screenDeterrentForceStop=true
        // write (line ~998) and the worker thread's clear (line ~1047), and
        // the next event happens to be a manual deterrent fire while the car
        // is parked-but-already-in-sentry, the stale flag would block it.
        // Mirroring CameraDaemon.main()'s top-of-process clear keeps the
        // pair symmetric. See feedback memory: daemon-shutdown-clears-state.
        try {
            java.util.Map<String, Object> reset = new java.util.HashMap<>();
            reset.put("screenDeterrentForceStop", false);
            reset.put("screenDeterrentActiveUntilMs", 0L);
            com.overdrive.app.config.UnifiedConfigManager.updateValues(
                    "surveillance", reset);
        } catch (Throwable ignored) {}

        // Record start time for uptime tracking
        startTime = System.currentTimeMillis();

        if (myUid != 2000) {
            log("WARNING: Not running as shell (UID 2000)! Screen control may not work.");
        }

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        // Create handler for periodic status checks
        statusHandler = new android.os.Handler(Looper.myLooper());

        try {
            Context context = createAppContext();
            if (context == null) {
                log("createAppContext failed, trying getSystemContext...");
                context = getSystemContext();
            }

            if (context != null) {
                log("Got context: " + context);
                appContext = context;

                // Debug: Dump sleep reason constants to identify correct values for this firmware
                //logAllSleepReasonFields();

                // Dump all power-related methods for discovery
                //dumpPowerManagerMethods();
                //dumpBydPowerDeviceMethods();
                //dumpBydSettingDeviceMethods();
                
                // Dump all BYD device methods for discovery
                //dumpAllBydDeviceMethods();
                
                // Test instrument device (charging power)
                //testInstrumentDevice();

                // Acquire WakeLock for guaranteed CPU cycles.
                // GATE (G4a): in "Vehicle ON only" mode, do NOT hold the process-wide
                // AccSentry:Core wakelock while the car is already OFF at daemon start.
                // This matters on a watchdog/revival RESPAWN while parked: no ACC power
                // edge fires on a respawn, so enterSentryMode()'s G1 gate never runs and
                // the freshly-spawned daemon would otherwise pin the CPU awake forever.
                // probeAccState() returns true only when ACC is CONFIRMED OFF; on ACC-ON
                // or any reflection/HAL failure it returns false → we still acquire
                // (fail-open, current behaviour preserved). The wakelock is re-acquired
                // on the ACC-ON edge in exitSentryMode() (G4b).
                if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()
                        && com.overdrive.app.monitor.AccMonitor.probeAccState(appContext)) {
                    log("onOnly + ACC currently OFF: skipping core wakelock so AP can sleep");
                } else {
                    acquireWakeLock();
                }
                //forceSmartSleepReflection();
                
                // SIGKILL recovery: if the previous instance darkened the panel
                // while parked and was then killed, the backlight is still off
                // and nothing is left to wake it. Restore here when ACC reads
                // ON, so a driver can never be handed a dark panel by a daemon
                // that died. probeAccState() returns true only when ACC is
                // confirmed OFF, so `!probeAccState` is our ON signal; on any
                // HAL/reflection failure it returns false, which reads as "ON"
                // here — deliberately fail-VISIBLE: the worst case is waking a
                // parked car's panel, which the keep-alive re-darkens within one
                // 10 s tick, whereas the opposite failure (leaving it dark) is
                // not recoverable by the user. turnOn() self-skips when the
                // screen already reads on, so this is a no-op on a normal boot.
                try {
                    if (!com.overdrive.app.monitor.AccMonitor.probeAccState(appContext)) {
                        com.overdrive.app.power.StealthPanel.turnOn(appContext);
                    }
                } catch (Throwable t) {
                    log("Stealth panel boot recovery failed: " + t.getMessage());
                }

                // CRITICAL: Whitelist our app from ACC power management killing
                whitelistAppPackageOld();

                // CRITICAL: Whitelist app UID with BYD background data-cache services.
                // BgDataCacheService accepts shell UID (2000), so this only succeeds
                // when called from the daemon — not from MainActivity (UID 10xxx).
                applyDataCacheWhitelist();

                // Install shutdown hook for debugging process termination
                installShutdownHook();
                
                // Log initial memory status
                logMemoryStatus();
                
                // Start periodic status monitoring
                startStatusMonitoring();
                
                // Disable BYD traffic monitor app (consumes data/battery in background)
                // NOTE: Removed automatic disable — user can now toggle this from the app drawer menu
                // disableBydTrafficMonitor();
                
                // Note: VehicleDataMonitor is initialized in CameraDaemon (separate process)
                // which handles the HTTP API for vehicle data
            } else {
                log("WARNING: Running without context");
            }

            // Register bodywork listener for ACC state changes. On cold boot
            // BYD's bodywork service may not be up yet — historically this
            // failed silently and the daemon ran as a wakelock-holding zombie
            // forever (no ACC events ever delivered). Retry with backoff so
            // a slow service-startup window doesn't strand us.
            boolean registered = registerBodyworkListener(context);
            if (!registered && context != null) {
                final int maxRetries = 5;
                final long retryDelayMs = 5000L;
                for (int attempt = 1; attempt <= maxRetries && !registered; attempt++) {
                    log("Bodywork listener registration failed — retry "
                        + attempt + "/" + maxRetries + " in " + (retryDelayMs / 1000) + "s");
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        log("Bodywork-listener retry interrupted — proceeding without listener");
                        break;
                    }
                    registered = registerBodyworkListener(context);
                }
            }
            if (registered) {
                bodyworkRegistered = true;
                // Periodic ACC state heartbeat — repairs the wedge where
                // CameraDaemon restarts mid-drive and never receives an
                // edge-only ACC IPC. Cheap (~1 HAL probe / 30 s).
                startAccStateHeartbeat();
            } else {
                log("Bodywork listener failed after retries — starting slow-retry every 60s "
                    + "(daemon should never run without a listener if one can be eventually established)");
                startBodyworkSlowRetry(context);
            }

            // Esco-parity: ALSO register BYDAutoPowerDevice
            // onPowerCtlStatusChanged listener for event id 0x99000037
            // (= -1728053193). Esco's sentry/camera pipeline gates on this
            // signal (esco bk/C1478c.java:71-75 and p111dh/C4995i.java
            // FlameoutService). The bodywork onPowerLevelChanged signal is
            // different in timing/state from the power-ctl signal on
            // byd_apa firmware. Both listeners fan into the same
            // idempotent enterSentryMode/exitSentryMode — whichever
            // fires first wins, the other is a no-op.
            //
            // Gated to dilink4 — legacy fleet keeps the bodywork-only
            // path bit-exact unchanged.
            if (isDilink4CameraMode()) {
                try {
                    registerPowerListener(context);
                } catch (Throwable t) {
                    log("BYDAutoPowerDevice listener registration failed: " + t.getMessage());
                }
            }

            log("Daemon running, entering persistence loop...");
            
            // UNKILLABLE LOOP WRAPPER - Crash-proof main loop
            // Automatically restarts logic if a random crash occurs
            while (true) {
                try {
                    // Start the message pump. This blocks until an exception occurs.
                    Looper.loop();
                } catch (Throwable e) {
                    // Catch ANY crash (Exception or Error)
                    log("CRASH DETECTED in Main Loop: " + e.getMessage());
                    e.printStackTrace();
                    
                    // Safety pause to prevent CPU spiking if crash is repetitive
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {}
                    
                    log("Restarting message queue...");
                    if (Looper.myLooper() == null) {
                        Looper.prepare();
                    }
                }
            }

        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void log(String msg) {
        if (logger != null) {
            logger.info(msg);
        }
        // Note: System.out.println is now handled by DaemonLogger when enableStdoutLog is true
    }
    
    // ==================== SINGLETON LOCK ====================
    
    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     */
    private static boolean acquireSingletonLock() {
        try {
            java.io.File lockFileObj = new java.io.File(LOCK_FILE);
            lockFileHandle = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFileHandle.getChannel();
            
            // Try to acquire exclusive lock (non-blocking)
            fileLock = channel.tryLock();
            
            if (fileLock == null) {
                lockFileHandle.close();
                return false;
            }
            
            // Write our PID to the lock file
            lockFileHandle.setLength(0);
            lockFileHandle.writeBytes(String.valueOf(android.os.Process.myPid()));
            
            log("Acquired singleton lock (PID: " + android.os.Process.myPid() + ")");
            
            // Register shutdown hook to release lock on process termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdownDaemon();
            }, "DaemonCleanup"));
            
            return true;
            
        } catch (java.nio.channels.OverlappingFileLockException e) {
            log("Lock already held by this process");
            return false;
        } catch (Exception e) {
            log("Failed to acquire singleton lock: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Release the singleton lock on shutdown.
     */
    private static void releaseSingletonLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockFileHandle != null) {
                lockFileHandle.close();
                lockFileHandle = null;
            }
            new java.io.File(LOCK_FILE).delete();
        } catch (Exception e) {
            log("Error releasing singleton lock: " + e.getMessage());
        }
    }

    // ==================== WAKELOCK MANAGEMENT ====================

    private static synchronized void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        if (appContext == null) return;

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AccSentry:Core");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            log("WakeLock Acquired");
        } catch (Exception e) {
            log("WakeLock Error: " + e.getMessage());
        }
    }

    private static synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                log("WakeLock Released");
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    // ==================== ACC WHITELIST ====================
    /**
     * Whitelist app package from ACC power management killing.
     * 
     * Loads the real system IAccModeManager$Stub via Class.forName from the boot
     * classloader, guaranteeing the correct transaction code is used.
     * 
     * Fallback: direct binder transact with TX code 2 (confirmed working).
     */
    private static void whitelistAppPackageOld() {
        String pkg = APP_PACKAGE_NAME();
        log("Whitelisting package " + pkg + " via accmodemanager...");

        boolean success = false;

        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, SERVICE_ACCMODEMANAGER());

            if (binder != null) {
                log("Got accmodemanager binder: " + binder);

                // === STRATEGY 1: Load real system stub via Class.forName ===
                try {
                    Class<?> stubClass = Class.forName("android.os.IAccModeManager$Stub");
                    Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                    Object manager = asInterface.invoke(null, binder);

                    if (manager != null) {
                        Method setPkg = manager.getClass().getMethod("setPkg2AccWhiteList", String.class);
                        setPkg.invoke(manager, pkg);
                        log("Whitelisted successfully via system stub!");
                        success = true;
                    } else {
                        log("System stub asInterface returned null");
                    }
                } catch (Exception e) {
                    String msg = (e.getCause() != null) ? e.getCause().getMessage() : e.getMessage();
                    log("System stub method failed: " + msg);
                }

                // === STRATEGY 2: Direct binder transact with known TX code 2 ===
                if (!success) {
                    log("System stub failed, trying direct transact (TX code 2)...");
                    success = whitelistViaDirectTransact(binder, pkg);
                }

            } else {
                log("accmodemanager service not found");
            }
        } catch (Exception e) {
            log("Binder Access Error: " + e.getMessage());
            e.printStackTrace();
        }

        if (!success) {
            log("WARNING: All whitelist strategies failed - app may be killed during ACC OFF");
        }
    }

    /**
     * Direct binder transact fallback using TX code 2 (confirmed working).
     * If TX code 2 fails, scans codes 1-5 for firmware variations.
     */
    private static boolean whitelistViaDirectTransact(IBinder binder, String packageName) {
        if (tryTransactCode(binder, packageName, 2)) {
            return true;
        }

        log("TX code 2 failed, scanning codes 1-5...");
        for (int code = 1; code <= 5; code++) {
            if (code == 2) continue;
            if (tryTransactCode(binder, packageName, code)) {
                return true;
            }
        }

        log("Direct transact: no working transaction code found");
        return false;
    }

    private static boolean tryTransactCode(IBinder binder, String packageName, int code) {
        try {
            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("android.os.IAccModeManager");
                data.writeString(packageName);

                boolean transactSuccess = binder.transact(code, data, reply, 0);
                if (transactSuccess) {
                    reply.readException();
                    log("Whitelist SUCCESS with transaction code " + code);
                    return true;
                }
            } catch (Exception e) {
                log("TX code " + code + ": " + e.getMessage());
            } finally {
                data.recycle();
                reply.recycle();
            }
        } catch (Exception e) {
            // Parcel obtain failed
        }
        return false;
    }

    // ==================== DATA-CACHE WHITELIST ====================
    /**
     * Whitelist app UID with BYD background data-cache services.
     *
     * BYD's BgDataCacheService accepts the shell UID (2000), so calls from this
     * daemon succeed where the same call from MainActivity (UID 10xxx) hits the
     * AppOps gate. Mirrors the secondary reference app's vanss daemon, which arrives at shell UID via
     * an ADB-localhost tunnel and then makes this exact call.
     *
     * SDK ≥ 31 → byd_datacached.setAppStartupData(uid, 0)
     * SDK < 31 → bg_datacache.setAppOpsData(uid, 0)
     *
     * Threshold matches esco's C0241c.m941c() — earlier we used >= 32, but
     * BYD DiLink 4 ROMs that ship Android 12 (API 31) base have the new
     * byd_datacached service available, and the old bg_datacache.setAppOpsData
     * gates on ACCESS_APPOPSDATA (denied to shell UID 2000 → frames all-black
     * post ACC OFF on byd_apa).
     */
    private static void applyDataCacheWhitelist() {
        if (appContext == null) {
            log("applyDataCacheWhitelist: no context");
            return;
        }

        String pkg = APP_PACKAGE_NAME();
        int appUid;
        try {
            appUid = appContext.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Exception e) {
            log("applyDataCacheWhitelist: failed to resolve UID: " + e.getMessage());
            return;
        }
        String uidStr = String.valueOf(appUid);
        log("Applying data-cache whitelist for " + pkg + " (uid=" + appUid + ")");

        Context permissiveContext = new PermissionBypassContext(appContext);
        // Probe both services — esco gates on SDK_INT >= 31, but some BYD
        // ROMs (DiLink 4 on Android 11 base, build markers report SDK 30
        // even though the BYD branding says "DiLink 4") expose
        // byd_datacached without bumping SDK_INT. Try the new service
        // unconditionally and only fall through to the legacy service when
        // it returns null. Removes the false negative we hit when SDK is
        // exactly 30 but byd_datacached IS available.
        log("Data-cache whitelist: SDK_INT=" + android.os.Build.VERSION.SDK_INT
            + " — probing byd_datacached first regardless");

        try {
            Object service = permissiveContext.getSystemService(SERVICE_BYD_DATACACHE());
            if (service != null) {
                Method m = service.getClass().getMethod("setAppStartupData", String.class, Integer.TYPE);
                m.invoke(service, uidStr, 0);
                log("setAppStartupData OK (uid=" + appUid + ")");
                return;
            }
            log("byd_datacached service unavailable — falling through to bg_datacache");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            log("setAppStartupData rejected: " + ite.getCause());
        } catch (NoSuchMethodException nsme) {
            log("setAppStartupData method not on this ROM: " + nsme.getMessage());
        } catch (Exception e) {
            log("setAppStartupData failed: " + e.getMessage());
        }

        try {
            Object service = permissiveContext.getSystemService(SERVICE_BG_DATACACHE());
            if (service != null) {
                Method m = service.getClass().getMethod("setAppOpsData", String.class, Integer.TYPE);
                m.invoke(service, uidStr, 0);
                log("setAppOpsData OK (uid=" + appUid + ")");
                return;
            }
            log("bg_datacache service unavailable");
        } catch (java.lang.reflect.InvocationTargetException ite) {
            log("setAppOpsData rejected: " + ite.getCause());
        } catch (Exception e) {
            log("setAppOpsData failed: " + e.getMessage());
        }
    }

    // ==================== ACC STATE DETECTION ====================

    private static boolean registerBodyworkListener(Context context) {
        if (context == null) return false;

        try {
            log("Registering bodywork listener...");

            Class<?> deviceClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, context);

            if (device == null) {
                log("BYDAutoBodyworkDevice.getInstance returned null");
                return false;
            }

            log("Got bodywork device: " + device);

            Class<?> listenerClass = Class.forName("android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener");
            Method registerListener = deviceClass.getMethod("registerListener", listenerClass);

            AccListener listener = new AccListener();
            registerListener.invoke(device, listener);

            log("Bodywork listener registered!");

            // Get initial power level
            try {
                Method getPowerLevel = deviceClass.getMethod("getPowerLevel");
                int level = (Integer) getPowerLevel.invoke(device);
                log("Initial power level: " + powerLevelToString(level));

                if (level < 0 || level > 3) {
                    // HAL is bluffing on first probe (FAKE_OK=4, INVALID=255,
                    // or anything else). Don't seed lastPowerLevel with
                    // garbage and don't push a misleading IPC. The first
                    // real onPowerLevelChanged event will set the correct
                    // initial value; until then the daemon defers to its
                    // own conservative defaults (no sentry, no notify).
                    // lastPowerLevel stays at its class-default (-1, see
                    // field declaration above). On the first real event:
                    //   - real OFF(0) → 0 != -1 → enterSentryMode (correct)
                    //   - real ON(2/3) → 2 >= 2 && -1 < 2 → exitSentryMode,
                    //     which is a no-op because !inSentryMode (correct)
                    // Both branches converge on the right state via the
                    // existing idempotency guards in enter/exitSentryMode.
                    log("Initial power level is sentinel — deferring state seed; "
                            + "first real onPowerLevelChanged event will initialize");
                } else {
                    lastPowerLevel = level;
                    if (level == POWER_LEVEL_OFF) {
                        log("Started with ACC OFF - entering sentry mode");
                        enterSentryMode();
                    } else {
                        // ACC is ON - notify CameraDaemon so AccMonitor has correct state
                        log("Started with ACC ON - notifying CameraDaemon");
                        notifyAccState(false);  // accOff=false means ACC is ON
                    }
                }
            } catch (Exception e) {
                log("Could not get initial power level: " + e.getMessage());
            }

            return true;

        } catch (Exception e) {
            log("Bodywork registration failed: " + e.getMessage());
            return false;
        }
    }

    // ==================== ESCO-PARITY POWER LISTENER ====================

    /** BYD power-ctl event id 0x99000037 = -1728053193, value 0=ACC OFF,
     *  value 1=ACC ON. Source: esco bk/C1478c.java:71-75. */
    private static final int POWER_CTL_EVENT_ACC = -1728053193;

    /** Register esco-style BYDAutoPowerDevice.onPowerCtlStatusChanged
     *  listener via reflection. Runs in parallel with the bodywork
     *  listener; whichever fires first wins. */
    private static boolean registerPowerListener(Context context) {
        if (context == null) return false;
        try {
            log("Registering BYDAutoPowerDevice listener (esco-parity)...");

            Class<?> deviceClass = Class.forName(
                "android.hardware.bydauto.power.BYDAutoPowerDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, context);
            if (device == null) {
                log("BYDAutoPowerDevice.getInstance returned null");
                return false;
            }

            Class<?> listenerClass = Class.forName(
                "android.hardware.bydauto.power.AbsBYDAutoPowerListener");
            Method registerListener = deviceClass.getMethod(
                "registerListener", listenerClass);

            // SDK stub for AbsBYDAutoPowerListener is in our tree at
            // android/hardware/bydauto/power/AbsBYDAutoPowerListener.java
            // — at runtime BYD's bmmcamera.jar provides the real class.
            // Subclass directly; if the runtime class signature differs
            // we'll catch via the outer try/catch.
            registerListener.invoke(device, new EscoStylePowerListener());
            log("BYDAutoPowerDevice listener registered (esco-parity)");
            return true;
        } catch (ClassNotFoundException cnf) {
            log("BYDAutoPowerDevice classes not on this ROM: " + cnf.getMessage());
            return false;
        } catch (Exception e) {
            log("registerPowerListener failed: " + e.getMessage());
            return false;
        }
    }

    /** Concrete subclass of AbsBYDAutoPowerListener that drives the
     *  same enterSentryMode/exitSentryMode as the bodywork listener.
     *  Mirrors esco bk/C1478c.java:65-77. The bytecode is loaded by the
     *  daemon process against BYD's runtime class via the SDK stub. */
    private static class EscoStylePowerListener
        extends AbsBYDAutoPowerListener {
        @Override
        public void onPowerCtlStatusChanged(int eventId, int value) {
            if (eventId == POWER_CTL_EVENT_ACC) {
                log(">>> POWER CTL: " + (value == 0 ? "ACC OFF" : "ACC ON")
                    + " (event=0x" + Integer.toHexString(eventId)
                    + ", value=" + value + ")");
                if (value == 0) {
                    enterSentryMode();
                } else if (value == 1) {
                    exitSentryMode();
                }
            }
        }
    }

    private static class AccListener extends AbsBYDAutoBodyworkListener {
        @Override
        public void onPowerLevelChanged(int level) {
            log(">>> POWER LEVEL: " + powerLevelToString(level) + " (was: " + powerLevelToString(lastPowerLevel) + ")");

            // Reject sentinel readings (FAKE_OK=4, INVALID=255, or any
            // value outside the documented 0..3 range). The HAL emits
            // these when state is transiently unreliable. Treating them
            // as ACC=ON (because they satisfy `>= POWER_LEVEL_ON`) would:
            //   1. Falsely exit sentry mode on a HAL bluff → trigger
            //      surveillance teardown + IPC to AccMonitor saying
            //      "ACC ON" → false recording activation downstream.
            //   2. Cache 255 into `lastPowerLevel` → the NEXT legitimate
            //      ACC=ON event would silently fail because
            //      `level >= 2 && lastPowerLevel < 2` evaluates false
            //      (255 is not < 2). The daemon would stay stuck in
            //      sentry until the next OFF/ON cycle.
            // So we drop the event entirely — don't fire any transition,
            // don't update lastPowerLevel. The next legitimate reading
            // will compare against the actual prior state.
            if (level < 0 || level > 3) {
                log("Sentinel power level " + powerLevelToString(level)
                        + " — ignoring (lastPowerLevel stays "
                        + powerLevelToString(lastPowerLevel) + ")");
                return;
            }

            if (level == POWER_LEVEL_OFF && lastPowerLevel != POWER_LEVEL_OFF) {
                log("ACC OFF detected");
                enterSentryMode();
            } else if (level >= POWER_LEVEL_ON && lastPowerLevel < POWER_LEVEL_ON) {
                log("ACC ON detected");
                exitSentryMode();
            } else if (level == POWER_LEVEL_ACC && lastPowerLevel >= POWER_LEVEL_ON) {
                // BYD app scenario: car was ON (level 2+) and dropped to ACC (level 1)
                // This is a "turning off" transition — treat as ACC OFF for sentry purposes.
                // Without this, a brief BYD app wake (OFF→ON→ACC→OFF) leaves AccMonitor
                // stuck showing ACC ON because exitSentryMode fired but enterSentryMode
                // only triggers on level 0.
                log("ACC level dropped from ON to ACC — treating as ACC OFF (BYD app shutdown)");
                enterSentryMode();
            }

            lastPowerLevel = level;
        }

        @Override
        public void onAutoSystemStateChanged(int state) {
            log("System state: " + state);
        }

        @Override
        public void onBatteryVoltageLevelChanged(int level) {
            // Discrete level callback (0=LOW, 1=NORMAL)
            // Actual voltage monitoring is done via polling in manageMcuPowerState()
            String levelName = (level == 0) ? "LOW" : (level == 1) ? "NORMAL" : "INVALID";
            log("Car battery level: " + levelName);
            
            // Emergency action on LOW level.
            // GATE (onOnly): this HAL-delivered low-battery callback is event-driven and
            // independent of the SentrySetup worker that G1 gates — AccListener is
            // registered unconditionally at daemon init and inSentryMode stays true for
            // the whole park, so without this guard forceMcuWakeUp() would fire in onOnly
            // and (with the default Keep-USB toggle ON) force the AP awake via
            // performSystemWakeUp(), defeating "let the head unit sleep". In onOnly the
            // daemon deliberately runs no post-OFF voltage management (G1 skipped
            // initVehicleDataMonitor / BatteryVoltageMonitorV2), so this emergency wake is
            // suppressed too — the system is meant to sleep and rely on the vehicle's own
            // BMS for low-SoC protection, consistent with the mode's contract.
            if (level == 0 && inSentryMode
                    && !com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
                log("CRITICAL: Battery level LOW - triggering emergency wake");
                forceMcuWakeUp();

                if (surveillanceEnabled) {
                    // Dispatch disableSurveillance off the BYD HAL listener thread
                    // — it does an IPC with up to ~11s of retry sleep
                    // (sendSurveillanceCommandRaw 2× attempts × 5s SoTimeout +
                    // 1s backoff). Stalling the listener here drops subsequent
                    // ACC edges. FIFO single-thread accNotifyExecutor preserves
                    // ordering against any in-flight notifyAccState. See
                    // prior-audit "AccListener.onBatteryVoltageLevelChanged
                    // calls disableSurveillance inline".
                    log("LOW BATTERY - Dispatching surveillance disable off HAL listener thread");
                    try {
                        accNotifyExecutor.execute(() -> {
                            try {
                                disableSurveillance();
                            } catch (Throwable t) {
                                log("WARN: low-battery disableSurveillance failed: " + t.getMessage());
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException ree) {
                        // Executor shut down (process tearing down). Run inline as
                        // last-chance attempt; we'd rather block briefly than
                        // miss the disable on shutdown.
                        log("WARN: accNotifyExecutor rejected low-battery disable, running inline: "
                            + ree.getMessage());
                        disableSurveillance();
                    }
                }
            }
        }
    }

    // ==================== VOLTAGE HYSTERESIS STATE (REPLACED) ====================
    //
    // Replaced by BatteryVoltageMonitorV2 (12.0/12.5 V thresholds, 15 min
    // sleep-defer). The local copy is kept commented as a reference only.
    //
    // private static volatile boolean isVoltageChargingCycle = false;
    // private static final double LOW_VOLTAGE_THRESHOLD = 12.1;      // Wake Trigger (Volts)
    // private static final double HEALTHY_VOLTAGE_THRESHOLD = 12.8;  // Sleep Trigger (Volts)
    
    // VehicleDataMonitor listener for voltage-based MCU control
    private static VehicleDataListener vehicleDataListener = null;

    private static String powerLevelToString(int level) {
        switch (level) {
            case POWER_LEVEL_OFF: return "OFF";
            case POWER_LEVEL_ACC: return "ACC";
            case POWER_LEVEL_ON: return "ON";
            case POWER_LEVEL_OK: return "OK";
            default: return "UNKNOWN(" + level + ")";
        }
    }

    // ==================== SENTRY MODE ====================

    /**
     * Enter Sentry Mode - The "car is off but watching" state.
     * 
     * CRITICAL SEQUENCE (order matters for power stability):
     * 1. Initialize voltage monitoring FIRST
     * 2. Wake MCU immediately (triggers DC-DC converter)
     * 3. THEN wake the system (screen/CPU)
     * 4. Start the keep-alive loop (maintains the wake state)
     * 5. Enable surveillance AFTER power is stable
     */
    private static void enterSentryMode() {
        if (inSentryMode) {
            log("Already in sentry mode");
            return;
        }

        inSentryMode = true;
        log("=== ENTERING SENTRY MODE ===");

        // ORDERING FIX (DiLink 4 only): cast the MCU/AVM power hold BEFORE the IPC
        // that makes CameraDaemon (re)open AVMCamera.
        //
        // The reference app's FlameoutService orders ACC-OFF strictly as
        // "hold power, THEN start the sentry camera consumer" (dh/i.q(): y() →
        // MCU wake at delay 0, then w(60000) → sentry camera start).
        //
        // Ours had no such ordering guarantee. notifyAccState(true) below hands the
        // IPC to a single-thread executor, and CameraDaemon then runs
        // startSentryPipeline → gpuPipeline.start() → AVMCamera open in ITS process.
        // Meanwhile our rail writes only began after this method spawned the
        // SentrySetup worker, behind ~300ms + 500ms of sleeps. The two sequences are
        // concurrent and unsynchronised, so the camera could — and on byd_apa
        // evidently did — get opened against an AVM/ISP rail that had not been held
        // yet, after which the HAL hands back all-zero buffers for the whole park.
        //
        // Casting the hold here — inline, before the IPC is even queued — makes the
        // rail live before CameraDaemon can possibly receive the ACC-OFF edge, which
        // is the ordering the reference app gets for free by holding power first. It
        // adds NO delay to the arm path (the deliberate "no 60s gate on dilink4"
        // decision in CameraDaemon.onAccStateChanged stays intact — this is a
        // reorder, not a delay). The SentrySetup worker still re-applies the full
        // rail set afterwards; every write involved is idempotent.
        //
        // COST: this runs on the BYD HAL listener thread, which is single-threaded —
        // stalling it makes the HAL drop later ACC edges. Five binder writes (MCU
        // hold, 1901/1902, the 409 pair) plus a one-time device resolution on first
        // call. No sleeps, no shell, no disk, no config writes. That is the same
        // order of work the listener already does elsewhere on this path.
        // No-op on every non-dilink4 variant.
        //
        // GATE (G1 parity): ALSO skipped in "Vehicle ON only" mode. That mode's whole
        // contract is that nothing keeps the head unit awake after vehicle-OFF, and
        // holding the MCU / AVM / ISP rails is precisely such a keep-awake. The G1
        // check below (and the one in the SentrySetup worker) would otherwise be
        // defeated by these writes, because they execute BEFORE it. Read here rather
        // than relying on the later gate — same mtime-cached read, fail-open to
        // "not onOnly" so a read glitch can never silently suppress the fix.
        if (isDilink4CameraMode()
                && !com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
            try {
                applyV1ParityMcuPowerHold();         // PowerDevice -1442840502 = 1
                applyEscoSentrySpecialConfig(true);  // SpecialDevice 1901=1, 1902=1
                applySentryIspPowerVote(true);       // SpecialDevice 409 camera/ISP vote
                log("[dilink4] pre-IPC rail hold cast before camera arm");
            } catch (Throwable t) {
                log("[dilink4] pre-IPC rail hold failed: " + t.getMessage());
            }
        }

        // CRITICAL: Always notify CameraDaemon that ACC is OFF immediately.
        // enableSurveillance() may skip the IPC if surveillanceEnabled is already true
        // or if the user has surveillance disabled in config, which would leave
        // AccMonitor stuck showing ACC ON (e.g. when parked in a safe zone).
        // This mirrors exitSentryMode() which also calls notifyAccState() first.
        notifyAccState(true);  // accOff=true → ACC is OFF

        // GATE (G4c): in onOnly, release the core wakelock HERE — inline on the BYD HAL
        // listener thread — not on the SentrySetup worker below. exitSentryMode()'s
        // re-acquire (G4b) also runs on this listener thread, and the BYD bodywork
        // callbacks are single-threaded, so releasing here is strictly serialized against
        // the next ACC-ON acquire: OFF releases, next ON re-acquires, no interleave. Doing
        // the release on the async worker (as the first cut did) raced a rapid ACC-ON that
        // could re-acquire via G4b and flip inSentryMode=false, after which the lagging
        // worker would clobber the lock for the whole ON session. Guarded on inSentryMode
        // (still true here — we set it above and the listener is single-threaded) purely
        // for symmetry with the onAndOff path. onAndOff never enters this branch.
        if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
            releaseWakeLock();
            log("onOnly mode: released core wakelock on ACC-OFF (inline) — letting head unit sleep");
            // TERMINATE-ON-PARK: launch the detached reaper that plants the parked marker,
            // removes the watchdog start-scripts, waits a grace window for CameraDaemon's
            // own graceful self-terminate (parkTerminate closes its H2 cleanly) and for the
            // watchdogs to gate-exit on the marker, then backstop-kills any survivor and
            // self-deletes. The reaper reparents to init (nohup &) so it outlives THIS
            // acc_sentry_daemon, which it will also kill. We do NOT run the keep-awake
            // SentrySetup worker below in onOnly. notifyAccState(true) above already told
            // CameraDaemon to finalize + self-terminate; the reaper handles everything else.
            launchParkReaper();
            return;
        }

        // Background thread for setup
        new Thread(() -> {
            try {
                // 0. Clear any stale screen-deterrent stop flag from the previous
                // sentry session. exitSentryMode sets it; the ScreenWake worker
                // clears it on its way out, but if the daemon was killed mid-exit
                // the flag may linger. Without this clear, the first motion in
                // this session would see screenDeterrentForceStop=true and refuse
                // to render. Coalesced into a single updateValues call (full-JSON
                // write) and dispatched off the BYD HAL listener thread per
                // prior-audit "enterSentryMode does two inline UnifiedConfig
                // .updateValues writes on BYD HAL listener thread".
                try {
                    java.util.Map<String, Object> deterrentClear = new java.util.HashMap<>();
                    deterrentClear.put("screenDeterrentForceStop", false);
                    deterrentClear.put("screenDeterrentActiveUntilMs", 0L);
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance", deterrentClear);
                    log("Cleared stale screen-deterrent flags on SentrySetup worker (coalesced)");
                } catch (Throwable t) {
                    log("WARN: failed to clear stale screen-deterrent flags: " + t.getMessage());
                }

                // GATE (G1): "Vehicle ON only" operating mode. When the user selected
                // onOnly, EVERYTHING below this point is post-vehicle-OFF keep-awake and
                // must NOT run — the head unit is allowed to sleep normally when the car
                // is off. We still ran notifyAccState(true) inline above (line ~1287) so
                // CameraDaemon finalises the last drive segment, stops pollers, and
                // mounts the SD card for playback; only the keep-awake stack
                // (MCU wake, peripheral/USB/AP wake, keep-alive loop, voltage + SoC
                // monitors, Telegram auto-start) is skipped. Read fresh (mtime-gated) so
                // a Settings toggle applies on this next ACC-OFF without a daemon restart.
                // Fail-open: a read glitch returns false → full keep-awake, never a
                // silent battery-drain suppression for an on-and-off user.
                if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
                    // The core wakelock was already released inline on the listener thread
                    // (G4c, in enterSentryMode before this worker was spawned) so it can't
                    // race a concurrent ACC-ON re-acquire. Here the worker just skips the
                    // entire post-OFF keep-awake stack so the head unit can sleep.
                    log("onOnly mode: skipping post-OFF keep-awake stack — letting head unit sleep");
                    return;
                }

                // 1. Initialize voltage monitoring FIRST (for battery protection)
                initVehicleDataMonitor();

                // 1a. DiLink 4 only: V1-parity unconditional MCU power hold, cast
                // BEFORE anything that can early-return or depend on MCU status.
                // The reference app's DEFAULT flameout mode (0 → V1) does exactly
                // this one write and nothing else; our status-gated equivalent
                // inside configurePeripheralPower could skip it entirely when
                // getMcuStatus() reported an unexpected value, letting the AVM/ISP
                // rail collapse. No-op on every other variant.
                applyV1ParityMcuPowerHold();

                // 2. Wake MCU immediately (triggers DC-DC converter for stable power)
                immediateWakeUpMcu();

                // 3. Configure peripheral power to keep camera/ISP/AVM rails active.
                // ALWAYS enabled and identical in both toggle states — all rail writes
                // (esco AVM keys, MCU hold, 409 ISP vote, 5V sentry keep-alive, USB/modem
                // rail) are unconditional so cameras never go dark. The "Keep USB powered"
                // toggle does NOT touch these; it only gates performSystemWakeUp() below.
                configurePeripheralPower(true);

                // 4. Small delay to let MCU stabilize power rails
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}

                // 4. THEN wake the system (screen/CPU) — GATED ON THE "Keep USB powered"
                // TOGGLE. On DiLink 3.0 the USB-port VBUS follows the AP wake state, not
                // the SpecialDevice rail registers (rail writes proven ineffective
                // on-device). Forcing wakefulness=Awake here is what keeps USB powered
                // while parked. When the user turns "Keep USB powered" OFF we SKIP the
                // forced wake so the AP can sleep on ACC-OFF and USB drops, saving the
                // 12 V battery. Default ON → identical to the pre-toggle behaviour.
                boolean keepUsbPowered = isKeepUsbPowerOnAccOff();
                if (keepUsbPowered) {
                    performSystemWakeUp();
                } else {
                    log("Keep-USB-power OFF: skipping forced system wake so AP can sleep "
                        + "(USB drops). HAL events still wake on demand.");
                }

                // 5. Start the keep-alive loop. Still started even when USB-power is OFF:
                // it re-asserts the OEM 409 camera/ISP vote and (dilink4) the AVC
                // keep-alive that surveillance needs. Its userActivity() injection,
                // which resets the AP sleep timer, is itself gated on the toggle inside
                // the loop, so when USB-power is OFF the loop maintains the camera rail
                // without re-forcing the AP awake — letting the AP sleep and USB drop.
                startSystemKeepAlive();

                // 5a. Schedule the V2 voltage monitor to start 35 s after ACC=OFF.
                // Sibling-app trace: gives the head unit time to settle before
                // we start polling MCU sleep/wake decisions. The monitor
                // owns the new "RemoteMonitorWakeLock" + 12.0/12.5 V hysteresis
                // model that replaces our old 45 s pulse loop.
                scheduleBatteryVoltageMonitorV2();

                // 5b. Start the SoC-based voluntary cutoff watcher. Listens
                // to BYDAutoStatisticDevice.onElecPercentageChanged and
                // self-shuts-down at <=10% SoC after a 60 s grace.
                // Wrap appContext in PermissionBypassContext per the same
                // pattern the rest of the daemon uses for BYD HAL access.
                try {
                    Context socCtx = new PermissionBypassContext(appContext);
                    com.overdrive.app.power.SocCutoffMonitor.startMonitor(socCtx);
                } catch (Throwable t) {
                    log("SocCutoffMonitor start failed: " + t.getMessage());
                }

                // 6. Another small delay to let power stabilize before surveillance
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                
                // 7. Register door lock listener and wait for lock before arming surveillance.
                // When ACC goes OFF and you exit the car, motion detection would pick you up
                // Door lock gate is now handled by CameraDaemon (which has the cloud MQTT
                // subscriber running in-process). CameraDaemon arms/disarms surveillance
                // based on lock/unlock events after receiving the ACC OFF notification above.
                // AccSentryDaemon no longer needs to manage lock detection or surveillance IPC.
                log("Door lock gate delegated to CameraDaemon (cloud MQTT in-process)");
                
                // 8. Optional: Telegram daemon (in separate try-catch so surveillance failure doesn't block it)
                try {
                    startTelegramDaemonIfEnabled();
                } catch (Throwable t) {
                    log("Telegram daemon start failed: " + t.getMessage());
                }
                
                log("Sentry mode setup complete");
                
            } catch (Throwable t) {
                log("CRITICAL: Sentry setup failed: " + t.getMessage());
                t.printStackTrace();
                // Don't exit sentry mode - keep-alive may still work
            }
        }, "SentrySetup").start();
        
        log("Sentry mode ACTIVE");
    }

    /**
     * Exit Sentry Mode - Restore normal operation.
     *
     * Listener-thread contract: BYD bodywork callbacks are single-threaded.
     * Anything that does shell exec, Process.waitFor, Thread.join, binder
     * reflection, or UnifiedConfig disk writes MUST be dispatched off this
     * thread or the next ACC edge will queue behind us. The state flips
     * (inSentryMode/surveillanceEnabled) and the dispatch decision happen
     * inline; everything heavy runs on SentryTeardown.
     */
    private static void exitSentryMode() {
        // GATE (G4b): re-acquire the core wakelock on the ACC-ON edge. In onOnly mode
        // G4a may have skipped the acquisition at daemon start (ACC was OFF), so the ON
        // session needs it back for reliable CPU cycles. Placed BEFORE the !inSentryMode
        // early-return on purpose: on a respawn-while-parked (ACC OFF at boot → sentry
        // was never entered → inSentryMode is false), the subsequent ACC-ON still calls
        // exitSentryMode() and must re-arm here. acquireWakeLock() is idempotent (guards
        // isHeld) so this is a harmless no-op on the onAndOff path and when already held.
        acquireWakeLock();

        // Stamp the ACC-ON edge FIRST (cheap volatile write, no I/O). From here
        // until the trust window lapses, StealthPanel.turnOff() refuses to darken
        // the panel — which closes the race where the keep-alive's final in-flight
        // tick, or a deterrent teardown, darkens the screen just as the driver
        // starts the car. Deliberately before the !inSentryMode early-return, for
        // the same respawn-while-parked reason as G4b/G4c.
        try {
            com.overdrive.app.power.StealthPanel.noteAccOnObserved();
        } catch (Throwable ignored) {}

        // GATE (G4c): wake the panel BEFORE the !inSentryMode early-return, for
        // exactly the reason G4b above sits here. Respawn-while-parked: a
        // previous daemon instance turned the backlight off and was killed; the
        // replacement never saw an ACC-OFF edge, so enterSentryMode() never ran
        // and inSentryMode is false. The real ACC-ON then lands here and would
        // bail below — leaving a dark panel on a car the driver just started,
        // which the user cannot recover from (nothing visible to interact with).
        // The backlight is DEVICE state, not in-memory state, so it outlives the
        // process that set it and must be undone independently of inSentryMode.
        //
        // turnOn() self-skips when the screen already reads on, so this is
        // effectively free on the normal path and on the whole DiLink 3 fleet.
        //
        // Dispatched OFF this thread per the listener-thread contract in the
        // javadoc above: turnOn() does up to five binder reflection round-trips,
        // and stalling the BYD HAL callback would queue the next ACC edge behind
        // us. A dedicated short-lived thread (not the shared teardown worker,
        // which is only spawned past the gate) keeps this correct on the respawn
        // path too.
        new Thread(() -> {
            try {
                com.overdrive.app.power.StealthPanel.turnOn(appContext);
            } catch (Throwable t) {
                log("Stealth panel wake (pre-gate) failed: " + t.getMessage());
            }
        }, "StealthPanelWake").start();

        if (!inSentryMode) {
            log("Not in sentry mode");
            return;
        }

        log("=== EXITING SENTRY MODE ===");

        // CRITICAL: Set inSentryMode=false FIRST, before stopping the keep-alive thread.
        // The keep-alive loop checks `while (running && inSentryMode)` and its interrupt
        // handler also checks `if (!running || !inSentryMode)`. If we stop the thread
        // while inSentryMode is still true, the interrupt handler sees inSentryMode=true
        // and CONTINUES the loop instead of exiting — racing with the screen-wake thread
        // below and calling setBacklightState(false) after we've already turned the screen on.
        // This race caused intermittent 20-30 second screen blackouts after vehicle ON.
        inSentryMode = false;
        surveillanceEnabled = false;

        // CRITICAL: Always notify CameraDaemon that ACC is ON.
        // CameraDaemon handles all surveillance cleanup (door lock gate, unlock poll,
        // cloud listener, pipeline stop) in its ACC ON path. notifyAccState already
        // dispatches the IPC onto accNotifyExecutor so it doesn't block the listener.
        notifyAccState(false);  // accOff=false → ACC is ON

        // Clear safe zone suppression flag (clean slate for next sentry session) —
        // simple in-memory volatile flip in CameraDaemon, safe inline.
        try { CameraDaemon.setSafeZoneSuppressed(false); } catch (Exception ignored) {}

        // Background thread for teardown — symmetric to enterSentryMode's
        // SentrySetup worker. Heavy work (UnifiedConfig writes, peripheral
        // power binder chain, Telegram shell stop with Process.waitFor,
        // keep-alive Thread.join, monitor teardown) MUST NOT run on the
        // BYD HAL listener thread; a 5-30 s stall there would queue any
        // subsequent ACC edge and the second OFF/ON transition would land
        // late, leaving CONTINUOUS / DRIVE_MODE recording on stale ACC
        // state until the 30 s heartbeat resyncs.
        log("Dispatching sentry teardown off BYD HAL listener thread");
        new Thread(() -> {
            try {
                // Tear down any in-progress screen deterrent. ScreenDeterrent.fire()
                // runs in byd_cam_daemon's process (different JVM), so we signal it
                // via unified config: screenDeterrentForceStop=true. The render loop
                // polls this every tick and exits its draw loop, which lets its
                // finally block release the surface and turn the backlight off
                // BEFORE our setBacklightState(true) below — otherwise our wake
                // would land while the deterrent surface still occluded the panel.
                try {
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance",
                        java.util.Collections.singletonMap("screenDeterrentForceStop", true));
                } catch (Throwable t) {
                    log("Failed to signal screen deterrent stop: " + t.getMessage());
                }

                // Restore stock peripheral power behavior (allow MCU to cut power)
                configurePeripheralPower(false);

                // Stop Telegram daemon if it was auto-started
                stopTelegramDaemonIfAutoStarted();

                // Stop active charging maintenance (NO-OP — kept for symmetry).
                stopChargingMaintenance();

                // Stop the V2 voltage monitor that owns the new MCU sleep/wake loop.
                try {
                    com.overdrive.app.power.BatteryVoltageMonitorV2.stopMonitor();
                } catch (Throwable t) {
                    log("BatteryVoltageMonitorV2 stop failed: " + t.getMessage());
                }

                // Stop the SoC-based cutoff monitor.
                try {
                    com.overdrive.app.power.SocCutoffMonitor.stopMonitor();
                } catch (Throwable t) {
                    log("SocCutoffMonitor stop failed: " + t.getMessage());
                }

                // Stop VehicleDataMonitor listener
                stopVehicleDataMonitor();

                // Stop system keep-alive (thread will exit cleanly since inSentryMode is already false)
                stopSystemKeepAlive();
            } catch (Throwable t) {
                log("WARN: sentry teardown worker failed: " + t.getMessage());
                t.printStackTrace();
            }
        }, "SentryTeardown").start();

        // Restore backlight — retry a few times with delay.
        // The keep-alive thread should be fully stopped by now (inSentryMode=false
        // ensures it exits on interrupt), but retry in case the BYD system overrides
        // our first attempt during its own ACC ON boot sequence. Brief delay at
        // the start gives ScreenDeterrent (in byd_cam_daemon process) time to
        // pick up the screenDeterrentForceStop flag, release its SurfaceControl
        // layer, and turn the backlight off — otherwise our wake here lands
        // while the deterrent surface still covers the screen.
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            // dilink4: wake via the reference app's verified two-tier path
            // BEFORE the legacy retry loop below. setBacklightState(true) only
            // has tier 1 (PowerManager.turnBacklightOn) — on firmware where the
            // panel was darkened by TurnBacklightOffWithLock, tier 1 alone can
            // be silently ignored and the driver gets a black screen. turnOn()
            // escalates to TurnBacklightOnWithLock and verifies with
            // getPowerScreenStatus(). Deliberately not mode-gated (see
            // StealthPanel.turnOn) so a cameraMode change made while parked can
            // never strand the panel dark.
            try {
                com.overdrive.app.power.StealthPanel.turnOn(appContext);
            } catch (Throwable t) {
                log("Stealth panel wake failed: " + t.getMessage());
            }
            for (int attempt = 1; attempt <= 3; attempt++) {
                setBacklightState(true);
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
            // Once we've finished the wake sequence, clear the cross-process
            // force-stop flag so the next sentry session can run deterrents
            // again. Done last so any belated ScreenDeterrent tick still sees
            // the stop signal. Coalesced into a single updateValues call
            // (full-JSON write) per prior-audit pattern.
            try {
                java.util.Map<String, Object> deterrentClear = new java.util.HashMap<>();
                deterrentClear.put("screenDeterrentForceStop", false);
                deterrentClear.put("screenDeterrentActiveUntilMs", 0L);
                com.overdrive.app.config.UnifiedConfigManager.updateValues(
                    "surveillance", deterrentClear);
            } catch (Throwable ignored) {}
        }, "ScreenWake").start();

        log("Sentry mode DEACTIVATED");
    }
    
    /**
     * Cleanup and shutdown the daemon gracefully.
     * Called on process termination or manual shutdown.
     */
    private static void shutdownDaemon() {
        log("=== DAEMON SHUTDOWN INITIATED ===");
        
        running = false;
        
        // Exit sentry mode if active
        if (inSentryMode) {
            exitSentryMode();
        }
        
        // Stop status monitoring
        stopStatusMonitoring();
        
        // Release wake lock
        releaseWakeLock();
        
        // Release singleton lock
        releaseSingletonLock();
        
        log("=== DAEMON SHUTDOWN COMPLETE ===");
    }

    // ==================== DEBUG TOOLS ====================
    
    /**
     * DEBUG TOOL: Dumps the values of all known Sleep Reason constants.
     * Use this to verify which magic number (9, 13, etc.) your specific car firmware uses.
     */
    private static void logAllSleepReasonFields() {
        log("=== DUMPING SLEEP REASON CONSTANTS ===");
        
        String[] possibleFieldNames = {
            "GO_TO_SLEEP_REASON_ACCOFF",       // Primary BYD constant
            "GO_TO_SLEEP_REASON_ACC_OFF",      // Alternative naming
            "GO_TO_SLEEP_REASON_POWER_OFF",    // Generic power off
            "GO_TO_SLEEP_REASON_DEVICE_ADMIN", // Android 10+ constant (value 13)
            "GO_TO_SLEEP_REASON_TIMEOUT",      // Standard Android (usually 2)
            "GO_TO_SLEEP_REASON_POWER_BUTTON"  // Standard Android (usually 4)
        };
        
        for (String fieldName : possibleFieldNames) {
            try {
                java.lang.reflect.Field field = PowerManager.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                int value = field.getInt(null); // Static field, so object is null
                log("  [FOUND] " + fieldName + " = " + value);
            } catch (NoSuchFieldException e) {
                log("  [MISSING] " + fieldName + " (Not present on this firmware)");
            } catch (Exception e) {
                log("  [ERROR] " + fieldName + ": " + e.getMessage());
            }
        }
        
        // Also dump the standard SDK version for context
        log("  [INFO] Android SDK Version: " + android.os.Build.VERSION.SDK_INT);
        log("=== END DUMP ===");
    }

    // ==================== SENTRY HELPERS ====================

    /*// ==================== POWER METHOD DISCOVERY ====================

    *//**
     * Dump ALL PowerManager methods (no filtering).
     *//*
    private static void dumpPowerManagerMethods() {
        log("=== DUMPING ALL POWERMANAGER METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);
            
            for (Method m : pm.getClass().getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  PM: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
            // Also try to get current screen power status if method exists
            try {
                Method getStatus = PowerManager.class.getMethod("getPowerScreenStatus");
                int status = (int) getStatus.invoke(pm);
                log("  >> Current getPowerScreenStatus(): " + status);
            } catch (NoSuchMethodException e) {
                log("  >> getPowerScreenStatus() not found");
            }
            
        } catch (Exception e) {
            log("PowerManager dump error: " + e.getMessage());
        }
        log("=== END POWERMANAGER METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoPowerDevice methods (no filtering).
     *//*
    private static void dumpBydPowerDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOPOWERDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            BYDAutoPowerDevice powerDevice = BYDAutoPowerDevice.getInstance(permissiveContext);
            
            if (powerDevice == null) {
                log("  BYDAutoPowerDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : powerDevice.getClass().getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  BYD: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoPowerDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOPOWERDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoSettingDevice methods (no filtering).
     *//*
    private static void dumpBydSettingDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOSETTINGDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object settingDevice = getInstance.invoke(null, permissiveContext);
            
            if (settingDevice == null) {
                log("  BYDAutoSettingDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  SETTING: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoSettingDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOSETTINGDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoLocationDevice methods.
     *//*
    private static void dumpBydLocationDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOLOCATIONDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.location.BYDAutoLocationDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);
            
            if (device == null) {
                log("  BYDAutoLocationDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  LOCATION: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoLocationDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOLOCATIONDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoADASDevice methods.
     *//*
    private static void dumpBydAdasDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOADASDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.adas.BYDAutoADASDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);
            
            if (device == null) {
                log("  BYDAutoADASDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  ADAS: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoADASDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOADASDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoBodyworkDevice methods.
     *//*
    private static void dumpBydBodyworkDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOBODYWORKDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);
            
            if (device == null) {
                log("  BYDAutoBodyworkDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  BODYWORK: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoBodyworkDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOBODYWORKDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoChargingDevice methods.
     *//*
    private static void dumpBydChargingDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOCHARGINGDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.charging.BYDAutoChargingDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);
            
            if (device == null) {
                log("  BYDAutoChargingDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  CHARGING: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoChargingDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOCHARGINGDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoStatisticDevice methods.
     *//*
    private static void dumpBydStatisticDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOSTATISTICDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);
            
            if (device == null) {
                log("  BYDAutoStatisticDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  STATISTIC: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoStatisticDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOSTATISTICDEVICE METHODS ===");
    }

    *//**
     * Dump ALL BYDAutoTyreDevice methods.
     *//*
    private static void dumpBydTyreDeviceMethods() {
        log("=== DUMPING ALL BYDAUTOTYREDEVICE METHODS ===");
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            Class<?> clazz = Class.forName("android.hardware.bydauto.tyre.BYDAutoTyreDevice");
            Method getInstance = clazz.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, permissiveContext);
            
            if (device == null) {
                log("  BYDAutoTyreDevice.getInstance() returned null");
                return;
            }
            
            for (Method m : clazz.getMethods()) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) {
                    if (params.length() > 0) params.append(", ");
                    params.append(p.getSimpleName());
                }
                log("  TYRE: " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
            
        } catch (Exception e) {
            log("BYDAutoTyreDevice dump error: " + e.getMessage());
        }
        log("=== END BYDAUTOTYREDEVICE METHODS ===");
    }

    *//**
     * Dump all BYD device methods at startup for discovery.
     *//*
    private static void dumpAllBydDeviceMethods() {
        log("=== STARTING BYD DEVICE METHOD DUMP ===");
        dumpBydLocationDeviceMethods();
        dumpBydAdasDeviceMethods();
        dumpBydBodyworkDeviceMethods();
        dumpBydChargingDeviceMethods();
        dumpBydStatisticDeviceMethods();
        dumpBydTyreDeviceMethods();
        log("=== COMPLETED BYD DEVICE METHOD DUMP ===");
    }*/

    // ==================== POWER CONTROL (Reflection) ====================

    /**
     * Dynamically retrieves the correct sleep reason code from the PowerManager.
     * This ensures compatibility across different Android versions (SDK 28 vs 29+)
     * and different BYD car models (Atto 3, Seal, etc.).
     * 
     * Tries multiple field names that BYD might use across firmware versions.
     * 
     * @return The correct GO_TO_SLEEP_REASON code (9 for older, 13 for SDK 32+).
     */
    private static int getSystemSleepReasonCode() {
        // Probe the BYD-added GO_TO_SLEEP_REASON_ACCOFF constant; if absent,
        // fall back to the SDK-version literal (13 on SDK 32+, else 9).
        try {
            java.lang.reflect.Field field =
                PowerManager.class.getDeclaredField("GO_TO_SLEEP_REASON_ACCOFF");
            field.setAccessible(true);
            return field.getInt(null);
        } catch (NoSuchFieldException e) {
            // Constant not present on this firmware — use the version literal.
        } catch (Exception e) {
            // Access error — use the version literal.
        }
        return android.os.Build.VERSION.SDK_INT >= 32 ? 13 : 9;
    }

    /**
     * Performs a validated wake-up call using the correct context ID and details string.
     * This mimics a legitimate ignition event to bypass the ACC lock.
     * Uses "Double-Key" logic (Correct ID + "ACC_ON") to pass security check.
     * 
     * CRITICAL: This is the initial wake call when entering sentry mode.
     * The keep-alive thread maintains this state via userActivity().
     */
    // Cached 3-arg PowerManager.wakeUp(long, int, String), resolved once.
    private static volatile Method pmWakeUp3ArgMethod;

    private static void performSystemWakeUp() {
        if (appContext == null) {
            log("performSystemWakeUp: No context available");
            return;
        }

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);

            int reasonID = getSystemSleepReasonCode();

            if (pmWakeUp3ArgMethod == null) {
                pmWakeUp3ArgMethod = PowerManager.class.getMethod(
                        "wakeUp", Long.TYPE, Integer.TYPE, String.class);
            }
            pmWakeUp3ArgMethod.invoke(pm, android.os.SystemClock.uptimeMillis(), reasonID, "ACC_ON");
            log("System wake-up sent (reason: " + reasonID + ")");
        } catch (Throwable t) {
            log("Wake-up failed: " + t.getMessage());
        }
    }

    private static void setBacklightState(boolean on) {
        log("Setting backlight: " + (on ? "ON" : "OFF"));

        // Try PowerManager reflection
        if (appContext != null) {
            try {
                PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                Class<?> pmClass = pm.getClass();

                // First-call probe of lowercase variant; cached thereafter.
                // Original semantics: lowercase invoke-time exceptions
                // bubble to the outer catch (then to BYD path), so we let
                // them propagate naturally.
                Method lower = getPmBacklightLowerMethod(on, pmClass);
                if (lower != null) {
                    lower.invoke(pm, android.os.SystemClock.uptimeMillis());
                    log("Backlight: PowerManager." + (on ? "turnBacklightOn" : "turnBacklightOff") + " SUCCESS");
                    return;
                }

                // Lowercase missing — try PascalCase variant. Original
                // probe order is preserved on first run; subsequent calls
                // skip directly to whichever resolved. PascalCase invoke-
                // time exceptions are swallowed (original code wrapped
                // [C]+[D] in catch (Exception e2)) so we mirror that with
                // an inner try.
                Method pascal = getPmBacklightPascalMethod(on, pmClass);
                if (pascal != null) {
                    try {
                        pascal.invoke(pm, android.os.SystemClock.uptimeMillis());
                        log("Backlight: PowerManager." + (on ? "TurnBacklightOn" : "TurnBacklightOff") + " SUCCESS");
                        return;
                    } catch (Exception e2) {
                        // Fall through to BYD path
                    }
                }
            } catch (Exception e) {
                // Fall through to BYD path (matches original outer catch).
            }

            // Try BYD Hardware Service
            try {
                resolveBydSettingDevice();
                if (bydSettingDeviceResolved) {
                    Method bydMethod = getBydSettingBacklightMethod(on);
                    if (bydMethod != null) {
                        Object device = bydSettingGetInstanceMethod.invoke(null, appContext);
                        bydMethod.invoke(device);
                        log("Backlight: BYDAutoSettingDevice." + (on ? "turnBacklightOn" : "turnBacklightOff") + " SUCCESS");
                        return;
                    }
                }
            } catch (Exception e) {
                // Fall through
            }
        }

        // Fallback: Settings brightness
        int brightness = on ? 128 : 0;
        execShell("settings put system screen_brightness " + brightness);
        if (on) {
            execShell("input keyevent 224");  // KEYCODE_WAKEUP
        } else {
            execShell("input keyevent 223");  // KEYCODE_SLEEP
        }
    }

    /**
     * Enforces strict power management state.
     * Transitions the display to the OFF state while strictly prohibiting
     * the operating system from entering deep sleep (Doze) modes.
     * This maintains network and CPU availability while minimizing power draw.
     */
    private static void enforceSmartSleep() {
        if (appContext == null) return;

        // Yield to an active screen deterrent — the deterrent in
        // byd_cam_daemon's process is currently driving the panel ON and
        // would be clobbered by goToSleep / setBacklightState(false).
        if (isScreenDeterrentActive()) {
            return;
        }

        // DiLink 4: use the verified two-tier backlight path rather than
        // goToSleep. goToSleep is a full AP sleep request, which the reference
        // app reserves for its own shutdown path — for "dark panel, CPU alive"
        // it uses TurnBacklightOff(+WithLock) and verifies with
        // getPowerScreenStatus(). The setBacklightState(false) fallback below is
        // tier-1-only and can be silently ignored on this firmware.
        //
        // This method currently has NO callers. The gate is here because it
        // reads as the obvious helper to reach for ("enforce stealth power
        // state"), so the next person to wire it up gets the verified path
        // instead of an unverified one. Legacy pano_h/pano_l units are
        // unaffected and keep the original goToSleep behaviour.
        if (isDilink4CameraMode()) {
            try {
                com.overdrive.app.power.StealthPanel.turnOff(appContext);
                log("enforceSmartSleep: used verified backlight-off path (dilink4)");
            } catch (Throwable t) {
                log("enforceSmartSleep backlight-off failed: " + t.getMessage());
            }
            return;
        }

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);

            // Method signature: goToSleep(long time, int reason, int flags)
            Method method = PowerManager.class.getMethod("goToSleep", Long.TYPE, Integer.TYPE, Integer.TYPE);

            // Dynamically retrieve the system-specific reason code (Compatibility Mode)
            // This ensures the command is accepted by the Body Control Module
            int reasonID = getSystemSleepReasonCode();

            // Execute with Flag 1 (GO_TO_SLEEP_FLAG_NO_DOZE)
            // Flag 1 is the critical component: Screen OFF, but CPU/Radio remain ACTIVE.
            method.invoke(pm, android.os.SystemClock.uptimeMillis(), reasonID, 1);

        } catch (Exception e) {
            log("Smart sleep state enforcement failed: " + e.getMessage());
            // Graceful fallback to basic backlight control if reflection fails
            setBacklightState(false);
        }
    }

    // ==================== SYSTEM PERSISTENCE SERVICE ====================
    
    /**
     * Starts the System Persistence Service (10-second maintenance loop).
     * Implements the "Refresh & Enforce" pattern:
     * 1. Maintains network interface stability (WiFi)
     * 2. Refreshes CPU wake timer (fake user activity)
     * 3. Enforces stealth power state (screen off, CPU active)
     * 
     * CRITICAL: Uses Throwable catch to survive OutOfMemoryError and other Errors.
     * Thread is NOT a daemon so it survives if main thread has issues.
     */
    private static void startSystemKeepAlive() {
        if (systemKeepAliveThread != null && systemKeepAliveThread.isAlive()) {
            return;
        }

        systemKeepAliveThread = new Thread(() -> {
            log("System Persistence Service started");

            // Re-assert cadence for the OEM 409 camera/ISP power vote. The
            // loop ticks every SYSTEM_KEEPALIVE_INTERVAL_MS (10s); the ISP
            // gate that black-frames the AVM feed fires at ~30-35 min of
            // inactivity, so re-asserting every ~5 min (30 ticks) is a 6-7x
            // safety margin while keeping binder/log churn negligible. The
            // initial vote is already cast by configurePeripheralPower(true)
            // in enterSentryMode; this only defends against the MCU/BCM
            // silently dropping the flag mid-session.
            final long ISP_VOTE_REASSERT_EVERY_TICKS = 30;  // 30 * 10s = 5 min
            // Cadence for re-reporting a panel that refuses to darken (~5 min).
            final long PANEL_FAILURE_REPORT_EVERY_TICKS = 30;
            // Periodic MCU re-wake + peripheral-rail re-assert cadence. On some
            // models the BCM drifts the MCU back to sleep mid-park after the
            // one-shot enterSentryMode wake, which collapses the USB-bridged SD
            // reader rail and the modem/data rail — the unit then loses USB/SD
            // power AND network connectivity while parked. Re-asserting on a slow
            // cadence holds them up. 48 ticks * 10s = 8 min.
            final long MCU_REWAKE_EVERY_TICKS = 48;  // 48 * 10s = 8 min
            // Reactive SD-rail recovery. On-car (log 2026-07-19): the USB-bridged
            // SD rail collapses DURING the ACC-OFF transition itself
            // (sys.byd.isSDExist flips false seconds after sentry entry), so the
            // one-shot enterSentryMode wake was too early to help and the FIRST
            // periodic re-assert at 8 min was too late — a 4.5-min park session
            // ran its whole life with the SD dead (all events fell back to
            // internal), and an 8-min session only recovered the card exactly at
            // the first re-wake (sm mount succeeded within one watchdog tick of
            // it). Rather than blind early ticks, PROBE the vendor card-detect
            // prop each tick (reflection SystemProperties read — microseconds)
            // and fire the same MCU+rail re-assert only when the rail is actually
            // dead AND the user has SD configured as a storage target. Backoff
            // doubles per attempt (30s → 1m → 2m → 4m → capped at the 8-min
            // cadence) so a genuinely absent card (user pulled it) degenerates
            // to the existing periodic behaviour instead of waking the MCU every
            // 30s all night. Backoff resets whenever the rail reads alive, so a
            // LATER mid-park drop is again recovered within ~30s. First probe at
            // tick 3 (~30s) — the MCU needs a beat to stabilize post-transition;
            // probing earlier just burns a wake on a rail that's still settling.
            final long SD_RECOVERY_MIN_TICK = 3;            // ~30s after entry
            final long SD_RECOVERY_BASE_BACKOFF_TICKS = 3;  // 30s between attempts
            long sdRecoveryBackoffTicks = SD_RECOVERY_BASE_BACKOFF_TICKS;
            // NOT Long.MIN_VALUE: `tick - lastSdRecoveryTick` would overflow to
            // a negative value and the backoff gate would never open. Seeding
            // one backoff below zero makes the first eligible tick pass exactly.
            long lastSdRecoveryTick = -SD_RECOVERY_BASE_BACKOFF_TICKS;
            long tick = 0;

            while (running && inSentryMode) {  // Check BOTH flags
                try {
                    // 1. Maintain Network Interface Stability
                    ensureWifiEnabled();
                    // Fake-activity injection resets the AP sleep timer using the
                    // 2-arg stealth userActivity(uptime, noChangeLights=true), which
                    // holds the AP awake (USB VBUS follows wakefulness) WITHOUT
                    // relighting the panel. We can therefore keep the backlight OFF
                    // and USB powered at the same time. Only inject when "Keep USB
                    // powered" is ON (default); toggle OFF lets the AP drift to sleep
                    // and USB drop to save the 12 V battery. The ISP/AVM camera vote
                    // and AVC keep-alive below stay unconditional — they're the camera
                    // rail, not the AP wake, and surveillance needs them either way.
                    if (isKeepUsbPowerOnAccOff()) {
                        injectFakeUserActivity();
                    }

                    // ScreenDeterrent gate: if a screen deterrent is currently
                    // displaying (set by ScreenDeterrent.fire()), skip the
                    // backlight-off tick. Otherwise this loop would clobber
                    // the wake within 10s and the user would never see the
                    // deterrent through to its full duration.
                    //
                    // DiLink 4 gate: byd_apa AVMCamera HAL ties its preview
                    // surface to display power state. setBacklightState(false)
                    // makes the HAL emit event=8 ("camera died") and tear
                    // down the preview, killing 24/7 sentry recording on a
                    // 10s cadence. Skip the backlight-off entirely on
                    // dilink4. Legacy pano_h/pano_l HALs are
                    // display-state-agnostic and keep their existing
                    // power-save behaviour.
                    //
                    // BUT skipping backlight-off did NOT mean the panel dimmed
                    // by itself. This comment used to claim the "display
                    // naturally dims via the head-unit's own timeout" — it
                    // cannot: injectFakeUserActivity() above pumps
                    // PowerManager.userActivity() every 10s to hold the AP
                    // awake, which is precisely what resets the display-off
                    // timer. So dilink4 parked with a fully-lit screen.
                    //
                    // The fix is NOT to suppress display power. Verified against
                    // the byd_apa reference app (Escort_Auto.apk,
                    // BacklightController + DeviceWakeupMonitor): it turns the
                    // backlight genuinely OFF at park entry WHILE its
                    // AVMCameraRecordAgent is recording, so backlight-off is not
                    // what kills the AVM preview. It also never writes
                    // screen_brightness — brightness 0 does not sleep this
                    // panel. What it has that we lacked is a second tier
                    // (TurnBacklightOffWithLock on PowerManager.mService) plus
                    // getPowerScreenStatus() verification, which is what makes
                    // the off actually stick. StealthPanel.turnOff() reproduces
                    // that. Strictly dilink4-only; legacy units keep
                    // setBacklightState(false) byte-for-byte.
                    //
                    // The cameraActiveUntilMs check is the finer-grained
                    // gate: only the slice of time when the GPU pipeline is
                    // actively consuming frames. Useful even on legacy if
                    // a dashcam mode is running across ACC OFF (rare).
                    //
                    // "Keep USB powered" is NO LONGER a backlight gate. Keeping
                    // USB powered needs the AP awake (wakefulness AWAKE → VBUS),
                    // NOT the panel illuminated — so we let the backlight go off
                    // here even with the toggle ON, rather than pinning the panel
                    // lit for the whole park (the old behaviour: a parked car with
                    // a fully lit screen all night).
                    //
                    // AP wakefulness is held by two mechanisms: (a) the per-tick
                    // injectFakeUserActivity() above WHILE the panel is on (it
                    // self-skips once getPowerScreenStatus()==0, i.e. after this
                    // backlight-off tick), and (b) the periodic performSystemWakeUp()
                    // in the 8-min re-assert block below, which re-arms wakefulness
                    // with the panel dark (then immediately re-darks it). (b) is the
                    // load-bearing re-assert once the screen is off.
                    //
                    // We still suppress the backlight-off in three cases that
                    // genuinely need the panel powered:
                    //   - a screen deterrent is on-screen (it owns the backlight);
                    //   - DiLink 4 AVMCamera HAL ties its preview surface to display
                    //     power — backlight off makes it emit event=8 ("camera
                    //     died") and tear down 24/7 sentry recording;
                    //   - the GPU camera pipeline is actively consuming frames
                    //     (cameraActiveUntilMs lease) on a display-coupled HAL.
                    // Legacy pano_h/pano_l HALs are display-state-agnostic, so on
                    // those the backlight goes off here even mid-recording.
                    // Evaluate each predicate ONCE per tick. Both read config, and
                    // the branches below used to ask for the same answer twice.
                    // isScreenDeterrentActive(true) escalates to a fresh read only
                    // when the cheap read says "inactive" — see its javadoc: a
                    // same-second stale read here would darken the panel out from
                    // under a deterrent that is actively rendering.
                    final boolean dilink4Tick = isDilink4CameraMode();
                    // NOTE ON EVALUATION ORDER — this is deliberate, not stylistic.
                    // The legacy branch keeps the ORIGINAL cheap mtime-gated read
                    // (isScreenDeterrentActive()), so the pano_h/pano_l fleet pays
                    // exactly what it always did. The confirm-with-forceReload
                    // variant is evaluated ONLY inside the dilink4 branch, and only
                    // after the cheap deterrent check has already said "inactive"
                    // and the panel is about to be darkened. Hoisting it into a
                    // local (the obvious-looking cleanup) would force a ~10 KB JSON
                    // re-parse every 10 s on EVERY unit — the ≈3.6 MB/hour churn
                    // that isScreenDeterrentActive's own javadoc exists to avoid.
                    if (!isScreenDeterrentActive()
                            && !dilink4Tick
                            && !isCameraPipelineActive()) {
                        setBacklightState(false);
                    } else if (dilink4Tick && shouldDarkenPanelNow()) {
                        // dilink4: real backlight-off, via the reference app's
                        // two-tier + verify path. Deliberately NOT gated on
                        // isCameraPipelineActive(): the reference app darkens the
                        // panel while its AVM agent records, and on dilink4 the
                        // pipeline runs for essentially the whole park, so
                        // honouring that gate would mean never darkening at all.
                        //
                        // turnOff() self-skips when getPowerScreenStatus()
                        // already reads off, so the steady-state cost on the
                        // remaining ~8600 ticks of an overnight park is a single
                        // status read — and it re-darkens automatically if
                        // anything (the 8-min wakeUp, a deterrent) relit the
                        // panel, which is why it is called every tick rather
                        // than once.
                        try {
                            // Surface a persistent failure. StealthPanel de-dups its
                            // own logging to one line per transition, so a panel that
                            // refuses to darken would otherwise be a single line at
                            // the start of the park and silence for the next ~8600
                            // ticks. Report on a slow cadence so the symptom is
                            // visible in a log pull without becoming the log.
                            if (!com.overdrive.app.power.StealthPanel.turnOff(appContext)
                                    && tick % PANEL_FAILURE_REPORT_EVERY_TICKS == 0) {
                                log("WARN: parked panel still not dark after both tiers "
                                    + "(tick " + tick + ") — screen may be lit while parked");
                            }
                        } catch (Throwable t) {
                            log("Stealth panel turnOff failed: " + t.getMessage());
                        }
                    }

                    // DiLink 4 AVC keep-alive (June 2026 reversal). The
                    // AVM HAL only delivers mosaic content into our
                    // panoramic producer surface while another consumer is
                    // attached to the same vendor.byd.avm daemon — AVC is
                    // exactly that consumer. If BYD's reaper kills AVC
                    // post-ACC-OFF our frames go all-zero. ensureAvcAlive
                    // is a cheap pidof + (only if absent) am start. No-op
                    // when AVC is already running. Red overlay is masked
                    // cosmetically by the GL red-mask shader.
                    if (isDilink4CameraMode()) {
                        try {
                            com.overdrive.app.camera.AvcHalWarmup.ensureAvcAlive();
                        } catch (Throwable t) {
                            log("AVC keep-alive tick failed: " + t.getMessage());
                        }
                    }

                    // 3b. Re-assert the OEM 409 camera/ISP power vote on a
                    // ~5 min cadence so the AVM ISP rail can't power-gate
                    // mid-session (the ~30-35 min black-frame symptom). Cheap
                    // best-effort SpecialDevice writes; no-op on trims that
                    // don't expose the key. Fires on the first tick too
                    // (tick==0) as a belt-and-suspenders re-cast after the
                    // enterSentryMode vote. ALWAYS on — this is the CAMERA/ISP
                    // rail, not the USB rail; the USB toggle never gates it.
                    if (tick % ISP_VOTE_REASSERT_EVERY_TICKS == 0) {
                        try {
                            applySentryIspPowerVote(true);
                        } catch (Throwable t) {
                            log("ISP power vote re-assert failed: " + t.getMessage());
                        }
                    }

                    // Periodic MCU re-wake + peripheral-rail re-assert (every
                    // MCU_REWAKE_EVERY_TICKS, ~8 min). Gated on the "Keep USB
                    // powered" toggle so the battery-save (toggle-OFF) path is
                    // byte-unchanged — when the user opted to let the AP/USB
                    // sleep we must NOT re-wake the MCU here. Skipped on tick 0
                    // because enterSentryMode just ran the same sequence seconds
                    // ago. configurePeripheralPower(true) is idempotent and
                    // already self-checks MCU status (re-waking it if asleep)
                    // before re-writing the rails, so this is the exact same
                    // proven entry-path call, just re-run on a slow cadence to
                    // counter the BCM drifting the MCU back to sleep mid-park.
                    // Reactive SD-rail probe (see constants above). Only when the
                    // rail is confirmed dead, SD is a configured storage target,
                    // the backoff window has elapsed, and keep-USB is ON.
                    boolean sdRecoveryTick = false;
                    if (tick >= SD_RECOVERY_MIN_TICK
                            && (tick - lastSdRecoveryTick) >= sdRecoveryBackoffTicks
                            && isKeepUsbPowerOnAccOff()
                            && isSdConfiguredAsStorageTarget()) {
                        if (isSdRailDead()) {
                            sdRecoveryTick = true;
                            lastSdRecoveryTick = tick;
                            // Exponential backoff, capped at the periodic cadence.
                            sdRecoveryBackoffTicks = Math.min(
                                sdRecoveryBackoffTicks * 2, MCU_REWAKE_EVERY_TICKS);
                        } else {
                            // Rail alive — reset so a later mid-park drop gets the
                            // fast 30s response again.
                            sdRecoveryBackoffTicks = SD_RECOVERY_BASE_BACKOFF_TICKS;
                        }
                    }
                    if ((sdRecoveryTick || (tick > 0 && tick % MCU_REWAKE_EVERY_TICKS == 0))
                            && isKeepUsbPowerOnAccOff()) {
                        try {
                            log("Periodic MCU re-wake + rail re-assert ("
                                + (sdRecoveryTick
                                    ? "SD-rail-dead recovery, tick " + tick
                                        + ", next backoff " + sdRecoveryBackoffTicks + " ticks"
                                    : "8-min cadence") + ")");
                            // Re-assert the MCU + peripheral rails (idempotent;
                            // self-wakes the MCU if the BCM slept it).
                            configurePeripheralPower(true);
                            // ALSO re-establish AP wakefulness. configurePeripheralPower
                            // only drives the MCU/DC-DC rail, NOT the AP wake state —
                            // and on DiLink 3.0 USB VBUS follows AP wakefulness. The
                            // per-tick injectFakeUserActivity() pump self-skips once the
                            // backlight is off (it returns early on getPowerScreenStatus
                            // ==0), so without this the AP can drift to sleep mid-park and
                            // USB/SD drops even with the toggle ON. performSystemWakeUp()
                            // is the same AP-wake enterSentryMode casts at ACC-OFF.
                            // NOTE: wakeUp() lights the panel. Left alone, the screen
                            // would stay on until the next backlight-off tick (up to one
                            // SYSTEM_KEEPALIVE_INTERVAL_MS away) — an ~8-min screen flash
                            // on a parked car. So immediately re-dark the panel here under
                            // the SAME conditions the per-tick backlight-off gate uses
                            // (skip when a deterrent owns the panel, on dilink4 where
                            // backlight-off kills the AVM HAL, or while the GPU pipeline is
                            // actively consuming frames). The AP wakefulness set by
                            // wakeUp() persists with the panel dark — exactly the desired
                            // state. Gated on the same toggle so the OFF path is untouched.
                            performSystemWakeUp();
                            // wakeUp() just relit the panel. On firmware with no
                            // getPowerScreenStatus() that relight is invisible to
                            // StealthPanel, so declare it — otherwise its
                            // unverifiable-firmware latch would treat the
                            // re-darken below as redundant and skip it, leaving
                            // the panel lit for the rest of the park.
                            //
                            // Gated on dilink4: this is a CONFIG WRITE, and the key
                            // it clears is one only the dilink4 path ever reads. Run
                            // unconditionally it would bump the config mtime every
                            // ~8 min on the legacy fleet too, invalidating the
                            // mtime-gated loadConfig() cache in every daemon process
                            // and forcing a ~10 KB re-parse on their next read — the
                            // churn the gates here are carefully written to avoid.
                            if (isDilink4CameraMode()) {
                                try {
                                    com.overdrive.app.power.StealthPanel
                                        .notePanelStateChangedExternally();
                                } catch (Throwable ignored) {}
                            }
                            if (!isScreenDeterrentActive()
                                    && !isDilink4CameraMode()
                                    && !isCameraPipelineActive()) {
                                setBacklightState(false);
                            } else if (isDilink4CameraMode()
                                    && !isScreenDeterrentActive(true)
                                    && !com.overdrive.app.power.StealthPanel
                                            .isUserOverrideActive()) {
                                // Same rationale as the per-tick gate above, and the
                                // same two guards (fresh-read deterrent check so we
                                // can't darken a rendering warning; user-override
                                // grace so an explicit screen-on survives). This call
                                // is load-bearing rather than merely idempotent:
                                // performSystemWakeUp() just relit the panel, so the
                                // status now reads ON and turnOff() genuinely
                                // re-darkens instead of self-skipping. Without it the
                                // panel stays lit until the next 10 s tick.
                                try {
                                    com.overdrive.app.power.StealthPanel.turnOff(appContext);
                                } catch (Throwable t) {
                                    log("Stealth panel re-darken failed: " + t.getMessage());
                                }
                            }
                        } catch (Throwable t) {
                            log("Periodic MCU re-wake / rail re-assert failed: " + t.getMessage());
                        }
                    }
                    tick++;

                    // 4. Maintenance Cycle Interval (10 seconds)
                    Thread.sleep(SYSTEM_KEEPALIVE_INTERVAL_MS);
                    
                } catch (InterruptedException e) {
                    log("KeepAlive interrupted - checking if should continue...");
                    if (!running || !inSentryMode) {
                        break;  // Exit cleanly
                    }
                    // Otherwise continue the loop
                } catch (Throwable t) {
                    // CRITICAL: Catch EVERYTHING including Errors (OutOfMemoryError, etc.)
                    // DON'T break - keep trying!
                    log("KeepAlive error: " + t.getMessage());
                    try {
                        Thread.sleep(1000);  // Brief pause before retry
                    } catch (InterruptedException ignored) {
                        if (!running || !inSentryMode) break;
                    }
                }
            }

            log("System Persistence Service stopped");
        }, "SystemKeepAlive");

        // CRITICAL: Not a daemon thread! Survives if main thread has issues.
        systemKeepAliveThread.setDaemon(false);
        systemKeepAliveThread.start();
    }

    /**
     * True if a screen deterrent is currently displaying (set by
     * ScreenDeterrent.fire() in byd_cam_daemon's process). loadConfig()
     * invalidates its cache against configFile.lastModified() which is
     * filesystem-wide and therefore visible across UIDs — forceReload()
     * here would re-parse ~10 KB JSON every 10 s for the same answer
     * (≈3.6 MB/hour GC churn). Returns false on any failure so a stuck
     * flag can never disable the stealth keep-alive permanently.
     */
    private static boolean isScreenDeterrentActive() {
        return isScreenDeterrentActive(false);
    }

    /**
     * The dilink4 "should the keep-alive darken the panel this tick?" decision.
     *
     * <p>Factored out of the keep-alive's {@code else if} condition deliberately.
     * Those guards previously sat in the condition itself, OUTSIDE the narrow
     * try/catch around the darken call — so a throw from any of them would escape
     * to the loop-body handler and abandon the REST of that tick (AVC keep-alive,
     * SD-rail recovery, USB-power re-assert, the 8-min MCU re-wake), every tick,
     * for the whole park. Each guard catches Throwable internally today, but the
     * structure made a future edit to any of them a fleet-wide surveillance
     * outage. Here a failure is contained and fails CLOSED (don't darken), which
     * is the safe direction: a lit panel is visible and self-corrects next tick,
     * whereas losing the tick's rail work is not visible at all.
     *
     * <p>Order is a cost decision, not style. {@code isAlreadyDark} is checked
     * FIRST because it is the cheap common case (~99.9% of ticks) and short-
     * circuits the two expensive guards, both of which do a full config re-parse.
     */
    private static boolean shouldDarkenPanelNow() {
        String reason;
        try {
            if (com.overdrive.app.power.StealthPanel.isAlreadyDark(appContext)) {
                reason = "already-dark";
            } else if (isScreenDeterrentActive(true)) {
                reason = "deterrent-active";
            } else if (com.overdrive.app.power.StealthPanel.isUserOverrideActive()) {
                reason = "user-override";
            } else {
                logPanelGate(null);
                return true;
            }
        } catch (Throwable t) {
            log("Panel-darken gate failed, skipping this tick: " + t.getMessage());
            return false;
        }
        // Log WHY we declined, on state change only. Without this a park that
        // stayed lit produced no log line at all from either file: all three
        // branches above skip turnOff() entirely, so neither StealthPanel's
        // per-transition log nor the ~5-min failure WARN can fire. That made the
        // four possible causes of "my screen stayed on all night" indistinguishable
        // in a customer log pull.
        logPanelGate(reason);
        return false;
    }

    private static volatile String lastPanelGateReason = "";

    private static void logPanelGate(String reason) {
        String key = (reason == null) ? "" : reason;
        if (key.equals(lastPanelGateReason)) return;
        lastPanelGateReason = key;
        if (reason == null) log("Panel-darken gate: proceeding (darkening panel)");
        else log("Panel-darken gate: declining — " + reason);
    }

    /**
     * @param confirmInactive when true, a negative answer from the mtime-cached
     *        read is re-checked with {@link
     *        com.overdrive.app.config.UnifiedConfigManager#forceReload()} before
     *        being believed.
     *
     * <p>Why that escalation exists: ext4 mtime resolution is 1 s, and
     * ScreenDeterrent (a DIFFERENT process) publishes its gate immediately before
     * waking the panel. If that write lands in the same wall-clock second as our
     * cached read, loadConfig()'s {@code fileModified <= lastModified} check
     * returns the STALE config without the new deadline, we conclude no deterrent
     * is active, and turnOff() darkens the panel while the deterrent's
     * z=Integer.MAX_VALUE layer is being composited — an intruder warning nobody
     * can see. ScreenDeterrent guards the mirror-image hazard the same way
     * (forceReload in shouldStop / isForceStop).
     *
     * <p>Cost is bounded to the case that matters: the extra parse happens only
     * when the cheap read says "inactive" AND the caller is about to darken the
     * panel. Once the panel is dark, turnOff() self-skips before consulting this,
     * so the steady state does not pay it — nowhere near the ≈3.6 MB/hour that an
     * unconditional forceReload here would cost.
     */
    private static boolean isScreenDeterrentActive(boolean confirmInactive) {
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            long deadline = (s == null) ? 0L : s.optLong("screenDeterrentActiveUntilMs", 0L);
            if (isDeterrentDeadlineLive(deadline)) return true;
            if (!confirmInactive) return false;
            // Cheap read says inactive — re-read fresh before acting on it.
            org.json.JSONObject fresh = com.overdrive.app.config.UnifiedConfigManager.forceReload()
                    .optJSONObject("surveillance");
            if (fresh == null) return false;
            return isDeterrentDeadlineLive(
                    fresh.optLong("screenDeterrentActiveUntilMs", 0L));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Upper-bound the deterrent gate the same way StealthPanel bounds its
     * user-override deadline. ScreenDeterrent only ever publishes {@code now +
     * duration} with duration clamped to 3-30 s, so any legitimate deadline is
     * seconds away. A far-future value means a stale gate survived an unclean
     * teardown (the deterrent was SIGKILLed before cleanup() zeroed it) AND the
     * wall clock stepped backward — a documented condition on these head units.
     * Unbounded, that reads as "deterrent active" forever and silently suppresses
     * parked darkening for the whole park. The 5-minute ceiling is ~10x the
     * longest real deterrent, so it cannot reject a genuine one.
     */
    private static final long DETERRENT_GATE_MAX_HORIZON_MS = 5 * 60_000L;

    private static boolean isDeterrentDeadlineLive(long deadlineMs) {
        long now = System.currentTimeMillis();
        return deadlineMs > now && deadlineMs <= now + DETERRENT_GATE_MAX_HORIZON_MS;
    }

    /**
     * True when the user has selected DiLink 4 mode for the camera. On
     * byd_apa firmware the AVMCamera HAL tears down the preview surface
     * whenever the display backlight goes off, so the keepalive's
     * setBacklightState(false) tick must be suppressed entirely. Reads
     * the same UnifiedConfigManager cross-UID cache as the screen-deterrent
     * gate; cheap (~0 GC churn between writes since loadConfig() is mtime-
     * gated). Returns false on any failure so a stuck flag can never
     * keep the legacy fleet's screen on permanently.
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

    /**
     * User toggle (Surveillance → General → "Keep USB powered while parked"):
     * whether the head-unit AP is forced fully awake after ACC OFF. DEFAULT TRUE so the
     * out-of-box behaviour is byte-for-byte identical to the pre-toggle build (and any
     * install whose config predates this key).
     *
     * <p><b>This gates {@link #performSystemWakeUp()} in {@link #enterSentryMode()}.</b>
     * On DiLink 3.0 the USB-port VBUS follows the AP wake state, not the SpecialDevice
     * rail registers (rail writes were proven ineffective on-device). When the toggle is
     * ON we force wakefulness=Awake (USB stays powered, as today). When OFF we skip the
     * wake so the AP can sleep on ACC-OFF and USB drops, saving the 12 V battery.
     *
     * <p><b>Trade-off:</b> skipping the forced wake means features that need the AP
     * continuously awake while parked may not run when the toggle is OFF. The MCU/AVM
     * power writes in {@link #configurePeripheralPower} still fire, and HAL events still
     * wake the AP on demand, but a continuously-awake assumption (e.g. some keep-alive
     * cadence) won't hold. The toggle defaults ON so this only applies when the user
     * explicitly opts out.
     *
     * <p>Read fresh on the ACC-OFF setup path so a change applies on the NEXT ACC-OFF
     * cycle. Reads the same cross-UID mtime-gated loadConfig() cache the other gates here
     * use (no forceReload churn); returns the safe DEFAULT (true) on any failure so a
     * transient read error can never suppress the wake unexpectedly.
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
     * True when the vendor SD card-detect prop reports the card ABSENT. On the
     * affected fleet the USB-bridged SD reader loses power during the ACC-OFF
     * transition; the MCU's card-detect pin then drives sys.byd.isSDExist to
     * 'false' even though a card is physically inserted. The keepalive loop
     * uses this as the "rail is dead" trigger for a reactive MCU+rail
     * re-assert. Deliberately conservative: only an EXPLICIT 'false' counts —
     * an empty/unreadable prop (trim without the vendor prop, reflection
     * blocked) returns false here so those units never wake the MCU on a
     * signal they don't actually have.
     */
    private static boolean isSdRailDead() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String v = (String) get.invoke(null, "sys.byd.isSDExist", "");
            return "false".equalsIgnoreCase(v);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when the user has the SD card configured as the storage target for
     * ANY stream (surveillance / recordings / trips). Gates the reactive
     * SD-rail recovery so an internal-storage-only install never pays the
     * extra MCU wakes for a rail it doesn't use. Reads the same mtime-gated
     * loadConfig() cache as the other keepalive gates; defaults false (skip
     * recovery) on read failure — the periodic 8-min re-assert still covers
     * the rail as a backstop.
     */
    private static boolean isSdConfiguredAsStorageTarget() {
        try {
            org.json.JSONObject st = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("storage");
            if (st == null) return false;
            return "SD_CARD".equals(st.optString("surveillanceStorageType", ""))
                || "SD_CARD".equals(st.optString("recordingsStorageType", ""))
                || "SD_CARD".equals(st.optString("tripsStorageType", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when CameraDaemon's GPU pipeline is actively consuming camera
     * frames. CameraDaemon refreshes a lease deadline ~8s ahead of now while
     * gpuPipeline.isRunning(); we read it here to skip the keepalive's
     * backlight-off tick during that window.
     *
     * <p>The lease lives in a dedicated sidecar file (a single timestamp), NOT
     * in the shared unified config — reading/writing it must not take the
     * cross-process config lock or bump the config mtime every 4s (that defeated
     * the mtime-gated config cache other subsystems rely on; see
     * CameraDaemon.writeCameraActiveLease). We read the sidecar directly and fall
     * back to the legacy {@code surveillance.cameraActiveUntilMs} key for
     * compatibility with an older CameraDaemon build that still writes it there.
     * A missing/unparseable lease → false, so legacy fleets (that write neither)
     * keep the existing power-save behaviour bit-exact.
     */
    private static final String CAMERA_ACTIVE_LEASE_PATH =
        "/data/local/tmp/camera_active_lease";

    // Upper bound on how far ahead of "now" a lease deadline may legitimately be.
    // CameraDaemon only ever writes now + 8s, so any live lease is <=8s out; we
    // allow a generous 5-min horizon to absorb any future bump to the lease
    // duration. A deadline BEYOND this is not a real lease — it means a stale
    // file persisted across an unclean teardown (crash / kill / power-loss never
    // runs clearCameraActiveLease) and the wall clock then stepped BACKWARD, a
    // documented BYD head-unit condition (see UnifiedConfigManager.isCacheFresh:
    // "BYD head units boot with a wrong clock" / "the RTC stepped backward").
    // Without this bound such a file reads as active FOREVER — with no camera
    // running and nothing to refresh or clear it — pinning the backlight on for a
    // parked car all night. Clamp fails safe to "not active", matching the
    // isDilink4CameraMode() invariant that a stuck flag can never keep the
    // screen on permanently.
    private static final long CAMERA_ACTIVE_LEASE_MAX_HORIZON_MS = 5 * 60_000L;

    private static boolean isLeaseDeadlineLive(long deadlineMs, long nowMs) {
        return deadlineMs > nowMs
                && deadlineMs <= nowMs + CAMERA_ACTIVE_LEASE_MAX_HORIZON_MS;
    }

    private static boolean isCameraPipelineActive() {
        long now = System.currentTimeMillis();
        // Primary: the dedicated sidecar file (cheap, lock-free, no config parse).
        try {
            java.io.File f = new java.io.File(CAMERA_ACTIVE_LEASE_PATH);
            if (f.exists()) {
                byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
                long deadline = Long.parseLong(new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim());
                return isLeaseDeadlineLive(deadline, now);
            }
        } catch (Throwable ignored) {
            // Torn/absent/unparseable read fails safe to the fallback below.
        }
        // Fallback: legacy config key (older CameraDaemon build). Only reached
        // when the sidecar is absent, so it does not reintroduce the 4s config
        // churn on a current build.
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            if (s == null) return false;
            long deadline = s.optLong("cameraActiveUntilMs", 0L);
            return isLeaseDeadlineLive(deadline, now);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void stopSystemKeepAlive() {
        if (systemKeepAliveThread != null) {
            log("Stopping System Persistence Service...");
            systemKeepAliveThread.interrupt();
            
            // Wait briefly for clean shutdown
            try {
                systemKeepAliveThread.join(2000);
            } catch (InterruptedException ignored) {}
            
            if (systemKeepAliveThread.isAlive()) {
                log("WARN: KeepAlive thread did not stop cleanly");
            }
            
            systemKeepAliveThread = null;
        }
    }

    /**
     * Checks if Wi-Fi is enabled and forces it ON if not.
     * Equivalent to: Runtime.getRuntime().exec("svc wifi enable");
     */
    private static void ensureWifiEnabled() {
        // Respect a user's explicit "WiFi off" automation / key-mapping: when the radio
        // action set the suppression flag, the keep-alive stands down so the deliberate
        // off state isn't clobbered every 10s tick. With no such rule the flag is false
        // (default) and keep-alive behaves exactly as before. Fail-open (read returns
        // false on error → we re-enable), so a config glitch can never strand WiFi off.
        if (com.overdrive.app.config.UnifiedConfigManager.isWifiKeepAliveSuppressed()) {
            return;
        }
        // We use a lightweight check to avoid spamming the shell log
        // running it blindly is safer for persistence.
        execShell(CMD_WIFI_ENABLE());
    }

    // ==================== REFLECTION CACHES ====================
    // injectFakeUserActivity is invoked every SYSTEM_KEEPALIVE_INTERVAL_MS
    // (10s) while sentry is active — ~360 calls/hr, ~3000/night. Without
    // caching, each call performed up to three Class.getMethod() lookups
    // (linear method-table scans) on PowerManager for getPowerScreenStatus,
    // userActivity(long), and userActivity(long, boolean). The Method
    // objects are immutable; resolve once and reuse. Volatile for safe
    // publication; idempotent double-resolve race accepted (matches
    // RecordingModeManager.resolveBodyworkReflection pattern).
    //
    // Per-method resolved/failed flags so that a missing optional method
    // (e.g., getPowerScreenStatus on older firmware) doesn't poison the
    // userActivity lookups, and a missing 1-arg userActivity still allows
    // the 2-arg fallback to be cached.
    private static volatile Method pmGetPowerScreenStatusMethod;
    private static volatile boolean pmGetPowerScreenStatusResolved = false;
    private static volatile boolean pmGetPowerScreenStatusFailed = false;

    private static volatile Method pmUserActivity1ArgMethod;
    private static volatile boolean pmUserActivity1ArgResolved = false;
    private static volatile boolean pmUserActivity1ArgFailed = false;

    private static volatile Method pmUserActivity2ArgMethod;
    private static volatile boolean pmUserActivity2ArgResolved = false;
    private static volatile boolean pmUserActivity2ArgFailed = false;

    private static void resolvePmGetPowerScreenStatus() {
        if (pmGetPowerScreenStatusResolved || pmGetPowerScreenStatusFailed) return;
        try {
            pmGetPowerScreenStatusMethod = PowerManager.class.getMethod("getPowerScreenStatus");
            pmGetPowerScreenStatusResolved = true;
        } catch (NoSuchMethodException e) {
            pmGetPowerScreenStatusFailed = true;
        } catch (Exception e) {
            pmGetPowerScreenStatusFailed = true;
        }
    }

    private static void resolvePmUserActivity1Arg() {
        if (pmUserActivity1ArgResolved || pmUserActivity1ArgFailed) return;
        try {
            pmUserActivity1ArgMethod = PowerManager.class.getMethod("userActivity", long.class);
            pmUserActivity1ArgResolved = true;
        } catch (NoSuchMethodException e) {
            pmUserActivity1ArgFailed = true;
        } catch (Exception e) {
            pmUserActivity1ArgFailed = true;
        }
    }

    private static void resolvePmUserActivity2Arg() {
        if (pmUserActivity2ArgResolved || pmUserActivity2ArgFailed) return;
        try {
            pmUserActivity2ArgMethod = PowerManager.class.getMethod("userActivity", long.class, boolean.class);
            pmUserActivity2ArgResolved = true;
        } catch (NoSuchMethodException e) {
            pmUserActivity2ArgFailed = true;
        } catch (Exception e) {
            pmUserActivity2ArgFailed = true;
        }
    }

    // setBacklightState reflection cache. The probe order is:
    //   1. PowerManager lowercase (turnBacklightOn / turnBacklightOff)
    //   2. PowerManager PascalCase (TurnBacklightOn / TurnBacklightOff)
    //   3. BYDAutoSettingDevice lowercase (turnBacklightOn / turnBacklightOff)
    // The on-variant and off-variant are independent methods on the same
    // class, so each (variant, on/off) tuple has its own scalar volatile
    // fields. The "which class won" is implicit per variant: subsequent
    // calls hit whichever resolved first.
    //
    // First-call probing is preserved: each variant has its own
    // resolved/failed pair, so if lowercase is missing, the PascalCase
    // resolve still runs the first time. Once any one succeeds it short-
    // circuits subsequent lookups for that (variant, on/off) tuple.
    //
    // Scalar volatiles (rather than arrays) used to match the
    // RecordingModeManager.resolveBodyworkReflection memory-publication
    // pattern — boolean[] elements are not volatile in Java's MM.
    private static volatile Method pmBacklightLowerOnMethod;
    private static volatile Method pmBacklightLowerOffMethod;
    private static volatile boolean pmBacklightLowerOnResolved = false;
    private static volatile boolean pmBacklightLowerOnFailed = false;
    private static volatile boolean pmBacklightLowerOffResolved = false;
    private static volatile boolean pmBacklightLowerOffFailed = false;

    private static volatile Method pmBacklightPascalOnMethod;
    private static volatile Method pmBacklightPascalOffMethod;
    private static volatile boolean pmBacklightPascalOnResolved = false;
    private static volatile boolean pmBacklightPascalOnFailed = false;
    private static volatile boolean pmBacklightPascalOffResolved = false;
    private static volatile boolean pmBacklightPascalOffFailed = false;

    private static volatile Class<?> bydSettingDeviceClass;
    private static volatile Method bydSettingGetInstanceMethod;
    private static volatile boolean bydSettingDeviceResolved = false;
    private static volatile boolean bydSettingDeviceFailed = false;

    private static volatile Method bydSettingBacklightOnMethod;
    private static volatile Method bydSettingBacklightOffMethod;
    private static volatile boolean bydSettingBacklightOnResolved = false;
    private static volatile boolean bydSettingBacklightOnFailed = false;
    private static volatile boolean bydSettingBacklightOffResolved = false;
    private static volatile boolean bydSettingBacklightOffFailed = false;

    private static Method getPmBacklightLowerMethod(boolean on, Class<?> pmClass) {
        if (on) {
            if (pmBacklightLowerOnResolved) return pmBacklightLowerOnMethod;
            if (pmBacklightLowerOnFailed) return null;
            try {
                pmBacklightLowerOnMethod = pmClass.getMethod("turnBacklightOn", long.class);
                pmBacklightLowerOnResolved = true;
                return pmBacklightLowerOnMethod;
            } catch (Exception e) {
                pmBacklightLowerOnFailed = true;
                return null;
            }
        } else {
            if (pmBacklightLowerOffResolved) return pmBacklightLowerOffMethod;
            if (pmBacklightLowerOffFailed) return null;
            try {
                pmBacklightLowerOffMethod = pmClass.getMethod("turnBacklightOff", long.class);
                pmBacklightLowerOffResolved = true;
                return pmBacklightLowerOffMethod;
            } catch (Exception e) {
                pmBacklightLowerOffFailed = true;
                return null;
            }
        }
    }

    private static Method getPmBacklightPascalMethod(boolean on, Class<?> pmClass) {
        if (on) {
            if (pmBacklightPascalOnResolved) return pmBacklightPascalOnMethod;
            if (pmBacklightPascalOnFailed) return null;
            try {
                pmBacklightPascalOnMethod = pmClass.getMethod("TurnBacklightOn", long.class);
                pmBacklightPascalOnResolved = true;
                return pmBacklightPascalOnMethod;
            } catch (Exception e) {
                pmBacklightPascalOnFailed = true;
                return null;
            }
        } else {
            if (pmBacklightPascalOffResolved) return pmBacklightPascalOffMethod;
            if (pmBacklightPascalOffFailed) return null;
            try {
                pmBacklightPascalOffMethod = pmClass.getMethod("TurnBacklightOff", long.class);
                pmBacklightPascalOffResolved = true;
                return pmBacklightPascalOffMethod;
            } catch (Exception e) {
                pmBacklightPascalOffFailed = true;
                return null;
            }
        }
    }

    private static void resolveBydSettingDevice() {
        if (bydSettingDeviceResolved || bydSettingDeviceFailed) return;
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            bydSettingDeviceClass = cls;
            bydSettingGetInstanceMethod = getInstance;
            bydSettingDeviceResolved = true;
        } catch (Exception e) {
            bydSettingDeviceFailed = true;
        }
    }

    private static Method getBydSettingBacklightMethod(boolean on) {
        if (!bydSettingDeviceResolved) return null;
        if (on) {
            if (bydSettingBacklightOnResolved) return bydSettingBacklightOnMethod;
            if (bydSettingBacklightOnFailed) return null;
            try {
                bydSettingBacklightOnMethod = bydSettingDeviceClass.getMethod("turnBacklightOn");
                bydSettingBacklightOnResolved = true;
                return bydSettingBacklightOnMethod;
            } catch (Exception e) {
                bydSettingBacklightOnFailed = true;
                return null;
            }
        } else {
            if (bydSettingBacklightOffResolved) return bydSettingBacklightOffMethod;
            if (bydSettingBacklightOffFailed) return null;
            try {
                bydSettingBacklightOffMethod = bydSettingDeviceClass.getMethod("turnBacklightOff");
                bydSettingBacklightOffResolved = true;
                return bydSettingBacklightOffMethod;
            } catch (Exception e) {
                bydSettingBacklightOffFailed = true;
                return null;
            }
        }
    }

    /**
     * Uses Reflection to call PowerManager.userActivity()
     * This mimics the "Fake Touch" to keep CPU awake.
     *
     * CRITICAL: Checks screen status FIRST to avoid exceptions on some BYD firmware
     * where calling userActivity() when screen is OFF causes issues.
     */
    private static void injectFakeUserActivity() {
        if (appContext == null) return;

        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            PowerManager pm = (PowerManager) permissiveContext.getSystemService(Context.POWER_SERVICE);

            // CRITICAL: Check screen status FIRST ( pattern)
            // On some BYD firmware, calling userActivity() when screen is OFF fails
            resolvePmGetPowerScreenStatus();
            if (pmGetPowerScreenStatusResolved) {
                try {
                    int screenStatus = (Integer) pmGetPowerScreenStatusMethod.invoke(pm);
                    if (screenStatus == 0) {
                        // Screen is OFF - userActivity may fail or be ignored
                        // Skip it - the wakeUp call in performSystemWakeUp() handles keeping CPU alive
                        log("Screen OFF - skipping userActivity");
                        return;
                    }
                } catch (Exception e) {
                    // Per-call invocation failure (transient binder/access issue);
                    // do NOT mark resolution failed — proceed anyway, matching
                    // the original try/catch semantics.
                }
            }

            // DILINK 4 ONLY: prefer the 2-arg variant
            // userActivity(uptime, noChangeLights=true) — "reset the sleep
            // timer, but do NOT touch the lights". This is what the keep-alive
            // loop's own comment has always claimed it used, but the 1-arg
            // branch below returns first, so on any firmware that exposes
            // 1-arg (i.e. all of them) the noChangeLights call was unreachable.
            //
            // Why it matters on dilink4: this pump is the reason the parked panel
            // came back on. The 1-arg call resets the display's dim/off state
            // machine — i.e. it actively fights the backlight-off we just
            // performed. (The byd_apa reference app sidesteps this entirely: it
            // never calls userActivity at all, holding the AP awake with
            // PowerManager.wakeUp on a 60 s cadence instead. We keep the pump
            // because our USB-VBUS-follows-wakefulness requirement depends on
            // it, and just stop it from touching the lights.)
            //
            // Strictly gated: legacy pano_h/pano_l units keep the original
            // 1-arg-first order byte-for-byte. There the pump self-skips once
            // getPowerScreenStatus()==0 anyway, and that path is long-proven on
            // the fleet — no reason to re-sequence it. Falls through to the
            // 1-arg path below if this firmware has no 2-arg overload.
            if (isDilink4CameraMode()) {
                resolvePmUserActivity2Arg();
                if (pmUserActivity2ArgResolved) {
                    pmUserActivity2ArgMethod.invoke(
                        pm, android.os.SystemClock.uptimeMillis(), true);
                    log("userActivity(long, noChangeLights=true) called [dilink4 stealth]");
                    return;
                }
            }

            // 1-arg version ( style). Original semantics: only
            // NoSuchMethodException falls through to the 2-arg fallback;
            // invocation exceptions bubble to the outer catch. We preserve
            // that by gating only on the resolved flag (NoSuchMethodException
            // is now captured at resolve-time as failed=true) and letting
            // any invoke-time exception propagate.
            resolvePmUserActivity1Arg();
            if (pmUserActivity1ArgResolved) {
                pmUserActivity1ArgMethod.invoke(pm, android.os.SystemClock.uptimeMillis());
                log("userActivity(long) called");
                return;
            } else {
                // Preserved verbatim from the original ordering: on firmware with
                // no 1-arg overload this line fired BEFORE the 2-arg fallback was
                // attempted. Keeping it here (rather than folding it into an else
                // on the 2-arg branch) keeps the legacy log stream identical.
                log("userActivity: no compatible method found");
            }

            // Fallback: Try 2-arg version (stealth mode - doesn't turn on screen)
            // noChangeLights = true means "Reset the sleep timer, but don't turn on the screen"
            resolvePmUserActivity2Arg();
            if (pmUserActivity2ArgResolved) {
                pmUserActivity2ArgMethod.invoke(pm, android.os.SystemClock.uptimeMillis(), true);
                log("userActivity(long, boolean) called");
            }

        } catch (Exception e) {
            log("userActivity error: " + e.getMessage());
        }
    }

    private static void immediateWakeUpMcu() {
        log("IMMEDIATE MCU WAKE-UP...");
        
        if (wakeUpMcu()) {
            log("  MCU wake: OK");
        } else {
            log("  MCU wake: FAILED");
        }
    }

    /**
     * Force MCU wake-up for voltage-triggered charging cycles.
     * Called by VehicleDataListener when battery drops below threshold.
     * Also triggers system wake to ensure full power rail activation.
     */
    private static void forceMcuWakeUp() {
        log("VOLTAGE-TRIGGERED MCU WAKE-UP...");

        // Update wake timestamp
        lastMcuWakeTime = System.currentTimeMillis();

        // Wake the system first (ensures power rails are active) — GATED ON THE
        // "Keep USB powered" TOGGLE, matching enterSentryMode(). The forced AP wake
        // is what keeps USB powered while parked; when the user turned it OFF we must
        // NOT force the AP awake, or the emergency wake silently contradicts their
        // power-save preference. The MCU wake below (DC-DC converter) stays
        // unconditional — it is the actual low-battery emergency action.
        if (isKeepUsbPowerOnAccOff()) {
            performSystemWakeUp();
        } else {
            log("  Keep-USB-power OFF: skipping forced system wake (AP may stay asleep)");
        }

        // Then wake MCU to trigger DC-DC converter
        if (wakeUpMcu()) {
            log("  MCU wake: OK");
        }
        
        // Double-tap for reliability
        try {
            Thread.sleep(500);
            wakeUpMcu();
        } catch (InterruptedException ignored) {}
    }

    // ==================== ACTIVE VOLTAGE RECOVERY ====================
    
    /**
     * Schedule the V2 voltage monitor to start 35 s after ACC=OFF.
     * 35 s mirrors the sibling-app entry timer — gives the head unit time
     * to finish its own ACC-OFF housekeeping before we start writing
     * MCU sleep/wake events.
     *
     * <p>Uses {@link java.util.concurrent.ScheduledExecutorService} rather
     * than {@code Handler.postDelayed} because this daemon runs in
     * {@code app_process} with no main Looper —
     * {@code Looper.getMainLooper()} returns null and the Handler
     * constructor NPE'd. The executor is a single-shot, daemon thread.
     */
    private static void scheduleBatteryVoltageMonitorV2() {
        java.util.concurrent.ScheduledExecutorService exec =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BatteryV2-Schedule");
                t.setDaemon(true);
                return t;
            });
        exec.schedule(() -> {
            try {
                if (!inSentryMode) {
                    log("scheduleBatteryVoltageMonitorV2: exited sentry before delay — abort");
                    return;
                }
                com.overdrive.app.power.BatteryVoltageMonitorV2.startMonitor(appContext);
            } catch (Throwable t) {
                log("BatteryVoltageMonitorV2 start failed: " + t.getMessage());
            } finally {
                exec.shutdown();
            }
        }, 35_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        log("scheduleBatteryVoltageMonitorV2: in 35 s");
    }

    /**
     * REPLACED — superseded by {@link com.overdrive.app.power.BatteryVoltageMonitorV2}.
     * The old 45 s MCU-pulse loop drained the 12 V faster than it preserved
     * it on a parked car (no alternator load). Calls are now no-ops; the
     * V2 monitor is what does MCU sleep/wake hysteresis.
     */
    private static void startChargingMaintenance() {
        log("startChargingMaintenance: NO-OP (replaced by BatteryVoltageMonitorV2)");
        // Original body retained for reference:
        //   if (isVoltageChargingCycle && mcuChargingThread != null && mcuChargingThread.isAlive()) return;
        //   log("Starting Active Voltage Recovery (Target: " + HEALTHY_VOLTAGE_THRESHOLD + "V)...");
        //   isVoltageChargingCycle = true;
        //   mcuChargingThread = new Thread(() -> { while (isVoltageChargingCycle && running && inSentryMode) { try { forceMcuWakeUp(); Thread.sleep(MCU_CHARGE_PULSE_INTERVAL_MS); } catch (...) {} } }, "McuChargeLoop");
        //   mcuChargingThread.start();
    }

    /** REPLACED — see {@link #startChargingMaintenance}. */
    private static void stopChargingMaintenance() {
        log("stopChargingMaintenance: NO-OP (replaced by BatteryVoltageMonitorV2)");
        // Original body retained for reference:
        //   if (!isVoltageChargingCycle) return;
        //   log("Target voltage reached. Stopping Active Recovery.");
        //   isVoltageChargingCycle = false;
        //   if (mcuChargingThread != null) { mcuChargingThread.interrupt(); mcuChargingThread = null; }
    }

    // ==================== VEHICLE DATA MONITOR INTEGRATION ====================
    
    /**
     * Initialize VehicleDataMonitor and register listener for voltage-based MCU control.
     * Only initializes the 12V battery power monitor (not all monitors) for sentry mode.
     */
    private static void initVehicleDataMonitor() {
        if (appContext == null) {
            log("Cannot init VehicleDataMonitor: no context");
            return;
        }
        
        try {
            log("Initializing VehicleDataMonitor for voltage monitoring (battery power only)...");
            
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            
            // Initialize with our permissive context - ONLY battery power monitor
            Context permissiveContext = new PermissionBypassContext(appContext);
            monitor.initBatteryPowerOnly(permissiveContext);
            
            // Create and register our listener for voltage-based MCU control
            vehicleDataListener = new VehicleDataListener() {
                @Override
                public void onBatteryVoltageChanged(BatteryVoltageData data) {
                    // Discrete level changes (0=LOW, 1=NORMAL) - handled by AccListener
                }
                
                @Override
                public void onBatteryPowerChanged(BatteryPowerData data) {
                    // REPLACED — voltage hysteresis is now owned by
                    // BatteryVoltageMonitorV2 (12.0V wake / 12.5V sleep,
                    // 15-min defer). The 12V isCritical < 10.5V kill switch
                    // is also dropped — the new SoC-driven cutoff
                    // (SocCutoffMonitor, HV battery, default 10%) is the
                    // primary safety net. The replaced body remains for
                    // reference only:
                    //
                    //   if (!inSentryMode || data == null) return;
                    //   double voltage = data.voltageVolts;
                    //   if (!data.isValidRange()) { forceMcuWakeUp(); }
                    //   if (isVoltageChargingCycle) {
                    //       if (voltage >= HEALTHY_VOLTAGE_THRESHOLD) stopChargingMaintenance();
                    //   } else {
                    //       if (voltage <= LOW_VOLTAGE_THRESHOLD) startChargingMaintenance();
                    //   }
                    //   if (data.isCritical && surveillanceEnabled) disableSurveillance();
                    if (data != null) {
                        log("onBatteryPowerChanged " + String.format("%.2f", data.voltageVolts)
                                + "V (handled by BatteryVoltageMonitorV2)");
                    }
                }
                
                @Override
                public void onChargingStateChanged(ChargingStateData data) {
                    // Not used in sentry mode (battery power only)
                }
                
                @Override
                public void onChargingPowerChanged(double powerKW) {
                    // Not used in sentry mode (battery power only)
                }
                
                @Override
                public void onDataUnavailable(String monitorName, String reason) {
                    log("VehicleData unavailable: " + monitorName + " - " + reason);
                }
            };
            
            monitor.addListener(vehicleDataListener);
            monitor.startBatteryPowerOnly();
            
            log("VehicleDataMonitor initialized (battery power only)");
            
        } catch (Exception e) {
            log("VehicleDataMonitor init failed: " + e.getMessage());
        }
    }
    
    /**
     * Stop listening to VehicleDataMonitor (battery power only).
     */
    private static void stopVehicleDataMonitor() {
        try {
            log("Removing VehicleDataMonitor listener...");
            
            if (vehicleDataListener != null) {
                VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
                monitor.removeListener(vehicleDataListener);
                monitor.stopBatteryPowerOnly();
                vehicleDataListener = null;
            }
            
            // isVoltageChargingCycle = false;  // (state replaced — see V2 monitor)

            log("VehicleDataMonitor listener removed");
            
        } catch (Exception e) {
            log("VehicleDataMonitor cleanup failed: " + e.getMessage());
        }
    }

    // ==================== SURVEILLANCE ====================

    private static void enableSurveillance() {
        if (surveillanceEnabled) {
            // Silent return historically — added log so future diagnosis of
            // "why didn't surveillance arm this cycle?" doesn't cost a build
            // cycle. AccSentry's surveillanceEnabled flag is reset to false
            // by exitSentryMode and disableSurveillance, so reaching this
            // line means AccSentry believes a prior IPC succeeded within
            // the current sentry session — re-arming would just churn.
            // See feedback memory: diagnostic-log-paths.
            log("enableSurveillance: already enabled this sentry session — skipping");
            return;
        }

        // RACE CONDITION FIX: Check inSentryMode before attempting to enable.
        // If exitSentryMode() was called (ACC ON) while we were sleeping/retrying,
        // we must NOT enable surveillance.
        if (!inSentryMode) {
            log("enableSurveillance() aborted — no longer in sentry mode (ACC is ON)");
            return;
        }

        // Check if user has enabled surveillance in config
        // If not enabled, skip — don't auto-start on ACC OFF
        try {
            boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
            if (!userEnabled) {
                log("Surveillance NOT enabled in config — skipping auto-start on ACC OFF");
                return;
            }
        } catch (Exception e) {
            log("WARN: Could not read surveillance config: " + e.getMessage() + " — skipping auto-start");
            return;
        }

        log("Enabling surveillance...");

        // Check safe zone — don't start surveillance if parked in a safe zone.
        // Mark as suppressed so onLeftSafeZone() can re-arm if the car is towed out.
        try {
            com.overdrive.app.surveillance.SafeLocationManager safeLocMgr = 
                com.overdrive.app.surveillance.SafeLocationManager.getInstance();
            if (safeLocMgr.isFeatureEnabled() && safeLocMgr.isInSafeZone()) {
                log("In safe zone '" + safeLocMgr.getCurrentZoneName() + "' — skipping surveillance");
                CameraDaemon.setSafeZoneSuppressed(true);
                return;
            }
        } catch (Exception e) {
            log("Safe zone check failed: " + e.getMessage() + " — proceeding with surveillance");
        }

        // Check schedule — don't start surveillance outside configured time windows
        try {
            com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
            if (schedule != null && schedule.isEnabled() && !schedule.isActiveNow()) {
                log("SCHEDULE: Outside time window (" + schedule.getSummary() + ") — skipping surveillance");
                return;
            }
        } catch (Exception e) {
            log("Schedule check failed: " + e.getMessage() + " — proceeding with surveillance");
        }

        // Retry with backoff — CameraDaemon may not be up yet after boot
        int maxRetries = 10;
        long retryDelayMs = 3000; // Start with 3 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // RACE CONDITION FIX: Re-check inSentryMode on EVERY retry iteration.
            // exitSentryMode() sets inSentryMode=false, so if ACC turned ON during
            // our sleep between retries, we bail out immediately.
            if (!inSentryMode) {
                log("enableSurveillance() aborted at attempt " + attempt + " — no longer in sentry mode (ACC is ON)");
                return;
            }

            try {
                JSONObject cmd = new JSONObject();
                cmd.put("command", "SET_CONFIG");
                JSONObject config = new JSONObject();
                // NOTE: Do NOT send accOff=true here — it was already sent by
                // notifyAccState(true) in enterSentryMode(). Sending it again
                // causes CameraDaemon.onAccStateChanged to run twice, which
                // double-enables surveillance and resets the V2 pipeline.
                config.put("enabled", true);
                cmd.put("config", config);

                JSONObject response = sendSurveillanceCommandRaw(cmd);
                if (response != null && response.optBoolean("success", false)) {
                    // Final guard: verify we're still in sentry mode AFTER the IPC succeeded.
                    // There's a tiny window where exitSentryMode() could fire between the IPC
                    // send and this check — if so, immediately send a disable to undo it.
                    if (!inSentryMode) {
                        log("Surveillance enabled but ACC turned ON during IPC — immediately disabling");
                        disableSurveillance();
                        return;
                    }
                    surveillanceEnabled = true;
                    log("Surveillance ENABLED (attempt " + attempt + ")");
                    return;
                } else {
                    log("WARN: Surveillance enable failed (attempt " + attempt + "/" + maxRetries + "): " +
                        (response != null ? response.toString() : "null"));
                }
            } catch (Exception e) {
                log("WARN: Surveillance enable failed (attempt " + attempt + "/" + maxRetries + "): " + e.getMessage());
            }

            if (attempt < maxRetries) {
                try {
                    log("Retrying surveillance enable in " + (retryDelayMs / 1000) + "s...");
                    Thread.sleep(retryDelayMs);
                    retryDelayMs = Math.min(retryDelayMs + 2000, 10000); // Increase delay, cap at 10s
                } catch (InterruptedException e) {
                    log("Surveillance retry interrupted");
                    return;
                }
            }
        }

        log("ERROR: Failed to enable surveillance after " + maxRetries + " attempts — CameraDaemon may not be running");
    }

    private static void disableSurveillance() {
        // SOTA: Always attempt to disable when called — CameraDaemon may have enabled
        // surveillance independently (e.g., via the periodic schedule checker or the
        // 45-second fallback timer) without AccSentryDaemon knowing. Skipping based on
        // the local surveillanceEnabled flag would leave surveillance running when the
        // owner returns and unlocks the door.
        // Note: exitSentryMode() already sends notifyAccState(false) which triggers
        // CameraDaemon's full ACC ON path (pipeline.stop()), so this is a belt-and-suspenders
        // call. It's safe to send even if surveillance is already stopped.

        log("Disabling surveillance via IPC (battery protection / session stop)...");

        try {
            // Send stopSurveillance=true to stop motion detection without persisting
            // the preference change. This preserves the user's "surveillance enabled"
            // setting so it auto-starts on the next ACC OFF cycle.
            JSONObject cmd = new JSONObject();
            cmd.put("command", "SET_CONFIG");
            JSONObject config = new JSONObject();
            config.put("stopSurveillance", true);
            cmd.put("config", config);
            
            sendSurveillanceCommandRaw(cmd);
            surveillanceEnabled = false;
            log("Surveillance STOPPED via IPC (user preference preserved)");
        } catch (Exception e) {
            log("WARN: Failed to disable surveillance via IPC: " + e.getMessage());
        }
    }

    /**
     * Notify CameraDaemon of ACC state change.
     * This updates AccMonitor so HTTP API returns correct acc status.
     * 
     * @param accOff true if ACC is OFF, false if ACC is ON
     */
    
    // ==================== DOOR LOCK GATED SURVEILLANCE — DELETED ====================
    // Door-lock gating is owned by CameraDaemon (it has the cloud MQTT subscriber
    // in-process and BydDataCollector's typed HAL listener). AccSentryDaemon
    // delegates by calling notifyAccState() — see enterSentryMode() / exitSentryMode().
    
    /**
     * Read the current bodywork power level via reflection.
     *
     * Returns the raw int the HAL gave us (0..3 = real states, 4 = FAKE_OK,
     * 255 = INVALID, anything else = unknown). Returns -1 if reflection
     * itself failed (no HAL, no context). Callers MUST gate sentinel values
     * (4, 255, anything outside 0..3) — see startAccStateHeartbeat().
     *
     * Mirrors the inline reflection used in registerBodyworkListener; kept
     * standalone so the heartbeat doesn't have to re-register a listener
     * just to peek at the current level.
     */
    private static int readPowerLevel() {
        if (appContext == null) return -1;
        try {
            Class<?> deviceClass = Class.forName(
                "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, appContext);
            if (device == null) return -1;
            Method getPowerLevel = deviceClass.getMethod("getPowerLevel");
            return (Integer) getPowerLevel.invoke(device);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Periodic ACC state heartbeat — runs every 30s while the daemon is up
     * and a bodywork listener has been registered. Republishes the current
     * ACC state to CameraDaemon so a CameraDaemon restart mid-drive
     * resyncs within ≤30s instead of waiting for the next ACC edge (which
     * may never come if the user just keeps driving).
     *
     * Skips sentinel HAL readings (FAKE_OK=4, INVALID=255, anything outside
     * 0..3) — only publish definitive states. Same parsing as the
     * onPowerLevelChanged edge handler.
     */
    private static synchronized void startAccStateHeartbeat() {
        if (accHeartbeatThread != null && accHeartbeatThread.isAlive()) {
            return;
        }
        accHeartbeatThread = new Thread(() -> {
            log("ACC state heartbeat started (30s interval)");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException ie) {
                    return;
                }
                try {
                    int level = readPowerLevel();
                    // Gate on definitive levels only. 4 = FAKE_OK and
                    // 255 = INVALID are HAL bluffs; -1 is our reflection
                    // failure sentinel. Any of these → no publish.
                    //
                    // Also skip level=1 (ACC). During ignition there is a
                    // 200-500ms transient where the HAL emits level=1
                    // before settling at 2/3; a heartbeat tick landing in
                    // that window would publish accOff=true mid-startup
                    // and falsely flip CameraDaemon into the ACC OFF path.
                    // Edge handler in onPowerLevelChanged already maps
                    // level=1 correctly (drop-from-ON treated as OFF, no
                    // transition otherwise) — heartbeat doesn't need to
                    // duplicate that. See prior-audit "Heartbeat publishes
                    // during transient level=1 ACC-edge blip".
                    if (level == POWER_LEVEL_ACC) {
                        log("ACC heartbeat: level=ACC (1) is a transient — skipping publish");
                    } else if (level == POWER_LEVEL_OFF
                            || level == POWER_LEVEL_ON
                            || level == POWER_LEVEL_OK) {
                        boolean isAccOff = level < POWER_LEVEL_ON;
                        int desiredFlag = isAccOff ? 1 : 0;
                        // Equality short-circuit. CameraDaemon.onAccStateChanged
                        // runs cleanupDoorLockGate, surveillanceEnabled reset,
                        // DB write, OEM recalc on every IPC; none are
                        // unconditionally idempotent, so a 30s heartbeat
                        // republishing the same state churns those side
                        // effects forever. Only publish on actual change
                        // since last heartbeat publish (edge handler still
                        // owns transitions). See prior-audit
                        // "Heartbeat triggers full CameraDaemon
                        // .onAccStateChanged side-effects every 30s".
                        if (desiredFlag == lastHeartbeatPublishedAccOff) {
                            // No-op: state unchanged since last publish.
                            // Do not log every tick to avoid log flood —
                            // 2880 lines/day at 30s otherwise.
                            //
                            // EXCEPT: if we've been deduping for
                            // HEARTBEAT_FORCE_REPUBLISH_TICKS ticks (~1 min at
                            // 30s tick × 2 — was ~5min, dropped per prior-audit
                            // "Heartbeat dedup creates 5-minute pano wedge on
                            // CameraDaemon mid-drive restart"),
                            // republish anyway so a CameraDaemon process
                            // restart mid-drive (which resets its
                            // lastDispatchedAccIsOff cache to null) resyncs
                            // without waiting for the next bodywork edge.
                            // CameraDaemon's onAccStateChanged dedup drops
                            // the no-op IPC when state already matches, so
                            // this is a cheap heartbeat in steady state.
                            heartbeatDedupRunLength++;
                            if (heartbeatDedupRunLength >= HEARTBEAT_FORCE_REPUBLISH_TICKS) {
                                notifyAccState(isAccOff);
                                heartbeatDedupRunLength = 0;
                                log("ACC heartbeat: forced republish after "
                                    + HEARTBEAT_FORCE_REPUBLISH_TICKS
                                    + " dedup ticks (~1min) accOff=" + isAccOff
                                    + " — covers CameraDaemon process restart");
                            }
                        } else {
                            notifyAccState(isAccOff);
                            lastHeartbeatPublishedAccOff = desiredFlag;
                            heartbeatDedupRunLength = 0;
                            log("ACC heartbeat: level=" + powerLevelToString(level)
                                + " accOff=" + isAccOff + " (state changed since last heartbeat)");
                        }
                    }
                } catch (Throwable th) {
                    log("ACC heartbeat error: " + th.getMessage());
                }
            }
        }, "AccSentryHeartbeat");
        accHeartbeatThread.setDaemon(true);
        accHeartbeatThread.start();
    }

    /**
     * Slow-retry for bodywork listener registration — never gives up.
     *
     * Called when the initial 5×5s retry budget is exhausted. We can't run
     * as a wakelock-holding zombie with no event source forever, so we keep
     * trying every 60s. If/when registration eventually succeeds, the
     * heartbeat starts and the daemon recovers without any external
     * intervention. Per memory `feedback_watchdog_no_retry_cap.md` —
     * sentinel-only stop, no retry cap.
     */
    private static void startBodyworkSlowRetry(final Context context) {
        Thread slow = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && !bodyworkRegistered) {
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException ie) {
                    return;
                }
                try {
                    if (registerBodyworkListener(context)) {
                        bodyworkRegistered = true;
                        log("Bodywork slow-retry succeeded — heartbeat starting");
                        startAccStateHeartbeat();
                        return;
                    }
                } catch (Throwable th) {
                    log("Bodywork slow-retry error: " + th.getMessage());
                }
            }
        }, "AccSentrySlowRetry");
        slow.setDaemon(true);
        slow.start();
    }

    /**
     * Notify CameraDaemon of ACC state change.
     * This updates AccMonitor so HTTP API returns correct acc status.
     *
     * @param accOff true if ACC is OFF, false if ACC is ON
     */
    private static void notifyAccState(final boolean accOff) {
        // Dispatch onto the dedicated single-thread executor so the IPC
        // (with its 1s ConnectException retry sleep + up-to-2× 5s socket
        // timeout = ~11s worst case) never blocks the BYD HAL listener
        // thread. BYD's bodywork callbacks are single-threaded; if we
        // stall the listener inside onPowerLevelChanged the HAL drops
        // subsequent ACC edges. The executor is FIFO single-thread so
        // ordering of accOff transitions is preserved end-to-end. See
        // prior-audit "notifyAccState blocks BYD HAL listener thread up
        // to 11s on retry".
        try {
            accNotifyExecutor.execute(() -> {
                try {
                    JSONObject cmd = new JSONObject();
                    cmd.put("command", "SET_CONFIG");
                    JSONObject config = new JSONObject();
                    config.put("accOff", accOff);
                    cmd.put("config", config);

                    JSONObject resp = sendSurveillanceCommandRaw(cmd);
                    if (resp == null) {
                        // IPC failed (CameraDaemon not running, port not bound,
                        // socket timeout, etc). Reset heartbeat dedup state so
                        // the next heartbeat tick republishes unconditionally
                        // — covers the case where CameraDaemon was mid-restart
                        // and the heartbeat would otherwise wait
                        // HEARTBEAT_FORCE_REPUBLISH_TICKS to force-republish.
                        // Per prior-audit "Heartbeat dedup creates 5-minute
                        // pano wedge on CameraDaemon mid-drive restart".
                        lastHeartbeatPublishedAccOff = -1;
                        heartbeatDedupRunLength = 0;
                        log("WARN: ACC state IPC returned null — reset heartbeat dedup state for next tick");
                    } else if (!resp.optBoolean("success", false)) {
                        // Non-null reply but consumer reports partial-apply
                        // failure (CameraDaemon caught a side-effect throw
                        // and returned {success:false,error:...}). Treating
                        // this as success previously seeded dedup against a
                        // partial-apply consumer state, with CameraDaemon's
                        // own lastDispatchedAccIsOff cache then suppressing
                        // subsequent heartbeats. Keep dedup unseeded so the
                        // next heartbeat tick republishes unconditionally
                        // and lets the consumer rebuild correctly. Per
                        // prior-audit "notifyAccState treats {success:false}
                        // IPC reply as success → seeds dedup against a
                        // partial-apply on consumer side".
                        lastHeartbeatPublishedAccOff = -1;
                        heartbeatDedupRunLength = 0;
                        String err = resp.optString("error", "<no-error-field>");
                        log("WARN: ACC state IPC reply success=false (error="
                            + err + ") — kept heartbeat dedup unseeded so next tick republishes");
                    } else {
                        // Seed heartbeat dedup so the first heartbeat tick
                        // after startup / edge-driven notify is a true no-op
                        // rather than a redundant republish. Per prior-audit
                        // "lastHeartbeatPublishedAccOff not seeded by initial
                        // -state seed or edge handler" — collapses one extra
                        // IPC per startup and per ACC edge.
                        lastHeartbeatPublishedAccOff = accOff ? 1 : 0;
                        heartbeatDedupRunLength = 0;
                        log("ACC state notified to CameraDaemon: accOff=" + accOff
                            + " (seeded heartbeat dedup=" + lastHeartbeatPublishedAccOff + ")");
                    }
                } catch (Exception e) {
                    // Same dedup reset on exception path so a transient HAL
                    // / IPC fault doesn't silently delay resync by 5 min.
                    lastHeartbeatPublishedAccOff = -1;
                    heartbeatDedupRunLength = 0;
                    log("WARN: Failed to notify ACC state: " + e.getMessage()
                        + " — reset heartbeat dedup state");
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ree) {
            // Executor shut down (process tearing down) — fall back to
            // direct call so a final state update during shutdown still
            // makes it across the wire if at all possible.
            log("WARN: notifyAccState executor rejected (likely shutdown), running inline: "
                + ree.getMessage());
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("command", "SET_CONFIG");
                JSONObject config = new JSONObject();
                config.put("accOff", accOff);
                cmd.put("config", config);
                JSONObject resp = sendSurveillanceCommandRaw(cmd);
                if (resp == null) {
                    lastHeartbeatPublishedAccOff = -1;
                    heartbeatDedupRunLength = 0;
                    log("WARN: inline ACC state IPC returned null — reset heartbeat dedup state");
                } else if (!resp.optBoolean("success", false)) {
                    // Mirror the executor path: a non-null {success:false}
                    // reply means the consumer caught a partial-apply throw
                    // and may be holding a stale lastDispatchedAccIsOff.
                    // Don't seed dedup — let the next heartbeat republish
                    // unconditionally. Per prior-audit "notifyAccState
                    // treats {success:false} IPC reply as success".
                    lastHeartbeatPublishedAccOff = -1;
                    heartbeatDedupRunLength = 0;
                    String err = resp.optString("error", "<no-error-field>");
                    log("WARN: inline ACC state IPC reply success=false (error="
                        + err + ") — kept heartbeat dedup unseeded");
                } else {
                    // Seed dedup on inline-fallback success path too. Per
                    // prior-audit "lastHeartbeatPublishedAccOff not seeded
                    // by initial-state seed or edge handler".
                    lastHeartbeatPublishedAccOff = accOff ? 1 : 0;
                    heartbeatDedupRunLength = 0;
                    log("inline ACC state notified to CameraDaemon: accOff=" + accOff
                        + " (seeded heartbeat dedup=" + lastHeartbeatPublishedAccOff + ")");
                }
            } catch (Exception e) {
                lastHeartbeatPublishedAccOff = -1;
                heartbeatDedupRunLength = 0;
                log("WARN: inline notifyAccState fallback failed: " + e.getMessage()
                    + " — reset heartbeat dedup state");
            }
        }
    }

    private static JSONObject sendSurveillanceCommandRaw(JSONObject command) {
        // Bounded retry: 2 attempts, 1s backoff between them. Targets the
        // narrow case where CameraDaemon's IPC server is mid-bind (port not
        // yet listening) — without this, an edge ACC event could land in the
        // ~hundreds of ms gap and silently drop, leaving AccMonitor wedged
        // on stale state. Connection-refused only; other errors fail fast
        // (treat as the existing terminal path).
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket socket = null;
            try {
                // Bound the connect itself to 2s. `new Socket(host, port)`
                // uses the OS default connect timeout (~21s on Android),
                // so a half-stuck CameraDaemon (port bound but accept
                // stalled by HAL/init) would block this single-thread
                // executor for ~42s per IPC across 2 retries — backing
                // up subsequent ACC edges. See prior-audit
                // "sendSurveillanceCommandRaw lacks a connect timeout".
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", SURVEILLANCE_IPC_PORT), 2000);
                socket.setSoTimeout(5000);

                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                writer.println(command.toString());
                String responseLine = reader.readLine();

                return responseLine != null ? new JSONObject(responseLine) : null;
            } catch (java.net.ConnectException ce) {
                if (attempt == 0) {
                    log("IPC connect refused, retry in 1s");
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                log("IPC connect refused after retry: " + ce.getMessage());
                return null;
            } catch (java.net.SocketTimeoutException ste) {
                // Connect or read timed out. The 2s connect cap above
                // turns a half-stuck CameraDaemon listen queue into a
                // SocketTimeoutException; retry once with the same 1s
                // backoff as ConnectException so transient HAL stalls
                // self-heal. Read-side timeouts (5s SoTimeout) also land
                // here — same retry policy is fine since we're
                // idempotent on SET_CONFIG accOff.
                if (attempt == 0) {
                    log("WARN: IPC socket timeout (connect or read), retry in 1s: "
                        + ste.getMessage());
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
                log("WARN: IPC socket timeout after retry: " + ste.getMessage());
                return null;
            } catch (Exception e) {
                log("Surveillance IPC error: " + e.getMessage());
                return null;
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    // ==================== TELEGRAM DAEMON AUTO-START ====================
    
    private static final String TELEGRAM_CONFIG_FILE = null; // Lazy init
    private static String getTelegramConfigFile() { return PATH_TELEGRAM_CONFIG(); }
    private static final String TELEGRAM_DAEMON_PROCESS = "telegram_bot_daemon";
    
    /**
     * Check if Telegram daemon auto-start on ACC off is enabled.
     *
     * <p>True for EITHER signal: the parked-only {@code autoStartAccOff}
     * toggle, or {@code daemonEnabled} — the cross-UID mirror of the
     * Daemons-screen switch. The latter is what makes "I enabled the Telegram
     * daemon" survive a park: the app-side SharedPreferences that used to be
     * the only record of it is unreadable from this shell-UID process.
     */
    private static boolean isTelegramAutoStartEnabled() {
        try {
            // Force-reload so a toggle the user just flipped from the app UI
            // (different UID, different mtime tick) is visible immediately
            // rather than after the cache expires.
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            boolean enabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.shouldStartOnAccOff();
            log("Telegram start-on-ACC-off = " + enabled
                + " (autoStartAccOff=" + com.overdrive.app.telegram.config.UnifiedTelegramConfig.isAutoStartAccOff()
                + ", daemonEnabled=" + com.overdrive.app.telegram.config.UnifiedTelegramConfig.isDaemonEnabled() + ")");
            return enabled;
        } catch (Exception e) {
            log("Error reading telegram config: " + e.getMessage());
            return false;
        }
    }

    /**
     * ACC-ON stop gate. Deliberately NOT the same predicate as the start gate:
     * only the parked-only {@code autoStartAccOff} mode means "stop again when
     * the vehicle starts". A daemon the user switched on from the Daemons
     * screen must keep running across ACC-on.
     */
    private static boolean isTelegramParkedOnlyMode() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            boolean parkedOnly = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isAutoStartAccOff()
                    && !com.overdrive.app.telegram.config.UnifiedTelegramConfig.isDaemonEnabled();
            log("Telegram parked-only mode = " + parkedOnly);
            return parkedOnly;
        } catch (Exception e) {
            log("Error reading telegram config: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if Telegram daemon is running.
     */
    private static boolean isTelegramDaemonRunning() {
        String output = execShell("ps -A | grep " + TELEGRAM_DAEMON_PROCESS + " | grep -v grep");
        return output != null && !output.trim().isEmpty();
    }
    
    /**
     * Start Telegram daemon if auto-start is enabled.
     * Retries once if first attempt fails (APK path detection can be flaky when ACC is off).
     */
    private static void startTelegramDaemonIfEnabled() {
        log("Checking if Telegram daemon should auto-start...");
        
        if (!isTelegramAutoStartEnabled()) {
            log("Telegram auto-start not enabled, skipping");
            return;
        }
        
        // Check if the user explicitly stopped it. Two signals:
        //  (1) the durable disable sentinel — written by BOTH the UI stop and
        //      the Telegram stop, the single cross-UID source of truth. We're
        //      UID 2000 here so we can stat it directly.
        //  (2) the legacy daemon_telegram_state.properties — Telegram-only,
        //      kept for back-compat.
        // The sentinel is the important one: without this check, a daemon the
        // user stopped from the Daemons UI (which never wrote the .properties
        // file) would get silently resurrected on the next ACC-on.
        //
        // BUT the sentinel file is overloaded — three kinds of writer use it:
        //   "disabled by ui"/"disabled by telegram"  → a REAL user stop, honor it
        //   "disabled by ACC-on"                     → our own ACC arbitration pause
        //   "disabled by stopAllDaemons sweep"       → the app-update sweep
        //                                              (AppUpdater.stopAllDaemons)
        //
        // "ACC-on" — we wrote it ourselves on the last ACC-on edge, and
        // launchTelegramDaemon() rm's it before redeploying.
        // "stopAllDaemons sweep" — the app-update sweep, which is write-if-absent
        // (AppUpdater.stopAllDaemons), so this text can ONLY appear when no user
        // stop was already recorded. Both are therefore unambiguous machine
        // stops. Honouring the sweep text as a user stop is what previously left
        // a parked-only bot permanently unable to auto-start after any update,
        // since nothing clears the optional-daemon sentinels post-update.
        //
        // A missing/unreadable file also falls through to auto-start.
        java.io.File telegramSentinel =
            new java.io.File("/data/local/tmp/telegram_bot_daemon.disabled");
        if (telegramSentinel.exists()) {
            String reason = readSentinelReason(telegramSentinel);
            boolean machineStop = reason != null
                    && (reason.contains("ACC-on") || reason.contains("stopAllDaemons"));
            if (machineStop) {
                log("Telegram disable sentinel is a machine stop (" + reason
                    + "), not a user stop; proceeding to auto-start");
            } else {
                log("Telegram daemon disable sentinel present (user-stopped), not auto-starting");
                return;
            }
        }
        try {
            if (com.overdrive.app.daemon.telegram.DaemonCommandHandler.isDaemonStoppedViaTelegram("telegram")) {
                log("Telegram daemon was stopped via Telegram command, not auto-starting");
                return;
            }
        } catch (Exception e) {
            // State file not available, proceed with auto-start
        }
        
        if (isTelegramDaemonRunning()) {
            log("Telegram daemon already running");
            return;
        }
        
        // Try up to 2 times (APK path detection can fail when system is still waking up)
        for (int attempt = 1; attempt <= 2; attempt++) {
            log("Starting Telegram daemon (attempt " + attempt + "/2)...");
            
            if (attempt > 1) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
            
            try {
                launchTelegramDaemon();
                
                // Verify it started
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                
                if (isTelegramDaemonRunning()) {
                    log("Telegram daemon started successfully (attempt " + attempt + ")");
                    return;
                } else {
                    log("Telegram daemon not running after attempt " + attempt);
                    String logContent = execShell("tail -20 /data/local/tmp/telegrambotdaemon.log 2>/dev/null");
                    if (logContent != null && !logContent.isEmpty()) {
                        log("Telegram daemon log: " + logContent);
                    }
                }
            } catch (Exception e) {
                log("Telegram daemon launch error (attempt " + attempt + "): " + e.getMessage());
            }
        }
        
        log("WARN: Telegram daemon failed to start after 2 attempts");
    }

    /**
     * Read the first line of a disable sentinel so callers can tell a real
     * user stop ("disabled by ui"/"disabled by telegram") from an ACC-on
     * arbitration pause ("disabled by ACC-on"). Returns null if unreadable.
     */
    private static String readSentinelReason(java.io.File sentinel) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(sentinel)))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Launch the Telegram daemon process.
     */
    private static void launchTelegramDaemon() {
        
        // SOTA: Use pm path to get current APK path (most reliable method)
        // This ensures we always use the correct path even after app updates
        String apkPath = execShell("pm path com.overdrive.app 2>/dev/null | head -1 | cut -d: -f2");
        
        // Fallback to ls if pm path fails
        if (apkPath == null || apkPath.trim().isEmpty()) {
            log("pm path failed, using ls fallback");
            apkPath = execShell("ls /data/app/*/com.overdrive.app*/base.apk 2>/dev/null | head -1");
            if (apkPath == null || apkPath.trim().isEmpty()) {
                apkPath = execShell("ls /data/app/com.overdrive.app*/base.apk 2>/dev/null | head -1");
            }
        }
        
        if (apkPath == null || apkPath.trim().isEmpty()) {
            log("ERROR: Could not find APK path for com.overdrive.app");
            return;
        }
        
        apkPath = apkPath.trim();
        log("Using APK path: " + apkPath);

        // Clear the disable sentinel — ACC OFF path is explicitly starting
        // the daemon. Without this, the watchdog we're about to deploy
        // would gate-1 → exit 0 immediately because a previous ACC-on
        // stop left the sentinel on disk.
        execShell("rm -f /data/local/tmp/telegram_bot_daemon.disabled 2>/dev/null");

        // Kill any prior watchdog shells before deploying a fresh one. On
        // boot, this path can race the UI's DaemonStartupManager launch
        // — both write start_telegram.sh and `nohup sh` it, leaving two
        // watchdog shells alive. Each spawns the daemon; daemon's
        // killOldInstances kills the other watchdog's daemon; that
        // watchdog respawns; restart loop. The sentinel-gate alone can't
        // catch this because we just cleared the sentinel above.
        // ALSO kill the daemon itself for the same reason — without it,
        // an alive daemon from a stale watchdog would refuse our new
        // daemon's singleton lock.
        //
        // We can't use `pkill -f <pattern>` here: pkill -f matches against
        // /proc/<pid>/cmdline, and execShell wraps each command in
        // `sh -c "<cmd>"`. The wrapper's cmdline contains the literal
        // pattern (or the variable assignment text — pkill matches the
        // bytes regardless), so `pkill -f start_telegram.sh` would
        // SIGKILL its own parent shell. The "P=…; pkill -f \"$P\""
        // variable-hop trick was cargo-culted defense; the assignment
        // text "P=start_telegram.sh" still appears in argv and pkill
        // catches it.
        //
        // Use the ps+awk+kill pattern instead — it filters by PID list
        // and explicitly excludes the calling shell's own PID. This is
        // the same pattern TelegramBotDaemon.killOldInstances uses.
        execShell(
            "MY_PID=$$; "
            + "ps -A -o PID,ARGS | grep -F start_telegram.sh | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done; "
            + "ps -A -o PID,ARGS | grep -F " + TELEGRAM_DAEMON_PROCESS + " | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
        );
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        execShell("rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null");

        // Deploy the SAME shell watchdog script the UI uses
        // (DaemonLauncher.Companion.buildTelegramWatchdogScript). Without
        // a watchdog, a transient daemon crash leaves it dead until the
        // next ACC cycle or the next 30s in-process health-check tick
        // (only fires when MainActivity is alive). The watchdog respawns
        // on any non-zero exit, sentinel-gated for legitimate stops.
        String scriptPath = "/data/local/tmp/start_telegram.sh";
        try {
            // proxyArgs="" because AccSentry-launched daemon doesn't have
            // visibility into Android global HTTP proxy from this context.
            // Direct connection — Telegram bot's OkHttp proxies are
            // configured via UnifiedTelegramConfig at runtime.
            java.util.List<String> lines =
                com.overdrive.app.launcher.DaemonLauncher.Companion
                    .buildTelegramWatchdogScript(apkPath, "");
            // Write line-by-line — heredoc through execShell isn't reliable
            // across all toybox builds. Same pattern as
            // DaemonCommandHandler.startCameraDaemonWithWatchdog.
            execShell("rm -f " + scriptPath + " 2>/dev/null");
            boolean first = true;
            for (String line : lines) {
                String escaped = line
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("$", "\\$")
                    .replace("`", "\\`");
                String redirect = first ? " > " : " >> ";
                execShell("echo \"" + escaped + "\"" + redirect + scriptPath);
                first = false;
            }
            execShell("chmod 755 " + scriptPath);
        } catch (Throwable t) {
            log("Failed to deploy Telegram watchdog: " + t.getMessage()
                + " — falling back to bare nohup launch (no supervision)");
            String innerCmd = "CLASSPATH=" + apkPath + " "
                + "app_process /system/bin "
                + "--nice-name=" + TELEGRAM_DAEMON_PROCESS + " "
                + "com.overdrive.app.daemon.TelegramBotDaemon";
            String cmd = "nohup sh -c '" + innerCmd
                + "' > /data/local/tmp/telegrambotdaemon.log 2>&1 &";
            log("Telegram launch command (fallback): " + cmd);
            execShell(cmd);
            return;
        }

        // Run the watchdog. nohup so it survives the AccSentry shell's
        // exit; it execs the daemon binary in a loop.
        String launchCmd = "nohup sh " + scriptPath + " > /dev/null 2>&1 &";
        log("Telegram launch command (watchdog-supervised): " + launchCmd);
        execShell(launchCmd);
    }
    
    /**
     * Stop Telegram daemon if it was auto-started.
     */
    private static void stopTelegramDaemonIfAutoStarted() {
        if (!isTelegramParkedOnlyMode()) {
            log("Telegram not in parked-only mode, not stopping");
            return;
        }

        if (!isTelegramDaemonRunning()) {
            log("Telegram daemon not running");
            return;
        }

        // ACC-driven stop must plant the disable sentinel BEFORE pkill,
        // OTHERWISE the start_telegram.sh watchdog (deployed by the UI
        // launchTelegramDaemon path) will respawn the daemon within 60s
        // — exactly the loop ACC-on is meant to break. The sentinel
        // signals the watchdog to gate-1 → exit 0 cleanly. ACC-off path
        // (launchTelegramDaemon below) clears the sentinel before
        // re-deploying.
        //
        // Also rm the watchdog script so any orphan watchdog dies; lock
        // rm comes AFTER pkill+settle to prevent the lockfile resurrection
        // race. Mirrors DaemonLauncher.stopTelegramDaemon pattern.
        log("Stopping Telegram daemon (vehicle on)...");
        execShell(
            "echo \"disabled by ACC-on at $(date)\" > /data/local/tmp/telegram_bot_daemon.disabled; " +
            "chmod 666 /data/local/tmp/telegram_bot_daemon.disabled 2>/dev/null; " +
            "rm -f /data/local/tmp/start_telegram.sh 2>/dev/null"
        );
        // Kill the watchdog shell first so it can't respawn the daemon
        // between our pkill and the lock-rm. The sentinel-gate on its
        // next iteration would also stop it, but the watchdog's outer
        // 10-60 s sleep would let it spawn a daemon before noticing.
        //
        // pkill -f matches the FULL argv. The "P=…; pkill -f \"$P\""
        // variable-hop trick was cargo-cult: the assignment text is
        // also in argv and toybox pkill matches it. ps+awk+kill
        // filters by PID list and excludes the calling shell's own
        // PID via $$ — the same pattern TelegramBotDaemon's
        // killOldInstances uses. Mirror of the launchTelegramDaemon
        // path (line 2620) that we already fixed.
        execShell(
            "MY_PID=$$; "
            + "ps -A -o PID,ARGS | grep -F start_telegram.sh | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done; "
            + "ps -A -o PID,ARGS | grep -F " + TELEGRAM_DAEMON_PROCESS + " | grep -v grep | awk '{print $1}' "
            + "| while read pid; do if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
        );
        // Settle so SIGKILL'd daemon releases its lockfile before we rm
        // it (otherwise the daemon's still-flushing JVM rewrites the
        // lock between our rm and its actual death).
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        execShell("rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null");
        log("Telegram daemon stopped (sentinel-disabled)");
    }

    // ==================== CONTEXT HELPERS ====================

    private static Context getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = resolveActivityThread(activityThreadClass);
            if (activityThread == null) return null;
            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            return (Context) getSystemContext.invoke(activityThread);
        } catch (Exception e) {
            log("getSystemContext failed: " + e.getMessage());
            return null;
        }
    }

    private static Context createAppContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = resolveActivityThread(activityThreadClass);

            if (activityThread == null) {
                log("createAppContext: all strategies failed, using null-safe fallback");
                return new PermissionBypassContext(null);
            }

            Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            Context systemContext = (Context) getSystemContext.invoke(activityThread);
            if (systemContext == null) return new PermissionBypassContext(null);

            String packageName = APP_PACKAGE_NAME();
            Context appContext = systemContext.createPackageContext(packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            
            return new PermissionBypassContext(appContext);

        } catch (Exception e) {
            log("createAppContext failed: " + e.getMessage());
            return new PermissionBypassContext(null);
        }
    }

    private static Object resolveActivityThread(Class<?> activityThreadClass) {
        try {
            Method cur = activityThreadClass.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at != null) return at;
        } catch (Exception ignored) {}

        final Object[] result = new Object[1];
        try {
            Thread t = new Thread(() -> {
                try {
                    Method systemMain = activityThreadClass.getMethod("systemMain");
                    result[0] = systemMain.invoke(null);
                } catch (Exception ignored) {}
            }, "SystemMainInit");
            t.setDaemon(true);
            t.start();
            t.join(10_000);
            if (t.isAlive()) {
                log("resolveActivityThread: systemMain timed out");
                t.interrupt();
                try {
                    Method cur = activityThreadClass.getMethod("currentActivityThread");
                    Object at = cur.invoke(null);
                    if (at != null) return at;
                } catch (Exception ignored) {}
            } else if (result[0] != null) {
                return result[0];
            }
        } catch (Exception ignored) {}

        try {
            try { android.os.Looper.prepareMainLooper(); } catch (Exception ignored) {}
            java.lang.reflect.Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object at = ctor.newInstance();
            try {
                java.lang.reflect.Field f = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                f.setAccessible(true);
                f.set(null, at);
            } catch (Exception ignored) {}
            log("resolveActivityThread: manual creation succeeded");
            return at;
        } catch (Exception e) {
            log("resolveActivityThread: manual creation failed: " + e.getMessage());
        }

        return null;
    }
    
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) { super(base); }
        
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public Context getApplicationContext() {
            try { return super.getApplicationContext(); } catch (NullPointerException e) { return this; }
        }
        @Override public String getPackageName() {
            try { return super.getPackageName(); } catch (NullPointerException e) { return APP_PACKAGE_NAME(); }
        }
        @Override public Object getSystemService(String name) {
            try { return super.getSystemService(name); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            try { return super.getApplicationInfo(); } catch (NullPointerException e) { return new android.content.pm.ApplicationInfo(); }
        }
        @Override public android.content.ContentResolver getContentResolver() {
            try { return super.getContentResolver(); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.res.Resources getResources() {
            try { return super.getResources(); } catch (NullPointerException e) { return null; }
        }
        @Override public Context createPackageContext(String packageName, int flags) {
            try { return super.createPackageContext(packageName, flags); } catch (Exception e) { return this; }
        }
    }

    // ==================== INSTRUMENT DEVICE TEST ====================
    
    /**
     * Tests BYDAutoInstrumentDevice and BYDAutoStatisticDevice for charging data.
     */
    private static void testInstrumentDevice() {
        log("=== TESTING CHARGING DATA SOURCES ===");
        
        if (appContext == null) {
            log("ERROR: No context available");
            return;
        }
        
        try {
            Context permissiveContext = new PermissionBypassContext(appContext);
            
            // Test InstrumentDevice
            log("--- BYDAutoInstrumentDevice ---");
            Class<?> instrClazz = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstrInstance = instrClazz.getMethod("getInstance", Context.class);
            Object instrDevice = getInstrInstance.invoke(null, permissiveContext);
            
            if (instrDevice != null) {
                String[] instrGetters = {
                    "getExternalChargingPower",
                    "getChargePower",
                    "getChargePercent",
                    "getChargeRestTime",
                    "getOutCarTemperature"
                };
                
                for (String methodName : instrGetters) {
                    testGetter(instrClazz, instrDevice, methodName);
                }
            }
            
            // Test StatisticDevice ( uses this for SOC)
            log("--- BYDAutoStatisticDevice ---");
            Class<?> statClazz = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            Method getStatInstance = statClazz.getMethod("getInstance", Context.class);
            Object statDevice = getStatInstance.invoke(null, permissiveContext);
            
            if (statDevice != null) {
                String[] statGetters = {
                    "getElecPercentageValue",      // SOC % ( uses this!)
                    "getFuelPercentageValue",      // Fuel %
                    "getTotalElecConValue",        // Total kWh consumed
                    "getTotalFuelConValue",        // Total fuel consumed
                    "getEVMileageValue",           // EV range
                    "getWaterTemperature"          // Coolant temp
                };
                
                for (String methodName : statGetters) {
                    testGetter(statClazz, statDevice, methodName);
                }
                
                // Test getMileageNumber(int type)
                try {
                    Method m = statClazz.getMethod("getMileageNumber", int.class);
                    for (int type = 0; type <= 3; type++) {
                        Object result = m.invoke(statDevice, type);
                        log("  getMileageNumber(" + type + ") = " + result);
                    }
                } catch (Exception e) {
                    log("  getMileageNumber(int) = [ERROR]");
                }
            }
            
            // Test EnergyDevice
            log("--- BYDAutoEnergyDevice ---");
            Class<?> energyClazz = Class.forName("android.hardware.bydauto.energy.BYDAutoEnergyDevice");
            Method getEnergyInstance = energyClazz.getMethod("getInstance", Context.class);
            Object energyDevice = getEnergyInstance.invoke(null, permissiveContext);
            
            if (energyDevice != null) {
                String[] energyGetters = {
                    "getElecPercentageValue",
                    "getEnergyMode",
                    "getOperationMode",
                    "getEVMileageValue"
                };
                
                for (String methodName : energyGetters) {
                    testGetter(energyClazz, energyDevice, methodName);
                }
            }
            
            log("=== END CHARGING DATA TEST ===");
            
        } catch (Exception e) {
            log("ERROR testing devices: " + e.getMessage());
        }
    }
    
    private static void testGetter(Class<?> clazz, Object device, String methodName) {
        try {
            Method method = clazz.getMethod(methodName);
            Object result = method.invoke(device);
            
            String resultStr;
            if (result == null) {
                resultStr = "null";
            } else if (result instanceof int[]) {
                resultStr = java.util.Arrays.toString((int[]) result);
            } else if (result instanceof double[]) {
                resultStr = java.util.Arrays.toString((double[]) result);
            } else {
                resultStr = result.toString();
            }
            
            log("  " + methodName + "() = " + resultStr);
        } catch (NoSuchMethodException e) {
            log("  " + methodName + "() = [NOT FOUND]");
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log("  " + methodName + "() = [ERROR: " + msg + "]");
        }
    }

    // ==================== SHELL EXECUTION ====================

    /**
     * Disable BYD's built-in traffic monitor app.
     * It runs in the background consuming mobile data and battery.
     */
    private static void disableBydTrafficMonitor() {
        try {
            String result = execShell("pm disable-user --user 0 com.byd.trafficmonitor 2>&1");
            log("Disable BYD traffic monitor: " + result);
        } catch (Exception e) {
            log("Failed to disable BYD traffic monitor: " + e.getMessage());
        }
    }

    /**
     * "Vehicle ON only" parked reaper. Writes a detached shell script and launches it with
     * {@code nohup ... &} so it reparents to init and OUTLIVES this acc_sentry_daemon (which
     * it kills). Sequence:
     *   1. (Re-)plant the parked-shutdown marker (chmod 666) — the single arbiter that makes
     *      every watchdog gate-exit and every app-side rebuild path stand down.
     *   2. rm the watchdog start-scripts + cam_watchdog.pid so nothing can re-exec.
     *   3. sleep GRACE so (a) CameraDaemon's own parkTerminate (graceful H2 close, up to
     *      ~15s SD work) finishes and (b) any live watchdog loop re-checks the marker and
     *      exit 0's (≤2s). The marker is already set, so nothing rebuilds during the grace.
     *   4. Backstop psAwkKill any survivor (watchdog shells + daemon processes) — excludes
     *      the reaper's own PID; no pattern matches the reaper script name.
     *   5. rm stale *_daemon.lock, then self-delete. Does NOT clear the marker (that is the
     *      whole point — it stays until the ACC-on edge clears it).
     * This mirrors UpdateLifecycle.hardResetDaemons's proven kill cascade, swapped to the
     * parked marker and with no end-of-run marker clear.
     */
    private static void launchParkReaper() {
        final String marker = com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH;
        final String reaperPath = "/data/local/tmp/overdrive_park_reaper.sh";
        // psAwkKill helper line: kill by argv match, excluding our own PID; SIGKILL.
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        // 1. (Re-)assert the marker with the park timestamp (epoch millis), world rw.
        sb.append("echo ").append(System.currentTimeMillis()).append(" > ").append(marker).append(" 2>/dev/null\n");
        sb.append("chmod 666 ").append(marker).append(" 2>/dev/null\n");
        // 2. Remove re-exec ability immediately — core daemon watchdogs AND the optional
        //    tunnel/proxy watchdogs (start_zrok.sh etc.), or a surviving tunnel watchdog
        //    loop would re-exec its daemon during/after the grace window and keep the AP
        //    awake (sentry_proxy holds a /sys/power/wake_lock; zrok/tailscale loop-respawn).
        sb.append("rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/start_acc_sentry.sh ")
          .append("/data/local/tmp/start_telegram.sh /data/local/tmp/cam_watchdog.pid ")
          .append("/data/local/tmp/start_zrok.sh /data/local/tmp/start_singbox.sh ")
          .append("/data/local/tmp/start_cloudflared.sh /data/local/tmp/start_tailscale.sh 2>/dev/null\n");
        // 3. Grace window: let CameraDaemon.parkTerminate finish its graceful close (it
        //    owns the H2 recordings/trips DBs — a SIGKILL mid-close risks corruption) and
        //    let watchdog loops gate-exit on the marker. Instead of a blind fixed sleep,
        //    POLL for CameraDaemon's absence up to a ceiling and break the instant it's
        //    gone, so a graceful self-terminate that legitimately takes ~15-18s (slow SD
        //    force-remount: 8s sm-mount + 10s FUSE poll, all BEFORE the H2 close) is never
        //    clobbered. Ceiling comfortably exceeds the code's own worst case (~30s). Only
        //    survivors past the ceiling get the backstop kill below.
        sb.append("i=0\n");
        sb.append("while [ $i -lt 40 ]; do\n");
        sb.append("  if ! ps -A -o ARGS 2>/dev/null | grep -F 'byd_cam_daemon' | grep -qv grep; then break; fi\n");
        sb.append("  sleep 1\n");
        sb.append("  i=$((i+1))\n");
        sb.append("done\n");
        // Small settle so the other daemons/watchdogs also observe the marker and exit.
        sb.append("sleep 2\n");
        // 4. Backstop kill of any survivor: core daemon watchdogs + daemon app_process
        //    children, AND the optional tunnel/proxy stack (sentry_proxy holds a wakelock;
        //    the others hold the network / loop-respawn). Mirrors the UpdateLifecycle /
        //    AppUpdater kill lists, which include these exact process names.
        String[] patterns = {
            "start_cam_daemon", "start_acc_sentry", "start_telegram",
            "byd_cam_daemon", "cam_daemon", "sentry_daemon", "acc_sentry_daemon", "telegram_bot_daemon",
            "start_zrok", "start_singbox", "start_cloudflared", "start_tailscale",
            "sentry_proxy", "sing-box", "cloudflared", "zrok", "tailscaled"
        };
        for (String p : patterns) {
            sb.append("MY_PID=$$; ps -A -o PID,ARGS | grep -F '").append(p).append("' | grep -v grep")
              .append(" | awk '{print $1}' | while read pid; do")
              .append(" if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n");
        }
        // 4b. Explicitly release sentry_proxy's KERNEL wakelock. GlobalProxyDaemon acquires
        //     a userspace sysfs wakelock via `echo GlobalProxyDaemon > /sys/power/wake_lock`
        //     (+ `svc power stayon true` fallback). That is a NAMED GLOBAL wakeup source not
        //     bound to the process — kill -9 above does NOT run its JVM shutdown hook, so the
        //     kernel wakelock (and stayon) would persist and pin the AP awake for the whole
        //     park, defeating onOnly. Release it directly here, matching
        //     GlobalProxyDaemon.releaseWakeLock (tag "GlobalProxyDaemon"). Harmless no-op if
        //     the proxy was never running / the sysfs node is absent.
        sb.append("echo GlobalProxyDaemon > /sys/power/wake_unlock 2>/dev/null\n");
        sb.append("svc power stayon false 2>/dev/null\n");
        // 5. Clean stale locks, then self-delete. Do NOT clear the marker.
        sb.append("sleep 1\n");
        sb.append("rm -f /data/local/tmp/*_daemon.lock 2>/dev/null\n");
        sb.append("rm -f ").append(reaperPath).append(" 2>/dev/null\n");
        try {
            java.io.FileWriter fw = new java.io.FileWriter(reaperPath);
            fw.write(sb.toString());
            fw.close();
            try { new java.io.File(reaperPath).setReadable(true, false);
                  new java.io.File(reaperPath).setExecutable(true, false); } catch (Exception ignored) {}
            // Detached launch: reparent to init so it survives our imminent death.
            Runtime.getRuntime().exec(new String[]{"sh", "-c",
                "nohup sh " + reaperPath + " >/dev/null 2>&1 &"});
            log("Park reaper launched (detached): " + reaperPath);
        } catch (Exception e) {
            log("WARNING: failed to launch park reaper: " + e.getMessage());
            // Fallback: at least plant the marker inline so watchdogs stand down even if the
            // detached reaper couldn't launch. The app-side standdown still runs off ACC_OFF.
            execShell("echo " + System.currentTimeMillis() + " > " + marker + " 2>/dev/null; chmod 666 " + marker + " 2>/dev/null");
        }
    }

    private static String execShell(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            process.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            return output.toString().trim();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
    
    // ==================== MONITORING & DIAGNOSTICS ====================
    
    /**
     * Install shutdown hook to detect process termination.
     * This helps debug why the daemon might be dying.
     */
    private static void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("=== SHUTDOWN HOOK TRIGGERED ===");
            log("Reason: Process is being terminated");
            log("Uptime: " + (System.currentTimeMillis() - startTime) / 1000 + "s");
            log("InSentryMode: " + inSentryMode);
            log("Running flag: " + running);
            
            // Try to determine why we're dying
            try {
                String ps = execShell("ps -p " + android.os.Process.myPid());
                log("Process status before death: " + ps);
            } catch (Exception e) {
                log("Could not get process status: " + e.getMessage());
            }
            
            // Check wake lock status
            if (wakeLock != null) {
                try {
                    log("WakeLock held: " + wakeLock.isHeld());
                } catch (Exception e) {
                    log("Could not check WakeLock: " + e.getMessage());
                }
            }
            
            // Log memory status at death
            try {
                logMemoryStatus();
            } catch (Exception e) {
                log("Could not log memory status: " + e.getMessage());
            }
            
            log("=== SHUTDOWN COMPLETE ===");
        }, "ShutdownHook"));
        
        log("Shutdown hook installed");
    }
    
    /**
     * Log current memory status.
     * Helps detect if we're being killed due to low memory.
     */
    private static void logMemoryStatus() {
        if (appContext == null) {
            log("Cannot log memory status: no context");
            return;
        }
        
        try {
            android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
            android.app.ActivityManager am = (android.app.ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            
            if (am != null) {
                am.getMemoryInfo(memInfo);
                long availMB = memInfo.availMem / 1024 / 1024;
                long totalMB = memInfo.totalMem / 1024 / 1024;
                long usedMB = totalMB - availMB;
                
                log("=== MEMORY STATUS ===");
                log("  Available: " + availMB + " MB");
                log("  Total: " + totalMB + " MB");
                log("  Used: " + usedMB + " MB");
                log("  Low memory: " + memInfo.lowMemory);
                log("  Threshold: " + (memInfo.threshold / 1024 / 1024) + " MB");
            } else {
                log("ActivityManager is null");
            }
        } catch (Exception e) {
            log("Error logging memory status: " + e.getMessage());
        }
    }
    
    /**
     * Start periodic status monitoring.
     * Logs daemon health every 60 seconds for debugging.
     */
    private static void startStatusMonitoring() {
        if (statusHandler == null) {
            log("Cannot start status monitoring: no handler");
            return;
        }
        
        final Runnable statusCheck = new Runnable() {
            @Override
            public void run() {
                try {
                    long uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000;
                    long uptimeMinutes = uptimeSeconds / 60;
                    
                    log("=== STATUS CHECK ===");
                    log("  Uptime: " + uptimeMinutes + "m " + (uptimeSeconds % 60) + "s");
                    log("  WakeLock: " + (wakeLock != null && wakeLock.isHeld()));
                    log("  InSentryMode: " + inSentryMode);
                    log("  Running: " + running);
                    log("  KeepAlive thread: " + (systemKeepAliveThread != null && systemKeepAliveThread.isAlive()));
                    // Charging thread replaced by BatteryVoltageMonitorV2 — handler-thread, no liveness probe needed.
                    // log("  Charging thread: " + (mcuChargingThread != null && mcuChargingThread.isAlive()));
                    log("  Surveillance: " + surveillanceEnabled);
                    log("  Last power level: " + powerLevelToString(lastPowerLevel));
                    log("  Last MCU status: " + lastMcuStatus);
                    
                    // Check MCU status
                    int currentMcuStatus = getMcuStatus();
                    if (currentMcuStatus != -1) {
                        log("  Current MCU status: " + currentMcuStatus);
                    }
                    
                    // Log memory every 5 minutes
                    if (uptimeMinutes % 5 == 0) {
                        logMemoryStatus();
                    }
                    
                    log("===================");
                    
                } catch (Exception e) {
                    log("Status check error: " + e.getMessage());
                }
                
                // Schedule next check
                if (running && statusHandler != null) {
                    statusHandler.postDelayed(this, 60000);  // 60 seconds
                }
            }
        };
        
        // Start first check after 60 seconds
        statusHandler.postDelayed(statusCheck, 60000);
        log("Status monitoring started (60s interval)");
    }
    
    /**
     * Stop periodic status monitoring.
     */
    private static void stopStatusMonitoring() {
        if (statusHandler != null) {
            statusHandler.removeCallbacksAndMessages(null);
            log("Status monitoring stopped");
        }
    }
}
