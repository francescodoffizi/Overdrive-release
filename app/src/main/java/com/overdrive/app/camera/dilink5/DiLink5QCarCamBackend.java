package com.overdrive.app.camera.dilink5;

import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Backend driver for BYD DiLink 5.0 (Qualcomm Snapdragon SA8155P / QCarCam / AIS).
 * Communicates directly with /vendor/lib64/libais_client.so to stream raw camera frames
 * (1920x1300 @ 30 FPS) for OverDrive's Dashcam, Sentry, and EGL video encoding pipelines.
 */
public class DiLink5QCarCamBackend {

    private static final String TAG = "DiLink5QCarCam";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile Boolean sSupported = null;

    static {
        try {
            try {
                System.loadLibrary("fast_cam_client");
            } catch (Throwable ignored) {}
            System.loadLibrary("surveillance");
        } catch (Throwable t) {
            try {
                try {
                    System.load("/data/local/tmp/libfast_cam_client.so");
                } catch (Throwable ignored) {}
                System.load("/data/local/tmp/libsurveillance.so");
            } catch (Throwable t2) {
                logger.warn("Failed to load libsurveillance.so: " + t2.getMessage());
            }
        }
    }

    private long nativeHandle = 0;
    private final int cameraId;
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private static volatile Process sHardwareProcess = null;

    public static boolean isSupported() {
        if (sSupported != null) return sSupported;
        try {
            sSupported = nativeIsSupported();
        } catch (Throwable t) {
            logger.warn("nativeIsSupported check failed: " + t.getMessage());
            sSupported = false;
        }
        return sSupported;
    }

    public static void main(String[] args) {
        System.out.println("[+] DiLink5QCarCamBackend.isSupported() = " + isSupported());
    }

    public DiLink5QCarCamBackend(int cameraId) {
        this.cameraId = cameraId;
    }

    private static final java.util.Set<DiLink5QCarCamBackend> sActiveInstances =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public static boolean hasActiveStreamingBackend() {
        for (DiLink5QCarCamBackend backend : sActiveInstances) {
            if (backend.isStreaming.get() || backend.nativeHandle != 0) {
                return true;
            }
        }
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.isRunning()) {
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            com.overdrive.app.camera.OemDashcamPipeline oem =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            if (oem != null && oem.isRunning()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static volatile boolean sShutdownHookRegistered = false;

    private static void gracefulStopProcess(String processPattern) {
        try {
            // Stage 1: Graceful SIGTERM so Qualcomm AIS / QCarCam can release DMA buffers and close camera session
            Runtime.getRuntime().exec(new String[]{"pkill", "-15", "-f", processPattern}).waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS);

            // Check if process has exited
            Process checkProc = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", processPattern});
            boolean exited = false;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(checkProc.getInputStream()))) {
                if (reader.readLine() == null) {
                    exited = true;
                }
            } catch (Throwable ignored) {}

            // Stage 2: Fallback to SIGKILL only if process is still lingering
            if (!exited) {
                logger.warn("Process " + processPattern + " did not terminate on SIGTERM, forcing SIGKILL");
                Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", processPattern}).waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            logger.warn("Error in gracefulStopProcess(" + processPattern + "): " + t.getMessage());
        }
    }

    private static volatile boolean sYieldedForReverse = false;
    private static final Object sGearLock = new Object();
    private static java.util.concurrent.ScheduledExecutorService sGearResumeExecutor = null;
    private static volatile boolean sGearListenerRegistered = false;

    private static volatile boolean sYieldedForAccOn = false;
    private static final Object sAccLock = new Object();
    private static java.util.concurrent.ScheduledExecutorService sAccResumeExecutor = null;
    private static volatile boolean sAccListenerRegistered = false;
    private static final Object sSpawnLock = new Object();

    private static volatile int sConsecutiveFailures = 0;
    private static volatile long sLastFailureTimeMs = 0;
    private static volatile long sLastGearChangeTimeMs = 0;
    private static volatile boolean sAvmListenerRegistered = false;

    private static synchronized void ensureAvmListenerRegistered() {
        if (!sAvmListenerRegistered) {
            sAvmListenerRegistered = true;
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (ctx != null) {
                    TsAvmCoordinator coord = TsAvmCoordinator.getInstance(ctx);
                    coord.bind();
                    coord.addListener(active -> {
                        if (active) {
                            logger.info("BYD native AVM (360) became ACTIVE: immediately yielding Qualcomm AIS hardware capture...");
                            terminateHardwareProcess();
                        } else {
                            logger.info("BYD native AVM (360) became INACTIVE: scheduling cooperative capture resumption in 2500ms...");
                            scheduleResumeAfterReverse();
                        }
                    });
                }
            } catch (Throwable t) {
                logger.warn("Failed to register TsAvmCoordinator listener: " + t.getMessage());
            }
        }
    }

    public static boolean isAvmActive() {
        return TsAvmCoordinator.isAvmActive();
    }

    private static synchronized void ensureGearListenerRegistered() {
        if (!sGearListenerRegistered) {
            sGearListenerRegistered = true;
            try {
                com.overdrive.app.monitor.GearMonitor.getInstance().addListener((oldGear, newGear) -> {
                    onGearChanged(oldGear, newGear);
                });
            } catch (Throwable t) {
                logger.warn("Failed to register GearMonitor listener: " + t.getMessage());
            }
        }
    }

    public static void onGearChanged(int oldGear, int newGear) {
        sLastGearChangeTimeMs = System.currentTimeMillis();
        ensureAvmListenerRegistered();
        if (newGear == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
            logger.info("Gear shifted to REVERSE: gracefully yielding Qualcomm AIS capture to native BYD 360 app...");
            sYieldedForReverse = true;
            synchronized (sGearLock) {
                if (sGearResumeExecutor != null) {
                    sGearResumeExecutor.shutdownNow();
                    sGearResumeExecutor = null;
                }
            }
            terminateHardwareProcess();
        } else if (oldGear == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
            logger.info("Gear shifted from REVERSE to " + com.overdrive.app.monitor.GearMonitor.gearToString(newGear) + ": scheduling capture resumption in 3000ms (checking AVM)...");
            sYieldedForReverse = false;
            scheduleResumeAfterReverse();
        } else {
            logger.info("Gear transition " + com.overdrive.app.monitor.GearMonitor.gearToString(oldGear) + " -> " + com.overdrive.app.monitor.GearMonitor.gearToString(newGear) + ": transient settle timer armed (2000ms)");
        }
    }

    private static void scheduleResumeAfterReverse() {
        synchronized (sGearLock) {
            if (sGearResumeExecutor != null) {
                sGearResumeExecutor.shutdownNow();
            }
            sGearResumeExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fast-cam-reverse-resume");
                t.setDaemon(true);
                return t;
            });
            // Cooperative yield allows native BYD 360/AVM view to close cleanly without AIS contention
            sGearResumeExecutor.schedule(() -> {
                int curGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                if (curGear == com.overdrive.app.monitor.GearMonitor.GEAR_R || sYieldedForReverse) {
                    logger.info("Capture resumption cancelled: vehicle is still in REVERSE");
                    return;
                }
                if (sYieldedForAccOn) {
                    logger.info("Capture resumption deferred: vehicle is yielding for ACC-ON");
                    return;
                }
                if (isAvmActive()) {
                    logger.info("Capture resumption deferred: BYD native AVM (360) is still active on screen. Waiting for AVM to close...");
                    scheduleResumeAfterReverse();
                    return;
                }
                if (hasActiveStreamingBackend()) {
                    logger.info("Resuming Qualcomm fast_cam_capture hardware pipeline after reverse/AVM yield...");
                    ensureHardwareProcess();
                }
            }, 3000, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static synchronized void ensureAccListenerRegistered() {
        if (!sAccListenerRegistered) {
            sAccListenerRegistered = true;
            try {
                com.overdrive.app.monitor.AccMonitor.addListener(isAccOn -> {
                    onAccStateChanged(isAccOn);
                });
            } catch (Throwable t) {
                logger.warn("Failed to register AccMonitor listener: " + t.getMessage());
            }
        }
    }

    public static void onAccStateChanged(boolean isAccOn) {
        if (isAccOn) {
            logger.info("ACC switched to ON: gracefully yielding Qualcomm AIS capture to system services (AVM, radar, SystemUI)...");
            sYieldedForAccOn = true;
            synchronized (sAccLock) {
                if (sAccResumeExecutor != null) {
                    sAccResumeExecutor.shutdownNow();
                    sAccResumeExecutor = null;
                }
            }
            terminateHardwareProcess();
            scheduleResumeAfterAccOn();
        } else {
            logger.info("ACC switched to OFF: entering Sentry mode capture");
            sYieldedForAccOn = false;
            synchronized (sAccLock) {
                if (sAccResumeExecutor != null) {
                    sAccResumeExecutor.shutdownNow();
                    sAccResumeExecutor = null;
                }
            }
            if (hasActiveStreamingBackend()) {
                ensureHardwareProcess();
            }
        }
    }

    private static void scheduleResumeAfterAccOn() {
        synchronized (sAccLock) {
            if (sAccResumeExecutor != null) {
                sAccResumeExecutor.shutdownNow();
            }
            sAccResumeExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fast-cam-acc-resume");
                t.setDaemon(true);
                return t;
            });
            // 4000 ms cooperative yield allows native BYD AVM / camera HAL, SystemUI, and vehicle services to initialize cleanly
            sAccResumeExecutor.schedule(() -> {
                sYieldedForAccOn = false;
                if (hasActiveStreamingBackend()) {
                    int curGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                    if (curGear == com.overdrive.app.monitor.GearMonitor.GEAR_R || sYieldedForReverse) {
                        logger.info("Capture resumption after ACC-ON deferred: vehicle is in REVERSE");
                        return;
                    }
                    if (isAvmActive()) {
                        logger.info("Capture resumption after ACC-ON deferred: AVM is active");
                        return;
                    }
                    logger.info("Resuming Qualcomm fast_cam_capture hardware pipeline after ACC-ON yield...");
                    ensureHardwareProcess();
                }
            }, 4000, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    public static synchronized void terminateHardwareProcess() {
        if (sHardwareProcess != null) {
            try {
                sHardwareProcess.destroy(); // sends SIGTERM
                sHardwareProcess.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}
            if (sHardwareProcess != null) {
                try {
                    sHardwareProcess.exitValue();
                } catch (IllegalThreadStateException lingering) {
                    try {
                        sHardwareProcess.destroyForcibly();
                    } catch (Throwable ignored) {}
                }
            }
            sHardwareProcess = null;
        }
        gracefulStopProcess("fast_cam_capture");
        try {
            Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "/data/local/tmp/fast_cam_capture"}).waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {}
    }

    private static void ensureHardwareProcess() {
        synchronized (sSpawnLock) {
            try {
                ensureGearListenerRegistered();
                ensureAccListenerRegistered();
                ensureAvmListenerRegistered();

                if (sYieldedForAccOn) {
                    logger.info("Skipping ensureHardwareProcess: vehicle currently yielding for ACC-ON transition");
                    return;
                }

                int curGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                if (sYieldedForReverse || curGear == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
                    logger.info("Skipping ensureHardwareProcess: vehicle currently in REVERSE (yielding to native AVM)");
                    return;
                }

                if (isAvmActive()) {
                    logger.info("Skipping ensureHardwareProcess: BYD native 360 camera (AVM) is currently ACTIVE (yielding camera hardware pipeline)");
                    return;
                }

                // Settle delay on gear transitions: give BYD native AVM / camera HAL 2000ms to stabilize
                long timeSinceGearChange = System.currentTimeMillis() - sLastGearChangeTimeMs;
                if (timeSinceGearChange < 2000L) {
                    long settleDelay = 2000L - timeSinceGearChange;
                    logger.info("Deferring ensureHardwareProcess by " + settleDelay + "ms for vehicle transition settle...");
                    Thread.sleep(settleDelay);
                }

                // Check if fast_cam_capture is already running
                Process checkPgrep = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", "fast_cam_capture"});
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(checkPgrep.getInputStream()));
                String line = reader.readLine();
                checkPgrep.waitFor();
                if (line != null && !line.trim().isEmpty()) {
                    logger.info("Qualcomm fast_cam_capture hardware pipeline already running (PID: " + line.trim() + ")");
                    return;
                }

                logger.info("Starting Qualcomm fast_cam_capture hardware capture supervisor...");

                // Terminate any obsolete processes
                terminateHardwareProcess();

                // Ensure fast_cam_capture binary exists in /data/local/tmp and is executable
                String binPath = "/data/local/tmp/fast_cam_capture";
                java.io.File binFile = new java.io.File(binPath);
                if (!binFile.exists() || binFile.length() == 0) {
                    ensureDaemonBinaryExtracted(binFile);
                }
                if (binFile.exists()) {
                    binFile.setReadable(true, false);
                    binFile.setExecutable(true, false);
                }

                ProcessBuilder pb = new ProcessBuilder(
                        "/system/bin/sh", "-c",
                        "export LD_LIBRARY_PATH=/vendor/lib64:/system/lib64:/data/local/tmp && exec " + binPath + " --all --time 0"
                );
                pb.redirectErrorStream(true);
                sHardwareProcess = pb.start();

                // Register shutdown hook once so termination on daemon shutdown or reinstall is guaranteed
                if (!sShutdownHookRegistered) {
                    sShutdownHookRegistered = true;
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        terminateHardwareProcess();
                    }, "fast-cam-shutdown-hook"));
                }

                // Asynchronously drain stdout/stderr to prevent pipe buffer saturation (64KB deadlock)
                final Process proc = sHardwareProcess;
                Thread drainer = new Thread(() -> {
                    try (java.io.BufferedReader streamReader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                        String drainLine;
                        while ((drainLine = streamReader.readLine()) != null) {
                            logger.info("[FastCamProc] " + drainLine);
                        }
                    } catch (Throwable ignored) {}

                    int exitCode = -1;
                    try {
                        exitCode = proc.exitValue();
                    } catch (Throwable ignored) {}

                    if (sHardwareProcess == proc) {
                        sHardwareProcess = null;
                    } else if (sHardwareProcess != null) {
                        return;
                    }

                    if (exitCode == 42) {
                        logger.info("Qualcomm fast_cam_capture exited cleanly due to hardware preemption (exit code 42)");
                    } else if (exitCode != 0) {
                        long now = System.currentTimeMillis();
                        if (now - sLastFailureTimeMs < 60000L) {
                            sConsecutiveFailures++;
                        } else {
                            sConsecutiveFailures = 1;
                        }
                        sLastFailureTimeMs = now;
                    }

                    if (hasActiveStreamingBackend()) {
                        if (sYieldedForAccOn) {
                            logger.info("Suppressing auto-recovery: vehicle is yielding for ACC-ON transition");
                            return;
                        }

                        int gear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                        if (gear == com.overdrive.app.monitor.GearMonitor.GEAR_R || sYieldedForReverse) {
                            logger.info("Suppressing auto-recovery: vehicle is in REVERSE, waiting for gear change to resume");
                            return;
                        }

                        if (isAvmActive()) {
                            logger.info("Suppressing auto-recovery: BYD AVM is active on screen");
                            return;
                        }

                        // Circuit breaker: backoff exponentially if failing repeatedly in short window
                        long backoffMs;
                        if (exitCode == 42) {
                            backoffMs = 5000L;
                        } else if (sConsecutiveFailures >= 3) {
                            backoffMs = 30000L;
                            logger.warn("Qualcomm fast_cam_capture CIRCUIT BREAKER tripped (" + sConsecutiveFailures
                                    + " consecutive crashes in <60s). Pausing auto-recovery for 30s to protect GPU memory and system stability.");
                        } else if (sConsecutiveFailures == 2) {
                            backoffMs = 10000L;
                            logger.warn("Qualcomm fast_cam_capture repeated exit (code " + exitCode + ", attempt 2/3). Scheduling recovery in 10s...");
                        } else {
                            backoffMs = 5000L;
                            logger.warn("Qualcomm fast_cam_capture process exited (code " + exitCode + "). Scheduling auto-recovery in " + backoffMs + "ms...");
                        }

                        try {
                            Thread.sleep(backoffMs);
                            // Re-verify vehicle state after backoff before respawning
                            int postSleepGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                            if (postSleepGear == com.overdrive.app.monitor.GearMonitor.GEAR_R || sYieldedForReverse || sYieldedForAccOn || isAvmActive()) {
                                logger.info("Suppressing auto-recovery: vehicle state changed during backoff");
                                return;
                            }
                            if (hasActiveStreamingBackend()) {
                                ensureHardwareProcess();
                            }
                        } catch (Throwable t) {
                            logger.error("Auto-recovery supervisor failed: " + t.getMessage(), t);
                        }
                    }
                }, "fast-cam-capture-drainer");
                drainer.setDaemon(true);
                drainer.start();

                logger.info("Qualcomm fast_cam_capture hardware pipeline started successfully via supervisor.");
            } catch (Throwable t) {
                logger.error("Failed to start Qualcomm fast_cam_capture hardware supervisor: " + t.getMessage(), t);
            }
        }
    }

    private static void ensureDaemonBinaryExtracted(java.io.File dst) {
        try {
            // 0. Try standard Android Context assets if available
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (ctx != null) {
                    try (java.io.InputStream in = ctx.getAssets().open("dilink5/fast_cam_capture");
                         java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                        dst.setReadable(true, false);
                        dst.setExecutable(true, false);
                        logger.info("Extracted fast_cam_capture from Context assets");
                        return;
                    }
                }
            } catch (Throwable t) {
                logger.warn("Context assets extraction skipped: " + t.getMessage());
            }

            // 1. Search via environment CLASSPATH (app_process daemon primary mechanism)
            String envClasspath = System.getenv("CLASSPATH");
            if (envClasspath != null && !envClasspath.isEmpty()) {
                for (String cpEntry : envClasspath.split(":")) {
                    if (cpEntry.endsWith(".apk") && (cpEntry.contains("com.overdrive.app") || new java.io.File(cpEntry).exists())) {
                        java.io.File apkFile = new java.io.File(cpEntry);
                        if (apkFile.exists() && apkFile.canRead()) {
                            if (extractFromZip(apkFile, "assets/dilink5/fast_cam_capture", dst)) {
                                logger.info("Extracted fast_cam_capture from CLASSPATH APK: " + cpEntry);
                                return;
                            }
                        }
                    }
                }
            }

            // 2. Check Context package code path
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (ctx != null) {
                    String pkgCodePath = ctx.getPackageCodePath();
                    if (pkgCodePath != null) {
                        java.io.File apkFile = new java.io.File(pkgCodePath);
                        if (apkFile.exists() && apkFile.canRead()) {
                            if (extractFromZip(apkFile, "assets/dilink5/fast_cam_capture", dst)) {
                                logger.info("Extracted fast_cam_capture from Context package code path: " + pkgCodePath);
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warn("Context package code path check skipped: " + t.getMessage());
            }

            // 3. Fallback: Query package manager path via shell
            try {
                Process pmProc = Runtime.getRuntime().exec(new String[]{"pm", "path", "com.overdrive.app"});
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(pmProc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("package:")) {
                            String apkPath = line.substring("package:".length()).trim();
                            java.io.File apkFile = new java.io.File(apkPath);
                            if (apkFile.exists() && apkFile.canRead()) {
                                if (extractFromZip(apkFile, "assets/dilink5/fast_cam_capture", dst)) {
                                    logger.info("Extracted fast_cam_capture from pm path: " + apkPath);
                                    return;
                                }
                            }
                        }
                    }
                }
                pmProc.waitFor();
            } catch (Throwable t) {
                logger.warn("pm path fallback skipped: " + t.getMessage());
            }

            // 4. Recursive scan in /data/app (handling Android 11+ ~~hash/ subdirectories)
            java.io.File dataApp = new java.io.File("/data/app");
            if (dataApp.exists() && dataApp.isDirectory()) {
                findAndExtractInDir(dataApp, dst, 0, 3);
                if (dst.exists() && dst.length() > 0) {
                    logger.info("Extracted fast_cam_capture via /data/app recursive scan");
                    return;
                }
            }

            // 5. Fallback: check java.class.path JVM property
            String propClasspath = System.getProperty("java.class.path");
            if (propClasspath != null && !propClasspath.isEmpty()) {
                for (String cpEntry : propClasspath.split(":")) {
                    if (cpEntry.endsWith(".apk") && new java.io.File(cpEntry).exists()) {
                        if (extractFromZip(new java.io.File(cpEntry), "assets/dilink5/fast_cam_capture", dst)) {
                            logger.info("Extracted fast_cam_capture from java.class.path: " + cpEntry);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.warn("ensureDaemonBinaryExtracted failed: " + t.getMessage());
        }
    }

    private static boolean findAndExtractInDir(java.io.File dir, java.io.File dst, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > maxDepth) return false;
        java.io.File[] files = dir.listFiles();
        if (files == null) return false;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                if (findAndExtractInDir(f, dst, depth + 1, maxDepth)) return true;
            } else if (f.getName().endsWith(".apk") && f.getAbsolutePath().contains("com.overdrive.app")) {
                if (extractFromZip(f, "assets/dilink5/fast_cam_capture", dst)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean extractFromZip(java.io.File zipFile, String entryPath, java.io.File dst) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            java.util.zip.ZipEntry entry = zf.getEntry(entryPath);
            if (entry != null) {
                // If destination already exists with the exact same non-zero size, no need to re-write
                if (dst.exists() && dst.length() == entry.getSize() && entry.getSize() > 0) {
                    dst.setReadable(true, false);
                    dst.setExecutable(true, false);
                    return true;
                }
                // Overwrite stale/mismatched file
                java.io.File tmpDst = new java.io.File(dst.getParentFile(), dst.getName() + ".tmp." + System.currentTimeMillis());
                try (java.io.InputStream in = zf.getInputStream(entry);
                     java.io.OutputStream out = new java.io.FileOutputStream(tmpDst)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
                if (tmpDst.exists() && tmpDst.length() > 0) {
                    if (dst.exists()) dst.delete();
                    tmpDst.renameTo(dst);
                    dst.setReadable(true, false);
                    dst.setExecutable(true, false);
                    logger.info("Extracted and deployed " + dst.getName() + " (" + dst.length() + " bytes, mode 755)");
                    return true;
                }
            }
        } catch (Throwable t) {
            logger.warn("extractFromZip failed for " + entryPath + ": " + t.getMessage());
        }
        return false;
    }

    private static void copyFile(java.io.File src, java.io.File dst) {
        try {
            if (dst.exists() && dst.length() == src.length() && src.length() > 0) {
                dst.setReadable(true, false);
                dst.setExecutable(true, false);
                return;
            }
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
            dst.setReadable(true, false);
            dst.setExecutable(true, false);
            logger.info("Copied and deployed " + dst.getName() + " (" + dst.length() + " bytes, mode 755)");
        } catch (Throwable ignored) {}
    }

    public static synchronized void stopHardwareProcess() {
        try {
            if (sHardwareProcess != null) {
                sHardwareProcess.destroy();
                sHardwareProcess = null;
            }
            Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "fast_cam_capture"});
            Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "qcarcam_test"});
            logger.info("Qualcomm fast_cam_capture hardware supervisor stopped.");
        } catch (Throwable t) {
            logger.warn("Error stopping hardware supervisor: " + t.getMessage());
        }
    }

    public synchronized boolean open() {
        if (nativeHandle != 0) return true;
        if (!isSupported()) {
            logger.warn("DiLink 5 QCarCam backend is not supported on this platform.");
            return false;
        }

        sActiveInstances.add(this);
        ensureHardwareProcess();

        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx != null) {
                TsAvmCoordinator.getInstance(ctx).bind();
                TsAvmCoordinator.getInstance(ctx).startAvm();
            }
        } catch (Throwable t) {
            logger.warn("TsAvmCoordinator start error: " + t.getMessage());
        }

        try {
            nativeHandle = nativeInit(cameraId);
            if (nativeHandle == 0) {
                logger.error("nativeInit(" + cameraId + ") failed to open camera handle.");
                return false;
            }
            logger.info("DiLink 5 QCarCam handle opened successfully: 0x" + Long.toHexString(nativeHandle));
            return true;
        } catch (Throwable t) {
            logger.error("Error opening DiLink 5 QCarCam backend", t);
            return false;
        }
    }

    public synchronized boolean start() {
        if (nativeHandle == 0 && !open()) return false;
        if (isStreaming.get()) return true;

        try {
            boolean ok = nativeStart(nativeHandle);
            if (ok) {
                isStreaming.set(true);
                logger.info("DiLink 5 QCarCam stream started on camera " + cameraId);
            } else {
                logger.warn("nativeStart failed on camera " + cameraId);
            }
            return ok;
        } catch (Throwable t) {
            logger.error("Error starting DiLink 5 QCarCam stream", t);
            return false;
        }
    }

    public synchronized boolean startSurface(android.view.Surface surface) {
        if (nativeHandle == 0 && !open()) return false;
        if (isStreaming.get()) return true;

        try {
            boolean ok = nativeStartSurface(surface);
            if (ok) {
                isStreaming.set(true);
                logger.info("DiLink 5 QCarCam stream started on Surface for camera " + cameraId);
            } else {
                logger.warn("nativeStartSurface failed on camera " + cameraId);
            }
            return ok;
        } catch (Throwable t) {
            logger.error("Error starting DiLink 5 QCarCam stream on Surface", t);
            return false;
        }
    }

    public synchronized void stop() {
        if (nativeHandle != 0 && isStreaming.getAndSet(false)) {
            try {
                nativeStop(nativeHandle);
                logger.info("DiLink 5 QCarCam stream stopped on camera " + cameraId);
            } catch (Throwable t) {
                logger.warn("Error stopping DiLink 5 QCarCam stream: " + t.getMessage());
            }
        }
    }

    public synchronized void close() {
        stop();
        if (nativeHandle != 0) {
            try {
                nativeRelease(nativeHandle);
                logger.info("DiLink 5 QCarCam handle closed.");
            } catch (Throwable t) {
                logger.warn("Error closing DiLink 5 QCarCam: " + t.getMessage());
            }
            nativeHandle = 0;
        }
        sActiveInstances.remove(this);
        if (!hasActiveStreamingBackend()) {
            terminateHardwareProcess();
        }
    }

    public boolean isStreaming() {
        return isStreaming.get();
    }

    public static void setActiveCamera(int camIdx) {
        try {
            nativeSetActiveCamera(camIdx);
            logger.info("Switched active QCarCam camera to: " + camIdx);
        } catch (Throwable t) {
            logger.warn("Failed to set active camera: " + t.getMessage());
        }
    }

    public static void set4KUltraEnabled(boolean enabled) {
        setActiveCamera(enabled ? 5 : 4);
    }

    // --- Native JNI Interface ---
    private static native boolean nativeIsSupported();
    private static native void nativeSetActiveCamera(int camIdx);
    private native long nativeInit(int inputId);
    private native boolean nativeStart(long handle);
    private native boolean nativeStartSurface(android.view.Surface surface);
    private native boolean nativeStop(long handle);
    private native void nativeRelease(long handle);
}
