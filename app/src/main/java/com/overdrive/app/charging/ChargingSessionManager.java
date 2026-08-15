package com.overdrive.app.charging;

import android.content.Context;

import com.overdrive.app.byd.BydVehicleData;
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
public class ChargingSessionManager implements ChargingDetector.FusedStateListener,
        ChargingDetector.AuthoritativeStopListener {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ChargingSessionManager");
    // Parked collection runs every 90 seconds. Keep the probe open for a full cycle plus enough
    // scheduler/device-read margin that a poll starting near the boundary can still publish evidence.
    private static final long TAPER_PROBE_GRACE_MS = 120_000L;
    /** Brief drain window for a terminal charged-energy callback that follows FINISHED. */
    private static final long FINAL_COUNTER_GRACE_MS = 5_000L;
    private static final int BOUNDARY_WRITE_ATTEMPTS = 2;
    private static final int SHUTDOWN_DATABASE_EDGE_ATTEMPTS = 3;
    private static final long EDGE_RETRY_INITIAL_MS = 500L;
    private static final long EDGE_RETRY_MAX_MS = 30_000L;
    private static final long[] STARTUP_SWEEP_RETRY_DELAYS_MS =
            new long[] {1_000L, 5_000L, 30_000L};

    private ChargingConfig config;
    private SocHistoryDatabase socDb;

    private ScheduledExecutorService sampler;
    private volatile ScheduledFuture<?> sampleTask;
    private volatile boolean charging = false;
    /**
     * Whether the previous fast-sampler tick recorded a usable measured rate. Used to write the
     * gap-boundary row only on the FALLING edge: a trim that never resolves a rate would otherwise
     * append a boundary row every sample period for the whole session, and one boundary is
     * sufficient — the integrator needs the chain broken once, not repeatedly.
     */
    private volatile boolean lastTickHadPower = false;
    /** True after the current contiguous missing-rate interval has been durably represented. */
    private volatile boolean missingRateIntervalMarked = false;
    /** Bounded window for a final counter callback or post-FINISHED taper observation. */
    private volatile long deferredStopDeadlineElapsedMs = 0L;
    /**
     * Incremented whenever sampling stops. A tick that was already executing when the cancel landed
     * carries the old value and discards its own write, so it cannot insert a positive sample after the
     * gap boundary and re-arm the integrator's trapezoid chain across a real pause.
     */
    private volatile int samplerGeneration = 0;
    /**
     * Serialises a sampler tick's generation-check-plus-write against the gap-boundary write, so a tick
     * cancelled mid-flight cannot append a positive sample after the boundary. Deliberately NOT the
     * instance monitor: {@code stopSampling()} is {@code synchronized}, and a tick blocking on that
     * while the edge thread waits inside it would deadlock.
     */
    private final Object sampleWriteLock = new Object();
    /** A failed boundary is retried before any later positive sample is allowed to persist. */
    private long pendingBoundarySessionStart = -1L;
    private long pendingBoundaryAtMs = 0L;
    private double pendingBoundaryPowerKw = SocHistoryDatabase.STOP_BOUNDARY_POWER_KW;
    /** DB edge that did not reach its postcondition yet; null when persistence is reconciled. */
    private Boolean pendingDatabaseEdge = null;
    private ScheduledFuture<?> databaseEdgeRetryTask;
    private int databaseEdgeRetryGeneration = 0;
    private int databaseEdgeRetryAttempt = 0;
    /** Desired config state and the last state durably acknowledged by SocHistoryDatabase. */
    private boolean desiredAnalyticsEnabled = false;
    private boolean appliedAnalyticsEnabled = false;
    private boolean analyticsStateConfirmed = false;
    private ScheduledFuture<?> analyticsStateRetryTask;
    private int analyticsStateRetryGeneration = 0;
    private int analyticsStateRetryAttempt = 0;
    /** A new physical ON arrived while the preceding row was still failing to close. */
    private boolean startAfterPendingClose = false;
    /** Database cleanup is retained until the row for its captured physical generation is closed. */
    private boolean closeCleanupPending = false;
    private long closeCleanupScopeGeneration = 0L;
    private long closeCleanupSessionStart = -1L;
    private long nextSessionScopeGeneration = 0L;
    private long activeSessionScopeGeneration = 0L;
    /** Unplug/V2L is physically OFF now, but the row remains open briefly for the final counter. */
    private volatile boolean authoritativeStopPending = false;
    private ScheduledFuture<?> startupSweepRetryTask;
    private int startupSweepRetryGeneration = 0;
    private volatile boolean initialized = false;
    private volatile boolean shuttingDown = false;
    /** True only after listener registration and a stable detector-state reconciliation. */
    private boolean detectorOwnershipReconciled = false;

    private static long monotonicNowMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    // ==================== LIFECYCLE ====================

    /**
     * Initialize. Called from CameraDaemon after SocHistoryDatabase.start().
     * Registers the fused-state listener so the fast sampler tracks the real
     * charging edge.
     */
    public void init(Context context) {
        shuttingDown = false;
        detectorOwnershipReconciled = false;
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
        // SocHistoryDatabase's scheduler starts first. Keep its charging lifecycle inference gated
        // until config, listeners and detector state have all been reconciled below.
        socDb.setChargingLifecycleOwnerReady(false);
        socDb.replayPendingChargingPostCommitMetadata();
        // Push the opt-in flag before any edge can open a row. A failed journal write leaves this
        // state unconfirmed, so opening and sampling stay fenced until the bounded retry succeeds.
        latchDesiredAnalyticsStateLocked(config.isEnabled());
        if (!applyDesiredAnalyticsStateLocked("init")) {
            logger.warn("Charging analytics state is waiting for lifecycle-journal durability");
        }

        if (isAnalyticsRecordingReady()) {
            ensureSamplerExecutor();
        }

        // Register regardless of the analytics opt-in. The live power resolver/classifier still has a
        // physical-session lifecycle when recording is disabled, and runtime enable must know whether
        // it is joining a charge already in progress.
        try {
            ChargingDetector detector = ChargingDetector.getInstance();
            detector.addFusedStateListener(this);
            detector.addAuthoritativeStopListener(this);
            reconcileDetectorState("init-seed");
            detectorOwnershipReconciled = true;
            updateLifecycleOwnerReadinessLocked();
            if (!isLifecycleOwnerReadyLocked()
                    || !runStartupStaleSweep("init")) {
                scheduleStartupStaleSweepRetry(0);
            }
        } catch (Exception e) {
            logger.error("Failed to register fused-state listener: " + e.getMessage());
            detectorOwnershipReconciled = false;
            try { socDb.setChargingLifecycleOwnerReady(false); } catch (Exception ignored) {}
            try { ChargingDetector.getInstance().removeFusedStateListener(this); }
            catch (Exception ignored) {}
            try { ChargingDetector.getInstance().removeAuthoritativeStopListener(this); }
            catch (Exception ignored) {}
            synchronized (this) {
                cancelStartupSweepRetryLocked();
                cancelAnalyticsStateRetryLocked();
            }
            shutdownSamplerExecutor();
            throw e;
        }

        initialized = true;
        logger.info("ChargingSessionManager initialized — enabled=" + config.isEnabled()
                + " fastSampleSec=" + config.getFastSampleSec());
    }

    public void shutdown() {
        synchronized (this) {
            if (shuttingDown) return;
            shuttingDown = true;
            initialized = false;
            detectorOwnershipReconciled = false;
            if (socDb != null) {
                socDb.setChargingLifecycleOwnerReady(false);
                socDb.setChargingLiveEnrichmentAllowed(false);
            }
            // Stop the scheduled retry without erasing which physical generation must replace which
            // row. The synchronous shutdown flush below needs that intent to make buffered session B
            // durable before process memory disappears.
            cancelDatabaseEdgeRetryTaskLocked();
            cancelStartupSweepRetryLocked();
            cancelAnalyticsStateRetryLocked();
        }
        logger.info("Shutting down ChargingSessionManager");
        // Persist any debounced tariff usage counters before we go: markUsed()
        // coalesces writes (see TariffManager.MARK_USED_FLUSH_MS), so a pending
        // bump would otherwise be lost on a clean daemon stop.
        try { TariffManager.getInstance().flushPendingUsage(); } catch (Throwable ignored) {}
        try { ChargingDetector.getInstance().removeFusedStateListener(this); } catch (Exception ignored) {}
        try { ChargingDetector.getInstance().removeAuthoritativeStopListener(this); }
        catch (Exception ignored) {}
        // Fence the periodic task first, then persist a restart boundary before the DB is closed. A
        // resumed row must never trapezoid-integrate across daemon downtime.
        stopSampling();
        writeGapBoundary();
        retryPendingBoundary();
        flushPendingDatabaseLifecycleForShutdown();
        // Await outside the instance monitor. A due deferred-close task briefly acquires that monitor;
        // holding it while awaiting would force the executor through the timeout/interrupt path.
        shutdownSamplerExecutor();
        // One final attempt after every in-flight tick has left sampleWriteLock. The database is still
        // open at this lifecycle point, and a transient failure during the first attempt may have cleared.
        retryPendingBoundary();
    }

    // ==================== FUSED CHARGING EDGE ====================

    @Override
    public synchronized void onFusedChargingChanged(boolean isCharging, String source) {
        if (shuttingDown) return;
        // Push physical truth even for the duplicate callback used during initialization. The row can
        // remain open for a bounded final-counter drain, but chargingNow must flip at the physical edge.
        if (socDb != null) socDb.setPhysicalChargingNow(isCharging);
        if (isCharging == this.charging) {
            // A repeated FINISHED publication must not revoke a taper that a later cohesive poll has
            // already proved. An authoritative unplug keeps the gate closed regardless of stale flow.
            if (!isCharging && !authoritativeStopPending && isTaperInProgress() && socDb != null) {
                socDb.setChargingLiveEnrichmentAllowed(true);
            }
            return;
        }
        this.charging = isCharging;
        if (isCharging) {
            authoritativeStopPending = false;
            boolean mustClosePrevious =
                    (socDb != null && socDb.hasPendingChargingCloseBoundary())
                    || hasDeferredStop()
                    || isPendingDatabaseEdge(false) || closeCleanupPending;
            if (mustClosePrevious) {
                prepareClosedSessionCleanup();
                // Fence A's sampler before B owns the resolver/classifier. Waiting for its write
                // critical section closes the window where an already-running tick could resolve
                // B's telemetry and append it to A's still-open row.
                stopSampling();
                writeGapBoundary();
                if (socDb != null && !socDb.deferPhysicalChargingStart()) {
                    logger.warn("Deferred charging start is waiting for lifecycle-journal durability");
                }
                synchronized (sampleWriteLock) {
                    advanceSessionScopedStateForPhysicalOn();
                }
            } else {
                advanceSessionScopedStateForPhysicalOn();
            }
            // The physical generation changes at this edge even if persistence for the preceding
            // row is unavailable. Advance power/classifier ownership before attempting that close
            // so retained raw values cannot borrow the preceding charge's scale proof.
            deferredStopDeadlineElapsedMs = 0L;
            if (mustClosePrevious) {
                // Never let a failed close turn the new physical generation into the old DB row.
                startAfterPendingClose = true;
                if (!finishStoppedSession()) {
                    logger.warn("New charging generation is waiting for the prior row to close");
                    if (isAnalyticsRecordingReady()) startSampling(true);
                    return;
                }
                // The successful close consumes startAfterPendingClose and opens this generation.
                // Falling through would apply the same open edge and sampler transition twice.
                return;
            }
            beginChargingSession();
            return;
        }

        boolean taper = isTaperInProgress();
        // Do NOT stop on a FINISHED edge that is really a constant-voltage taper: the charger is
        // still delivering and the session row is still open, so cancelling the task here would
        // make sampleOnce's taper branch unreachable. Keep sampling and let the taper's own end
        // (or the next non-taper edge) stop it.
        if (taper) {
            deferredStopDeadlineElapsedMs = 0L;
            if (socDb != null) {
                // Freeze an initial FINISHED boundary even though flow is already visible. Each
                // admitted positive taper sample advances it; the later poll that merely observes
                // zero flow must not add its idle interval to duration or average power.
                boolean boundaryDurable = socDb.hasDeferredPhysicalGenerations()
                        ? socDb.deferPhysicalChargingStop()
                        : socDb.capturePendingChargingClose();
                if (!boundaryDurable) {
                    logger.warn("Charging taper boundary is waiting for lifecycle-journal durability");
                }
                socDb.setChargingLiveEnrichmentAllowed(true);
            }
            // With analytics DISABLED there is no sampler, so sampleOnce() -- the only deferred
            // cleanup -- never runs and the resolver/classifier state would cross into the next
            // charge. Nothing is being recorded here either, so there is no tail to preserve.
            if (!isAnalyticsRecordingReady()) {
                if (isPendingDatabaseEdge(false) || closeCleanupPending) {
                    startAfterPendingClose = false;
                    finishStoppedSession();
                    return;
                }
                cancelDatabaseEdgeRetryLocked();
                if (socDb != null) socDb.setChargingLifecycleHold(false);
                if (socDb != null) socDb.setChargingLiveEnrichmentAllowed(false);
                releaseSessionScopedState();
                return;
            }
            logger.info("Charging edge OFF during a constant-voltage taper -- keeping the sampler"
                    + " running so the taper tail is recorded");
            return;
        }
        if (socDb != null) {
            boolean boundaryDurable = socDb.hasDeferredPhysicalGenerations()
                    ? socDb.deferPhysicalChargingStop()
                    : socDb.capturePendingChargingClose();
            if (!boundaryDurable) {
                logger.warn("Charging stop boundary is waiting for lifecycle-journal durability");
            }
        }
        if (isAnalyticsRecordingReady()) {
            // FINISHED can be followed by a final charged-energy callback. On a PHEV it can also
            // precede the next parked collection that proves a real CV taper. Keep DB ownership and
            // the existing sampler generation until the appropriate bounded drain window expires.
            long graceMs = mayStillEnterTaper()
                    ? TAPER_PROBE_GRACE_MS : FINAL_COUNTER_GRACE_MS;
            deferStoppedSession(graceMs);
            logger.info((graceMs == TAPER_PROBE_GRACE_MS
                    ? "FINISHED edge awaiting post-finish pack-flow evidence for "
                    : "Charging OFF awaiting final counter callback for ")
                    + (graceMs / 1000) + "s");
            return;
        }
        if (isPendingDatabaseEdge(false) || closeCleanupPending) {
            startAfterPendingClose = false;
            finishStoppedSession();
            return;
        }
        cancelDatabaseEdgeRetryLocked();
        if (socDb != null) socDb.setChargingLifecycleHold(false);
        releaseSessionScopedState();
    }

    private void beginChargingSession() {
        activateSessionScopedState();
        if (socDb == null || !isAnalyticsRecordingReady()) return;

        socDb.setChargingLifecycleHold(true);
        if (applyDatabaseEdgeAndVerify(true, "open")) {
            clearDatabaseEdgeRetryLocked();
            startSampling(true);
        } else {
            scheduleDatabaseEdgeRetryLocked(true);
            if (socDb.hasDeferredPhysicalGenerations()) startSampling(true);
        }
    }

    private void activateSessionScopedState() {
        if (activeSessionScopeGeneration > 0L) return;
        activeSessionScopeGeneration = ++nextSessionScopeGeneration;
        try { com.overdrive.app.monitor.ChargeRateResolver.onSessionStarted(); }
        catch (Throwable ignored) {}
    }

    private void advanceSessionScopedStateForPhysicalOn() {
        releaseSessionScopedState();
        activateSessionScopedState();
    }

    @Override
    public synchronized void onAuthoritativeChargingStop(String source) {
        if (shuttingDown) return;
        // Physical state changes immediately. Only persistence remains open for a bounded counter drain.
        charging = false;
        if (socDb != null) socDb.setPhysicalChargingNow(false);
        authoritativeStopPending = true;
        boolean analyticsEnabled = isAnalyticsRecordingReady();
        boolean hasOpenRow = socDb != null && socDb.getOpenChargingSessionStart() > 0;
        if (socDb != null) {
            boolean boundaryDurable = socDb.hasDeferredPhysicalGenerations()
                    ? socDb.deferPhysicalChargingStop(true)
                    : socDb.capturePendingChargingClose(true);
            if (!boundaryDurable) {
                logger.warn("Authoritative stop boundary is waiting for lifecycle-journal durability");
            }
        }
        if (!analyticsEnabled) {
            authoritativeStopPending = false;
            deferredStopDeadlineElapsedMs = 0L;
            stopSampling();
            if (isPendingDatabaseEdge(false) || closeCleanupPending || hasOpenRow) {
                startAfterPendingClose = false;
                finishStoppedSession();
                return;
            }
            cancelDatabaseEdgeRetryLocked();
            if (socDb != null) socDb.setChargingLifecycleHold(false);
            releaseSessionScopedState();
            return;
        }
        if (!hasDeferredStop() && !hasOpenRow && pendingDatabaseEdge == null) {
            authoritativeStopPending = false;
            deferredStopDeadlineElapsedMs = 0L;
            stopSampling();
            cancelDatabaseEdgeRetryLocked();
            if (socDb != null) socDb.setChargingLifecycleHold(false);
            releaseSessionScopedState();
            return;
        }

        // If a close was already retrying, suspend it until the final-counter window expires.
        if (isPendingDatabaseEdge(false)) clearDatabaseEdgeRetryLocked();
        stopSampling();
        writeGapBoundary();
        retryPendingBoundary();
        deferStoppedSession(FINAL_COUNTER_GRACE_MS);
        logger.info("Authoritative charging stop (" + source + ") -- physical OFF; retaining the"
                + " session row for " + (FINAL_COUNTER_GRACE_MS / 1000)
                + "s to admit the final charged-energy callback");
    }

    private boolean isPendingDatabaseEdge(boolean desired) {
        return pendingDatabaseEdge != null && pendingDatabaseEdge == desired;
    }

    private boolean hasDeferredStop() {
        ScheduledFuture<?> task = sampleTask;
        return deferredStopDeadlineElapsedMs > 0L
                || (!charging && task != null && !task.isCancelled() && !task.isDone());
    }

    private void deferStoppedSession(long graceMs) {
        ensureSamplerExecutor();
        long boundedGraceMs = Math.max(1L, graceMs);
        long deadline = monotonicNowMs() + boundedGraceMs;
        deferredStopDeadlineElapsedMs = deadline;
        final int generation = samplerGeneration;
        if (sampler != null) {
            try {
                sampler.schedule(() -> sampleOnce(generation), boundedGraceMs + 5L,
                        TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.warn("Could not schedule deferred charging close: " + e.getMessage());
                finishStoppedSessionIfGeneration(generation);
            }
        } else {
            finishStoppedSessionIfGeneration(generation);
        }
    }

    private boolean mayStillEnterTaper() {
        try {
            VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
            BydVehicleData vd = vm != null ? vm.getVd() : null;
            if (vd == null || !vm.isPhev()) return false;
            boolean gunCharging = vd.chargingGunState == 2
                    || vd.chargingGunState == 3 || vd.chargingGunState == 4;
            return vd.chargingState == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH
                    && vd.chargingStateAtMs > 0
                    && gunCharging
                    && !vd.vtolCharging;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Complete every session-scoped lifecycle operation exactly once at a real stop. */
    private synchronized boolean finishStoppedSession() {
        deferredStopDeadlineElapsedMs = 0L;
        authoritativeStopPending = false;
        if (socDb != null) socDb.setChargingLiveEnrichmentAllowed(false);
        // Stop first so an in-flight tick cannot append a positive row after the boundary.
        stopSampling();
        writeGapBoundary();
        if (!retryPendingBoundary()) {
            prepareClosedSessionCleanup();
            scheduleDatabaseEdgeRetryLocked(false);
            return false;
        }
        try {
            VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
            if (vm != null) vm.closeTaperAdmissionForCurrentFinishedState();
        } catch (Throwable ignored) {}
        prepareClosedSessionCleanup();
        if (!applyDatabaseEdgeAndVerify(false, "close")) {
            scheduleDatabaseEdgeRetryLocked(false);
            return false;
        }
        clearDatabaseEdgeRetryLocked();
        completeClosedSessionAndStartDeferred();
        return true;
    }

    /** Decline cleanup from a cancelled sampler generation or after a newer ON edge won. */
    private synchronized void finishStoppedSessionIfGeneration(int generation) {
        if (shuttingDown || generation != samplerGeneration || charging) return;
        finishStoppedSession();
    }

    /** Apply the DB-owned result contract and independently verify its observable row-state postcondition. */
    boolean applyDatabaseEdgeAndVerify(boolean desiredOpen, String operation) {
        if (socDb == null) return !desiredOpen;
        if (desiredOpen && !isAnalyticsRecordingReady()) return false;
        long expectedCloseStart = desiredOpen ? -1L : closeCleanupSessionStart;
        if (!desiredOpen && expectedCloseStart <= 0L) {
            expectedCloseStart = socDb.getChargingCloseTargetStart();
            closeCleanupSessionStart = expectedCloseStart;
        }
        if (!desiredOpen && expectedCloseStart > 0L) {
            expectedCloseStart = socDb.remapChargingCloseTargetStart(expectedCloseStart);
            closeCleanupSessionStart = expectedCloseStart;
        }
        boolean targetlessClose = !desiredOpen && expectedCloseStart <= 0L;
        boolean committed = false;
        try {
            committed = desiredOpen
                    ? socDb.onChargingEdge(true)
                    : socDb.onChargingEdge(
                            false, Math.max(0L, expectedCloseStart),
                            targetlessClose || !startAfterPendingClose || !charging);
        } catch (Throwable t) {
            logger.warn("Session-row " + operation + " threw: " + t.getMessage());
        }
        boolean rowOpen;
        try {
            rowOpen = socDb.hasOpenChargingSessionRow();
        } catch (Throwable t) {
            logger.warn("Could not verify session-row " + operation + ": " + t.getMessage());
            return false;
        }
        boolean exactCloseSatisfied = desiredOpen;
        boolean deferredDrained = true;
        if (targetlessClose) {
            try {
                deferredDrained = !socDb.hasDeferredPhysicalGenerations();
                exactCloseSatisfied = !rowOpen && deferredDrained;
            } catch (Throwable t) {
                logger.warn("Could not verify target-less session-row " + operation
                        + " drain: " + t.getMessage());
                exactCloseSatisfied = false;
                deferredDrained = false;
            }
        } else if (!desiredOpen && expectedCloseStart > 0L) {
            try {
                exactCloseSatisfied =
                        socDb.isChargingSessionCloseSatisfied(expectedCloseStart);
            } catch (Throwable t) {
                logger.warn("Could not verify exact session-row " + operation
                        + " for " + expectedCloseStart + ": " + t.getMessage());
                exactCloseSatisfied = false;
            }
        }
        boolean success = committed && (desiredOpen
                ? databaseEdgeReachedDesiredState(true, rowOpen ? 1L : -1L)
                : exactCloseSatisfied);
        if (!success) {
            logger.warn("Session-row " + operation + " did not reach its persistence postcondition"
                    + " (committed=" + committed + ", expectedOpen=" + desiredOpen
                    + ", expectedCloseStart=" + expectedCloseStart
                    + ", actualOpen=" + rowOpen
                    + ", deferredDrained=" + deferredDrained + ")");
        }
        return success;
    }

    static boolean databaseEdgeReachedDesiredState(boolean desiredOpen, long openSessionStart) {
        return desiredOpen == (openSessionStart > 0L);
    }

    synchronized void scheduleDatabaseEdgeRetryLocked(boolean desiredOpen) {
        if (shuttingDown) return;
        if (pendingDatabaseEdge == null || pendingDatabaseEdge != desiredOpen) {
            clearDatabaseEdgeRetryLocked();
            pendingDatabaseEdge = desiredOpen;
        }
        if (databaseEdgeRetryTask != null && !databaseEdgeRetryTask.isDone()
                && !databaseEdgeRetryTask.isCancelled()) {
            return;
        }
        ensureSamplerExecutor();
        if (sampler == null) return;

        int shift = Math.min(databaseEdgeRetryAttempt, 6);
        long delayMs = Math.min(EDGE_RETRY_MAX_MS, EDGE_RETRY_INITIAL_MS << shift);
        databaseEdgeRetryAttempt++;
        final int retryGeneration = ++databaseEdgeRetryGeneration;
        try {
            databaseEdgeRetryTask = sampler.schedule(
                    () -> retryDatabaseEdge(retryGeneration, desiredOpen),
                    delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            databaseEdgeRetryTask = null;
            logger.warn("Could not schedule session-row "
                    + (desiredOpen ? "open" : "close") + " retry: " + e.getMessage());
        }
    }

    private synchronized void retryDatabaseEdge(int retryGeneration, boolean desiredOpen) {
        if (shuttingDown || retryGeneration != databaseEdgeRetryGeneration
                || pendingDatabaseEdge == null || pendingDatabaseEdge != desiredOpen) {
            return;
        }
        databaseEdgeRetryTask = null;
        if (desiredOpen && !isAnalyticsRecordingReady()) {
            scheduleAnalyticsStateRetryLocked();
            return;
        }
        if (!desiredOpen && !retryPendingBoundary()) {
            scheduleDatabaseEdgeRetryLocked(false);
            return;
        }
        if (!applyDatabaseEdgeAndVerify(desiredOpen,
                desiredOpen ? "open retry" : "close retry")) {
            scheduleDatabaseEdgeRetryLocked(desiredOpen);
            return;
        }

        clearDatabaseEdgeRetryLocked();
        if (desiredOpen) {
            if (isAnalyticsRecordingReady()
                    && (charging || deferredStopDeadlineElapsedMs > 0L
                    || (!authoritativeStopPending && isTaperInProgress()))) {
                startSampling(true);
            } else if (!charging) {
                // The physical session ended while its open was retrying. Close the recovered row
                // instead of leaving it detached from any live generation.
                finishStoppedSession();
            }
            return;
        }

        completeClosedSessionAndStartDeferred();
    }

    private void completeClosedSessionAndStartDeferred() {
        completeClosedSessionCleanup();
        boolean restart = startAfterPendingClose;
        startAfterPendingClose = false;
        if (!restart) return;
        beginChargingSession();
        // The newer physical generation may have ended while the preceding close was unavailable.
        // Its start/end/counter were buffered by SocHistoryDatabase, so materialize and close it now
        // instead of dropping the whole session.
        if (!charging && !hasDeferredStop() && !isPendingDatabaseEdge(true)) {
            finishStoppedSession();
        }
    }

    private void completeClosedSessionCleanup() {
        long closedScopeGeneration = closeCleanupScopeGeneration;
        closeCleanupPending = false;
        closeCleanupScopeGeneration = 0L;
        closeCleanupSessionStart = -1L;
        releaseSessionScopedState(closedScopeGeneration);
        // A newer physical generation may have started while this row was retrying, then stopped
        // before the retry succeeded. Preserve it through its bounded final-counter drain, but do
        // not leave its resolver/classifier state open after that drain has completed.
        if (!charging && !hasDeferredStop()) releaseSessionScopedState();
    }

    private void prepareClosedSessionCleanup() {
        if (closeCleanupPending) return;
        closeCleanupPending = true;
        closeCleanupScopeGeneration = activeSessionScopeGeneration;
        closeCleanupSessionStart = socDb != null
                ? socDb.getChargingCloseTargetStart() : -1L;
    }

    private void clearDatabaseEdgeRetryLocked() {
        cancelDatabaseEdgeRetryTaskLocked();
        pendingDatabaseEdge = null;
        databaseEdgeRetryAttempt = 0;
    }

    private void cancelDatabaseEdgeRetryTaskLocked() {
        databaseEdgeRetryGeneration++;
        if (databaseEdgeRetryTask != null) {
            try { databaseEdgeRetryTask.cancel(false); } catch (Exception ignored) {}
        }
        databaseEdgeRetryTask = null;
    }

    private void cancelDatabaseEdgeRetryLocked() {
        clearDatabaseEdgeRetryLocked();
        startAfterPendingClose = false;
        closeCleanupPending = false;
        closeCleanupScopeGeneration = 0L;
        closeCleanupSessionStart = -1L;
    }

    synchronized boolean isAnalyticsRecordingReady() {
        return desiredAnalyticsEnabled
                && analyticsStateConfirmed
                && appliedAnalyticsEnabled;
    }

    private synchronized void latchDesiredAnalyticsStateLocked(boolean desired) {
        if (desiredAnalyticsEnabled == desired && analyticsStateConfirmed
                && appliedAnalyticsEnabled == desired) {
            return;
        }
        desiredAnalyticsEnabled = desired;
        analyticsStateConfirmed = false;
        updateLifecycleOwnerReadinessLocked();
        cancelAnalyticsStateRetryLocked();
    }

    /**
     * Apply the latched state. A false result is not treated as the current state: the journal and
     * H2 lifecycle must agree before an enabled state can open rows or admit samples.
     */
    synchronized boolean applyDesiredAnalyticsStateLocked(String source) {
        if (shuttingDown || socDb == null || config == null
                || config.isEnabled() != desiredAnalyticsEnabled) {
            return false;
        }
        boolean applied;
        try {
            applied = socDb.setChargingAnalyticsEnabled(desiredAnalyticsEnabled);
        } catch (Throwable t) {
            applied = false;
            logger.warn("Charging analytics state apply failed (" + source + "): "
                    + t.getMessage());
        }
        if (!applied) {
            analyticsStateConfirmed = false;
            updateLifecycleOwnerReadinessLocked();
            scheduleAnalyticsStateRetryLocked();
            return false;
        }
        appliedAnalyticsEnabled = desiredAnalyticsEnabled;
        analyticsStateConfirmed = true;
        updateLifecycleOwnerReadinessLocked();
        cancelAnalyticsStateRetryLocked();
        return true;
    }

    private synchronized void updateLifecycleOwnerReadinessLocked() {
        if (socDb == null) return;
        socDb.setChargingLifecycleOwnerReady(isLifecycleOwnerReadyLocked());
    }

    private boolean isLifecycleOwnerReadyLocked() {
        return !shuttingDown
                && detectorOwnershipReconciled
                && analyticsStateConfirmed
                && appliedAnalyticsEnabled == desiredAnalyticsEnabled;
    }

    private void scheduleAnalyticsStateRetryLocked() {
        if (shuttingDown || config == null
                || config.isEnabled() != desiredAnalyticsEnabled) {
            return;
        }
        if (analyticsStateRetryTask != null && !analyticsStateRetryTask.isDone()
                && !analyticsStateRetryTask.isCancelled()) {
            return;
        }
        ensureSamplerExecutor();
        if (sampler == null) return;
        int shift = Math.min(analyticsStateRetryAttempt, 6);
        long delayMs = Math.min(EDGE_RETRY_MAX_MS, EDGE_RETRY_INITIAL_MS << shift);
        analyticsStateRetryAttempt++;
        final boolean desired = desiredAnalyticsEnabled;
        final int generation = ++analyticsStateRetryGeneration;
        try {
            analyticsStateRetryTask = sampler.schedule(
                    () -> retryAnalyticsState(generation, desired),
                    delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            analyticsStateRetryTask = null;
            logger.warn("Could not schedule charging analytics state retry: " + e.getMessage());
        }
    }

    private synchronized void retryAnalyticsState(int generation, boolean desired) {
        if (shuttingDown || generation != analyticsStateRetryGeneration
                || desired != desiredAnalyticsEnabled || config == null
                || config.isEnabled() != desired) {
            return;
        }
        analyticsStateRetryTask = null;
        if (!applyDesiredAnalyticsStateLocked("retry")) return;
        cancelStartupSweepRetryLocked();
        if (!runStartupStaleSweep("analytics-state-ready")) {
            scheduleStartupStaleSweepRetry(0);
        }
        if (desired) resumeAnalyticsAfterStateApply();
    }

    private void resumeAnalyticsAfterStateApply() {
        if (!isAnalyticsRecordingReady()
                || !(charging || (!authoritativeStopPending && isTaperInProgress()))) {
            return;
        }
        if (isPendingDatabaseEdge(false) || closeCleanupPending) {
            startAfterPendingClose = true;
            finishStoppedSession();
            return;
        }
        beginChargingSession();
    }

    private void cancelAnalyticsStateRetryLocked() {
        analyticsStateRetryGeneration++;
        if (analyticsStateRetryTask != null) {
            try { analyticsStateRetryTask.cancel(false); } catch (Exception ignored) {}
        }
        analyticsStateRetryTask = null;
        analyticsStateRetryAttempt = 0;
    }

    /**
     * Resolve a pending A-close/B-start boundary while the database is still open during shutdown.
     *
     * <p>Scheduled retries are already fenced at this point. Retrying synchronously preserves the
     * replacement generation in a durable row; otherwise shutdown used to clear the only B intent and
     * restart could resume B into A's still-open row.
     */
    private synchronized void flushPendingDatabaseLifecycleForShutdown() {
        if (socDb == null) return;
        boolean hadBufferedReplacement = startAfterPendingClose;
        if (isPendingDatabaseEdge(false) || closeCleanupPending || hadBufferedReplacement) {
            for (int attempt = 0;
                 attempt < SHUTDOWN_DATABASE_EDGE_ATTEMPTS
                         && (isPendingDatabaseEdge(false)
                             || closeCleanupPending || startAfterPendingClose);
                 attempt++) {
                cancelDatabaseEdgeRetryTaskLocked();
                if (finishStoppedSession()) break;
            }
        }
        if (startAfterPendingClose) {
            logger.error("Shutdown could not make the buffered charging generation durable;"
                    + " retaining its in-memory boundary until database shutdown");
            return;
        }

        // beginChargingSession() already attempts this when A closes. Retry an unavailable B-open
        // synchronously; SocHistoryDatabase retains the deferred B payload until the INSERT commits.
        if (hadBufferedReplacement && socDb.getOpenChargingSessionStart() <= 0L) {
            for (int attempt = 0; attempt < SHUTDOWN_DATABASE_EDGE_ATTEMPTS; attempt++) {
                if (applyDatabaseEdgeAndVerify(true, "shutdown replacement open")) {
                    clearDatabaseEdgeRetryLocked();
                    break;
                }
            }
        }

        // If B also ended before A's retry recovered, its buffered endpoint is already captured. There
        // is no callback drain to wait for after listeners are removed, so close B before the DB stops.
        if (hadBufferedReplacement && !charging && socDb.getOpenChargingSessionStart() > 0L) {
            deferredStopDeadlineElapsedMs = 0L;
            for (int attempt = 0;
                 attempt < SHUTDOWN_DATABASE_EDGE_ATTEMPTS
                         && socDb.getOpenChargingSessionStart() > 0L;
                 attempt++) {
                cancelDatabaseEdgeRetryTaskLocked();
                finishStoppedSession();
            }
        }
    }

    /**
     * Append a {@code power_kw = -1} boundary row to the open session, breaking the integrator's
     * trapezoid chain so the gap that follows is not bridged as delivered energy.
     *
     * <p>No-op when no session is open or the last tick already wrote one.
     */
    private void writeGapBoundary() {
        // Same lock a sampler tick holds across its check-and-write, so the boundary cannot be
        // interleaved with a positive sample. The lastTickHadPower check MUST also live under this
        // lock: an in-flight first sample may have passed its generation check before stopSampling(),
        // then set the flag while shutdown is waiting here.
        synchronized (sampleWriteLock) {
            if (socDb == null || !lastTickHadPower) return;
            if (socDb.hasDeferredPhysicalGenerations()) {
                socDb.recordDeferredChargingSample(
                        System.currentTimeMillis(), SocHistoryDatabase.STOP_BOUNDARY_POWER_KW,
                        Double.NaN, -999, -999, -999);
                lastTickHadPower = false;
                return;
            }
            long sessionStart = socDb.getOpenChargingSessionStart();
            if (sessionStart <= 0) return;
            long atMs = System.currentTimeMillis();
            if (!persistBoundaryLocked(
                    sessionStart, atMs, SocHistoryDatabase.STOP_BOUNDARY_POWER_KW)) {
                pendingBoundarySessionStart = sessionStart;
                pendingBoundaryAtMs = atMs;
                pendingBoundaryPowerKw = SocHistoryDatabase.STOP_BOUNDARY_POWER_KW;
            }
            lastTickHadPower = false;
        }
    }

    private boolean persistBoundaryLocked(long sessionStart, long atMs, double boundaryPowerKw) {
        if (socDb == null || sessionStart <= 0) return false;
        for (int attempt = 0; attempt < BOUNDARY_WRITE_ATTEMPTS; attempt++) {
            if (socDb.recordChargingSample(sessionStart, atMs,
                    boundaryPowerKw, Double.NaN, -999, -999, -999)) {
                return true;
            }
        }
        logger.warn("Charging gap boundary remains pending for session " + sessionStart);
        return false;
    }

    private boolean retryPendingBoundary() {
        synchronized (sampleWriteLock) {
            if (pendingBoundarySessionStart <= 0) return true;
            long openSessionStart = socDb != null
                    ? socDb.getOpenChargingSessionStart() : -1L;
            if (openSessionStart != pendingBoundarySessionStart) {
                // Closing or atomically replacing the row is itself a hard integration boundary.
                // Retrying the obsolete key would otherwise block every sample of the new row.
                pendingBoundarySessionStart = -1L;
                pendingBoundaryAtMs = 0L;
                pendingBoundaryPowerKw = SocHistoryDatabase.STOP_BOUNDARY_POWER_KW;
                return true;
            }
            if (!persistBoundaryLocked(
                    pendingBoundarySessionStart, pendingBoundaryAtMs,
                    pendingBoundaryPowerKw)) {
                return false;
            }
            pendingBoundarySessionStart = -1L;
            pendingBoundaryAtMs = 0L;
            pendingBoundaryPowerKw = SocHistoryDatabase.STOP_BOUNDARY_POWER_KW;
            return true;
        }
    }

    /** Release everything scoped to one charging session: rate slopes, scale reference, classifier runs. */
    private void releaseSessionScopedState() {
        releaseSessionScopedState(activeSessionScopeGeneration);
    }

    private void releaseSessionScopedState(long expectedGeneration) {
        if (expectedGeneration <= 0L || activeSessionScopeGeneration != expectedGeneration) return;
        // Clear ownership first so duplicate retry/callback paths cannot release this generation twice.
        activeSessionScopeGeneration = 0L;
        try { com.overdrive.app.monitor.ChargeRateResolver.onSessionEnded(); }
        catch (Throwable ignored) {}
        releaseClassifierState();
    }

    /** Close any in-progress repeat/rise runs so they cannot bridge two separate charges. */
    private void releaseClassifierState() {
        try { com.overdrive.app.byd.ChargeSourceClassifier.onSessionEnded(); } catch (Throwable ignored) {}
    }

    /** True while the monitor reports a CV taper, i.e. current still flowing after FINISHED. */
    private boolean isTaperInProgress() {
        try {
            VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
            if (vm == null) return false;
            ChargingStateData cs = vm.getChargingState();
            return cs != null && cs.isTaperCharging;
        } catch (Throwable t) {
            return false;
        }
    }

    private synchronized void ensureSamplerExecutor() {
        if (sampler != null && !sampler.isShutdown()) return;
        if (shuttingDown) return;
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChargeSampler");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    logger.error("Uncaught in ChargeSampler: " + ex.getMessage(), ex));
            return t;
        });
    }

    private void shutdownSamplerExecutor() {
        ScheduledExecutorService executor = sampler;
        sampler = null;
        if (executor == null) return;
        try {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            try { executor.shutdownNow(); } catch (Exception ignoredAgain) {}
        }
    }

    /**
     * Reconcile registration with the detector's current state. A transition can race either state
     * read, so repeat until the state applied by this thread still matches the detector; a transition
     * after the final equality check is delivered by the registered listener.
     */
    private void reconcileDetectorState(String source) {
        ChargingDetector detector = ChargingDetector.getInstance();
        for (int i = 0; i < 4; i++) {
            boolean state = detector.isCharging();
            onFusedChargingChanged(state, source);
            if (detector.isCharging() == state) {
                reconcileRecoveredLifecycleWhilePhysicalOff(state, source);
                return;
            }
        }
        boolean state = detector.isCharging();
        onFusedChargingChanged(state, source + "-final");
        reconcileRecoveredLifecycleWhilePhysicalOff(state, source + "-final");
    }

    /**
     * The manager starts with {@code charging=false}, so an actually-OFF detector produces no edge
     * callback. A journal-restored A/B/C lifecycle still has to drain in that case.
     */
    private synchronized void reconcileRecoveredLifecycleWhilePhysicalOff(
            boolean physicalCharging, String source) {
        if (physicalCharging || socDb == null || !socDb.hasPendingChargingLifecycle()) return;
        prepareClosedSessionCleanup();
        if (applyDatabaseEdgeAndVerify(false, source + " recovered lifecycle")) {
            clearDatabaseEdgeRetryLocked();
            completeClosedSessionCleanup();
        } else {
            scheduleDatabaseEdgeRetryLocked(false);
        }
    }

    /**
     * Re-run the idempotent startup sweep after delays so a transient database failure cannot leave an
     * abandoned OPEN row until the next daemon restart. Each attempt re-snapshots detector state first;
     * a genuinely live in-memory row remains protected by SocHistoryDatabase's ownership check.
     */
    private synchronized boolean runStartupStaleSweep(String source) {
        if (!isLifecycleOwnerReadyLocked() || socDb == null || config == null) return false;
        ChargingDetector detector = ChargingDetector.getInstance();
        reconcileDetectorState(source + "-pre");
        boolean detectorCharging = detector.isCharging();
        boolean managerOwnsLiveRow = charging || hasDeferredStop()
                || closeCleanupPending || isPendingDatabaseEdge(true);
        boolean forceRecent = !config.isEnabled()
                || (!detectorCharging && !managerOwnsLiveRow);
        boolean committed = false;
        try {
            committed = socDb.finalizeStaleOpenSessions(forceRecent);
        } catch (Throwable t) {
            logger.warn("Startup stale-session sweep failed (" + source + "): " + t.getMessage());
        }
        reconcileDetectorState(source + "-post");
        if (!detector.isCharging() && (!managerOwnsLiveRow || !config.isEnabled())) {
            try {
                // Covers a transition that raced the first state snapshot and normal sweep.
                committed &= socDb.finalizeStaleOpenSessions(true);
            } catch (Throwable t) {
                logger.warn("Forced startup stale-session sweep failed (" + source + "): "
                        + t.getMessage());
                committed = false;
            }
        }
        return committed;
    }

    private synchronized void scheduleStartupStaleSweepRetry(int retryIndex) {
        if (shuttingDown || retryIndex < 0
                || retryIndex >= STARTUP_SWEEP_RETRY_DELAYS_MS.length) {
            return;
        }
        ensureSamplerExecutor();
        if (sampler == null) return;
        final int retryGeneration = ++startupSweepRetryGeneration;
        try {
            startupSweepRetryTask = sampler.schedule(
                    () -> runStartupStaleSweepRetry(retryGeneration, retryIndex),
                    STARTUP_SWEEP_RETRY_DELAYS_MS[retryIndex], TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            startupSweepRetryTask = null;
            logger.warn("Could not schedule stale-session startup retry: " + e.getMessage());
        }
    }

    private synchronized void runStartupStaleSweepRetry(int retryGeneration, int retryIndex) {
        if (shuttingDown || retryGeneration != startupSweepRetryGeneration) return;
        startupSweepRetryTask = null;
        if (!runStartupStaleSweep("retry-" + (retryIndex + 1))) {
            scheduleStartupStaleSweepRetry(retryIndex + 1);
        }
    }

    private void cancelStartupSweepRetryLocked() {
        startupSweepRetryGeneration++;
        if (startupSweepRetryTask != null) {
            try { startupSweepRetryTask.cancel(false); } catch (Exception ignored) {}
        }
        startupSweepRetryTask = null;
    }

    private synchronized void startSampling(boolean resetPowerState) {
        if (shuttingDown) return;
        // Construct on demand so runtime analytics enable works after a disabled startup.
        if (isAnalyticsRecordingReady()) ensureSamplerExecutor();
        if (sampler == null || config == null) return;
        if (!isAnalyticsRecordingReady()) return;   // desired state is not durably applied
        if (sampleTask != null && !sampleTask.isCancelled()) return; // already running
        if (!retryPendingBoundary()) {
            logger.warn("Fast sampler start deferred until the prior gap boundary can be persisted");
        }
        // Reset only for a newly-opened recorded segment. An interval-only task restart belongs to the
        // same row and must retain this flag so shutdown/stop can still append its pending boundary.
        if (resetPowerState) {
            lastTickHadPower = false;
            missingRateIntervalMarked = false;
        }
        int periodSec = config.getFastSampleSec();
        final int generation = samplerGeneration;
        try {
            sampleTask = sampler.scheduleAtFixedRate(
                    () -> sampleOnce(generation), 0, periodSec, TimeUnit.SECONDS);
            logger.info("Fast charging sampler started (every " + periodSec + "s)");
        } catch (Exception e) {
            logger.error("Failed to start fast sampler: " + e.getMessage());
        }
    }

    private synchronized void stopSampling() {
        samplerGeneration++;   // invalidate any tick already in flight
        if (sampleTask != null) {
            try { sampleTask.cancel(false); } catch (Exception ignored) {}
            sampleTask = null;
        }
    }

    /**
     * One fast-sampler tick: snapshot power/SoC/temp and append a ramp sample to
     * the currently-open charging session. Best-effort; never throws.
     */
    private void sampleOnce(int generation) {
        try {
            if (socDb == null || shuttingDown || generation != samplerGeneration) return;

            VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
            if (vm == null) {
                if (!charging) {
                    long deadline = deferredStopDeadlineElapsedMs;
                    if (deadline <= 0L || monotonicNowMs() >= deadline) {
                        finishStoppedSessionIfGeneration(generation);
                    }
                }
                return;
            }

            ChargingStateData cs = vm.getChargingState();
            // Keep sampling through a constant-voltage taper. The fused verdict has already gone
            // false by then (the BMS calls the session FINISHED while current still flows), so gating
            // on `charging` alone dropped the taper tail out of the curve while the session row it
            // belongs to was still open and still accruing that energy.
            boolean taperStillAdmitted = !authoritativeStopPending
                    && cs != null && cs.isTaperCharging;
            if (!charging && !taperStillAdmitted) {
                socDb.setChargingLiveEnrichmentAllowed(false);
                long deadline = deferredStopDeadlineElapsedMs;
                if (deadline > 0 && monotonicNowMs() < deadline) {
                    return;
                }
                // The taper we were held open for has ended. Stop now — onFusedChargingChanged
                // deliberately declined to cancel the task while the taper was live, so this is the
                // only place that can, and without it the sampler would run for the rest of uptime.
                // It also deferred all session cleanup for the same reason, so complete the boundary,
                // classifier/rate reset, taper fence, and DB close together here.
                finishStoppedSessionIfGeneration(generation);
                return;
            }
            if (!charging && taperStillAdmitted) {
                deferredStopDeadlineElapsedMs = 0L;
                socDb.setChargingLiveEnrichmentAllowed(true);
            }
            boolean deferredTarget = socDb.hasDeferredPhysicalGenerations();
            long sessionStart = deferredTarget
                    ? -1L : socDb.getOpenChargingSessionStart();
            if (!deferredTarget && sessionStart <= 0) {
                if (!charging) finishStoppedSessionIfGeneration(generation);
                return;
            }
            // A positive sample after a missing boundary would reconnect the trapezoid chain. Retry
            // first and decline this tick if persistence is still unavailable.
            if (!deferredTarget && !retryPendingBoundary()) return;
            double power = cs != null ? cs.chargingPowerKW : Double.NaN;
            // No usable MEASURED rate this tick. Two cases, both skipped for the curve:
            //   - NaN/<=0: ACC-off or every source refused (including a source still UNKNOWN).
            //   - isEstimated: a nominal placeholder or an inference, which must never enter a
            //     curve that is integrated into energy and then priced.
            //
            // MARK THE GAP rather than returning silently. The energy integrator resets its
            // trapezoid chain on any power_kw <= 0 row, so writing an explicit boundary is what
            // stops the next live sample being bridged back to the last one across a hole that
            // delivered nothing. Returning without a row left the integrator unable to see the gap
            // at all, and it then credited the whole interval at the last known rate.
            if (Double.isNaN(power) || power <= 0 || cs.isEstimated) {
                // One -2 marker per contiguous missing-rate interval. This includes a session that
                // begins without a usable rate; waiting for a positive-to-missing falling edge made
                // that opening interval disappear without setting energy_incomplete.
                if (!missingRateIntervalMarked) {
                    try {
                        // SAME generation fence and lock as the positive path. Without it a tick that
                        // was cancelled mid-flight could append its -1 boundary AFTER charging resumed
                        // and a new positive sample had landed, severing the trapezoid chain inside a
                        // live charge and under-counting its energy — the mirror image of the race the
                        // positive path already guards.
                        synchronized (sampleWriteLock) {
                            if (generation != samplerGeneration) return;
                            long atMs = System.currentTimeMillis();
                            boolean persisted = deferredTarget
                                    ? socDb.recordDeferredChargingSample(
                                            atMs, SocHistoryDatabase.MISSING_RATE_BOUNDARY_POWER_KW,
                                            Double.NaN, -999, -999, -999)
                                    : persistBoundaryLocked(
                                            sessionStart, atMs,
                                            SocHistoryDatabase.MISSING_RATE_BOUNDARY_POWER_KW);
                            if (persisted) {
                                lastTickHadPower = false;
                                missingRateIntervalMarked = true;
                            } else if (!deferredTarget) {
                                pendingBoundarySessionStart = sessionStart;
                                pendingBoundaryAtMs = atMs;
                                pendingBoundaryPowerKw =
                                        SocHistoryDatabase.MISSING_RATE_BOUNDARY_POWER_KW;
                                lastTickHadPower = false;
                                missingRateIntervalMarked = true;
                            } else {
                                // The deferred generation already contains the marker in memory;
                                // its next successful journal write will make both it and later
                                // samples durable.
                                lastTickHadPower = false;
                                missingRateIntervalMarked = true;
                            }
                        }
                    } catch (Exception ignored) {}
                }
                return;
            }

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

            // Check-and-write ATOMICALLY. A bare check before the write leaves a TOCTOU window: the
            // tick passes the check, OFF then increments the generation and writes the -1 boundary, and
            // the tick's positive sample lands after it — re-arming the trapezoid chain across a real
            // pause, which is the exact thing the boundary exists to prevent. Holding sampleWriteLock
            // across both makes the sequence indivisible with respect to writeGapBoundary(), which
            // takes the same lock.
            synchronized (sampleWriteLock) {
                if (generation != samplerGeneration) return;
                if (pendingBoundarySessionStart > 0
                        && !persistBoundaryLocked(
                                pendingBoundarySessionStart, pendingBoundaryAtMs,
                                pendingBoundaryPowerKw)) {
                    return;
                }
                pendingBoundarySessionStart = -1L;
                pendingBoundaryAtMs = 0L;
                pendingBoundaryPowerKw = SocHistoryDatabase.STOP_BOUNDARY_POWER_KW;
                boolean persisted = deferredTarget
                        ? socDb.recordDeferredChargingSample(
                                System.currentTimeMillis(), power, socPct,
                                temp, tempHigh, tempLow)
                        : socDb.recordChargingSample(
                                sessionStart, System.currentTimeMillis(), power, socPct,
                                temp, tempHigh, tempLow);
                if (persisted) {
                    lastTickHadPower = true;
                    missingRateIntervalMarked = false;
                }
            }
        } catch (Exception e) {
            logger.debug("sampleOnce failed: " + e.getMessage());
        }
    }

    // ==================== CONFIG ====================

    /** Re-read config after a POST /api/charging/config and restart sampler if needed. */
    public synchronized void onConfigChanged() {
        reloadRuntimeConfig();
    }

    /**
     * Re-read every charging-owned runtime setting after a whole-config restore.
     *
     * @return true when both charging config and tariffs were reloaded
     */
    public synchronized boolean onConfigRestored() {
        return reloadRuntimeConfig();
    }

    private boolean reloadRuntimeConfig() {
        if (shuttingDown || config == null) return false;
        boolean wasEnabled = config.isEnabled();
        long priorDeferredDeadline = deferredStopDeadlineElapsedMs;
        boolean hadDeferredDeadline = priorDeferredDeadline > 0L;
        if (!config.load()) {
            logger.warn("Charging config reload failed; keeping current runtime state");
            return false;
        }
        boolean enabled = config.isEnabled();
        latchDesiredAnalyticsStateLocked(enabled);
        // Tariffs live in the same chargingAnalytics section, so re-read them on
        // every config change — otherwise the daemon keeps pricing with a stale
        // list after the UI edits one.
        boolean tariffsReloaded = false;
        try {
            tariffsReloaded = TariffManager.getInstance().load();
            if (tariffsReloaded && socDb != null) {
                socDb.replayPendingChargingPostCommitMetadata();
            }
        } catch (Throwable t) {
            logger.warn("Tariff reload skipped: " + t.getMessage());
        }
        if (!tariffsReloaded) {
            logger.warn("Tariff reload failed; strict pricing reads will retry");
        }
        boolean shouldResume = charging || isTaperInProgress();
        if (wasEnabled && !enabled) {
            // Persist the boundary while the row is still open, then close it immediately. Waiting for
            // the next SoC tick leaves an opted-out interval inside the row if the user re-enables first.
            stopSampling();
            deferredStopDeadlineElapsedMs = 0L;
            authoritativeStopPending = false;
            writeGapBoundary();
            retryPendingBoundary();
            if (!applyDesiredAnalyticsStateLocked("config-disable")) {
                logger.warn("Feature-disable boundary remains pending journal durability");
            }
            prepareClosedSessionCleanup();
            startAfterPendingClose = false;
            if (applyDatabaseEdgeAndVerify(false, "feature-disable close")) {
                clearDatabaseEdgeRetryLocked();
                completeClosedSessionCleanup();
            } else {
                scheduleDatabaseEdgeRetryLocked(false);
            }
        } else if (!wasEnabled && enabled) {
            boolean stateApplied = applyDesiredAnalyticsStateLocked("config-enable");
            if (!stateApplied) {
                logger.warn("Feature re-enable remains fenced behind the durable opt-out boundary");
            }
            if (stateApplied && shouldResume) {
                if (isPendingDatabaseEdge(false) || closeCleanupPending) {
                    // A failed disable-time close must complete before this recorded segment starts,
                    // otherwise the counter spans the opted-out interval.
                    startAfterPendingClose = true;
                    // finishStoppedSession() consumes this flag and opens the replacement exactly
                    // once. On failure, its retry path retains the same intent.
                    finishStoppedSession();
                } else {
                    // Live display resolution continued during opt-out and may have rebuilt slope and
                    // classifier state. Fence it at the exact recording boundary.
                    releaseSessionScopedState();
                    beginChargingSession();
                }
            }
        } else {
            boolean stateApplied = applyDesiredAnalyticsStateLocked("config-reload");
            if (!stateApplied) {
                logger.warn("Charging analytics state change remains pending journal durability");
            }
            // Interval-only change: fence the old task, then preserve both the physical sampler and a
            // deferred OFF deadline on the new generation. Without the latter, the old generation's
            // one-shot exits at its fence and the held-open row never closes.
            stopSampling();
            if (stateApplied && enabled && hadDeferredDeadline) {
                long remainingMs = Math.max(1L, priorDeferredDeadline - monotonicNowMs());
                deferStoppedSession(remainingMs);
            }
            if (stateApplied && enabled && (shouldResume || hadDeferredDeadline)) {
                startSampling(false);
            }
        }
        logger.info("ChargingSessionManager config reloaded — enabled=" + enabled
                + " fastSampleSec=" + config.getFastSampleSec());
        return tariffsReloaded;
    }

    // ==================== ACCESSORS ====================

    public ChargingConfig getConfig() { return config; }
    public SocHistoryDatabase getSocDb() { return socDb != null ? socDb : SocHistoryDatabase.getInstance(); }
    public boolean isInitialized() { return initialized; }
    public boolean isChargingNow() { return charging; }
}
