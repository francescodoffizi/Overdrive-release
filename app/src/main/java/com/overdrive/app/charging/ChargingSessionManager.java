package com.overdrive.app.charging;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.BatteryThermalData;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.SocHistoryDatabase;
import com.overdrive.app.monitor.VehicleDataMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the fine-grained in-session sampler + config for Charging Analytics, and
 * exposes accessors used by {@link ChargingApiHandler}.
 *
 * <p>The discrete session edge INSERT/UPDATE (running-max peak, AC/DC, cost,
 * rollup) stays inside {@link SocHistoryDatabase#trackChargingSession} — it is
 * already driven on the 2-minute SoC sampler thread with the correct
 * {@code wasCharging} state. This manager adds ONLY:
 *
 * <ul>
 *   <li>A fast sampler (every {@code fastSampleSec}, default 12 s) that runs
 *       while {@link ChargingDetector#isCharging()} is true and writes
 *       {@code charging_power_samples} rows for true ramp curves.</li>
 *   <li>Registration as a {@link ChargingDetector.FusedStateListener} so the
 *       sampler starts/stops exactly on the fused charging edge (same truth
 *       source as the session edges — no divergence).</li>
 * </ul>
 *
 * <p>Edge-case guards (see project memory): the fast sampler skips ticks whose
 * power is NaN or ≤0 so an ACC-OFF read can't poison the curve; it uses the
 * charger-reported {@code ChargingStateData.chargingPowerKW} which is
 * ACC-independent during DC charging.
 */
public class ChargingSessionManager implements ChargingDetector.FusedStateListener {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ChargingSessionManager");

    private ChargingConfig config;
    private SocHistoryDatabase socDb;

    private ScheduledExecutorService sampler;
    private volatile ScheduledFuture<?> sampleTask;
    private volatile boolean charging = false;
    private volatile boolean initialized = false;

    // ==================== LIFECYCLE ====================

    /**
     * Initialize. Called from CameraDaemon after SocHistoryDatabase.start().
     * Registers the fused-state listener so the fast sampler tracks the real
     * charging edge.
     */
    public void init(Context context) {
        config = new ChargingConfig();
        config.load();

        // Load the location-aware tariffs BEFORE anything can price a session.
        // finalizeStaleOpenSessions() below closes (and therefore prices) any
        // session left open by a restart, and priceSession() would otherwise
        // lazy-load on the SoC thread mid-close.
        try {
            TariffManager.getInstance().load();
        } catch (Throwable t) {
            logger.warn("Tariff load skipped: " + t.getMessage());
        }

        socDb = SocHistoryDatabase.getInstance();
        // Push the opt-in flag so SocHistoryDatabase.trackChargingSession (which
        // runs on the always-on SoC tick) records nothing when disabled.
        socDb.setChargingAnalyticsEnabled(config.isEnabled());

        if (config.isEnabled()) {
            sampler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ChargeSampler");
                t.setPriority(Thread.MIN_PRIORITY);
                t.setUncaughtExceptionHandler((thread, ex) ->
                        logger.error("Uncaught in ChargeSampler: " + ex.getMessage(), ex));
                return t;
            });

            try {
                ChargingDetector.getInstance().addFusedStateListener(this);
                // Seed from current state in case we're already charging at init.
                if (ChargingDetector.getInstance().isCharging()) {
                    onFusedChargingChanged(true, "init-seed");
                } else {
                    // Not charging at startup — close any session left OPEN by a
                    // charge that ended while the daemon was down/restarting (its
                    // SESSION END tick never fired, so it shows blank energy/cost/
                    // range with only start values). Reconstruct end values from
                    // the recorded samples and fold into the rollup.
                    socDb.finalizeStaleOpenSessions();
                }
            } catch (Exception e) {
                logger.error("Failed to register fused-state listener: " + e.getMessage());
                if (sampler != null) {
                    try { sampler.shutdownNow(); } catch (Exception ignored) {}
                    sampler = null;
                }
                throw e;
            }
        }

        initialized = true;
        logger.info("ChargingSessionManager initialized — enabled=" + config.isEnabled()
                + " fastSampleSec=" + config.getFastSampleSec());
    }

    public void shutdown() {
        logger.info("Shutting down ChargingSessionManager");
        // Persist any debounced tariff usage counters before we go: markUsed()
        // coalesces writes (see TariffManager.MARK_USED_FLUSH_MS), so a pending
        // bump would otherwise be lost on a clean daemon stop.
        try { TariffManager.getInstance().flushPendingUsage(); } catch (Throwable ignored) {}
        try { ChargingDetector.getInstance().removeFusedStateListener(this); } catch (Exception ignored) {}
        stopSampling();
        if (sampler != null) {
            try { sampler.shutdownNow(); } catch (Exception ignored) {}
            sampler = null;
        }
        initialized = false;
    }

    // ==================== FUSED CHARGING EDGE ====================

    @Override
    public void onFusedChargingChanged(boolean isCharging, String source) {
        this.charging = isCharging;
        if (isCharging) {
            startSampling();
        } else {
            stopSampling();
        }
    }

    private synchronized void startSampling() {
        if (sampler == null || config == null) return;
        if (!config.isEnabled()) return;            // user opted out of recording
        if (sampleTask != null && !sampleTask.isCancelled()) return; // already running
        int periodSec = config.getFastSampleSec();
        try {
            sampleTask = sampler.scheduleAtFixedRate(this::sampleOnce, 0, periodSec, TimeUnit.SECONDS);
            logger.info("Fast charging sampler started (every " + periodSec + "s)");
        } catch (Exception e) {
            logger.error("Failed to start fast sampler: " + e.getMessage());
        }
    }

    private synchronized void stopSampling() {
        if (sampleTask != null) {
            try { sampleTask.cancel(false); } catch (Exception ignored) {}
            sampleTask = null;
        }
    }

    /**
     * One fast-sampler tick: snapshot power/SoC/temp and append a ramp sample to
     * the currently-open charging session. Best-effort; never throws.
     */
    private void sampleOnce() {
        try {
            if (!charging || socDb == null) return;
            long sessionStart = socDb.getOpenChargingSessionStart();
            if (sessionStart <= 0) return;  // 2-min edge hasn't opened the row yet

            VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
            if (vm == null) return;

            ChargingStateData cs = vm.getChargingState();
            double power = cs != null ? cs.chargingPowerKW : Double.NaN;
            // Guard: ACC-OFF can make power NaN/≤0; skip so the curve has no holes.
            if (Double.isNaN(power) || power <= 0) return;
            // Guard: in the first ticks of a charge, the BMS hasn't reported a
            // real kW yet, so getChargingState() substitutes a nominal-based
            // PLACEHOLDER (3.3 kW PHEV / 7.0 kW BEV) and flags isEstimated. That
            // placeholder must NOT enter the ramp curve — it produced a flat
            // false 7 kW plateau at session start. Skip estimated samples; the
            // curve simply begins when the first MEASURED reading lands.
            if (cs.isEstimated) return;

            BatterySocData soc = vm.getBatterySoc();
            double socPct = soc != null ? soc.socPercent : Double.NaN;

            double temp = -999, tempHigh = -999, tempLow = -999;
            try {
                BatteryThermalData th = vm.getBatteryThermal();
                if (th != null && th.hasData()) {
                    if (!Double.isNaN(th.averageTempC)) temp = th.averageTempC;
                    if (!Double.isNaN(th.highestTempC)) tempHigh = th.highestTempC;
                    if (!Double.isNaN(th.lowestTempC)) tempLow = th.lowestTempC;
                }
            } catch (Exception ignored) {}

            socDb.recordChargingSample(sessionStart, System.currentTimeMillis(), power, socPct, temp, tempHigh, tempLow);
        } catch (Exception e) {
            logger.debug("sampleOnce failed: " + e.getMessage());
        }
    }

    // ==================== CONFIG ====================

    /** Re-read config after a POST /api/charging/config and restart sampler if needed. */
    public void onConfigChanged() {
        if (config == null) return;
        config.load();
        // Tariffs live in the same chargingAnalytics section, so re-read them on
        // every config change — otherwise the daemon keeps pricing with a stale
        // list after the UI edits one.
        try {
            // Flush any debounced usage counters BEFORE reloading, or the reload
            // would drop them (markUsed coalesces writes; see TariffManager).
            TariffManager.getInstance().flushPendingUsage();
            TariffManager.getInstance().load();
        } catch (Throwable t) {
            logger.warn("Tariff reload skipped: " + t.getMessage());
        }
        // Propagate the opt-in flag to the always-on session recorder.
        if (socDb != null) socDb.setChargingAnalyticsEnabled(config.isEnabled());
        // Restart the sampler to pick up a new interval / enabled flag.
        boolean wasCharging = charging;
        stopSampling();
        if (wasCharging && config.isEnabled()) startSampling();
        logger.info("ChargingSessionManager config reloaded — enabled=" + config.isEnabled()
                + " fastSampleSec=" + config.getFastSampleSec());
    }

    // ==================== ACCESSORS ====================

    public ChargingConfig getConfig() { return config; }
    public SocHistoryDatabase getSocDb() { return socDb != null ? socDb : SocHistoryDatabase.getInstance(); }
    public boolean isInitialized() { return initialized; }
    public boolean isChargingNow() { return charging; }
}
