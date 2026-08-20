package com.overdrive.app.power;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * MCU power-rail control via BYD HAL device-event writes.
 *
 * <p>Two surfaces:
 * <ol>
 *   <li><b>MCU sleep/wake</b> — {@code BYDAutoPowerDevice.set(new int[]{-1442840502}, BYDAutoEventValue.intValue=0/1)}.
 *       value=0 → request MCU sleep; value=1 → request MCU wake. The MCU
 *       gates the DC-DC converter that runs the head-unit + camera ISP rails;
 *       letting it sleep when the 12V is healthy is what makes ACC=OFF
 *       surveillance not drain the battery in 4 hours.</li>
 *   <li><b>Sentry-mode MCU</b> — {@code BYDAutoSpecialDevice.set(new int[]{1901}, …)} +
 *       {@code set(new int[]{1902}, …)}. Sleep: 1901→0, 1902→2.
 *       Wake: 1901→1, 1902→1.</li>
 * </ol>
 *
 * <p>All device handles are resolved process-locally from the caller-supplied
 * {@code appContext}. Reaching into {@code BydDataCollector.getInstance()}
 * doesn't work cross-process — that singleton is per-process and is only
 * initialised in cam_daemon. Other daemons (e.g. acc_sentry) get a fresh
 * empty collector.
 *
 * <p>Class FQNs:
 * <ul>
 *   <li>{@code android.hardware.bydauto.power.BYDAutoPowerDevice} — stable
 *       across the fleet.</li>
 *   <li>BYDAutoSpecialDevice lives in <b>one of two packages</b> depending on
 *       the trim, so it is probed in order — see
 *       {@link #SPECIAL_CLASS_CANDIDATES}. The {@code .bydauto.special.} form
 *       was verified on DiLink 3.0; the reference OEM dashcam app uses the bare
 *       {@code .special.} form, verified in its raw dex string table.
 *       Hardcoding only the former made every
 *       sentry-mode write a silent no-op on trims that ship the latter.</li>
 * </ul>
 *
 * <p>All calls are best-effort. When the underlying HAL/class is missing on a
 * trim, the call returns false and logs at info level. Caller must not rely
 * on success — guard surveillance behaviour with separate state.
 */
public final class McuPowerHal {

    private static final String TAG = "McuPowerHal";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Event-type constant for MCU sleep/wake writes. */
    public static final int EVENT_MCU_SLEEP_WAKE = -1442840502;

    /** Sentry-mode special-device feature keys. */
    public static final int SENTRY_KEY_1 = 1901;
    public static final int SENTRY_KEY_2 = 1902;

    /** Power-device class FQN. */
    private static final String POWER_CLASS = "android.hardware.bydauto.power.BYDAutoPowerDevice";

    /**
     * Candidate FQNs for BYDAutoSpecialDevice, probed in declaration order.
     *
     * <p>The {@code .bydauto.special.} form was verified on DiLink 3.0. The reference
     * reference app ships the BARE {@code .special.} form — confirmed in its raw dex
     * string table ({@code /Landroid/hardware/special/BYDAutoSpecialDevice;}), and
     * corroborated by the BYD SDK javadoc in {@code doc/}, which lists 20
     * {@code android.hardware.bydauto.*} packages and no {@code special} one.
     *
     * <p>Order matters and is deliberately legacy-first: any trim that already
     * resolved the {@code .bydauto.special.} class keeps resolving the exact same
     * class, so its behaviour is bit-identical.
     *
     * <p><b>The bare fallback is DiLink 4 only</b>, enforced in
     * {@link #resolveSpecialDevice()}. It is tempting to treat the extra candidate as
     * "strictly additive because the old path already returned null" — that is WRONG
     * here. {@code BatteryVoltageMonitorV2} runs on <i>every</i> variant, not just
     * DiLink 4, and its {@link #requestSentryWake}/{@link #requestSentrySleep} calls
     * flow straight into this resolver. On a legacy trim that ships only the bare
     * class, those sentry-key writes are currently inert no-ops; letting them start
     * landing would newly drive vehicle sentry-mode flags (1901/1902 and the 0x2EA0
     * pair) on hardware we have never exercised them against. That is a behaviour
     * change to the 90% fleet, so the fallback stays gated.
     */
    private static final String[] SPECIAL_CLASS_CANDIDATES = {
        "android.hardware.bydauto.special.BYDAutoSpecialDevice",
        "android.hardware.special.BYDAutoSpecialDevice",
    };

    /** Index into {@link #SPECIAL_CLASS_CANDIDATES} of the first DiLink4-only entry. */
    private static final int SPECIAL_CLASS_LEGACY_CANDIDATE_COUNT = 1;

    /**
     * True when the user has selected DiLink 4 (byd_apa) camera mode.
     *
     * <p>Read directly from config rather than by calling into a daemon — this class
     * runs in whichever process boots it. Mirrors
     * {@code AccSentryDaemon.isDilink4CameraMode()} exactly, including the
     * fail-closed default, so a transient read error can never expand the write set
     * on a legacy trim.
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
     * Process-local context handed in by whichever daemon boots us. Required
     * for both power and special device {@code getInstance} calls.
     */
    private static volatile android.content.Context appContext;

    private static volatile Object cachedPowerDevice;
    private static volatile Object cachedSpecialDevice;

    private McuPowerHal() {}

    /**
     * Daemons set this to their own (PermissionBypass-wrapped) appContext.
     * Must be called before any {@code request*} call — without it every
     * request silently no-ops because the device classes need a Context.
     */
    public static void setAppContext(android.content.Context ctx) {
        appContext = ctx;
        // Drop cached handles — they were resolved against the previous
        // (possibly null) context and may be unusable.
        cachedPowerDevice = null;
        cachedSpecialDevice = null;
    }

    // ── MCU sleep/wake on BYDAutoPowerDevice ──────────────────────────

    /** Request MCU sleep ({@code EVENT_MCU_SLEEP_WAKE} ← 0). */
    public static boolean requestMcuSleep() {
        Object device = resolvePowerDevice();
        if (device == null) {
            logger.info("requestMcuSleep: power device unresolved — no-op");
            return false;
        }
        boolean ok = writeConfirmed(device, EVENT_MCU_SLEEP_WAKE, 0, "mcuSleep -1442840502<-0");
        logger.info("requestMcuSleep -> " + ok);
        return ok;
    }

    /** Request MCU wake ({@code EVENT_MCU_SLEEP_WAKE} ← 1). */
    public static boolean requestMcuWake() {
        Object device = resolvePowerDevice();
        if (device == null) {
            logger.info("requestMcuWake: power device unresolved — no-op");
            return false;
        }
        boolean ok = writeConfirmed(device, EVENT_MCU_SLEEP_WAKE, 1, "mcuWake -1442840502<-1");
        logger.info("requestMcuWake -> " + ok);
        return ok;
    }

    // ── Sentry-mode keys on BYDAutoSpecialDevice ─────────────────────

    // The OEM SENTRY-MODE pair, per the DiCarServer feature catalog
    // (dev/byd-property-bus/dicarserver_feature_catalog.txt):
    //   782237711 = 0x2EA0000F SPECIAL_LOCAL_CTL_ENTER_SENTRY_MODE_SET
    //   782237728 = 0x2EA00020 SPECIAL_SENTRY_MODE_SET
    // So these are NOT "5V rail" / "modem-USB rail" holds, despite how they are labelled
    // elsewhere in this codebase — they are the vehicle's own sentry/surveillance-mode
    // enter+state flags on BYDAutoSpecialDevice. Holding sentry mode is what keeps the MCU
    // from cutting the rails the cameras need, which is why they LOOK like rail holds.
    //
    // The 1901/1902 writes below are untouched. These are written IN ADDITION because the two
    // pairs are not interchangeable — 1901/1902 are bare small integers with no BYD namespace
    // prefix, while these are properly-formed 0x2EA0 ids, so a trim whose HAL ignores one may
    // honour the other. Both use the same value convention here (sleep 0/2, wake 1/1), so the
    // two writes agree and can never contradict each other.
    //
    // GATED TO DILINK 4 — deliberately NOT fleet-wide. It is tempting to call the extra pair
    // "additive because it's just two more writes", but that is the same mistake the
    // SPECIAL_CLASS_CANDIDATES note above warns about: BatteryVoltageMonitorV2 drives
    // requestSentrySleep/Wake on EVERY variant, so on a legacy trim these ids would start
    // driving real vehicle sentry-mode flags that have never been exercised on that hardware.
    // A write that lands is a behaviour change even when the surrounding code is unchanged.
    // DiLink 4 is where the AVM rail problem lives and where the reference behaviour was
    // observed, so that is the only place this runs; the 90% fleet stays bit-identical.
    private static final int SENTRY_MODE_ENTER = 782237711;  // 0x2EA0000F
    private static final int SENTRY_MODE_STATE = 782237728;  // 0x2EA00020

    /** Request sentry-mode sleep — writes 1901←0, 1902←2 plus the 0x2EA0 sentry pair (0/2). */
    public static boolean requestSentrySleep() {
        Object device = resolveSpecialDevice();
        if (device == null) {
            logger.info("requestSentrySleep: BYDAutoSpecialDevice unavailable — no-op");
            return false;
        }
        boolean a = writeConfirmed(device, SENTRY_KEY_1, 0, "sleep 1901<-0");
        boolean b = writeConfirmed(device, SENTRY_KEY_2, 2, "sleep 1902<-2");
        // 0x2EA0 pair: DiLink 4 only (see the constants' note — a landing write on a legacy
        // trim is a behaviour change to the 90% fleet).
        boolean c = false, d = false;
        if (isDilink4CameraMode()) {
            c = writeConfirmed(device, SENTRY_MODE_ENTER, 0, "sleep 782237711<-0");
            d = writeConfirmed(device, SENTRY_MODE_STATE, 2, "sleep 782237728<-2");
            logger.info("requestSentrySleep 1901<-0=" + a + " 1902<-2=" + b
                    + " 782237711<-0=" + c + " 782237728<-2=" + d);
            // Either pair landing is a real sleep request — don't fail the call just because
            // the trim ignores one id family.
            return (a && b) || (c && d);
        }
        logger.info("requestSentrySleep 1901<-0=" + a + " 1902<-2=" + b);
        return a && b;
    }

    /** Request sentry-mode wake — writes 1901←1, 1902←1 plus the 0x2EA0 sentry pair (1/1). */
    public static boolean requestSentryWake() {
        Object device = resolveSpecialDevice();
        if (device == null) {
            logger.info("requestSentryWake: BYDAutoSpecialDevice unavailable — no-op");
            return false;
        }
        boolean a = writeConfirmed(device, SENTRY_KEY_1, 1, "wake 1901<-1");
        boolean b = writeConfirmed(device, SENTRY_KEY_2, 1, "wake 1902<-1");
        // 0x2EA0 pair: DiLink 4 only — see requestSentrySleep and the constants' note.
        boolean c = false, d = false;
        if (isDilink4CameraMode()) {
            c = writeConfirmed(device, SENTRY_MODE_ENTER, 1, "wake 782237711<-1");
            d = writeConfirmed(device, SENTRY_MODE_STATE, 1, "wake 782237728<-1");
            logger.info("requestSentryWake 1901<-1=" + a + " 1902<-1=" + b
                    + " 782237711<-1=" + c + " 782237728<-1=" + d);
            return (a && b) || (c && d);
        }
        logger.info("requestSentryWake 1901<-1=" + a + " 1902<-1=" + b);
        return a && b;
    }

    // ── Internals ────────────────────────────────────────────────────

    /**
     * Write one feature id and report whether the HAL actually CONFIRMED it.
     *
     * <p>Why not {@code BydDeviceHelper.sendSetCommand}: that helper maps
     * {@code code >= 0} to true, but the BYD HAL contract is {@code code == 0} for
     * success — a positive non-zero code is a real failure, and
     * {@code sendSetCommandRaw} additionally returns 0 for "non-null result, assume
     * success" when the SDK hands back something it can't interpret. Under the
     * loose rule these all logged as {@code true}, so a field log showing
     * "requestSentryWake 1901<-1=true" told us nothing about whether the rail was
     * actually held. Every log line here is now the RAW code, which is what we
     * need to tell "write landed" from "write silently rejected".
     *
     * <p>Scoped deliberately to this class. The shared
     * {@code BydDeviceHelper.sendSetCommand} is used by many other subsystems
     * across all variants; tightening it globally would change behaviour far
     * outside the DiLink 4 ACC-off path, so it is left exactly as-is.
     *
     * @return true only when the HAL returned the success code 0.
     */
    private static boolean writeConfirmed(Object device, int featureId, int value, String label) {
        int code = BydDeviceHelper.sendSetCommandRaw(device, featureId, value);
        boolean confirmed = (code == 0);
        if (!confirmed) {
            logger.info(label + " NOT confirmed (rc=" + code + ")");
        }
        return confirmed;
    }

    private static Object resolvePowerDevice() {
        if (cachedPowerDevice != null) return cachedPowerDevice;
        if (appContext == null) {
            logger.debug("resolvePowerDevice: no appContext — call setAppContext first");
            return null;
        }
        try {
            Class<?> cls = Class.forName(POWER_CLASS);
            Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
            cachedPowerDevice = getInstance.invoke(null, appContext);
            if (cachedPowerDevice != null) {
                logger.info("resolvePowerDevice: " + cachedPowerDevice.getClass().getName());
            }
        } catch (Throwable t) {
            logger.debug("BYDAutoPowerDevice.getInstance failed: " + t.getMessage());
        }
        return cachedPowerDevice;
    }

    private static Object resolveSpecialDevice() {
        if (cachedSpecialDevice != null) return cachedSpecialDevice;
        if (appContext == null) {
            logger.debug("resolveSpecialDevice: no appContext — call setAppContext first");
            return null;
        }
        // Legacy trims probe ONLY the historically-resolved FQN, so their write set
        // is byte-identical to before. The bare-package fallback is DiLink 4 only —
        // see SPECIAL_CLASS_CANDIDATES for why this must not be "additive for all".
        int limit = isDilink4CameraMode()
                ? SPECIAL_CLASS_CANDIDATES.length
                : SPECIAL_CLASS_LEGACY_CANDIDATE_COUNT;
        for (int i = 0; i < limit; i++) {
            String fqn = SPECIAL_CLASS_CANDIDATES[i];
            try {
                Class<?> cls = Class.forName(fqn);
                Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
                Object device = getInstance.invoke(null, appContext);
                if (device == null) {
                    logger.debug("resolveSpecialDevice: " + fqn + " getInstance returned null");
                    continue;
                }
                cachedSpecialDevice = device;
                logger.info("resolveSpecialDevice: " + fqn
                        + " -> " + device.getClass().getName());
                return cachedSpecialDevice;
            } catch (ClassNotFoundException e) {
                logger.debug("resolveSpecialDevice: " + fqn + " not present");
            } catch (Throwable t) {
                logger.debug("resolveSpecialDevice: " + fqn + " failed: " + t.getMessage());
            }
        }
        // Warn (not debug): with no special device, sentry keep-alive is inert.
        logger.warn("resolveSpecialDevice: BYDAutoSpecialDevice unavailable ("
                + limit + " candidate(s) probed) — sentry-mode writes will no-op");
        return cachedSpecialDevice;
    }
}
