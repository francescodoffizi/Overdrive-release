package com.overdrive.app.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.services.MediaPlaybackService;
import com.overdrive.app.services.PlaybackDuckCoordinator;
import com.overdrive.app.ui.view.ZoomableVideoView;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fullscreen video player for the "Play Video" automation / key-mapping action.
 *
 * <p><b>Why an app-process Activity.</b> The daemon (UID 2000, {@code app_process})
 * cannot render video — a daemon MediaPlayer's {@code prepare()} fails with
 * {@code status=0x80000000} (see {@link com.overdrive.app.byd.AudioPlaybackController}),
 * and the daemon-owned SurfaceControl lane is heavyweight and reserved for the safety
 * overlays. A normal Activity plays in the real app process where MediaPlayer works, and
 * is far more intuitive (a real fullscreen player the user can dismiss) than compositing a
 * video onto a system layer.
 *
 * <p><b>Why {@link ZoomableVideoView}, not {@code VideoView}.</b> On this BYD DiLink
 * Android-10 head unit, {@code VideoView} (which wraps a {@code SurfaceView}) prepared and
 * played but never composited a visible frame — the reported "Play Video shows a blank
 * black screen while the audio plays". The app's own recordings player already proved this
 * and switched to a {@code TextureView}-backed player ({@link ZoomableVideoView}), which
 * routes frames through the view compositor and renders correctly on this unit. Reusing it
 * here fixes the blank video with the same setVideoURI/listener/start surface.
 *
 * <p>Launched by the daemon via {@code am start -n .../VideoPlaybackActivity} with
 * extras: {@code libName} OR {@code filePath}, {@code loop}. Video audio intentionally uses
 * the player's default Media attributes: reassigning a channel can stall video frames on this
 * DiLink build. A library file streams from the daemon's authenticated
 * {@code /api/audio/library/raw} (the app can't read {@code /data/local/tmp}); an external
 * path is opened directly. Tapping the screen, back, or the {@link #ACTION_STOP} broadcast
 * (from Stop Audio) finishes it.
 */
public final class VideoPlaybackActivity extends Activity {

    private static final String TAG = "VideoPlaybackActivity";
    private static final String DAEMON_BASE = "http://127.0.0.1:8080";
    /** Same stop broadcast the audio service + daemon stop() use. */
    public static final String ACTION_STOP = "com.overdrive.app.action.STOP_MEDIA";

    private ZoomableVideoView videoView;
    private boolean stopReceiverRegistered;
    private boolean hasAcceptedPlayback;
    private boolean playbackUiReady;
    private long safetyRequestGeneration;
    private ScheduledExecutorService safetyExecutor;
    private ScheduledFuture<?> safetyMonitor;
    private volatile boolean roadSenseDucked;
    private final PlaybackDuckCoordinator.Target roadSenseDuckTarget = ducked -> {
        roadSenseDucked = ducked;
        runOnUiThread(this::applyRoadSenseDuck);
    };

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Diagnostic: proves whether the activity was actually created (vs the launch being
        // dropped before onCreate — the real question behind "Play Video does nothing").
        Log.i(TAG, "onCreate reached");

        safetyExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread worker = new Thread(r, "VideoDrivingSafety");
            worker.setDaemon(true);
            return worker;
        });
        registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP));
        stopReceiverRegistered = true;
        requestSafeStart(getIntent());
    }

    private void ensurePlaybackUi() {
        if (playbackUiReady) return;
        playbackUiReady = true;
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        videoView = new ZoomableVideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(lp);
        root.addView(videoView);
        setContentView(root);
        try {
            MediaPlaybackService.attachRoadSenseDuckTarget(roadSenseDuckTarget);
        } catch (Throwable t) {
            Log.w(TAG, "RoadSense duck coordinator attach failed: " + t.getMessage());
        }
        applyRoadSenseDuck();

        // Tap anywhere to dismiss (a played video shouldn't trap the user).
        root.setOnClickListener(v -> finish());

        hideSystemUi(root);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        requestSafeStart(intent);
    }

    private void requestSafeStart(Intent intent) {
        final long generation = ++safetyRequestGeneration;
        ScheduledExecutorService executor = safetyExecutor;
        if (executor == null || executor.isShutdown()) {
            finish();
            return;
        }
        try {
            executor.execute(() -> {
                boolean blocked = DrivingSafetyGuard.isActionBlockedViaDaemon(
                        DrivingSafetyGuard.GUARD_SCREEN_MEDIA);
                runOnUiThread(() -> {
                    if (generation != safetyRequestGeneration
                            || isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (blocked) {
                        Log.w(TAG, "blocked fullscreen media at final app-process boundary");
                        finish();
                        return;
                    }
                    ensurePlaybackUi();
                    // Keep the last accepted Intent when a malformed replacement arrives, so a
                    // later Activity recreation cannot resurrect the rejected command.
                    if (startFromIntent(intent, generation)) {
                        setIntent(intent);
                        startSafetyMonitor();
                    }
                });
            });
        } catch (Throwable unavailable) {
            finish();
        }
    }

    /** Stop ongoing fullscreen playback if the guard becomes active while driving. */
    private void startSafetyMonitor() {
        if (safetyMonitor != null) safetyMonitor.cancel(false);
        ScheduledExecutorService executor = safetyExecutor;
        if (executor == null || executor.isShutdown()) {
            finish();
            return;
        }
        try {
            safetyMonitor = executor.scheduleWithFixedDelay(() -> {
                if (!DrivingSafetyGuard.isActionBlockedViaDaemon(
                        DrivingSafetyGuard.GUARD_SCREEN_MEDIA)) {
                    return;
                }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Log.w(TAG, "stopping fullscreen media after driving state changed");
                        finish();
                    }
                });
            }, 1, 1, TimeUnit.SECONDS);
        } catch (RuntimeException unavailable) {
            finish();
        }
    }

    private boolean startFromIntent(Intent intent, long generation) {
        if (intent == null) {
            finish();
            return false;
        }
        StartRequest request = readStartRequest(intent);
        if (request == null) {
            Log.w(TAG, "rejected malformed video start");
            if (!hasAcceptedPlayback) finish();
            return false;
        }
        final boolean loop = request.loop;
        String libName = request.libName;
        String filePath = request.filePath;

        Uri uri;
        if (libName != null && !libName.trim().isEmpty()) {
            // Library file streams from the daemon over LOOPBACK (127.0.0.1); the auth
            // middleware's loopback safety net trusts 127.0.0.1 with no tunnel headers, so
            // no auth cookie is needed (ZoomableVideoView.setVideoURI has no header overload,
            // and the raw endpoint is Range-aware so the MediaPlayer can seek the moov atom).
            uri = Uri.parse(DAEMON_BASE + "/api/audio/library/raw?name=" + Uri.encode(libName));
        } else if (filePath != null && !filePath.trim().isEmpty()) {
            uri = Uri.fromFile(new java.io.File(filePath));
        } else {
            Log.w(TAG, "no libName/filePath — nothing to play");
            if (!hasAcceptedPlayback) finish();
            return false;
        }

        try {
            videoView.setOnPreparedListener(mp -> {
                ScheduledExecutorService executor = safetyExecutor;
                if (executor == null || executor.isShutdown()) {
                    finish();
                    return;
                }
                try {
                    executor.execute(() -> {
                        boolean blocked = DrivingSafetyGuard.isActionBlockedViaDaemon(
                                DrivingSafetyGuard.GUARD_SCREEN_MEDIA);
                        runOnUiThread(() -> {
                            if (generation != safetyRequestGeneration
                                    || isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (blocked) {
                                Log.w(TAG, "blocked fullscreen media before prepared playback");
                                finish();
                                return;
                            }
                            // Do NOT reassign audio attributes here: doing so in PREPARED
                            // stalls video frames on this DiLink stack.
                            try { mp.setLooping(loop); } catch (Throwable ignored) {}
                            videoView.start();
                        });
                    });
                } catch (RuntimeException unavailable) {
                    finish();
                }
            });
            // One-shot finishes when the clip ends; a looping clip never completes.
            videoView.setOnCompletionListener(mp -> { if (!loop) finish(); });
            videoView.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "ZoomableVideoView error what=" + what + " extra=" + extra);
                finish();
                return true;
            });
            Log.i(TAG, "setVideoURI " + uri);
            videoView.setVideoURI(uri);
            hasAcceptedPlayback = true;
            videoView.requestFocus();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setup failed: " + t.getMessage());
            finish();
            return false;
        }
    }

    private static StartRequest readStartRequest(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return new StartRequest(null, null, false);

            boolean hasLibName = extras.containsKey("libName");
            boolean hasFilePath = extras.containsKey("filePath");
            boolean hasLoop = extras.containsKey("loop");
            String libName = parseStringExtra(extras.get("libName"), hasLibName);
            String filePath = parseStringExtra(extras.get("filePath"), hasFilePath);
            Boolean loop = parseBooleanExtra(extras.get("loop"), hasLoop);
            if ((hasLibName && libName == null)
                    || (hasFilePath && filePath == null)
                    || loop == null) {
                return null;
            }
            return new StartRequest(libName, filePath, loop);
        } catch (Throwable t) {
            // This Activity is exported to the shell UID. Reject unparcelable or
            // adversarial extras instead of letting them terminate the app process.
            Log.w(TAG, "could not parse video extras: " + t.getClass().getSimpleName());
            return null;
        }
    }

    static String parseStringExtra(Object raw, boolean present) {
        if (!present) return null;
        return raw instanceof String ? (String) raw : null;
    }

    static Boolean parseBooleanExtra(Object raw, boolean present) {
        if (!present) return Boolean.FALSE;
        return raw instanceof Boolean ? (Boolean) raw : null;
    }

    private static final class StartRequest {
        final String libName;
        final String filePath;
        final boolean loop;

        StartRequest(String libName, String filePath, boolean loop) {
            this.libName = libName;
            this.filePath = filePath;
            this.loop = loop;
        }
    }

    private void hideSystemUi(View v) {
        v.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void applyRoadSenseDuck() {
        ZoomableVideoView target = videoView;
        if (target == null) return;
        target.setPlaybackVolume(roadSenseDucked ? 0.25f : 1.0f);
    }

    @Override protected void onDestroy() {
        safetyRequestGeneration++;
        if (safetyMonitor != null) safetyMonitor.cancel(true);
        if (safetyExecutor != null) safetyExecutor.shutdownNow();
        try {
            MediaPlaybackService.detachRoadSenseDuckTarget(roadSenseDuckTarget);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
        if (stopReceiverRegistered) {
            try { unregisterReceiver(stopReceiver); } catch (Throwable ignored) {}
            stopReceiverRegistered = false;
        }
        try { if (videoView != null) videoView.stopPlayback(); } catch (Throwable ignored) {}
    }
}
