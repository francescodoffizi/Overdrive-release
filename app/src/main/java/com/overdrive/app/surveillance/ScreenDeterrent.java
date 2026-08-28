package com.overdrive.app.surveillance;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.Point;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.DaemonBootstrap;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.DaemonFonts;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daemon-side coordinator for the Screen Deterrent feature.
 *
 * Hybrid architecture: SurfaceControl owns the visual; an Activity in the
 * app process captures touches.
 *
 * Why hybrid? While ACC is off, BYD composites only its own AccAnimation
 * layer (`z=2^30`) into HWC — every other Window from any process is
 * excluded from composition by the vendor compositor. Only a SurfaceControl
 * layer placed directly into SurfaceFlinger at `z=Integer.MAX_VALUE` (=
 * 2^31-1) sits above AccAnimation and is composited. WindowManager-managed
 * Activity windows top out at `z≈3000` and are invisible during ACC-off.
 *
 * However, a SurfaceControl color/buffer layer has no `InputChannel` of its
 * own — taps pass through to whatever's beneath. So we also `am start` a
 * fullscreen DeterrentActivity in the app process: it's visually hidden by
 * AccAnimation but its InputChannel sits at the top of the input-dispatch
 * stack, consuming taps and back-key presses.
 *
 * Process split:
 *   ┌─ byd_cam_daemon (UID 2000) ──────────────────────────────────────┐
 *   │  Atomic CAS deadline (GL frame thread, lock-free)                │
 *   │  Executor:                                                       │
 *   │    PowerManager.TurnBacklightOn   ← BYD vendor API, UID 2000     │
 *   │    SurfaceControl@z=MAX render    ← visual                       │
 *   │    am start DeterrentActivity     ← input capture                │
 *   │    refresh UCM gate ≤1 Hz                                        │
 *   │    release surface + TurnBacklightOff                            │
 *   └──────────────────────────────────────────────────────────────────┘
 *   ┌─ com.overdrive.app (UID 10067) ──────────────────────────────────┐
 *   │  DeterrentActivity                                               │
 *   │    setOnTouchListener → consume                                  │
 *   │    polls UCM, finishes when deadline elapses                     │
 *   └──────────────────────────────────────────────────────────────────┘
 *
 * Cross-process coordination is via UnifiedConfigManager:
 *   - screenDeterrentActiveUntilMs: deadline; signals AccSentryDaemon's
 *     keep-alive (in a third process) to skip its setBacklightState(false).
 *   - screenDeterrentForceStop: set by AccSentryDaemon.exitSentryMode() to
 *     ask the daemon-side render and the activity to bail before duration.
 *   - screenDeterrentUserDismissed: set by DeterrentActivity on tap; cleared
 *     by cleanup() so it never blocks a later, unrelated firing.
 *
 * previewNow() is a separate, manually-triggered entry point (the settings
 * page's "Test on Screen" button) with no ACC or enabled-toggle gating —
 * see its own doc comment. It shares the renderer with fire() but not the
 * eligibility checks.
 */
public final class ScreenDeterrent {

    private static final String TAG = "ScreenDeterrent";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final int DEFAULT_DURATION_SEC = 8;
    private static final int GIF_FRAME_INTERVAL_MS = 50;
    private static final int STATIC_FRAME_TICK_MS = 200;
    /**
     * Fallback display dimensions if the WindowManager / Display lookup fails.
     * Matches the BYD Seal landscape baseline; portrait Seal (1080×1920) and
     * other supported models (Tang, Atto3, etc.) override these at fire()
     * time via resolveDisplaySize(). Typography in drawDefaultText() is
     * authored against this reference height — a `dh / FALLBACK_DISPLAY_H`
     * scale factor adapts every text size + icon dimension to the real panel
     * without per-model branching.
     */
    private static final int FALLBACK_DISPLAY_W = 1920;
    private static final int FALLBACK_DISPLAY_H = 1080;

    private static final long HOT_CACHE_TTL_MS = 1_000;
    private static final long GATE_REFRESH_INTERVAL_MS = 1_000;

    private static volatile ScreenDeterrent instance;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ScreenDeterrent");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /**
     * Background scheduler that refreshes the hot cache off the GL frame
     * thread. The GL thread only ever reads volatiles. Without this, every
     * cross-process write to the config file would force the next motion
     * frame to re-read disk (audit #7).
     */
    private final java.util.concurrent.ScheduledExecutorService cacheScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ScreenDeterrentCache");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

    private final AtomicLong extendDeadlineMs = new AtomicLong(0);
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Thread renderThread;

    private final AtomicBoolean previewInFlight = new AtomicBoolean(false);
    private volatile long previewDeadlineMs = 0;

    /**
     * Hot-path cache: read on the GL frame thread (volatile-only, no I/O).
     * Refreshed by cacheScheduler at HOT_CACHE_TTL_MS intervals.
     */
    private volatile boolean hotCacheEnabled = false;
    private volatile int hotCacheDurationSec = DEFAULT_DURATION_SEC;

    private long lastPublishedGateMs = 0;

    // Throttle for the "no daemon context" warning. Without this, sustained
    // motion produced one log line per millisecond because cleanup()
    // re-enqueues onMotionDetected() while the deadline is still in the
    // future, fire() early-returns on null ctx, and the cycle repeats.
    private long lastNoCtxWarnMs = 0;
    private static final long NO_CTX_WARN_INTERVAL_MS = 5_000;

    /**
     * Resolve the app context for this process. Two sources:
     *   1. CameraDaemon.getAppContext() — populated by CameraDaemon.main()
     *      via createAppContext(). This is the *primary* source: the GL
     *      thread that triggers ScreenDeterrent.onMotionDetected lives in
     *      the CameraDaemon process, so this field is always set there.
     *   2. DaemonBootstrap.getContext() — populated only if a daemon
     *      explicitly called DaemonBootstrap.init(). Currently no daemon
     *      does, so reading from here in isolation always returned null
     *      and produced the spam pattern described above.
     *
     * Reflection on CameraDaemon avoids a hard compile-time dependency
     * from `surveillance` → `daemon`; ScreenDeterrent is also reachable
     * from non-daemon callers (the API handler invokes reset() during a
     * config POST), so a direct import would create a coupling we'd then
     * need to maintain across both call sites.
     */
    private static Context resolveContext() {
        try {
            Class<?> cd = Class.forName("com.overdrive.app.daemon.CameraDaemon");
            Method m = cd.getMethod("getAppContext");
            Object ctx = m.invoke(null);
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {
            // CameraDaemon class missing or method renamed — fall through
            // to DaemonBootstrap.
        }
        return DaemonBootstrap.getContext();
    }
    private long lastGateWriteMs = 0;
    private long lastWakeReassertMs = 0;
    private boolean activityLaunched = false;

    private ScreenDeterrent() {
        // Refresh hot cache from disk every second on a dedicated background
        // thread. Bootstraps with one immediate read so first-motion isn't
        // forced to wait a full second.
        cacheScheduler.scheduleWithFixedDelay(
            this::refreshHotCacheFromDisk, 0, HOT_CACHE_TTL_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void refreshHotCacheFromDisk() {
        try {
            JSONObject s = UnifiedConfigManager.getSurveillance();
            hotCacheEnabled = s.optBoolean("screenDeterrentEnabled", false);
            int sec = s.optInt("screenDeterrentDurationSeconds", DEFAULT_DURATION_SEC);
            hotCacheDurationSec = Math.max(3, Math.min(30, sec));
        } catch (Throwable ignored) {
            // Keep last known good values on read failure.
        }
    }

    public static ScreenDeterrent getInstance() {
        if (instance == null) {
            synchronized (ScreenDeterrent.class) {
                if (instance == null) {
                    instance = new ScreenDeterrent();
                }
            }
        }
        return instance;
    }

    /**
     * GL frame thread enters here on every confirmed motion. Must be cheap:
     * volatile reads + atomic CAS only. NO I/O, NO locks, NO allocations.
     * The hot cache is kept fresh by cacheScheduler on a separate thread.
     */
    public void onMotionDetected() {
        if (!hotCacheEnabled) return;

        // SAFETY (deterrent-while-driving): the deterrent renders a full-screen
        // SurfaceControl layer at z=Integer.MAX_VALUE that occludes the ENTIRE
        // head-unit (nav, reversing camera, controls). It must NEVER appear
        // while ACC is on / the vehicle is in use. This is the load-bearing
        // gate: it covers EVERY caller — the motion pipeline
        // (SurveillanceEngineGpu), the /api/surveillance/screen-deterrent/test
        // endpoint, and any future caller — instead of relying on the
        // edge-driven screenDeterrentForceStop flag, which is only asserted on
        // the ACC OFF→ON transition and is false during steady-state driving
        // (so a deterrent triggered WHILE already driving had nothing stopping
        // it). isAccOn() is a free volatile read. It can be stale-false in
        // narrow windows (dead AccSentry, stalled IPC), but those self-heal via
        // the ACC-on disarm watchdog's HAL probe which also refreshes this
        // flag; this guard closes the common + steady-state cases that had NO
        // guard at all. Fail-safe: if ACC is on, do not arm or render.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn("Screen deterrent suppressed — ACC is ON (must never cover the screen while driving)");
            return;
        }

        long durationMs = hotCacheDurationSec * 1000L;
        long newDeadline = System.currentTimeMillis() + durationMs;

        while (true) {
            long current = extendDeadlineMs.get();
            if (newDeadline <= current) break;
            if (extendDeadlineMs.compareAndSet(current, newDeadline)) break;
        }

        if (!inFlight.compareAndSet(false, true)) return;

        cancelled.set(false);
        executor.execute(() -> {
            renderThread = Thread.currentThread();
            try {
                fire();
            } catch (Throwable t) {
                logger.warn("Screen deterrent failed: " + t.getMessage());
            } finally {
                cleanup();
            }
        });
    }

    private void cleanup() {
        try {
            java.util.Map<String, Object> reset = new java.util.HashMap<>();
            reset.put("screenDeterrentActiveUntilMs", 0L);
            reset.put("screenDeterrentUserDismissed", false);
            UnifiedConfigManager.updateValues("surveillance", reset);
        } catch (Throwable ignored) {}
        lastPublishedGateMs = 0;
        lastGateWriteMs = 0;
        activityLaunched = false;

        // Restore stealth backlight unless somebody else owns the wake (ACC-on).
        //
        // The isAccOn() read is deliberate and mirrors shouldStop()'s guard: the
        // other two conditions are not sufficient on the ACC-ON edge. `cancelled`
        // is never set on ACC-ON (only by the CameraDaemon shutdown hook and
        // SurveillanceEngineGpu), and `isForceStop()` reads a flag that
        // AccSentryDaemon CLEARS ~3.3 s into its wake sequence — and never sets at
        // all if that daemon is dead or wedged at the transition. Without this,
        // "owner walks up → deterrent fires → owner starts the car" can darken the
        // panel after the last wake and leave the driver with a black screen.
        // Note: turnBacklightOff → StealthPanel.turnOff also refuses while a fresh
        // ACC-ON edge is in its trust window, so this is belt-and-braces; keeping it
        // here avoids even starting the teardown chain (locked config writes +
        // binder calls) on the ACC-ON edge.
        Context ctx = resolveContext();
        if (ctx != null && !cancelled.get() && !isForceStop()
                && !com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            turnBacklightOff(ctx);
        }

        // Race fix (audit #8): a GL-thread motion bump can land between any
        // two of the next three lines. Clearing extendDeadlineMs first then
        // inFlight is wrong (bump lost). Clearing inFlight first then
        // re-reading the deadline AFTER catches the bump correctly. We also
        // re-trigger by enqueueing a fresh executor task instead of recursing
        // (audit #9) — recursion on the executor with sustained motion would
        // keep the executor queue at depth 1 (single-thread) but generates
        // unbounded stack frames inside cleanup→onMotionDetected→cleanup.
        renderThread = null;
        inFlight.set(false);
        long pendingDeadline = extendDeadlineMs.get();
        long now = System.currentTimeMillis();
        if (pendingDeadline > now && !cancelled.get()) {
            // Re-enter via the public API so we hit inFlight CAS again
            // cleanly. The executor will queue our next fire() task.
            // Note: at this point another GL-thread motion call could ALSO
            // re-enter — that's fine, only one wins the CAS.
            executor.execute(this::onMotionDetected);
        } else {
            extendDeadlineMs.set(0);
        }
    }

    // ── previewNow() — manual, user-initiated preview ──────────────────────
    //
    // Entered only from the settings page's "Test on Screen" button, never
    // from the motion pipeline. Deliberately has NO AccMonitor.isAccOn()
    // check and no screenDeterrentEnabled requirement: the button is meant
    // to work while sitting in the car with ACC on, inspecting the selected
    // asset on the real display. onMotionDetected()/fire()/shouldStop() —
    // the real motion-triggered path — are untouched by this method.

    /** Renders the configured asset full-screen until the duration elapses
     *  or the user taps the panel. Reuses {@link #renderAsset}, the same
     *  renderer the real motion path uses. */
    public void previewNow() {
        if (!previewInFlight.compareAndSet(false, true)) return;
        executor.execute(() -> {
            renderThread = Thread.currentThread();
            try {
                firePreview();
            } catch (Throwable t) {
                logger.warn("Screen deterrent preview failed: " + t.getMessage());
            } finally {
                cleanupPreview();
            }
        });
    }

    private void firePreview() {
        Context ctx = resolveContext();
        if (ctx == null) {
            logger.warn("No daemon context — cannot preview");
            return;
        }
        wakePanel(ctx);
        launchActivity();

        Point size = resolveDisplaySize(ctx);
        previewDeadlineMs = System.currentTimeMillis() + hotCacheDurationSec * 1000L;

        renderAsset(size.x, size.y, this::shouldStopPreview, this::maybeReassertWake);
    }

    private boolean shouldStopPreview() {
        if (cancelled.get()) return true;
        if (System.currentTimeMillis() >= previewDeadlineMs) return true;
        try {
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            if (s == null) return false;
            if (s.optBoolean("screenDeterrentForceStop", false)) return true;
            if (s.optBoolean("screenDeterrentUserDismissed", false)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void cleanupPreview() {
        try {
            UnifiedConfigManager.updateValues("surveillance",
                java.util.Collections.singletonMap("screenDeterrentUserDismissed", false));
        } catch (Throwable ignored) {}
        renderThread = null;
        previewInFlight.set(false);
    }

    public void cancel() {
        cancelled.set(true);
        extendDeadlineMs.set(0);
        Thread t = renderThread;
        if (t != null) {
            try { t.interrupt(); } catch (Throwable ignored) {}
        }
    }

    public void reset() {
        extendDeadlineMs.set(0);
        cancelled.set(false);
        // Force the cache scheduler to refresh on its next tick by writing
        // through the same code path (idempotent).
        refreshHotCacheFromDisk();
    }

    // ── fire() — the executor-thread render loop ───────────────────────────

    private void fire() {
        // SAFETY (deterrent-while-driving), defense-in-depth: onMotionDetected()
        // enqueues fire() on the executor, so ACC could turn on in the gap
        // between that check and this render. Re-check at the actual render
        // moment — this is the last line before the z=MAX SurfaceControl layer
        // is created. Zero the deadline so cleanup()'s sustained-motion
        // re-enqueue loop can't keep re-firing into a driving window.
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            logger.warn("Screen deterrent fire() aborted — ACC turned ON before render");
            extendDeadlineMs.set(0);
            return;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            // Throttle: cleanup() re-enqueues onMotionDetected on sustained
            // motion, so without this throttle a single null-ctx state
            // produces one warning per millisecond. Also cancel the deadline
            // so cleanup doesn't keep re-enqueueing — there's nothing this
            // process can do without a Context, and ACC monitoring will
            // re-trigger us on the next genuine motion event after context
            // becomes available.
            long now = System.currentTimeMillis();
            if (now - lastNoCtxWarnMs > NO_CTX_WARN_INTERVAL_MS) {
                logger.warn("No daemon context — cannot wake panel (throttled)");
                lastNoCtxWarnMs = now;
            }
            extendDeadlineMs.set(0);
            return;
        }

        publishGate(extendDeadlineMs.get());
        wakePanel(ctx);
        launchActivity();  // touch-capture in app process
        activityLaunched = true;

        // Resolve the real panel size once per fire() so portrait-rotated
        // Seal (1080×1920) and other models (Tang, Atto3, etc.) get a buffer
        // matched to the panel instead of a stretched/clipped 1920×1080
        // landscape canvas.
        Point size = resolveDisplaySize(ctx);
        final int dispW = size.x;
        final int dispH = size.y;

        renderAsset(dispW, dispH, this::shouldStop, this::maybeReassertWake);
    }

    /**
     * Shared asset dispatch (video/GIF/static image) used by both the real
     * motion path ({@link #fire}) and the manual preview ({@link #firePreview}).
     * Eligibility gating lives entirely in the caller — this method just renders
     * whatever is configured until {@code stop} says otherwise.
     */
    private void renderAsset(int dispW, int dispH, ScreenDeterrentVideo.StopSignal stop,
                             Runnable onFrame) {
        String imagePath = getImagePath();

        // Video owns its own SurfaceControl layer, so none of the Bitmap/lockCanvas
        // plumbing below applies. On failure we fall through to the default screen
        // rather than a poster frame: MediaMetadataRetriever can hang indefinitely
        // on a malformed mp4, and this thread owns the full-screen layer.
        if (imagePath != null && !imagePath.isEmpty()
                && ScreenDeterrentVideo.isMp4File(imagePath)) {
            if (ScreenDeterrentVideo.play(imagePath, dispW, dispH, stop, onFrame)) {
                return;
            }
            logger.warn("Deterrent video playback failed — falling back to default screen");
            imagePath = "";
        }

        Object surface = null;
        Bitmap staticFrame = null;
        Movie movie = null;
        try {
            boolean isGif = imagePath != null && !imagePath.isEmpty()
                    && isGifFile(imagePath);

            surface = createBufferLayer("ScreenDeterrent", dispW, dispH);
            if (surface == null) {
                logger.warn("Failed to create SurfaceControl buffer layer");
                return;
            }
            applyTransaction(surface, Integer.MAX_VALUE, true);

            if (isGif) movie = decodeGifSafe(imagePath);

            if (movie != null && movie.duration() > 0) {
                renderGifLoop(surface, movie, dispW, dispH, stop);
            } else {
                staticFrame = buildStaticFrame(imagePath, dispW, dispH);
                renderStaticLoop(surface, staticFrame, stop);
            }
        } catch (Throwable t) {
            logger.warn("Deterrent render failed: " + t.getMessage());
        } finally {
            if (staticFrame != null) {
                try { staticFrame.recycle(); } catch (Throwable ignored) {}
            }
            if (surface != null) releaseSurface(surface);
        }
    }

    private void renderStaticLoop(Object surface, Bitmap frame, ScreenDeterrentVideo.StopSignal stop) {
        drawBitmapToSurface(surface, frame);
        while (!stop.shouldStop()) {
            maybeReassertWake();
            try {
                Thread.sleep(STATIC_FRAME_TICK_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (cancelled.get()) return;
                Thread.interrupted();
            }
        }
    }

    private void renderGifLoop(Object surface, Movie movie, int dispW, int dispH,
                               ScreenDeterrentVideo.StopSignal stop) {
        Bitmap frame = null;
        try {
            frame = Bitmap.createBitmap(dispW, dispH, Bitmap.Config.ARGB_8888);
            Canvas frameCanvas = new Canvas(frame);

            float scale = Math.min((float) dispW / movie.width(),
                                   (float) dispH / movie.height());
            int dw = (int) (movie.width() * scale);
            int dh = (int) (movie.height() * scale);
            int dx = (dispW - dw) / 2;
            int dy = (dispH - dh) / 2;

            long start = SystemClock.uptimeMillis();
            while (!stop.shouldStop()) {
                long elapsed = SystemClock.uptimeMillis() - start;
                int progress = (int) (elapsed % movie.duration());
                movie.setTime(progress);

                frameCanvas.drawColor(Color.BLACK);
                frameCanvas.save();
                frameCanvas.translate(dx, dy);
                frameCanvas.scale(scale, scale);
                movie.draw(frameCanvas, 0, 0);
                frameCanvas.restore();

                drawBitmapToSurface(surface, frame);
                maybeReassertWake();

                try {
                    Thread.sleep(GIF_FRAME_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (cancelled.get()) return;
                    Thread.interrupted();
                }
            }
        } catch (Throwable t) {
            logger.debug("GIF loop failed: " + t.getMessage());
        } finally {
            if (frame != null) frame.recycle();
        }
    }

    private void maybeReassertWake() {
        long now = System.currentTimeMillis();
        if (now - lastWakeReassertMs > 5_000) {
            Context ctx = resolveContext();
            if (ctx != null) wakePanel(ctx);
            lastWakeReassertMs = now;
        }
    }

    /**
     * Stop predicate. Side-effect: throttled UCM gate refresh (≤1 Hz, only
     * when local deadline moved).
     */
    private boolean shouldStop() {
        if (cancelled.get()) return true;
        // SAFETY (deterrent-while-driving): tear down the in-flight layer the
        // instant ACC reads ON, independent of the cross-process
        // screenDeterrentForceStop flag. forceStop is only written on the ACC
        // OFF→ON edge by AccSentryDaemon; if that daemon is dead/stalled at the
        // transition, forceStop never flips and the loop would otherwise run to
        // its deadline (up to 30s) over the live driving screen. A direct
        // isAccOn() read here bounds that to one render tick (≤200ms).
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) return true;
        long now = System.currentTimeMillis();
        long localDeadline = extendDeadlineMs.get();
        if (now >= localDeadline) return true;

        if (localDeadline != lastPublishedGateMs
                && (now - lastGateWriteMs) >= GATE_REFRESH_INTERVAL_MS) {
            publishGate(localDeadline);
        }

        try {
            // forceReload() (not loadConfig()) is required here. ext4 mtime
            // resolution is 1s; this method's caller writes mtime via
            // publishGate at up to 1 Hz, while AccSentryDaemon may write
            // screenDeterrentForceStop=true cross-UID. If both writes land
            // in the same wallclock-second, loadConfig()'s
            // (fileModified ≤ lastModified) check returns the stale cached
            // config and shouldStop misses the force-stop signal for up to
            // ~1s — perceived as user-tap-to-dismiss latency on the
            // deterrent. forceReload re-parses every call (~10 KB JSON on
            // a tiny structure) which is cheap enough on this hot path
            // compared to the deterrent's render cost.
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            if (s == null) return false;
            if (s.optBoolean("screenDeterrentForceStop", false)) return true;
            if (!s.optBoolean("screenDeterrentEnabled", false)) return true;
            if (s.optBoolean("screenDeterrentUserDismissed", false)) {
                // Skip any pending sustained-motion re-fire — the user just
                // dismissed this exact session, not future motion.
                extendDeadlineMs.set(0);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void publishGate(long deadlineMs) {
        try {
            UnifiedConfigManager.updateValues("surveillance",
                java.util.Collections.singletonMap("screenDeterrentActiveUntilMs", deadlineMs));
            lastPublishedGateMs = deadlineMs;
            lastGateWriteMs = System.currentTimeMillis();
        } catch (Throwable t) {
            logger.debug("Failed to publish gate: " + t.getMessage());
        }
    }

    private static boolean isForceStop() {
        try {
            // forceReload(): same rationale as shouldStop above — a
            // same-second cross-UID write of screenDeterrentForceStop
            // would be hidden behind the cached config until the next
            // mtime bump. cleanup() callers depend on a fresh read.
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            return s != null && s.optBoolean("screenDeterrentForceStop", false);
        } catch (Throwable t) {
            return false;
        }
    }

    // ── Bitmap building (downsample to avoid OOM) ──────────────────────────

    /** Decode static image with inSampleSize keyed to display so even a
     *  50 MP user upload doesn't allocate >100 MB. */
    private Bitmap buildStaticFrame(String imagePath, int dispW, int dispH) {
        Bitmap bg = null;
        if (imagePath != null && !imagePath.isEmpty()) {
            bg = decodeBitmapDownsampled(imagePath, dispW, dispH);
        }

        Bitmap canvas = Bitmap.createBitmap(dispW, dispH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvas);

        if (bg != null) {
            c.drawColor(Color.BLACK);
            float scale = Math.min((float) dispW / bg.getWidth(),
                                   (float) dispH / bg.getHeight());
            int dw = (int) (bg.getWidth() * scale);
            int dh = (int) (bg.getHeight() * scale);
            int dx = (dispW - dw) / 2;
            int dy = (dispH - dh) / 2;
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(bg, null, new Rect(dx, dy, dx + dw, dy + dh), p);
            bg.recycle();
        } else {
            c.drawColor(0xFFB00020);
            drawDefaultText(c, dispW, dispH);
        }
        return canvas;
    }

    /**
     * Decode the OverDrive glyph from the APK asset bundle once, cache it
     * for subsequent fire() calls, and return null on any failure (caller
     * falls back to the synthetic camera icon).
     *
     * Reads {@code web/shared/app-icon-glyph-dark.webp} — the bare brand
     * glyph on a transparent background. Painted directly over the red
     * deterrent screen with alpha blending; no container / squircle, the
     * glyph floats free to match the rest of the deterrent's flat layout.
     */
    private volatile Bitmap cachedBrandLogo = null;
    private Bitmap loadBrandLogo() {
        Bitmap b = cachedBrandLogo;
        if (b != null && !b.isRecycled()) return b;

        Context ctx = resolveContext();
        if (ctx == null) return null;
        try {
            android.content.res.AssetManager am = ctx.getAssets();
            if (am == null) return null;
            try (java.io.InputStream is = am.open("web/shared/app-icon-glyph-dark.webp")) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap decoded = BitmapFactory.decodeStream(is, null, opts);
                if (decoded != null) cachedBrandLogo = decoded;
                return decoded;
            }
        } catch (Throwable t) {
            logger.debug("loadBrandLogo failed: " + t.getMessage());
            return null;
        }
    }

    private static Bitmap decodeBitmapDownsampled(String path, int dispW, int dispH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while ((bounds.outWidth / sample) > dispW * 2
                    || (bounds.outHeight / sample) > dispH * 2) {
                sample *= 2;
            }
            BitmapFactory.Options decode = new BitmapFactory.Options();
            decode.inSampleSize = sample;
            decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, decode);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Default content layout (no user image):
     *   ┌──────────────────────────────────────────┐
     *   │                                          │
     *   │           ┌───────────────┐              │
     *   │           │   [camera]    │   ← rounded-rect icon, light grey
     *   │           └───────────────┘              │
     *   │                                          │
     *   │             OVERDRIVE                    │   ← wordmark, light grey
     *   │                                          │
     *   │       YOU ARE ON CAMERA                  │   ← 144pt BOLD headline
     *   │                                          │
     *   │   Surveillance recording in progress     │   ← 64pt subtitle
     *   │                                          │
     *   └──────────────────────────────────────────┘
     * All text + icon use #E5E7EB (light grey) on the red background — pure
     * white was too harsh; this reads cleaner per user feedback.
     */
    private void drawDefaultText(Canvas c, int dispW, int dispH) {
        // Pure white for foreground text — sits on a saturated red background,
        // so the slight harshness vs the previous E5E7EB grey is the right
        // call for legibility from across the cabin. The brand glyph keeps
        // its mint green; it gets painted inside a white rounded-rect card
        // (below) so it reads cleanly without clashing with the red fill.
        final int FG = 0xFFFFFFFF;

        // Layout was authored against the 1920×1080 BYD Seal landscape
        // baseline. Scale every absolute pixel value by the shorter axis
        // (min of width-ratio and height-ratio) so portrait Seal (1080×1920)
        // and other panels keep the same visual proportions instead of
        // overflowing the headline off-canvas or producing a tiny logo on a
        // wider screen.
        float minRatio = Math.min((float) dispW / FALLBACK_DISPLAY_W,
                                  (float) dispH / FALLBACK_DISPLAY_H);

        // 1. OverDrive glyph, centered upper-third. Painted INSIDE a white
        //    rounded-rectangle "card" so the green glyph has its own surface
        //    against the red deterrent background. The card uses iOS-style
        //    squircle radius (~22%) to match the OverDrive launcher icon.
        //
        //    Falls back to a hand-drawn camera icon if the APK asset can't
        //    be loaded (asset path renamed, context lost, OOM on decode)
        //    so the deterrent always paints something instead of going blank.
        float iconCx = dispW / 2f;
        float iconCy = dispH * 0.26f;
        float cardSize = 280f * minRatio;
        Bitmap logo = loadBrandLogo();
        if (logo != null) {
            float cardHalf = cardSize / 2f;
            android.graphics.RectF cardRect = new android.graphics.RectF(
                iconCx - cardHalf, iconCy - cardHalf,
                iconCx + cardHalf, iconCy + cardHalf);
            float corner = cardSize * 0.2237f;

            // White card.
            Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            cardPaint.setColor(0xFFFFFFFF);
            cardPaint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(cardRect, corner, corner, cardPaint);

            // Glyph centered inside, with breathing room.
            float glyphSize = cardSize * 0.88f;
            float glyphHalf = glyphSize / 2f;
            Rect glyphDst = new Rect(
                (int) (iconCx - glyphHalf), (int) (iconCy - glyphHalf),
                (int) (iconCx + glyphHalf), (int) (iconCy + glyphHalf));
            Paint imgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            c.drawBitmap(logo, null, glyphDst, imgPaint);
        } else {
            drawCameraIcon(c, iconCx, iconCy, 200f * minRatio, FG);
        }

        // Text lines below. On a BSP where the daemon font system is unusable,
        // drawText would abort the whole process natively (SIGABRT —
        // gDefaultTypeface == null). The brand glyph / camera icon above still
        // conveys "you are on camera" without text, so skip the wordmark +
        // headline + subtitle rather than crash the daemon. Healthy DiLink
        // 3/4/5 devices always take the text path.
        if (!DaemonFonts.canDrawText()) {
            logger.warn("drawDefaultText: font system unusable, rendering deterrent without text");
            return;
        }

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(FG);
        p.setTextAlign(Paint.Align.CENTER);

        // 2. OVERDRIVE wordmark — white, sits below the card.
        p.setTextSize(56f * minRatio);
        DaemonFonts.apply(p, Typeface.BOLD);
        p.setLetterSpacing(0.20f);
        c.drawText("OVERDRIVE", dispW / 2f, dispH * 0.50f, p);

        // 3. Headline — BIG, BOLD. Authored at 144pt against 1080p; scaled
        //    proportionally to the panel's shorter axis so portrait or
        //    smaller panels don't blow the text off the canvas.
        //    Letter-spacing 0.04 keeps it readable at this size.
        p.setTextSize(144f * minRatio);
        DaemonFonts.apply(p, Typeface.BOLD);
        p.setLetterSpacing(0.04f);
        String headline = readMessage("YOU ARE ON CAMERA");
        c.drawText(headline, dispW / 2f, dispH * 0.70f, p);

        // 4. Subtitle.
        p.setTextSize(64f * minRatio);
        DaemonFonts.apply(p, Typeface.NORMAL);
        p.setLetterSpacing(0.04f);
        p.setAlpha(220);
        c.drawText("Surveillance recording in progress",
            dispW / 2f, dispH * 0.82f, p);
    }

    /**
     * Render a flat outline-style camera icon inside a rounded rectangle.
     * cx, cy are the icon center; size is the rounded-rect side length in px.
     * Designed minimal — single colour, ~12px stroke, no fills, so it reads
     * cleanly against any background.
     */
    private static void drawCameraIcon(Canvas c, float cx, float cy, float size, int color) {
        if (size <= 0) return;
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.045f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        float half = size / 2f;
        float radius = size * 0.18f;

        // Outer rounded rectangle.
        android.graphics.RectF rect = new android.graphics.RectF(
            cx - half, cy - half, cx + half, cy + half);
        c.drawRoundRect(rect, radius, radius, stroke);

        // Inside: a simplified camera lens (circle) + viewfinder bump (small rect on top).
        float lensRadius = size * 0.22f;
        c.drawCircle(cx, cy + size * 0.04f, lensRadius, stroke);

        // Inner lens dot (filled to break monotony).
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy + size * 0.04f, size * 0.07f, fill);

        // Top viewfinder rectangle.
        float vfW = size * 0.18f;
        float vfH = size * 0.10f;
        android.graphics.RectF vf = new android.graphics.RectF(
            cx - vfW / 2f - size * 0.10f, cy - half + size * 0.06f,
            cx - vfW / 2f - size * 0.10f + vfW, cy - half + size * 0.06f + vfH);
        c.drawRoundRect(vf, radius * 0.3f, radius * 0.3f, stroke);
    }

    private static Movie decodeGifSafe(String path) {
        try {
            byte[] data = readAllBytes(path);
            if (data != null) return Movie.decodeByteArray(data, 0, data.length);
        } catch (Throwable ignored) {}
        return null;
    }

    // ── Display size resolution ────────────────────────────────────────────

    /**
     * Real-pixel size of the head unit's primary display. Uses
     * Display.getRealSize() so we get the full panel resolution including
     * any system bars (the SurfaceControl layer paints over everything
     * regardless). Falls back to the BYD Seal landscape baseline when the
     * lookup fails — better to render a slightly off-size buffer than
     * nothing at all.
     *
     * Why per-fire instead of cached: the BYD Seal panel rotates between
     * 1920×1080 and 1080×1920 at runtime (per the target-display memory),
     * and a cached value would lock the deterrent to whatever orientation
     * was active at process start.
     */
    private static Point resolveDisplaySize(Context ctx) {
        Point out = new Point(FALLBACK_DISPLAY_W, FALLBACK_DISPLAY_H);
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return out;
            Display d = wm.getDefaultDisplay();
            if (d == null) return out;
            Point real = new Point();
            d.getRealSize(real);
            if (real.x > 0 && real.y > 0) {
                out.x = real.x;
                out.y = real.y;
            }
        } catch (Throwable t) {
            logger.debug("resolveDisplaySize failed: " + t.getMessage());
        }
        return out;
    }

    // ── Wake / sleep panel (BYD PowerManager extension, UID 2000 only) ─────

    /**
     * Bring the panel up so the deterrent layer is actually visible.
     *
     * <p>Tier 1 is the original {@code TurnBacklightOn(long)} reflection,
     * unchanged. On dilink4 we then escalate through
     * {@link com.overdrive.app.power.StealthPanel#turnOn} — which adds the
     * vendor {@code TurnBacklightOnWithLock} tier and verifies the result with
     * {@code getPowerScreenStatus()}. That matters here specifically: the
     * keep-alive may have darkened the panel with
     * {@code TurnBacklightOffWithLock}, and a plain tier-1 wake can be silently
     * ignored against that vendor lock — leaving the deterrent compositing its
     * z=MAX layer onto a dark screen, i.e. an intruder warning nobody sees.
     *
     * <p>{@code turnOn()} self-skips when the screen already reads on, so on
     * legacy units (where tier 1 works) it costs one status read and performs no
     * additional panel write.
     */
    private static void wakePanel(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            for (String name : new String[]{"TurnBacklightOn", "turnBacklightOn"}) {
                try {
                    Method m = pm.getClass().getMethod(name, long.class);
                    m.invoke(pm, SystemClock.uptimeMillis());
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable t) {
            logger.warn("TurnBacklightOn reflection failed: " + t.getMessage());
        }
        // dilink4: escalate to the WithLock tier + verify, else the layer may be
        // invisible. Self-gated — no-op on legacy.
        try {
            if (com.overdrive.app.power.StealthPanel.isDilink4()) {
                // The tier-1 write above already changed the panel behind
                // StealthPanel's back. On firmware with no getPowerScreenStatus()
                // that is unobservable, so declare it — otherwise the
                // unverifiable-firmware latch could treat this wake (and the
                // re-darken after the deterrent) as redundant and skip the write,
                // leaving the warning invisible or the panel lit afterwards.
                com.overdrive.app.power.StealthPanel.notePanelStateChangedExternally();
                com.overdrive.app.power.StealthPanel.turnOn(ctx);
            }
        } catch (Throwable t) {
            logger.debug("Verified panel wake failed: " + t.getMessage());
        }
    }

    /**
     * Return the panel to dark after the deterrent ends.
     *
     * <p>Tier 1 ({@code TurnBacklightOff(long)}) is the original path and is
     * kept for legacy units exactly as it was. On dilink4 we use the verified
     * two-tier path instead: the reference OEM dashcam app (
     * {@code BacklightController.turnOffBacklight}) escalates to
     * {@code TurnBacklightOffWithLock} and re-checks
     * {@code getPowerScreenStatus()}, because tier 1 alone does not reliably
     * stick on this firmware — which would leave the panel lit for the rest of
     * the park after every motion event.
     */
    private static void turnBacklightOff(Context ctx) {
        try {
            if (com.overdrive.app.power.StealthPanel.isDilink4()) {
                // Honour an in-flight user screen-on request. Without this the
                // deterrent defeats it from the OTHER process: the user taps
                // screen-on, walks to the car, motion fires a deterrent, and this
                // cleanup darkens the panel inside the 60 s grace — so the
                // screen-on button appears not to work whenever you are near the
                // car. The keep-alive already declines to darken in that window;
                // this is the same rule applied to the deterrent's own teardown.
                if (com.overdrive.app.power.StealthPanel.isUserOverrideActive()) {
                    logger.info("Deterrent end: leaving panel lit — user screen-on override active");
                    return;
                }
                com.overdrive.app.power.StealthPanel.turnOff(ctx);
                return;
            }
        } catch (Throwable t) {
            logger.debug("Verified backlight-off failed, falling back: " + t.getMessage());
        }
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            for (String name : new String[]{"TurnBacklightOff", "turnBacklightOff"}) {
                try {
                    Method m = pm.getClass().getMethod(name, long.class);
                    m.invoke(pm, SystemClock.uptimeMillis());
                    return;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable t) {
            logger.debug("TurnBacklightOff reflection failed: " + t.getMessage());
        }
    }

    // ── Activity launch (touch capture only, no visual) ────────────────────

    private static void launchActivity() {
        try {
            // TWO fixes here, both the same class of bug:
            //
            // 1. `--activity-new-task` was passed and is NOT a valid `am` option —
            //    Intent.parseCommandArgs has no case for it, so its default: branch throws
            //    IllegalArgumentException("Unknown option: …") and ShellCommand.exec aborts
            //    the ENTIRE command. This activity therefore never launched at all; the
            //    failure was invisible because the deterrent's visible output comes from the
            //    daemon-owned SurfaceControl layer (only the touch capture was silently
            //    lost) and output here goes to /dev/null. NEW_TASK is added unconditionally
            //    by ActivityManagerShellCommand.runStartActivity, so nothing replaces it.
            //
            // 2. NO `--activity-*` options at all now — both of the previous ones were wrong
            //    for this activity, and neither buys anything:
            //
            //    * `--activity-no-history` (FLAG_ACTIVITY_NO_HISTORY) finishes the activity as
            //      soon as it stops being foreground. During an ACC-off deterrent anything that
            //      briefly takes foreground (a vendor overlay, a display off/on cycle) would
            //      therefore kill it MID-FIRE — and because that is not an orderly finish,
            //      onDestroy writes screenDeterrentForceStop=true, which the daemon's
            //      shouldStop() reads on its next ~200ms tick and tears the whole render down.
            //      The deterrent would silently truncate, and the latched force-stop can also
            //      cut the next one short. `excludeFromRecents=true` in the manifest already
            //      covers the only thing no-history was wanted for (staying out of recents).
            //    * `--activity-clear-task` would defeat the coalescing this class documents:
            //      re-launching during sustained motion is meant to land on onNewIntent (a
            //      deliberate no-op — "the deadline poll keeps us up"), not destroy and
            //      re-create the instance. (For a singleInstance activity alone in its task
            //      CLEAR_TASK is largely a no-op, but it is still the wrong intent to express.)
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                "am start -n com.overdrive.app/.DeterrentActivity "
                    + "-a android.intent.action.MAIN "
                    + ">/dev/null 2>&1 &");
            pb.redirectErrorStream(true);
            pb.start();
            // Detach — am start can take 2-3s on cold app spawn; we don't wait.
        } catch (Throwable t) {
            logger.warn("am start DeterrentActivity failed: " + t.getMessage());
        }
    }

    // ── SurfaceControl reflection (visual layer at z=Integer.MAX_VALUE) ────

    // Cached reflection metadata for the SurfaceControl path. drawBitmapToSurface
    // is invoked every GIF_FRAME_INTERVAL_MS (50ms = 20Hz) for the duration of a
    // deterrent fire, so even cheap Class.forName + getConstructor lookups add
    // up: a 30s fire = 600 lookups, ~50 fires/day during active sentry use =
    // ~30k/day. Resolve once at first call, reuse forever. The hidden-API
    // SurfaceControl class is process-stable on this firmware (the 20Hz draw
    // loop already proves it works). Volatile for safe publication; we accept
    // the rare double-resolve race because the resolution is idempotent.
    //
    // applyTransaction / releaseSurface / createBufferLayer also touch the same
    // SurfaceControl Class<?>, so they reuse the cached reference at zero cost.
    // Their per-fire (not per-frame) Method lookups are left uncached — the
    // call rate doesn't justify the extra surface area.
    private static volatile Class<?> surfaceControlClass;
    private static volatile java.lang.reflect.Constructor<Surface> surfaceCtorFromSc;
    private static volatile boolean surfaceControlReflectionResolved = false;
    private static volatile boolean surfaceControlReflectionFailed = false;

    private static void resolveSurfaceControlReflection() {
        if (surfaceControlReflectionResolved || surfaceControlReflectionFailed) return;
        try {
            Class<?> cls = Class.forName("android.view.SurfaceControl");
            java.lang.reflect.Constructor<Surface> ctor = Surface.class.getConstructor(cls);
            surfaceControlClass = cls;
            surfaceCtorFromSc = ctor;
            surfaceControlReflectionResolved = true;
        } catch (Throwable t) {
            // Permanent failure — class or ctor genuinely not present on this
            // firmware. Mark failed so we stop probing on every 20Hz tick. The
            // hidden-API surface is fixed at boot.
            surfaceControlReflectionFailed = true;
            logger.debug("SurfaceControl reflection unavailable: " + t.getMessage());
        }
    }

    private static Object createBufferLayer(String name, int w, int h) {
        try {
            Class<?> builderCls = Class.forName("android.view.SurfaceControl$Builder");
            Object builder = builderCls.getDeclaredConstructor().newInstance();
            builderCls.getMethod("setName", String.class).invoke(builder, name);
            builderCls.getMethod("setBufferSize", int.class, int.class).invoke(builder, w, h);
            try {
                builderCls.getMethod("setOpaque", boolean.class).invoke(builder, true);
            } catch (NoSuchMethodException ignored) {}
            return builderCls.getMethod("build").invoke(builder);
        } catch (Throwable t) {
            logger.warn("SurfaceControl.Builder failed: " + t.getMessage());
            return null;
        }
    }

    private static void applyTransaction(Object surface, int z, boolean show) {
        try {
            resolveSurfaceControlReflection();
            Class<?> sc = surfaceControlReflectionResolved
                    ? surfaceControlClass
                    : Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("setLayer", sc, int.class).invoke(tx, surface, z); } catch (Throwable ignored) {}
            try { txCls.getMethod("setAlpha", sc, float.class).invoke(tx, surface, 1.0f); } catch (Throwable ignored) {}
            if (show) try { txCls.getMethod("show", sc).invoke(tx, surface); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
        } catch (Throwable t) {
            logger.warn("SurfaceControl.Transaction failed: " + t.getMessage());
        }
    }

    private static void releaseSurface(Object surface) {
        try {
            resolveSurfaceControlReflection();
            Class<?> sc = surfaceControlReflectionResolved
                    ? surfaceControlClass
                    : Class.forName("android.view.SurfaceControl");
            Class<?> txCls = Class.forName("android.view.SurfaceControl$Transaction");
            Object tx = txCls.getDeclaredConstructor().newInstance();
            try { txCls.getMethod("hide", sc).invoke(tx, surface); } catch (Throwable ignored) {}
            try { txCls.getMethod("reparent", sc, sc).invoke(tx, surface, null); } catch (Throwable ignored) {}
            txCls.getMethod("apply").invoke(tx);
            try { sc.getMethod("release").invoke(surface); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            logger.debug("Surface release failed: " + t.getMessage());
        }
    }

    private static void drawBitmapToSurface(Object surfaceControl, Bitmap bitmap) {
        resolveSurfaceControlReflection();
        if (surfaceControlReflectionFailed) {
            // Permanent resolution failure — nothing to fall back to here; the
            // original code would have thrown on Class.forName too. Log once
            // per call (matches prior behavior) and bail. The renderGifLoop
            // continues; the deterrent visual will be black for this fire.
            logger.warn("drawBitmapToSurface skipped: SurfaceControl reflection unavailable");
            return;
        }
        Surface surface = null;
        try {
            surface = surfaceCtorFromSc.newInstance(surfaceControl);
            Canvas canvas = surface.lockCanvas(null);
            try {
                canvas.drawBitmap(bitmap, 0, 0, null);
            } finally {
                surface.unlockCanvasAndPost(canvas);
            }
        } catch (Throwable t) {
            // Per-call invocation failure (e.g. ctor.newInstance throws for a
            // particular SurfaceControl instance, lockCanvas races a release).
            // Don't mark reflection failed — the lookup itself succeeded.
            logger.warn("drawBitmapToSurface failed: " + t.getMessage());
        } finally {
            if (surface != null) {
                try { surface.release(); } catch (Throwable ignored) {}
            }
        }
    }

    // ── Config readers ─────────────────────────────────────────────────────

    private static String getImagePath() {
        try {
            JSONObject s = UnifiedConfigManager.getSurveillance();
            String p = s.optString("screenDeterrentImagePath", "");
            if (p.isEmpty()) return "";
            File f = new File(p);
            return (f.exists() && f.length() > 0) ? p : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readMessage(String fallback) {
        try {
            JSONObject s = UnifiedConfigManager.getSurveillance();
            String m = s.optString("screenDeterrentMessage", "").trim();
            return m.isEmpty() ? fallback : m;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static boolean isGifFile(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] hdr = new byte[6];
            int n = fis.read(hdr);
            return n >= 6 && hdr[0] == 'G' && hdr[1] == 'I' && hdr[2] == 'F'
                && hdr[3] == '8' && (hdr[4] == '7' || hdr[4] == '9') && hdr[5] == 'a';
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readAllBytes(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
}
