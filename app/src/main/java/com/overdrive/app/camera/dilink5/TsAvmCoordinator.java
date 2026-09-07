package com.overdrive.app.camera.dilink5;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;
import com.ts.avm.IAvmServiceInterface;
import com.ts.avm.IAvmServiceListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates with the DiLink 5 system AVM service (com.ts.avm.AvmAndroidService)
 * via AIDL for waking up and managing the camera / surround view hardware subsystem.
 */
public class TsAvmCoordinator {

    private static final String TAG = "TsAvmCoordinator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile TsAvmCoordinator sInstance;

    private final Context context;
    private IAvmServiceInterface avmService;
    private final AtomicBoolean isBound = new AtomicBoolean(false);
    private volatile int avmStatus = 0;

    public interface AvmStateListener {
        void onAvmStateChanged(boolean active);
    }

    private final java.util.List<AvmStateListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addListener(AvmStateListener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(AvmStateListener l) {
        if (l != null) listeners.remove(l);
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            avmService = IAvmServiceInterface.Stub.asInterface(service);
            isBound.set(true);
            logger.info("Connected to com.ts.avm.AvmAndroidService (AIDL)");

            try {
                int status = avmService.getAvmStatus();
                avmStatus = status;
                logger.info("Initial AVM Status: " + status);
                notifyListeners(isAvmActive());

                avmService.registerAvmStatusListener(new IAvmServiceListener.Stub() {
                    @Override
                    public void onAvmServiceStatusChanged(int status, String extra) {
                        avmStatus = status;
                        logger.info(String.format("AVM status changed: status=%d, extra='%s'", status, extra));
                        notifyListeners(status > 0 || isAvmProcessAlive());
                    }
                });
            } catch (Exception e) {
                logger.warn("Failed to register AVM listener: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound.set(false);
            avmService = null;
            avmStatus = 0;
            logger.info("Disconnected from com.ts.avm.AvmAndroidService");
            notifyListeners(isAvmProcessAlive());
        }
    };

    private void notifyListeners(boolean active) {
        for (AvmStateListener l : listeners) {
            try {
                l.onAvmStateChanged(active);
            } catch (Throwable t) {
                logger.error("Error in AvmStateListener: " + t.getMessage(), t);
            }
        }
    }

    public static TsAvmCoordinator getInstance(Context context) {
        if (sInstance == null) {
            synchronized (TsAvmCoordinator.class) {
                if (sInstance == null) {
                    sInstance = new TsAvmCoordinator(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private TsAvmCoordinator(Context context) {
        this.context = context;
    }

    public synchronized void bind() {
        if (isBound.get()) return;

        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.ts.avm", "com.ts.avm.AvmAndroidService"));
            boolean ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            logger.info("bindService com.ts.avm returned " + ok);
        } catch (Exception e) {
            logger.warn("Failed to bind com.ts.avm: " + e.getMessage());
        }
    }

    public void startAvm() {
        startAvm(false);
    }

    public void startAvm(boolean force) {
        if (!force && AccMonitor.isAccOn()) {
            logger.warn("startAvm() suppressed: ACC is ON, avoiding OEM 360 collision with display / SurfaceFlinger");
            return;
        }
        bind();
        if (avmService != null) {
            try {
                avmService.startAvm();
                logger.info("startAvm() invoked successfully (force=" + force + ")");
            } catch (Exception e) {
                logger.warn("startAvm() failed: " + e.getMessage());
            }
        }
    }

    public void stopAvm() {
        if (avmService != null) {
            try {
                avmService.stopAvm();
                logger.info("stopAvm() invoked successfully");
            } catch (Exception e) {
                logger.warn("stopAvm() failed: " + e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        return isBound.get() && avmService != null;
    }

    public static boolean isAvmServiceAlive() {
        if (sInstance != null) {
            return sInstance.isConnected();
        }
        return false;
    }

    private static volatile long sLastForegroundCheckMs = 0;
    private static volatile boolean sCachedAvmForeground = false;

    /**
     * Checks if the BYD AVM 360 camera app is currently resumed / visible in the foreground.
     * Cached with a 500ms TTL to avoid repeated shell executions.
     */
    public static boolean isAvmForeground() {
        long now = System.currentTimeMillis();
        if (now - sLastForegroundCheckMs < 500L) {
            return sCachedAvmForeground;
        }
        sLastForegroundCheckMs = now;
        boolean foreground = false;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/sh", "-c",
                    "dumpsys activity activities | grep -m 1 'mResumedActivity'"
            });
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && (line.contains("com.byd.avm") || line.contains("com.ts.avm"))) {
                    foreground = true;
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {}
        sCachedAvmForeground = foreground;
        return foreground;
    }

    public static boolean isAvmProcessAlive() {
        return isAvmForeground();
    }

    public static boolean isAvmActive() {
        if (sInstance != null && sInstance.avmStatus > 0) return true;
        return isAvmForeground();
    }

    public synchronized void unbind() {
        if (isBound.getAndSet(false)) {
            try {
                context.unbindService(connection);
            } catch (Exception ignored) {}
            avmService = null;
        }
    }
}
