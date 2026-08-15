package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;

/**
 * AVC HAL Warmup — ensures the BYD camera HAL is initialized by com.byd.avc
 * BEFORE our daemon opens the camera.
 *
 * PROBLEM: When ACC turns ON, both our daemon and the native DVR (com.byd.cdr)
 * race to open the panoramic camera. If our daemon opens first, the HAL enters
 * a state where the native DVR can't attach its surface → "no video signal."
 *
 * SOLUTION:
 * 1. Launch com.byd.avc silently (the camera HAL initializer, NOT the DVR)
 * 2. Wait 4 seconds for the HAL to fully initialize in multi-consumer mode
 * 3. THEN open our camera as a secondary consumer
 *
 * Additionally, a 60-second keep-alive watchdog re-pokes com.byd.avc while
 * the pipeline is running, regardless of ACC state. BYD's system can kill
 * the camera app after inactivity, which destabilizes the HAL for all
 * consumers — including during ACC OFF sentry mode when the head unit stays
 * awake (charging, surveillance armed).
 *
 * LIFECYCLE:
 * - start() when pipeline starts (any mode, any ACC state)
 * - stop() when pipeline stops OR daemon shuts down
 *
 * <p><b>DiLink 4 (June 2026 reversal).</b> Empirically the AVMCamera HAL on
 * byd_apa only delivers mosaic content into the panoramic producer surface
 * when ANOTHER consumer is attached to the same vendor.byd.avm daemon —
 * com.byd.avc is exactly that consumer. Killing AVC made post-ACC-OFF frames
 * go all-zero (Frame 1 size dropped from ~80 KB to ~350 B). We now COOPERATE
 * with AVC on dilink4 like oem does: warm it on entry AND keep-alive ticks
 * keep it propped up. The red "calibration failed" chrome that AVC paints
 * is suppressed cosmetically by the GL red-mask shader (already in place),
 * so we no longer need to evict AVC at all. Legacy cars (90% of fleet)
 * unchanged.
 */
public class AvcHalWarmup {

    private static final String TAG = "AvcHalWarmup";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * True when the active camera mode is "dilink4". As of June 2026 we no
     * longer suppress AVC on dilink4 — we cooperate with it. Kept as a
     * boolean predicate because callers may still want to differentiate
     * (e.g. logging tags). Reads the unified config fresh; cheap (single
     * JSON load) and called only at warmup/keep-alive entry points.
     */
    private static boolean isDilink4Mode() {
        try {
            org.json.JSONObject root = com.overdrive.app.config
                .UnifiedConfigManager.loadConfig();
            org.json.JSONObject cam = root != null ? root.optJSONObject("camera") : null;
            if (cam == null) return false;
            String mode = cam.optString("cameraMode", "default");
            return "dilink4".equalsIgnoreCase(mode);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Time to wait after launching com.byd.avc before opening our camera. */
    private static final long HAL_WARMUP_DELAY_MS = 4000;

    /** Interval for keep-alive pokes to prevent system from killing com.byd.avc. */
    private static final long KEEP_ALIVE_INTERVAL_MS = 60_000;

    /** The am start command to silently launch com.byd.avc without bringing it to foreground. */
    private static final String[] AVC_LAUNCH_CMD = new String[]{
        "am", "start",
        "--user", "0",
        "-n", "com.byd.avc/.MainActivity",
        // 0x10000000 FLAG_ACTIVITY_NEW_TASK | 0x00010000 FLAG_ACTIVITY_NO_ANIMATION.
        // NOT 0x00020000 — that is FLAG_ACTIVITY_REORDER_TO_FRONT, the OPPOSITE of what this
        // silent warmup wants: it moves an existing com.byd.avc task to the front of its stack,
        // popping the OEM camera UI over whatever the driver is looking at on every keep-alive
        // tick, and with no NO_ANIMATION it does so with the full window animation.
        "-f", "0x10010000"
    };

    private volatile Thread keepAliveThread;
    private boolean keepAliveDesired;
    private long keepAliveGeneration;
    private static final long KEEP_ALIVE_STOP_TIMEOUT_MS = 7000L;
    private static final long KEEP_ALIVE_RETRY_INITIAL_MS = 250L;
    private static final long KEEP_ALIVE_RETRY_MAX_MS = 5000L;
    private long keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
    private boolean keepAliveHandoffScheduled;
    private Thread keepAliveHandoffOwner;

    /**
     * Tracks consecutive failed launch attempts (instance scope — warmupAndWait
     * + keep-alive ticks). After {@link #LAUNCH_FAILURE_ESCALATE_THRESHOLD}
     * consecutive failures we escalate to a force-stop + restart of
     * com.byd.avc to recover from a wedged HAL co-consumer state.
     *
     * <p>Reset to 0 after a successful launch OR after an escalation runs.
     * Legacy fleet only — dilink4 doesn't launch AVC at all.
     */
    private int consecutiveLaunchFailures = 0;

    /**
     * Counter for static {@link #ensureAvcAlive()} keep-alive callers
     * (AccSentryDaemon, CameraDaemon mode tick). Static because the method
     * is static and may be invoked from contexts that don't own an
     * AvcHalWarmup instance. Same semantics as the instance counter.
     */
    private static int staticConsecutiveLaunchFailures = 0;

    /** Threshold for escalating to force-stop + restart. */
    private static final int LAUNCH_FAILURE_ESCALATE_THRESHOLD = 3;
    private static final long AVC_COMMAND_TIMEOUT_SECONDS = 5L;
    private static final long AVC_PROBE_TIMEOUT_SECONDS = 2L;
    private static final Object AVC_PROCESS_LANE = new Object();
    private static final java.util.concurrent.ScheduledExecutorService
            KEEP_ALIVE_HANDOFF_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "AvcKeepAliveHandoff");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Minimum interval between consecutive force-stop + restart escalations
     * (audit avc-yield "forceRestartAvc self-evicts legitimate co-consumer").
     * On low-mem-killer pressure, am-start can flake every 180s. Without a
     * cooldown each escalation force-stops com.byd.avc, which cascades into
     * pano onCameraError + full close+reopen. 5min cap keeps recovery
     * possible while preventing churn against a structural flake.
     */
    private static final long FORCE_RESTART_COOLDOWN_MS = 5L * 60_000L;

    /** Last forceRestartAvc time (instance scope). 0 = never. */
    private long lastForceRestartMs = 0L;

    /** Last forceRestartAvcStatic time (static scope). 0 = never. */
    private static volatile long lastForceRestartStaticMs = 0L;

    public AvcHalWarmup() {
    }

    // ==================== One-Shot Warmup ====================

    /**
     * Launches com.byd.avc and blocks for HAL_WARMUP_DELAY_MS.
     * Call this BEFORE opening the camera on ACC ON transitions.
     *
     * This is a blocking call — run it on a background thread.
     *
     * @return true if warmup completed, false if interrupted
     */
    public boolean warmupAndWait() {
        boolean dilink4 = isDilink4Mode();
        if (dilink4) {
            // OEM-PARITY: oem does NOT launch com.byd.avc anywhere in its
            // panorama-camera flow. The 4 s blocking sleep + `am start
            // com.byd.avc/.MainActivity` was OverDrive-specific and
            // suspected of stealing the HAL's mosaic mode (PANORAMA_OUTPUT_STATE=7).
            // Skip the warmup entirely on dilink4. ensureAvcAlive() (pidof +
            // conditional am start, no sleep) still runs separately for the
            // multi-consumer keep-alive case; that's the closest behaviour
            // oem's environment naturally provides without an explicit
            // launch.
            logger.info("dilink4: skipping warmupAndWait (oem-parity — oem never launches com.byd.avc explicitly)");
            return true;
        }

        logger.info("Warming up camera HAL via com.byd.avc (waiting " +
            HAL_WARMUP_DELAY_MS + "ms)...");
        boolean launched = launchAvc();
        if (launched) {
            consecutiveLaunchFailures = 0;
        } else {
            consecutiveLaunchFailures++;
            if (consecutiveLaunchFailures >= LAUNCH_FAILURE_ESCALATE_THRESHOLD) {
                logger.warn("warmupAndWait: " + consecutiveLaunchFailures
                    + " consecutive AVC launch failures — escalating to force-stop+restart");
                forceRestartAvc();
                consecutiveLaunchFailures = 0;
            }
        }

        try {
            Thread.sleep(HAL_WARMUP_DELAY_MS);
            logger.info("HAL warmup complete — safe to open camera");
            return true;
        } catch (InterruptedException e) {
            logger.warn("HAL warmup interrupted");
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Keep-Alive Watchdog ====================

    /**
     * Starts the 60-second keep-alive watchdog.
     * Periodically re-launches com.byd.avc to prevent the system from killing it.
     *
     * Only runs while ACC is ON and pipeline is active.
     * Call this after the pipeline has started successfully.
     */
    public synchronized void startKeepAlive() {
        if (keepAliveDesired
                && keepAliveThread != null
                && keepAliveThread.isAlive()) {
            logger.info("Keep-alive already running");
            return;
        }

        keepAliveDesired = true;
        keepAliveGeneration++;
        if (keepAliveThread != null && keepAliveThread.isAlive()) {
            keepAliveThread.interrupt();
            logger.info("Keep-alive restart queued behind prior worker termination");
            return;
        }
        keepAliveThread = null;
        tryStartKeepAliveWorkerLocked(keepAliveGeneration);
    }

    private void tryStartKeepAliveWorkerLocked(final long generation) {
        try {
            startKeepAliveWorkerLocked(generation);
        } catch (Throwable constructionFailure) {
            keepAliveThread = null;
            logger.warn("AVC keep-alive worker construction failed: "
                + constructionFailure.getMessage());
            scheduleKeepAliveHandoffLocked(null);
        }
    }

    private void startKeepAliveWorkerLocked(final long generation) {
        final Thread worker = new Thread(() -> {
            logger.info("AVC keep-alive watchdog started (interval=" +
                KEEP_ALIVE_INTERVAL_MS / 1000 + "s)");

            try {
                while (isKeepAliveWorkerCurrent(
                        Thread.currentThread(), generation)
                        && !Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(KEEP_ALIVE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (!isKeepAliveWorkerCurrent(
                            Thread.currentThread(), generation)) {
                        break;
                    }

                    // OEM-PARITY: on dilink4 do NOT launch com.byd.avc.
                    if (isDilink4Mode()) {
                        try {
                            BydApaViewpointHelper.reassertIfHeld();
                        } catch (Throwable t) {
                            logger.warn("Keep-alive viewpoint re-assert failed: "
                                + t.getMessage());
                        }
                        logger.info("Keep-alive tick (dilink4): skipping AVC re-launch (oem-parity)");
                        continue;
                    }

                    int avcPid = probeAvcPid();
                    if (!isKeepAliveWorkerCurrent(
                            Thread.currentThread(), generation)) {
                        break;
                    }
                    if (avcPid > 0) {
                        consecutiveLaunchFailures = 0;
                        continue;
                    }
                    logger.info("Keep-alive: com.byd.avc not running (pidof miss) — re-launching (accOn=" +
                        AccMonitor.isAccOn() + ")");
                    boolean launched = launchAvc();
                    if (!isKeepAliveWorkerCurrent(
                            Thread.currentThread(), generation)) {
                        break;
                    }
                    if (launched) {
                        consecutiveLaunchFailures = 0;
                    } else {
                        consecutiveLaunchFailures++;
                        if (consecutiveLaunchFailures >= LAUNCH_FAILURE_ESCALATE_THRESHOLD) {
                            logger.warn("Keep-alive: " + consecutiveLaunchFailures
                                + " consecutive AVC launch failures — escalating to force-stop+restart");
                            forceRestartAvc();
                            consecutiveLaunchFailures = 0;
                        }
                    }
                }
            } finally {
                logger.info("AVC keep-alive watchdog stopped");
                synchronized (AvcHalWarmup.this) {
                    if (keepAliveThread == Thread.currentThread()) {
                        scheduleKeepAliveHandoffLocked(
                            Thread.currentThread());
                    }
                }
            }
        }, "AvcKeepAlive");

        worker.setDaemon(true);
        keepAliveThread = worker;
        try {
            worker.start();
            keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
        } catch (Throwable failure) {
            logger.warn("AVC keep-alive worker failed to start: "
                + failure.getMessage());
            if (keepAliveThread == worker) {
                scheduleKeepAliveHandoffLocked(worker);
            }
        }
    }

    private void scheduleKeepAliveHandoffLocked(Thread owner) {
        keepAliveHandoffOwner = owner;
        if (keepAliveHandoffScheduled) {
            return;
        }

        final long delayMs = keepAliveRetryDelayMs;
        keepAliveRetryDelayMs = Math.min(
            keepAliveRetryDelayMs * 2L, KEEP_ALIVE_RETRY_MAX_MS);
        keepAliveHandoffScheduled = true;
        try {
            KEEP_ALIVE_HANDOFF_SCHEDULER.schedule(
                this::completeKeepAliveHandoff,
                delayMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable schedulingFailure) {
            logger.warn("AVC keep-alive handoff scheduling failed: "
                + schedulingFailure.getMessage());
            startKeepAliveHandoffFallbackLocked(delayMs);
        }
    }

    private void startKeepAliveHandoffFallbackLocked(long delayMs) {
        final Thread fallback;
        try {
            fallback = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                completeKeepAliveHandoff();
            }, "AvcKeepAliveHandoffFallback");
            fallback.setDaemon(true);
            fallback.start();
        } catch (Throwable fallbackFailure) {
            keepAliveHandoffScheduled = false;
            logger.warn("AVC keep-alive handoff fallback failed to start: "
                + fallbackFailure.getMessage());
        }
    }

    private void completeKeepAliveHandoff() {
        synchronized (this) {
            Thread owner = keepAliveHandoffOwner;
            keepAliveHandoffOwner = null;
            keepAliveHandoffScheduled = false;
            if (owner == null) {
                if (keepAliveDesired && keepAliveThread == null) {
                    tryStartKeepAliveWorkerLocked(keepAliveGeneration);
                }
                return;
            }
            if (keepAliveThread != owner) {
                return;
            }
            if (owner.isAlive()) {
                scheduleKeepAliveHandoffLocked(owner);
                return;
            }

            keepAliveThread = null;
            if (keepAliveDesired) {
                tryStartKeepAliveWorkerLocked(keepAliveGeneration);
            } else {
                keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
            }
        }
    }

    /**
     * Stops the keep-alive watchdog.
     * Call when pipeline stops, ACC goes OFF, or daemon shuts down.
     */
    public void stopKeepAlive() {
        final Thread target;
        synchronized (this) {
            if (!keepAliveDesired && keepAliveThread == null) return;
            keepAliveDesired = false;
            keepAliveGeneration++;
            keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
            target = keepAliveThread;
            if (target != null) {
                target.interrupt();
            }
        }

        if (target != null && target != Thread.currentThread()) {
            try {
                target.join(KEEP_ALIVE_STOP_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (keepAliveThread == target
                    && (target == null || !target.isAlive())) {
                keepAliveThread = null;
            } else if (target != null && target.isAlive()) {
                logger.warn("AVC keep-alive worker still terminating; "
                    + "ownership retained and replacement suppressed");
            }
        }
        logger.info("AVC keep-alive stopped");
    }

    private synchronized boolean isKeepAliveWorkerCurrent(
            Thread worker, long generation) {
        return keepAliveDesired
                && keepAliveGeneration == generation
                && keepAliveThread == worker;
    }

    /**
     * Whether the keep-alive watchdog is currently running.
     */
    public synchronized boolean isActive() {
        return keepAliveDesired
                || keepAliveHandoffScheduled
                || keepAliveHandoffOwner != null
                || (keepAliveThread != null
                    && keepAliveThread.isAlive());
    }

    // ==================== Internal ====================

    /**
     * Silently launches com.byd.avc via am start.
     * Runs as UID 2000 (shell) — has permission to launch activities.
     * Uses FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION to avoid
     * bringing it to the foreground or showing any visual disruption.
     *
     * @return true if {@code am start} exited 0, false on any non-zero exit
     *         OR exception. Used by caller to drive the legacy
     *         force-stop+restart escalation when AVC wedges its HAL
     *         co-consumer state.
     */
    private boolean launchAvc() {
        synchronized (AVC_PROCESS_LANE) {
        // audit avc-yield (round 7, finding stuck-warmup-pins-warmupInFlight):
        // Process.waitFor() with no timeout can block forever if system_server /
        // ActivityManagerService is wedged or binder is back-pressured under
        // memory pressure. Because launchAvc is called inside the warmup
        // worker thread spawned by RecordingModeManager.activateModeWithWarmup,
        // a hung waitFor pins warmupInFlight=true forever (finally never runs)
        // → all subsequent activations / wedge-retries / gear handlers
        // CAS-coalesce and silently succeed-no-op. The only recovery is
        // daemon respawn — exactly the wedge type this audit hunts for.
        // Cap the wait at 5 s and destroyForcibly() on timeout. Worst case:
        // we report a launch failure (caller's existing failure counter
        // triggers the legacy force-restart escalation a few ticks later).
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                logger.warn("am start com.byd.avc did not exit within 5s — "
                    + "destroyForcibly + treating as failure (avoids "
                    + "warmupInFlight pin)");
                try {
                    process.destroyForcibly();
                } catch (Throwable th) {
                    logger.warn("destroyForcibly errored: " + th.getMessage());
                }
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("am start com.byd.avc exited with code " + exitCode);
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            terminateProcess(process);
            Thread.currentThread().interrupt();
            logger.warn("AVC launch interrupted");
            return false;
        } catch (Exception e) {
            logger.warn("Failed to launch com.byd.avc: " + e.getMessage());
            if (process != null) {
                try { process.destroyForcibly(); } catch (Throwable ignored) {}
            }
            return false;
        }
        }
    }

    /**
     * LEGACY-ONLY escalation: force-stop com.byd.avc, brief settle, then
     * relaunch. Used when {@link #consecutiveLaunchFailures} crosses the
     * threshold — re-poking a wedged process won't unstick it, but a
     * full kill+respawn forces the system to rebuild the HAL co-consumer
     * registration cleanly.
     *
     * <p>Self-gates on dilink4: dilink4 cooperates with AVC and we never
     * want to force-stop it there. ensureAvcAlive's dilink4 branch never
     * increments the failure counter anyway, but the guard makes this
     * helper safe to call from any context.
     */
    private void forceRestartAvc() {
        synchronized (AVC_PROCESS_LANE) {
        boolean isDilink4 = false;
        try {
            isDilink4 = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
        } catch (Throwable ignored) {}
        if (isDilink4) {
            logger.warn("forceRestartAvc: skipped on dilink4 (cooperates with AVC)");
            return;
        }
        long now = System.currentTimeMillis();
        long sinceLast = now - lastForceRestartMs;
        if (lastForceRestartMs > 0L && sinceLast < FORCE_RESTART_COOLDOWN_MS) {
            // audit avc-yield: prevent escalation churn under transient
            // low-mem-killer pressure. Force-stopping com.byd.avc cascades
            // into pano onCameraError + close+reopen on the live consumer.
            logger.warn("forceRestartAvc: COOLDOWN — skipped (last escalation "
                + sinceLast + "ms ago, cooldown="
                + FORCE_RESTART_COOLDOWN_MS + "ms)");
            return;
        }
        lastForceRestartMs = now;
        logger.warn("forceRestartAvc: force-stopping com.byd.avc and restarting");
        Process forceStop = null;
        try {
            forceStop = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", "am force-stop com.byd.avc"});
            if (!forceStop.waitFor(
                    AVC_PROBE_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(forceStop);
                logger.warn("forceRestartAvc: force-stop timed out");
            }
            Thread.sleep(500);
        } catch (Throwable t) {
            terminateProcess(forceStop);
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("force-stop com.byd.avc failed: " + t.getMessage());
        }
        launchAvc();  // restart
        }
    }

    /**
     * Static counterpart of {@link #forceRestartAvc()} — same legacy-only
     * gating + force-stop + relaunch, but reachable from the static
     * {@link #ensureAvcAlive()} keep-alive path.
     */
    private static void forceRestartAvcStatic() {
        synchronized (AVC_PROCESS_LANE) {
        boolean isDilink4 = false;
        try {
            isDilink4 = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
        } catch (Throwable ignored) {}
        if (isDilink4) {
            logger.warn("forceRestartAvcStatic: skipped on dilink4 (cooperates with AVC)");
            return;
        }
        long now = System.currentTimeMillis();
        long sinceLast = now - lastForceRestartStaticMs;
        if (lastForceRestartStaticMs > 0L && sinceLast < FORCE_RESTART_COOLDOWN_MS) {
            // audit avc-yield: cooldown gate — see forceRestartAvc().
            logger.warn("forceRestartAvcStatic: COOLDOWN — skipped (last escalation "
                + sinceLast + "ms ago, cooldown="
                + FORCE_RESTART_COOLDOWN_MS + "ms)");
            return;
        }
        lastForceRestartStaticMs = now;
        logger.warn("forceRestartAvcStatic: force-stopping com.byd.avc and restarting");
        Process forceStop = null;
        try {
            forceStop = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", "am force-stop com.byd.avc"});
            if (!forceStop.waitFor(
                    AVC_PROBE_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(forceStop);
                logger.warn("forceRestartAvcStatic: force-stop timed out");
            }
            Thread.sleep(500);
        } catch (Throwable t) {
            terminateProcess(forceStop);
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("force-stop com.byd.avc failed: " + t.getMessage());
        }
        Process relaunch = null;
        try {
            relaunch = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            if (!relaunch.waitFor(
                    AVC_COMMAND_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(relaunch);
                logger.warn("forceRestartAvcStatic: relaunch timed out");
                return;
            }
            int rc = relaunch.exitValue();
            if (rc != 0) {
                logger.warn("forceRestartAvcStatic: am start exited rc=" + rc);
            }
        } catch (Exception e) {
            terminateProcess(relaunch);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("forceRestartAvcStatic: relaunch failed: " + e.getMessage());
        }
        }
    }

    // ==================== AVC KEEP-ALIVE (DILINK 4) ====================
    //
    // June 2026 reversal: on byd_apa firmware com.byd.avc is a co-consumer
    // of the vendor.byd.avm HAL daemon. Its presence is what keeps the
    // AVM mosaic blender feeding the panoramic producer surface; remove
    // it and our frames go all-zero. So instead of evicting AVC we
    // PROP IT UP — periodic pidof; if absent, am start.
    //
    // ensureAvcAlive() is intended to be called from a long-running
    // keep-alive tick (e.g. AccSentry's 10 s SystemKeepAlive) when the
    // active camera mode is dilink4. Static so any caller can hit it
    // without owning an AvcHalWarmup instance. Idempotent for concurrent
    // callers — `am start` on an already-running activity is a no-op
    // beyond an intent broadcast.

    /**
     * Returns the current pid of com.byd.avc, or -1 if not running / probe
     * failed. Uses `pidof` which is available in toybox on all BYD images
     * we've seen; falls back to -1 silently on parse failure.
     */
    private static int probeAvcPid() {
        synchronized (AVC_PROCESS_LANE) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "pidof", "com.byd.avc" });
            if (!p.waitFor(
                    AVC_PROBE_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(p);
                return -1;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            if (line == null) return -1;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) return -1;
            String[] parts = trimmed.split("\\s+");
            try {
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            terminateProcess(p);
        }
        }
    }

    /**
     * Periodic AVC keep-alive — re-launch com.byd.avc if it's currently
     * NOT running. Returns true if a launch was issued, false if AVC was
     * already alive.
     *
     * <p>Caller is responsible for gating on cameraMode=dilink4 — this
     * method assumes you've decided AVC must stay up.
     */
    public static boolean ensureAvcAlive() {
        synchronized (AVC_PROCESS_LANE) {
        // OEM-PARITY: oem never launches com.byd.avc. On dilink4 we make
        // this a presence-check-only — no `am start`, no relaunch. If AVC
        // is dead, it's dead; we report state and move on. The HAL on
        // calibrated dilink4 firmware delivers mosaic frames without AVC
        // being a live consumer. Suspect cause of black frames in field
        // logs is exactly the AVC `am start` flipping HAL out of mosaic
        // mode (PANORAMA_OUTPUT_STATE=7).
        if (isDilink4Mode()) {
            int pid = probeAvcPid();
            if (pid > 0) {
                return false;
            }
            logger.info("AVC keep-alive (dilink4): pidof returned 0 — NOT relaunching (oem-parity)");
            return false;
        }

        // Legacy fleet: original behaviour — relaunch if absent.
        int pid = probeAvcPid();
        if (pid > 0) {
            // AVC alive: probe success implies the process is at least
            // running. Reset the failure counter so a transient hiccup
            // doesn't accumulate toward an unrelated future escalation.
            staticConsecutiveLaunchFailures = 0;
            return false;
        }
        boolean launched = false;
        Process launch = null;
        try {
            launch = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            if (!launch.waitFor(
                    AVC_COMMAND_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(launch);
                logger.warn("AVC keep-alive: am start timed out");
            } else {
                int rc = launch.exitValue();
                if (rc == 0) {
                    logger.info("AVC keep-alive: re-launched com.byd.avc "
                        + "(was not running)");
                    launched = true;
                } else {
                    logger.warn("AVC keep-alive: am start exited rc=" + rc);
                }
            }
        } catch (InterruptedException interrupted) {
            terminateProcess(launch);
            Thread.currentThread().interrupt();
            logger.warn("AVC keep-alive interrupted");
        } catch (Exception e) {
            terminateProcess(launch);
            logger.warn("AVC keep-alive: " + e.getMessage());
        }

        if (launched) {
            staticConsecutiveLaunchFailures = 0;
            return true;
        }

        staticConsecutiveLaunchFailures++;
        if (staticConsecutiveLaunchFailures >= LAUNCH_FAILURE_ESCALATE_THRESHOLD) {
            logger.warn("AVC keep-alive: " + staticConsecutiveLaunchFailures
                + " consecutive launch failures — escalating to force-stop+restart");
            forceRestartAvcStatic();
            staticConsecutiveLaunchFailures = 0;
        }
        return false;
        }
    }

    private static void terminateProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            process.destroy();
            if (!process.waitFor(
                    250L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(
                    250L, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            try { process.destroyForcibly(); } catch (Throwable ignored) {}
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            try { process.destroyForcibly(); } catch (Throwable ignoredAgain) {}
        }
    }
}
