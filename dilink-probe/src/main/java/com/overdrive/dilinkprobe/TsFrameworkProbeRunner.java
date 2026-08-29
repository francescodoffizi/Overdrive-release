package com.overdrive.dilinkprobe;

import dalvik.system.PathClassLoader;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class TsFrameworkProbeRunner {

    public interface Listener {
        void onLog(String line);
        void onStatus(String status);
        void onFinished(String summary);
        void onError(String error);
    }

    private final Listener listener;

    public TsFrameworkProbeRunner(Listener listener) {
        this.listener = listener;
    }

    public void runProbe() {
        new Thread(() -> {
            try {
                doProbe();
            } catch (Exception e) {
                listener.onError("Errore scan ts-framework: " + e.getMessage());
            }
        }).start();
    }

    private void doProbe() {
        listener.onLog("\n=== [TS-FRAMEWORK.JAR & SYSTEM LIBS PROBE] ===");
        listener.onStatus("Scansione framework JAR in corso...");

        String dexPath = "/system/framework/ts-framework.jar:/system/framework/ts-platform-library.jar:/system/framework/car-frameworks-service.jar:/system/framework/android.car.jar";
        PathClassLoader loader = new PathClassLoader(dexPath, ClassLoader.getSystemClassLoader());

        String[] candidateClasses = {
                // TS Camera & Video
                "com.ts.camera.CameraManager",
                "com.ts.camera.TsCamera",
                "com.ts.camera.ITsCameraService",
                "com.ts.avm.sdk.AvmManager",
                "com.ts.avm.IAvmServiceInterface",
                "com.ts.car.caradapter.CarAdapterManager",
                "com.ts.appservice.settings.SettingsService",
                // BYD Auto classes
                "android.hardware.bydauto.BYDAutoDeviceManager",
                "android.hardware.bydauto.video.BYDAutoVideoDevice",
                "android.hardware.bydauto.camera.BYDAutoCameraDevice",
                "android.hardware.bydauto.panoramic.BYDAutoPanoramicDevice",
                // QTI / EVS
                "vendor.qti.automotive.qcarcam.V1_0.IQCarCam",
                "android.hardware.automotive.evs.V1_0.IEvsEnumerator",
                "android.hardware.automotive.evs.V1_1.IEvsEnumerator"
        };

        int foundCount = 0;
        for (String className : candidateClasses) {
            try {
                Class<?> clazz = loader.loadClass(className);
                foundCount++;
                listener.onLog("✓ [FOUND IN JAR] " + className);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (Modifier.isPublic(m.getModifiers())) {
                        listener.onLog("   -> " + m.getName() + "(" + formatParams(m.getParameterTypes()) + "): " + m.getReturnType().getSimpleName());
                    }
                }
            } catch (ClassNotFoundException e) {
                listener.onLog("✗ [NOT FOUND] " + className);
            } catch (Throwable t) {
                listener.onLog("! [ERROR] " + className + ": " + t.getMessage());
            }
        }

        String summary = String.format("Scansione completata: trovate %d classi su %d testate.", foundCount, candidateClasses.length);
        listener.onLog("\n" + summary);
        listener.onStatus("Scan ts-framework completato");
        listener.onFinished(summary);
    }

    private String formatParams(Class<?>[] params) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].getSimpleName());
        }
        return sb.toString();
    }
}
