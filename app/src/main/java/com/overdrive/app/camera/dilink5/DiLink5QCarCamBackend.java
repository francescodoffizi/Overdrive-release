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
            logger.warn("Failed to load libsurveillance.so: " + t.getMessage());
        }
    }

    private long nativeHandle = 0;
    private final int cameraId;
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);

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

    public DiLink5QCarCamBackend(int cameraId) {
        this.cameraId = cameraId;
    }

    public synchronized boolean open() {
        if (nativeHandle != 0) return true;
        if (!isSupported()) {
            logger.warn("DiLink 5 QCarCam backend is not supported on this platform.");
            return false;
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

    // --- Native JNI Interface ---
    private static native boolean nativeIsSupported();
    private native long nativeInit(int inputId);
    private native boolean nativeStart(long handle);
    private native boolean nativeStop(long handle);
    private native void nativeRelease(long handle);
}
