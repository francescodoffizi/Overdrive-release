package com.overdrive.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

/**
 * Touch-capture companion to the daemon-side ScreenDeterrent.
 *
 * Why this exists: while ACC is off, BYD's vendor compositor excludes every
 * Window from HWC composition except its own AccAnimation layer at z=2^30.
 * That means an Activity launched in the app process is INVISIBLE during
 * ACC off — no matter what flags or content it sets. The daemon-side
 * SurfaceControl render at z=Integer.MAX_VALUE is what the user sees.
 *
 * What this Activity owns: input. Even though it's not composited, its
 * Window is the foreground task per WindowManager and its InputChannel sits
 * at the top of the input-dispatch stack. Tap-through-to-launcher is
 * suppressed because the dispatcher delivers events to this Activity first
 * — and we consume them all.
 *
 * Lifetime: launched by `am start` from byd_cam_daemon when motion is
 * confirmed. Polls UnifiedConfigManager every 500ms and finishes when:
 *   - screenDeterrentActiveUntilMs has elapsed, OR
 *   - screenDeterrentForceStop is true (ACC turned on), OR
 *   - screenDeterrentEnabled was toggled off mid-session, OR
 *   - the absolute safety bound (60s) was hit.
 *
 * Single-instance: re-launching while already running is a no-op (or routes
 * through onNewIntent — also a no-op for us). The deadline poll handles
 * sustained motion automatically.
 */
public class DeterrentActivity extends Activity {

    private static final long POLL_INTERVAL_MS = 500;
    /** Hard ceiling — even with a stuck deadline we never display longer. */
    private static final long ABSOLUTE_MAX_MS = 60_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** When THIS instance was created. Fixed for the instance's lifetime; used only for the
     *  startup grace window (see shouldFinishNow), which must not be re-openable. */
    private long createdAtMs = 0;
    /** Anchor for {@link #ABSOLUTE_MAX_MS}. Re-anchored on each daemon re-launch
     *  ({@link #onNewIntent}) so the ceiling means "60s per deterrent" rather than "60s per
     *  Activity instance" — a surviving instance would otherwise carry its elapsed time into
     *  the next fire and self-finish seconds in, dropping touch capture while the daemon's
     *  layer stayed up. Separate from {@link #createdAtMs} on purpose. */
    private long deterrentStartedAtMs = 0;
    private boolean finishing = false;
    /** Tracks consecutive UCM read failures so a corrupted config doesn't
     *  keep us alive for the full 60s ABSOLUTE_MAX_MS. */
    private int consecutiveReadFailures = 0;
    private static final int MAX_READ_FAILURES = 3;
    /** True if we're finishing because daemon told us to (force-stop, deadline
     *  elapsed, surveillance disabled). False if we're finishing for any
     *  other reason (orientation change, system kill, swipe-from-recents).
     *  Drives whether onDestroy signals the daemon to tear down. */
    private boolean orderlyFinish = false;

    private final Runnable deadlinePoll = new Runnable() {
        @Override public void run() {
            if (finishing) return;
            if (shouldFinishNow()) {
                finishCleanly();
                return;
            }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdAtMs = System.currentTimeMillis();
        deterrentStartedAtMs = createdAtMs;

        // Best-effort flags. Most of these are noise during ACC-off because
        // BYD's compositor ignores us anyway, but they cost nothing and help
        // the rare case where ACC flips on mid-deterrent and our Window
        // briefly becomes visible before exitSentryMode finishes us.
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        applyImmersive();

        // Transparent root that blocks all touches. Visual content (image /
        // GIF / default red) is rendered by the daemon's SurfaceControl
        // layer at z=Integer.MAX_VALUE, ABOVE this Window in the
        // SurfaceFlinger stack. We're invisible by design.
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0x00000000);
        root.setOnTouchListener((v, event) -> true);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        mainHandler.postDelayed(deadlinePoll, POLL_INTERVAL_MS);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // Re-launched by the daemon during sustained motion. The deadline poll already keeps us
        // up, so there is nothing to restart — but re-anchor the ABSOLUTE_MAX_MS bound so it
        // means "60s per deterrent", not "60s per Activity instance". Without this a surviving
        // instance carried its elapsed time into the next fire and self-finished seconds in,
        // dropping touch capture while the daemon's layer stayed up (screen covered, taps
        // passing through). This does not weaken the ceiling: it guards a stuck deadline with a
        // dead/wedged daemon, and a dead daemon issues no re-launch, so nothing re-anchors.
        // createdAtMs is deliberately NOT touched — the startup grace window must stay closed
        // (publishGate has already written a real deadline by now). Main thread, same as the
        // poll, so no race.
        deterrentStartedAtMs = System.currentTimeMillis();
        consecutiveReadFailures = 0;
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersive();
    }

    @Override public void onBackPressed() { /* swallow */ }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return true; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return true; }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { return true; }
    @Override public boolean dispatchTouchEvent(MotionEvent ev) { return true; }

    private boolean shouldFinishNow() {
        long now = System.currentTimeMillis();
        if (now - deterrentStartedAtMs > ABSOLUTE_MAX_MS) return true;
        try {
            JSONObject s = UnifiedConfigManager.forceReload().optJSONObject("surveillance");
            consecutiveReadFailures = 0;
            if (s == null) return false;
            if (s.optBoolean("screenDeterrentForceStop", false)) return true;
            if (!s.optBoolean("screenDeterrentEnabled", false)) return true;
            long deadline = s.optLong("screenDeterrentActiveUntilMs", 0L);
            // Grace period: if the gate hasn't been written yet (first 1s
            // after launch the daemon may still be on its publishGate path)
            // hold off the "deadline elapsed" check. Without this, a slow
            // daemon-side fire() would let us self-destruct at +500ms.
            if (deadline == 0 && (now - createdAtMs) < 1500) return false;
            return now >= deadline;
        } catch (Throwable t) {
            // Persistent UCM read failure (e.g. file corrupted, removed,
            // EACCES). Bail after 3 consecutive failures rather than waiting
            // for ABSOLUTE_MAX_MS.
            consecutiveReadFailures++;
            return consecutiveReadFailures >= MAX_READ_FAILURES;
        }
    }

    private void finishCleanly() {
        if (finishing) return;
        finishing = true;
        orderlyFinish = true;
        mainHandler.removeCallbacksAndMessages(null);
        try { finish(); } catch (Throwable ignored) {}
        overridePendingTransition(0, 0);
    }

    /**
     * If we're being destroyed for any reason OTHER than an orderly finish
     * (orientation change recreated us, system killed our task, user swiped
     * from recents) the daemon-side render is still running. Without a
     * signal it would keep the surface up and the panel awake until its
     * deadline elapses. Write screenDeterrentForceStop so the daemon's
     * shouldStop() picks it up on its next 200ms tick.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        finishing = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (!orderlyFinish) {
            // Best-effort signal to the daemon that the activity died
            // unexpectedly (low-mem kill, swipe from recents, etc.) so it
            // tears down its SurfaceControl + backlight rather than holding
            // them for the full deadline. Run on a one-shot background
            // thread because UCM.updateValues does file I/O — per the
            // user-memory rule we never write UCM on the UI thread, even
            // during onDestroy (the looper may be killed mid-write but the
            // process itself is dying anyway, no functional difference).
            new Thread(() -> {
                try {
                    UnifiedConfigManager.updateValues("surveillance",
                        java.util.Collections.singletonMap("screenDeterrentForceStop", true));
                } catch (Throwable ignored) {}
            }, "DeterrentForceStop").start();
        }
    }
}
