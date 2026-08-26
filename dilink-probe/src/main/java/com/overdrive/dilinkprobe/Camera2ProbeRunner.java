package com.overdrive.dilinkprobe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Camera2ProbeRunner {

    public interface Listener {
        void onLog(String line);
        void onStatus(String status);
        void onFrameStats(int cameraId, int totalFrames, float fps, int width, int height);
        void onFinished(String summary);
        void onError(String error);
    }

    private final Context context;
    private final Listener listener;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraDevice currentCamera;
    private CameraCaptureSession currentSession;
    private ImageReader imageReader;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private long startTimeMs = 0;
    private long lastFpsCalcTimeMs = 0;
    private int lastFpsFrameCount = 0;

    public Camera2ProbeRunner(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void startProbe() {
        if (isRunning.getAndSet(true)) return;

        cameraThread = new HandlerThread("Camera2ProbeThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        cameraHandler.post(this::runCamera2DiscoveryAndStream);
    }

    public void stopProbe() {
        if (!isRunning.getAndSet(false)) return;

        if (cameraHandler != null) {
            cameraHandler.post(this::cleanup);
        }
    }

    @SuppressLint("MissingPermission")
    private void runCamera2DiscoveryAndStream() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                listener.onError("CameraManager non disponibile sul sistema!");
                isRunning.set(false);
                return;
            }

            String[] cameraIds = manager.getCameraIdList();
            listener.onLog("\n=== [CAMERA2 API DISCOVERY] ===");
            listener.onLog("Camera IDs trovati: " + (cameraIds.length > 0 ? String.join(", ", cameraIds) : "<nessuna telecamera esposta>"));

            if (cameraIds.length == 0) {
                listener.onStatus("Nessuna telecamera esposta da CameraManager.");
                listener.onFinished("CameraManager.getCameraIdList() ha restituito 0 telecamere.");
                isRunning.set(false);
                return;
            }

            StringBuilder summary = new StringBuilder();
            summary.append("Telecamere rilevate: ").append(cameraIds.length).append("\n");

            for (String idStr : cameraIds) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(idStr);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

                String facingStr = facing != null ? (facing == CameraCharacteristics.LENS_FACING_FRONT ? "FRONT" :
                        facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "EXTERNAL") : "UNKNOWN";

                listener.onLog(String.format("Camera ID %s: facing=%s, orientation=%d°, hwLevel=%d",
                        idStr, facingStr, sensorOrientation != null ? sensorOrientation : 0, hwLevel != null ? hwLevel : -1));

                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) {
                    Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                    if (yuvSizes != null && yuvSizes.length > 0) {
                        listener.onLog("   -> Risoluzioni YUV_420_888: " + formatSizes(yuvSizes));
                    }
                }
            }

            // Test stream on the first available camera (or all in sequence)
            String targetCameraId = cameraIds[0];
            listener.onLog("\n--- Avvio test stream su Camera ID " + targetCameraId + " per 10 secondi ---");
            listener.onStatus("Apertura Camera ID " + targetCameraId + "...");

            CameraCharacteristics chars = manager.getCameraCharacteristics(targetCameraId);
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size targetSize = new Size(1920, 1080);
            if (map != null) {
                Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                if (yuvSizes != null && yuvSizes.length > 0) {
                    targetSize = yuvSizes[0]; // Choose first/best size
                }
            }

            final int chosenW = targetSize.getWidth();
            final int chosenH = targetSize.getHeight();
            final int camIdInt = Integer.parseInt(targetCameraId);

            listener.onLog("Configurazione ImageReader: " + chosenW + "x" + chosenH + " YUV_420_888");

            imageReader = ImageReader.newInstance(chosenW, chosenH, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img != null) {
                    int count = frameCounter.incrementAndGet();
                    img.close();

                    long now = System.currentTimeMillis();
                    if (now - lastFpsCalcTimeMs >= 1000) {
                        float fps = (count - lastFpsFrameCount) * 1000.0f / (now - lastFpsCalcTimeMs);
                        lastFpsCalcTimeMs = now;
                        lastFpsFrameCount = count;
                        listener.onFrameStats(camIdInt, count, fps, chosenW, chosenH);
                    }
                }
            }, cameraHandler);

            manager.openCamera(targetCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    currentCamera = camera;
                    listener.onLog("✓ Camera ID " + targetCameraId + " APERTA con successo!");
                    listener.onStatus("Camera " + targetCameraId + " connessa. Avvio CaptureSession...");

                    try {
                        CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.addTarget(imageReader.getSurface());

                        camera.createCaptureSession(Collections.singletonList(imageReader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(@NonNull CameraCaptureSession session) {
                                        currentSession = session;
                                        listener.onLog("✓ CaptureSession configurata! Ricezione frame in corso...");
                                        listener.onStatus("Streaming attivo su Camera " + targetCameraId);

                                        startTimeMs = System.currentTimeMillis();
                                        lastFpsCalcTimeMs = startTimeMs;
                                        frameCounter.set(0);
                                        lastFpsFrameCount = 0;

                                        try {
                                            session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                        } catch (CameraAccessException e) {
                                            listener.onError("setRepeatingRequest fallita: " + e.getMessage());
                                        }

                                        // Auto stop after 10 seconds
                                        cameraHandler.postDelayed(() -> {
                                            if (isRunning.get()) {
                                                long elapsed = (System.currentTimeMillis() - startTimeMs) / 1000;
                                                int total = frameCounter.get();
                                                float avgFps = elapsed > 0 ? (float) total / elapsed : 0f;

                                                String report = String.format("Test completato con successo!\nCamera ID: %s (%dx%d)\nFrame totali: %d in %ds\nFPS medio: %.1f",
                                                        targetCameraId, chosenW, chosenH, total, elapsed, avgFps);
                                                listener.onLog("\n" + report);
                                                listener.onStatus("Test stream terminato con successo!");
                                                listener.onFinished(report);
                                                stopProbe();
                                            }
                                        }, 10000);
                                    }

                                    @Override
                                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                                        listener.onError("Configurazione CaptureSession fallita!");
                                        stopProbe();
                                    }
                                }, cameraHandler);

                    } catch (Exception e) {
                        listener.onError("Errore creazione CaptureSession: " + e.getMessage());
                        stopProbe();
                    }
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    listener.onLog("Camera ID " + targetCameraId + " disconnessa.");
                    stopProbe();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    listener.onError("CameraDevice onError (code=" + error + ")");
                    stopProbe();
                }
            }, cameraHandler);

        } catch (Exception e) {
            listener.onError("Eccezione durante probe Camera2: " + e.getMessage());
            stopProbe();
        }
    }

    private void cleanup() {
        try {
            if (currentSession != null) {
                currentSession.close();
                currentSession = null;
            }
            if (currentCamera != null) {
                currentCamera.close();
                currentCamera = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (cameraThread != null) {
                cameraThread.quitSafely();
                cameraThread = null;
            }
        } catch (Exception ignored) {}
        isRunning.set(false);
    }

    private String formatSizes(Size[] sizes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(sizes.length, 4); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sizes[i].getWidth()).append("x").append(sizes[i].getHeight());
        }
        if (sizes.length > 4) sb.append(" (+").append(sizes.length - 4).append(" altre)");
        return sb.toString();
    }
}
