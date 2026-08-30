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
            System.loadLibrary("surveillance");
        } catch (Throwable t) {
            try {
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

    private static synchronized void ensureHardwareProcess() {
        try {
            // Check if qcarcam_test is already running
            Process checkPgrep = Runtime.getRuntime().exec(new String[]{"pgrep", "-f", "qcarcam_test"});
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(checkPgrep.getInputStream()));
            String line = reader.readLine();
            checkPgrep.waitFor();
            if (line != null && !line.trim().isEmpty()) {
                logger.info("Qualcomm QCarCam hardware pipeline already running (PID: " + line.trim() + ")");
                return;
            }

            logger.info("Starting Qualcomm QCarCam hardware capture supervisor...");

            // Ensure 4cam.xml exists in /data/local/tmp
            java.io.File cfgFile = new java.io.File("/data/local/tmp/4cam.xml");
            if (!cfgFile.exists()) {
                String defaultXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<qcarcam_inputs>\n" +
                        "    <input_device>\n" +
                        "        <properties input_id=\"0\"/>\n" +
                        "        <display_setting display_id=\"0\"/>\n" +
                        "        <output_setting nbufs=\"5\"/>\n" +
                        "    </input_device>\n" +
                        "    <input_device>\n" +
                        "        <properties input_id=\"1\"/>\n" +
                        "        <display_setting display_id=\"0\"/>\n" +
                        "        <output_setting nbufs=\"5\"/>\n" +
                        "    </input_device>\n" +
                        "    <input_device>\n" +
                        "        <properties input_id=\"2\"/>\n" +
                        "        <display_setting display_id=\"0\"/>\n" +
                        "        <output_setting nbufs=\"5\"/>\n" +
                        "    </input_device>\n" +
                        "    <input_device>\n" +
                        "        <properties input_id=\"3\"/>\n" +
                        "        <display_setting display_id=\"0\"/>\n" +
                        "        <output_setting nbufs=\"5\"/>\n" +
                        "    </input_device>\n" +
                        "</qcarcam_inputs>\n";
                java.io.FileWriter writer = new java.io.FileWriter(cfgFile);
                writer.write(defaultXml);
                writer.close();
            }

            // Ensure libhook_qcarcam.so exists in /data/local/tmp for LD_PRELOAD
            String hookPath = "/data/local/tmp/libhook_qcarcam.so";
            java.io.File hookFile = new java.io.File(hookPath);
            if (!hookFile.exists() || hookFile.length() == 0) {
                ensureHookLibraryExtracted(hookFile);
            }
            if (hookFile.exists()) {
                hookFile.setReadable(true, false);
                hookFile.setExecutable(true, false);
            }

            String qcarcamBin = "/data/local/tmp/qcarcam_test";
            if (!new java.io.File(qcarcamBin).exists()) {
                qcarcamBin = "/vendor/bin/qcarcam_test";
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "LD_PRELOAD=" + hookPath + " " + qcarcamBin + " -config=/data/local/tmp/4cam.xml"
            );
            pb.redirectErrorStream(true);
            sHardwareProcess = pb.start();

            // Asynchronously drain stdout/stderr to prevent pipe buffer saturation (64KB deadlock)
            final Process proc = sHardwareProcess;
            Thread drainer = new Thread(() -> {
                try (java.io.BufferedReader streamReader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()))) {
                    String drainLine;
                    while ((drainLine = streamReader.readLine()) != null) {
                        logger.info("[QCarCamProc] " + drainLine);
                    }
                } catch (Throwable ignored) {}
            }, "qcarcam-test-drainer");
            drainer.setDaemon(true);
            drainer.start();

            logger.info("Qualcomm QCarCam hardware pipeline started successfully via supervisor.");
        } catch (Throwable t) {
            logger.error("Failed to start Qualcomm QCarCam hardware supervisor: " + t.getMessage(), t);
        }
    }

    private static void ensureHookLibraryExtracted(java.io.File dst) {
        try {
            // 1. Search via environment CLASSPATH (app_process daemon primary mechanism)
            String envClasspath = System.getenv("CLASSPATH");
            if (envClasspath != null && !envClasspath.isEmpty()) {
                for (String cpEntry : envClasspath.split(":")) {
                    if (cpEntry.endsWith(".apk") && (cpEntry.contains("com.overdrive.app") || new java.io.File(cpEntry).exists())) {
                        java.io.File apkFile = new java.io.File(cpEntry);
                        if (apkFile.exists() && apkFile.canRead()) {
                            if (extractFromZip(apkFile, "lib/arm64-v8a/libhook_qcarcam.so", dst) ||
                                extractFromZip(apkFile, "lib/arm64/libhook_qcarcam.so", dst)) {
                                logger.info("Extracted libhook_qcarcam.so from CLASSPATH APK: " + cpEntry);
                                return;
                            }
                        }
                    }
                }
            }

            // 2. Check Context package code path / native library dir
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (ctx != null) {
                    if (ctx.getApplicationInfo() != null && ctx.getApplicationInfo().nativeLibraryDir != null) {
                        java.io.File candidate = new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libhook_qcarcam.so");
                        if (candidate.exists() && candidate.length() > 0) {
                            copyFile(candidate, dst);
                            if (dst.exists() && dst.length() > 0) {
                                logger.info("Copied libhook_qcarcam.so from nativeLibraryDir: " + candidate.getAbsolutePath());
                                return;
                            }
                        }
                    }
                    String pkgCodePath = ctx.getPackageCodePath();
                    if (pkgCodePath != null) {
                        java.io.File apkFile = new java.io.File(pkgCodePath);
                        if (apkFile.exists() && apkFile.canRead()) {
                            if (extractFromZip(apkFile, "lib/arm64-v8a/libhook_qcarcam.so", dst) ||
                                extractFromZip(apkFile, "lib/arm64/libhook_qcarcam.so", dst)) {
                                logger.info("Extracted libhook_qcarcam.so from Context package code path: " + pkgCodePath);
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
                                if (extractFromZip(apkFile, "lib/arm64-v8a/libhook_qcarcam.so", dst) ||
                                    extractFromZip(apkFile, "lib/arm64/libhook_qcarcam.so", dst)) {
                                    logger.info("Extracted libhook_qcarcam.so from pm path: " + apkPath);
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
                    logger.info("Extracted libhook_qcarcam.so via /data/app recursive scan");
                    return;
                }
            }

            // 5. Fallback: check java.class.path JVM property
            String propClasspath = System.getProperty("java.class.path");
            if (propClasspath != null && !propClasspath.isEmpty()) {
                for (String cpEntry : propClasspath.split(":")) {
                    if (cpEntry.endsWith(".apk") && new java.io.File(cpEntry).exists()) {
                        if (extractFromZip(new java.io.File(cpEntry), "lib/arm64-v8a/libhook_qcarcam.so", dst) ||
                            extractFromZip(new java.io.File(cpEntry), "lib/arm64/libhook_qcarcam.so", dst)) {
                            logger.info("Extracted libhook_qcarcam.so from java.class.path: " + cpEntry);
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.warn("ensureHookLibraryExtracted failed: " + t.getMessage());
        }
    }

    private static boolean findAndExtractInDir(java.io.File dir, java.io.File dst, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > maxDepth) return false;
        java.io.File[] files = dir.listFiles();
        if (files == null) return false;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                if (f.getName().equals("lib") || f.getName().equals("arm64") || f.getName().equals("arm64-v8a")) {
                    java.io.File candidate = new java.io.File(f, "libhook_qcarcam.so");
                    if (candidate.exists() && candidate.length() > 0) {
                        copyFile(candidate, dst);
                        if (dst.exists() && dst.length() > 0) return true;
                    }
                }
                if (findAndExtractInDir(f, dst, depth + 1, maxDepth)) return true;
            } else if (f.getName().endsWith(".apk") && f.getAbsolutePath().contains("com.overdrive.app")) {
                if (extractFromZip(f, "lib/arm64-v8a/libhook_qcarcam.so", dst) ||
                    extractFromZip(f, "lib/arm64/libhook_qcarcam.so", dst)) {
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
            Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "qcarcam_test"});
            logger.info("Qualcomm QCarCam hardware supervisor stopped.");
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

    // --- Native JNI Interface ---
    private static native boolean nativeIsSupported();
    private static native void nativeSetActiveCamera(int camIdx);
    private native long nativeInit(int inputId);
    private native boolean nativeStart(long handle);
    private native boolean nativeStartSurface(android.view.Surface surface);
    private native boolean nativeStop(long handle);
    private native void nativeRelease(long handle);
}
