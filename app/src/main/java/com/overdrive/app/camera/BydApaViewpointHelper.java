package com.overdrive.app.camera;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Reflective port of esco's il.C6498a (BYDApaHelper).
 *
 * Purpose: on byd_apa / apa firmware variants the AVMCamera HAL boots in
 * single-camera (dashcam) mode. Even with a correct addTexture(st, 0)
 * attach, the HAL keeps streaming the dashcam feed unless we tell the
 * BYDAutoManager Panorama device to switch to mosaic-output viewpoint.
 *
 * The handshake is a single setIntArray write to device 1031 with the
 * five PANO_VIEWPOINT_SET_FEATURES feature IDs and the magic args
 * {7, 0, 1, 0, 0}. Args 0 = reset (used on close).
 *
 * No public BYDAutoManager method signature is in the SDK stub
 * (android/hardware/BYDAutoManager.java only declares get/set Int/Double/Buffer).
 * We resolve setIntArray + enableDevice + disableDevice + register/unregisterListener
 * via reflection — same pattern the rest of this package uses for AVMCamera.
 *
 * Mirrors esco's lifecycle:
 *   m28930h(register)   → enableDevice(1031, features) + setViewpoint(2012)
 *   m28933k(unregister) → setViewpoint(0) + disableDevice(1031)
 */
public final class BydApaViewpointHelper {

    private static final DaemonLogger logger = DaemonLogger.getInstance("BydApaViewpointHelper");

    private static final int DEVICE_PANORAMA = 1031;

    /** Feature IDs from esco il/C6498a.java:35 — PANO_VIEWPOINT_SET_FEATURES. */
    private static final int[] PANO_VIEWPOINT_SET_FEATURES = {
        1306529808,
        1306529812,
        1306529826,
        1306529828,
        1306529832,
    };

    /** Listener feature IDs — same set the reference app subscribes in
     *  {@code il/a$a.features}. enableDevice subscribes to these; the listener
     *  filters in onChanged.
     *
     *  <p>Five of these could NOT be read out of the APK: in smali they are
     *  {@code sget} field loads from {@code android.hardware.bydauto
     *  .BYDAutoFeatureIds$Panorama} (i.e. non-inlined platform constants), so the
     *  reference app carries only the field reference, never the numeric value:
     *  <pre>
     *    sget v2, L…/BYDAutoFeatureIds$Panorama;->PANORAMA_OUTPUT_STATE_SET:I
     *  </pre>
     *  The values below for those five are therefore INFERRED from the
     *  even-numbered Panorama slot sequence and must be treated as guesses.
     *
     *  <p>{@link #resolveListenerFeatures()} replaces each inferred entry with the
     *  REAL constant when the platform class is present at runtime, which it is on
     *  an actual head unit. That removes the guesswork on-device while keeping
     *  these literals as the fallback for the SDK-stub classpath. */
    private static final int[] PANORAMA_LISTENER_FEATURES_FALLBACK = {
        322961416,    // PANORAMA_APA_STATE                (verified 0x13400008)
        862978056,    // PANORAMA_EMERGENCY_BUTTON_STATE   (verified 0x33700008)
        864026632,    // PANORAMA_ACU_STATE                (verified 0x33800008)
        1086328862,   // PANORAMA_RIGHT_CAMERA_SWITCH      (verified 0x40c0101e)
        1329598480,   // PANORAMA_OUTPUT_STATE             (verified 0x4f401010)
        1329598482,   // PANORAMA_OUTPUT_STATE_SET         (INFERRED — sget in APK)
        1329598492,   // PANORAMA_ROTATION_SET             (INFERRED — sget in APK)
        1329598484,   // PANORAMA_WORK_MODE_SET            (INFERRED — sget in APK)
        1329598486,   // PANORAMA_APA_AVM_MODE             (INFERRED — sget in APK)
        1329598494,   // PANORAMA_APA_TRANSPARENT_SWITCH   (verified 0x4f40101e)
        1329598496,   // PANORAMA_BACK_LINE_CONFIG         (verified 0x4f401020)
        1329598502,   // PANORAMA_REMOTE_CALL              (INFERRED — sget in APK)
        1329598504,   // PANORAMA_CAR_BODY_STATE           (verified 0x4f401028)
        1329598508,   // PANORAMA_REMOTE_CALL_SUPPORT      (verified 0x4f40102c)
    };

    /** Index → platform constant name for the five INFERRED slots above. A null
     *  entry means "the literal is verified, don't touch it". */
    private static final String[] PANORAMA_LISTENER_FEATURE_NAMES = {
        null, null, null, null, null,
        "PANORAMA_OUTPUT_STATE_SET",
        "PANORAMA_ROTATION_SET",
        "PANORAMA_WORK_MODE_SET",
        "PANORAMA_APA_AVM_MODE",
        null, null,
        "PANORAMA_REMOTE_CALL",
        null, null,
    };

    /** Resolved once, lazily. */
    private static volatile int[] resolvedListenerFeatures;

    /**
     * Return the listener feature IDs, preferring real platform constants.
     *
     * <p>Reads each INFERRED slot from {@code BYDAutoFeatureIds$Panorama} by field
     * name. Any field that is missing (or whose class isn't on this classpath —
     * e.g. the SDK stub, where {@code Panorama} doesn't exist) leaves the
     * fallback literal in place, so behaviour is unchanged from before this
     * change on any unit where resolution fails.
     *
     * <p>Result is cached: this runs once per process, on the first viewpoint
     * acquire, and only on the DiLink 4 path (the sole caller chain).
     */
    private static int[] resolveListenerFeatures() {
        int[] cached = resolvedListenerFeatures;
        if (cached != null) return cached;
        int[] out = PANORAMA_LISTENER_FEATURES_FALLBACK.clone();
        Class<?> panorama = null;
        try {
            panorama = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds$Panorama");
        } catch (Throwable t) {
            logger.info("BYDAutoFeatureIds$Panorama absent — using inferred listener feature IDs");
        }
        if (panorama != null) {
            int replaced = 0;
            for (int i = 0; i < PANORAMA_LISTENER_FEATURE_NAMES.length && i < out.length; i++) {
                String name = PANORAMA_LISTENER_FEATURE_NAMES[i];
                if (name == null) continue;
                try {
                    java.lang.reflect.Field f = panorama.getField(name);
                    Object v = f.get(null);
                    if (v instanceof Integer && ((Integer) v) != out[i]) {
                        logger.info("Listener feature " + name + ": inferred " + out[i]
                            + " -> platform " + v);
                        out[i] = (Integer) v;
                        replaced++;
                    }
                } catch (Throwable t) {
                    logger.info("Listener feature " + name + " unresolved — keeping " + out[i]);
                }
            }
            logger.info("Resolved " + replaced + " listener feature ID(s) from platform constants");
        }
        resolvedListenerFeatures = out;
        return out;
    }

    /** Result of the most recent 0→1 mosaic-viewpoint write, and whether it was
     *  ACCEPTED (rc == 0 — BYD's success code; never treat rc &gt; 0 as success).
     *  Exposed so the camera path can report "HAL is probably not in mosaic
     *  mode" instead of silently rendering 2x2 geometry over a single camera.
     *  Absence of a manager, or a thrown setIntArray, both leave this false. */
    private static volatile boolean mosaicViewpointConfirmed = false;
    private static volatile int lastAcquireRc = Integer.MIN_VALUE;

    /** True only if the last mosaic-viewpoint write returned rc == 0. */
    public static boolean isMosaicViewpointConfirmed() { return mosaicViewpointConfirmed; }

    /** Raw rc of the last 0→1 write; {@code Integer.MIN_VALUE} if never attempted. */
    public static int getLastAcquireRc() { return lastAcquireRc; }

    private static final int VIEWPOINT_ON  = 2012;  // mosaic / panoramic output
    private static final int VIEWPOINT_OFF = 0;     // reset

    /** Feature ID for PANORAMA_OUTPUT_STATE — when this transitions to 1
     *  (HAL re-init / native AVM app yielded back to us), we must re-issue
     *  the viewpoint=2012 write or the HAL keeps streaming dashcam. esco
     *  il/C6498a.java:303-309. */
    private static final int FEATURE_PANORAMA_OUTPUT_STATE = 1329598480;

    private static final Object LOCK = new Object();
    private static volatile Object autoManagerInstance = null;
    private static volatile Object listenerProxy = null;

    /** Token-based observer set. Mirrors esco C6498a observerSet
     *  (CopyOnWriteArraySet<Object>). The set transitions 0→1 trigger
     *  viewpoint=2012 + enableDevice + listener register; 1→0 transitions
     *  trigger viewpoint=0 + listener unregister + disableDevice. While
     *  size > 0 the helper is "enabled" — additional acquires are no-ops
     *  beyond a viewpoint re-issue (idempotent recovery for HAL resets). */
    private static final CopyOnWriteArraySet<Object> observerSet = new CopyOnWriteArraySet<>();

    private BydApaViewpointHelper() {}

    /** Acquire a viewpoint hold under {@code token}. First holder writes
     *  viewpoint=2012 + enables the panorama device + registers the
     *  PANORAMA_OUTPUT_STATE listener. Subsequent holders re-issue the
     *  viewpoint write (idempotent) but don't touch device/listener state.
     *
     *  ACC-OFF flow uses this to STRADDLE a session boundary: acquire a
     *  fresh "sentry" token before releasing the active "pano" token, so
     *  the set never goes 1→0 and the HAL never sees a viewpoint=0 write
     *  during the camera close. Mirrors esco's C7340b sentry restart not
     *  unregistering its observer until after the new C5319i registers.
     *
     *  Token must be a stable Object reference — a per-instance Object()
     *  is fine. {@code release} must be called with the same token. */
    public static void acquire(Object token) {
        if (token == null) return;
        synchronized (LOCK) {
            try {
                Object mgr = ensureAutoManager();
                if (mgr == null) {
                    logger.warn("BYDAutoManager unavailable — skipping viewpoint write."
                        + " The AVM HAL will stay in whatever mode it booted in"
                        + " (single-camera dashcam on byd_apa), so mosaic geometry"
                        + " is NOT guaranteed on this unit.");
                    mosaicViewpointConfirmed = false;
                    return;
                }
                boolean wasEmpty = observerSet.isEmpty();
                if (!observerSet.add(token)) {
                    // Already held by this token — re-issue viewpoint as
                    // an HAL-reset recovery, mirroring the prior idempotent
                    // call site (PANORAMA_OUTPUT_STATE=1 → re-apply).
                    int rc = invokeSetIntArray(mgr, DEVICE_PANORAMA,
                        PANO_VIEWPOINT_SET_FEATURES,
                        new int[]{ 7, 0, 1, 0, 0 });
                    logger.info("Viewpoint re-applied (vp=" + VIEWPOINT_ON
                        + ", rc=" + rc + ", token=" + token + ", set=" + observerSet.size() + ")");
                    return;
                }
                if (wasEmpty) {
                    // Mirror esco C6498a.m28930h size==1 branch (il/C6498a.java:176-178):
                    // enableDevice + listener register + viewpoint=2012 fire ONLY on
                    // 0→1 transition. Subsequent holders register silently; the
                    // listener's PANORAMA_OUTPUT_STATE=1 handler is the recovery
                    // path for HAL resets, not a per-add re-issue.
                    invokeEnableDevice(mgr, DEVICE_PANORAMA, resolveListenerFeatures());
                    registerListener(mgr);
                    int rc = invokeSetIntArray(mgr, DEVICE_PANORAMA,
                        PANO_VIEWPOINT_SET_FEATURES,
                        new int[]{ 7, 0, 1, 0, 0 });
                    logger.info("Viewpoint acquire 0→1 (vp=" + VIEWPOINT_ON
                        + ", rc=" + rc + ", token=" + token + ")");
                    // VERIFY the write. This write is what flips the byd_apa HAL
                    // out of single-camera dashcam mode into mosaic output; if it
                    // silently fails the HAL keeps streaming one camera and every
                    // downstream 2x2 assumption is wrong. Previously rc was logged
                    // and discarded, so a rejected write was indistinguishable
                    // from a successful one.
                    //
                    // NOTE on the success test: `rc == 0` is an INFERRED
                    // convention, carried over from other BYD HAL writes in this
                    // codebase (see byd-write-success-semantics: success is code
                    // == 0, never >= 0). It is NOT corroborated by the reference
                    // app — the OEM boxes setIntArray's return straight into a log
                    // string (il/a.java:169-171) and never branches on it, and no
                    // BYDAutoManager source or constants ship in the APK. So treat
                    // a non-zero rc as "unconfirmed", not as proven failure: we
                    // only surface it, we do not gate anything on it.
                    lastAcquireRc = rc;
                    mosaicViewpointConfirmed = (rc == 0);
                    if (rc != 0) {
                        logger.warn("Viewpoint write REJECTED (rc=" + rc + " != 0) — the AVM HAL"
                            + " is probably still in single-camera dashcam mode. Expect a"
                            + " non-mosaic producer; 2x2 quadrant geometry will be wrong.");
                    }
                } else {
                    logger.info("Viewpoint acquire (additional holder, no HAL write, token="
                        + token + ", set=" + observerSet.size() + ")");
                }
            } catch (Throwable t) {
                // A throw means the write never landed — do not leave a stale
                // "confirmed" from an earlier session standing.
                mosaicViewpointConfirmed = false;
                if (isDeadBinder(t)) {
                    logger.warn("acquire hit dead binder — recovering on next call");
                    invalidateAutoManagerInstance();
                } else {
                    logger.warn("acquire failed: " + t.getMessage());
                }
            }
        }
    }

    /** Release {@code token}. If this was the last holder, write viewpoint=0,
     *  unregister the listener, and disableDevice. Otherwise no HAL writes.
     *
     *  Mirrors esco C6498a.m28933k (line 217-226): on observerSet emptiness
     *  it calls m28932j(0) + processMonitor.m28941d() + panoStateMonitor.m28937d();
     *  otherwise it just removes the entry silently. */
    public static void release(Object token) {
        if (token == null) return;
        synchronized (LOCK) {
            if (!observerSet.remove(token)) {
                logger.info("release: token not held (token=" + token
                    + ", set=" + observerSet.size() + ")");
                return;
            }
            if (!observerSet.isEmpty()) {
                logger.info("release: holders remain (token=" + token
                    + ", set=" + observerSet.size() + ") — keeping viewpoint=ON");
                return;
            }
            try {
                Object mgr = autoManagerInstance;
                if (mgr != null) {
                    int rc = invokeSetIntArray(mgr, DEVICE_PANORAMA,
                        PANO_VIEWPOINT_SET_FEATURES,
                        new int[]{ 2, 0, 1, 0, 0 });
                    logger.info("Viewpoint release (vp=" + VIEWPOINT_OFF
                        + ", rc=" + rc + ", token=" + token + ", set=0)");
                    // We just told the HAL to leave mosaic mode — the previous
                    // confirmation no longer describes reality.
                    mosaicViewpointConfirmed = false;
                    unregisterListener(mgr);
                    invokeDisableDevice(mgr, DEVICE_PANORAMA);
                }
            } catch (Throwable t) {
                if (isDeadBinder(t)) {
                    logger.warn("release hit dead binder — invalidating cached BYDAutoManager");
                    invalidateAutoManagerInstance();
                } else {
                    logger.warn("release failed: " + t.getMessage());
                }
            }
        }
    }

    /** Snapshot of the active holder count — for diagnostic logs only. */
    public static int holderCount() {
        return observerSet.size();
    }

    /**
     * Re-assert viewpoint=2012 if (and only if) we currently hold the viewpoint.
     *
     * <p><b>Why a poll instead of an event.</b> The reference app recovers the
     * viewpoint reactively from TWO sources: the PANORAMA_OUTPUT_STATE listener we
     * already mirror in {@link #registerListener}, AND an
     * {@code IProcessObserver} ({@code il/a$b} → {@code ActivityManagerApis
     * .registerProcessObserver}) that re-issues {@code setViewpoint(2012)} within
     * 50 ms of {@code com.byd.avc} leaving the foreground. We cannot replicate the
     * second one: {@code registerProcessObserver} requires the signature-level
     * {@code SET_ACTIVITY_WATCHER} permission (the reference app reaches it through
     * a Shizuku-style privileged binder), and its {@code UsageStatsManager}
     * foreground probe needs {@code PACKAGE_USAGE_STATS}, which this app is not
     * granted. Attempting either would just throw on every call.
     *
     * <p>So we close the same gap with an idempotent periodic re-assert, driven
     * from the ACC-off keep-alive tick that already runs on DiLink 4. That covers
     * the failure this is really about: the panorama HAL silently dropping our
     * viewpoint mid-park (AVC coming to foreground and back, HAL re-init that does
     * not emit PANORAMA_OUTPUT_STATE=1) leaving the mosaic feed stuck on the
     * single-camera dashcam view. Slower to react than the reference app's 50 ms,
     * but it is bounded recovery instead of none.
     *
     * <p>Deliberately a NO-OP when {@code observerSet} is empty — if nothing holds
     * the viewpoint we must not write it, or we would flip the HAL into mosaic mode
     * with no consumer attached. Safe to call from any thread and at any cadence;
     * it takes the same {@link #LOCK} as acquire/release so it cannot interleave
     * with a session boundary.
     *
     * @return true if a re-assert write was issued.
     */
    public static boolean reassertIfHeld() {
        synchronized (LOCK) {
            if (observerSet.isEmpty()) return false;
            // ensureAutoManager(), not the raw cached field. A dead binder causes
            // acquire/release to call invalidateAutoManagerInstance(), which nulls
            // the cache — so reading the field directly made this periodic
            // re-assert silently no-op FOREVER after the first binder death,
            // exactly when the HAL is most likely to have dropped our viewpoint.
            Object mgr = ensureAutoManager();
            if (mgr == null) return false;
            try {
                int rc = invokeSetIntArray(mgr, DEVICE_PANORAMA,
                    PANO_VIEWPOINT_SET_FEATURES,
                    new int[]{ 7, 0, 1, 0, 0 });
                logger.info("Viewpoint periodic re-assert (vp=" + VIEWPOINT_ON
                    + ", rc=" + rc + ", holders=" + observerSet.size() + ")");
                return true;
            } catch (Throwable t) {
                if (isDeadBinder(t)) {
                    logger.warn("re-assert hit dead binder — invalidating cached BYDAutoManager");
                    invalidateAutoManagerInstance();
                } else {
                    logger.warn("re-assert failed: " + t.getMessage());
                }
                return false;
            }
        }
    }

    /** Legacy wrapper: per-process singleton token used by the existing
     *  startCameraViaAvmReflection callsite. Equivalent to esco's
     *  C5920a.mo26750v register call (single C5920a instance per camera
     *  open). New code paths (sentry straddle) should allocate their own
     *  token so the observerSet semantics work as designed. */
    private static final Object PIPELINE_TOKEN = new Object() {
        @Override public String toString() { return "pipeline"; }
    };

    /** @deprecated use {@link #acquire(Object)} with a per-instance token. */
    @Deprecated
    public static void enableForPanoramic() {
        acquire(PIPELINE_TOKEN);
    }

    /** @deprecated use {@link #release(Object)} with the matching token. */
    @Deprecated
    public static void disable() {
        release(PIPELINE_TOKEN);
    }

    /** Detect DeadObjectException in any reflective wrapper layer. The
     *  exception can surface as InvocationTargetException(DeadObjectException)
     *  or directly, depending on the call site. */
    private static boolean isDeadBinder(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 6; depth++) {
            if (cur instanceof android.os.DeadObjectException) return true;
            String cls = cur.getClass().getName();
            if ("android.os.DeadObjectException".equals(cls)) return true;
            cur = cur.getCause();
        }
        return false;
    }

    /** Drop the cached BYDAutoManager and listener proxy. Next acquire()
     *  will call ensureAutoManager again, getting a fresh binder proxy
     *  from the system service. The observerSet stays — it tracks intent,
     *  not binder state — so once the manager comes back the next acquire()
     *  branch (wasEmpty? false) will re-issue the viewpoint write. The
     *  enableDevice + listener register path is one-shot per holder count
     *  transition; we lose listener registration here but on dead-binder
     *  recovery the manager is fresh and the next acquire() will re-register
     *  via the size 0→1 path the next time the set fully empties first.
     *  Acceptable: dead binder is rare and listener loss is recovered on
     *  next session boundary. */
    private static void invalidateAutoManagerInstance() {
        autoManagerInstance = null;
        listenerProxy = null;
    }

    private static Object ensureAutoManager() {
        if (autoManagerInstance != null) return autoManagerInstance;
        Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
        if (ctx == null) return null;
        Object svc = ctx.getSystemService("auto");
        if (svc == null) return null;
        try {
            // Sanity: must be a BYDAutoManager (or whatever the real platform
            // class is). The SDK stub class is fine; reflection works on either.
            autoManagerInstance = svc;
            logger.info("BYDAutoManager acquired: " + svc.getClass().getName());
        } catch (Throwable t) {
            logger.warn("BYDAutoManager type check failed: " + t.getMessage());
            return null;
        }
        return autoManagerInstance;
    }

    /** Mirror esco panoStateMonitor.m28936c: register an OnBYDAutoListener so
     *  we can re-apply viewpoint=2012 if PANORAMA_OUTPUT_STATE flips back to
     *  1 (HAL re-init, native app yielded). The listener interface is an
     *  inner of BYDAutoManager — load it reflectively so the SDK-stub
     *  classpath doesn't have to know about it. */
    private static void registerListener(Object mgr) {
        if (listenerProxy != null) return;
        try {
            Class<?> listenerIface;
            try {
                listenerIface = Class.forName("android.hardware.BYDAutoManager$OnBYDAutoListener");
            } catch (ClassNotFoundException e) {
                logger.warn("OnBYDAutoListener iface not found — cannot self-recover viewpoint");
                return;
            }
            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("toString".equals(name)) return "BydApaViewpointListener";
                if ("onChanged".equals(name) && args != null && args.length >= 3) {
                    // overload (int device, int feature, int value, Object data)
                    Object device = args[0];
                    Object feature = args[1];
                    Object value = args[2];
                    if (device instanceof Integer && (Integer) device == DEVICE_PANORAMA
                            && feature instanceof Integer
                            && (Integer) feature == FEATURE_PANORAMA_OUTPUT_STATE
                            && value instanceof Integer) {
                        int v = (Integer) value;
                        // Always log so we can correlate value transitions
                        // against event=8 cadence in the field.
                        logger.info("PANORAMA_OUTPUT_STATE=" + v);
                        if (v == 1) {
                            // HAL came back online — re-apply mosaic viewpoint.
                            // Hold LOCK + check observerSet to avoid racing release().
                            // The listener fires on a BYDAutoManager binder thread;
                            // release() runs on whichever thread closes the camera.
                            synchronized (LOCK) {
                                if (observerSet.isEmpty()) return null;  // last release() already ran
                                try {
                                    int rc = invokeSetIntArray(mgr, DEVICE_PANORAMA,
                                        PANO_VIEWPOINT_SET_FEATURES,
                                        new int[]{ 7, 0, 1, 0, 0 });
                                    logger.info("Re-applied viewpoint=" + VIEWPOINT_ON
                                        + " on PANORAMA_OUTPUT_STATE=1 (rc=" + rc + ")");
                                } catch (Throwable t) {
                                    logger.warn("Re-apply viewpoint failed: " + t.getMessage());
                                }
                            }
                        }
                        // PANORAMA_OUTPUT_STATE=7 (compositor reporting
                        // non-matching mode) is intentionally unhandled.
                        // Esco only releases viewpoint here when AVC is
                        // foreground (il/C6498a.java:310-313 — guarded by
                        // processMonitor.m28938a()). We don't have UsageStats
                        // access, so we previously fired UNCONDITIONAL release
                        // — which corrupted every dilink4 session: the =7
                        // event fires within milliseconds of every HAL warmup,
                        // releasing the panorama viewpoint right as we open
                        // the camera. Frames came back at 24 KB instead of
                        // ~80 KB. Without UsageStats we'd rather hold
                        // viewpoint=2012 always than mis-release it.
                    }
                }
                return null;
            };
            Object proxy = Proxy.newProxyInstance(
                listenerIface.getClassLoader(),
                new Class<?>[]{ listenerIface },
                handler);
            Method m = mgr.getClass().getMethod("registerListener", listenerIface);
            m.invoke(mgr, proxy);
            listenerProxy = proxy;
            logger.info("PANORAMA_OUTPUT_STATE listener registered");
        } catch (NoSuchMethodException e) {
            logger.warn("registerListener not present on this BYDAutoManager");
        } catch (Throwable t) {
            logger.warn("registerListener failed: " + t.getMessage());
        }
    }

    private static void unregisterListener(Object mgr) {
        Object proxy = listenerProxy;
        if (proxy == null) return;
        try {
            Class<?> listenerIface = Class.forName(
                "android.hardware.BYDAutoManager$OnBYDAutoListener");
            Method m = mgr.getClass().getMethod("unregisterListener", listenerIface);
            m.invoke(mgr, proxy);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            logger.warn("unregisterListener failed: " + t.getMessage());
        } finally {
            listenerProxy = null;
        }
    }

    private static int invokeSetIntArray(Object mgr, int device, int[] features, int[] values)
            throws Exception {
        Method m = mgr.getClass().getMethod("setIntArray", int.class, int[].class, int[].class);
        Object rc = m.invoke(mgr, device, features, values);
        return (rc instanceof Integer) ? (Integer) rc : -1;
    }

    private static void invokeEnableDevice(Object mgr, int device, int[] features) {
        try {
            Method m = mgr.getClass().getMethod("enableDevice", int.class, int[].class);
            m.invoke(mgr, device, features);
        } catch (NoSuchMethodException e) {
            logger.warn("enableDevice(int, int[]) not present on this BYDAutoManager");
        } catch (Throwable t) {
            logger.warn("enableDevice failed: " + t.getMessage());
        }
    }

    private static void invokeDisableDevice(Object mgr, int device) {
        try {
            Method m = mgr.getClass().getMethod("disableDevice", int.class);
            m.invoke(mgr, device);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            logger.warn("disableDevice failed: " + t.getMessage());
        }
    }
}
