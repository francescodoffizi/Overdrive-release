package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MANAGER-LEVEL access channel to the BYD HAL — a fallback for reads that the per-device path
 * cannot satisfy, plus the device-activation call we never made.
 *
 * <p><b>Why this exists.</b> We reach the HAL exactly one way: {@code Device.getInstance(ctx)} then
 * {@code device.get(int[] featureIds, Class type)}. Two independently-built reference apps use additional
 * channels, verified by bytecode inspection on 2026-07-29:
 *
 * <ul>
 *   <li><b>reference app A</b> (its telemetry bridge) calls
 *       {@code enableDevice(...)} on {@code BYDAutoDeviceManager} for every telemetry device it
 *       polls, and reads through the manager's own {@code getDouble(deviceType, featureId)}.
 *       It calls BOTH enable shapes — {@code manager.enableDevice(IBYDAutoDevice)} and
 *       {@code BYDAutoManager.enableDevice(int deviceType)}.</li>
 *   <li><b>reference app B</b> (its device-init layer) resolves a
 *       device through a THREE-tier ladder — {@code DeviceManager.getInstance(ctx).getDevice(type)}
 *       → per-device {@code getInstance(ctx)} → raw {@code getConstructor(Context).newInstance} —
 *       and resolves {@code getDouble} alongside {@code get} up front.</li>
 * </ul>
 *
 * <p><b>The symptom that motivated it.</b> On a BEV capture (log_F2ZQH7CC, 2026-07-29) the entire
 * charging-power surface read NaN simultaneously — {@code getChargePower},
 * {@code getExternalChargingPower}, {@code getChargingPower} AND cluster feature id
 * {@code 0x32300018}. Four independent getters failing in lockstep points at an un-activated or
 * unresolved device handle far more than at four separate HAL bugs. This class tests that
 * hypothesis with data instead of argument.
 *
 * <p><b>Design constraints (deliberate, do not "simplify"):</b>
 * <ol>
 *   <li><b>Purely additive.</b> Nothing here runs unless the existing path already returned
 *       nothing. A trim that works today takes byte-identical code paths, so this cannot regress
 *       a working vehicle — the same containment rule as invariant I1.</li>
 *   <li><b>Every probe is one-shot and cached, including FAILURE.</b> The HAL is a binder; a
 *       reflective miss costs a thrown exception. Retrying a known-absent method on every 5 s poll
 *       would burn CPU for nothing, so negative results are cached too
 *       (see {@link #missCache}).</li>
 *   <li><b>Never throws.</b> Every entry point returns a sentinel (NaN / MIN_VALUE / false) and
 *       swallows {@code Throwable}, because callers sit on the collector's hot path.</li>
 *   <li><b>One-shot INFO logging per outcome.</b> A per-poll log would be ~1440 lines/day; the
 *       question this answers ("which channel works on this trim?") is answered once.</li>
 * </ol>
 *
 * <p><b>WHERE THE DIAGNOSTICS ARE VISIBLE — read before asking for a capture.</b> The one-shot
 * lines below are the entire payoff of this class, and in the {@code release} (alpha) variant they
 * are stripped THREE ways over: the Gradle auto-detect adds {@code proguard-rules-strip-logs.pro}
 * (R8 deletes the {@code info()}/{@code debug()} calls outright),
 * {@code proguard-rules-strip-console.pro} is always applied, and this tag is not in
 * {@code DaemonLogConfig.ENABLED_TAGS} by default. They appear in full in a <b>braveheart</b> build
 * ({@code LOG_CAPTURE=true}, no stripping), or in {@code release} by setting
 * {@code DaemonLogConfig.BYD_TELEMETRY = true}, which both keeps the calls and opens the tag.
 * Asking an alpha customer for these lines without one of those two things yields nothing.
 */
public final class BydManagerChannel {

    private static final String TAG = "BydManagerChannel";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private BydManagerChannel() {}

    // ---- Resolved lazily, once. `volatile` because the collector poll thread, the HTTP thread
    // and HAL binder callbacks all touch these. ----
    private static volatile Object deviceManager;      // BYDAutoDeviceManager
    private static volatile boolean deviceManagerTried;
    private static volatile Object autoManager;        // BYDAutoManager (getSystemService("auto"))
    private static volatile boolean autoManagerTried;
    /**
     * One process-wide lane for manager Binder calls. A timed-out Binder invocation may ignore
     * interruption, so recreating a scratch thread for every retry would leak one thread per call.
     * This lane can strand at most one daemon thread; while it is occupied, later probes fail fast.
     */
    private static final ThreadPoolExecutor boundedCallExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread worker = new Thread(runnable, "BydHalCall");
                worker.setDaemon(true);
                return worker;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private static final AtomicBoolean boundedCallStalled = new AtomicBoolean();

    /**
     * Resolved reflective lookups. A MISS is recorded in {@link #missCache} rather than as a null
     * value here, because {@link ConcurrentHashMap} forbids null values — an earlier version cached
     * a "negative" {@code Method} sentinel obtained reflectively, which would have thrown NPE on
     * {@code put} had that sentinel lookup ever failed (and it was itself strippable by R8). Two
     * maps cannot fail that way.
     */
    private static final Map<String, Method> methodCache = new ConcurrentHashMap<>();
    /** Keys known to be absent on this platform, so we probe each at most once (constraint 2). */
    private static final java.util.Set<String> missCache =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * LEARNED className → HAL deviceType, populated whenever we hold a live handle
     * ({@link #rememberDeviceType}).
     *
     * <p>Needed because the manager tier of {@link #resolveDeviceFallback} is keyed by deviceType,
     * but the type can only be read OFF an instance ({@code getDevicetype()}/{@code getType()}) —
     * so on the very first acquisition attempt there is nothing to key with and that tier is
     * unreachable. It cannot be fixed with a hardcoded table: the bundled compile-time stubs report
     * placeholder types (several duplicate {@code 1007}, most return {@code 0}), so a table built
     * from them would be actively wrong. Learning the real value from a live handle is the only
     * sound source. Deliberately NOT cleared by {@link #invalidate()} — a className→type mapping is
     * a property of the platform, not of a Context or a binder, so it stays valid across a re-init
     * and is exactly what makes the manager tier reachable on the second pass.
     */
    private static final Map<String, Integer> typeByClassName = new ConcurrentHashMap<>();

    /**
     * Record the HAL type of a live handle so a later acquisition can use the manager tier.
     * Safe to call with anything; non-resolving handles are ignored.
     */
    public static void rememberDeviceType(String className, Object device) {
        if (className == null || device == null) return;
        // PROVENANCE CHECK. The handle must actually BE the class we are keying under — otherwise a
        // wrong-device handle (e.g. one returned by getDevice(int) for a mistaken type) would have
        // its type learned as this class and permanently misroute every later read.
        try {
            Class<?> want = Class.forName(className);
            if (!want.isInstance(device)) return;
        } catch (Throwable ignored) { /* cannot verify → fall through to the value checks */ }
        int t = BydDeviceHelper.deviceTypeOf(device);
        if (t == Integer.MIN_VALUE) return;
        // REJECT PLACEHOLDERS. A directly-constructed (never registered) device commonly reports 0 —
        // the default of an uninitialised int field, i.e. the manager handshake never completed. The
        // bundled compile-time stubs likewise report 0 and a duplicated 1007. Learning either would
        // permanently key getDevice()/enableDevice()/getDouble() to a bogus type, and because this
        // cache deliberately survives invalidate() only a process restart could clear it. A real HAL
        // type is a positive, non-placeholder value.
        if (t <= 0) {
            logger.debug("rememberDeviceType: refusing placeholder type " + t + " for " + className);
            return;
        }
        // put(), not putIfAbsent(): provenance is not uniform. A later, better-provenance handle
        // (a manager-registered one) must be able to correct a value learned from a constructed
        // shell — putIfAbsent would freeze the least trustworthy first writer in place forever.
        Integer prev = typeByClassName.put(className, t);
        if (prev != null && prev != t) {
            logger.info("rememberDeviceType: " + className + " type corrected " + prev + " → " + t);
        }
    }

    /** Learned HAL type for a class, or {@code Integer.MIN_VALUE} when not yet known. */
    public static int learnedDeviceType(String className) {
        Integer t = (className != null) ? typeByClassName.get(className) : null;
        return (t != null) ? t : Integer.MIN_VALUE;
    }

    // One-shot log latches.
    private static volatile boolean loggedManagerAcquire;
    private static volatile boolean loggedDoubleChannel;
    private static volatile boolean loggedIntChannel;
    /** Throttle for the dead-binder warning — see noteBinderDeath for why a latch was wrong. */
    private static volatile long lastBinderDeathLogMs;
    private static final long BINDER_DEATH_LOG_THROTTLE_MS = 60_000L;

    /**
     * Earliest time a manager handle may be re-probed after a dead-binder invalidate.
     *
     * <p>Without a backoff, {@code invalidate()} clears the {@code *Tried} latches and the very next
     * poll re-enters acquisition — during a framework restart that is a re-probe every 5 s, each one
     * a binder call, plus the full reflective lookup set and log set. Staying dead forever is
     * cheaper but never recovers; a rate limit gets recovery within one backoff window at no
     * steady-state cost.
     */
    /**
     * Bumped by every {@link #invalidate()}. Read BEFORE an out-of-monitor resolve and re-checked
     * when publishing: the resolve now happens outside the lock, so a handle obtained before an
     * invalidate could otherwise be published after it — resurrecting the very dead proxy the
     * invalidate dropped, and permanently voiding the backoff because the cached fast path is
     * consulted before {@code backoffActive()}.
     */
    private static volatile long invalidateGen;

    private static volatile long nextProbeAtMs;
    private static final long REPROBE_BACKOFF_MS = 30_000L;

    /**
     * The {@code BYDAutoDeviceManager} singleton, or null when the platform doesn't expose one.
     * Resolved once; a failure is remembered so we don't re-probe a missing class every poll.
     */
    private static Object deviceManager(Context ctx) {
        Object cached = deviceManager;
        if (cached != null) return cached;          // fast path: resolved
        if (deviceManagerTried) return null;        // fast path: known-absent
        if (ctx == null) return null;               // too early — not a verdict, see below
        if (backoffActive()) return null;           // dead-binder cool-down
        // RESOLVE OUTSIDE THE MONITOR. getInstance() is an untimed binder call; holding the class
        // monitor across it means a wedged HAL blocks EVERY other thread's first
        // deviceManager()/autoManager()/invalidate() — including the init thread. That is the exact
        // wedge constructBounded() exists to prevent, and it became reachable from the 5 s collector
        // thread once dead-binder invalidation started clearing the `tried` latches. A duplicate
        // concurrent resolve is harmless (the loser is discarded); a global stall is not.
        long gen = invalidateGen;   // see invalidateGen — guards the publish below
        String emit = null;         // log message built under the lock, emitted after it
        Object resolved = null;
        boolean acquisitionConclusive = true;
        boolean permanentlyAbsent = false;
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoDeviceManager");
            Method gi = cls.getMethod("getInstance", Context.class);
            // BOUNDED. The *Tried latch normally makes this once-per-process, but invalidate()
            // clears it and invalidate() is reachable FROM THE POLL THREAD (a dead-binder catch in
            // invokeDouble). So after every dead-binder event the 5 s collector thread re-enters
            // this untimed binder call — and BydDataPoll is a single-thread scheduler, so the entire
            // vehicle snapshot (SOC, 12 V, temps, charging) freezes for its duration. Moving the
            // call out of the monitor fixed the lock half of this; the bound fixes the stall half.
            BoundedCallResult acquired =
                    invokeBoundedResult(gi, null, ctx, "BYDAutoDeviceManager.getInstance");
            resolved = acquired.value;
            acquisitionConclusive = acquired.completed;
        } catch (ClassNotFoundException | NoSuchMethodException absent) {
            permanentlyAbsent = true;
            logger.debug("BYDAutoDeviceManager unavailable: "
                    + absent.getClass().getSimpleName());
        } catch (Throwable t) {
            logger.debug("BYDAutoDeviceManager unavailable: " + t.getClass().getSimpleName());
        }
        // Cancellation is not an availability verdict. The app-process actuator deliberately
        // bounds its wait; if that interrupt were published as `tried=true, manager=null`, every
        // later command would permanently skip activation until process restart.
        if (Thread.currentThread().isInterrupted() || !acquisitionConclusive) return null;
        synchronized (BydManagerChannel.class) {
            // Raced an invalidate while resolving: this handle predates it, so discard rather than
            // publish. Returning null also leaves the backoff intact for the next caller.
            if (invalidateGen != gen) return null;
            if (deviceManager != null) return deviceManager;   // another thread won the race
            // Publish the HANDLE BEFORE the `tried` latch. Reversed, another thread could observe
            // tried==true while `deviceManager` was still null and wrongly conclude "unavailable"
            // for the rest of the process — a lost-capability race, not just a wasted probe.
            deviceManager = resolved;
            // A completed null is common while the framework is still starting and is not proof
            // that the manager is absent for this process. Structural absence is conclusive.
            deviceManagerTried = resolved != null || permanentlyAbsent;
            // Latch only on a SUCCESS. A losing thread that resolved null would otherwise latch
            // "NOT available" and permanently suppress the winner's successful-acquisition line —
            // the capture would report the channel dead while it is live, and one decisive line is
            // this class's entire payoff.
            if (resolved != null && !loggedManagerAcquire) {
                loggedManagerAcquire = true;
                emit = "BYDAutoDeviceManager acquired: " + resolved.getClass().getName();
            }
        }
        // LOG OUTSIDE THE MONITOR. logger.info -> writeToFile does println + flush() and can enter
        // log rotation (close + renameTo/delete on /data), and the stdout sink blocks indefinitely if
        // the wrapper pipe is not drained. Holding the GLOBAL class monitor across that would stall
        // every other thread's acquisition/invalidate. noteBinderDeath already follows this pattern.
        if (emit != null) logger.info(emit);
        else if (resolved == null) logger.debug("BYDAutoDeviceManager NOT available this attempt"
                + " — manager-level reads/activation skipped");
        return resolved;
    }

    /**
     * The {@code BYDAutoManager} system service, via {@code getSystemService("auto")} — the same
     * acquisition the panorama path already uses successfully
     * ({@code BydApaViewpointHelper.ensureAutoManager}), so it is a proven route on this platform.
     */
    private static Object autoManager(Context ctx) {
        Object cached = autoManager;
        if (cached != null) return cached;
        if (autoManagerTried) return null;
        if (ctx == null) return null;   // too early, not a verdict — see deviceManager()
        if (backoffActive()) return null;
        long gen = invalidateGen;
        Object resolved = null;
        boolean acquisitionConclusive = true;
        try {
            // Bounded for the same reason as getInstance above: reachable from the poll thread after
            // an invalidate. getSystemService can block on a binder during a framework restart.
            Method gss = Context.class.getMethod("getSystemService", String.class);
            BoundedCallResult acquired =
                    invokeBoundedResult(gss, ctx, "auto", "getSystemService(auto)");
            resolved = acquired.value;
            acquisitionConclusive = acquired.completed;
        } catch (Throwable t) {
            logger.debug("getSystemService(\"auto\") failed: " + t.getMessage());
        }
        if (Thread.currentThread().isInterrupted() || !acquisitionConclusive) return null;
        synchronized (BydManagerChannel.class) {
            if (invalidateGen != gen) return null;   // raced an invalidate — see deviceManager()
            if (autoManager != null) return autoManager;
            autoManager = resolved;      // handle before latch — see deviceManager()
            // getSystemService("auto") can transiently return null during framework startup.
            autoManagerTried = resolved != null;
            return resolved;
        }
    }

    /** True while a dead-binder cool-down is in effect, so we do not re-probe on every poll. */
    private static boolean backoffActive() {
        long until = nextProbeAtMs;
        // elapsedRealtime, NOT wall clock: these units correct the clock from GPS/NTP shortly after
        // boot — exactly when init() runs — and a BACKWARD jump would hold a wall-clock deadline in
        // the future for hours, silently disabling the whole channel with no log line saying so.
        return until > 0 && android.os.SystemClock.elapsedRealtime() < until;
    }

    /** Value plus whether the reflective call actually completed and produced that value. */
    private static final class BoundedCallResult {
        final Object value;
        final boolean completed;

        BoundedCallResult(Object value, boolean completed) {
            this.value = value;
            this.completed = completed;
        }
    }

    /**
     * Invoke a zero-or-one-arg reflective HAL call with a bounded wait, for the calls that run on
     * the daemon INIT thread. Same rationale as {@link #constructBounded}: an untimed binder call
     * there means {@code init()} may never return.
     *
     * @return the result, or null on failure/timeout
     */
    private static Object invokeBounded(final Method m, final Object target, final Object arg,
                                        String what) {
        return invokeBoundedResult(m, target, arg, what).value;
    }

    /**
     * A null value from a completed call is a real negative result; a null caused by lane
     * contention, timeout, interruption, or invocation failure is inconclusive. Manager acquisition
     * uses that distinction so a transient bounded-lane condition is never cached as permanent
     * platform absence.
     */
    private static BoundedCallResult invokeBoundedResult(
            final Method m, final Object target, final Object arg, String what) {
        if (boundedCallStalled.get()) {
            if (boundedCallExecutor.getActiveCount() != 0) {
                logger.debug("HAL call " + what + " skipped while bounded lane is stalled");
                return new BoundedCallResult(null, false);
            }
            boundedCallStalled.set(false);
        }
        final Future<Object> call;
        try {
            call = boundedCallExecutor.submit(
                    () -> (arg == null) ? m.invoke(target) : m.invoke(target, arg));
        } catch (Throwable unavailable) {
            logger.debug("HAL call " + what + " skipped while bounded lane is occupied");
            return new BoundedCallResult(null, false);
        }
        try {
            Object result = call.get(CONSTRUCT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            boundedCallStalled.set(false);
            return new BoundedCallResult(result, true);
        } catch (TimeoutException timeout) {
            call.cancel(true);
            boundedCallExecutor.remove((Runnable) call);
            boundedCallStalled.set(true);
            logger.warn("HAL call " + what + " did not return in " + CONSTRUCT_TIMEOUT_MS
                    + "ms — bounded lane remains capped while Binder unwinds");
            return new BoundedCallResult(null, false);
        } catch (InterruptedException ie) {
            call.cancel(true);
            boundedCallExecutor.remove((Runnable) call);
            Thread.currentThread().interrupt();
            return new BoundedCallResult(null, false);
        } catch (Throwable failed) {
            call.cancel(true);
            boundedCallExecutor.remove((Runnable) call);
            boundedCallStalled.set(false);
            noteBinderDeath(failed);
            return new BoundedCallResult(null, false);
        }
    }

    /**
     * Cached reflective lookup; misses are remembered too, so a method absent on this platform is
     * probed at most once instead of throwing on every 5 s poll.
     *
     * <p>Key is {@code class|name|paramType,paramType}. The separators are characters that cannot
     * occur in a JVM binary class name (which permits letters, digits, {@code _}, {@code $},
     * {@code .} and, for arrays, {@code [}/{@code ;}), so two distinct lookups cannot collide onto
     * one key.
     */
    private static Method method(Object target, String name, Class<?>... params) {
        if (target == null) return null;
        StringBuilder key = new StringBuilder(target.getClass().getName()).append('|').append(name);
        for (Class<?> p : params) key.append('|').append(p.getName());
        String k = key.toString();
        Method cached = methodCache.get(k);
        if (cached != null) return cached;
        if (missCache.contains(k)) return null;
        Method found = null;
        try {
            found = target.getClass().getMethod(name, params);
        } catch (Throwable ignored) { /* absent on this platform */ }
        if (found != null) methodCache.put(k, found);
        else missCache.add(k);
        return found;
    }

    // ==================== ACTIVATION ====================

    /**
     * Ask the HAL to activate {@code device} so it begins serving its feature ids, mirroring the
     * reference behaviour for every telemetry device. Tries both observed shapes, in that order:
     * {@code deviceManager.enableDevice(IBYDAutoDevice)} first, then
     * {@code autoManager.enableDevice(int deviceType)}.
     *
     * <p>Best-effort and idempotent-by-caller: this does not track which devices were enabled,
     * because the HAL's own return code is the only authority and callers invoke this once at init.
     *
     * @param label short name for the log line (e.g. "instrument")
     * @return true if either shape reported success (code 0 — see the BYD write-semantics rule
     *         that {@code 0} means OK and non-zero is a failure code, NOT a magnitude)
     */
    public static boolean enableDevice(Context ctx, Object device, String label) {
        if (device == null) return false;
        boolean ok = false;
        // Each branch sets its OWN outcome string. A shared "none" default made three distinct
        // shape-1 failures (no manager / method absent / wrong type) indistinguishable, and this
        // one log line is the whole diagnostic — an ambiguous line is a defect, not cosmetics.
        String s1;
        String s2;

        // Shape 1 — manager.enableDevice(IBYDAutoDevice). Resolve the parameter type reflectively:
        // declaring it against our stub would bind to OUR class, and the HAL matches the exact
        // platform interface.
        Object mgr = deviceManager(ctx);
        if (mgr == null) {
            s1 = "s1:no-deviceManager";
        } else {
            try {
                Class<?> iface = Class.forName("android.hardware.IBYDAutoDevice");
                if (!iface.isInstance(device)) {
                    s1 = "s1:not-IBYDAutoDevice";
                } else {
                    Method m = method(mgr, "enableDevice", iface);
                    if (m == null) {
                        s1 = "s1:method-absent";
                    } else {
                        // Bounded: this runs on the daemon init thread WHILE init() holds the
                        // collector monitor, and there are 20 of these. An untimed binder call
                        // here wedges init() and every thread waiting on that monitor — the same
                        // argument that already bounds getDevice(int) below.
                        Object r = invokeBounded(m, mgr, device, "enableDevice(IBYDAutoDevice)");
                        // BYD convention: 0 == success. A non-zero value is an error code, so
                        // ">= 0" would wrongly accept failures.
                        ok = (r instanceof Number) && ((Number) r).intValue() == 0;
                        s1 = "s1:enableDevice(IBYDAutoDevice)=" + r;
                    }
                }
            } catch (Throwable t) {
                s1 = "s1:threw " + t.getClass().getSimpleName();
                noteBinderDeath(t);
            }
        }

        // Shape 2 — autoManager.enableDevice(int deviceType). The reference behaviour calls this
        // even when shape 1 ran, so we do too rather than short-circuiting on the first success.
        Object am = autoManager(ctx);
        int type = BydDeviceHelper.deviceTypeOf(device);
        if (am == null) {
            s2 = "s2:no-autoManager";
        } else if (type == Integer.MIN_VALUE) {
            s2 = "s2:unknown-deviceType";
        } else {
            try {
                Method m = method(am, "enableDevice", int.class);
                if (m == null) {
                    s2 = "s2:method-absent";
                } else {
                    Object r = invokeBounded(m, am, type, "enableDevice(" + type + ")");
                    boolean ok2 = (r instanceof Number) && ((Number) r).intValue() == 0;
                    ok = ok || ok2;
                    s2 = "s2:enableDevice(" + type + ")=" + r;
                }
            } catch (Throwable t) {
                s2 = "s2:threw " + t.getClass().getSimpleName();
                noteBinderDeath(t);
            }
        }
        logger.info("enableDevice[" + label + "] ok=" + ok + " | " + s1 + " | " + s2);
        return ok;
    }

    // ==================== MANAGER-LEVEL READS ====================

    /**
     * Read a feature id as a double through the MANAGER, not the device — a second HAL entry point
     * that a trim may implement when the per-device {@code get(int[], Class)} form returns nothing.
     * Tries {@code BYDAutoDeviceManager.getDouble} then {@code BYDAutoManager.getDouble}.
     *
     * <p><b>NO SENTINEL FILTERING IS PERFORMED</b> — see {@link #getInt}. NaN means only "no
     * channel produced a value"; a HAL sentinel such as {@code 104857.5} or {@code -1} is returned
     * verbatim, and the caller must range-check it exactly as it would a per-device read (I4).
     *
     * @return the value, or NaN when neither channel produced one
     */
    public static double getDouble(Context ctx, Object device, int featureId) {
        int type = BydDeviceHelper.deviceTypeOf(device);
        if (type == Integer.MIN_VALUE) return Double.NaN;
        // CHANNEL 1 — the DEVICE's own getDouble(deviceType, featureId). Best-evidenced of the three:
        // the platform's AbsBYDAutoDevice declares getDouble/getDoubleArray directly (our bundled
        // stub does not, which is why this accessor was invisible to us), so every device inherits
        // it. Tried first because it needs no manager handle at all and therefore works even when
        // BYDAutoDeviceManager is unavailable.
        // 0.0 is NOT a success. callGetDouble cannot distinguish "feature id unsupported" from a
        // genuine zero — the HAL answers 0.0 for both — so accepting it would (a) win over the two
        // manager channels that might hold a real value, and (b) latch the one-shot "WORKS" log,
        // permanently hiding whichever channel actually works. Exactly the defect a previous audit
        // round found in invokeDouble below; do not reintroduce it here. Nothing is lost: every
        // consumer of this field requires > 0.1 anyway, so 0.0 could never have surfaced.
        double v = BydDeviceHelper.callGetDouble(device, featureId);
        if (!Double.isNaN(v) && v != 0.0) {
            if (!loggedDoubleChannel) {
                loggedDoubleChannel = true;
                logger.info(String.format(java.util.Locale.US,
                    "device getDouble WORKS: type=%d id=0x%X -> %.3f"
                    + " (per-device get() had returned nothing)", type, featureId, v));
            }
            return v;
        }
        // CHANNEL 2/3 — manager-level, for firmware that routes reads through the manager instead.
        v = invokeDouble(deviceManager(ctx), type, featureId, "deviceManager");
        if (!Double.isNaN(v)) return v;
        return invokeDouble(autoManager(ctx), type, featureId, "autoManager");
    }

    private static double invokeDouble(Object target, int type, int featureId, String which) {
        if (target == null) return Double.NaN;
        try {
            Method m = method(target, "getDouble", int.class, int.class);
            if (m == null) return Double.NaN;
            Object r = m.invoke(target, type, featureId);
            if (r instanceof Number) {
                double d = ((Number) r).doubleValue();
                // NaN is a Number, and "no value" is exactly how the HAL answers when a feature id
                // is unsupported. Logging it as "WORKS" would latch the one-shot and permanently
                // suppress the line for the OTHER channel that may genuinely work — turning the
                // one diagnostic this class exists to produce into a wrong answer.
                if (Double.isNaN(d)) return Double.NaN;
                if (!loggedDoubleChannel) {
                    loggedDoubleChannel = true;
                    logger.info(String.format(java.util.Locale.US,
                        "manager getDouble WORKS via %s: type=%d id=0x%X -> %.3f"
                        + " (per-device get() had returned nothing)",
                        which, type, featureId, d));
                }
                return d;
            }
        } catch (Throwable t) {
            noteBinderDeath(t);
        }
        return Double.NaN;
    }

    /**
     * Drop cached handles when a call fails with a DEAD BINDER, so the channel can recover.
     *
     * <p>Without this, a framework/HAL restart (which does happen on head units) leaves a dead
     * proxy cached behind permanent {@code *Tried} latches: every later invoke throws, is swallowed,
     * and returns NaN for the rest of the process — with the one-shot log latches already set, so
     * nothing is ever logged about it either. Matched by SIMPLE NAME to avoid a hard dependency on
     * {@code android.os.DeadObjectException} / {@code RemoteException} from this package.
     */
    private static void noteBinderDeath(Throwable t) {
        // DELIBERATELY NARROW. Only these two mean "the other end is gone" and are therefore
        // recoverable by re-resolving. Bare RemoteException covers transient service-side failures,
        // and TransactionTooLargeException means the PAYLOAD was oversized on a perfectly healthy
        // binder — invalidating on either can never fix the cause, so the identical call fails
        // identically next poll and we would re-probe forever every 5 s. DeadObjectException
        // extends RemoteException, so nothing recoverable is lost by dropping the broader names.
        // Bounded walk: a cause chain can be cyclic (initCause only rejects cause == this), and an
        // unbounded walk here would spin the collector thread.
        Throwable c = t;
        for (int i = 0; c != null && i < 16; i++, c = c.getCause()) {
            String n = c.getClass().getSimpleName();
            if ("DeadObjectException".equals(n) || "DeadSystemException".equals(n)) {
                // Latched: this fires per INVALIDATE CYCLE, not per throw. Unlatched it produced
                // ~2 lines per getter per poll during a storm (~140k/day) — the exact per-poll
                // flood constraint 4 forbids.
                // TIME-throttled, not latched. A latch cleared only by init() went silent for the
                // rest of a multi-day session, while clearing it inside invalidate() would unlatch
                // completely and restore the per-poll flood (invalidate() runs on the line below).
                long nowMs = android.os.SystemClock.elapsedRealtime();
                if (nowMs - lastBinderDeathLogMs > BINDER_DEATH_LOG_THROTTLE_MS) {
                    lastBinderDeathLogMs = nowMs;
                    logger.warn("manager channel hit a dead binder (" + n
                            + ") — invalidating handles, re-probe deferred "
                            + (REPROBE_BACKOFF_MS / 1000) + "s");
                }
                invalidate();
                return;
            }
        }
    }

    /**
     * Integer sibling of {@link #getDouble}. Returns {@code Integer.MIN_VALUE} when unavailable,
     * which matches {@code BydVehicleData.UNAVAILABLE}.
     *
     * <p><b>NO SENTINEL FILTERING IS PERFORMED.</b> Only "the HAL gave us nothing" is normalised to
     * MIN_VALUE. The HAL's own not-available encodings — {@code -1}, {@code 65535},
     * {@code -10011}, {@code -2147482645} — come back as ordinary numbers, and by invariant I4 a
     * sentinel must never become a value. Callers MUST range-check exactly as they do for a
     * per-device read. (An earlier version of this doc said callers "can pass it straight through";
     * that was wrong and would have admitted a -1 as a real reading.)
     */
    public static int getInt(Context ctx, Object device, int featureId) {
        int type = BydDeviceHelper.deviceTypeOf(device);
        if (type == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        int v = invokeInt(deviceManager(ctx), type, featureId, "deviceManager");
        if (v != Integer.MIN_VALUE) return v;
        return invokeInt(autoManager(ctx), type, featureId, "autoManager");
    }

    private static int invokeInt(Object target, int type, int featureId, String which) {
        if (target == null) return Integer.MIN_VALUE;
        try {
            Method m = method(target, "getInt", int.class, int.class);
            if (m == null) return Integer.MIN_VALUE;
            Object r = m.invoke(target, type, featureId);
            if (r instanceof Number) {
                int i = ((Number) r).intValue();
                // Same trap as invokeDouble: MIN_VALUE means "no value", so it must not latch the
                // one-shot "WORKS" log and suppress the other channel's line.
                if (i == Integer.MIN_VALUE) return Integer.MIN_VALUE;
                if (!loggedIntChannel) {
                    loggedIntChannel = true;
                    logger.info(String.format(java.util.Locale.US,
                        "manager getInt WORKS via %s: type=%d id=0x%X -> %d", which, type, featureId, i));
                }
                return i;
            }
        } catch (Throwable t) {
            noteBinderDeath(t);
        }
        return Integer.MIN_VALUE;
    }

    // ==================== DEVICE ACQUISITION (reference app B tier 1 + 3) ====================

    /**
     * Resolve a device handle the two ways we currently do NOT: via
     * {@code DeviceManager.getDevice(type)} (reference app B's FIRST tier) and via a raw
     * {@code getConstructor(Context).newInstance(ctx)} (its LAST tier). The caller keeps using its
     * own {@code getInstance(ctx)} attempt first, so this only runs when that returned null.
     *
     * @param className fully-qualified platform device class, e.g.
     *                  {@code android.hardware.bydauto.instrument.BYDAutoInstrumentDevice}
     * @param deviceType the numeric type for the manager lookup, or {@code Integer.MIN_VALUE} to
     *                   skip that tier
     * @return a device handle, or null when every tier failed
     */
    public static Object resolveDeviceFallback(Context ctx, String className, int deviceType) {
        // Fall back to a LEARNED type when the caller has none. Without this the manager tier was
        // statically unreachable — the only call site cannot know a type before it holds an
        // instance, so it always passed MIN_VALUE and this tier never ran on any trim.
        if (deviceType == Integer.MIN_VALUE) deviceType = learnedDeviceType(className);
        // Tier A — manager.getDevice(type)
        Object mgr = deviceManager(ctx);
        if (mgr != null && deviceType != Integer.MIN_VALUE) {
            try {
                Method m = method(mgr, "getDevice", int.class);
                if (m != null) {
                    // Bounded: this runs on the daemon init thread, so an untimed binder call here
                    // could wedge init() exactly as an untimed constructor could.
                    Object d = invokeBounded(m, mgr, deviceType, "getDevice(" + deviceType + ")");
                    // VERIFY THE CLASS. getDevice(int) returns whatever device the manager maps to
                    // that int — if the type is wrong (see the learned-type provenance caveat), we
                    // would hand the caller a handle for a DIFFERENT HAL device and every feature-id
                    // read for the session would silently hit the wrong device. That is fabricated
                    // telemetry rather than missing telemetry, the worst possible outcome under I4.
                    // isInstance (not name equality) so a platform `…Impl` subclass still passes.
                    if (d != null) {
                        Class<?> want = null;
                        try { want = Class.forName(className); } catch (Throwable ignored) { }
                        if (want != null && !want.isInstance(d)) {
                            logger.warn("resolveDeviceFallback: getDevice(" + deviceType + ") returned "
                                    + d.getClass().getName() + " but " + className
                                    + " was requested — discarding (wrong deviceType?)");
                            d = null;
                        }
                    }
                    if (d != null) {
                        logger.info("resolveDeviceFallback: got " + className
                                + " via DeviceManager.getDevice(" + deviceType + ")");
                        return d;
                    }
                }
            } catch (Throwable t) {
                logger.debug("getDevice(" + deviceType + ") failed: " + t.getClass().getSimpleName());
            }
        }
        // Tier B — direct construction. Last resort: some firmware exposes the class but no
        // working getInstance singleton.
        //
        // VALIDATED before returning, for two reasons:
        //  1. A constructed device was never addDevice()'d with the manager, so it can be
        //     "present but answers nothing" — strictly WORSE for diagnosis than being absent,
        //     because initDevice() would list it in availableDevices and poll it all session.
        //  2. On a non-BYD build Class.forName resolves OUR OWN compile-time stub, whose
        //     (Context) constructor happily succeeds and whose getters all return sentinels —
        //     a guaranteed false positive that would log "constructed directly" on a device
        //     that has no BYD HAL at all.
        // deviceTypeOf() is the cheapest liveness probe available: it calls getDevicetype()/
        // getType() on the handle. A stub answers (so this does not filter case 2 alone), but a
        // handle that cannot even report its type is certainly unusable. We therefore ALSO
        // require the class we loaded not to be our stub, by checking the code source.
        if (ctx != null) {
            try {
                Class<?> cls = Class.forName(className);
                if (isOurOwnStub(cls, ctx)) {
                    logger.info("resolveDeviceFallback: " + className
                            + " resolves to our compile-time STUB (no platform HAL) — refusing");
                    return null;
                }
                Object d = constructBounded(cls, ctx, className);
                if (d != null) {
                    int t = BydDeviceHelper.deviceTypeOf(d);
                    if (t == Integer.MIN_VALUE) {
                        // Do NOT return it. A handle that cannot even report its own device type is
                        // unusable: every manager-level read keys on that type, so the caller would
                        // hold a device that answers nothing. (The type miss is only TTL-cached now,
                        // so this is no longer about poisoning a permanent cache — the handle itself
                        // is the problem.)
                        logger.info("resolveDeviceFallback: constructed " + className
                                + " but it cannot report a device type — discarding as unusable");
                        return null;
                    }
                    logger.info("resolveDeviceFallback: constructed " + className
                            + " directly (UNREGISTERED handle, type=" + t + ")");
                    return d;
                }
            } catch (Throwable t) {
                logger.debug("constructor fallback failed for " + className + ": "
                        + t.getClass().getSimpleName());
            }
        }
        return null;
    }

    /**
     * Construct a HAL device on a scratch thread with a bounded wait, so a constructor that blocks
     * on a dead binder cannot wedge the caller.
     *
     * <p>This mirrors the pattern the multimedia-device path in {@code BydDataCollector} already
     * uses ("3s timeout — abort if it hangs"), and it matters more here: this runs on the daemon
     * INIT thread, once per device. A single indefinite block would mean {@code init()} never
     * returns — no listeners, no polling, and the ACC-ON re-init wedged too — which is a far worse
     * outcome than simply failing to acquire one device.
     *
     * <p><b>A timeout is not free.</b> The abandoned thread may still complete the constructor later,
     * producing a HAL device nobody holds — and platform device constructors typically register a
     * binder listener or add themselves to a manager's device list, so that is a leaked HAL
     * REGISTRATION, not merely a leaked thread. We therefore refuse to retry a class that has once
     * wedged ({@link #wedgedClasses}): probing a hung HAL once per init would accumulate one such
     * leak per re-init forever.
     *
     * @return the constructed device, or null on failure/timeout
     */
    private static Object constructBounded(final Class<?> cls, final Context ctx, String className) {
        if (wedgedClasses.contains(className)) {
            logger.debug("resolveDeviceFallback: skipping " + className
                    + " — its constructor wedged earlier this process");
            return null;
        }
        final Object[] out = new Object[1];
        // Thread name carries the class: a thread dump with several abandoned constructors is
        // useless if they are all called the same thing.
        String simple = className.substring(className.lastIndexOf('.') + 1);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    out[0] = cls.getConstructor(Context.class).newInstance(ctx);
                } catch (Throwable ignored) { /* reported by the caller as a null result */ }
            }
        }, "BydDevCtor-" + simple);
        t.setDaemon(true);
        try {
            t.start();
            t.join(CONSTRUCT_TIMEOUT_MS);
        } catch (InterruptedException ie) {
            // Restore the flag: swallowing it destroys a shutdown/cancellation signal for the init
            // thread, which would then keep probing the remaining devices instead of unwinding.
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // Includes OutOfMemoryError from start(); falls through to the isAlive() check, which is
            // false in that case, so we return out[0] == null — logged by the caller as a failure.
        }
        if (t.isAlive()) {
            wedgedClasses.add(className);
            logger.warn("resolveDeviceFallback: constructor for " + className + " did not return in "
                    + CONSTRUCT_TIMEOUT_MS + "ms — abandoning and refusing further attempts"
                    + " this process (HAL likely wedged)");
            return null;
        }
        // Safe to read without synchronization: a successful join() or isAlive()==false establishes
        // the happens-before edge with the thread's final action (JLS 17.4.4 termination detection).
        return out[0];
    }

    /** Classes whose constructor once exceeded {@link #CONSTRUCT_TIMEOUT_MS}; never retried. */
    private static final java.util.Set<String> wedgedClasses =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Bound for a single HAL constructor call; matches the existing multimedia-path timeout. */
    private static final long CONSTRUCT_TIMEOUT_MS = 3000L;

    /**
     * True when {@code cls} was loaded from our own APK rather than the platform framework — i.e.
     * it is the compile-time stub, not the real HAL class. Platform classes come from the boot
     * classpath and have no code source / a system path; ours resolve inside the app's own dex.
     */
    private static boolean isOurOwnStub(Class<?> cls, Context ctx) {
        try {
            // PLATFORM-PRESENCE FIRST, classloader second. Classloader identity alone is the wrong
            // discriminator in both directions: a vendor HAL delivered via <uses-library> is appended
            // to the APP's PathClassLoader (so a REAL class looks like ours → we would refuse tier B
            // on a genuine vehicle), and a class loaded by any third loader (split APK, dynamic
            // feature, instrumentation) looks like neither (so a STUB would slip through and its
            // placeholder type could reach the learned-type cache).
            //
            // getSystemService("auto") answering is positive evidence that a BYD platform is present
            // — the panorama path already relies on it. If it answers, we are on a real head unit and
            // must NOT refuse the fallback regardless of which loader owns the class.
            if (autoManager(ctx) != null) return false;
            ClassLoader platform = Context.class.getClassLoader();
            ClassLoader mine = BydManagerChannel.class.getClassLoader();
            ClassLoader owner = cls.getClassLoader();
            if (owner == platform) return false;      // boot classpath → real
            return owner == mine;                     // our dex, and no BYD service → bundled stub
        } catch (Throwable ignored) {
            return false;   // cannot tell → do not block the fallback
        }
    }

    /**
     * Drop cached handles so the next call re-probes. Called on dead-binder detection
     * ({@link #noteBinderDeath}) and available as a test seam.
     *
     * <p>Resets the one-shot LOG latches too: a recovery that produced no confirmation line would
     * be invisible in a capture, and this class's entire value is that one decisive line.
     */
    public static void invalidate() {
        synchronized (BydManagerChannel.class) {
            invalidateGen++;   // invalidates any resolve currently in flight
            deviceManager = null; deviceManagerTried = false;
            autoManager = null;   autoManagerTried = false;
            // methodCache / missCache are DELIBERATELY NOT cleared. A Method is bound to a Class,
            // not to a binder handle, and a framework restart does not reload platform Class objects
            // in our process — so a hit stays valid and a miss stays a miss. Clearing them made every
            // absent call shape re-throw NoSuchMethodException on every poll during a storm, which is
            // exactly the cost constraint 2 exists to avoid. (Keys include the class name, so even if
            // recovery returned a different class the stale entries would simply go unused.)
            loggedManagerAcquire = false;
            loggedDoubleChannel = false;
            loggedIntChannel = false;
            // Device TYPES resolved from the now-stale handles must go too. Unlike methodCache
            // (Class-bound, so still valid), a type is read OFF a handle — and a dead binder can
            // make that read throw. Keeping a value derived from a dead handle would silently
            // misroute every manager-level read after recovery.
            BydDeviceHelper.invalidateDeviceTypeCache();
            // Arm the cool-down so the next poll does not immediately re-probe (see nextProbeAtMs).
            nextProbeAtMs = android.os.SystemClock.elapsedRealtime() + REPROBE_BACKOFF_MS;
        }
    }

    /**
     * Clear the dead-binder cool-down and allow an immediate re-probe. Called from
     * {@code BydDataCollector.init()}, where a NEW Context is genuine new information — waiting out
     * a backoff armed against the OLD one would be pointless.
     */
    public static void allowImmediateReprobe() {
        synchronized (BydManagerChannel.class) {
            nextProbeAtMs = 0L;
            // Do not clear wedgedClasses. A constructor that ignored interruption may still own a
            // Binder registration and thread; retrying it on every collector re-init accumulates one
            // permanent leak per ACC cycle. Only process restart can safely reset that evidence.
            lastBinderDeathLogMs = 0L;   // a new Context deserves a fresh warning if it recurs
        }
    }
}
