package com.overdrive.dilinkprobe;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public class Camera1LegacyProbeRunner {

    public interface Listener {
        void onLog(String line);
        void onStatus(String status);
        void onFrameStats(int cameraId, int totalFrames, float fps, int width, int height);
        void onFinished(String summary);
        void onError(String error);
    }

    private final Listener listener;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private Camera camera;
    private SurfaceTexture surfaceTexture;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private long startTimeMs = 0;
    private long lastFpsCalcTimeMs = 0;
    private int lastFpsFrameCount = 0;

    public Camera1LegacyProbeRunner(Listener listener) {
        this.listener = listener;
    }

    public void startProbe(int targetCameraId) {
        if (isRunning.getAndSet(true)) return;

        cameraThread = new HandlerThread("Camera1ProbeThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        cameraHandler.post(() -> runCamera1Stream(targetCameraId));
    }

    public void stopProbe() {
        if (!isRunning.getAndSet(false)) return;

        if (cameraHandler != null) {
            cameraHandler.post(this::cleanup);
        }
    }

    private void runCamera1Stream(int targetCameraId) {
        try {
            int numCameras = Camera.getNumberOfCameras();
            listener.onLog("\n=== [CAMERA1 LEGACY API PROBE] ===");
            listener.onLog("Numero telecamere rilevate da Camera.getNumberOfCameras(): " + numCameras);

            for (int i = 0; i < numCameras; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                String facing = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "FRONT" :
                        info.facing == Camera.CameraInfo.CAMERA_FACING_BACK ? "BACK" : "OTHER";
                listener.onLog(String.format("Camera1 ID %d: facing=%s, orientation=%d°", i, facing, info.orientation));
            }

            int idToOpen = targetCameraId >= 0 && targetCameraId < numCameras ? targetCameraId : 0;
            listener.onLog("\n--- Tentativo apertura Camera1 ID " + idToOpen + " ---");
            listener.onStatus("Apertura Camera1 ID " + idToOpen + "...");

            try {
                camera = Camera.open(idToOpen);
            } catch (Exception e) {
                listener.onError("Camera.open(" + idToOpen + ") fallita: " + e.getMessage());
                stopProbe();
                return;
            }

            if (camera == null) {
                listener.onError("Camera.open(" + idToOpen + ") ha restituito null!");
                stopProbe();
                return;
            }

            listener.onLog("✓ Camera1 ID " + idToOpen + " aperta con successo!");

            Camera.Parameters params = camera.getParameters();
            Camera.Size previewSize = params.getPreviewSize();
            final int w = previewSize != null ? previewSize.width : 1280;
            final int h = previewSize != null ? previewSize.height : 720;
            listener.onLog(String.format("Parametri Camera1: Risoluzione preview=%dx%d", w, h));

            surfaceTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(surfaceTexture);

            frameCounter.set(0);
            startTimeMs = System.currentTimeMillis();
            lastFpsCalcTimeMs = startTimeMs;
            lastFpsFrameCount = 0;

            camera.setPreviewCallback((data, cam) -> {
                if (data != null && isRunning.get()) {
                    int count = frameCounter.incrementAndGet();
                    long now = System.currentTimeMillis();
                    if (now - lastFpsCalcTimeMs >= 1000) {
                        float fps = (count - lastFpsFrameCount) * 1000.0f / (now - lastFpsCalcTimeMs);
                        lastFpsCalcTimeMs = now;
                        lastFpsFrameCount = count;
                        listener.onFrameStats(idToOpen, count, fps, w, h);
                    }
                }
            });

            camera.startPreview();
            listener.onLog("✓ camera.startPreview() avviato! Ricezione frame in corso per 10s...");
            listener.onStatus("Streaming Camera1 attivo su ID " + idToOpen);

            cameraHandler.postDelayed(() -> {
                if (isRunning.get()) {
                    long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
                    int total = frameCounter.get();
                    float avgFps = elapsed > 0 ? (float) total / elapsed : 0f;

                    String report = String.format("Test Camera1 completato!\nID: %d (%dx%d)\nFrame totali: %d in %ds\nFPS medio: %.1f",
                            idToOpen, w, h, total, elapsed, avgFps);
                    listener.onLog("\n" + report);
                    listener.onStatus("Test Camera1 completato");
                    listener.onFinished(report);
                    stopProbe();
                }
            }, 10000);

        } catch (Exception e) {
            listener.onError("Eccezione durante probe Camera1: " + e.getMessage());
            stopProbe();
        }
    }

    private void cleanup() {
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
                camera = null;
            }
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
            if (cameraThread != null) {
                cameraThread.quitSafely();
                cameraThread = null;
            }
        } catch (Exception ignored) {}
        isRunning.set(false);
    }
}
