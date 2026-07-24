package com.overdrive.app.camera;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuDownscaler;
import com.overdrive.app.surveillance.FoveatedCropper;
import com.overdrive.app.surveillance.GpuMosaicRecorder;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PanoramicCameraGpu - GPU Edition with Zero-Copy Pipeline.
 * 
 * This is the GPU-native version of PanoramicCamera that replaces ImageReader
 * with SurfaceTexture. Camera frames flow directly to GPU texture, enabling:
 * - Zero-copy recording (camera → GPU → encoder)
 * - Minimal AI readback (GPU downscales to 320x240)
 * - <10% total CPU usage
 * 
 * Architecture:
 * - Camera writes to GL_TEXTURE_EXTERNAL_OES via SurfaceTexture
 * - Render loop on dedicated GL thread distributes frames to:
 *   - Recording Lane: GpuMosaicRecorder (zero-copy to encoder)
 *   - AI Lane: GpuDownscaler (2 FPS readback for motion detection)
 */
public class PanoramicCameraGpu {
    private static final String TAG = "PanoramicCameraGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final int PHYSICAL_CAMERA_ID = 1;
    private static final int MAX_CAMERA_ID = 5;     // Probe camera IDs 0-5

    /** Sentry-restart straddle token. The CameraDaemon dilink4 ACC-OFF
     *  handler acquires this BEFORE calling gpuPipeline.stop(), so the
     *  BydApaViewpointHelper observer set never goes empty during the
     *  close+gate+reopen window. Releases automatically on ACC ON or after
     *  the new pipeline registers its own per-instance token (whichever
     *  comes first). Mirrors esco's behaviour where the FlameoutService
     *  keeps no observer of its own but the new C5319i registers its
     *  fresh C5920a into C6498a.observerSet during the reopen, BEFORE the
     *  caller's own MainService-side C5320a was released — so the set
     *  stays non-empty across the whole flow.
     *
     *  We approximate that by acquiring the bridge token explicitly at
     *  ACC-OFF (before stop) and releasing it explicitly after start
     *  completes. Use a static singleton — there's only ever one ACC-OFF
     *  in flight per process. */
    private static final Object SENTRY_BRIDGE_TOKEN = new Object() {
        @Override public String toString() { return "sentry-bridge"; }
    };
    private static volatile boolean sentryBridgeHeld = false;

    /** Called by CameraDaemon at ACC-OFF, BEFORE gpuPipeline.stop().
     *  Idempotent. */
    public static void acquireSentryBridgeViewpoint() {
        if (sentryBridgeHeld) {
            logger.info("sentry-bridge token already held");
            return;
        }
        BydApaViewpointHelper.acquire(SENTRY_BRIDGE_TOKEN);
        sentryBridgeHeld = true;
        logger.info("sentry-bridge viewpoint token acquired (straddling stop+reopen)");
    }

    /** Called by CameraDaemon AFTER gpuPipeline.start() returns successfully
     *  (the new pipeline instance has acquired its own per-instance token).
     *  Also called on ACC ON as a safety net. Idempotent. */
    public static void releaseSentryBridgeViewpoint() {
        if (!sentryBridgeHeld) return;
        BydApaViewpointHelper.release(SENTRY_BRIDGE_TOKEN);
        sentryBridgeHeld = false;
        logger.info("sentry-bridge viewpoint token released");
    }

    // AVMCamera surface mode — 0 works on Seal, Atto 1 may need different value
    // Set via setCameraSurfaceMode() before start() for per-model override.
    // On the esco SurfaceTexture path this same value is the previewIndex
    // passed to addTexture/setTexture/rmTexture — 0=mosaic, 1-4=quadrants.
    private int cameraSurfaceMode = 0;

    // Frame-ingestion path selector. Two modes, persisted in unified
    // config under camera.cameraMode:
    //   "default" → legacy ImageReader + 4-strip → 2x2 rearrangement.
    //   "dilink4" → esco SurfaceTexture (addTexture + setTexture +
    //               previewIndex) + cameraLayout=3 (passthrough). The
    //               HAL emits its final 2x2 mosaic into the producer
    //               surface natively on byd_apa firmware variants.
    //
    // Resolved at construction. Default when key is missing → legacy.
    private final boolean USE_ESCO_SURFACE_TEXTURE_PATH = resolveCameraModeFromConfig();

    private static boolean resolveCameraModeFromConfig() {
        try {
            org.json.JSONObject cam = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cam == null) return false;
            String mode = cam.optString("cameraMode", "default");
            return "dilink4".equalsIgnoreCase(mode);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Effective camera-layout mode for downstream consumers.
     *  0 = 4-strip → 2x2 rearrangement (legacy);
     *  3 = passthrough (dilink4 — HAL emits 2x2 natively). */
    public int getCameraLayoutMode() {
        return USE_ESCO_SURFACE_TEXTURE_PATH ? 3 : 0;
    }
    
    // Camera ID override — set via setCameraId() before start()
    private int cameraIdOverride = -1;  // -1 = use default PHYSICAL_CAMERA_ID
    
    // SOTA: Full-matrix auto-probe — sweeps camera IDs 0-5 × surface modes 0-5
    // to find the first combination that produces panoramic image data.
    private boolean autoProbeCameras = false;
    // When true, skip frame-15/50 validation entirely (user manually set camera ID)
    private boolean skipFrameValidation = false;
    private int probeStartId = -1;  // Tracks where probe started for wrap-around detection
    private int probeNextCameraId = 0;    // Next camera ID to try
    private int probeNextSurfaceMode = 0; // Next surface mode to try
    
    // SOTA: Probe gate — blocks recording/streaming/AI until probe finds a working camera.
    // Without this, the encoder records BLACK frames and the stream shows garbage during probe.
    // Defaults to true (no gate) — only set to false when setAutoProbeCameras(true) is called.
    private volatile boolean probeComplete = true;
    
    // Track the last camera ID that delivered non-black data during probe.
    // If the probe exhausts all IDs without finding a verified strip, fall back
    // to this camera — it's better to record from a real camera than nothing.
    private int lastDataCameraId = -1;
    
    // Callback when auto-probe discovers a working camera config
    public interface CameraProbeCallback {
        void onCameraFound(int cameraId, int surfaceMode);
    }
    private CameraProbeCallback probeCallback;
    
    // Camera dimensions
    private final int width;
    private final int height;
    
    // EGL and OpenGL
    private EGLCore eglCore;
    private android.opengl.EGLSurface dummySurface;  // Pbuffer for headless context
    private int cameraTextureId;
    // CRASH FIX (cross-context use-after-free): the AI-lane GL thread (AiLaneGl,
    // 2nd shared EGL context) samples cameraTextureId for readback/foveated while
    // this render thread can rebind a new EGLImage onto the same id or free the
    // prior gralloc buffer (consumeLatestImageAndBind / consumeSurfaceTextureFrame
    // / releaseCameraConsumer). Sampling a freed/swapped backing buffer mid-readback
    // faults the Adreno driver (SIGSEGV in libGLESv2_adreno). Both sides hold this
    // monitor around their texture access; the AI lane (AiLaneGl.processOnce) also
    // re-checks isCameraTextureValid() inside the lock + issues glFinish() before
    // releasing, so the GPU sample completes before the encoder can recycle the
    // buffer. See AiLaneGl.CameraState.cameraTextureLock()/isCameraTextureValid().
    private final Object cameraTextureLock = new Object();
    // Camera consumer: ImageReader → AHardwareBuffer → EGLImage →
    // cameraTextureId. Bypasses SurfaceFlinger throttling that clamps the
    // SurfaceTexture path to ~8.5 fps on DiLink50 5.0UI builds (verified by
    // AvmImageReaderFpsProbe → 26 fps panoramic). cameraSurface is what we
    // hand to AVMCamera.addPreviewSurface — sourced from ImageReader.getSurface().
    // minSdk=28 enforces Image.getHardwareBuffer availability.
    private ImageReader cameraImageReader;
    private Surface cameraSurface;
    // SurfaceTexture-backed consumer for the esco-style path (addTexture /
    // setTexture / rmTexture). Lifetime mirrors cameraImageReader: created
    // by createCameraSurfaceTexture(), freed by releaseCameraConsumer().
    // Bound directly to cameraTextureId — no separate gralloc handoff.
    private SurfaceTexture cameraSurfaceTexture;

    // Optional direct windshield camera used by the dashcam recording layout.
    // Field verification on Tang: pano camera 2 and windshield camera 0 stream
    // concurrently. This path is opened only when the user selects dashcam +
    // windshield source; if open/bind fails the recorder falls back to the
    // 360-front slice without dropping frames.
    private ImageReader windshieldImageReader;
    private Surface windshieldSurface;
    private Object windshieldCameraObj;
    private int windshieldTextureId;
    private volatile boolean windshieldEnabled = false;
    private volatile int windshieldCameraId = -1;
    private volatile boolean windshieldPending = false;
    private boolean windshieldStarted = false;
    private boolean windshieldOpenFailed = false;
    private boolean windshieldFrameReady = false;
    private Image windshieldBoundImage;
    private HardwareBuffer windshieldBoundHwBuffer;
    private long windshieldFrameCount = 0;
    // GL-thread-confined: wall-clock ms of the most recent successful
    // windshield frame bind. Used by the render loop to detect a STALLED
    // windshield feed — once frames stop arriving (AVMCamera handle
    // contention from pano + OEM-dashcam + windshield all competing, a HAL
    // pause, or a silent open that never delivers), windshieldFrameReady
    // stays latched true and drawFrame would composite the last bound
    // HardwareBuffer forever → the "stuck on a static frame" symptom in the
    // dashcam recording layout. On stall we drop windshieldFrameReady (so the
    // recorder falls back to the live 360 front in the top band — never a
    // frozen image) and schedule one throttled reopen attempt.
    private long windshieldLastFrameMs = 0;
    private long windshieldLastReopenMs = 0;
    // No new frame for this long ⇒ treat the windshield as stalled. Matches
    // the main-camera FRAME_STALL_THRESHOLD_MS (4s) so a transient HAL/IO
    // hiccup doesn't flap the top band between windshield and 360 front.
    private static final long WINDSHIELD_STALL_THRESHOLD_MS = 4000;
    // Don't hammer close+reopen: a single-client HAL that refuses the second
    // client would otherwise spin. One attempt per this interval.
    private static final long WINDSHIELD_REOPEN_MIN_INTERVAL_MS = 10_000;
    // Dedicated handler for ImageReader.OnImageAvailableListener. MUST be
    // separate from glHandler — renderLoop blocks the GL thread on
    // frameSync.wait(), which would starve the listener if it ran on the
    // same looper. The callback hops to glHandler.post for the actual GL
    // bind work via onHalImageAvailable.
    private HandlerThread imageReaderThread;
    private Handler imageReaderHandler;
    
    // Camera object (via reflection).
    // volatile because reopenCamera() runs on the daemon thread and writes
    // cameraObj while the GL render thread reads it in renderLoop(). Without
    // volatile, the GL thread could observe a stale non-null cameraObj after
    // we've torn down the BYD HAL and block in updateTexImage() against a
    // dead BufferQueue (which is what was tripping the GL watchdog on
    // ACC OFF→ON transitions).
    private volatile Object cameraObj;

    /** Per-instance BydApaViewpointHelper observer-set token. Mirrors esco
     *  C5920a's "self" registration into C6498a.observerSet (C5920a.java:323
     *  /:387 — `C6498a.f26622a.m28933k(this)` / `m28930h(this)`). Scoping
     *  the token to this instance lets a separate sentry caller acquire its
     *  own token at ACC-OFF and straddle our close+reopen — keeping the set
     *  non-empty so the helper never writes viewpoint=0 mid-session. */
    private final Object viewpointToken = new Object() {
        @Override public String toString() { return "PanoramicCameraGpu@" + System.identityHashCode(this); }
    };

    // Render loop
    private HandlerThread glThread;
    private Handler glHandler;
    private volatile boolean running = false;
    private final Object frameSync = new Object();
    // State-backed signal between the HAL callback (onHalImageAvailable) and
    // the GL render loop. Plain notify()/wait() races: if the HAL fires while
    // the GL thread is mid-processing (not yet in wait()), the notification
    // is dropped and the GL thread blocks for up to 100 ms before the NEXT
    // HAL fire wakes it — capping effective FPS well below the HAL emission
    // rate. The pending flag closes the race: HAL sets it, GL skips wait()
    // when it's already set, and clears it before processing.
    private volatile boolean imagePending = false;
    
    // Consumers
    private GpuMosaicRecorder recorder;
    private HardwareEventRecorderGpu encoder;  // Direct encoder reference for draining
    // Volatile so the GL render loop's snapshot read at drawFrame's
    // top-of-loop sees stream-disable's null-write atomically. Without
    // volatile, the GL thread can cache a stale ref past the disable
    // and call drawFrame on a released scaler (EGL surface destroyed,
    // program deleted) — undefined behaviour on Adreno.
    private volatile com.overdrive.app.streaming.GpuStreamScaler streamScaler;  // Stream scaler (optional)
    private volatile HardwareEventRecorderGpu streamEncoder;  // Stream encoder (optional)
    // Dedicated blind-spot lane (views 7/8). A SECOND independent scaler+encoder
    // fed from the SAME camera texture each render-loop iteration (read-only
    // fan-out, exactly like the stream lane). Kept fully separate from the stream
    // lane so the blind-spot overlay never contends with / hijacks the live-view
    // stream's view mode, quality, or WS. Null until setBsStreamingComponents().
    private volatile com.overdrive.app.streaming.GpuStreamScaler bsStreamScaler;
    private volatile HardwareEventRecorderGpu bsStreamEncoder;
    private volatile boolean bsLayerVisible = false;
    // BS render diagnostics (throttled): counts PASS-1C drawFrame calls + records why
    // it was skipped, so a "card composites but stays BLACK" report can be triaged from
    // the log (is drawFrame even running? is the texture valid?) instead of guessing.
    private long bsDiagFrames = 0L;
    private long bsDiagLastLogMs = 0L;
    private long bsDiagSkipScaler = 0L;   // skipped: scaler null
    private long bsDiagSkipHidden = 0L;   // skipped: bsLayerVisible false
    private GpuDownscaler downscaler;
    /** Lazy-allocated full-resolution sampler for the camera-mapping dialog.
     *  Lives on the GL handler; allocates GL resources on first use. */
    private HighResPreviewSampler highResSampler;
    private SurveillanceEngineGpu sentry;
    private FoveatedCropper foveatedCropper;  // High-res AI crop from raw strip
    
    // Frame timing
    private int frameCounter = 0;
    // AI lane is fully decoupled from the GL thread (AiLaneWorker). GL thread
    // produces downscaled frames at camera rate; worker consumes at its own
    // pace and drops frames when busy. V2 motion's internal 100ms throttle
    // (MOTION_PROCESS_INTERVAL_MS) keeps actual processing at ~10 fps so
    // there's no need for a separate frame-skip counter on the GL side.
    private com.overdrive.app.camera.AiLaneWorker aiLaneWorker;
    // Tier-1 SOTA fix: dedicated AI-lane GL thread on a shared EGL context.
    // The encoder GL thread now does ONLY consume→draw→swap; the readback
    // and foveated crops live here, on a separate hardware-queue submission
    // path that no longer stalls eglSwapBuffers when YOLO OpenCL is busy.
    // volatile: written on the GL thread (ensure/release), read on the GL
    // thread in renderLoop. volatile is belt-and-suspenders for the rare
    // cross-thread reader (releaseGl runs the shutdown net) — see note there.
    private volatile AiLaneGl aiLaneGl;
    // CameraState captured at start() for the LAZY AI-lane bring-up. The lane
    // is created on the first surveillance-active frame, not at pipeline start.
    private AiLaneGl.CameraState aiCameraStateRef;
    // Single-flight guard so ensure/release toggle cleanly and two near-
    // simultaneous arm frames can't double-start the lane. GL-thread-confined.
    private boolean aiLaneStarting = false;
    // Monotonic per-bound-frame counter. AiLaneGl polls this via the
    // CameraState callback to detect "is there a new frame to read?"; we
    // bump it after every successful HAL bind in consumeLatestImageAndBind.
    private final java.util.concurrent.atomic.AtomicLong cameraFrameSeq =
            new java.util.concurrent.atomic.AtomicLong(0);
    // Last measured camera FPS, computed in the 2-min Stats log. Surfaced
    // via getMeasuredFps() so the UI can show actualFps when it falls below
    // requested (HAL clamp; e.g. user requests 30, HAL emits ~26).
    private volatile float measuredFps = 0f;
    private long lastFrameTime = 0;
    private volatile long lastCameraStartTime = 0;
    // DiLink 4: track last error-restart so we can throttle tight reopen
    // loops when the HAL keeps emitting event=8. esco-parity: 60 s, not 30 s.
    // The AVM HAL daemon (vendor.byd.avm) needs ~60 s after a
    // DAEMON_DIED/SERVER_DIED event to (1) respawn, (2) re-handshake the MCU/
    // ISP rail, (3) re-allocate gralloc pool. Reopens inside that window catch
    // the daemon mid-respawn — accept the open, hand back a buffer, but the
    // calibration isn't done yet, so frames are black/garbage. Esco hardcodes
    // 60_000L in p290le/C7340b.java:595 (m32197w aka tryRestart). Only
    // consulted on USE_ESCO_SURFACE_TEXTURE_PATH; legacy cars unchanged.
    private volatile long lastErrorRestartTime = 0;
    private static final long DILINK4_ERROR_RESTART_MIN_INTERVAL_MS = 60_000L;
    private long startTime = 0;
    
    // Watchdog for GL thread hang detection
    private volatile long lastGlThreadHeartbeat = 0;
    private Thread watchdogThread;
    private static final long GL_THREAD_TIMEOUT_MS = 3000;
    // Extended timeout for initial camera warmup — the BYD panoramic camera HAL
    // can take several seconds to deliver the first frame. During this period the
    // GL thread is legitimately blocked on frameSync.wait(), not deadlocked.
    private static final long GL_THREAD_WARMUP_TIMEOUT_MS = 10000;
    private volatile boolean firstFrameReceived = false;
    
    // SOTA: BYD camera coordinator for cooperative sharing and error recovery
    private BydCameraCoordinator cameraCoordinator;
    private volatile boolean cameraYielded = false;

    // Yield-state re-acquire poller. registerCameraUser() is permanently disabled
    // (see BydCameraCoordinator), so once we yield to the native AVM app the
    // event-driven IBYDCameraUser.onCameraAvailable callback never fires. The
    // GL render loop also early-returns at line 2131 while yielded, so its
    // frame-stall watchdog can't observe a recovery either. This poller is the
    // only authoritative re-acquire path: every 5 s while yielded, ping
    // BydCameraCoordinator.checkNativeAppActive() — its polling-fallback branch
    // (lines 398-406) calls handleNativeAppClosed → onReacquireCamera when the
    // native app releases the camera.
    private volatile Thread yieldPollerThread;
    private static final long YIELD_POLL_INTERVAL_MS = 5000;

    // audit avc-yield (round 2): when onReacquireCamera's GL-handler runnable
    // throws (AVMCamera.open transient false, NoSuchMethodError on a reflection
    // target, attachSurfaceTextureToCamera failing because the BYD HAL is
    // still finalising the prior native release, etc.) the previous recovery
    // depended solely on startYieldPoller observing a *future* native-app
    // transition — but coordinator.yielded was already cleared on the first
    // transition, so checkNativeAppActive's edge-only handleNativeAppClosed
    // never re-fires, the poller exits, and pano recording stays dead until
    // ACC cycle. Schedule explicit backoff retries of startCamera at 2s/5s/
    // 10s (then give up and self-restart the daemon process the way the GL
    // watchdog does at line 2400/2767).
    private static final long[] REACQUIRE_RETRY_DELAYS_MS = new long[] { 2000L, 5000L, 10000L };
    // audit avc-yield (round 5, finding cross-thread-race): reacquireRetryCount
    // is read+incremented from the GL-handler retry catch block AND reset from
    // onReacquireCamera (HAL listener thread). A naive int can lose an
    // increment if the listener fires reset between the read and the +1 write.
    // AtomicInteger.compareAndSet(currentAttempt, currentAttempt+1) makes the
    // increment fail loud (we treat it as concurrent-reset and skip the retry
    // bump rather than overwriting a fresh 0).
    private final AtomicInteger reacquireRetryCount = new AtomicInteger(0);
    // audit avc-yield (round 7, finding pending-retry-not-cancelled): when
    // a yield-cycle attempt fails we postDelayed a retry runnable on glHandler.
    // If the yield poller's edge-fire path (onReacquireCamera) succeeds before
    // the postDelayed fires, the retry will still run and unconditionally
    // tear down the just-resumed camera (attemptReacquireOnGlThread always
    // closes cameraObj on entry). Track the pending runnable so we can
    // removeCallbacks on success or on a fresh poller-driven re-entry.
    // Also bump pendingReacquireEpoch on success/fresh-cycle so that even if
    // the cancellation race loses, the epoch-gate at the top of
    // attemptReacquireOnGlThread short-circuits the stale runnable.
    private volatile Runnable pendingReacquireRetry = null;
    private final AtomicInteger pendingReacquireEpoch = new AtomicInteger(0);
    // audit avc-yield (round 5, finding consumer-recreate-every-attempt):
    // recreateCameraSurface is only needed once per yield cycle (after the BYD
    // HAL released the ImageReader Surface). Doing it on every retry attempt
    // re-allocates GL textures for nothing and risks racing the encoder. Set
    // by yieldCameraInternal, cleared after the first successful recreate.
    private volatile boolean consumerNeedsRecreation = false;


    // Camera health monitor — detects stalled frames and triggers recovery
    private static final long FRAME_STALL_THRESHOLD_MS = 4000;  // 4 seconds without frames (HAL issue)
    // Post-(re)open grace window. The BYD panoramic AVM HAL is documented (see
    // the GL watchdog comment) to take ~5-8s to deliver the first frame after a
    // camera open. Measured from lastCameraStartTime, the stall watchdog must not
    // declare a frame stall inside this window — otherwise the 4s threshold trips
    // before frame 1 can arrive and the camera is torn down and reopened in a loop
    // that never escapes warmup (root cause of the sentry->drive recording blackout
    // when ACC turns on while surveillance is still armed). 9s covers the worst-
    // case 8s first-frame latency with margin while staying under the 10s GL-hang
    // warmup timeout that bounds genuine deadlocks.
    private static final long FRAME_STALL_WARMUP_GRACE_MS = 9000;
    // When native app is active, use a longer threshold to avoid false yields
    // from transient CPU/IO load. The HAL needs time to settle into sharing mode.
    private static final long FRAME_STALL_CONTENTION_THRESHOLD_MS = 3000;
    // Require consecutive stalls before yielding — a single stall could be transient
    private static final int CONTENTION_STALL_COUNT_TO_YIELD = 2;
    private volatile int consecutiveContentionStalls = 0;

    // Escalation: count consecutive bare-reopen restarts that delivered ZERO
    // frames. A bare close/reopen cannot recover a wedged AVM HAL co-consumer
    // state (the sentry->drive blackout: 14 reopens, 0 frames, 2 min lost).
    // Only a full teardown + com.byd.avc warmup recovers it. After this many
    // back-to-back zero-frame reopens, escalate to the listener's warmup-routed
    // restart instead of looping bare reopens forever. Incremented in
    // restartCameraAfterError when the prior open never produced a frame;
    // reset to 0 the moment a real frame arrives.
    private static final int FRAME_STALL_RESTART_ESCALATE_THRESHOLD = 3;
    private volatile int consecutiveZeroFrameRestarts = 0;
    // Snapshot of frameCounter at the start of the current open. If frameCounter
    // hasn't advanced past this by the next restart, that open delivered nothing.
    private volatile long frameCounterAtOpen = 0;
    // Set true while an escalation is in flight so the watchdog stops posting
    // bare restartCameraAfterError() until the warmup-routed recovery completes
    // (and resets it via notePipelineRestarted()).
    private volatile boolean halRecoveryEscalated = false;
    
    // DEAD-SLOT ESCAPE (issue #170). Every self-healing path above needs proof
    // that the camera produced at least one frame: the frame-15/50 revalidation
    // is driven by frameCounter, and the frame-stall monitor is gated on
    // `lastFrameTime > 0`. A slot that opens but can NEVER stream therefore arms
    // none of them and the pipeline waits forever — Diagnostics stays on
    // "Probing…", frameCount stays 0, nothing is ever recorded.
    //
    // That is exactly what BYD's 2602-generation firmware does on legacy pano_h
    // boards: AVMCamera.open(1) succeeds, but the HAL reports a 0x0 preview so
    // addPreviewSurface fails (err 423) and startPreview fails (err 279) — both
    // inside the HAL, neither raising a Java exception we could catch at open.
    //
    // So: if NO frame has arrived within this window of the camera opening, the
    // slot is presumed dead and we walk PanoCameraFallbackOrder. The window must
    // clear the documented 5-8s BYD first-frame latency and the 9s stall grace
    // with room to spare — a false trigger costs a HAL close/open cycle, so we
    // are deliberately patient. This only ever fires for a camera that has not
    // produced a single frame; once one arrives, the existing machinery owns
    // recovery and this path is permanently disarmed for the session.
    private static final long FIRST_FRAME_DEAD_SLOT_MS = 25000;
    // Camera IDs opened this session, so the walk never retries a dead slot.
    // CopyOnWriteArraySet, not a synchronized wrapper: startCamera adds from the
    // camera-open worker thread that restartCameraAfterError spawns, while the
    // GL thread reads it (and concatenates it into log lines). COW gives both
    // contains() and toString() snapshot semantics with no lock and no risk of
    // ConcurrentModificationException. Writes are rare and the set holds at most
    // a couple of entries, so the copy cost is irrelevant.
    private final java.util.Set<Integer> deadSlotTriedCameraIds =
        new java.util.concurrent.CopyOnWriteArraySet<>();
    // Set when a dead-slot fallback switched IDs; the first frame that arrives
    // afterwards persists the winning ID so the next boot goes straight to it.
    private volatile boolean deadSlotFallbackPendingPersist = false;
    // Latched once the candidate list is exhausted so we log once, not per tick.
    private volatile boolean deadSlotWalkExhausted = false;
    // BmmCameraInfo panoramic tag mapping, resolved lazily on first use so the
    // reflection + logging cost is paid only on boards that actually wedge.
    // MIN_VALUE = not yet resolved; -1 = resolved, API absent (DiLink 3.0).
    private volatile int halPanoCameraIdHint = Integer.MIN_VALUE;

    // Flag to indicate camera restart is in progress — watchdog uses extended timeout.
    // P1 #11: AtomicBoolean so concurrent restartCameraAfterError + reopenCamera
    // calls can't both enter the restart path. Loser observes
    // compareAndSet(false,true)==false and returns; only the winner runs the
    // close/open sequence and is responsible for clearing the flag.
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    
    // SOTA: Pre-yield listener — pipeline registers this to finalize recordings before yield
    public interface CameraYieldListener {
        /** Called BEFORE camera is yielded. Finalize any active recording to prevent corruption. */
        void onPreYield();
        /** Called AFTER camera is re-acquired. Resume recording if needed. */
        void onPostReacquire();
        /**
         * Called when bare close/reopen restarts have repeatedly failed to
         * revive frame delivery (FRAME_STALL_RESTART_ESCALATE_THRESHOLD
         * consecutive reopens with zero frames). A bare reopen cannot recover
         * a wedged AVM HAL co-consumer state — only a full pipeline teardown +
         * com.byd.avc warmup can (observed empirically: the only thing that
         * ever broke the sentry->drive reopen-loop blackout). The listener
         * should route through its warmup-capable restart path
         * (RecordingModeManager.activateModeWithWarmup-equivalent). Default
         * no-op so existing listeners stay source-compatible.
         */
        default void onHalRecoveryNeeded() {}
    }
    private CameraYieldListener yieldListener;
    
    // CPU usage monitoring
    private long lastCpuCheckTime = 0;
    private static final long CPU_CHECK_INTERVAL_MS = 10000;  // Every 10 seconds
    
    // Stats logging (time-based, not frame-based)
    private long lastStatsTime = 0;
    private int lastStatsFrameCount = 0;
    private static final long STATS_INTERVAL_MS = 120000;  // Every 2 minutes

    // Per-stage timing diagnostic. Tracks the WORST frame in a 30 s window
    // and logs a single line per window so the contribution of each stage
    // (acquire / mosaicDraw / aiReadback / aiSubmit / swap) is visible
    // without log spam. Used to verify that readback-skip + drainer keep
    // each stage under budget.
    private static final long STAGE_TIMING_LOG_INTERVAL_MS = 30000;
    private long stageTimingWindowStartMs = 0;
    private long stageWorstTotalNs = 0;
    private long stageWorstAcquireNs = 0;
    private long stageWorstMosaicNs = 0;
    private int stageWindowFrames = 0;

    // AI readback throttle — frame-counter modulo, NOT wall-clock.
    // Wall-clock throttling is fragile when readback duration approaches the
    // interval: the GL thread spends ~117ms per frame (mosaic+swap+readback),
    // which guarantees `now - lastReadback >= 95ms` on every loop, so 100% of
    // frames trigger readback and the pipeline collapses to ~8 fps.
    // Frame-modulo couples AI rate directly to HAL emission rate. With HAL
    // emitting at ~26 fps (ImageReader path), every 3rd frame is ~8.6 AI fps,
    // matching V2 motion's 10 fps internal cadence. If HAL rate changes, AI
    // rate scales proportionally and the GL thread budget stays balanced.
    private static final int AI_READBACK_FRAME_MODULO = 3;

    private int targetFps = 15;  // Desired frame rate for camera

    // Cached AI-lane readback pacing (parked-idle throttle). The AiLaneGl is
    // created/torn down lazily on the GL thread and is null while disarmed, so a
    // control-thread setter caches the value here and re-applies it at bring-up
    // (ensureAiLaneStarted) — mirroring how sentry.setCameraTargetFps is
    // re-asserted. Default 0 = disabled ⇒ AiLaneGl uses its compiled modulo=3,
    // byte-identical to today.
    private volatile long aiReadbackMinIntervalMs = 0L;

    // Recorder draw stride. The render loop draws into the RECORDING encoder's
    // input surface (PASS 1A) only on every Nth camera frame; stream (PASS 1B)
    // and blind-spot (PASS 1C) are unaffected (separate encoders, drawn every
    // frame). MediaCodec encodes exactly the frames rendered into its Surface,
    // so a stride of N yields an effective ~cameraFps/N recording rate without
    // touching KEY_FRAME_RATE (which Android can't change at runtime). Used by
    // Proximity Guard to keep a low-rate, low-bitrate pre-record ring while
    // MONITORING and snap to full rate the instant a trigger fires.
    //
    // 1 = draw every frame (default; ZERO behaviour change for every other
    // mode). Always >= 1. Volatile: written by the proximity controller's
    // state thread (via pipeline), read by the GL render thread — same
    // single-writer/single-reader visibility pattern as bsLayerVisible.
    private volatile int recorderFrameStride = 1;
    // Master on/off for PASS 1A (the H.265 recorder mosaic). true = normal
    // (default; ZERO behaviour change for every recording mode). false = skip
    // the recorder drawFrame + drainEncoder ENTIRELY this frame — used when the
    // camera is kept warm ONLY for blind-spot (PASS 1C, no encoder): there is no
    // recording mode and no pre-record ring to feed, so running the encoder is
    // pure wasted Venus/GPU. Distinct from recorderFrameStride (which sub-samples
    // the lane); this gates it off completely. Stream (1B) + BS (1C) unaffected.
    // Volatile: written by RecordingModeManager's lifecycle thread (via pipeline),
    // read by the GL render thread — same single-writer/single-reader pattern as
    // bsLayerVisible / recorderFrameStride.
    private volatile boolean recorderLaneEnabled = true;
    // Counter that advances every consumed camera frame and selects which
    // frames clear the stride gate (drawn when counter % stride == 0). The GL
    // render thread increments it; setRecorderFrameStride resets it to 0 from
    // the control thread so a stride change starts on a drawn frame. Volatile
    // so that cross-thread reset is atomic (no 32-bit long tearing) and visible
    // — the GL increment racing a reset can at worst drop one increment, which
    // is benign for a phase counter read as `% stride`.
    private volatile long recorderStrideCounter = 0;

    private final float[] quadrantStripOffsetX;
    private final float[] quadrantCornerOffsetsXY;

    // One-shot mismatch warning for HAL-emitted dims vs. configured strip.
    // The HAL silently delivers whatever it wants; we want to know if Tang
    // returns 720 against a Seal-configured 960 ImageReader (mosaic geometry
    // would be wrong). volatile because the GL thread sets it on first frame.
    private volatile boolean emittedDimsLogged = false;

    // Sticky flag for the SurfaceTexture path: true once SurfaceTexture has
    // signalled at least one onFrameAvailable. Drives the renderLoop bind
    // instead of imagePending (which is for the ImageReader path).
    private volatile boolean stFramePending = false;

    // One-shot first-frame transform-matrix dump on the SurfaceTexture path.
    // Cleared by attachSurfaceTextureToCamera so we re-emit on every
    // (re)attach, not just the cold-start session. The transform matrix
    // tells us the HAL's actual framing — diagonal sx/sy give us "what
    // fraction of the surface holds real pixels", which exposes 5120x960
    // strip-vs-2x2-mosaic and similar. Cheap (single 16-float read).
    private boolean firstFrameDimsLogged = false;

    // Per-frame SurfaceTexture transform matrix, captured from
    // SurfaceTexture.getTransformMatrix() inside consumeSurfaceTextureFrame
    // and forwarded to GpuMosaicRecorder + GpuStreamScaler via
    // setTextureMatrix. esco-parity: same matrix esco's pipeline applies as
    // uTexMatrix in the vertex shader. Only used when the esco SurfaceTexture
    // path is active; legacy ImageReader path leaves it at identity.
    private final float[] currentTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };

    /**
     * Creates a GPU-based panoramic camera.
     *
     * @param width Camera width (typically 5120)
     * @param height Camera height (typically 960 on Seal, 720 on Tang)
     */
    public PanoramicCameraGpu(int width, int height) {
        this(width, height, null);
    }

    /**
     * @param quadrantStripOffsetX Per-quadrant strip-X offsets. Null = legacy
     *     Seal default. Forwarded to the foveated cropper so its
     *     {@code centerX} math picks the slice the user mapped to each role.
     */
    public PanoramicCameraGpu(int width, int height, float[] quadrantStripOffsetX) {
        this(width, height, quadrantStripOffsetX, null);
    }

    /**
     * @param quadrantStripOffsetX Per-role X offsets for legacy 4-strip HAL.
     *     {Front, Right, Rear, Left}. Null → legacy default.
     * @param quadrantCornerOffsetsXY Per-role (cornerX, cornerY) for the
     *     0.5×0.5 corner in a 2x2-native HAL frame. {fX,fY, rX,rY, bX,bY,
     *     lX,lY}. Null → mirrors recorder's default (Front=TL, Right=TR,
     *     Rear=BL, Left=BR). Used when DiLink 4 mode is active.
     */
    public PanoramicCameraGpu(int width, int height,
                              float[] quadrantStripOffsetX,
                              float[] quadrantCornerOffsetsXY) {
        this.width = width;
        this.height = height;
        this.quadrantStripOffsetX = (quadrantStripOffsetX != null && quadrantStripOffsetX.length == 4)
            ? quadrantStripOffsetX.clone()
            : null;
        this.quadrantCornerOffsetsXY =
            (quadrantCornerOffsetsXY != null && quadrantCornerOffsetsXY.length == 8)
                ? quadrantCornerOffsetsXY.clone()
                : null;
    }
    
    /**
     * Sets the consumers for the camera frames.
     * 
     * @param recorder GPU mosaic recorder for zero-copy recording
     * @param downscaler GPU downscaler for AI lane
     * @param sentry Surveillance engine for motion detection
     */
    public void setConsumers(GpuMosaicRecorder recorder, GpuDownscaler downscaler,
                            SurveillanceEngineGpu sentry) {
        this.recorder = recorder;
        this.downscaler = downscaler;
        this.sentry = sentry;

        // Build the AI lane worker once consumers are wired. Recycler points
        // back to the downscaler's buffer pool so dropped frames are returned
        // immediately (no leak under sustained submit-while-busy).
        if (this.aiLaneWorker == null) {
            this.aiLaneWorker = new com.overdrive.app.camera.AiLaneWorker(frame -> {
                GpuDownscaler ds = this.downscaler;
                if (ds != null && frame != null) {
                    try {
                        ds.recycleBuffer(frame);
                    } catch (Throwable ignored) {}
                }
            });
        }
        this.aiLaneWorker.setSentry(sentry);
        // Sentry's foveated crops now run on the AiLaneGl thread, so we
        // don't hand it the encoder GL handler any more — that path posted
        // crops back to the encoder thread and competed with the render
        // loop for handler slots. With AiLaneGl, the crop runs inline on
        // its own GL context.
        if (sentry != null) {
            sentry.setCameraTargetFps(targetFps);
        }
    }
    
    /**
     * Starts the GPU camera pipeline.
     * 
     * @throws Exception if initialization fails
     */
    public void start() throws Exception {
        logger.info( "Starting GPU camera pipeline...");
        // Surface the resolved ingestion mode in every camera open log so
        // field debugging can correlate "what does the recording look like"
        // with "which path the daemon took".
        logger.info("Camera ingestion mode: "
            + (USE_ESCO_SURFACE_TEXTURE_PATH ? "DiLink 4 (SurfaceTexture passthrough)"
                                              : "Default (ImageReader + 2x2 rearrangement)"));
        startTime = System.currentTimeMillis();
        
        // SOTA: Initialize BYD camera coordinator for cooperative sharing
        if (cameraCoordinator == null) {
            cameraCoordinator = new BydCameraCoordinator();
            cameraCoordinator.setYieldCallback(new BydCameraCoordinator.CameraYieldCallback() {
                @Override
                public void onYieldCamera() {
                    // Contention detected — yield on GL thread
                    logger.info("YIELD: Contention detected — releasing camera for native app");
                    cameraYielded = true;
                    // audit avc-yield (round 8, finding yield-mid-backoff-cascades-to-exit):
                    // a Yield #2 arriving mid-backoff for a failed Yield #1 reacquire
                    // must reset the retry budget and cancel the pending postDelayed
                    // retry. Otherwise, the stale retry runnable fires after the new
                    // yield, hits the isCameraYielded gate, throws "treating as
                    // reacquire failure", bumps the counter further, and at
                    // attemptIdx=3 cascades to System.exit(0) — converting a
                    // legitimate native-app re-engagement into a daemon kill.
                    // Symmetric with onReacquireCamera at line 543/552.
                    reacquireRetryCount.set(0);
                    pendingReacquireEpoch.incrementAndGet();
                    Runnable staleRetry = pendingReacquireRetry;
                    pendingReacquireRetry = null;
                    if (staleRetry != null && glHandler != null) {
                        try {
                            glHandler.removeCallbacks(staleRetry);
                            logger.info("Yield: cancelled pending postDelayed "
                                + "reacquire retry (fresh yield supersedes prior "
                                + "failed-reacquire backoff)");
                        } catch (Throwable th) {
                            logger.warn("Yield: removeCallbacks errored: "
                                + th.getMessage());
                        }
                    }
                    if (glHandler != null) {
                        glHandler.post(() -> yieldCameraInternal());
                    }
                }

                @Override
                public void onReacquireCamera() {
                    // Native app released camera after contention yield — re-acquire
                    logger.info("REACQUIRE: Native app released camera — reopening");
                    cameraYielded = false;
                    stopYieldPoller();
                    // audit avc-yield (round 2): reset retry counter at the
                    // start of every fresh re-acquire — a successful prior
                    // cycle should not poison the next yield.
                    reacquireRetryCount.set(0);
                    // audit avc-yield (round 7, finding pending-retry-not-cancelled):
                    // the previous attempt may have scheduled a postDelayed retry
                    // runnable. If we post a fresh immediate attempt now without
                    // cancelling it, the stale retry will fire ~2s later and tear
                    // down the just-resumed camera (extra MP4 split + ~1-3s
                    // recording gap). Cancel + bump epoch so any in-flight
                    // runnable that survives the removeCallbacks race short-
                    // circuits at the epoch-gate.
                    pendingReacquireEpoch.incrementAndGet();
                    Runnable stale = pendingReacquireRetry;
                    pendingReacquireRetry = null;
                    if (stale != null && glHandler != null) {
                        try {
                            glHandler.removeCallbacks(stale);
                            logger.info("Reacquire (poller path): cancelled "
                                + "pending postDelayed retry before fresh attempt");
                        } catch (Throwable th) {
                            logger.warn("Reacquire: removeCallbacks errored: "
                                + th.getMessage());
                        }
                    }
                    if (glHandler != null) {
                        glHandler.post(() -> attemptReacquireOnGlThread());
                    }
                }

                @Override
                public void onCameraError(int eventType) {
                    // Camera HAL error — but only restart if frames have actually stopped.
                    // On DiLink5.0, event 8 fires immediately after camera open (after event 1004)
                    // as a benign HAL lifecycle notification. Restarting on it causes an infinite loop.
                    // Guard: ignore error events within 3 seconds of camera start — the HAL is still
                    // settling. If it's a real error, the frame stall watchdog will catch it.
                    long timeSinceStart = System.currentTimeMillis() - lastCameraStartTime;
                    if (timeSinceStart < 3000) {
                        logger.warn("CAMERA ERROR: event=" + eventType + " — IGNORED (camera started " +
                            timeSinceStart + "ms ago, waiting for frame stall watchdog)");
                        return;
                    }
                    // DiLink 4 backoff: byd_apa firmware can emit event=8
                    // every 10 s when the AVMCamera preview surface is being
                    // torn down by another consumer (com.byd.avc, backlight
                    // sleep, etc.). Restarting that fast just churns the
                    // CAN bus and battery without ever stabilising. Skip
                    // restarts that fire within 60 s of the last error
                    // (esco-parity, p290le/C7340b.java:595); the frame-stall
                    // watchdog will catch a genuine permanent failure later.
                    // Legacy fleet (USE_ESCO_SURFACE_TEXTURE_PATH == false)
                    // keeps the prior immediate-restart behaviour.
                    if (USE_ESCO_SURFACE_TEXTURE_PATH) {
                        // ESCO-PARITY: dilink4 does NOT restart on HAL
                        // onCameraError. Field log shows the BYD HAL emits
                        // event=8 spuriously during normal parked operation
                        // — restarting puts the producer surface in
                        // red-banner mode (close+reopen race on byd_apa).
                        // esco's PanoCameraRecord retries via the recorder
                        // listener path with a 5×15s backoff, NOT a camera
                        // close+reopen. Skip the restart entirely on
                        // dilink4 — log the event for observability.
                        long now = System.currentTimeMillis();
                        long timeSinceLastError = now - lastErrorRestartTime;
                        lastErrorRestartTime = now;
                        logger.warn("CAMERA ERROR: event=" + eventType
                            + " — IGNORED on dilink4 (esco-parity, "
                            + (timeSinceLastError == now ? "first" : timeSinceLastError + "ms since last")
                            + ")");
                        return;
                    }
                    logger.error("CAMERA ERROR: event=" + eventType + " — restarting camera");
                    if (glHandler != null) {
                        glHandler.post(() -> restartCameraAfterError());
                    }
                }
            });
            // Esco-parity: skip IBYDCameraService binder registration on
            // dilink4 (byd_apa). Esco never touches the bydcameramanager
            // service — it opens AVMCamera directly. The arbitration
            // protocol may require an IBYDCameraUser.onYield ack we never
            // send; the HAL can stay in "waiting for user-ack" state and
            // refuse to stream frames. See audit Top-5 #5.
            if (!USE_ESCO_SURFACE_TEXTURE_PATH) {
                cameraCoordinator.register();
            } else {
                logger.info("dilink4: skipping IBYDCameraService registration (esco-parity)");
            }
        }
        
        // Start GL thread
        glThread = new HandlerThread("GL-RenderLoop");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        // Tier 1 wiring: the AI-lane GL thread needs the camera frame seq
        // and texture id, both of which live on this instance. Implement
        // CameraState here.
        final AiLaneGl.CameraState aiCameraState = new AiLaneGl.CameraState() {
            @Override public int getCameraTextureId() { return cameraTextureId; }
            @Override public long getFrameSeq()      { return cameraFrameSeq.get(); }
            // Crash-fix: the AI lane must NOT sample the camera texture while the
            // camera is yielded/closed/restarting (its backing EGLImage is being
            // freed/swapped). cameraYielded / cameraObj==null / restartInProgress
            // are all volatile/atomic — safe to read cross-thread.
            @Override public boolean isCameraTextureValid() {
                return !(cameraYielded || cameraObj == null || restartInProgress.get());
            }
            @Override public Object cameraTextureLock() { return cameraTextureLock; }
        };

        if (sentry != null) {
            sentry.setCameraTargetFps(targetFps);
        }

        // Initialize on GL thread
        glHandler.post(() -> {
            try {
                initializeGl();
                startCamera();

                // SOTA: Setup event callback for HAL error detection (-10086, 8)
                if (cameraCoordinator != null && cameraObj != null) {
                    cameraCoordinator.setupEventCallback(cameraObj);
                }

                // Tier 1: the AI-lane GL thread + its second EGL context +
                // FoveatedCropper FBO/PBO ring (~6.5MB GPU + ~2.8MB CPU) are
                // brought up LAZILY on the first surveillance-active frame
                // (see ensureAiLaneStarted in renderLoop), NOT eagerly here.
                // Both AI consumers are surveillance-only, so in every ACC-ON
                // recording mode where sentry never activates (CONTINUOUS /
                // DRIVE_MODE / PROXIMITY_GUARD) the lane + its memory + its
                // idle thread/context never exist. It is created when sentry
                // arms and torn back down when sentry disarms. The CameraState
                // (aiCameraState) is captured into a field for the lazy path.
                this.aiCameraStateRef = aiCameraState;

                running = true;

                // Start render loop
                glHandler.post(this::renderLoop);

                // Start watchdog
                startWatchdog();

                logger.info("GPU camera pipeline started (AI lane on dedicated GL thread)");
            } catch (Exception e) {
                logger.error("Failed to start GPU pipeline", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Bring up the AI lane (AiLaneGl thread + shared EGL context + FoveatedCropper
     * FBO/PBO ring) lazily on the first surveillance-active frame. MUST run on the
     * GL thread with eglCore current (it is — called from renderLoop) so the
     * share-group context create + cropper GL alloc happen against a live parent
     * context. Single-flight via aiLaneStarting + the aiLaneGl null check so it's
     * a cheap no-op once up. Mirrors the old eager block at start() verbatim.
     */
    private void ensureAiLaneStarted() {
        if (aiLaneGl != null || aiLaneStarting) return;
        if (eglCore == null || aiCameraStateRef == null) return;  // pipeline not fully up yet
        aiLaneStarting = true;
        try {
            // This block can stall the GL render thread for up to ~start()'s 3s
            // latch + 1.5s cropper.init() in the degraded-driver tail. The GL
            // watchdog (GL_THREAD_TIMEOUT_MS=3000) would otherwise System.exit
            // the recording process mid-bring-up. Refresh the heartbeat right
            // before AND after so a legitimate one-time lane warmup can never be
            // mistaken for a wedged GL thread. (A genuinely hung warmup is still
            // bounded by start()'s/runOnGlThreadBlocking's own internal timeouts.)
            lastGlThreadHeartbeat = System.currentTimeMillis();
            AiLaneGl lane = new AiLaneGl(eglCore, aiCameraStateRef);
            lane.start();  // blocks until the shared context is current on its thread
            lastGlThreadHeartbeat = System.currentTimeMillis();
            lane.setConsumers(downscaler, foveatedCropper, sentry, aiLaneWorker);
            boolean cropperReady = lane.runOnGlThreadBlocking(() -> {
                if (foveatedCropper != null) foveatedCropper.init();
            }, 1500);
            lastGlThreadHeartbeat = System.currentTimeMillis();
            if (!cropperReady) {
                logger.warn("Lazy AI-lane: FoveatedCropper init did not complete in 1.5s");
            }
            // Publish the lane FIRST, then apply the cached idle-throttle readback
            // pacing by RE-READING the volatile cache. Ordering matters: a
            // control-thread setReadbackMinIntervalMs that races bring-up reads
            // aiLaneGl and forwards to the live lane only if non-null, so once we
            // publish here any subsequent setter reaches the lane directly; and by
            // re-reading the cache AFTER publishing we also pick up a write that
            // landed during the (blocking) bring-up above. If we instead applied
            // the cache before publishing, a setter interleaving between the apply
            // and the publish would see aiLaneGl==null, update only the cache, and
            // leave the just-published lane on the stale value. 0 = disabled (lane
            // keeps its modulo default).
            aiLaneGl = lane;
            lane.setReadbackMinIntervalMs(aiReadbackMinIntervalMs);
            logger.info("AI lane started lazily (surveillance armed)");
        } catch (Throwable t) {
            logger.warn("Lazy AI-lane start failed: " + t.getMessage());
            // Leave aiLaneGl null so the next active frame retries.
        } finally {
            aiLaneStarting = false;
        }
    }

    /**
     * Tear the AI lane back down when surveillance disarms, freeing the thread,
     * the shared EGL context, and the cropper's ~6.5MB GPU + ~2.8MB CPU buffers.
     * MUST run on the GL thread (called from renderLoop). aiLaneGl.shutdown()
     * releases the cropper + downscaler direct resources on the AI-lane context,
     * so afterwards we null sentry's cropper ref so AiLaneGl.processOnce re-attaches
     * a freshly-re-init'd cropper on the NEXT arm (the cropper OBJECT is reused;
     * only its GL state was released, and ensureAiLaneStarted re-init()s it).
     */
    private void releaseAiLaneOnGlThread() {
        AiLaneGl lane = aiLaneGl;
        if (lane == null) return;
        aiLaneGl = null;
        try { lane.shutdown(); } catch (Throwable ignored) {}
        // Clear the stale cropper ref the sentry captured so the lazy re-attach
        // (AiLaneGl.processOnce: getFoveatedCropper()==null -> setFoveatedCropper)
        // re-fires on the next arm instead of holding a released-GL-state cropper.
        try {
            SurveillanceEngineGpu s = sentry;
            if (s != null) s.setFoveatedCropper(null, cameraTextureId);
        } catch (Throwable ignored) {}
        logger.info("AI lane released (surveillance disarmed) — freed thread + EGL context + cropper buffers");
    }

    /**
     * Initializes OpenGL context and textures.
     */
    private void initializeGl() {
        // Create EGL context
        eglCore = new EGLCore();
        
        // Create a dummy pbuffer surface and make it current
        // This is required before any OpenGL calls can be made
        dummySurface = eglCore.createPbufferSurface(1, 1);
        eglCore.makeCurrent(dummySurface);
        
        // Log GL info (now that context is current)
        GlUtil.logGlInfo();
        
        // Create camera texture (OES type for external camera)
        cameraTextureId = GlUtil.createExternalTexture();
        windshieldTextureId = GlUtil.createExternalTexture();

        // Build the camera consumer. Default = esco-style SurfaceTexture
        // path (addTexture/setTexture/rmTexture + previewIndex). Falls back
        // to the ImageReader path only when USE_ESCO_SURFACE_TEXTURE_PATH is
        // disabled — kept around for FPS-ceiling investigations on Seal
        // (verified ~26 fps by AvmImageReaderFpsProbe vs SurfaceFlinger's
        // ~8.5 fps clamp on legacy SurfaceTexture wiring).
        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            createCameraSurfaceTexture();
        } else {
            createCameraImageReader();
        }
        
        // Initialize GPU components now that EGL context exists
        if (recorder != null) {
            // Recorder needs to be initialized with EGLCore and encoder
            // This should be done by the caller after encoder is created
            logger.debug( "Recorder initialization deferred to caller");
        }
        
        if (downscaler != null) {
            // The downscaler's init() spawns ITS OWN HandlerThread+EGL for the
            // legacy ImageReader-backed probe path (readPixels). That path is
            // independent of the AI-lane GL context — it's used by the
            // camera-profile probe (PanoramicCameraGpu#1125,1193) and the
            // diagnostics camera-mapping snapshot endpoint, neither of which
            // is event-correlated. So leave it set up on the encoder thread.
            //
            // The hot path (readPixelsDirect) lazy-allocates its FBO + PBO
            // ring on the *current* GL thread the first time it's called.
            // With Tier 1 wiring, that first call lands on the AiLaneGl
            // thread, so the FBO/PBOs end up in the AI-lane share-group
            // context — not here. AiLaneGl.shutdown() calls
            // GpuDownscaler.releaseDirectResources() to free them on the
            // matching context (see T1-H1 fix).
            downscaler.init();
            // Layout 3 = DiLink 4 / 2x2-native HAL. The fragment shader
            // rearranges the producer's 2x2 into canonical Front=TL,
            // Right=TR, Rear=BL, Left=BR upright via the per-role corner+
            // flip uniforms set just below. Layout 0 = legacy 4-strip on
            // every other car (Seal, Atto, Dolphin, Tang).
            downscaler.setCameraLayout(getCameraLayoutMode());

            // DiLink 4: same Variant A producer-corner remap + per-role
            // flip the recorder/stream use, so the AI-lane downscaled
            // mosaic is canonically arranged (Front=TL, Right=TR, Rear=BL,
            // Left=BR upright). V2 motion's hardcoded quadrant-index→role
            // mapping (Q0=Front..Q3=Left) only holds when the downscaler
            // emits canonical layout — without this the cropper's centroid
            // and the engine's quadrant grid disagree.
            if (USE_ESCO_SURFACE_TEXTURE_PATH) {
                downscaler.setProducerCornerMap(
                    new float[] { 0.0f, 0.0f },  // Front  → producer TL
                    new float[] { 0.5f, 0.5f },  // Right  → producer BR
                    new float[] { 0.5f, 0.0f },  // Rear   → producer TR
                    new float[] { 0.0f, 0.5f }); // Left   → producer BL
                downscaler.setFlipFlags(
                    new float[] { 1.0f, 1.0f },  // Front  X+Y-flip
                    new float[] { 0.0f, 1.0f },  // Right  Y-flip
                    new float[] { 0.0f, 0.0f },  // Rear   no flip
                    new float[] { 0.0f, 0.0f }); // Left   no flip
                // Red-overlay suppression on the AI lane. Same dilink4RedMask
                // unified-config flag the recorder reads — keeps motion
                // thumbnails clean of the HAL "calibration failed" chrome.
                try {
                    org.json.JSONObject camCfgDs = com.overdrive.app.config
                        .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                    if (camCfgDs != null) {
                        downscaler.setRedMaskEnabled(
                            camCfgDs.optBoolean("dilink4RedMask", false));
                        downscaler.setApaCenterInset(
                            (float) camCfgDs.optDouble("dilink4ApaCenterInset", 0.09375));
                    }
                } catch (Throwable t) {
                    logger.warn("Downscaler red-mask flag read failed: " + t.getMessage());
                }
            }
            logger.debug("Downscaler initialized (probe path on its own thread)");
        }

        // Construct the foveated cropper, but defer its init to the AI-lane
        // GL thread (see brings up AiLaneGl below). Allocating its FBOs +
        // shader on the encoder thread would put them in the encoder
        // context's command stream, which defeats the whole point of
        // Tier 1 — the readback in crop() would still serialize against
        // the encoder thread's eglSwapBuffers.
        foveatedCropper = new FoveatedCropper(width, height,
            quadrantStripOffsetX, quadrantCornerOffsetsXY);
        // Cropper picks 4-strip vs 2x2 corner geometry from the active
        // layout mode. Layout 0 = legacy 4-strip; layout 3 = DiLink 4 /
        // 2x2-native HAL with Variant A producer-corner remap pushed below.
        foveatedCropper.setCameraLayout(getCameraLayoutMode());

        // DiLink 4: override the canonical corner map with the only
        // known-good Variant A layout (Front=TL X-flip, Right=BR no-flip,
        // Rear=TR Y-flip, Left=BL Y-flip). Mirrors GpuMosaicRecorder so
        // V2 motion crops align with the recorder's mosaic.
        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            foveatedCropper.setProducerCornerMap(
                new float[] { 0.0f, 0.0f },  // Front  → producer TL
                new float[] { 0.5f, 0.5f },  // Right  → producer BR
                new float[] { 0.5f, 0.0f },  // Rear   → producer TR
                new float[] { 0.0f, 0.5f }); // Left   → producer BL
            foveatedCropper.setFlipFlags(
                new float[] { 1.0f, 0.0f },  // Front  X-flip
                new float[] { 0.0f, 0.0f },  // Right  no flip
                new float[] { 0.0f, 1.0f },  // Rear   Y-flip
                new float[] { 0.0f, 1.0f }); // Left   Y-flip
            // Red-overlay suppression on AI thumbnails too — same flag the
            // recorder/stream/downscaler read.
            try {
                org.json.JSONObject camCfgFc = com.overdrive.app.config
                    .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                if (camCfgFc != null) {
                    foveatedCropper.setRedMaskEnabled(
                        camCfgFc.optBoolean("dilink4RedMask", false));
                    foveatedCropper.setApaCenterInset(
                        (float) camCfgFc.optDouble("dilink4ApaCenterInset", 0.09375));
                }
            } catch (Throwable t) {
                logger.warn("Cropper red-mask flag read failed: " + t.getMessage());
            }
        }

        logger.info("OpenGL initialized (texture=" + cameraTextureId + ")");
    }
    
    /**
     * Initializes the recorder on the GL thread.
     * 
     * This must be called after the GL context is created and made current.
     * 
     * @param recorder GPU mosaic recorder to initialize
     * @param encoder Hardware encoder providing the input surface
     */
    public void initRecorderOnGlThread(GpuMosaicRecorder recorder, HardwareEventRecorderGpu encoder) {
        if (glHandler == null) {
            logger.error( "GL thread not started");
            return;
        }
        
        // Store encoder reference for draining in render loop
        this.encoder = encoder;
        
        glHandler.post(() -> {
            try {
                recorder.init(eglCore, encoder);
                // Wire the HAL-contention probe so the recorder's safety valve
                // only fires when the BYD native AVM app is actively sharing
                // the camera. In normal solo operation the valve stays inert
                // and eglSwapBuffers handles encoder backpressure natively —
                // no more 1-in-N drops at 25–30 fps.
                recorder.setHalContentionProbe(() -> {
                    BydCameraCoordinator c = cameraCoordinator;
                    return c != null && c.isNativeAppActive();
                });
                logger.info( "Recorder initialized on GL thread");

                // Notify pipeline that recorder is ready
                if (recorderInitCallback != null) {
                    recorderInitCallback.run();
                }
            } catch (Exception e) {
                logger.error( "Failed to initialize recorder on GL thread", e);
            }
        });
    }
    
    // Callback for when recorder is initialized
    private Runnable recorderInitCallback;
    
    /**
     * Sets a callback to be invoked when the recorder is initialized.
     * 
     * @param callback Callback to run on GL thread after recorder init
     */
    public void setRecorderInitCallback(Runnable callback) {
        this.recorderInitCallback = callback;
    }
    
    /**
     * Initializes the stream scaler on the GL thread.
     * 
     * @param streamScaler GPU stream scaler to initialize
     * @param streamEncoder Hardware encoder for streaming
     */
    public void initStreamScalerOnGlThread(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                          HardwareEventRecorderGpu streamEncoder) {
        if (glHandler == null) {
            logger.error("GL thread not started");
            return;
        }
        
        glHandler.post(() -> {
            try {
                streamScaler.init(eglCore, streamEncoder);
                logger.info("Stream scaler initialized on GL thread");
            } catch (Exception e) {
                logger.error("Failed to initialize stream scaler on GL thread", e);
            }
        });
    }
    
    /**
     * Gets the EGL core for initializing GPU components.
     * 
     * @return EGLCore instance (only valid after start() is called)
     */
    public EGLCore getEglCore() {
        return eglCore;
    }
    
    /**
     * Recreates the SurfaceTexture and Surface for camera switching.
     * 
     * The BYD AVMCamera HAL doesn't properly deliver frames to a Surface
     * that was previously connected to a different camera ID. After the first
     * frame, subsequent frames are never delivered, causing a frozen image.
     * Recreating the SurfaceTexture forces a clean connection to the new camera.
     */
    private void recreateCameraSurface() {
        logger.info("Recreating "
            + (USE_ESCO_SURFACE_TEXTURE_PATH ? "SurfaceTexture" : "ImageReader")
            + " consumer for camera switch...");
        releaseCameraConsumer();
        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            createCameraSurfaceTexture();
        } else {
            createCameraImageReader();
        }
        logger.info("Camera consumer recreated for camera switch");
    }

    /** Build a SurfaceTexture-backed consumer (esco path).
     *  Frame handling:
     *    HAL → SurfaceTexture producer (BufferQueue)
     *      → setOnFrameAvailableListener fires on glHandler
     *        → renderLoop sees stFramePending, calls updateTexImage()
     *  Mirrors esco's gl.C5920a path: addTexture/setTexture/rmTexture.
     *  cameraTextureId is created in initializeGl() and is the EXTERNAL_OES
     *  texture the SurfaceTexture writes into. We attach the listener on
     *  glHandler so the renderLoop wakeup happens on the same thread that
     *  later calls updateTexImage — the HAL ping/notify race that motivates
     *  imagePending on the ImageReader path applies the same way here.
     *
     *  We do NOT call attachToGLContext / detachFromGLContext on this
     *  SurfaceTexture: the SurfaceTexture(int) ctor already attaches it to
     *  the current EGL context's cameraTextureId, and updateTexImage runs
     *  on the GL thread where that context is current.  */
    private void createCameraSurfaceTexture() {
        if (cameraTextureId == 0) {
            logger.warn("createCameraSurfaceTexture called before GL texture exists");
            return;
        }
        cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
        // esco-parity: do NOT call setDefaultBufferSize. esco's GL pipeline
        // wraps SurfaceTexture without setting a default size; the BYD HAL
        // drives the BufferQueue dims for the active previewIndex (mosaic
        // strip = 5120x960 on Seal/Tang, etc.) and the consumer adapts via
        // updateTexImage's transform matrix. Forcing dims here can make the
        // HAL silently scale/crop or stall on byd_apa boards.
        cameraSurfaceTexture.setOnFrameAvailableListener(st -> {
            // Cheap signalling — the actual updateTexImage happens on the GL
            // thread inside renderLoop. Ride frameSync so the wait/notify
            // protocol matches the ImageReader path.
            synchronized (frameSync) {
                stFramePending = true;
                frameSync.notify();
            }
        }, glHandler);
        // Build the Surface that AVMCamera doesn't actually receive — the
        // esco path uses addTexture(SurfaceTexture, ...) directly. We keep
        // the cameraSurface reference null on this path so any stray
        // addPreviewSurface code can't accidentally re-attach.
        cameraSurface = null;
    }

    /** Bind the active SurfaceTexture to the AVMCamera via reflection,
     *  mirroring esco's startPreview block:
     *      addTexture(st, previewIndex)
     *      setTexture(st, previewIndex)
     *      startPreview()
     *  previewIndex comes from cameraSurfaceMode (0=mosaic, 1-4=quadrant).
     *  Caller must have just opened the camera (cameraObj != null). */
    private void attachSurfaceTextureToCamera(int cameraId) throws Exception {
        if (cameraObj == null) {
            throw new IllegalStateException("attachSurfaceTextureToCamera with null cameraObj");
        }
        if (cameraSurfaceTexture == null) {
            throw new IllegalStateException("attachSurfaceTextureToCamera before createCameraSurfaceTexture");
        }
        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
        int previewIndex = cameraSurfaceMode;

        // BmmCameraInfo dim probe — what the HAL claims it'll emit for this
        // camera id BEFORE we attach. Mirrors esco's C6500c.m28945a path.
        // Output is purely diagnostic; the BYD HAL ignores anything we do
        // with these numbers, but logging them lets us correlate the
        // "configured strip" (pipeline expects 5120x960) against the slot
        // we're actually opening (id=1 might be 1280x720, etc.). On variants
        // where BmmCameraInfo is empty (vehicle.config.cam_sort unset) both
        // calls return 0 — note that explicitly.
        logHalDeclaredDims(cameraId);

        // AVM factory-calibration probe. esco reads these at app init:
        //   - persist.vendor.camera.autostudy.avm  (calibration coefficients)
        //   - vehicle.config.camInfo.avm           (physical module info)
        //   - vehicle.config.cam_sort / pano_cam / pano_l_cam (id mapping)
        // When `autostudy.avm` is empty / absent, the BYD AVM HAL itself
        // refuses to stream the stitched mosaic and returns a red "no
        // calibration" frame (or similar) — esco doesn't post-process this
        // away because on properly-calibrated cars it never appears. If you
        // see a red feed on this car, check this log: if all four props are
        // empty, the AVM was never calibrated by the dealer.
        logAvmCalibrationProps();

        Method mAddTexture = avmClass.getDeclaredMethod(
            "addTexture", SurfaceTexture.class, int.class);
        mAddTexture.setAccessible(true);
        mAddTexture.invoke(cameraObj, cameraSurfaceTexture, previewIndex);

        Method mSetTexture = avmClass.getDeclaredMethod(
            "setTexture", SurfaceTexture.class, int.class);
        mSetTexture.setAccessible(true);
        mSetTexture.invoke(cameraObj, cameraSurfaceTexture, previewIndex);

        Method mStart = avmClass.getDeclaredMethod("startPreview");
        mStart.setAccessible(true);
        Object startResult = mStart.invoke(cameraObj);
        logger.info("Esco-path attached: addTexture+setTexture(idx=" + previewIndex
            + ") + startPreview → " + startResult
            + " (cameraId=" + cameraId + ")");

        // Re-arm the first-frame transform-matrix log so we print it on the
        // next frame after every (re)attach, not just the very first
        // session. SurfaceTexture stays the same instance across recreate
        // so we'd otherwise miss the dim probe on probe-driven re-opens.
        firstFrameDimsLogged = false;
    }

    /** Probe BmmCameraInfo for the HAL's declared preview size for this
     *  cameraId. Pure logging — we never trust these numbers, but they're
     *  the cheapest signal of "the slot you opened streams something
     *  utterly different from the configured strip". */
    private void logHalDeclaredDims(int cameraId) {
        try {
            Class<?> bmm = Class.forName("android.hardware.BmmCameraInfo");
            Method gw = bmm.getDeclaredMethod("getDefaultPreviewWidth", int.class);
            Method gh = bmm.getDeclaredMethod("getDefaultPreviewHeight", int.class);
            gw.setAccessible(true);
            gh.setAccessible(true);
            Object w = gw.invoke(null, cameraId);
            Object h = gh.invoke(null, cameraId);
            int wInt = (w instanceof Integer) ? (Integer) w : 0;
            int hInt = (h instanceof Integer) ? (Integer) h : 0;
            logger.info("BmmCameraInfo declared dims for cam=" + cameraId
                + ": " + wInt + "x" + hInt
                + (wInt == 0 || hInt == 0
                    ? "  (HAL has no entry — vehicle.config.cam_sort empty)"
                    : "")
                + "; pipeline configured " + width + "x" + height);
            // BmmCameraInfo's "single preview" reports per-quadrant; the
            // mosaic-doubled dims that AVMCamera emits for previewIndex=0
            // are 2*W x 2*H per esco's C6500c.m28945a:88. Spell that out.
            if (wInt > 0 && hInt > 0) {
                int mosaicW = wInt * 2;
                int mosaicH = hInt * 2;
                logger.info("  → mosaic-doubled would be " + mosaicW + "x" + mosaicH
                    + " (esco AVMCamera 2x scale rule)");
            }
        } catch (ClassNotFoundException e) {
            logger.info("BmmCameraInfo class not present — skipping dim probe");
        } catch (NoSuchMethodException e) {
            logger.info("BmmCameraInfo.getDefaultPreviewWidth/Height not found — skipping dim probe");
        } catch (Throwable t) {
            logger.warn("BmmCameraInfo dim probe failed: " + t.getMessage());
        }
    }

    /** Dump the four AVM-related SystemProperties so we can tell at a glance
     *  whether the car has been factory-calibrated. Empty `autostudy.avm` +
     *  empty `camInfo.avm` is the classic "AVM HAL renders red 'calibration
     *  failed' frame" symptom — esco never sees it because it ships on cars
     *  where these are populated by the dealer-side calibration procedure. */
    private void logAvmCalibrationProps() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            String autostudy = safeGetProp(get, "persist.vendor.camera.autostudy.avm");
            String camInfo   = safeGetProp(get, "vehicle.config.camInfo.avm");
            String camSort   = safeGetProp(get, "vehicle.config.cam_sort");
            String panoCam   = safeGetProp(get, "vehicle.config.pano_cam");
            String panoLCam  = safeGetProp(get, "vehicle.config.pano_l_cam");
            logger.info("AVM calibration props:"
                + " autostudy.avm=" + describeProp(autostudy)
                + " camInfo.avm=" + describeProp(camInfo)
                + " cam_sort=" + describeProp(camSort)
                + " pano_cam=" + describeProp(panoCam)
                + " pano_l_cam=" + describeProp(panoLCam));
            if (isBlank(autostudy) && isBlank(camInfo)) {
                // Information only. The HAL gates the red 'calibration failed'
                // overlay on this property being non-empty; nothing in
                // user-space (not us, not esco) can write
                // persist.vendor.camera.autostudy.avm — it's owned by the
                // dealer-run autostudy procedure and SELinux denies app/shell
                // writes (verified rc=1 in the field). When this fires the
                // user's only software remedy is the GL red-mask filter.
                logger.info("AVM is UNCALIBRATED on this vehicle (autostudy "
                    + "and camInfo properties are empty). The HAL will paint "
                    + "a red banner into the producer surface; enable "
                    + "dilink4RedMask to mask it cosmetically.");
            }
        } catch (Throwable t) {
            logger.warn("AVM calibration prop probe failed: " + t.getMessage());
        }
    }

    private static String safeGetProp(java.lang.reflect.Method get, String name) {
        try {
            Object v = get.invoke(null, name);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String describeProp(String v) {
        if (v == null || v.isEmpty()) return "(empty)";
        // Truncate ridiculously long values (autostudy can be hundreds of bytes
        // of binary-as-hex). Just show length + first 40 chars so logs stay
        // legible while still preserving "is it actually populated?".
        if (v.length() <= 60) return "'" + v + "'";
        return "len=" + v.length() + " head='" + v.substring(0, 40) + "…'";
    }

    private static boolean isBlank(String v) {
        return v == null || v.isEmpty();
    }

    // Cached reflection handles for AVMCamera.rmTexture, resolved once.
    // Camera detach is not a hot path, but caching avoids a repeated
    // Class.forName + getDeclaredMethod on every close/reopen cycle.
    // sRmTextureMethod stays null on older HAL builds that lack rmTexture
    // (the NoSuchMethodException path) — close() handles teardown there.
    private static Class<?> sAvmCameraClass;
    private static Method sRmTextureMethod;
    private static boolean sReflectionInitialized = false;

    private static synchronized void ensureReflectionCache() {
        if (sReflectionInitialized) return;
        try {
            sAvmCameraClass = Class.forName("android.hardware.AVMCamera");
            try {
                sRmTextureMethod = sAvmCameraClass.getDeclaredMethod(
                    "rmTexture", SurfaceTexture.class, int.class);
                sRmTextureMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                // older HAL builds without rmTexture — close() handles it
            }
        } catch (Throwable t) {
            logger.warn("Failed to initialize AVMCamera reflection cache: " + t.getMessage());
        }
        sReflectionInitialized = true;
    }

    /** Detach the SurfaceTexture from the camera before close.
     *  Mirrors esco gl.C5920a.m26746l: rmTexture(st, previewIndex).
     *  Quiet on errors — close() right after is the canonical teardown. */
    private void detachSurfaceTextureFromCamera(Object cam) {
        SurfaceTexture st = cameraSurfaceTexture;
        if (cam == null || st == null) return;
        try {
            ensureReflectionCache();
            if (sRmTextureMethod != null) {
                sRmTextureMethod.invoke(cam, st, cameraSurfaceMode);
            }
            // else: older HAL builds without rmTexture — close() handles it
        } catch (Throwable t) {
            logger.warn("rmTexture failed: " + t.getMessage());
        }
    }

    /** Close the camera in the order esco uses (gl.C5920a.m26747m:318-356):
     *    1. BYDApaHelper unregister + vp reset       (esco line 325)
     *    2. rmTexture(SurfaceTexture, previewIndex)  (esco line 328)
     *    3. setPreviewCallback(null)                 (esco line 333)
     *    4. setEventCallback(null)                   (esco line 339)
     *    5. stopPreview                              (esco line 342)
     *    6. close                                    (esco line 345)
     *  BydCameraCoordinator.closeCamera bundles steps 5+6 (and a redundant
     *  disablePreviewCallback before stopPreview, which is benign).
     *
     *  Legacy path: only steps 5+6 — BydCameraCoordinator.closeCamera. */
    private void closeCameraForPath(Object cam) {
        if (cam == null) return;
        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            // Step 1 — release our viewpoint token. Mirrors esco C5920a.m26747m
            // (gl/C5920a.java:323 — C6498a.f26622a.m28933k(this)). Observer-set
            // semantics: this only writes viewpoint=0 + disableDevice if WE were
            // the last holder. If a sentry-restart caller acquired its own token
            // before stop() was invoked (ACC-OFF straddle), the set stays
            // non-empty and the HAL is never told to drop mosaic — that's what
            // keeps frames flowing across the close+reopen on this car.
            BydApaViewpointHelper.release(viewpointToken);
            // Step 2 — remove our texture binding from the HAL.
            detachSurfaceTextureFromCamera(cam);
            // Steps 3+4 — null callback proxies on the AVMCamera.
            clearAvmCameraCallbacks(cam);
        }
        // Steps 5+6 (and disablePreviewCallback in legacy compat).
        BydCameraCoordinator.closeCamera(cam, cameraSurfaceMode);
    }

    /** Null out the AVMCamera-side preview + event callback proxies before
     *  the HAL stopPreview/close. esco does this in m26747m at C5920a:333,339;
     *  some HAL builds keep stale refs alive otherwise. Quiet on errors. */
    private void clearAvmCameraCallbacks(Object cam) {
        if (cam == null) return;
        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            Class<?> previewCb = null;
            Class<?> eventCb = null;
            try {
                previewCb = Class.forName("android.hardware.AVMCamera$IPreviewCallback");
            } catch (ClassNotFoundException ignored) {}
            try {
                eventCb = Class.forName("android.hardware.AVMCamera$IEventCallback");
            } catch (ClassNotFoundException ignored) {}
            if (previewCb != null) {
                try {
                    Method m = avmClass.getDeclaredMethod("setPreviewCallback", previewCb);
                    m.setAccessible(true);
                    m.invoke(cam, new Object[]{null});
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable t) {
                    logger.warn("setPreviewCallback(null) failed: " + t.getMessage());
                }
            }
            if (eventCb != null) {
                try {
                    Method m = avmClass.getDeclaredMethod("setEventCallback", eventCb);
                    m.setAccessible(true);
                    m.invoke(cam, new Object[]{null});
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable t) {
                    logger.warn("setEventCallback(null) failed: " + t.getMessage());
                }
            }
        } catch (ClassNotFoundException e) {
            // AVMCamera class not present — fatal everywhere else, ignore here.
        }
    }

    /** Build an ImageReader-backed consumer (zero-copy path).
     *  Frame handling:
     *    HAL → ImageReader producer (gralloc)
     *      → OnImageAvailableListener fires on imageReaderThread
     *        → acquireLatestImage / getHardwareBuffer
     *          → glHandler.post(bindHardwareBufferToTexture + notify frameSync)
     *  The listener MUST run on a thread separate from glHandler because
     *  renderLoop parks the GL thread on frameSync.wait(); a same-thread
     *  listener would starve and the HAL queue would back up, dropping
     *  frames the way we observed at boot (Stats: 0 frames). */
    private void createCameraImageReader() {
        if (imageReaderThread == null) {
            imageReaderThread = new HandlerThread("CamImageReaderCb");
            imageReaderThread.start();
            imageReaderHandler = new Handler(imageReaderThread.getLooper());
        }
        // Pool size 6 (vs the typical 3) absorbs GL-thread stalls during
        // surveillance heavy work (YOLO inference, foveated readback) without
        // throttling the HAL producer rate. At 5120×960 NV12 = 7.4 MB/buf,
        // pool=6 holds ~44 MB gralloc — well within Adreno 610 budget.
        // Pool=3 was throttling HAL emission to ~5.7 fps in surveillance mode
        // because GL frames occasionally hit 261ms (logged backpressure).
        // 6 buffers × 67ms (15 fps cycle) = 400ms slack vs 200ms.
        // PRIVATE = opaque gralloc, optimal for zero-copy GPU sampling.
        // USAGE_GPU_SAMPLED_IMAGE tells the gralloc allocator we want a
        // GPU-friendly memory layout.
        final int poolSize = 6;
        try {
            long usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
            cameraImageReader = ImageReader.newInstance(
                width, height,
                ImageFormat.PRIVATE,
                poolSize,
                usage);
        } catch (Throwable t) {
            // Some BYD HAL builds may reject PRIVATE — fall back to YUV_420_888.
            logger.warn("ImageReader PRIVATE init failed: " + t.getMessage()
                + " — falling back to YUV_420_888");
            cameraImageReader = ImageReader.newInstance(
                width, height,
                ImageFormat.YUV_420_888,
                poolSize);
        }
        cameraImageReader.setOnImageAvailableListener(
            this::onHalImageAvailable, imageReaderHandler);
        cameraSurface = cameraImageReader.getSurface();
    }

    /** Idempotent teardown of whichever consumer is active. */
    private void releaseCameraConsumer() {
        // Crash-fix (gap-closer): this runs on the GL render thread via
        // recreateCameraSurface during live reacquire/auto-probe/restart while the
        // AI lane is STILL alive — freeing the bound gralloc / releasing the
        // SurfaceTexture here can race an in-flight AI-lane sample exactly like the
        // rebind path. Hold cameraTextureLock across BOTH buffer-free sites (the
        // top releasePreviousBoundImage AND the SurfaceTexture.release). Harmless
        // when reached from releaseGl (AI lane already shut down there).
        synchronized (cameraTextureLock) {
        // Release the held Image + HardwareBuffer FIRST so the gralloc slots
        // go back to the ImageReader pool before we close the reader.
        releasePreviousBoundImage();
        if (cameraSurface != null) {
            try { cameraSurface.release(); } catch (Throwable ignored) {}
            cameraSurface = null;
        }
        if (cameraImageReader != null) {
            try { cameraImageReader.close(); } catch (Throwable ignored) {}
            cameraImageReader = null;
        }
        if (cameraSurfaceTexture != null) {
            try { cameraSurfaceTexture.setOnFrameAvailableListener(null); } catch (Throwable ignored) {}
            try { cameraSurfaceTexture.release(); } catch (Throwable ignored) {}
            cameraSurfaceTexture = null;
        }
        }
        stFramePending = false;
        // Reset to identity so a stale matrix from the previous camera
        // can't leak into the first draw against a freshly-attached
        // SurfaceTexture if its first consumeSurfaceTextureFrame returns
        // false (e.g., spurious wakeup before HAL emits its first frame).
        // Identity is safe — it samples the whole producer surface, same
        // as legacy ImageReader behaviour.
        resetCurrentTexMatrixToIdentity();
    }

    private void resetCurrentTexMatrixToIdentity() {
        currentTexMatrix[0]  = 1f; currentTexMatrix[1]  = 0f;
        currentTexMatrix[2]  = 0f; currentTexMatrix[3]  = 0f;
        currentTexMatrix[4]  = 0f; currentTexMatrix[5]  = 1f;
        currentTexMatrix[6]  = 0f; currentTexMatrix[7]  = 0f;
        currentTexMatrix[8]  = 0f; currentTexMatrix[9]  = 0f;
        currentTexMatrix[10] = 1f; currentTexMatrix[11] = 0f;
        currentTexMatrix[12] = 0f; currentTexMatrix[13] = 0f;
        currentTexMatrix[14] = 0f; currentTexMatrix[15] = 1f;
    }

    private void createWindshieldImageReader() {
        if (imageReaderThread == null) {
            imageReaderThread = new HandlerThread("CamImageReaderCb");
            imageReaderThread.start();
            imageReaderHandler = new Handler(imageReaderThread.getLooper());
        }
        try {
            windshieldImageReader = ImageReader.newInstance(
                1920, 1080,
                ImageFormat.PRIVATE,
                4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        } catch (Throwable t) {
            logger.warn("Windshield ImageReader PRIVATE init failed: " + t.getMessage()
                + " — falling back to YUV_420_888");
            windshieldImageReader = ImageReader.newInstance(
                1920, 1080,
                ImageFormat.YUV_420_888,
                4);
        }
        windshieldImageReader.setOnImageAvailableListener(this::onWindshieldImageAvailable,
            imageReaderHandler);
        windshieldSurface = windshieldImageReader.getSurface();
    }

    private void onWindshieldImageAvailable(ImageReader r) {
        windshieldPending = true;
        synchronized (frameSync) {
            frameSync.notify();
        }
    }

    private void updateWindshieldCameraOnGlThread() {
        if (windshieldEnabled && windshieldCameraId >= 0) {
            if (!windshieldStarted && !windshieldOpenFailed) {
                startWindshieldCameraOnGlThread();
            }
        } else if (windshieldStarted || windshieldOpenFailed) {
            stopWindshieldCameraOnGlThread();
            windshieldOpenFailed = false;
        }
    }

    private void startWindshieldCameraOnGlThread() {
        try {
            createWindshieldImageReader();
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            windshieldCameraObj = constructor.newInstance(windshieldCameraId);

            Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            if (!(boolean) mOpen.invoke(windshieldCameraObj)) {
                throw new RuntimeException("AVMCamera.open() returned false (id="
                    + windshieldCameraId + ")");
            }

            AvmCameraHelper.setCameraFps(windshieldCameraObj, targetFps);

            Method mAddSurface = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            mAddSurface.setAccessible(true);
            mAddSurface.invoke(windshieldCameraObj, windshieldSurface, 0);

            Method mStart = avmClass.getDeclaredMethod("startPreview");
            mStart.setAccessible(true);
            mStart.invoke(windshieldCameraObj);

            windshieldStarted = true;
            windshieldFrameReady = false;
            windshieldFrameCount = 0;
            // Seed the stall clock at start so the first-frame grace window is
            // measured from now, not from a stale prior-session timestamp.
            windshieldLastFrameMs = System.currentTimeMillis();
            logger.info("Windshield camera started (id=" + windshieldCameraId + ")");
        } catch (Throwable t) {
            logger.warn("Windshield camera unavailable; dashcam layout will fall back to 360 front: "
                + t.getMessage());
            windshieldOpenFailed = true;
            stopWindshieldCameraOnGlThread();
        }
    }

    private void stopWindshieldCameraOnGlThread() {
        if (windshieldCameraObj != null) {
            BydCameraCoordinator.closeCamera(windshieldCameraObj, 0);
            windshieldCameraObj = null;
        }
        releasePreviousBoundWindshieldImage();
        if (windshieldSurface != null) {
            try { windshieldSurface.release(); } catch (Throwable ignored) {}
            windshieldSurface = null;
        }
        if (windshieldImageReader != null) {
            try { windshieldImageReader.close(); } catch (Throwable ignored) {}
            windshieldImageReader = null;
        }
        if (windshieldStarted || windshieldFrameReady) {
            logger.info("Windshield camera stopped (frames=" + windshieldFrameCount + ")");
        }
        windshieldStarted = false;
        windshieldPending = false;
        windshieldFrameReady = false;
        windshieldFrameCount = 0;
    }

    /**
     * Starts the BYD camera via AVMCamera reflection with multi-strategy fallback.
     * Tries constructor path first, then static factory for firmware compatibility.
     */
    private void startCamera() throws Exception {
        // GATE: Don't open camera if yielded to native app via IBYDCameraUser callback
        if (cameraCoordinator != null && cameraCoordinator.isCameraYielded()) {
            logger.info("Camera yielded to native app — skipping open");
            cameraYielded = true;
            // Defensive: ensure the yield poller is running. yieldCameraInternal
            // also starts it, but this path can be hit if startCamera is invoked
            // while the coordinator already reports yielded (e.g. ACC ON race).
            startYieldPoller();
            return;
        }

        int cameraId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;

        startCameraViaAvmReflection(cameraId);

        // Remember every slot we've opened so the dead-slot walk (issue #170)
        // can't loop back onto one that already failed to deliver a frame.
        deadSlotTriedCameraIds.add(cameraId);

        cameraYielded = false;
        lastCameraStartTime = System.currentTimeMillis();
        // Snapshot the frame counter at this open so the next restart can tell
        // whether THIS open ever delivered a frame (zero-frame-reopen escalation).
        frameCounterAtOpen = frameCounter;
        logger.info("Camera started (" + width + "x" + height +
            ", id=" + cameraId + ", surfaceMode=" + cameraSurfaceMode + ")");
        
        // Update coordinator with actual camera ID
        if (cameraCoordinator != null) {
            cameraCoordinator.setActiveCameraId(cameraId);
        }
    }

    /**
     * Opens camera via AVMCamera reflection.
     *
     * Strategy (mirrors the reference implementation approach):
     *   1. Constructor: new AVMCamera(int) + .open() — required on this device.
     *      The static factory AVMCamera.open(int) returns null because
     *      BmmCameraInfo.isValidCamera() is empty (vehicle.config.cam_sort
     *      is unset on DiLink 5.0). The constructor bypasses that gate and
     *      is the only path that opens the camera at all.
     *   2. Static factory AVMCamera.open(int) — only if constructor is
     *      missing entirely (DiLink 6.0+ may remove it).
     *
     * See CAMERA_FPS_INVESTIGATION.md for the full rationale.
     *
     * After either path succeeds, addPreviewSurface + startPreview are called.
     *
     * Notifies IBYDCameraService before opening so the service can arbitrate
     * with native apps (reverse camera, dashcam, AVM parking view).
     */
    private void startCameraViaAvmReflection(int cameraId) throws Exception {
        // ESCO-PARITY: no gate. esco's user-preview path opens AVMCamera
        // immediately on PanoCameraRecordService.m19854a → AIDL → daemon
        // C5312b.m24073j → C5920a.mo26750v with no wall-clock wait. All
        // OverDrive open paths (StreamingApiHandler, RecordingModeManager,
        // OemDashcam, CameraDaemon ACC-OFF) reach this method directly.

        // Notify camera service we're about to open
        if (cameraCoordinator != null) {
            cameraCoordinator.notifyPreOpenCamera();
        }

        // esco-parity: tell the BYDAutoManager Panorama device (1031) to switch
        // its viewpoint to mosaic-output BEFORE opening AVMCamera. On byd_apa /
        // apa firmware variants the HAL boots in single-camera (dashcam) mode
        // and stays there until this setIntArray write flips it. Mirrors esco
        // gl.C5920a.mo26750v:386-388.
        //
        // Gated on the DiLink 4 path: on legacy pano_h/pano_l boards the
        // disable counterpart in closeCameraForPath is also gated, so we
        // keep the pair symmetric. The helper would warn-log on legacy
        // anyway (no panorama device exposed), but skipping the call also
        // skips a binder round-trip per camera open.
        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            // Acquire our viewpoint token. Mirrors esco C5920a.mo26750v
            // (gl/C5920a.java:387 — C6498a.f26622a.m28930h(this)). If
            // we're the only holder this writes viewpoint=2012 and registers
            // the listener; if a sentry-restart caller is already holding a
            // token, this just re-issues the viewpoint write idempotently
            // (matches esco's "size>1 already, no enableDevice/listener" branch).
            BydApaViewpointHelper.acquire(viewpointToken);

            // Release the static sentry-bridge token NOW (set transitions
            // bridge+pano → pano on the same lock acquire as our add — no
            // empty-set window). Idempotent + harmless if no bridge was held.
            releaseSentryBridgeViewpoint();
        }

        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");

        // === ATTEMPT 1: Constructor new AVMCamera(int) + .open() ===
        // Required on this firmware. The static factory would return null.
        try {
            Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            cameraObj = constructor.newInstance(cameraId);

            Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            if (!(boolean) mOpen.invoke(cameraObj)) {
                throw new RuntimeException("AVMCamera.open() returned false (id=" + cameraId + ")");
            }
            logger.info("Camera opened via constructor path (id=" + cameraId + ")");
        } catch (NoSuchMethodException e) {
            // Constructor with int param doesn't exist — fall back to static factory
            logger.info("AVMCamera(int) constructor not found — trying static factory");
            cameraObj = null;

            // === ATTEMPT 2: Static factory AVMCamera.open(cameraId) ===
            try {
                Method mStaticOpen = avmClass.getDeclaredMethod("open", int.class);
                mStaticOpen.setAccessible(true);
                cameraObj = mStaticOpen.invoke(null, cameraId);
                if (cameraObj != null) {
                    logger.info("Camera opened via static factory (id=" + cameraId + ")");
                } else {
                    logger.info("AVMCamera.open(" + cameraId + ") returned null — trying IDs 0-5");
                    for (int tryId = 0; tryId <= 5; tryId++) {
                        if (tryId == cameraId) continue;
                        cameraObj = mStaticOpen.invoke(null, tryId);
                        if (cameraObj != null) {
                            logger.info("Camera opened via static factory probe (id=" + tryId + ")");
                            cameraIdOverride = tryId;
                            break;
                        }
                    }
                }
                if (cameraObj == null) {
                    throw new RuntimeException("AVMCamera.open() returned null for all IDs 0-5");
                }
            } catch (NoSuchMethodException e2) {
                throw new RuntimeException(
                    "AVMCamera API not compatible: no constructor(int) and no static open(int). " +
                    "Available constructors: " + Arrays.toString(avmClass.getDeclaredConstructors()) +
                    ", methods: " + Arrays.toString(avmClass.getDeclaredMethods()), e2);
            }
        }
        
        // Set FPS BEFORE attaching any consumer. On DiLink 3.x firmware the
        // HAL rejects setCameraFps once a consumer is bound — even before
        // startPreview. Order matches both esco's AVMCameraRecorder and the
        // legacy ImageReader path: open → setCameraFps → attach → start.
        AvmCameraHelper.setCameraFps(cameraObj, targetFps);

        // esco-parity: register the AVMCamera IEventCallback BEFORE the
        // consumer attach so the 1003 first-frame event and any pre-frame
        // 8/1000/1002 fatal events emitted during HAL warmup are observable.
        // Mirrors esco gl.C5920a.mo26750v:418 (after setCameraFps, before
        // addTexture). The previous wiring registered the callback after
        // start() returned; on byd_apa boards that fire the death event
        // inside the warmup window, the coordinator's onCameraError never
        // fired.
        if (cameraCoordinator != null) {
            cameraCoordinator.setupEventCallback(cameraObj);
        }

        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            // esco path: addTexture(st, idx) + setTexture(st, idx) + startPreview.
            // attachSurfaceTextureToCamera does all three; cameraSurfaceMode is
            // the previewIndex (0=mosaic on byd_apa/apa HAL).
            attachSurfaceTextureToCamera(cameraId);
        } else {
            // Legacy path: addPreviewSurface(Surface, surfaceMode) + startPreview.
            // mode 0 works on Seal; other models may need different mode.
            Method mAddSurface = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            mAddSurface.setAccessible(true);
            mAddSurface.invoke(cameraObj, cameraSurface, cameraSurfaceMode);

            // Start preview — required for real frame data on BYD Seal HAL.
            // The HAL supports multiple consumers calling startPreview simultaneously.
            // The AVC warmup (com.byd.avc launch + 4s delay) ensures the native DVR
            // has already initialized before we reach here, preventing race conditions.
            Method mStart = avmClass.getDeclaredMethod("startPreview");
            mStart.setAccessible(true);
            mStart.invoke(cameraObj);
            logger.info("Camera started (id=" + cameraId + ", targetFps=" + targetFps + ")");
        }
    }
    
    // Diagnostic counters for the ImageReader frame flow. Kept in place as
    // permanent instrumentation since the path crosses two threads + a
    // gralloc lifetime boundary; surfacing health via 2-min Stats line is
    // cheap and useful in field debugging.
    private volatile long irFireCount = 0;       // onHalImageAvailable invocations
    private volatile long irAcquireOkCount = 0;
    private volatile long irAcquireNullCount = 0;
    private volatile long irBindFailCount = 0;
    private volatile long lastIrDiagLogMs = 0;

    /**
     * Called when a new gralloc buffer is available from the HAL
     * (ImageReader path, API 28+). Runs on imageReaderThread (NOT glThread)
     * — we cannot do the EGLImage bind here because the EGL context lives
     * on the GL thread.
     *
     * Strategy: notify frameSync so renderLoop wakes up. renderLoop will
     * do acquireLatestImage + getHardwareBuffer + bind on the GL thread
     * where the EGL context is current. This mirrors the SurfaceTexture
     * path where the producer notifies and the consumer thread does
     * updateTexImage.
     */
    private void onHalImageAvailable(ImageReader r) {
        irFireCount++;
        synchronized (frameSync) {
            imagePending = true;
            frameSync.notify();
        }
    }

    /**
     * Acquires the latest gralloc buffer from cameraImageReader and binds it
     * to cameraTextureId. MUST be called from the GL thread (current EGL
     * context required for glEGLImageTargetTexture2DOES).
     *
     * Returns true if a frame was bound; false if no frame was ready or
     * the bind failed. acquireLatestImage drops older buffered frames if
     * the GL loop falls behind, matching SurfaceTexture's "always sample
     * latest" semantics.
     */
    // The Image and HardwareBuffer currently bound to cameraTextureId.
    // Held alive across GL render cycles — closing them returns the gralloc
    // slot to the ImageReader pool, which invalidates the EGLImage we bound
    // and causes the producer side to stall. Released only when the NEXT
    // bind succeeds (releasePreviousImage call inside consumeLatestImageAndBind),
    // so the texture always references a live gralloc buffer.
    //
    // THREAD-CONFINED to the GL thread (renderLoop). All reads and writes
    // happen inside consumeLatestImageAndBind / releasePreviousBoundImage,
    // which are only invoked from renderLoop. Do NOT access from the
    // ImageReader callback thread, watchdog, or any daemon thread — touching
    // these from another thread will leak the gralloc slot and stall the HAL.
    private Image currentBoundImage;             // @GuardedBy(GL thread)
    private HardwareBuffer currentBoundHwBuffer; // @GuardedBy(GL thread)
    // HAL-provided sensor timestamp (ns) of the currently bound image,
    // captured from Image.getTimestamp() inside consumeLatestImageAndBind.
    // Fed to eglPresentationTimeANDROID so MediaCodec produces honest PTS.
    //
    // BYD DiLink 5.0 HAL specifics observed in field probes:
    //   - On the very first frame, hwTs ≈ 52ms (an uptime offset, not a
    //     real sensor time).
    //   - Subsequent frames return the same value, OR micro-advance by
    //     a few hundred ns per frame, never tracking real cadence.
    //
    // Strategy (single clock domain): the BYD PRIVATE-ImageReader HAL returns a
    // stuck, different-epoch (~uptime) value for Image.getTimestamp() on this
    // fleet, so we DO NOT trust it. nextFrameTimestampNs() stamps
    // System.nanoTime() from frame 0 unconditionally — exactly what the sibling
    // esco SurfaceTexture path does — which means there is never a mid-stream
    // HW→nanoTime clock-domain transition to corrupt the muxer's rebase math.
    // (The former hwTs-trust-then-latch machine is the historical root of the
    // "55 min – 1 hr clip duration" bug; removed.) lastAcceptedPtsNs enforces
    // MediaCodec's strictly-increasing PTS contract within that one domain.
    private long currentFrameTimestampNs = 0;     // @GuardedBy(GL thread)
    private long lastAcceptedPtsNs = 0;           // @GuardedBy(GL thread)
    private int  ptsDomain = PTS_DOMAIN_NONE;     // @GuardedBy(GL thread)
    private boolean ptsSourceLogged = false;      // @GuardedBy(GL thread)
    private static final int PTS_DOMAIN_NONE = 0;
    private static final int PTS_DOMAIN_NANO = 2;

    private boolean consumeLatestImageAndBind() {
        ImageReader reader = cameraImageReader;
        if (reader == null) return false;
        Image image = null;
        HardwareBuffer hwBuffer = null;
        boolean transferredOwnership = false;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                irAcquireNullCount++;
                return false;
            }
            irAcquireOkCount++;
            // SOTA cross-vehicle sanity: log once per session if HAL-emitted
            // dims differ from the configured strip. Mosaic offsets, foveated
            // crop math, and encoder geometry all assume the configured size.
            // A silent mismatch (e.g., Tang HAL ignoring our 960 request and
            // emitting 720 anyway) would record corrupted mosaics — surface
            // it loudly so the operator can pick a different camera profile.
            if (!emittedDimsLogged) {
                int emittedW = image.getWidth();
                int emittedH = image.getHeight();
                if (emittedW != width || emittedH != height) {
                    logger.warn("HAL emitted " + emittedW + "x" + emittedH
                        + " but pipeline configured " + width + "x" + height
                        + " — mosaic/foveated geometry assumes the configured size."
                        + " Pick a different camera profile if this looks wrong.");
                } else {
                    logger.info("HAL emitted " + emittedW + "x" + emittedH
                        + " (matches configured " + width + "x" + height + ")");
                }
                emittedDimsLogged = true;
            }
            hwBuffer = image.getHardwareBuffer();
            if (hwBuffer == null) {
                logger.warn("Image.getHardwareBuffer() returned null — dropping frame");
                irBindFailCount++;
                return false;
            }
            // Crash-fix: hold cameraTextureLock across the rebind + prev-buffer
            // free so the AI lane cannot be mid-sampling the OLD backing buffer
            // when we swap the EGLImage / free the gralloc it points at.
            synchronized (cameraTextureLock) {
                boolean bound = HardwareBufferTextureBinder
                    .bindHardwareBufferToTextureNative(hwBuffer, cameraTextureId);
                if (!bound) {
                    logger.warn("bindHardwareBufferToTexture failed — dropping frame");
                    irBindFailCount++;
                    return false;
                }
                // Bind succeeded. NOW it's safe to release the previous image —
                // the texture is no longer pointing at it.
                releasePreviousBoundImage();
                // Transfer ownership of this image+hwBuffer into the held slots.
                currentBoundImage = image;
                currentBoundHwBuffer = hwBuffer;
            }
            // Resolve the per-frame PTS. The BYD DiLink HAL on the PRIVATE
            // ImageReader path returns a stuck, different-epoch value for
            // Image.getTimestamp(), so nextFrameTimestampNs() ignores it and
            // stamps System.nanoTime() from frame 0 (single clock domain) —
            // see that method for the full rationale.
            currentFrameTimestampNs = nextFrameTimestampNs(image);
            transferredOwnership = true;
            // Bump the per-bind seq counter so the AI-lane GL thread can
            // detect a fresh frame is ready. Bumped AFTER the bind succeeds
            // so we never advertise a half-bound texture.
            cameraFrameSeq.incrementAndGet();
            return true;
        } catch (Throwable t) {
            logger.warn("consumeLatestImageAndBind error: " + t.getMessage());
            irBindFailCount++;
            return false;
        } finally {
            // Only close locally if we did NOT transfer ownership to the
            // held slots. On the success path the held slots own the refs;
            // on failure paths we close immediately to release the slot.
            if (!transferredOwnership) {
                if (hwBuffer != null) {
                    try { hwBuffer.close(); } catch (Throwable ignored) {}
                }
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /**
     * SurfaceTexture-path equivalent of consumeLatestImageAndBind.
     * Pulls the freshest BufferQueue slot into cameraTextureId via
     * updateTexImage() and captures the SurfaceTexture timestamp for PTS.
     *
     * Mirrors esco's gl.C5920a / GL pipeline: the BYD HAL writes into the
     * SurfaceTexture-backed Surface, and updateTexImage rebinds the latest
     * frame to the EXTERNAL_OES texture. No gralloc handoff, so there's no
     * separate Image/HardwareBuffer ref to hold across GL cycles.
     *
     * Must be called on the GL thread.
     */
    private boolean consumeSurfaceTextureFrame() {
        SurfaceTexture st = cameraSurfaceTexture;
        if (st == null) return false;
        try {
            // Crash-fix: updateTexImage swaps the backing EGLImage of
            // cameraTextureId; hold cameraTextureLock so the AI lane isn't
            // mid-sampling the prior buffer when it's recycled.
            synchronized (cameraTextureLock) {
                st.updateTexImage();
            }
        } catch (IllegalStateException e) {
            // The BYD HAL can abandon the BufferQueue asynchronously (gear
            // transitions, AVM open/close). updateTexImage then throws ISE —
            // drop the frame gracefully rather than let it bubble up the GL loop.
            logger.warn("updateTexImage: BufferQueue abandoned by HAL, dropping frame: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            // BufferQueue can be in disconnected state during reopen — log and skip.
            logger.warn("updateTexImage failed: " + t.getMessage());
            return false;
        }
        // esco-parity: capture the producer's transform matrix. Forwarded
        // to the recorder + stream scaler + AI-lane downscaler so each
        // shader's uTexMatrix crops to the HAL's "live" sub-region. Without
        // this we sample any letterbox / chrome the HAL drew into the
        // producer surface.
        try {
            st.getTransformMatrix(currentTexMatrix);
        } catch (Throwable t) {
            // Fall back to identity — already initialised in the field.
            logger.warn("getTransformMatrix failed: " + t.getMessage());
        }
        // The HAL on this firmware publishes an identity matrix
        // (sx=1, sy=1, tx=0, ty=0). Our vertex layout maps NDC-bottom to
        // aTexCoord.y=0 and NDC-top to aTexCoord.y=1, while every
        // rearrangement shader treats `vTexCoord.y < 0.5` as "top half of
        // output". With identity texMatrix that conflict produces a
        // top-down flipped image on every consumer. When the matrix is
        // already a Y-flip (Android producer canonical, m[5]<0, m[13]=1),
        // the conventions line up. So: when m[5] >= 0, post-multiply a
        // Y-flip into the matrix so every shader sees the same Y-down
        // convention regardless of HAL build. (Esco hits the canonical
        // m[5]=-1 case so it doesn't need this; we have to.)
        if (currentTexMatrix[5] >= 0.0f) {
            currentTexMatrix[1]  = -currentTexMatrix[1];
            currentTexMatrix[5]  = -currentTexMatrix[5];
            currentTexMatrix[9]  = -currentTexMatrix[9];
            currentTexMatrix[13] =  1.0f - currentTexMatrix[13];
        }
        // Publish to the downscaler too. The probe shader runs on a
        // separate thread (AI-lane GL or probe GL), but the downscaler
        // instance is shared and copies the matrix internally.
        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            GpuDownscaler ds = downscaler;
            if (ds != null) ds.setTextureMatrix(currentTexMatrix);
            // FoveatedCropper does not consume the matrix — its samples are
            // already in producer-space UV via the role's corner+flip remap.
            // HighResPreviewSampler is lazy-allocated; only push when present.
            // The dialog endpoint is rare so freshness within a few frames
            // is fine, but per-frame upload is cheap (memcpy under lock).
            HighResPreviewSampler hr = highResSampler;
            if (hr != null) hr.setTextureMatrix(currentTexMatrix);
        }
        if (!firstFrameDimsLogged) {
            firstFrameDimsLogged = true;
            logFirstFrameDims(st);
        }
        // esco-parity: PTS comes from System.nanoTime() unconditionally.
        // esco's GL pipeline (C7411k) never trusts SurfaceTexture.getTimestamp;
        // it stamps frames at the moment of capture on the consumer thread.
        // The hwTs/latch state machine on the legacy ImageReader path exists
        // because gralloc's Image.getTimestamp returns a stuck value on this
        // HAL — same trap exists on SurfaceTexture, but esco proves nanoTime
        // is the right answer either way. Apply the same monotonic +1us
        // guard to satisfy MediaCodec's strictly-increasing PTS contract.
        long candidate = System.nanoTime();
        if (candidate <= lastAcceptedPtsNs) {
            candidate = lastAcceptedPtsNs + 1_000L;
        }
        lastAcceptedPtsNs = candidate;
        currentFrameTimestampNs = candidate;
        cameraFrameSeq.incrementAndGet();

        // dilink4 black-frame probe: every 30 frames, render a 4x4 region
        // of the OES texture into a 1x1 RGBA8 FBO and read back the pixel.
        // Tells us whether the buffer the HAL handed us has actual content
        // or is uniform/zero. Zero overhead for legacy fleet (gated on
        // USE_ESCO_SURFACE_TEXTURE_PATH).
        if (USE_ESCO_SURFACE_TEXTURE_PATH) {
            probeOesPixel();
        }
        return true;
    }

    // ==================== DILINK 4 PIXEL PROBE ====================
    //
    // Read one pixel from the OES texture every 30 frames to disambiguate
    // black-frame causes. Logs RGB. The probe samples (0.5, 0.5) of the
    // producer surface — middle of the configured strip. If the HAL is
    // delivering buffers with real content, R/G/B will vary frame to
    // frame. If the HAL is delivering all-zero buffers, R=G=B=0 forever.
    // If the HAL froze on a stale frame, R/G/B will be constant non-zero.

    private int probeFbo = 0;
    private int probeColorTex = 0;
    private int probeProgram = 0;
    private int probeAPosLoc = -1;
    private int probeATexLoc = -1;
    private int probeUTexSamplerLoc = -1;
    private int probeUTexMatrixLoc = -1;
    private final java.nio.FloatBuffer probeQuadVerts =
        java.nio.ByteBuffer.allocateDirect(16 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer();
    private final java.nio.ByteBuffer probeReadBuffer =
        java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder());
    private long probeFrameCount = 0L;
    private long probeNonZeroFrames = 0L;
    private long probeLastLogMs = 0L;
    private static final long PROBE_LOG_INTERVAL_MS = 5_000L;
    private static final int PROBE_EVERY_N_FRAMES = 30;

    private static final String PROBE_VS =
        "attribute vec2 aPos;\n" +
        "attribute vec2 aTex;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
        "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
        "}";

    private static final String PROBE_FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uTex;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(uTex, vTex);\n" +
        "}";

    private boolean ensureProbeResources() {
        if (probeFbo != 0) return true;
        try {
            // 1x1 RGBA8 color attachment.
            int[] tex = new int[1];
            android.opengl.GLES20.glGenTextures(1, tex, 0);
            probeColorTex = tex[0];
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, probeColorTex);
            android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0,
                android.opengl.GLES20.GL_RGBA, 1, 1, 0,
                android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, null);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR);
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, 0);

            int[] fbo = new int[1];
            android.opengl.GLES20.glGenFramebuffers(1, fbo, 0);
            probeFbo = fbo[0];
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, probeFbo);
            android.opengl.GLES20.glFramebufferTexture2D(android.opengl.GLES20.GL_FRAMEBUFFER,
                android.opengl.GLES20.GL_COLOR_ATTACHMENT0,
                android.opengl.GLES20.GL_TEXTURE_2D, probeColorTex, 0);
            int status = android.opengl.GLES20.glCheckFramebufferStatus(android.opengl.GLES20.GL_FRAMEBUFFER);
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
            if (status != android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.warn("probe FBO not complete: " + status);
                return false;
            }

            probeProgram = GlUtil.createProgram(PROBE_VS, PROBE_FS);
            probeAPosLoc = android.opengl.GLES20.glGetAttribLocation(probeProgram, "aPos");
            probeATexLoc = android.opengl.GLES20.glGetAttribLocation(probeProgram, "aTex");
            probeUTexSamplerLoc = android.opengl.GLES20.glGetUniformLocation(probeProgram, "uTex");
            probeUTexMatrixLoc = android.opengl.GLES20.glGetUniformLocation(probeProgram, "uTexMatrix");

            // Full-screen quad. NDC pos + UV (0..1).
            probeQuadVerts.put(new float[]{
                -1f, -1f,  0f, 0f,
                 1f, -1f,  1f, 0f,
                -1f,  1f,  0f, 1f,
                 1f,  1f,  1f, 1f,
            }).position(0);

            logger.info("dilink4 OES pixel probe initialized");
            return true;
        } catch (Throwable t) {
            logger.warn("ensureProbeResources failed: " + t.getMessage());
            probeFbo = 0;
            return false;
        }
    }

    private void probeOesPixel() {
        probeFrameCount++;
        if (probeFrameCount % PROBE_EVERY_N_FRAMES != 0) return;
        if (!ensureProbeResources()) return;
        if (cameraTextureId == 0) return;

        try {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, probeFbo);
            android.opengl.GLES20.glViewport(0, 0, 1, 1);
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f);
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT);

            android.opengl.GLES20.glUseProgram(probeProgram);

            android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0);
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            android.opengl.GLES20.glUniform1i(probeUTexSamplerLoc, 0);
            android.opengl.GLES20.glUniformMatrix4fv(probeUTexMatrixLoc, 1, false, currentTexMatrix, 0);

            probeQuadVerts.position(0);
            android.opengl.GLES20.glVertexAttribPointer(probeAPosLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, probeQuadVerts);
            probeQuadVerts.position(2);
            android.opengl.GLES20.glVertexAttribPointer(probeATexLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, probeQuadVerts);
            android.opengl.GLES20.glEnableVertexAttribArray(probeAPosLoc);
            android.opengl.GLES20.glEnableVertexAttribArray(probeATexLoc);

            android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4);

            android.opengl.GLES20.glDisableVertexAttribArray(probeAPosLoc);
            android.opengl.GLES20.glDisableVertexAttribArray(probeATexLoc);

            probeReadBuffer.position(0);
            android.opengl.GLES20.glReadPixels(0, 0, 1, 1,
                android.opengl.GLES20.GL_RGBA,
                android.opengl.GLES20.GL_UNSIGNED_BYTE, probeReadBuffer);

            int r = probeReadBuffer.get(0) & 0xFF;
            int g = probeReadBuffer.get(1) & 0xFF;
            int b = probeReadBuffer.get(2) & 0xFF;
            int a = probeReadBuffer.get(3) & 0xFF;

            if (r != 0 || g != 0 || b != 0) probeNonZeroFrames++;

            long now = System.currentTimeMillis();
            if (now - probeLastLogMs >= PROBE_LOG_INTERVAL_MS) {
                probeLastLogMs = now;
                long sampled = probeFrameCount / PROBE_EVERY_N_FRAMES;
                logger.info("OES-PROBE: frame=" + cameraFrameSeq.get()
                    + " sampled=" + sampled
                    + " nonZero=" + probeNonZeroFrames
                    + " last RGBA=(" + r + "," + g + "," + b + "," + a + ")");
            }
        } catch (Throwable t) {
            // Non-fatal — don't take down the render loop on a probe error.
            logger.warn("probeOesPixel error: " + t.getMessage());
        } finally {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
        }
    }

    /** First-frame diagnostic on the SurfaceTexture path. SurfaceTexture
     *  itself doesn't expose the producer-side W×H, but the transform
     *  matrix encodes the U/V scale the GL shader has to apply to sample
     *  the live region. With BYD HAL emitting the full configured strip,
     *  the diagonal entries (sx, sy) are very close to 1.0; if the HAL is
     *  cropping (e.g., delivering a 2x2 mosaic into a 5120x960 surface),
     *  the scale will be < 1.0 on one or both axes and that surfaces here
     *  before we waste cycles wondering why the recorded mosaic looks
     *  squashed. Cheap — one float[16] read per cold attach. */
    /** Effective HAL-emit dims derived from the SurfaceTexture transform
     *  matrix on the first frame after each (re)attach. {@code -1} until
     *  the first frame arrives. Volatile because the GL thread writes and
     *  HTTP / pipeline threads can read for diagnostics. */
    private volatile int halEffectiveWidth = -1;
    private volatile int halEffectiveHeight = -1;
    public int getHalEffectiveWidth() { return halEffectiveWidth; }
    public int getHalEffectiveHeight() { return halEffectiveHeight; }

    private void logFirstFrameDims(SurfaceTexture st) {
        try {
            float[] m = new float[16];
            st.getTransformMatrix(m);
            // Standard SurfaceTexture matrix: row-major OpenGL form, where
            // the diagonal m[0]/m[5] are X/Y scale and m[12]/m[13] are
            // X/Y translation. Sign of sy is usually negative (Y flip).
            float sx = m[0];
            float sy = m[5];
            float tx = m[12];
            float ty = m[13];
            // Effective sampled region in producer coords: |sx|×|sy| of
            // the surface, offset by (tx, ty). Scale 1.0 = full surface.
            float effW = Math.abs(sx) * (float) width;
            float effH = Math.abs(sy) * (float) height;
            halEffectiveWidth = Math.round(effW);
            halEffectiveHeight = Math.round(effH);
            logger.info(String.format(java.util.Locale.US,
                "First frame transform: sx=%.4f sy=%.4f tx=%.4f ty=%.4f → "
                + "effective sampled region ≈ %.0fx%.0f (configured %dx%d)",
                sx, sy, tx, ty, effW, effH, width, height));
            // Cross-correlate effective dims with the configured pipeline
            // viewport. esco's encoder adapts to whatever the HAL emits;
            // we instead pin a fixed encoder viewport (Seal: 2560×1920),
            // so a delta > 5% on either axis means the recorder is going
            // to stretch/squish content to fill the encoder. Surface the
            // warning loudly so the operator picks the right cameraMode
            // / camera profile rather than wondering why the recording
            // looks squashed.
            float wRatio = effW / (float) Math.max(1, width);
            float hRatio = effH / (float) Math.max(1, height);
            float wDeviation = Math.abs(wRatio - 1.0f);
            float hDeviation = Math.abs(hRatio - 1.0f);
            if (wDeviation > 0.05f || hDeviation > 0.05f) {
                logger.warn(String.format(java.util.Locale.US,
                    "ENCODER DIM MISMATCH: HAL effective %.0fx%.0f vs configured "
                    + "%dx%d (deviation: %.1f%% width, %.1f%% height). The "
                    + "recorder/streamer/AI lane will rescale content into the "
                    + "fixed viewport, which may stretch or squish the output. "
                    + "If this car ships a different mosaic shape, switch "
                    + "cameraMode (Default vs DiLink 4) or update the camera "
                    + "profile's panoWidth/panoHeight to match.",
                    effW, effH, width, height,
                    wDeviation * 100f, hDeviation * 100f));
            }
            if (Math.abs(Math.abs(sx) - 1.0f) > 0.01f
                    || Math.abs(Math.abs(sy) - 1.0f) > 0.01f) {
                logger.warn("HAL is delivering a CROPPED region of the surface — "
                    + "if you expected a 4-quadrant 5120x960 strip and effective "
                    + "is closer to half on either axis, the HAL is in 2x2 "
                    + "mosaic mode (or some non-strip layout). Consider trying "
                    + "a different previewIndex (cameraSurfaceMode) or cameraId.");
            }
        } catch (Throwable t) {
            logger.warn("First-frame transform-matrix probe failed: " + t.getMessage());
        }
    }

    /**
     * Resolve the per-frame PTS. Returns System.nanoTime() from frame 0 in a
     * single clock domain (the BYD HAL timestamp is not trusted on this fleet),
     * so no mid-stream clock-domain transition can ever corrupt the muxer's
     * rebase math. Output is strictly monotonic (MediaCodec requires it).
     *
     * The {@code image} argument is used only for a one-shot diagnostic log of
     * the (unused) HAL timestamp on the first frame.
     *
     * MUST be called from the GL thread (renderLoop).
     */
    private long nextFrameTimestampNs(Image image) {
        // SINGLE CLOCK DOMAIN — System.nanoTime() from frame 0, unconditionally.
        //
        // History: this path used to trust Image.getTimestamp() (HW sensor
        // clock) until it detected the value was stuck, then LATCHED to
        // System.nanoTime() after STUCK_HW_TS_FRAMES. That mid-stream HW→NANO
        // transition is the root of the "55 min – 1 hr clip duration" bug: the
        // two clocks have different epochs (HW ≈ uptime µs ~52ms; nanoTime ≈
        // CLOCK_MONOTONIC ns, billions), so when the encoder muxer rebases a
        // post-transition frame against an origin captured pre-transition it
        // records a multi-billion-µs gap as literal playback time. The
        // transition was especially likely right after a camera/encoder restart
        // (SD unmount, GL watchdog, ACC bounce) where the latch re-evaluates
        // while a muxer origin is already seeded.
        //
        // The latch machine ALWAYS ended at System.nanoTime() anyway — the BYD
        // PRIVATE-ImageReader HAL never honors the timestamp contract on this
        // fleet. The sibling esco SurfaceTexture path (renderLoop, search
        // "esco-parity") already stamps System.nanoTime() from frame 0 and its
        // comment documents that nanoTime "is the right answer either way."
        // Using it from the first frame here too eliminates the domain
        // transition entirely — there is exactly one clock domain for the whole
        // session, so no rebase can ever see a cross-epoch jump. The muxer-level
        // re-anchor guard in HardwareEventRecorderGpu.writeRebased remains as a
        // belt-and-suspenders net for any other producer.
        //
        // One-shot diagnostic: log the HAL timestamp we're deliberately NOT
        // using, so field logs still show what the sensor clock was doing.
        if (!ptsSourceLogged) {
            long hwTs = 0;
            try { hwTs = image.getTimestamp(); } catch (Throwable ignored) {}
            logger.info("PTS source: System.nanoTime() unconditional (single-domain). "
                + "HAL Image.getTimestamp() first value=" + hwTs + "ns (NOT used — "
                + "BYD PRIVATE-ImageReader HAL doesn't honor the timestamp contract)");
            ptsSourceLogged = true;
            ptsDomain = PTS_DOMAIN_NANO;
        }
        long candidate = System.nanoTime();
        // Monotonic guard. MediaCodec rejects non-increasing PTS; a duplicate
        // or rewind within the (now single) nanoTime domain gets bumped +1us.
        if (candidate <= lastAcceptedPtsNs) {
            candidate = lastAcceptedPtsNs + 1_000L;
        }
        lastAcceptedPtsNs = candidate;
        return candidate;
    }

    private void releasePreviousBoundImage() {
        if (currentBoundHwBuffer != null) {
            try { currentBoundHwBuffer.close(); } catch (Throwable ignored) {}
            currentBoundHwBuffer = null;
        }
        if (currentBoundImage != null) {
            try { currentBoundImage.close(); } catch (Throwable ignored) {}
            currentBoundImage = null;
        }
    }

    private boolean consumeLatestWindshieldImageAndBind() {
        ImageReader reader = windshieldImageReader;
        if (reader == null || windshieldTextureId == 0) return false;
        Image image = null;
        HardwareBuffer hwBuffer = null;
        boolean transferredOwnership = false;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return false;
            hwBuffer = image.getHardwareBuffer();
            if (hwBuffer == null) return false;
            boolean bound = HardwareBufferTextureBinder
                .bindHardwareBufferToTextureNative(hwBuffer, windshieldTextureId);
            if (!bound) return false;
            releasePreviousBoundWindshieldImage();
            windshieldBoundImage = image;
            windshieldBoundHwBuffer = hwBuffer;
            windshieldFrameReady = true;
            windshieldFrameCount++;
            windshieldLastFrameMs = System.currentTimeMillis();
            transferredOwnership = true;
            return true;
        } catch (Throwable t) {
            logger.warn("consumeLatestWindshieldImageAndBind error: " + t.getMessage());
            return false;
        } finally {
            if (!transferredOwnership) {
                if (hwBuffer != null) {
                    try { hwBuffer.close(); } catch (Throwable ignored) {}
                }
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void releasePreviousBoundWindshieldImage() {
        if (windshieldBoundHwBuffer != null) {
            try { windshieldBoundHwBuffer.close(); } catch (Throwable ignored) {}
            windshieldBoundHwBuffer = null;
        }
        if (windshieldBoundImage != null) {
            try { windshieldBoundImage.close(); } catch (Throwable ignored) {}
            windshieldBoundImage = null;
        }
    }

    /** Periodic diagnostic for the ImageReader path. Throttled to align with
     *  the 2-minute Stats log so it rides along instead of spamming. */
    private void maybeLogImageReaderDiag() {
        long now = System.currentTimeMillis();
        if (now - lastIrDiagLogMs < STATS_INTERVAL_MS) return;
        lastIrDiagLogMs = now;
        logger.info(String.format(
            "IR-diag: fire=%d acqOk=%d acqNull=%d bindFail=%d",
            irFireCount, irAcquireOkCount, irAcquireNullCount, irBindFailCount));
    }
    
    /**
     * Main render loop - distributes frames to recording and AI lanes.
     */
    private void renderLoop() {
        if (!running) {
            return;
        }

        try {
            // Wait for new frame (hardware sync). Skip the wait if either
            // path already signaled while we were processing the previous
            // frame — otherwise the unconditional wait() would miss that
            // notify and park us until the NEXT HAL fire, capping FPS.
            // imagePending is set by the ImageReader OnImageAvailable cb;
            // stFramePending is set by SurfaceTexture.onFrameAvailable. The
            // path that's inactive simply never sets its flag.
            synchronized (frameSync) {
                if (!imagePending && !stFramePending) {
                    try {
                        // FIX H4: 250 ms timeout (was 100 ms). The watchdog
                        // owns frame-stall detection at its own 5 s cadence;
                        // the timeout here only paces how often we re-check
                        // running.get(). 100 ms produced ~10 idle wakeups/s
                        // when the camera HAL was paused (e.g. during ACC-off
                        // teardown latency); 250 ms cuts that to ~4/s with no
                        // user-visible behaviour change.
                        frameSync.wait(250);
                    } catch (InterruptedException e) {
                        // Continue
                    }
                }
                imagePending = false;
                stFramePending = false;
            }

            if (!running) {
                return;
            }

            // Update watchdog heartbeat
            lastGlThreadHeartbeat = System.currentTimeMillis();
            maybeLogImageReaderDiag();

            // SOTA: Skip frame processing if camera is yielded to native app,
            // not yet open, or being torn down/reopened by the daemon thread
            // (reopenCamera/restartCameraAfterError). The restartInProgress
            // gate is essential — without it the GL thread can race the
            // daemon thread's close and block in updateTexImage() against a
            // dead BufferQueue, freezing the GL thread until the watchdog
            // kills the process.
            if (cameraYielded || cameraObj == null || restartInProgress.get()) {
                // GL thread stays alive but doesn't touch camera — waiting for re-acquire
                return;
            }

            // Bind the latest camera frame to cameraTextureId. Two paths:
            //   - esco SurfaceTexture: updateTexImage() pulls the most recent
            //     BufferQueue slot into the EXTERNAL_OES texture. PTS comes
            //     from SurfaceTexture.getTimestamp().
            //   - legacy ImageReader: acquireLatestImage + getHardwareBuffer
            //     + glEGLImageTargetTexture2DOES on the gralloc buffer.
            // Both run on the GL thread (current EGL context). If no new
            // frame is ready (spurious wakeup or notify race), return — the
            // finally re-posts the loop and we wait again.
            long stageT0 = System.nanoTime();
            if (USE_ESCO_SURFACE_TEXTURE_PATH) {
                if (cameraSurfaceTexture == null) {
                    return;
                }
                if (!consumeSurfaceTextureFrame()) {
                    return;
                }
            } else {
                if (cameraImageReader == null) {
                    return;
                }
                if (!consumeLatestImageAndBind()) {
                    return;
                }
            }
            long stageAfterAcquireNs = System.nanoTime();
            frameCounter++;
            lastFrameTime = System.currentTimeMillis();
            firstFrameReceived = true;
            consecutiveContentionStalls = 0;  // Frames flowing — clear stall counter
            consecutiveZeroFrameRestarts = 0; // Real frame arrived — reopen succeeded; clear escalation counter

            // Dead-slot fallback (issue #170) paid off: this camera id is the
            // one that actually streams on this vehicle. Persist it so the next
            // boot opens it directly instead of spending another 25s wedged on
            // the profile default. Fires once per successful fallback — the
            // flag is cleared before the callback so a throw can't re-arm it.
            if (deadSlotFallbackPendingPersist) {
                deadSlotFallbackPendingPersist = false;
                int recoveredId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                logger.info("Dead-slot fallback CONFIRMED: camera id " + recoveredId
                    + " is streaming — persisting as the validated panoramic id");
                if (probeCallback != null) {
                    try {
                        probeCallback.onCameraFound(recoveredId, cameraSurfaceMode);
                    } catch (Throwable t) {
                        logger.warn("Dead-slot persist callback failed: " + t.getMessage());
                    }
                }
            }

            // SOTA: Full-matrix auto-probe at frame 15 (~2 sec).
            // Sweeps camera IDs 0-5 × surface modes 0-5 to find the first
            // combination that produces panoramic image data. Each combo gets
            // 15 frames to warm up before pixel readback.
            if (frameCounter == 15 && downscaler != null && !skipFrameValidation) {
                try {
                    byte[] probe = downscaler.readPixels(cameraTextureId, 8, 8);
                    boolean hasData = false;
                    if (probe != null) {
                        for (int i = 0; i < Math.min(probe.length, 192); i++) {
                            if ((probe[i] & 0xFF) > 10) { hasData = true; break; }
                        }
                    }
                    int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                    boolean isPanoramic = width >= 5000;
                    logger.info("Camera ID " + currentId + " probe: " + 
                        (hasData ? "HAS DATA" : "BLACK") +
                        " | resolution=" + width + "x" + height +
                        " | type=" + (isPanoramic ? "PANORAMIC" : "SINGLE") +
                        " | surfaceMode=" + cameraSurfaceMode);
                    
                    if (hasData && isPanoramic) {
                        // Track this camera as having real data (for fallback if strip check fails)
                        lastDataCameraId = currentId;
                        
                        // During auto-probe: accept the first camera with non-black panoramic data.
                        // The 5120x960 resolution IS the panoramic strip identifier on BYD — no other
                        // camera output uses this resolution with real image data. The luma-based
                        // strip check was producing false negatives in low-light/uniform scenes.
                        if (autoProbeCameras) {
                            logger.info("Auto-probe: SELECTED camera ID " + currentId + 
                                " (panoramic data confirmed, surfaceMode=" + cameraSurfaceMode + ")");
                            autoProbeCameras = false;
                            probeStartId = -1;
                            probeComplete = true;
                            lastDataCameraId = -1;
                            logger.info("Probe complete — recording/streaming/AI lanes now active");
                            if (probeCallback != null) {
                                probeCallback.onCameraFound(currentId, cameraSurfaceMode);
                            }
                        } else {
                            // Not in auto-probe mode — this is the frame-15 check for a saved config.
                            // Camera has data at panoramic resolution — it's working correctly.
                            // No further validation needed (skipFrameValidation handles saved configs,
                            // but this path covers the default camera ID 1 on first boot).
                            probeComplete = true;
                        }
                    } else if (autoProbeCameras) {
                        // Advance to next combination in the matrix
                        advanceProbeToNext(currentId);
                    } else if (!hasData) {
                        // Saved config gave black frames at frame 15. This could be:
                        // 1. HAL warmup (normal — wait longer)
                        // 2. OEM dashcam contention (transient)
                        // 3. Genuinely wrong camera ID (BmmCameraInfo returned wrong value)
                        //
                        // Don't re-probe immediately (causes OEM dashcam "no signal").
                        // Instead, schedule a second check at frame 50 (~5s). If still black
                        // at that point, the saved config is genuinely wrong and we re-probe.
                        logger.warn("Frame 15 readback BLACK for cam=" + currentId +
                            ", surfaceMode=" + cameraSurfaceMode +
                            " — will recheck at frame 50 before deciding");
                    }
                } catch (Exception e) {
                    logger.warn("Camera probe failed: " + e.getMessage());
                }
            }
            
            // Frame 50 recheck (~5s): if frame 15 was black, verify again.
            // By frame 50 the HAL has definitely warmed up. If still black, the saved
            // config is genuinely wrong (BmmCameraInfo returned incorrect ID).
            // Only then trigger a re-probe — this is rare and justified.
            if (frameCounter == 50 && !autoProbeCameras && !skipFrameValidation && downscaler != null) {
                try {
                    byte[] probe = downscaler.readPixels(cameraTextureId, 8, 8);
                    boolean hasData = false;
                    if (probe != null) {
                        for (int i = 0; i < Math.min(probe.length, 192); i++) {
                            if ((probe[i] & 0xFF) > 10) { hasData = true; break; }
                        }
                    }
                    if (!hasData) {
                        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                        logger.warn("Frame 50 STILL BLACK for cam=" + currentId +
                            " — saved config is wrong, starting re-probe");
                        autoProbeCameras = true;
                        probeComplete = false;
                        probeNextCameraId = 0;
                        probeNextSurfaceMode = 0;
                        lastDataCameraId = -1;
                        advanceProbeToNext(currentId);
                    } else {
                        // Camera has non-black data at frame 50 — it's working.
                        // Persist as validated so next restart skips all frame checks.
                        // BUT: don't overwrite if user has a manual override set — they may have
                        // changed the camera ID in the UI and it hasn't taken effect yet.
                        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                        logger.info("Frame 50 recheck: camera ID " + currentId + " confirmed working");
                        probeComplete = true;
                        try {
                            org.json.JSONObject existingCam = com.overdrive.app.config.UnifiedConfigManager
                                .loadConfig().optJSONObject("camera");
                            boolean hasManualOverride = existingCam != null && existingCam.optBoolean("manualOverride", false);
                            int savedId = existingCam != null ? existingCam.optInt("probedCameraId", -1) : -1;
                            
                            // Only write back if there's no manual override, or if the manual override
                            // matches what we're currently running (user's choice is already applied)
                            if (!hasManualOverride || savedId == currentId) {
                                com.overdrive.app.camera.CameraConfigResolver.persistPanoramicProbe(
                                    currentId,
                                    cameraSurfaceMode,
                                    width,
                                    height,
                                    true,
                                    false);
                            } else {
                                logger.info("Skipping config write — manual override exists (saved=" + savedId + ", running=" + currentId + ")");
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    logger.warn("Frame 50 recheck failed: " + e.getMessage());
                }
            }

            // SOTA: Gate all consumer passes until probe finds a working camera.
            // Without this, the encoder records BLACK frames, the stream shows garbage,
            // and the AI lane processes empty images during the probe sweep.
            if (!probeComplete) {
                // Still probing — don't feed consumers. Heartbeat already
                // updated above. Re-post handled by the finally block.
                return;
            }

            // PASS 1: Recording (Zero-Copy GPU Path)
            // SOTA: Always render to encoder (for pre-record circular buffer)
            GpuMosaicRecorder localRecorder = recorder;
            HardwareEventRecorderGpu localEncoder = encoder;
            long stageBeforeMosaicNs = System.nanoTime();
            // Recorder lane master gate around ALL of PASS 1A (windshield consume
            // + recorder draw + drain). When the camera is kept warm ONLY for
            // blind-spot (PASS 1C, no encoder, no recording mode), the H.265
            // recorder lane is switched OFF: skip the windshield 2nd-camera
            // consume/drain AND the mosaic draw/encode so we burn zero Venus/GPU
            // on footage nothing will flush. A windshield camera that was already
            // STARTED is torn down in the `else if (windshieldStarted)` branch
            // below (it would otherwise gralloc-stall undrained) and re-opened by
            // updateWindshieldCameraOnGlThread() when PASS 1A resumes.
            //
            // SAFETY OVERRIDE — `|| localRecorder.isRecording()`: this single
            // GpuMosaicRecorder instance is SHARED by every recording consumer
            // (RecordingModeManager modes AND the ACC-off SurveillanceEngineGpu
            // sentry path, which holds the same recorder via setRecorder() and
            // triggers clips through recorder.triggerEventRecording() WITHOUT
            // routing through GpuSurveillancePipeline.startRecording()'s lane
            // re-assert — likewise OEM dashcam). Gating PASS 1A purely on
            // recorderLaneEnabled would let a sentry/OEM clip that started while
            // the camera was BS-only-warmed (lane OFF) record BLACK. Drawing
            // whenever the recorder is ACTUALLY recording closes every such
            // bypass at this one gate, by construction — recorderLaneEnabled then
            // only governs the idle pre-record-ring feed (no live clip open).
            if (localRecorder != null && (recorderLaneEnabled || localRecorder.isRecording())) {
                // Publish per-frame transform matrix to recorder before draw.
                // Cheap (16-float arraycopy); matches esco's per-frame
                // getTransformMatrix → uTexMatrix flow.
                if (USE_ESCO_SURFACE_TEXTURE_PATH) {
                    localRecorder.setTextureMatrix(currentTexMatrix);
                }
                // Pass the HAL-provided sensor timestamp straight through to
                // eglPresentationTimeANDROID. Replaces the old TBC EMA path:
                // the encoder now produces PTS values that exactly mirror real
                // camera cadence, eliminating the rubber-banding/snapback the
                // EMA introduced at 15+ fps.
                updateWindshieldCameraOnGlThread();
                // Drain the windshield ImageReader EVERY recorder frame, not
                // only when windshieldPending is set. The pending flag is a
                // lost-update race: the GL thread reads pending==true, drains,
                // then clears it — but if the OnImageAvailable listener (its
                // own thread) fires a NEW frame between the drain and the
                // clear, our windshieldPending=false clobbers that set. The
                // frame is never drained; with maxImages=4 the gralloc slots
                // fill, the BYD AVM HAL producer stalls, no further callbacks
                // fire, and the top band freezes — classically on the first
                // frame. acquireLatestImage() returns null when nothing new
                // arrived, so the unconditional drain is a cheap no-op that
                // just keeps the last frame bound on idle iterations.
                // (Ported from Overdrive-release PR #97.) The stall guard
                // below remains as a safety net for genuine frame-stoppage
                // (handle contention / HAL pause) that draining can't prevent.
                if (windshieldStarted) {
                    consumeLatestWindshieldImageAndBind();
                    windshieldPending = false;
                }
                // Windshield stall guard. If the feed has gone quiet past the
                // threshold while still "started", the bound HardwareBuffer is
                // stale — keep drawing it and the dashcam top band freezes on
                // one frame. Drop windshieldFrameReady so drawFrame composites
                // the LIVE 360 front instead (never a frozen image), and make
                // one throttled close+reopen attempt to recover the feed.
                // All windshield fields are GL-thread-confined, so no lock.
                if (windshieldStarted && windshieldFrameReady
                        && windshieldLastFrameMs > 0
                        && (System.currentTimeMillis() - windshieldLastFrameMs)
                            > WINDSHIELD_STALL_THRESHOLD_MS) {
                    long stalledMs = System.currentTimeMillis() - windshieldLastFrameMs;
                    logger.warn("Windshield feed stalled " + stalledMs
                        + "ms (frames=" + windshieldFrameCount
                        + ") — falling back to 360 front + scheduling reopen");
                    // Stop trusting the stale frame immediately.
                    windshieldFrameReady = false;
                    // Throttled reopen: close + restart on this (GL) thread.
                    long nowReopen = System.currentTimeMillis();
                    if (nowReopen - windshieldLastReopenMs > WINDSHIELD_REOPEN_MIN_INTERVAL_MS) {
                        windshieldLastReopenMs = nowReopen;
                        try {
                            stopWindshieldCameraOnGlThread();
                            // windshieldEnabled is still true; updateWindshield
                            // on the next iteration will re-run start. Clearing
                            // the open-failed latch lets that retry proceed.
                            windshieldOpenFailed = false;
                        } catch (Throwable t) {
                            logger.warn("Windshield reopen (stop phase) failed: " + t.getMessage());
                        }
                    }
                }
                // Recorder draw stride gate (Proximity Guard low-rate pre-record).
                // We draw into the encoder surface only on selected frames; on
                // skipped frames MediaCodec simply receives no input, lowering
                // the effective recording rate. The windshield drain above
                // intentionally runs EVERY frame (gralloc-slot starvation guard)
                // and is outside this gate. Stride 1 = every frame (default).
                // The counter advances per consumed camera frame so the cadence
                // is uniform; frame 0 always draws so a freshly-applied stride
                // starts with a frame rather than a gap.
                // Recorder draw stride gate (Proximity Guard low-rate pre-record):
                // draw into the encoder surface only every stride-th frame; on
                // skipped frames MediaCodec gets no input, lowering the effective
                // recording rate. Stride 1 = every frame (default).
                int stride = recorderFrameStride;
                boolean drawThisFrame = stride <= 1 || (recorderStrideCounter % stride) == 0;
                recorderStrideCounter++;
                if (drawThisFrame) {
                    localRecorder.drawFrame(cameraTextureId, windshieldTextureId,
                        windshieldStarted && windshieldFrameReady, currentFrameTimestampNs);

                    // CRITICAL: Drain encoder immediately after frame submission
                    // This prevents eglSwapBuffers from blocking when encoder buffers fill up
                    if (localEncoder != null) {
                        localEncoder.drainEncoder();
                    }
                } else if (localEncoder != null) {
                    // Even on a skipped draw, keep draining any already-queued
                    // output so the codec's output buffers can't back up while
                    // we're feeding it sparsely.
                    localEncoder.drainEncoder();
                }

                // RECOVERY: If encoder surface died (EGL_BAD_SURFACE after prolonged use),
                // reinitialize the encoder and reconnect the recorder.
                // P1 #9: keep using localRecorder/localEncoder captured above.
                // pipeline.stop() runs on the daemon thread and can null
                // this.recorder/this.encoder concurrently; re-reading the fields
                // here would NPE.
                if (localRecorder.needsReinit() && localEncoder != null) {
                    logger.warn("Encoder surface lost - reinitializing encoder...");
                    // Extend the GL watchdog window: encoder.release() joins
                    // the drainer (up to 2s) plus MediaCodec stop/release —
                    // the bare 3s GL timeout is not enough headroom.
                    // P1 #11: CAS so a concurrent reopenCamera (daemon thread)
                    // can't race; if another restart is already in flight,
                    // skip — it'll re-fire on the next frame.
                    if (!restartInProgress.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        // Full teardown of recorder GL resources. Without this,
                        // shader programs (programId, overlayProgramId) and the
                        // overlay texture (overlayTextureId) leak on every
                        // reinit, since recorder.init() only frees the encoder
                        // surface, not the programs/textures it then re-creates.
                        localRecorder.release();
                        localEncoder.release();
                        localEncoder.init();
                        localRecorder.init(eglCore, localEncoder);
                        // Re-wire the contention probe — release() restored
                        // the inert default, and we don't want a contention
                        // event right after recovery to silently ignore the
                        // BYD AVM's signal-loss risk.
                        localRecorder.setHalContentionProbe(() -> {
                            BydCameraCoordinator c = cameraCoordinator;
                            return c != null && c.isNativeAppActive();
                        });
                        localRecorder.clearReinitFlag();
                        logger.info("Encoder reinitialized successfully after surface loss");
                    } catch (Exception reinitEx) {
                        logger.error("Encoder reinit failed: " + reinitEx.getMessage());
                        // If reinit fails, force process restart — EGL context is likely corrupt
                        logger.error("CRITICAL: Encoder reinit failed, forcing process restart");
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        System.exit(0);
                    } finally {
                        restartInProgress.set(false);
                    }
                }
            } else if (windshieldStarted) {
                // PASS 1A is skipped (recorder lane OFF and nothing recording —
                // e.g. camera kept warm ONLY for blind-spot). The recorder is the
                // ONLY consumer of the 2nd (windshield) AVMCamera, so with PASS 1A
                // gated off nothing drains its 4-slot ImageReader — the gralloc
                // slots fill, the HAL producer stalls, and a 2nd physical camera
                // stays powered on the shared SDM665 bus for the whole idle
                // window. Tear it down here; updateWindshieldCameraOnGlThread()
                // re-opens it the moment PASS 1A resumes (windshieldEnabled is
                // unchanged). Only reached when the OEM windshield dual-cam feature
                // is on (default off), so normally a no-op.
                stopWindshieldCameraOnGlThread();
            }

            // PASS 1B: Streaming (Parallel Zero-Copy GPU Path)
            // Only runs if streaming is enabled - uses separate encoder at lower resolution
            // Capture local refs to avoid NPE from concurrent pipeline shutdown
            com.overdrive.app.streaming.GpuStreamScaler localStreamScaler = streamScaler;
            HardwareEventRecorderGpu localStreamEncoder = streamEncoder;
            if (localStreamScaler != null && localStreamEncoder != null) {
                if (USE_ESCO_SURFACE_TEXTURE_PATH) {
                    localStreamScaler.setTextureMatrix(currentTexMatrix);
                }
                localStreamScaler.drawFrame(cameraTextureId);
                localStreamEncoder.drainEncoder();
            }

            // PASS 1C: Blind-spot lane (views 7/8). Independent scaler fed from the
            // SAME cameraTextureId, read-only — like PASS 1B but owned by the
            // dedicated blind-spot pipeline. NATIVE path: the scaler's render target
            // is a SurfaceControl layer (GPU → screen), so there is NO encoder —
            // localBsEncoder is null and drawFrame's swapBuffers IS the on-screen
            // present; we only drain when an encoder is present (legacy/none now).
            // Local snapshot so a concurrent disableBlindSpot() nulling the field
            // can't NPE mid-frame.
            //
            // VISIBILITY GATE: only render when the SurfaceControl layer is actually
            // shown (turn signal active / debug-preview). While the layer is hidden,
            // rendering is pure GPU waste — eglSwapBuffers still rasterizes 1280×960
            // into the SC surface, SurfaceFlinger just discards the buffer. On the
            // Adreno 610 single shader core this doubles GPU load (recording mosaic +
            // blind-spot mosaic) and pins the clock at 820 MHz. Gating on visibility
            // reduces the "enabled but idle" cost to zero; the very next frame after
            // the turn trigger sets bsLayerVisible=true picks up rendering (~66ms
            // worst-case latency, imperceptible).
            com.overdrive.app.streaming.GpuStreamScaler localBsScaler = bsStreamScaler;
            HardwareEventRecorderGpu localBsEncoder = bsStreamEncoder;
            if (localBsScaler != null && bsLayerVisible) {
                if (USE_ESCO_SURFACE_TEXTURE_PATH && localBsScaler == bsStreamScaler) {
                    localBsScaler.setTextureMatrix(currentTexMatrix);
                }
                localBsScaler.drawFrame(cameraTextureId);
                bsDiagFrames++;
                // Drain only if an encoder is wired (legacy path). Native path has
                // none — the swapBuffers in drawFrame presented straight to screen.
                // Identity re-check guards a concurrent disable nulling the field.
                if (localBsEncoder != null && bsStreamEncoder == localBsEncoder) {
                    localBsEncoder.drainEncoder();
                }
            } else if (localBsScaler == null) {
                bsDiagSkipScaler++;
            } else {
                bsDiagSkipHidden++;
            }
            // Throttled BS render diagnostic (~5s). Logs whether PASS 1C is actually
            // drawing the BS lane and, when not, WHY — so "card shows but black" is
            // triageable from the log. cameraTextureId==0 here means the external
            // camera texture isn't allocated → drawFrame would sample nothing = black.
            // od.isReady()==false means the view-7/8 sampler coefficients are zero-filled
            // (license/authorize gate) = black even with frames + a valid texture.
            {
                long nowDiagMs = android.os.SystemClock.elapsedRealtime();
                if (nowDiagMs - bsDiagLastLogMs >= 5000L
                        && (bsDiagFrames > 0 || bsDiagSkipScaler > 0 || bsDiagSkipHidden > 0)) {
                    boolean odReady = false;
                    try { odReady = com.overdrive.app.od.Od.INSTANCE.isReady(); } catch (Throwable ignored) {}
                    logger.info("BS render diag: drawn=" + bsDiagFrames
                            + " skipNoScaler=" + bsDiagSkipScaler
                            + " skipHidden=" + bsDiagSkipHidden
                            + " camTex=" + cameraTextureId
                            + " bsVisible=" + bsLayerVisible
                            + " odReady=" + odReady);
                    bsDiagLastLogMs = nowDiagMs;
                    bsDiagFrames = 0; bsDiagSkipScaler = 0; bsDiagSkipHidden = 0;
                }
            }

            // PASS 2 + 3: AI lane.
            //
            // SOTA: All AI-lane GL work (mosaic readback + foveated crop)
            // moved off this thread to AiLaneGl, which owns a separate EGL
            // context in the same share group. We just publish "a new
            // camera frame is ready" via the seq counter and let that
            // thread pick it up. Stays decoupled from eglSwap cadence even
            // when the Adreno's hardware queue is busy with YOLO OpenCL —
            // any glReadPixels stall now lands on the AI thread, not here.
            //
            // glFlush ensures texture writes from this context are visible
            // to the share-group sibling. Without it, the AI thread may
            // sample stale bytes despite holding a "fresh" texture id —
            // share-group visibility for textures is per-EGL-flush.
            //
            // <b>Gate on sentry.isActive().</b> The AI lane has exactly two
            // consumers — V2 motion mosaic readback and the foveated crop
            // mailbox — and both are surveillance-only. When sentry is off
            // (every ACC-ON recording mode: CONTINUOUS / DRIVE_MODE /
            // PROXIMITY_GUARD) nobody reads the AI lane's output, so
            // publishing frames to it just burns CPU: glFlush is a kernel
            // ioctl into msm_kgsl, the AtomicInteger lazySet plus
            // postQueued CAS bounce a cache line cross-core, the
            // Handler.post wakes the AI-lane thread for a no-op
            // processOnce that immediately exits via the sentry.isActive()
            // checks inside it. Pre-v19 the entire AI block here was
            // gated on sentry.isActive(); the v19 refactor that moved AI
            // work to the dedicated GL thread inadvertently dropped this
            // gate, costing ~30-40% extra encoder-thread CPU during
            // ACC-ON CONTINUOUS recording. Restoring the gate here puts
            // ACC-ON load back at v17/v18 levels.
            // AI lane notify (publish-only; AI work runs on AiLaneGl's
            // own thread).
            SurveillanceEngineGpu localSentry = sentry;
            // AI lane is needed only when surveillance is active AND actually
            // consuming AI output. CONTINUOUS (always-record) ACC-OFF mode sets
            // active=true but uses no motion/YOLO/mosaic-readback — recording is
            // fed by the GL→encoder chain directly — so excluding it keeps the
            // lane (thread + EGL context + ~6.5MB cropper) from being created and
            // per-frame-fed for nothing in that sub-mode.
            boolean aiLaneNeeded = localSentry != null && localSentry.isActive()
                    && !localSentry.isContinuousMode();
            // Lazy lifecycle: bring the AI lane UP on the first surveillance-
            // active frame, tear it DOWN (freeing thread + EGL context + ~6.5MB
            // GPU + ~2.8MB CPU) when surveillance disarms. Both run here on the
            // GL thread with eglCore current — the only safe place to create the
            // share-group context + alloc the cropper FBO/PBO.
            if (aiLaneNeeded) {
                ensureAiLaneStarted();
            } else if (aiLaneGl != null) {
                releaseAiLaneOnGlThread();
            }
            AiLaneGl localAiLane = aiLaneGl;
            if (localAiLane != null && localAiLane.isRunning() && aiLaneNeeded) {
                android.opengl.GLES20.glFlush();
                localAiLane.notifyFrame(cameraFrameSeq.get());
            }

            // Per-stage timing roll-up. Track only the worst frame per 30 s
            // window so the log line stays bounded; the worst frame is what
            // crosses the encoder backpressure threshold. AI readback/submit
            // timers were removed: post-Tier-1 the AI work runs on a separate
            // GL thread, so the deltas here would always be zero.
            long stageEndNs = System.nanoTime();
            long stageTotalNs   = stageEndNs - stageT0;
            long stageAcquireNs = stageAfterAcquireNs - stageT0;
            long stageMosaicNs  = stageEndNs - stageBeforeMosaicNs;
            stageWindowFrames++;
            if (stageTotalNs > stageWorstTotalNs) {
                stageWorstTotalNs   = stageTotalNs;
                stageWorstAcquireNs = stageAcquireNs;
                stageWorstMosaicNs  = stageMosaicNs;
            }
            long nowMs = System.currentTimeMillis();
            if (stageTimingWindowStartMs == 0) {
                stageTimingWindowStartMs = nowMs;
            } else if (nowMs - stageTimingWindowStartMs >= STAGE_TIMING_LOG_INTERVAL_MS) {
                logger.info(String.format(
                        "Stage(worst/30s, encoder-thread): total=%dms acq=%dms mosaic+swap=%dms (frames=%d)",
                        stageWorstTotalNs / 1_000_000,
                        stageWorstAcquireNs / 1_000_000,
                        stageWorstMosaicNs / 1_000_000,
                        stageWindowFrames));
                stageWorstTotalNs = 0;
                stageWorstAcquireNs = 0;
                stageWorstMosaicNs = 0;
                stageWindowFrames = 0;
                stageTimingWindowStartMs = nowMs;
            }

            // Log stats periodically (every 2 minutes, time-based).
            // Reports the *windowed* FPS (frames since the last stats log) instead
            // of the lifetime average — otherwise a stall during one window drags
            // the running mean down forever and masks recovery in later windows.
            long now = System.currentTimeMillis();
            if (now - lastStatsTime >= STATS_INTERVAL_MS) {
                long windowMs = (lastStatsTime == 0) ? (now - startTime) : (now - lastStatsTime);
                int windowFrames = frameCounter - lastStatsFrameCount;
                float fps = windowMs > 0 ? (windowFrames * 1000.0f) / windowMs : 0f;
                measuredFps = fps;

                long aiProc = aiLaneWorker != null ? aiLaneWorker.getProcessedFrames() : 0;
                long aiDrop = aiLaneWorker != null ? aiLaneWorker.getDroppedFrames() : 0;
                long uptimeS = (now - startTime) / 1000;
                logger.info(String.format(
                        "Stats: %d frames (window), %.1f FPS (target=%d), uptime=%ds, aiProcessed=%d, aiDropped=%d",
                        windowFrames, fps, targetFps, uptimeS, aiProc, aiDrop));
                if (aiLaneWorker != null) {
                    aiLaneWorker.resetCounters();
                }

                lastStatsTime = now;
                lastStatsFrameCount = frameCounter;
            }

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            logger.error("Render loop error: " + msg, e);
        } finally {
            // Schedule next frame in finally so any `return` inside the try
            // (e.g., consumeLatestImageAndBind() returning false on a frame
            // where no new image is ready) still re-posts the loop. Without
            // this, the GL thread stops iterating and the watchdog kills us.
            if (running) {
                glHandler.post(this::renderLoop);
            }
        }
    }
    
    /**
     * Verifies that the camera is producing a real panoramic strip (4 distinct views)
     * rather than a single camera stretched or AVM bird's-eye view.
     *
     * A real panoramic strip has 4 cameras stitched side by side. Each quadrant shows
     * a different scene. We verify by reading pixel samples from each quadrant and
     * checking that they have significantly different luma values.
     *
     * Uses the downscaler's 8x8 readback. Columns 0-1=Q0, 2-3=Q1, 4-5=Q2, 6-7=Q3.
     */
    private boolean verifyPanoramicStrip(byte[] probe8x8) {
        if (probe8x8 == null || probe8x8.length < 192) return false;
        int[] qLuma = new int[4];
        int[] qCnt = new int[4];
        int[] qMin = {255, 255, 255, 255};
        int[] qMax = {0, 0, 0, 0};
        int totalNonBlack = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int idx = (y * 8 + x) * 3;
                int r = probe8x8[idx] & 0xFF, g = probe8x8[idx+1] & 0xFF, b = probe8x8[idx+2] & 0xFF;
                int luma = (r + g*2 + b) / 4;
                int q = x / 2;
                qLuma[q] += luma; qCnt[q]++;
                if (luma < qMin[q]) qMin[q] = luma;
                if (luma > qMax[q]) qMax[q] = luma;
                if (luma > 10) totalNonBlack++;
            }
        }
        for (int q = 0; q < 4; q++) { if (qCnt[q] > 0) qLuma[q] /= qCnt[q]; }
        
        // Primary check: luma difference between quadrant pairs.
        // A real panoramic strip has 4 cameras showing different scenes.
        int diffPairs = 0;
        for (int i = 0; i < 4; i++) for (int j = i+1; j < 4; j++) if (Math.abs(qLuma[i]-qLuma[j]) > 15) diffPairs++;
        boolean isStrip = diffPairs >= 2;
        
        // Secondary check: if all quadrants have real (non-black) data with internal
        // variance, this is a real camera feed even if the scenes look similar.
        // This handles the common case of a parked car in a garage/at night where
        // all 4 cameras see similar dark scenes (low inter-quadrant difference)
        // but each quadrant still has texture/detail (intra-quadrant variance).
        if (!isStrip && totalNonBlack >= 48) {  // At least 75% of pixels are non-black
            int quadrantsWithVariance = 0;
            for (int q = 0; q < 4; q++) {
                // Each quadrant has internal texture (not a flat solid color)
                if (qMax[q] - qMin[q] >= 3) quadrantsWithVariance++;
            }
            // Accept if all quadrants have real data (non-black) and at least 3 have
            // internal variance. This distinguishes a real 4-camera feed from a
            // synthetic AVM bird's-eye view (which would have large inter-quadrant
            // differences) or a single stretched camera (which would have identical
            // min/max patterns across all quadrants).
            if (quadrantsWithVariance >= 3) {
                isStrip = true;
                logger.info("Strip accepted via secondary check: " + quadrantsWithVariance + 
                    " quadrants with variance, " + totalNonBlack + "/64 non-black pixels");
            }
        }
        
        logger.info("Strip check: Q0=" + qLuma[0] + " Q1=" + qLuma[1] + " Q2=" + qLuma[2] + " Q3=" + qLuma[3] +
                " diffPairs=" + diffPairs + " → " + (isStrip ? "STRIP" : "NOT_STRIP"));
        return isStrip;
    }

    /**
     * SOTA: Advance to the next camera ID during probe.
     * Surface mode 0 is confirmed working on all tested models — only probe camera IDs 0-5.
     * 
     * @param skipId Camera ID to skip (the one we just tested). -1 to start fresh.
     */
    private void advanceProbeToNext(int skipId) {
        // Close current camera cleanly
        if (cameraObj != null) {
            try {
                closeCameraForPath(cameraObj);
            } catch (Exception closeEx) {
                logger.warn("Error closing camera for probe: " + closeEx.getMessage());
            }
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
            }
        }
        
        // CRITICAL: Let the BYD camera HAL settle between close and next open.
        // Without this delay, rapid camera cycling overwhelms the HAL service
        // and triggers a system watchdog reboot.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        
        // Probe camera IDs 0-5 with surface mode 0 (confirmed working on all models)
        boolean found = false;
        while (probeNextCameraId <= MAX_CAMERA_ID) {
            int tryId = probeNextCameraId;
            probeNextCameraId++;
            
            // Skip the ID we just tested
            if (tryId == skipId) {
                continue;
            }
            
            logger.info("Auto-probe: trying camera ID " + tryId + 
                " [" + (tryId + 1) + "/" + (MAX_CAMERA_ID + 1) + "]");
            
            cameraIdOverride = tryId;
            cameraSurfaceMode = 0;  // Surface mode 0 confirmed working
            frameCounter = 0;
            lastStatsFrameCount = 0;
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // Recreate SurfaceTexture — HAL won't deliver continuous frames
            // to a Surface previously connected to a different camera/mode
            recreateCameraSurface();
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            try {
                // Brief pause before opening next camera — HAL needs time to release resources
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                
                startCamera();
                // Setup event callback (only for AVMCamera path — binder service handles its own events)
                if (cameraCoordinator != null && cameraObj != null) {
                    cameraCoordinator.setupEventCallback(cameraObj);
                }
                found = true;
                break;
            } catch (Exception e) {
                // Camera ID doesn't exist or can't open — skip to next
                logger.info("Auto-probe: camera ID " + tryId + " failed to open: " + e.getMessage());
                cameraObj = null;
                // Delay before trying next combo to avoid HAL overload
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                continue;
            }
        }
        
        if (!found) {
            // If we found at least one camera with data during probe, switch back to it.
            // This prevents the "probe failed" state from leaving us on a black camera.
            if (lastDataCameraId >= 0 && lastDataCameraId != cameraIdOverride) {
                logger.info("Auto-probe: no verified strip found, falling back to camera ID " + 
                    lastDataCameraId + " (last known data source)");
                cameraIdOverride = lastDataCameraId;
                cameraSurfaceMode = 0;
                frameCounter = 0;
                lastStatsFrameCount = 0;
                lastGlThreadHeartbeat = System.currentTimeMillis();
                recreateCameraSurface();
                lastGlThreadHeartbeat = System.currentTimeMillis();
                try {
                    Thread.sleep(500);
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                } catch (Exception e) {
                    logger.error("Fallback camera open failed: " + e.getMessage());
                }
                // Persist this as a fallback so next restart doesn't re-probe
                try {
                    com.overdrive.app.camera.CameraConfigResolver.persistPanoramicProbe(
                        lastDataCameraId,
                        0,
                        width,
                        height,
                        true,
                        true);
                    logger.info("Persisted fallback camera ID " + lastDataCameraId + " for next launch");
                } catch (Exception ex) {
                    logger.warn("Failed to persist fallback camera config: " + ex.getMessage());
                }
            } else {
                logger.error("Auto-probe: exhausted all " + 
                    (MAX_CAMERA_ID + 1) + 
                    " camera IDs — no working panoramic camera found");
            }
            autoProbeCameras = false;
            probeStartId = -1;
            lastDataCameraId = -1;
            // Ungate consumers even on failure — better to record whatever we have
            // than to stay permanently blocked
            probeComplete = true;
            logger.warn("Probe complete (fallback mode) — unblocking consumers");
        }
    }

    /**
     * Starts the watchdog thread that monitors GL thread health.
     * 
     * If the GL thread hangs (e.g., eglSwapBuffers blocks), the watchdog
     * will call System.exit(0) to force a process restart, since EGL
     * contexts cannot be recovered from a blocked thread.
     */
    private void startWatchdog() {
        lastGlThreadHeartbeat = System.currentTimeMillis();
        firstFrameReceived = false;
        
        watchdogThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(1000);  // Check every second
                    
                    long now = System.currentTimeMillis();
                    long timeSinceHeartbeat = now - lastGlThreadHeartbeat;
                    
                    // Use extended timeout until the first camera frame arrives.
                    // The BYD panoramic camera HAL can take 5-8 seconds to deliver
                    // the first frame after open. During this period the GL thread
                    // is blocked on frameSync.wait(100) which still updates the
                    // heartbeat, but if the HAL is slow to even accept the surface
                    // (e.g., I/O contention from MediaScanner broadcasts), the
                    // heartbeat can stall. Killing the process here just causes a
                    // restart loop that makes things worse.
                    // Also use extended timeout during camera restart — the GL thread
                    // is busy with close/reopen operations and heartbeat updates are
                    // interleaved but may not be frequent enough for the normal timeout.
                    long effectiveTimeout = (firstFrameReceived && !restartInProgress.get())
                            ? GL_THREAD_TIMEOUT_MS
                            : GL_THREAD_WARMUP_TIMEOUT_MS;
                    
                    if (timeSinceHeartbeat > effectiveTimeout) {
                        logger.error( "CRITICAL: GL thread blocked for " + timeSinceHeartbeat + 
                                "ms - forcing process restart" +
                                (firstFrameReceived ? "" : " (during camera warmup)"));
                        
                        // Try to flush logs before exit
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                        
                        // Exit code 0 triggers restart loop in DaemonLauncher wrapper.
                        // EGL contexts cannot be recovered from a blocked thread.
                        System.exit(0);
                    }
                    
                    // DEAD-SLOT ESCAPE (issue #170) — must be checked BEFORE the
                    // frame-health monitor below, whose `lastFrameTime > 0` gate a
                    // never-streamed camera can by definition never satisfy.
                    //
                    // Conditions, all required:
                    //   - firstFrameReceived == false: this camera has produced
                    //     nothing since the pipeline started. Once any frame has
                    //     arrived the normal stall/restart machinery is armed and
                    //     owns recovery, so we stay out of its way for good.
                    //   - !cameraYielded: a native app (reverse cam, AVM parking
                    //     view) holding the HAL is a legitimate reason to see no
                    //     frames. Switching IDs would not help and could steal a
                    //     slot from the OEM app.
                    //   - cameraObj != null: we actually hold an open camera, so
                    //     "no frames" means the stream is dead rather than that we
                    //     simply never opened anything.
                    //   - no restart or HAL-recovery already in flight, so we never
                    //     race a close/open we don't own.
                    if (!firstFrameReceived
                            && !cameraYielded
                            && cameraObj != null
                            && !restartInProgress.get()
                            && !halRecoveryEscalated
                            && !deadSlotWalkExhausted
                            && lastCameraStartTime > 0
                            && (now - lastCameraStartTime) > FIRST_FRAME_DEAD_SLOT_MS
                            && glHandler != null) {
                        long deadFor = now - lastCameraStartTime;
                        logger.warn("DEAD SLOT: camera id "
                            + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID)
                            + " opened but delivered ZERO frames in " + deadFor
                            + "ms — the HAL accepted the open but cannot stream this slot"
                            + " (BYD 2602/pano_h reports a 0x0 preview here). Trying next"
                            + " panoramic candidate.");
                        glHandler.post(this::advanceToNextCandidateCameraId);
                    }

                    // SOTA: Frame health monitor — detect stalled camera feed
                    // If GL thread is alive but no new frames for FRAME_STALL_THRESHOLD_MS,
                    // the camera HAL may be starved or dead.
                    // Decision is contention-aware: if native app is active, use longer
                    // threshold and require consecutive stalls before yielding.
                    if (!cameraYielded && lastFrameTime > 0 && 
                        timeSinceHeartbeat < GL_THREAD_TIMEOUT_MS) {
                        long timeSinceFrame = now - lastFrameTime;
                        
                        // Use longer threshold when native app is active — transient
                        // CPU/IO stalls shouldn't trigger a yield that interrupts recording
                        boolean nativeActive = cameraCoordinator != null && 
                            cameraCoordinator.isNativeAppActive();
                        long stallThreshold = nativeActive 
                            ? FRAME_STALL_CONTENTION_THRESHOLD_MS 
                            : FRAME_STALL_THRESHOLD_MS;
                        
                        // Post-(re)open warmup grace. The BYD AVM HAL takes ~5-8s
                        // to deliver the first frame after any camera open (see GL
                        // watchdog comment). The 4s stall threshold otherwise trips
                        // before frame 1 can arrive — tearing the camera down and
                        // reopening it in a loop that never escapes warmup (the
                        // sentry->drive recording-blackout root cause when ACC turns
                        // on while surveillance is still armed). Suppress the stall
                        // until the grace window (measured from the most recent open)
                        // elapses. lastFrameTime is deliberately NOT reset here: if
                        // the HAL is genuinely dead, timeSinceFrame keeps growing and
                        // the real stall path fires on the first tick past the grace
                        // window instead of being perpetually re-deferred.
                        long timeSinceCameraStart = lastCameraStartTime > 0
                                ? now - lastCameraStartTime
                                : Long.MAX_VALUE;
                        if (timeSinceFrame > stallThreshold && halRecoveryEscalated) {
                            // A warmup-routed recovery (onHalRecoveryNeeded) is in
                            // flight. Don't post bare restarts on top of it — the
                            // pipeline clears this latch via notePipelineRestarted()
                            // once its full teardown + warmup restart completes.
                            logger.info("Frame stall while HAL-recovery escalation in flight — "
                                + "deferring to warmup-routed restart.");
                        } else if (timeSinceFrame > stallThreshold
                                && timeSinceCameraStart < FRAME_STALL_WARMUP_GRACE_MS) {
                            logger.info("Frame stall suppressed — within post-open warmup grace ("
                                + timeSinceCameraStart + "ms < " + FRAME_STALL_WARMUP_GRACE_MS
                                + "ms; BYD HAL first-frame latency is 5-8s). Not restarting yet.");
                        } else if (timeSinceFrame > stallThreshold) {
                            logger.warn("FRAME STALL: No frames for " + timeSinceFrame + "ms" +
                                (nativeActive ? " (native app active)" : ""));
                            // Reset lastFrameTime to prevent repeated triggers
                            lastFrameTime = now;

                            // ESCO-PARITY: dilink4 has NO frame-stall-driven
                            // restart. esco's PanoCameraRecord (`gl/C5920a.java`,
                            // `PanoCameraRecordService.java:174-200`) only
                            // restarts on actual `onCameraError` HAL events,
                            // capped at 5 retries with backoff. There is NO
                            // 4 s no-frame watchdog in esco. On parked cars
                            // the HAL routinely pauses frame emission (no
                            // consumer, AVC reaped) and a stall-driven
                            // close+reopen produces the all-zero frames the
                            // user reports. The HAL onEvent path
                            // (BydCameraCoordinator → onCameraError) still
                            // catches genuine fatal events (8/1000/1002) and
                            // routes through the throttled restart at
                            // line 547 (DILINK4_ERROR_RESTART_MIN_INTERVAL_MS).
                            boolean dilink4SkipStallRestart = USE_ESCO_SURFACE_TEXTURE_PATH;
                            if (dilink4SkipStallRestart) {
                                logger.info("Frame stall on dilink4 — NOT restarting (esco-parity, await real HAL error)");
                            } else if (cameraCoordinator != null) {
                                if (nativeActive) {
                                    // Contention path: require consecutive stalls before yielding
                                    consecutiveContentionStalls++;
                                    if (consecutiveContentionStalls >= CONTENTION_STALL_COUNT_TO_YIELD) {
                                        logger.warn("Consecutive contention stalls: " +
                                            consecutiveContentionStalls + " — yielding camera");
                                        consecutiveContentionStalls = 0;
                                        cameraCoordinator.onFrameStallDetected();
                                    } else {
                                        logger.info("Contention stall " + consecutiveContentionStalls +
                                            "/" + CONTENTION_STALL_COUNT_TO_YIELD +
                                            " — waiting for more evidence before yielding");
                                    }
                                } else {
                                    // No native app — this is a HAL issue, restart camera
                                    consecutiveContentionStalls = 0;
                                    logger.info("Frame stall is HAL issue — restarting camera");
                                    if (glHandler != null) {
                                        glHandler.post(() -> restartCameraAfterError());
                                    }
                                }
                            } else {
                                // No coordinator — just restart
                                if (glHandler != null) {
                                    glHandler.post(() -> restartCameraAfterError());
                                }
                            }
                        } else if (nativeActive && timeSinceFrame < 500) {
                            // Frames are flowing despite native app — reset stall counter
                            consecutiveContentionStalls = 0;
                        }
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "GL-Watchdog");
        
        watchdogThread.setDaemon(true);
        watchdogThread.start();
        
        logger.info( "GL thread watchdog started (timeout=" + GL_THREAD_TIMEOUT_MS + "ms, " +
            "warmupTimeout=" + GL_THREAD_WARMUP_TIMEOUT_MS + "ms, " +
            "frameStall=" + FRAME_STALL_THRESHOLD_MS + "ms, " +
            "cameraId=" + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID) + ", " +
            "probe=" + (autoProbeCameras ? "ACTIVE" : "OFF") + ")");
    }

    /**
     * Dead-slot escape (issue #170): move to the next panoramic camera candidate
     * after the current one opened but never produced a frame.
     *
     * <p>MUST run on the GL thread — it mutates {@code cameraIdOverride} and
     * hands off to {@link #restartCameraAfterError()}, which owns the
     * close/reopen sequence (including the encoder drainer, event-callback
     * re-registration and {@code onPostReacquire}). Reusing that path rather
     * than open-coding a second close/open keeps the two in lockstep.
     */
    private void advanceToNextCandidateCameraId() {
        // Re-validate on the GL thread: the watchdog observed this up to a tick
        // ago, and a first frame (or someone else's restart) may have landed in
        // between. Cheap insurance against tearing down a camera that just
        // started working.
        if (firstFrameReceived || cameraYielded || cameraObj == null
                || restartInProgress.get() || halRecoveryEscalated
                || deadSlotWalkExhausted) {
            return;
        }

        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
        int nextId = PanoCameraFallbackOrder.next(
            resolveHalPanoCameraIdHint(), deadSlotTriedCameraIds);

        if (nextId == PanoCameraFallbackOrder.NO_CANDIDATE) {
            // Latch so this logs once rather than every watchdog tick. Leaving
            // the camera on its current ID is deliberate: the normal restart /
            // HAL-recovery path stays armed, and a user can always pin a slot
            // explicitly via POST /api/surveillance/config {"manualCameraId":N}.
            deadSlotWalkExhausted = true;
            logger.error("Dead-slot walk exhausted — tried camera ids "
                + deadSlotTriedCameraIds + " and none delivered a frame. Leaving"
                + " camera on id " + currentId + " for the normal restart path."
                + " If this vehicle streams on another slot, pin it with"
                + " POST /api/surveillance/config {\"manualCameraId\": N}.");
            return;
        }

        logger.warn("Dead-slot fallback: switching panoramic camera id "
            + currentId + " -> " + nextId + " (tried so far: "
            + deadSlotTriedCameraIds + ")");

        cameraIdOverride = nextId;
        // The zero-frame reopen evidence was gathered against a DIFFERENT
        // physical slot, so it says nothing about this one. Without this reset
        // the escalation counter would reach its threshold mid-walk and hand
        // off to the warmup-routed full restart, which reopens the same dead
        // slot — exactly the loop this method exists to break.
        consecutiveZeroFrameRestarts = 0;
        deadSlotFallbackPendingPersist = true;

        restartCameraAfterError();
    }

    /**
     * Panoramic camera ID from the HAL's own tag map, resolved once and cached.
     *
     * <p>Returns -1 when {@code BmmCameraInfo} is unavailable — the norm on
     * DiLink 3.0, where only the native {@code BMMCamProp} holds the mapping and
     * no Java API exposes it. {@link PanoCameraFallbackOrder} handles that by
     * falling through to the raw-strip slot.
     */
    private int resolveHalPanoCameraIdHint() {
        if (halPanoCameraIdHint == Integer.MIN_VALUE) {
            int discovered;
            try {
                discovered = AvmCameraHelper.discoverPanoCameraId();
            } catch (Throwable t) {
                logger.warn("BmmCameraInfo pano-id lookup failed: " + t.getMessage());
                discovered = -1;
            }
            halPanoCameraIdHint = discovered;
            logger.info("Dead-slot fallback: BmmCameraInfo panoramic hint = "
                + (discovered >= 0 ? String.valueOf(discovered) : "unavailable"));
        }
        return halPanoCameraIdHint;
    }

    /**
     * SOTA: Yields the camera to the native BYD AVM app.
     * 
     * Called on GL thread when contention is detected (frame stall while native
     * app is active). Finalizes any active recording FIRST to prevent MP4 corruption,
     * then does a clean camera close.
     * 
     * The GL render loop continues running but skips frame processing while yielded.
     * Camera is re-acquired when onCloseCamera fires from IBYDCameraService.
     */
    private void yieldCameraInternal() {
        logger.info("Yielding camera to native AVM app...");

        // CRITICAL: Finalize active recording BEFORE closing camera.
        // FIX: Same bounded-yield pattern as restartCameraAfterError — onPreYield()
        // does muxer.stop() + FUSE rename which can wedge indefinitely on a flaky
        // USB/SD mount. Run on a worker thread with heartbeat ticking.
        if (yieldListener != null) {
            final java.util.concurrent.atomic.AtomicBoolean yieldDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<Exception> yieldError =
                new java.util.concurrent.atomic.AtomicReference<>(null);
            Thread yieldThread = new Thread(() -> {
                try {
                    yieldListener.onPreYield();
                } catch (Exception e) {
                    yieldError.set(e);
                } finally {
                    yieldDone.set(true);
                }
            }, "PreYield-Yield");
            yieldThread.setDaemon(true);
            yieldThread.start();

            long yieldStart = System.currentTimeMillis();
            long yieldTimeout = 8000;
            while (!yieldDone.get() &&
                   (System.currentTimeMillis() - yieldStart) < yieldTimeout) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                lastGlThreadHeartbeat = System.currentTimeMillis();
            }

            if (yieldDone.get()) {
                Exception err = yieldError.get();
                if (err != null) {
                    logger.warn("Pre-yield callback error: " + err.getMessage());
                } else {
                    logger.info("Pre-yield: recording finalized");
                }
            } else {
                logger.error("Pre-yield: onPreYield TIMED OUT after " + yieldTimeout
                    + "ms — FUSE mount wedged, proceeding with yield "
                    + "(recording may be truncated)");
            }
        }
        
        // Detach streaming components to stop drainer threads
        if (streamScaler != null || streamEncoder != null) {
            clearStreamingComponents();
        }
        
        // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera.
        // The drainer thread calls MediaCodec.dequeueOutputBuffer() which internally
        // accesses the camera's SurfaceTexture buffer queue via EGL. If we destroy
        // the camera (and its native mutex) while the drainer is mid-dequeue,
        // we get: FORTIFY: pthread_mutex_lock called on a destroyed mutex
        if (encoder != null) {
            encoder.stopDrainerForCameraClose();
        }
        if (streamEncoder != null) {
            streamEncoder.stopDrainerForCameraClose();
        }
        
        if (cameraObj != null) {
            closeCameraForPath(cameraObj);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
                cameraCoordinator.notifyPosCloseCamera();
            }
            logger.info("Camera yielded — GL pipeline idle, waiting for onCloseCamera");
        }
        
        // Restart drainer threads after camera is closed (for pre-record buffer)
        if (encoder != null) {
            encoder.restartDrainerAfterCameraClose();
        }

        // audit avc-yield (round 5): mark the consumer (ImageReader Surface)
        // as needing recreation — the BYD HAL just released our Surface, so
        // the FIRST reacquire attempt must re-allocate it, but subsequent
        // backoff retries should reuse the freshly-created consumer.
        if (!USE_ESCO_SURFACE_TEXTURE_PATH) {
            consumerNeedsRecreation = true;
            logger.info("Yield: marked consumerNeedsRecreation=true for next reacquire");
        }

        // Start the yield-state re-acquire poller. Without this, nothing
        // observes the native app closing — the IBYDCameraUser callback path
        // is disabled and the GL frame-stall watchdog can't fire (lastFrameTime
        // is frozen because the render loop early-returns while yielded).
        startYieldPoller();
    }

    /**
     * GL-thread runnable for the camera re-acquire after a contention yield.
     *
     * <p>Extracted from the original onReacquireCamera GL-handler runnable so
     * that on a transient failure (AVMCamera.open returning false, reflection
     * NoSuchMethodError, attachSurfaceTextureToCamera failing because the BYD
     * HAL is still finalising the prior native release) we can re-post the
     * exact same flow with a backoff schedule rather than relying on the
     * yield poller to observe a second native-app transition — that
     * transition was already consumed by the first checkNativeAppActive →
     * handleNativeAppClosed call, so the poller alone could never recover
     * (audit avc-yield round 2 RESIDUAL).
     *
     * <p>Retry schedule: {@link #REACQUIRE_RETRY_DELAYS_MS} (2 s, 5 s, 10 s).
     * After all retries fail, self-restart the daemon process via
     * {@code System.exit(0)} the way the GL watchdog does at line 2400 /
     * 2767 — wrapper script respawns and full cold-boot warmup runs.
     */
    private void attemptReacquireOnGlThread() {
        // audit avc-yield (round 7, finding pending-retry-not-cancelled):
        // capture the epoch on entry. The scheduling site (failure catch
        // below) snapshots epoch into a local closure when it postDelayed-
        // schedules a retry; that wrapper checks the snapshot vs the
        // current epoch before invoking us. This top-of-method guard is a
        // belt-and-braces fence in case a caller reaches us via a non-
        // wrapped post path. Currently no caller does, but we log to
        // catch regressions.
        //
        // audit avc-yield (round 8, finding yield-mid-backoff-cascades-to-exit):
        // if a fresh yield is in progress (coordinator reports yielded, or
        // local cameraYielded flag is true), abort silently. Do NOT close
        // cameraObj, do NOT call startCamera, do NOT bump
        // reacquireRetryCount, do NOT schedule retries. The yield poller
        // (started by yieldCameraInternal) is the authoritative recovery
        // path during yield. This prevents a Yield #1 backoff retry from
        // colliding with Yield #2 and counter-bumping into System.exit(0).
        if ((cameraCoordinator != null && cameraCoordinator.isCameraYielded())
                || cameraYielded) {
            logger.info("Reacquire: yield-in-progress detected on entry "
                + "(coordYielded="
                + (cameraCoordinator != null && cameraCoordinator.isCameraYielded())
                + ", localYielded=" + cameraYielded
                + ") — aborting attempt; yield poller owns recovery");
            return;
        }
        try {
            // audit avc-yield (round 5, finding belt-and-braces-leak): drop the
            // retryCount>0 gate. If cameraObj is non-null on entry to a fresh
            // reacquire (e.g. a belt-and-braces poller restart raced with the
            // first attempt and we re-entered with an already-open handle), we
            // must always close+null it to avoid leaking a second cameraObj.
            if (cameraObj != null) {
                Object stale = cameraObj;
                cameraObj = null;
                logger.warn("Reacquire (attempt=" + reacquireRetryCount.get()
                    + "): clearing stale cameraObj before startCamera (always-close)");
                try {
                    closeCameraForPath(stale);
                } catch (Throwable th) {
                    logger.warn("Reacquire: closeCameraForPath on stale obj errored: "
                        + th.getMessage());
                }
            }

            // audit avc-yield (round 3, finding 8): on the legacy ImageReader
            // path the BYD HAL won't deliver continuous frames to a Surface
            // that was previously connected to a different camera instance —
            // only the first frame arrives, then the stream freezes (~5s
            // before the stall watchdog kicks restartCameraAfterError). Mirror
            // restartCameraAfterError's recreateCameraSurface() step so the
            // post-yield reacquire is self-healing instead of structurally
            // dropping ~5-10s of recording every contention cycle.
            // SurfaceTexture path (dilink4) is gated out at the top of this
            // file's slice constraints; recreateCameraSurface itself routes
            // by USE_ESCO_SURFACE_TEXTURE_PATH so it stays safe either way.
            // audit avc-yield (round 5, finding consumer-recreate-every-attempt):
            // gate on consumerNeedsRecreation — only the first attempt of a
            // yield cycle needs the recreate; backoff retries reuse the just-
            // created consumer.
            if (!USE_ESCO_SURFACE_TEXTURE_PATH && consumerNeedsRecreation) {
                try {
                    logger.info("Reacquire: recreating ImageReader consumer "
                        + "before reopen (legacy HAL frozen-frame guard)");
                    recreateCameraSurface();
                    consumerNeedsRecreation = false;
                    lastGlThreadHeartbeat = System.currentTimeMillis();
                } catch (Throwable th) {
                    logger.warn("Reacquire: recreateCameraSurface failed — "
                        + "proceeding with stale consumer: " + th.getMessage());
                }
            } else if (!USE_ESCO_SURFACE_TEXTURE_PATH) {
                logger.info("Reacquire: skipping consumer recreate "
                    + "(consumerNeedsRecreation=false, retry attempt)");
            }

            startCamera();
            // audit avc-yield (round 3, finding 10): startCamera early-returns
            // (no throw) when cameraCoordinator.isCameraYielded() is true,
            // leaving cameraObj==null. Treat that as failure here so the
            // backoff/retry path runs instead of silently returning success
            // with a null camera handle.
            if (cameraObj == null) {
                throw new IllegalStateException(
                    "startCamera returned without opening (cameraObj==null) — "
                    + "likely yielded gate hit; treating as reacquire failure");
            }
            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.resetEventCallbackState();
                cameraCoordinator.setupEventCallback(cameraObj);
            }

            // Restart encoder drainer thread — it was stopped during
            // onPreYield → stopRecording → closeEventRecording.
            // Without this, triggerEventRecording creates a muxer but
            // no thread dequeues frames from the encoder to write them.
            if (encoder != null) {
                encoder.restartDrainerAfterCameraClose();
            }

            // SOTA: Notify pipeline to resume recording
            if (yieldListener != null) {
                try {
                    yieldListener.onPostReacquire();
                    logger.info("Post-reacquire: recording resumed");
                } catch (Exception e) {
                    logger.warn("Post-reacquire callback error: " + e.getMessage());
                }
            }

            // Success — reset retry budget for the next yield cycle.
            int finalCount = reacquireRetryCount.get();
            if (finalCount > 0) {
                logger.info("Camera re-acquired after " + finalCount
                    + " retry attempt(s)");
            } else {
                logger.info("Camera re-acquired after contention yield");
            }
            reacquireRetryCount.set(0);
            // audit avc-yield (round 7, finding pending-retry-not-cancelled):
            // a prior failed attempt in this cycle may have scheduled a
            // postDelayed retry. Now that we've succeeded, cancel any pending
            // retry runnable AND bump the epoch so any runnable that survives
            // the removeCallbacks race short-circuits at the epoch gate
            // (avoids spurious double-teardown of the just-resumed camera).
            pendingReacquireEpoch.incrementAndGet();
            Runnable staleRetry = pendingReacquireRetry;
            pendingReacquireRetry = null;
            if (staleRetry != null && glHandler != null) {
                try {
                    glHandler.removeCallbacks(staleRetry);
                    logger.info("Reacquire success: cancelled pending "
                        + "postDelayed retry runnable");
                } catch (Throwable th) {
                    logger.warn("Reacquire success: removeCallbacks errored: "
                        + th.getMessage());
                }
            }
        } catch (Exception e) {
            int attemptIdx = reacquireRetryCount.get();
            logger.error("Failed to re-acquire camera (attempt " + attemptIdx
                + "): " + e.getMessage());

            // audit avc-yield (round 8, finding yield-mid-backoff-cascades-to-exit):
            // belt-and-braces — if a fresh yield landed during the body
            // above (e.g. native app re-engaged just before we threw the
            // "yielded gate hit" sentinel), do NOT bump counter and do NOT
            // schedule a retry. Let the yield poller drive recovery. Without
            // this, the failure path here would still cascade to System.exit
            // even if the top-of-method gate raced ahead.
            if ((cameraCoordinator != null && cameraCoordinator.isCameraYielded())
                    || cameraYielded) {
                logger.warn("Reacquire: failure caught while yielded "
                    + "(coordYielded="
                    + (cameraCoordinator != null && cameraCoordinator.isCameraYielded())
                    + ", localYielded=" + cameraYielded
                    + ") — suppressing retry schedule; yield poller owns recovery");
                return;
            }

            if (attemptIdx < REACQUIRE_RETRY_DELAYS_MS.length) {
                long delay = REACQUIRE_RETRY_DELAYS_MS[attemptIdx];
                // audit avc-yield (round 5, finding cross-thread-race):
                // CAS-bump the counter. If a peer thread (onReacquireCamera
                // listener) already reset to 0, our CAS fails — that's OK,
                // the reset path will own scheduling.
                boolean bumped = reacquireRetryCount.compareAndSet(
                    attemptIdx, attemptIdx + 1);
                if (!bumped) {
                    logger.warn("Reacquire: CAS bump failed (attempt=" + attemptIdx
                        + ", current=" + reacquireRetryCount.get()
                        + ") — concurrent reset detected, skipping retry schedule");
                    return;
                }
                logger.warn("Reacquire scheduling backoff retry "
                    + reacquireRetryCount.get() + "/" + REACQUIRE_RETRY_DELAYS_MS.length
                    + " in " + delay + "ms");
                if (glHandler != null && running) {
                    // audit avc-yield (round 7, finding pending-retry-not-cancelled):
                    // capture epoch snapshot + store runnable handle so a
                    // poller-driven success path (onReacquireCamera) or a
                    // sibling success path can cancel us before we re-enter
                    // and unconditionally tear down the just-resumed camera.
                    final int scheduledEpoch = pendingReacquireEpoch.get();
                    Runnable retryRunnable = new Runnable() {
                        @Override
                        public void run() {
                            int currentEpoch = pendingReacquireEpoch.get();
                            if (currentEpoch != scheduledEpoch) {
                                logger.info("Reacquire backoff retry: epoch "
                                    + "mismatch (scheduled=" + scheduledEpoch
                                    + ", current=" + currentEpoch
                                    + ") — superseded by poller/success path,"
                                    + " skipping stale retry");
                                return;
                            }
                            // Clear our own handle before running so a peer
                            // cancellation observes null instead of stale.
                            if (pendingReacquireRetry == this) {
                                pendingReacquireRetry = null;
                            }
                            attemptReacquireOnGlThread();
                        }
                    };
                    pendingReacquireRetry = retryRunnable;
                    glHandler.postDelayed(retryRunnable, delay);
                } else {
                    logger.warn("Reacquire: glHandler null or pipeline stopped — "
                        + "cannot schedule retry");
                }
                // Also restart poller as belt-and-braces last-ditch path.
                // Audit notes this likely won't fire (edge already consumed),
                // but it costs nothing and protects against the corner case
                // where the native app re-opens and re-closes the camera
                // during our backoff window.
                //
                // audit avc-yield (round 9, finding
                // round-8-yield-gate-deadlocks-retry): do NOT set
                // cameraYielded=true here. The round-8 entry gate at the
                // top of attemptReacquireOnGlThread aborts when
                // cameraYielded is true; setting it here would deadlock our
                // own postDelayed retry runnable (scheduled just above) the
                // moment it fires. The poller's `while (cameraYielded ...)`
                // guard means it exits immediately if cameraYielded is
                // false, which is correct — the postDelayed runnable is the
                // primary recovery path, the poller is fallback only.
                logger.info("Reacquire scheduled retry — NOT setting cameraYielded; postDelayed runnable is the primary recovery path");
                try {
                    startYieldPoller();
                } catch (Throwable th) {
                    logger.warn("Reacquire: poller fallback start errored: "
                        + th.getMessage());
                }
            } else {
                // All retries exhausted — give up and let the watchdog
                // wrapper respawn the daemon. Mirrors line 2400/2767 GL
                // watchdog escape hatch.
                logger.error("Reacquire: all "
                    + REACQUIRE_RETRY_DELAYS_MS.length
                    + " retries exhausted — exiting daemon for wrapper respawn");
                reacquireRetryCount.set(0);
                try {
                    System.exit(0);
                } catch (Throwable th) {
                    logger.warn("System.exit(0) failed: " + th.getMessage());
                }
                // audit avc-yield (round 5, finding system-exit-failure):
                // belt-and-braces hard fallback. If System.exit(0) was
                // intercepted (SecurityManager) or returned without effect,
                // halt(0) bypasses shutdown hooks entirely. Last-resort
                // killProcess(myPid) if even halt is blocked.
                try {
                    logger.warn("Reacquire: System.exit(0) returned — "
                        + "invoking Runtime.halt(0) hard fallback");
                    Runtime.getRuntime().halt(0);
                } catch (Throwable th) {
                    logger.warn("Runtime.halt(0) failed: " + th.getMessage());
                }
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable th) {
                    logger.warn("Process.killProcess(myPid) failed: " + th.getMessage());
                }
            }
        }
    }

    /**
     * Spawns a daemon thread that polls BydCameraCoordinator.checkNativeAppActive()
     * every 5 s while {@code cameraYielded == true}. checkNativeAppActive's
     * polling branch fires handleNativeAppClosed → onReacquireCamera when the
     * native app releases the camera, which clears cameraYielded and re-opens
     * via the GL handler. The poller exits as soon as cameraYielded flips
     * false (i.e. the re-acquire path took over) or running flips false (stop).
     */
    private void startYieldPoller() {
        Thread existing = yieldPollerThread;
        if (existing != null && existing.isAlive()) return;
        Thread t = new Thread(() -> {
            logger.info("Yield poller started — will check native app every "
                + YIELD_POLL_INTERVAL_MS + "ms");
            while (cameraYielded && running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(YIELD_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!cameraYielded) return;
                try {
                    if (cameraCoordinator != null) {
                        // checkNativeAppActive is the live polling path. When
                        // the native app releases the camera it internally
                        // calls handleNativeAppClosed → onReacquireCamera,
                        // which posts startCamera() to the GL handler and
                        // clears cameraYielded. Loop will then exit.
                        boolean active = cameraCoordinator.checkNativeAppActive();
                        if (!active) {
                            logger.info("Yield poller: native app no longer active — re-acquire path triggered");
                            // checkNativeAppActive already fired the re-acquire
                            // when transitioning active→inactive. Exit; the
                            // re-acquire callback will null this thread.
                            return;
                        }
                    }
                } catch (Throwable th) {
                    logger.warn("Yield poller error: " + th.getMessage());
                }
            }
            logger.info("Yield poller exiting (yielded=" + cameraYielded + ", running=" + running + ")");
        }, "PanoYieldPoller");
        t.setDaemon(true);
        yieldPollerThread = t;
        t.start();
    }

    /**
     * Interrupts and clears the yield poller. Called when cameraYielded flips
     * false (re-acquire) or when the pipeline is stopped.
     */
    private void stopYieldPoller() {
        Thread t = yieldPollerThread;
        if (t != null) {
            t.interrupt();
            yieldPollerThread = null;
        }
    }

    /**
     * SOTA: Restarts the camera after a HAL error event or frame stall.
     *
     * Called on GL thread. Does a full close→reopen cycle with proper cleanup.
     * This is faster than the watchdog kill+restart because it doesn't require
     * a full process restart — just a camera reopen.
     */
    private void restartCameraAfterError() {
        // P1 #11: CAS — only one restart can be in flight. If reopenCamera
        // (daemon thread) is already restarting, return without touching the
        // flag so its finally{set(false)} doesn't get clobbered.
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.info("Restart already in progress — skipping restartCameraAfterError");
            return;
        }

        // RE-ENTRY GUARD: once we've escalated to the warmup-routed restart, that
        // path released restartInProgress (below) so its own close/open isn't
        // blocked — but it now OWNS the camera teardown on its worker thread
        // (forceWarmupRestart → pipeline.stop() → camera.stop() → closeCamera).
        // onCameraError is NOT gated on halRecoveryEscalated, so a fresh error
        // could re-enter here, win the CAS, and fall through to a BARE
        // close/reopen concurrent with that teardown — redundant HAL churn that
        // can re-grab the wedged slot and perturb the warmup recovery. Bail out
        // until notePipelineRestarted() clears the flag when warmup completes.
        if (halRecoveryEscalated) {
            logger.info("Restart skipped — HAL-recovery warmup restart in flight (owns camera teardown)");
            restartInProgress.set(false);
            return;
        }

        // ESCALATION (#3): a bare close/reopen cannot recover a wedged AVM HAL
        // co-consumer state. Empirically the sentry->drive blackout looped 14
        // bare reopens over 2 min with ZERO frames; only a full pipeline
        // teardown + com.byd.avc warmup recovered it. Detect that loop here: if
        // the PREVIOUS open produced no frames (frameCounter didn't advance past
        // the snapshot taken at its open), count it; once we've stacked
        // FRAME_STALL_RESTART_ESCALATE_THRESHOLD consecutive zero-frame reopens,
        // stop looping and hand off to the listener's warmup-routed restart.
        boolean priorOpenDeliveredNoFrame = (frameCounter == frameCounterAtOpen);
        if (priorOpenDeliveredNoFrame) {
            consecutiveZeroFrameRestarts++;
        } else {
            consecutiveZeroFrameRestarts = 0;
        }
        if (consecutiveZeroFrameRestarts >= FRAME_STALL_RESTART_ESCALATE_THRESHOLD
                && yieldListener != null && !halRecoveryEscalated) {
            halRecoveryEscalated = true;
            logger.error("Frame-stall restart loop: " + consecutiveZeroFrameRestarts
                + " consecutive reopens delivered ZERO frames — bare reopen cannot "
                + "recover a wedged AVM HAL. Escalating to warmup-routed full restart.");
            // Release the CAS so the warmup-routed restart (which does its own
            // close/open) isn't blocked by our in-flight flag, then hand off.
            restartInProgress.set(false);
            try {
                yieldListener.onHalRecoveryNeeded();
            } catch (Throwable t) {
                // The handler normally spawns a thread whose finally calls
                // notePipelineRestarted() to clear this latch. If the dispatch
                // itself throws (e.g. Thread.start() OOM/EAGAIN), that finally
                // never runs and the latch would stick true forever — the stall
                // watchdog's defer branch would then permanently suppress all
                // bare restarts (drive-long blackout). Since a throw here means
                // NO recovery is in flight, clear the latch so the watchdog can
                // retry on the next stall tick. Defense-in-depth; the normal
                // path still clears it via notePipelineRestarted().
                halRecoveryEscalated = false;
                logger.warn("onHalRecoveryNeeded() threw: " + t.getMessage()
                    + " — cleared halRecoveryEscalated so stall watchdog can retry");
            }
            return;
        }

        logger.info("Restarting camera after error/stall...");

        // audit avc-yield (round 3, finding 9): a stickily-true coordinator
        // yielded flag (e.g. left over from a prior cycle whose
        // active→inactive edge was masked by a binder-error short-circuit at
        // BydCameraCoordinator.checkNativeAppActive's catch path) would make
        // startCamera() early-return at line ~1285 without throwing. The open
        // thread below would then set openSuccess[0]=true with cameraObj==null,
        // and we'd "successfully" finish a restart with no live camera. Clear
        // the sticky yield up front: this is the error-recovery path; if a
        // native app is genuinely active, the very next reacquire poll will
        // re-set yielded. If it isn't (binder-error stickiness), we recover
        // immediately instead of waiting for ACC OFF→ON.
        if (cameraCoordinator != null && cameraCoordinator.isCameraYielded()) {
            logger.warn("Restart: clearing sticky coordinator yielded flag "
                + "before reopen (avoid silent startCamera no-op)");
            try {
                cameraCoordinator.clearYieldedForRestart();
            } catch (Throwable th) {
                logger.warn("Restart: clearYieldedForRestart errored: "
                    + th.getMessage());
            }
        }

        try {
            // CRITICAL: Finalize active recording BEFORE closing camera.
            // FIX: onPreYield() calls muxer.stop() + FUSE rename which can block
            // 10+ seconds on a wedged USB mount. Running it inline on the GL thread
            // starves the heartbeat and the watchdog kills the daemon — the root
            // cause of mid-drive trip loss. Run it on a bounded worker thread and
            // keep the heartbeat alive while we wait. The recording MUST finalize
            // before camera close (otherwise moov atom is never written), so we
            // wait up to 8 seconds — well within the 10s warmup watchdog timeout.
            if (yieldListener != null) {
                final java.util.concurrent.atomic.AtomicBoolean yieldDone =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                final java.util.concurrent.atomic.AtomicReference<Exception> yieldError =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
                Thread yieldThread = new Thread(() -> {
                    try {
                        yieldListener.onPreYield();
                    } catch (Exception e) {
                        yieldError.set(e);
                    } finally {
                        yieldDone.set(true);
                    }
                }, "PreYield-Finalize");
                yieldThread.setDaemon(true);
                yieldThread.start();

                // Keep heartbeat alive while waiting for FUSE I/O to complete
                long yieldStart = System.currentTimeMillis();
                long yieldTimeout = 8000; // 8s — within the 10s warmup watchdog budget
                while (!yieldDone.get() &&
                       (System.currentTimeMillis() - yieldStart) < yieldTimeout) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    lastGlThreadHeartbeat = System.currentTimeMillis();
                }

                if (yieldDone.get()) {
                    Exception err = yieldError.get();
                    if (err != null) {
                        logger.warn("Pre-restart callback error: " + err.getMessage());
                    } else {
                        logger.info("Pre-restart: recording finalized");
                    }
                } else {
                    // Timed out — the FUSE mount is wedged. The recording file will be
                    // left as .tmp (recovered on next startup by orphan tmp sweep).
                    // Proceeding with camera close is safe: the muxer is already writing
                    // to a file descriptor (not the camera), so closing the camera won't
                    // corrupt whatever did get flushed. The next onFileSaved will still
                    // fire once the mount unblocks (or the file stays .tmp and is reaped).
                    logger.error("Pre-restart: onPreYield TIMED OUT after " + yieldTimeout
                        + "ms — FUSE mount wedged, proceeding with camera restart "
                        + "(recording may be truncated)");
                }
            }
            
            // Detach streaming components
            if (streamScaler != null || streamEncoder != null) {
                clearStreamingComponents();
                logger.info("Pre-restart: streaming components detached");
            }

            // Update heartbeat before drainer join (which can block 2s each)
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera.
            if (encoder != null) {
                encoder.stopDrainerForCameraClose();
            }
            lastGlThreadHeartbeat = System.currentTimeMillis();
            if (streamEncoder != null) {
                streamEncoder.stopDrainerForCameraClose();
            }

            // Close with proper cleanup + notify service
            if (cameraObj != null) {
                closeCameraForPath(cameraObj);
                cameraObj = null;
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                    cameraCoordinator.notifyPosCloseCamera();
                }
            }

            // Brief pause to let HAL settle
            Thread.sleep(500);

            // Update heartbeat so watchdog doesn't kill us during restart
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // CRITICAL: Recreate SurfaceTexture before reopening camera.
            // The BYD HAL won't deliver continuous frames to a Surface that was
            // previously connected to a different camera instance — only the first
            // frame arrives, then the stream freezes. This matches the fix already
            // present in the auto-probe path in renderLoop().
            recreateCameraSurface();
            
            // Update heartbeat again after surface recreation
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // CRITICAL FIX: Open camera on a separate thread with a timeout.
            // startCamera() calls into the BYD HAL which can block indefinitely
            // if the HAL is in a bad state. Running it on the GL thread causes
            // the watchdog to kill the process (GL heartbeat stops updating).
            // By opening on a worker thread, the GL thread stays alive and the
            // watchdog heartbeat keeps ticking. If the open times out, we let
            // the watchdog handle it on the next stall cycle instead of crash-looping.
            final boolean[] openSuccess = {false};
            final Exception[] openError = {null};
            Thread cameraOpenThread = new Thread(() -> {
                try {
                    startCamera();
                    // audit avc-yield (round 3, finding 10): startCamera can
                    // early-return without throwing when the coordinator
                    // reports yielded; cameraObj==null then "succeeds" by
                    // accident. Treat success as "did not throw AND cameraObj
                    // is live" so the outer recovery path actually runs.
                    openSuccess[0] = (cameraObj != null);
                } catch (Exception e) {
                    openError[0] = e;
                }
            }, "CameraReopen");
            cameraOpenThread.start();

            // Wait up to 2 seconds for camera to open, updating heartbeat periodically
            long openStart = System.currentTimeMillis();
            long openTimeout = 2000;
            while (cameraOpenThread.isAlive() &&
                   (System.currentTimeMillis() - openStart) < openTimeout) {
                Thread.sleep(200);
                lastGlThreadHeartbeat = System.currentTimeMillis();
            }

            if (!openSuccess[0]) {
                if (cameraOpenThread.isAlive()) {
                    logger.warn("Camera open timed out after " + openTimeout +
                        "ms — will retry on next stall cycle");
                    // Don't interrupt — let it finish in background, watchdog won't kill us
                    // because heartbeat is still updating
                    return;
                }
                if (openError[0] != null) {
                    throw openError[0];
                }
                // audit avc-yield (round 3, finding 10): no throw but
                // cameraObj is still null — startCamera short-circuited.
                // Surface the failure so the outer catch logs and the GL
                // watchdog gets a chance to escalate, instead of pretending
                // the restart succeeded with a dead camera handle.
                if (cameraObj == null) {
                    throw new IllegalStateException(
                        "startCamera returned without opening (cameraObj==null) — "
                        + "treating restart as failed");
                }
            }
            
            // Update heartbeat after successful open
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // Reset the frame-stall clock to the reopen instant. Otherwise the
            // stall watchdog measures timeSinceFrame from the LAST pre-restart
            // frame, so it re-declares a stall within ~1 tick of this reopen
            // (the 4s threshold was already exceeded before we got here) and
            // tears the fresh camera straight back down — the unbreakable
            // reopen loop. Pairing this with the post-open warmup grace
            // (FRAME_STALL_WARMUP_GRACE_MS) gives the BYD HAL its full 5-8s
            // first-frame latency before any stall can fire again. startCamera
            // already set lastCameraStartTime; align lastFrameTime to it.
            lastFrameTime = System.currentTimeMillis();

            // Restart encoder drainer now that camera is open again
            if (encoder != null) {
                encoder.restartDrainerAfterCameraClose();
            }
            
            // Re-register event callback
            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }
            
            // Resume recording/surveillance after camera restart.
            // Update heartbeat first: onPostReacquire → startRecording →
            // ensureStorageReady can block up to 4s on a flaky mount.
            lastGlThreadHeartbeat = System.currentTimeMillis();
            if (yieldListener != null) {
                try {
                    yieldListener.onPostReacquire();
                    logger.info("Post-restart: recording/surveillance resumed");
                } catch (Exception e) {
                    logger.warn("Post-restart callback error: " + e.getMessage());
                }
            }

            logger.info("Camera restarted successfully after error");

        } catch (Exception e) {
            logger.error("Camera restart failed: " + e.getMessage());
            // If restart fails, the watchdog will eventually kill the process
            // but at least we won't crash-loop immediately
        } finally {
            restartInProgress.set(false);
        }
    }

    /**
     * Stops the GPU camera pipeline.
     */
    public void stop() {
        logger.info( "Stopping GPU camera pipeline...");
        running = false;

        // Stop watchdog
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }

        // Stop yield poller (if a yield is in-flight when stop() races in)
        stopYieldPoller();
        
        // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera
        if (encoder != null) {
            encoder.stopDrainerForCameraClose();
        }
        if (streamEncoder != null) {
            streamEncoder.stopDrainerForCameraClose();
        }
        
        // Close camera with proper cleanup + notify service
        if (cameraObj != null) {
            closeCameraForPath(cameraObj);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.notifyPosCloseCamera();
            }
        }
        
        // Unregister from IBYDCameraService AFTER notifying posCloseCamera.
        // Must keep the service proxy alive until the close notification is sent,
        // otherwise the native camera app never receives the "camera released" signal
        // and hangs waiting for it.
        if (cameraCoordinator != null) {
            cameraCoordinator.unregister();
        }
        
        // Cleanup on GL thread
        if (glHandler != null) {
            glHandler.post(this::releaseGl);
        }
        
        // Stop GL thread
        if (glThread != null) {
            glThread.quitSafely();
            try {
                glThread.join(1000);
            } catch (InterruptedException e) {
                logger.warn( "GL thread join interrupted");
            }
            glThread = null;
        }
        
        logger.info( "GPU camera pipeline stopped");
    }
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     *
     * This is needed during ACC OFF→ON transitions. The daemon holds the camera
     * open continuously (surveillance → recording mode), which prevents the BYD
     * native camera app from getting video frames. By briefly releasing the camera,
     * the native app can grab it, and when we reopen we get added as a secondary
     * consumer via addPreviewSurface.
     */
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     * 
     * During ACC OFF→ON, the daemon holds the camera from surveillance mode.
     * The BYD native camera app starts on ACC ON but can't get frames.
     * Releasing briefly lets the native app grab the primary slot, then we
     * get added as secondary consumer via addPreviewSurface.
     */
    public void reopenCamera() {
        reopenCamera(15000);
    }

    public void reopenCamera(long maxWaitMs) {
        if (!running) {
            logger.warn("Cannot reopen camera - not running");
            return;
        }

        // P1 #11: CAS — only one restart can be in flight. If
        // restartCameraAfterError (GL thread) already owns the flag, return
        // without clobbering its finally{set(false)}.
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.warn("Restart already in progress — skipping reopenCamera");
            return;
        }

        logger.info("Reopening AVMCamera...");

        // CRITICAL: Mark restart-in-progress BEFORE touching the camera so the
        // GL watchdog uses GL_THREAD_WARMUP_TIMEOUT_MS (10s) instead of the
        // normal 3s. Without this, the daemon thread's polling sleep + the
        // GL thread briefly blocking on updateTexImage() against a dying HAL
        // is enough to trip the watchdog and force a full process restart on
        // every ACC OFF→ON transition. See log: "GL thread blocked for 3492ms".

        try {
            // Proper cleanup order via BydCameraCoordinator.
            // Null cameraObj BEFORE closeCamera() so the GL renderLoop's
            // `cameraObj == null` short-circuit (line ~616) kicks in immediately
            // and stops calling updateTexImage() on a HAL that's being torn down.
            if (cameraObj != null) {
                Object toClose = cameraObj;
                cameraObj = null;
                closeCameraForPath(toClose);
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                }
                logger.info("Camera closed (proper cleanup)");
            }

            // Kick the GL heartbeat so the watchdog timer resets at the start of
            // the wait — close+log above can already have spent >1s.
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // registerCameraUser is DISABLED — the event-driven branch below is
            // dead. Kept for reference; do NOT re-enable without re-validating
            // the IBYDCameraUser yield/reacquire path end-to-end.
            // if (cameraCoordinator != null && cameraCoordinator.isRegisteredAsUser()) {
            //     logger.info("Registered as camera user — waiting for onCloseCamera callback");
            //     Thread.sleep(3000);
            //     if (!cameraCoordinator.isCameraYielded()) {
            //         startCamera();
            //         if (cameraCoordinator != null && cameraObj != null) {
            //             cameraCoordinator.setupEventCallback(cameraObj);
            //         }
            //     }
            //     return;
            // }

            // Polling path — the only live path. Wait long enough for the BYD
            // native AVM app to claim the primary camera slot, then reopen as
            // secondary consumer. Sleeps in 500ms chunks so we can refresh the
            // GL watchdog heartbeat — otherwise a long single sleep on this
            // (daemon) thread can race the GL thread mid-updateTexImage and
            // make timeSinceHeartbeat exceed the threshold.
            logger.info("Polling fallback (maxWait=" + maxWaitMs + "ms)");
            final long minWaitMs = 3000;
            sleepWithHeartbeat(minWaitMs);

            if (cameraCoordinator != null && cameraCoordinator.isRegistered()) {
                long deadline = System.currentTimeMillis() + (maxWaitMs - minWaitMs);
                boolean nativeAppDetected = false;

                while (System.currentTimeMillis() < deadline) {
                    if (cameraCoordinator.checkNativeAppActive()) {
                        nativeAppDetected = true;
                        logger.info("Native app claimed camera (polling) — waiting for release");
                        sleepWithHeartbeat(500);
                        break;
                    }
                    sleepWithHeartbeat(500);
                }

                if (!nativeAppDetected) {
                    logger.info("Native app not detected after polling — reopening");
                }
            } else {
                long remainingWait = maxWaitMs - minWaitMs;
                logger.info("No service available — fixed delay (" + remainingWait + "ms)");
                sleepWithHeartbeat(remainingWait);
            }

            startCamera();

            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }

            // Reset heartbeat after a successful reopen so the next watchdog
            // tick measures from a known-good baseline.
            lastGlThreadHeartbeat = System.currentTimeMillis();
            // Reset the frame-stall clock to the reopen instant too — same
            // reason as restartCameraAfterError: this is the ACC-ON
            // surveillance->drive reopen, after which lastFrameTime still
            // points at the last sentry-era frame. Without this, the stall
            // watchdog fires within ~1 tick and the just-reopened camera is
            // torn down before its 5-8s first-frame warmup can complete
            // (the recording-blackout reopen loop). startCamera set
            // lastCameraStartTime; align lastFrameTime to it.
            lastFrameTime = System.currentTimeMillis();
            logger.info("Camera reopened successfully");

        } catch (Exception e) {
            logger.error("Failed to reopen camera: " + e.getMessage(), e);
            try {
                if (cameraObj == null) {
                    logger.warn("Retry camera open...");
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                    lastGlThreadHeartbeat = System.currentTimeMillis();
                }
            } catch (Exception e2) {
                logger.error("Camera retry failed: " + e2.getMessage());
            }
        } finally {
            restartInProgress.set(false);
        }
    }

    /**
     * Sleeps for {@code totalMs} milliseconds in 250ms chunks, refreshing
     * the GL watchdog heartbeat each chunk. Used while the daemon thread is
     * waiting for the BYD HAL to settle so the watchdog doesn't kill the
     * process during the wait.
     */
    private void sleepWithHeartbeat(long totalMs) throws InterruptedException {
        final long step = 250;
        long remaining = totalMs;
        while (remaining > 0 && running) {
            long chunk = Math.min(step, remaining);
            Thread.sleep(chunk);
            lastGlThreadHeartbeat = System.currentTimeMillis();
            remaining -= chunk;
        }
    }
    
    /**
     * Releases OpenGL resources.
     */
    private void releaseGl() {
        // Shut down the AI worker FIRST so any in-flight processFrame
        // completes before we tear down the consumers it might still
        // reference. The worker's drain timeout caps this at ~2s.
        if (aiLaneWorker != null) {
            try { aiLaneWorker.shutdown(); } catch (Throwable ignored) {}
            aiLaneWorker = null;
        }

        // Tier 1: shut down the AI-lane GL thread before destroying the
        // encoder EGL context. The lane's shared context lives in the same
        // share group as eglCore — if eglCore went down first, the lane's
        // textures/programs would become orphans and its GL teardown would
        // log spurious errors. Order: shut lane (releases its GL state on
        // its own thread, including the foveated cropper FBOs), then we
        // can safely tear down eglCore here.
        if (aiLaneGl != null) {
            try { aiLaneGl.shutdown(); } catch (Throwable ignored) {}
            aiLaneGl = null;
        }
        // foveatedCropper.release() is called by AiLaneGl.shutdown() above
        // (the cropper's GL resources live in the AI-lane context). Just
        // null the reference here.
        foveatedCropper = null;

        // Tear down the dialog-preview sampler. It owns its own EGL context
        // (shared with our eglCore) on its own HandlerThread; if we don't
        // release it the context outlives this pipeline instance and leaks
        // EGL handles every time the daemon restarts.
        synchronized (this) {
            if (highResSampler != null) {
                try { highResSampler.release(); } catch (Throwable ignored) {}
                highResSampler = null;
            }
        }

        // Fortified teardown: isolate each step so one failing release
        // (e.g. a HAL binder already dead) doesn't strand the remaining
        // GL resources and leak them across the daemon restart.
        try { stopWindshieldCameraOnGlThread(); } catch (Throwable t) { logger.warn("releaseGl: windshield teardown: " + t.getMessage()); }

        // Releases whichever consumer (SurfaceTexture or ImageReader) is active.
        try { releaseCameraConsumer(); } catch (Throwable t) { logger.warn("releaseGl: consumer teardown: " + t.getMessage()); }

        // Tear down the ImageReader callback thread (full shutdown only —
        // recreateCameraSurface keeps it alive across camera re-attach).
        if (imageReaderThread != null) {
            try { imageReaderThread.quitSafely(); } catch (Throwable ignored) {}
            imageReaderThread = null;
            imageReaderHandler = null;
        }

        if (cameraTextureId != 0) {
            try { GlUtil.deleteTexture(cameraTextureId); } catch (Throwable t) { logger.warn("releaseGl: cameraTextureId: " + t.getMessage()); }
            cameraTextureId = 0;
        }
        if (windshieldTextureId != 0) {
            try { GlUtil.deleteTexture(windshieldTextureId); } catch (Throwable t) { logger.warn("releaseGl: windshieldTextureId: " + t.getMessage()); }
            windshieldTextureId = 0;
        }

        // Free the OES-probe FBO/texture/program. These are lazily created
        // by ensureProbeResources() and were previously never released here,
        // leaking one FBO + texture + program per daemon restart.
        if (probeFbo != 0) {
            try { android.opengl.GLES20.glDeleteFramebuffers(1, new int[]{probeFbo}, 0); } catch (Throwable ignored) {}
            probeFbo = 0;
        }
        if (probeColorTex != 0) {
            try { android.opengl.GLES20.glDeleteTextures(1, new int[]{probeColorTex}, 0); } catch (Throwable ignored) {}
            probeColorTex = 0;
        }
        if (probeProgram != 0) {
            try { android.opengl.GLES20.glDeleteProgram(probeProgram); } catch (Throwable ignored) {}
            probeProgram = 0;
        }

        if (dummySurface != null) {
            try { if (eglCore != null) eglCore.destroySurface(dummySurface); } catch (Throwable t) { logger.warn("releaseGl: dummySurface: " + t.getMessage()); }
            dummySurface = null;
        }

        if (eglCore != null) {
            try { eglCore.release(); } catch (Throwable t) { logger.warn("releaseGl: eglCore release: " + t.getMessage()); }
            eglCore = null;
        }

        logger.info("OpenGL resources released");
    }
    
    /**
     * Sets streaming components for parallel GPU path.
     * 
     * @param streamScaler GPU stream scaler
     * @param streamEncoder Stream encoder
     */
    public void setStreamingComponents(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                      HardwareEventRecorderGpu streamEncoder) {
        this.streamScaler = streamScaler;
        this.streamEncoder = streamEncoder;
    }

    /**
     * Publishes the dedicated blind-spot lane's scaler+encoder to the render
     * loop (PASS 1C). The fields are volatile so the render loop's per-frame
     * snapshot read sees the write, and so cross-thread readers (calibration /
     * param tuning via getBsStreamScaler) observe the reference atomically.
     *
     * <p>Volatile alone only publishes the <em>reference</em>, not a happens-
     * before edge to the scaler's GL-resource construction. The scaler is
     * init()'d on the GL thread (handler post in GpuSurveillancePipeline), while
     * this publish is invoked from the lifecycle (enable) thread. If the bare
     * volatile write became visible to the GL render loop before that init
     * Runnable had fully constructed the scaler's GL state, the render loop
     * would call drawFrame() on a half-built scaler — undefined behaviour on
     * Adreno (program/FBO not yet created). To make the publish safe, hop it
     * onto the GL thread: the Handler is FIFO, so this write lands strictly
     * after any already-queued init Runnable, and because the render loop runs
     * on the same thread it can never observe the reference ahead of the
     * scaler's fully-constructed GL state. If we're already on the GL thread
     * (or the handler is gone during teardown), write directly.
     */
    public void setBsStreamingComponents(com.overdrive.app.streaming.GpuStreamScaler scaler,
                                         HardwareEventRecorderGpu encoder) {
        final Handler h = glHandler;
        if (h == null || h.getLooper().getThread() == Thread.currentThread()) {
            this.bsStreamScaler = scaler;
            this.bsStreamEncoder = encoder;
            return;
        }
        h.post(() -> {
            this.bsStreamScaler = scaler;
            this.bsStreamEncoder = encoder;
        });
    }

    /** Detach the blind-spot lane from the render loop (render loop sees null
     *  next frame and stops blitting it). Does NOT release the GL objects — the
     *  caller releases them on the GL thread after this returns. */
    public void clearBsStreamingComponents() {
        this.bsStreamScaler = null;
        this.bsStreamEncoder = null;
    }

    /** @return the live blind-spot scaler, or null if the BS lane isn't active. */
    public com.overdrive.app.streaming.GpuStreamScaler getBsStreamScaler() { return bsStreamScaler; }

    /** Called by GpuSurveillancePipeline when the blind-spot SurfaceControl layer
     *  transitions between shown (turn active / debug-preview) and hidden. PASS 1C
     *  uses this to skip rendering while the layer is invisible, saving a full
     *  1280×960 GPU raster pass per frame. */
    public void setBsLayerVisible(boolean visible) {
        this.bsLayerVisible = visible;
    }

    /** Whether PASS 1C is currently drawing the BS lane (the render gate). Used by
     *  the pipeline to detect a gate/show desync and re-arm. */
    public boolean isBsLayerVisible() { return bsLayerVisible; }

    /**
     * Enables/disables the optional direct windshield camera used by the
     * dashcam recording layout. The actual AVMCamera open/close happens on
     * the GL thread; if it fails, the recorder keeps using the 360-front
     * fallback without interrupting recording.
     */
    public void setDashcamWindshieldCamera(boolean enabled, int cameraId) {
        this.windshieldEnabled = enabled && cameraId >= 0;
        this.windshieldCameraId = cameraId;
        this.windshieldOpenFailed = false;
        Handler handler = glHandler;
        if (handler != null) {
            handler.post(this::updateWindshieldCameraOnGlThread);
        }
        logger.info("Dashcam windshield source "
            + (this.windshieldEnabled ? ("enabled (id=" + cameraId + ")") : "disabled"));
    }

    /**
     * Clears streaming components (called when streaming is disabled).
     * This prevents the render loop from trying to use released surfaces.
     */
    public void clearStreamingComponents() {
        this.streamScaler = null;
        this.streamEncoder = null;
    }
    
    /**
     * Gets the GL thread handler for posting operations.
     * 
     * @return Handler for GL thread
     */
    public Handler getGlHandler() {
        return glHandler;
    }
    
    /**
     * Checks if the camera is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * True once the camera HAL has delivered at least one frame and the GL
     * thread has bound it to {@code cameraTextureId}. Used by the
     * camera-mapping dialog to decide whether a sync readback would actually
     * see real content vs. an uninitialized black texture.
     */
    public boolean isFirstFrameReceived() {
        return firstFrameReceived;
    }

    /**
     * Number of frames the GL thread has consumed from the HAL since
     * {@code start()}. Combined with {@link #isFirstFrameReceived()} this
     * lets callers wait for the BYD HAL warmup probe (frames 1–15 are
     * cold/dim on most builds) before sampling.
     */
    public int getFrameCounter() {
        return frameCounter;
    }

    /**
     * Sets the AVMCamera surface mode for addPreviewSurface().
     * Must be called before start(). Default is 0 (works on Seal).
     * Atto 1 may need mode 1 for processed panoramic output.
     *
     * On the esco SurfaceTexture path this same value is the previewIndex
     * argument to addTexture/setTexture/rmTexture: 0=mosaic, 1-4=quadrant.
     */
    public void setCameraSurfaceMode(int mode) {
        this.cameraSurfaceMode = mode;
        logger.info("Camera surface mode set to: " + mode);
    }

    public boolean isUsingEscoSurfaceTexturePath() {
        return USE_ESCO_SURFACE_TEXTURE_PATH;
    }
    
    /**
     * Gets the current camera surface mode.
     */
    public int getCameraSurfaceMode() {
        return cameraSurfaceMode;
    }
    
    /**
     * Gets the active camera ID (the one currently open or selected by probe).
     */
    public int getCameraId() {
        return cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
    }
    
    /**
     * Sets the AVMCamera ID to use.
     * Must be called before start(). Default is 1 (works on Seal).
     * Dolphin/Atto 1 may need ID 0.
     */
    public void setCameraId(int id) {
        this.cameraIdOverride = id;
        logger.info("Camera ID override set to: " + id);
    }
    
    /**
     * Sets the target frame rate for the binder camera backend.
     * Only effective when binder backend is enabled.
     * Updates the target frame rate. If the camera is already open, also
     * pushes the new rate to the HAL via AvmCameraHelper.setCameraFps so
     * emission rate matches the encoder's KEY_FRAME_RATE without a full
     * camera reopen.
     *
     * @param fps Desired frames per second (range enforced by callers; this
     *            method just stores and applies)
     */
    public void setTargetFps(int fps) {
        // Idempotent: skip the work (and the reflective HAL call) when the rate
        // is unchanged. RecordingModeManager.reconcileCameraProfile may re-assert
        // the same fps on every BS show/hide edge and lifecycle transition; with
        // a sustained turn signal that's many calls, and a reflective
        // setCameraFps each time would be needless churn on the HAL thread.
        if (fps == this.targetFps) {
            return;
        }
        this.targetFps = fps;
        logger.info("Target FPS set to: " + fps);
        // Keep the AI-lane GL-hop budget in sync with the new rate.
        if (sentry != null) {
            sentry.setCameraTargetFps(fps);
        }
        // If the camera is currently open, push the new rate to the HAL.
        // Returns false on devices where setCameraFps is rejected (e.g., the
        // BYD HAL when isValidCamera gate fails) — we log and continue; the
        // encoder reconfig will still produce the right KEY_FRAME_RATE.
        Object cam = cameraObj;
        if (cam != null) {
            try {
                AvmCameraHelper.setCameraFps(cam, fps);
            } catch (Throwable t) {
                logger.warn("Live setCameraFps failed: " + t.getMessage());
            }
        }
    }
    
    /**
     * Gets the target FPS setting.
     */
    public int getTargetFps() {
        return targetFps;
    }

    /**
     * Pin the AI-lane motion-readback cadence to a fixed wall-clock interval
     * (ms), independent of the HAL delivery rate; 0 restores the default
     * frame-count modulo. Caches the value and forwards to the live AiLaneGl if
     * present (re-applied at the next lazy bring-up otherwise). Used by the
     * parked-idle throttle so dropping/ramping the HAL fps does NOT change how
     * often motion detection runs.
     */
    public void setAiReadbackMinIntervalMs(long ms) {
        long clamped = Math.max(0L, ms);
        this.aiReadbackMinIntervalMs = clamped;
        AiLaneGl lane = aiLaneGl;
        if (lane != null) {
            lane.setReadbackMinIntervalMs(clamped);
        }
    }

    /**
     * Set the recorder draw stride. The render loop draws into the recording
     * encoder's input surface only every {@code stride}-th camera frame, giving
     * an effective recording rate of ~cameraFps/stride WITHOUT a codec
     * reconfigure. Streaming and blind-spot lanes are unaffected. {@code 1}
     * restores full-rate recording (the default). Values &lt; 1 are clamped to 1.
     *
     * <p>Thread-safe: writes a volatile read by the GL render thread.
     */
    public void setRecorderFrameStride(int stride) {
        int clamped = Math.max(1, stride);
        if (clamped != recorderFrameStride) {
            recorderFrameStride = clamped;
            // Reset the phase so the FIRST frame after a stride change always
            // draws (counter % stride == 0), rather than waiting up to the old
            // phase offset. The counter is GL-thread-confined; this write from
            // the control thread is a benign racy hint — worst case the first
            // post-change draw lands one frame early/late, cosmetic. It is read
            // as `% stride` so no torn-value hazard.
            recorderStrideCounter = 0;
            logger.info("Recorder frame stride set to " + clamped
                + " (effective recording rate ≈ cameraFps/" + clamped + ")");
        }
    }

    /**
     * Gets the current recorder draw stride (1 = every frame).
     */
    public int getRecorderFrameStride() {
        return recorderFrameStride;
    }

    /**
     * Master enable/disable for the recorder lane (PASS 1A H.265 mosaic). When
     * {@code false}, the render loop skips the recorder drawFrame + drainEncoder
     * entirely; the stream (PASS 1B) and blind-spot (PASS 1C) lanes are
     * unaffected. {@code true} is the default — ZERO behaviour change for every
     * recording mode. Used when the camera is kept warm ONLY for blind-spot (no
     * encoder, no pre-record ring to feed) so the H.265 encoder doesn't burn
     * Venus for footage nothing will flush.
     *
     * <p>Thread-safe: writes a volatile read by the GL render thread.
     */
    public void setRecorderLaneEnabled(boolean enabled) {
        if (enabled != recorderLaneEnabled) {
            recorderLaneEnabled = enabled;
            // Reset the stride phase so re-enabling starts on a drawn frame
            // (mirrors setRecorderFrameStride's reset rationale).
            if (enabled) recorderStrideCounter = 0;
            logger.info("Recorder lane " + (enabled ? "ENABLED" : "DISABLED (PASS 1A skipped)"));
        }
    }

    /** @return whether the recorder lane (PASS 1A) is currently drawing. */
    public boolean isRecorderLaneEnabled() {
        return recorderLaneEnabled;
    }

    /**
     * Gets the most recently measured camera FPS (over the last 2-minute
     * stats window). Returns 0 if no stats window has elapsed yet. Use this
     * to surface to the UI when HAL clamps below the requested target —
     * e.g., user picks 30, HAL emits ~26 on this device.
     */
    public float getMeasuredFps() {
        return measuredFps;
    }
    /**
     * Enables auto-probe mode: tries camera IDs 0-5 at startup to find
     * the one that produces actual image data. Logs resolution and pixel
     * content for each ID. Auto-selects the first panoramic (5120-wide) camera
     * with non-black frames.
     */
    public void setAutoProbeCameras(boolean enabled) {
        this.autoProbeCameras = enabled;
        if (enabled) {
            probeComplete = false;
            probeNextCameraId = 0;
            probeNextSurfaceMode = 0;
        }
        logger.info("Camera auto-probe: " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * When true, skip frame-15/50 validation. Used when user manually set camera ID.
     */
    public void setSkipFrameValidation(boolean skip) {
        this.skipFrameValidation = skip;
        if (skip) logger.info("Frame validation SKIPPED (manual camera override)");
    }
    
    /**
     * Sets a callback to be notified when auto-probe discovers a working camera.
     * The pipeline can use this to persist the result for faster restarts.
     */
    public void setCameraProbeCallback(CameraProbeCallback callback) {
        this.probeCallback = callback;
    }
    
    /**
     * Gets the timestamp of the last frame.
     * 
     * @return Timestamp in milliseconds
     */
    public long getLastFrameTime() {
        return lastFrameTime;
    }
    
    /**
     * SOTA: Gets the BYD camera coordinator for status queries.
     */
    public BydCameraCoordinator getCameraCoordinator() {
        return cameraCoordinator;
    }
    
    /**
     * SOTA: Sets the yield listener for recording finalization during camera yield.
     * The pipeline registers this to ensure recordings are properly closed before
     * the camera is released, and resumed after re-acquisition.
     */
    public void setCameraYieldListener(CameraYieldListener listener) {
        this.yieldListener = listener;
    }

    /**
     * Clears the zero-frame-reopen escalation latch. The pipeline MUST call
     * this after it has handled onHalRecoveryNeeded() (i.e. performed its
     * warmup-routed restart), so a later independent HAL wedge in the same
     * drive can escalate again instead of being permanently suppressed.
     * Also resets the zero-frame counter — the warmup restart is a fresh start.
     */
    public void notePipelineRestarted() {
        consecutiveZeroFrameRestarts = 0;
        halRecoveryEscalated = false;
    }
    
    /**
     * SOTA: Returns true if camera is currently yielded to native BYD app.
     */
    public boolean isCameraYielded() {
        return cameraYielded;
    }
    
    /**
     * Gets the total frame count.
     * 
     * @return Frame count
     */
    public int getFrameCount() {
        return frameCounter;
    }
    
    /**
     * Returns true when camera probe is complete and frames are valid for consumption.
     * During probe, recording/streaming/AI are gated to prevent encoding BLACK frames.
     */
    public boolean isProbeComplete() {
        return probeComplete;
    }
    
    /**
     * Gets the camera width.
     * 
     * @return Width in pixels
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Gets the camera height.
     * 
     * @return Height in pixels
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Gets the latest JPEG frame for a specific camera view.
     *
     * <p>Delegates to the surveillance engine's published mosaic JPEG —
     * no GL-thread work, no AVMCamera open, no concurrent HAL claim. The
     * engine encodes the mosaic on its own worker thread once per surveillance
     * cycle (see {@link SurveillanceEngineGpu#getLatestMosaicJpeg()}); this
     * call decodes that JPEG and crops the requested quadrant.
     *
     * <p>Returns null if the engine hasn't published a JPEG yet (surveillance
     * not running, or first frames still warming up). Callers should treat
     * null as "no frame available" and retry, NOT trigger any side-effect
     * that would touch the camera HAL.
     *
     * @param cameraId 0=full mosaic, 1=Front, 2=Right, 3=Rear, 4=Left
     * @return JPEG bytes, or null when no published mosaic is available
     */
    public byte[] getLatestJpegFrame(int cameraId) {
        SurveillanceEngineGpu engine = sentry;
        if (engine == null) return null;
        byte[] mosaicJpeg = engine.getLatestMosaicJpeg();
        if (mosaicJpeg == null || mosaicJpeg.length == 0) return null;
        if (cameraId == 0) return mosaicJpeg;
        return cropMosaicJpegQuadrant(mosaicJpeg, cameraId);
    }

    /**
     * Sample the live camera texture at FULL encoder resolution and return
     * a JPEG of the 2x2 mosaic. Seal: 2560×1920. Tang: 2560×1440.
     *
     * <p><b>Recording-safe.</b> Runs on a dedicated GL thread with a shared
     * EGL context — does NOT block the camera GL thread, the encoder draw,
     * or {@code eglSwapBuffers}. The shared context lets us sample the
     * camera's OES texture concurrently while it's being updated by the HAL.
     * Same pattern {@link com.overdrive.app.surveillance.GpuDownscaler} uses
     * for the AI lane.
     *
     * <p>Independent of {@link SurveillanceEngineGpu}. Works whenever the
     * camera pipeline is running, including proximity-guard mode.
     *
     * @return JPEG bytes, or null when the camera isn't running yet / EGL
     *     context isn't available.
     */
    public byte[] sampleFullResMosaicJpeg() {
        HighResPreviewSampler sampler = ensureHighResSampler();
        if (sampler == null || cameraTextureId == 0) {
            logger.warn("sampleFullResMosaicJpeg early-exit sampler="
                    + (sampler != null) + " textureId=" + cameraTextureId);
            return null;
        }
        float[] offsets = quadrantStripOffsetX != null
                ? quadrantStripOffsetX.clone()
                : new float[]{0.75f, 0.50f, 0.00f, 0.25f};
        return sampler.sampleFullMosaicJpeg(cameraTextureId, width, height, offsets);
    }

    /**
     * Sample one camera tile at FULL per-camera resolution.
     * Seal: 1280×960. Tang: 1280×720. Recording-safe — same threading model
     * as {@link #sampleFullResMosaicJpeg}.
     *
     * <p>Layout-aware:
     * <ul>
     *   <li>Default mode (legacy 4-strip HAL): pass {@code sliceOffsetX} only;
     *       cornerX/cornerY default to NaN → sampler uses 4-strip math.</li>
     *   <li>DiLink 4 mode (2x2-native HAL): pass corner XY for the slice's
     *       0.5×0.5 corner → sampler uses 2x2 math.</li>
     * </ul>
     *
     * @param sliceOffsetX strip-X offset for the slice (legacy path)
     */
    public byte[] samplePerQuadrantJpeg(float sliceOffsetX) {
        return samplePerQuadrantJpeg(sliceOffsetX, Float.NaN, Float.NaN);
    }

    /**
     * Layout-aware variant. cornerX/cornerY are the slice's top-left in a
     * 2x2-native HAL frame (only used when the camera is in DiLink 4 mode).
     */
    public byte[] samplePerQuadrantJpeg(float sliceOffsetX,
                                        float cornerX, float cornerY) {
        return samplePerQuadrantJpeg(sliceOffsetX, cornerX, cornerY, 0f, 0f);
    }

    /**
     * Layout-aware variant with per-role flip flags. xFlip/yFlip apply to
     * the local 0.5×0.5 sample window when DiLink 4's HAL emits a flipped
     * tile for that role. {@code 0f, 0f} = no flip (legacy/canonical).
     */
    public byte[] samplePerQuadrantJpeg(float sliceOffsetX,
                                        float cornerX, float cornerY,
                                        float xFlip, float yFlip) {
        HighResPreviewSampler sampler = ensureHighResSampler();
        if (sampler == null || cameraTextureId == 0) {
            logger.warn("samplePerQuadrantJpeg early-exit sampler="
                    + (sampler != null) + " textureId=" + cameraTextureId);
            return null;
        }
        // Force 2x2 math when DiLink 4 is active AND caller supplied corner
        // values; otherwise legacy 4-strip math.
        boolean useCorner = USE_ESCO_SURFACE_TEXTURE_PATH
            && !Float.isNaN(cornerX) && !Float.isNaN(cornerY);
        if (useCorner) {
            return sampler.samplePerQuadrantJpeg(
                cameraTextureId, width, height, sliceOffsetX,
                cornerX, cornerY, xFlip, yFlip);
        }
        return sampler.samplePerQuadrantJpeg(
            cameraTextureId, width, height, sliceOffsetX);
    }

    /**
     * Lazily allocate the high-res sampler with a shared EGL context.
     * Returns null when the camera isn't running yet (no EGL core).
     * Allocation is one-shot — sampler thread + EGL context outlive the
     * dialog session and absorb subsequent requests cheaply.
     */
    private synchronized HighResPreviewSampler ensureHighResSampler() {
        if (highResSampler != null) return highResSampler;
        if (eglCore == null) return null;
        android.opengl.EGLContext sharedContext = eglCore.getContext();
        if (sharedContext == null
                || sharedContext == android.opengl.EGL14.EGL_NO_CONTEXT) {
            return null;
        }
        try {
            highResSampler = new HighResPreviewSampler(sharedContext);
            // Layout mirrors the active camera layout mode; matrix is
            // refreshed on every consume tick so even legacy mode (which
            // uses identity) stays current.
            highResSampler.setCameraLayout(getCameraLayoutMode());
            if (USE_ESCO_SURFACE_TEXTURE_PATH) {
                highResSampler.setTextureMatrix(currentTexMatrix);
                try {
                    org.json.JSONObject camCfgHr = com.overdrive.app.config
                        .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                    if (camCfgHr != null) {
                        highResSampler.setRedMaskEnabled(
                            camCfgHr.optBoolean("dilink4RedMask", false));
                        highResSampler.setApaCenterInset(
                            (float) camCfgHr.optDouble("dilink4ApaCenterInset", 0.09375));
                    }
                } catch (Throwable t) {
                    logger.warn("Sampler red-mask flag read failed: " + t.getMessage());
                }
            }
            return highResSampler;
        } catch (Throwable t) {
            logger.warn("ensureHighResSampler failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Crops a 2×2-mosaic JPEG into the requested quadrant. Called on the
     * HTTP worker thread; no GL involvement. Quadrant indices match the
     * existing snapshot endpoint contract: 1=TL, 2=TR, 3=BL, 4=BR.
     */
    private static byte[] cropMosaicJpegQuadrant(byte[] mosaicJpeg, int cameraId) {
        if (cameraId < 1 || cameraId > 4) return null;
        android.graphics.Bitmap mosaic = null;
        android.graphics.Bitmap quadrant = null;
        try {
            mosaic = android.graphics.BitmapFactory.decodeByteArray(
                    mosaicJpeg, 0, mosaicJpeg.length);
            if (mosaic == null) return null;
            int qW = Math.max(1, mosaic.getWidth() / 2);
            int qH = Math.max(1, mosaic.getHeight() / 2);
            int x = (cameraId == 2 || cameraId == 4) ? qW : 0;
            int y = (cameraId == 3 || cameraId == 4) ? qH : 0;
            quadrant = android.graphics.Bitmap.createBitmap(mosaic, x, y, qW, qH);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            quadrant.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (quadrant != null) {
                try { quadrant.recycle(); } catch (Exception ignored) {}
            }
            if (mosaic != null) {
                try { mosaic.recycle(); } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Checks CPU usage and logs warning if exceeds threshold.
     * 
     * Provides breakdown by component to identify bottlenecks.
     */
    private void checkCpuUsage() {
        long now = System.currentTimeMillis();
        if (now - lastCpuCheckTime < CPU_CHECK_INTERVAL_MS) {
            return;
        }
        
        lastCpuCheckTime = now;
        
        try {
            // Read /proc/stat for total CPU time
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();
            
            // Parse CPU times
            String[] tokens = line.split("\\s+");
            long totalCpu = 0;
            for (int i = 1; i < tokens.length; i++) {
                totalCpu += Long.parseLong(tokens[i]);
            }
            
            // Read /proc/self/stat for process CPU time
            reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/stat"));
            line = reader.readLine();
            reader.close();
            
            tokens = line.split("\\s+");
            long processCpu = Long.parseLong(tokens[13]) + Long.parseLong(tokens[14]);
            
            // Calculate CPU percentage (simplified)
            // Note: This is a rough estimate. For accurate measurement, use
            // Android Profiler or systrace.
            // Logging disabled to reduce log spam - uncomment for debugging
            // logger.debug( String.format("CPU check: process=%d, total=%d", processCpu, totalCpu));
            
        } catch (Exception e) {
            // Silent fail - CPU monitoring is optional
        }
    }
}
