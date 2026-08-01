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
 * with AVC on dilink4 like esco does: warm it on entry AND keep-alive ticks
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
    private volatile boolean active = false;

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
            // ESCO-PARITY: esco does NOT launch com.byd.avc anywhere in its
            // panorama-camera flow. The 4 s blocking sleep + `am start
            // com.byd.avc/.MainActivity` was OverDrive-specific and
            // suspected of stealing the HAL's mosaic mode (PANORAMA_OUTPUT_STATE=7).
            // Skip the warmup entirely on dilink4. ensureAvcAlive() (pidof +
            // conditional am start, no sleep) still runs separately for the
            // multi-consumer keep-alive case; that's the closest behaviour
            // esco's environment naturally provides without an explicit
            // launch.
            logger.info("dilink4: skipping warmupAndWait (esco-parity — esco never launches com.byd.avc explicitly)");
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
        if (active) {
            logger.info("Keep-alive already running");
            return;
        }

        active = true;
        keepAliveThread = new Thread(() -> {
            logger.info("AVC keep-alive watchdog started (interval=" +
                KEEP_ALIVE_INTERVAL_MS / 1000 + "s)");

            while (active && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(KEEP_ALIVE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }

                // Double-check conditions before poking
                if (!active) break;

                // ESCO-PARITY: on dilink4 do NOT launch com.byd.avc.
                // esco never launches AVC. ensureAvcAlive() (pidof check +
                // optional non-launching am start) is sufficient as a
                // presence probe; an explicit launch is suspected of
                // stealing the HAL's mosaic mode.
                if (isDilink4Mode()) {
                    // Periodic panorama-viewpoint re-assert. Substitutes for the
                    // reference app's IProcessObserver, which re-issues
                    // setViewpoint(2012) within 50ms of com.byd.avc leaving the
                    // foreground — we can't register one (SET_ACTIVITY_WATCHER is
                    // signature-level and PACKAGE_USAGE_STATS isn't granted), so this
                    // 60s poll is the bounded-recovery equivalent. See
                    // BydApaViewpointHelper.reassertIfHeld() for the full rationale.
                    //
                    // Self-gating and idempotent: a no-op unless something currently
                    // holds the viewpoint, so it can never flip the HAL into mosaic
                    // mode with no consumer attached. This whole branch is dilink4-only,
                    // so legacy pano_h/pano_l keep-alive behaviour is unchanged.
                    try {
                        BydApaViewpointHelper.reassertIfHeld();
                    } catch (Throwable t) {
                        logger.warn("Keep-alive viewpoint re-assert failed: " + t.getMessage());
                    }
                    logger.info("Keep-alive tick (dilink4): skipping AVC re-launch (esco-parity)");
                    continue;
                }
                // pidof-first: only fork `am start` when AVC is actually dead.
                // The legacy loop previously relaunched UNCONDITIONALLY every
                // 60s — each relaunch is a zygote-forked app_process issuing a
                // binder startActivity into AMS (+ up to 5s waitFor), a per-
                // minute system-wide hitch on the shared SoC for what is a
                // no-op when AVC is already running. A toybox `pidof` is far
                // cheaper (no framework link) and is the same presence-probe
                // the dilink4 ensureAvcAlive() path already uses. When AVC IS
                // alive we treat the tick as a success for the escalation
                // counter (it's healthy — nothing to recover).
                int avcPid = probeAvcPid();
                if (avcPid > 0) {
                    consecutiveLaunchFailures = 0;
                    continue;
                }
                logger.info("Keep-alive: com.byd.avc not running (pidof miss) — re-launching (accOn=" +
                    AccMonitor.isAccOn() + ")");
                boolean launched = launchAvc();
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

            logger.info("AVC keep-alive watchdog stopped");
        }, "AvcKeepAlive");

        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    /**
     * Stops the keep-alive watchdog.
     * Call when pipeline stops, ACC goes OFF, or daemon shuts down.
     */
    public synchronized void stopKeepAlive() {
        if (!active) return;

        active = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
        logger.info("AVC keep-alive stopped");
    }

    /**
     * Whether the keep-alive watchdog is currently running.
     */
    public boolean isActive() {
        return active;
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
        } catch (Exception e) {
            logger.warn("Failed to launch com.byd.avc: " + e.getMessage());
            if (process != null) {
                try { process.destroyForcibly(); } catch (Throwable ignored) {}
            }
            return false;
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
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop com.byd.avc"});
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(500);
        } catch (Throwable t) {
            logger.warn("force-stop com.byd.avc failed: " + t.getMessage());
        }
        launchAvc();  // restart
    }

    /**
     * Static counterpart of {@link #forceRestartAvc()} — same legacy-only
     * gating + force-stop + relaunch, but reachable from the static
     * {@link #ensureAvcAlive()} keep-alive path.
     */
    private static void forceRestartAvcStatic() {
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
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop com.byd.avc"});
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(500);
        } catch (Throwable t) {
            logger.warn("force-stop com.byd.avc failed: " + t.getMessage());
        }
        try {
            Process p = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            int rc = p.waitFor();
            if (rc != 0) {
                logger.warn("forceRestartAvcStatic: am start exited rc=" + rc);
            }
        } catch (Exception e) {
            logger.warn("forceRestartAvcStatic: relaunch failed: " + e.getMessage());
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
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "pidof", "com.byd.avc" });
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            try { p.waitFor(); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (line == null) return -1;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) return -1;
            String[] parts = trimmed.split("\\s+");
            try {
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                return -1;
            }
        } catch (Exception e) {
            return -1;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
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
        // ESCO-PARITY: esco never launches com.byd.avc. On dilink4 we make
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
            logger.info("AVC keep-alive (dilink4): pidof returned 0 — NOT relaunching (esco-parity)");
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
        try {
            Process p = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            int rc = p.waitFor();
            if (rc == 0) {
                logger.info("AVC keep-alive: re-launched com.byd.avc "
                    + "(was not running)");
                launched = true;
            } else {
                logger.warn("AVC keep-alive: am start exited rc=" + rc);
            }
        } catch (Exception e) {
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
