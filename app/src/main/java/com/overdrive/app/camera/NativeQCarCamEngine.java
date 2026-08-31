package com.overdrive.app.camera;

import android.view.Surface;
import com.overdrive.app.logging.DaemonLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance Java/JNI interface for the Direct Qualcomm QCarCam / AIS Hardware Driver (SA8155P).
 * Delivers 30 FPS raw hardware camera frames directly to Surface/ANativeWindow without IPC socket overhead.
 */
public final class NativeQCarCamEngine {

    private static final String TAG = "NativeQCarCamEngine";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile Boolean sSupported = null;
    private static final AtomicBoolean sIsStreaming = new AtomicBoolean(false);

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

    private NativeQCarCamEngine() {}

    /**
     * Checks if Qualcomm QCarCam / AIS Client hardware API (/vendor/lib64/libais_client.so) is supported on this hardware.
     */
    public static boolean isSupported() {
        if (sSupported != null) return sSupported;
        try {
            sSupported = nativeIsSupported();
            logger.info("Qualcomm QCarCam Direct API support: " + sSupported);
        } catch (Throwable t) {
            logger.warn("Native check for QCarCam failed: " + t.getMessage());
            sSupported = false;
        }
        return sSupported;
    }

    /**
     * Starts the hardware QCarCam stream on the specified camera and renders directly to the Surface.
     *
     * @param surface   Target output Surface (e.g. MediaCodec input surface or display surface).
     * @param cameraId  Logical camera ID (0=Front, 1=Right, 2=Rear, 3=Left).
     * @param width     Frame width (e.g. 1920).
     * @param height    Frame height (e.g. 1300 or 1080).
     * @return True if streaming started successfully.
     */
    public static synchronized boolean startStream(Surface surface, int cameraId, int width, int height) {
        if (!isSupported()) {
            logger.warn("Cannot start Native QCarCam stream: hardware API not supported");
            return false;
        }
        if (sIsStreaming.get()) {
            logger.info("Native QCarCam stream already active");
            return true;
        }

        try {
            boolean ok = nativeStart(surface, cameraId, width, height);
            if (ok) {
                sIsStreaming.set(true);
                logger.info("Native QCarCam Direct stream active on Camera " + cameraId + " (" + width + "x" + height + " @ 30 FPS)");
            } else {
                logger.warn("nativeStart failed for Camera " + cameraId);
            }
            return ok;
        } catch (Throwable t) {
            logger.error("Exception starting Native QCarCam stream: " + t.getMessage(), t);
            return false;
        }
    }

    /**
     * Stops the hardware stream and frees all QCarCam resources.
     */
    public static synchronized void stopStream() {
        if (!sIsStreaming.get()) return;
        try {
            nativeStop();
            sIsStreaming.set(false);
            logger.info("Native QCarCam stream stopped successfully");
        } catch (Throwable t) {
            logger.warn("Exception stopping Native QCarCam stream: " + t.getMessage());
        }
    }

    public static boolean isStreaming() {
        return sIsStreaming.get();
    }

    // ================= Native JNI Declarations =================
    private static native boolean nativeIsSupported();
    private static native boolean nativeStart(Surface surface, int cameraId, int width, int height);
    private static native void nativeStop();
}
