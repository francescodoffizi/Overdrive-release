package com.overdrive.app.monitor;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Gear Monitor — polling-based gear position monitoring.
 * 
 * Uses polling instead of AbsBYDAutoGearboxListener because the BYD framework's
 * internal learningEPB() method crashes with a UID mismatch when running as shell
 * (UID 2000). The crash kills the BYD device manager's HandlerThread and cascades
 * into daemon restart loops.
 * 
 * Polls getGearboxAutoModeType() every 200ms — fast enough for gear change detection
 * while avoiding the listener crash path entirely.
 */
public class GearMonitor {
    private static final DaemonLogger logger = DaemonLogger.getInstance("GearMonitor");
    
    // Gear constants
    public static final int GEAR_P = 1;
    public static final int GEAR_R = 2;
    public static final int GEAR_N = 3;
    public static final int GEAR_D = 4;
    public static final int GEAR_M = 5;
    public static final int GEAR_S = 6;
    
    private static final long POLL_INTERVAL_MS = 200;  // 5 Hz polling
    private static final long CACHED_GEAR_MAX_AGE_MS = 1000L;
    
    private static GearMonitor instance;
    
    private Context context;
    // Volatile because the poll thread reads these without holding the
    // singleton's monitor; concurrent stop() (synchronized) nullifies them.
    // Volatile gives the poll iteration a consistent snapshot per loop turn.
    private volatile Object gearboxDevice;
    private volatile Method getGearMethod;
    private Thread pollThread;
    private volatile boolean isRunning = false;
    private volatile int currentGear = GEAR_P;
    /** Elapsed-realtime timestamp of the last valid gear observation. */
    private volatile long lastUpdateTime = 0;

    /** Whether the 200ms poll thread is active — i.e. getCurrentGear() is fresh
     *  to within ~POLL_INTERVAL_MS rather than a cold initial value. */
    public boolean isActive() { return isRunning; }
    
    // TelemetryDataCollector reference — when set, read gear from its cached snapshot
    // instead of polling the BYD device directly (avoids duplicate CAN bus reads)
    private volatile com.overdrive.app.telemetry.TelemetryDataCollector telemetrySource = null;
    
    private GearMonitor() {}
    
    public static synchronized GearMonitor getInstance() {
        if (instance == null) {
            instance = new GearMonitor();
        }
        return instance;
    }
    
    /**
     * Initialize with context.
     */
    public void init(Context context) {
        this.context = context;
        logger.info("GearMonitor initialized");
    }
    
    /**
     * Set the TelemetryDataCollector as the gear data source.
     * When set and its poller is running, GearMonitor reads gear from the cached
     * snapshot instead of polling the BYD device directly — eliminating duplicate
     * CAN bus reads.
     */
    public void setTelemetrySource(com.overdrive.app.telemetry.TelemetryDataCollector source) {
        this.telemetrySource = source;
    }
    
    /**
     * Start monitoring gear changes via polling.
     *
     * <p>Synchronized: the round-3 RecordingModeManager change made
     * {@code resyncFromHardware} call this every 30s when the monitor isn't
     * running. Without this lock, two concurrent callers (resync ticker +
     * cold-start retry) can both pass the {@code !isRunning} guard, both
     * complete the reflection, and both spawn their own {@code GearPoll}
     * thread — leaking a permanent second thread that double-reports every
     * gear change. The duplicate {@code onGearChanged} deliveries then
     * cancel each other in RMM (gear==currentGear short-circuit) but still
     * waste CPU on every 200ms tick.
     */
    public synchronized void start() {
        if (isRunning) {
            logger.warn("Already running");
            return;
        }

        try {
            logger.info("Starting gear monitor...");
            
            // Get gearbox device instance via BydDeviceHelper (with DiLink 5.0 / Android 11 bypass)
            gearboxDevice = com.overdrive.app.byd.BydDeviceHelper.getDevice("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", context);
            if (gearboxDevice == null) {
                try {
                    Class<?> gearboxClass = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
                    Method getInstance = gearboxClass.getMethod("getInstance", Context.class);
                    gearboxDevice = getInstance.invoke(null, context);
                } catch (Throwable ignored) {}
            }
            
            if (gearboxDevice == null) {
                logger.error("BYDAutoGearboxDevice.getInstance() returned null");
                return;
            }
            
            // Cache the getter method
            getGearMethod = gearboxDevice.getClass().getMethod("getGearboxAutoModeType");
            
            // Get initial gear state
            Object initialReadObj = getGearMethod.invoke(gearboxDevice);
            int initialGearRead = (initialReadObj instanceof Integer) ? (Integer) initialReadObj : -1;
            if (!isValidGearMode(initialGearRead)) {
                logger.error("Invalid initial gear read: "
                        + initialGearRead);
                gearboxDevice = null;
                getGearMethod = null;
                return;
            }
            currentGear = initialGearRead;
            lastUpdateTime = SystemClock.elapsedRealtime();
            logger.info("Initial gear: " + gearToString(currentGear));
            
            isRunning = true;
            
            // Build the poller first, but publish the hardware state before it
            // can run. Starting the thread first allowed a fast P -> D shift to
            // update currentGear before this initial callback, permanently
            // collapsing the P edge during async trip-manager startup.
            final int initialGear = currentGear;
            pollThread = new Thread(() -> {
                while (isRunning) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                        if (!isRunning) break;

                        int gear;
                        long gearObservedAtElapsedRealtimeMs;
                        // Prefer TelemetryDataCollector's cached snapshot to avoid
                        // duplicate CAN bus reads when the overlay poller is running
                        com.overdrive.app.telemetry.TelemetryDataCollector src = telemetrySource;
                        com.overdrive.app.telemetry.TelemetrySnapshot snap =
                            (src != null) ? src.getLatestSnapshot() : null;
                        long gearAgeMs = snap != null
                                && snap.gearReadElapsedRealtimeMs >= 0L
                                ? SystemClock.elapsedRealtime()
                                        - snap.gearReadElapsedRealtimeMs
                                : Long.MAX_VALUE;
                        if (snap != null
                                && snap.gearValid
                                && isValidGearMode(snap.gearMode)
                                && gearAgeMs >= 0L
                                && gearAgeMs
                                        < CACHED_GEAR_MAX_AGE_MS) {
                            // Only a recent successful gear read is cacheable.
                            gear = snap.gearMode;
                            gearObservedAtElapsedRealtimeMs =
                                    snap.gearReadElapsedRealtimeMs;
                        } else {
                            // Snapshot the reflection refs to locals: stop() is
                            // synchronized and nullifies these mid-iteration. Without
                            // local snapshot, getGearMethod.invoke would NPE and
                            // produce a bogus "Gear poll error: null" log on every
                            // race. Cleanly exit the loop on null instead.
                            Method getter = getGearMethod;
                            Object device = gearboxDevice;
                            if (getter == null || device == null) break;
                            gear = (int) getter.invoke(device);
                            gearObservedAtElapsedRealtimeMs =
                                    SystemClock.elapsedRealtime();
                        }

                        if (!isValidGearMode(gear)) {
                            logger.debug("Ignoring invalid gear read: "
                                    + gear);
                            continue;
                        }
                        int previousGear = currentGear;
                        currentGear = gear;
                        lastUpdateTime =
                                gearObservedAtElapsedRealtimeMs;
                        if (gear != previousGear) {
                            logger.info("Gear changed: " + gearToString(previousGear) + " -> " + gearToString(gear));
                            CameraDaemon.onGearChanged(gear);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        // Don't crash the poll thread — just log and retry
                        logger.debug("Gear poll error: " + e.getMessage());
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }
            }, "GearPoll");
            pollThread.setDaemon(true);
            // Notify initial state
            CameraDaemon.onGearChanged(initialGear);
            pollThread.start();
            
            logger.info("Gear monitor started successfully");
            
        } catch (Exception e) {
            logger.error("Failed to start gear monitor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Stop monitoring.
     */
    public synchronized void stop() {
        if (!isRunning) {
            return;
        }

        isRunning = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        gearboxDevice = null;
        getGearMethod = null;
        logger.info("Gear monitor stopped");
    }
    
    /**
     * Get current gear.
     */
    public int getCurrentGear() {
        return currentGear;
    }
    
    /**
     * Get last update time.
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    /**
     * Check if running.
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Convert gear to string.
     */
    public static String gearToString(int gear) {
        switch (gear) {
            case GEAR_P: return "P";
            case GEAR_R: return "R";
            case GEAR_N: return "N";
            case GEAR_D: return "D";
            case GEAR_M: return "M";
            case GEAR_S: return "S";
            default: return "UNKNOWN(" + gear + ")";
        }
    }

    private static boolean isValidGearMode(int gear) {
        return gear >= GEAR_P && gear <= GEAR_S;
    }
}
