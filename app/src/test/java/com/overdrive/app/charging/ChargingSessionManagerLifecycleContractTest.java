package com.overdrive.app.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.ChargeRateResolver;
import com.overdrive.app.monitor.SocHistoryDatabase;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.Test;

/** Regression guards for charging lifecycle ordering that depends on Android/HAL callbacks. */
public class ChargingSessionManagerLifecycleContractTest {

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false)
                .withStdoutLog(false));
    }

    @Test
    public void databaseEdgeRequiresCommitResultAndRequestedOpenState() throws IOException {
        String source = managerSource();
        int start = source.indexOf("boolean applyDatabaseEdgeAndVerify");
        int end = source.indexOf("synchronized void scheduleDatabaseEdgeRetryLocked", start);
        String method = source.substring(start, end);

        assertTrue(method.contains(
                "? socDb.onChargingEdge(true)"));
        assertTrue(method.contains(
                "false, Math.max(0L, expectedCloseStart)"));
        assertTrue(method.contains(
                "socDb.isChargingSessionCloseSatisfied(expectedCloseStart)"));
        assertTrue(method.contains("boolean success = committed"));
        assertTrue(method.contains(
                "return desiredOpen == (openSessionStart > 0L);"));
    }

    @Test
    public void intervalReloadRecreatesDeferredCloseOnNewGeneration() throws IOException {
        String source = managerSource();
        int branch = source.indexOf(
                "// Interval-only change: fence the old task");
        int end = source.indexOf("logger.info(\"ChargingSessionManager config reloaded", branch);
        String body = source.substring(branch, end);

        int stop = body.indexOf("stopSampling();");
        int remaining = body.indexOf(
                "priorDeferredDeadline - monotonicNowMs()");
        int defer = body.indexOf("deferStoppedSession(remainingMs);");
        int restart = body.indexOf("startSampling(false);");

        assertTrue(stop >= 0);
        assertTrue(remaining > stop);
        assertTrue(defer > remaining);
        assertTrue(restart > defer);
        assertTrue(body.contains("if (stateApplied && enabled && hadDeferredDeadline)"));
    }

    @Test
    public void shutdownBoundaryReadsPowerFlagOnlyAfterTakingWriteLock() throws IOException {
        String source = managerSource();
        int start = source.indexOf("private void writeGapBoundary()");
        int end = source.indexOf("private boolean persistBoundaryLocked", start);
        String method = source.substring(start, end);

        int lock = method.indexOf("synchronized (sampleWriteLock)");
        int flag = method.indexOf("!lastTickHadPower");
        int session = method.indexOf("getOpenChargingSessionStart()");

        assertTrue(lock >= 0);
        assertTrue(flag > lock);
        assertTrue(session > flag);
        assertFalse(method.substring(0, lock).contains("!lastTickHadPower"));
    }

    @Test
    public void authoritativeStopReportsOffBeforeBoundedCounterDrain() throws IOException {
        String source = managerSource();
        int start = source.indexOf(
                "public synchronized void onAuthoritativeChargingStop");
        int end = source.indexOf("private boolean isPendingDatabaseEdge", start);
        String method = source.substring(start, end);

        int physicalOff = method.indexOf("charging = false;");
        int fence = method.indexOf("stopSampling();");
        int boundary = method.indexOf("writeGapBoundary();");
        int drain = method.lastIndexOf(
                "deferStoppedSession(FINAL_COUNTER_GRACE_MS);");

        assertTrue(physicalOff >= 0);
        assertTrue(fence > physicalOff);
        assertTrue(boundary > fence);
        assertTrue(drain > boundary);
        assertTrue(method.contains("authoritativeStopPending = true;"));
        String boundedDrainPath = method.substring(
                method.indexOf("// If a close was already retrying"));
        assertFalse(boundedDrainPath.substring(0, boundedDrainPath.indexOf("deferStoppedSession("))
                .contains("finishStoppedSession();"));
    }

    @Test
    public void failedCloseRetriesBeforeNewGenerationCanOpen() throws IOException {
        String source = managerSource();
        int edgeStart = source.indexOf(
                "public synchronized void onFusedChargingChanged");
        int edgeEnd = source.indexOf("private void beginChargingSession", edgeStart);
        String edge = source.substring(edgeStart, edgeEnd);

        int capture = edge.indexOf("prepareClosedSessionCleanup();");
        int samplingFence = edge.indexOf("stopSampling();", capture);
        int advance = edge.indexOf("advanceSessionScopedStateForPhysicalOn();");
        int waitIntent = edge.indexOf("startAfterPendingClose = true;");
        int close = edge.indexOf("finishStoppedSession()", waitIntent);
        int open = edge.indexOf("beginChargingSession()", close);
        assertTrue(capture >= 0);
        assertTrue(samplingFence > capture);
        assertTrue(advance > samplingFence);
        assertTrue(waitIntent > advance);
        assertTrue(close > waitIntent);
        assertTrue(open > close);

        int finishStart = source.indexOf(
                "private synchronized boolean finishStoppedSession()");
        int finishEnd = source.indexOf(
                "private synchronized void finishStoppedSessionIfGeneration", finishStart);
        String finish = source.substring(finishStart, finishEnd);
        assertTrue(finish.contains("scheduleDatabaseEdgeRetryLocked(false);"));
        assertTrue(finish.indexOf("completeClosedSessionAndStartDeferred()")
                > finish.indexOf("applyDatabaseEdgeAndVerify(false"));
        assertTrue(source.contains(
                "false, Math.max(0L, expectedCloseStart)"));
        assertTrue(source.contains("boolean success = committed"));
    }

    @Test
    public void failedCloseAdvancesPhysicalProofAndRetryCannotClearNewGeneration() throws Exception {
        FailedCloseManager manager = new FailedCloseManager();
        String raw320 = "failedCloseRaw320-" + System.nanoTime();
        String raw650 = "failedCloseRaw650-" + System.nanoTime();
        DaemonLogger.Config previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        try {
            manager.onFusedChargingChanged(true, "session-a");
            assertEquals(3.2, ChargeRateResolver.rateKw(raw320, 320.0, 3.2), 1e-9);
            assertEquals(6.5, ChargeRateResolver.rateKw(raw650, 650.0, 6.5), 1e-9);
            assertEquals(3.2, ChargeRateResolver.rateKw(raw320, 320.0), 1e-9);
            assertEquals(6.5, ChargeRateResolver.rateKw(raw650, 650.0), 1e-9);

            // Model A's physical OFF after its row close has entered the retry state. B's fused ON
            // must advance the resolver/classifier generation before the failed close returns.
            invoke(manager, "prepareClosedSessionCleanup");
            setBoolean(manager, "charging", false);
            manager.failClose = true;
            manager.onFusedChargingChanged(true, "session-b");

            assertEquals(1, manager.closeAttempts);
            assertEquals(1, manager.retryRequests);
            assertEquals(0, manager.openAttempts);
            assertTrue(Double.isNaN(ChargeRateResolver.rateKw(raw320, 320.0)));
            assertTrue(Double.isNaN(ChargeRateResolver.rateKw(raw650, 650.0)));
            assertFalse(ChargeRateResolver.isScaleVerified(raw320, 3.2));
            assertFalse(ChargeRateResolver.isScaleVerified(raw650, 6.5));

            // B can establish its own proof while A remains fenced. A later successful close retry
            // must target only A's captured generation and leave B's proof active.
            assertEquals(3.2, ChargeRateResolver.rateKw(raw320, 320.0, 3.2), 1e-9);
            assertEquals(6.5, ChargeRateResolver.rateKw(raw650, 650.0, 6.5), 1e-9);
            manager.failClose = false;
            assertTrue((Boolean) invoke(manager, "finishStoppedSession"));
            assertEquals(3.2, ChargeRateResolver.rateKw(raw320, 320.0), 1e-9);
            assertEquals(6.5, ChargeRateResolver.rateKw(raw650, 650.0), 1e-9);

            // A duplicate ON callback is not a new physical generation.
            manager.onFusedChargingChanged(true, "session-b-duplicate");
            assertEquals(3.2, ChargeRateResolver.rateKw(raw320, 320.0), 1e-9);
            assertEquals(6.5, ChargeRateResolver.rateKw(raw650, 650.0), 1e-9);
        } finally {
            ChargeRateResolver.onSessionEnded();
            DaemonLogger.configure(previousLogConfig);
        }
    }

    @Test
    public void closingPriorRowReleasesOnlyItsCapturedClassifierGeneration()
            throws IOException {
        String source = managerSource();
        String cleanup = sourceBetween(
                "private void completeClosedSessionCleanup()",
                "private void prepareClosedSessionCleanup()");
        String release = sourceBetween(
                "private void releaseSessionScopedState(long expectedGeneration)",
                "/** Close any in-progress repeat/rise runs");

        assertTrue(cleanup.contains(
                "long closedScopeGeneration = closeCleanupScopeGeneration"));
        assertTrue(cleanup.contains(
                "releaseSessionScopedState(closedScopeGeneration)"));
        assertTrue(release.contains(
                "activeSessionScopeGeneration != expectedGeneration"));
        assertTrue(release.contains("releaseClassifierState();"));
    }

    @Test
    public void failedStartupSweepUsesBooleanResultAndDelayedRetry() throws IOException {
        String source = managerSource();
        assertTrue(source.contains(
                "|| !runStartupStaleSweep(\"init\"))"));
        assertTrue(source.contains(
                "committed = socDb.finalizeStaleOpenSessions(forceRecent);"));
        assertTrue(source.contains(
                "if (!runStartupStaleSweep(\"retry-\" + (retryIndex + 1)))"));
        assertTrue(source.contains("STARTUP_SWEEP_RETRY_DELAYS_MS"));
    }

    @Test
    public void startupReadinessOpensOnlyAfterConfigAndDetectorReconciliation()
            throws IOException {
        String source = managerSource();
        String init = sourceBetween(
                "public void init(Context context)",
                "public void shutdown()");

        int gateClosed = init.indexOf(
                "socDb.setChargingLifecycleOwnerReady(false)");
        int analyticsApplied = init.indexOf(
                "applyDesiredAnalyticsStateLocked(\"init\")");
        int listener = init.indexOf("detector.addFusedStateListener(this)");
        int reconcile = init.indexOf("reconcileDetectorState(\"init-seed\")");
        int ownership = init.indexOf("detectorOwnershipReconciled = true");
        int gateOpened = init.indexOf(
                "updateLifecycleOwnerReadinessLocked()", ownership);
        int staleSweep = init.indexOf("runStartupStaleSweep(\"init\")");

        assertTrue(gateClosed >= 0);
        assertTrue(analyticsApplied > gateClosed);
        assertTrue(listener > analyticsApplied);
        assertTrue(reconcile > listener);
        assertTrue(ownership > reconcile);
        assertTrue(gateOpened > ownership);
        assertTrue(staleSweep > gateOpened);
        assertTrue(init.contains(
                "socDb.setChargingLifecycleOwnerReady(false); } catch"));

        String shutdown = sourceBetween(
                "public void shutdown()",
                "// ==================== FUSED CHARGING EDGE");
        assertTrue(shutdown.contains(
                "socDb.setChargingLifecycleOwnerReady(false)"));

        String readiness = sourceBetween(
                "private synchronized void updateLifecycleOwnerReadinessLocked()",
                "private void scheduleAnalyticsStateRetryLocked");
        assertTrue(readiness.contains("detectorOwnershipReconciled"));
        assertTrue(readiness.contains("analyticsStateConfirmed"));
        assertTrue(readiness.contains(
                "appliedAnalyticsEnabled == desiredAnalyticsEnabled"));
        assertTrue(readiness.contains(
                "socDb.setChargingLifecycleOwnerReady(isLifecycleOwnerReadyLocked())"));
        assertTrue(source.contains(
                "if (!isLifecycleOwnerReadyLocked() || socDb == null || config == null)"));
        assertTrue(source.contains(
                "runStartupStaleSweep(\"analytics-state-ready\")"));
    }

    @Test
    public void provenTaperAloneReenablesLiveEnrichmentAfterFinished()
            throws IOException {
        String edge = sourceBetween(
                "public synchronized void onFusedChargingChanged",
                "private void beginChargingSession");
        String sample = sourceBetween(
                "private void sampleOnce(int generation)",
                "// ==================== CONFIG");
        String finish = sourceBetween(
                "private synchronized boolean finishStoppedSession()",
                "private synchronized void finishStoppedSessionIfGeneration");

        int physicalState = edge.indexOf("socDb.setPhysicalChargingNow(isCharging)");
        int taper = edge.indexOf("if (taper)");
        int boundary = edge.indexOf("socDb.capturePendingChargingClose()", taper);
        int admit = edge.indexOf(
                "socDb.setChargingLiveEnrichmentAllowed(true)", taper);
        assertTrue(physicalState >= 0);
        assertTrue(taper > physicalState);
        assertTrue(boundary > taper);
        assertTrue(admit > boundary);
        String duplicate = edge.substring(
                edge.indexOf("if (isCharging == this.charging)"),
                edge.indexOf("this.charging = isCharging"));
        assertTrue(duplicate.contains("!authoritativeStopPending"));
        assertTrue(duplicate.contains("isTaperInProgress()"));
        assertTrue(duplicate.contains(
                "socDb.setChargingLiveEnrichmentAllowed(true)"));

        String noTaper = sample.substring(
                sample.indexOf("if (!charging && !taperStillAdmitted)"),
                sample.indexOf("if (!charging && taperStillAdmitted)"));
        assertTrue(noTaper.contains(
                "socDb.setChargingLiveEnrichmentAllowed(false)"));
        assertTrue(sample.contains(
                "socDb.setChargingLiveEnrichmentAllowed(true)"));
        assertTrue(finish.contains(
                "socDb.setChargingLiveEnrichmentAllowed(false)"));
    }

    @Test
    public void failedPriorCloseBuffersAndEventuallyMaterializesNewPhysicalSession()
            throws IOException {
        String source = managerSource();
        String edge = source.substring(
                source.indexOf("public synchronized void onFusedChargingChanged"),
                source.indexOf("private void beginChargingSession"));
        String complete = source.substring(
                source.indexOf("private void completeClosedSessionAndStartDeferred"),
                source.indexOf("private void completeClosedSessionCleanup"));

        assertTrue(edge.contains("socDb.deferPhysicalChargingStart()"));
        assertTrue(edge.contains("socDb.deferPhysicalChargingStop()"));
        assertTrue(edge.contains(
                "!socDb.deferPhysicalChargingStart()"));
        assertTrue(edge.contains(
                "Charging stop boundary is waiting for lifecycle-journal durability"));
        assertTrue(complete.contains("boolean restart = startAfterPendingClose"));
        assertTrue(complete.contains("beginChargingSession();"));
        assertTrue(complete.contains(
                "if (!charging && !hasDeferredStop() && !isPendingDatabaseEdge(true))"));
        assertTrue(complete.contains("finishStoppedSession();"));
    }

    @Test
    public void failedPriorCloseKeepsSamplingTheDeferredGeneration() throws IOException {
        String source = managerSource();
        String edge = source.substring(
                source.indexOf("public synchronized void onFusedChargingChanged"),
                source.indexOf("private void beginChargingSession"));
        String sample = sourceBetween(
                "private void sampleOnce(int generation)",
                "// ==================== CONFIG");
        String boundary = sourceBetween(
                "private void writeGapBoundary()",
                "private boolean persistBoundaryLocked");

        int stop = edge.indexOf("stopSampling();");
        int oldBoundary = edge.indexOf("writeGapBoundary();", stop);
        int queue = edge.indexOf("socDb.deferPhysicalChargingStart()", oldBoundary);
        assertTrue(stop >= 0);
        assertTrue(oldBoundary > stop);
        assertTrue(queue > oldBoundary);
        assertTrue(edge.contains(
                "if (isAnalyticsRecordingReady()) startSampling(true);"));

        assertTrue(sample.contains(
                "boolean deferredTarget = socDb.hasDeferredPhysicalGenerations()"));
        assertTrue(sample.contains("socDb.recordDeferredChargingSample("));
        assertTrue(boundary.contains("socDb.recordDeferredChargingSample("));
    }

    @Test
    public void thirdPhysicalGenerationClosesThePriorDeferredCurveBeforeQueueing()
            throws IOException {
        String edge = sourceBetween(
                "public synchronized void onFusedChargingChanged",
                "private void beginChargingSession");

        int stop = edge.indexOf("stopSampling();");
        int boundary = edge.indexOf("writeGapBoundary();", stop);
        int defer = edge.indexOf("deferPhysicalChargingStart()", boundary);
        int advance = edge.indexOf("advanceSessionScopedStateForPhysicalOn();", defer);
        assertTrue(stop >= 0);
        assertTrue(boundary > stop);
        assertTrue(defer > boundary);
        assertTrue(advance > defer);
    }

    @Test
    public void synchronousPriorCloseOpensReplacementGenerationOnlyOnce() throws Exception {
        FailedCloseManager manager = new FailedCloseManager();
        ChargingConfig enabled = new ChargingConfig();
        enabled.setEnabled(true);
        setObject(manager, "config", enabled);
        setObject(manager, "socDb", SocHistoryDatabase.getInstance());
        DaemonLogger.Config previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        try {
            manager.onFusedChargingChanged(true, "session-a");
            assertEquals(1, manager.openAttempts);

            invoke(manager, "prepareClosedSessionCleanup");
            setBoolean(manager, "charging", false);
            manager.onFusedChargingChanged(true, "session-b");

            assertEquals(1, manager.closeAttempts);
            assertEquals(2, manager.openAttempts);
        } finally {
            invoke(manager, "stopSampling");
            invoke(manager, "shutdownSamplerExecutor");
            ChargeRateResolver.onSessionEnded();
            DaemonLogger.configure(previousLogConfig);
        }
    }

    @Test
    public void authoritativeStopKeepsBufferedReplacementBehindFailedClose() throws Exception {
        FailedCloseManager manager = new FailedCloseManager();
        ChargingConfig enabled = new ChargingConfig();
        enabled.setEnabled(true);
        setObject(manager, "config", enabled);
        DaemonLogger.Config previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        try {
            manager.onFusedChargingChanged(true, "session-a");
            invoke(manager, "prepareClosedSessionCleanup");
            setBoolean(manager, "charging", false);
            manager.failClose = true;
            manager.onFusedChargingChanged(true, "session-b");
            assertTrue(getBoolean(manager, "startAfterPendingClose"));

            setObject(manager, "pendingDatabaseEdge", Boolean.FALSE);
            manager.onAuthoritativeChargingStop("session-b-unplug");

            assertTrue(getBoolean(manager, "startAfterPendingClose"));
        } finally {
            invoke(manager, "stopSampling");
            invoke(manager, "shutdownSamplerExecutor");
            ChargeRateResolver.onSessionEnded();
            DaemonLogger.configure(previousLogConfig);
        }
    }

    @Test
    public void successfulPriorCloseRetryPreservesReplacementFinalCounterDrain() throws Exception {
        FailedCloseManager manager = new FailedCloseManager();
        ChargingConfig enabled = new ChargingConfig();
        enabled.setEnabled(true);
        setObject(manager, "config", enabled);
        DaemonLogger.Config previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        try {
            setObject(manager, "socDb", SocHistoryDatabase.getInstance());
            setObject(manager, "sampler", new NonExecutingScheduler());
            manager.onFusedChargingChanged(true, "session-a");
            assertEquals(1, manager.openAttempts);

            // A's row cannot close, so B is buffered behind the pending close.
            invoke(manager, "prepareClosedSessionCleanup");
            setBoolean(manager, "charging", false);
            manager.failClose = true;
            manager.onFusedChargingChanged(true, "session-b");
            assertEquals(1, manager.closeAttempts);
            assertTrue(getBoolean(manager, "startAfterPendingClose"));

            // B ends and starts its bounded final-counter drain before A's close retry succeeds.
            manager.onFusedChargingChanged(false, "session-b-finished");
            assertTrue(getLong(manager, "deferredStopDeadlineElapsedMs") > 0L);
            assertTrue((Boolean) invoke(manager, "hasDeferredStop"));

            setObject(manager, "pendingDatabaseEdge", Boolean.FALSE);
            int retryGeneration = getInt(manager, "databaseEdgeRetryGeneration");
            manager.failClose = false;
            invoke(manager, "retryDatabaseEdge",
                    new Class<?>[] { int.class, boolean.class },
                    retryGeneration, false);

            // The successful retry closes A and materializes B, but B must remain open until its
            // already-established drain expires (or the authoritative stop refreshes that drain).
            assertEquals(2, manager.closeAttempts);
            assertEquals(2, manager.openAttempts);
            assertFalse(getBoolean(manager, "startAfterPendingClose"));
            assertTrue(getLong(manager, "deferredStopDeadlineElapsedMs") > 0L);
            assertTrue((Boolean) invoke(manager, "hasDeferredStop"));
        } finally {
            invoke(manager, "stopSampling");
            invoke(manager, "shutdownSamplerExecutor");
            ChargeRateResolver.onSessionEnded();
            DaemonLogger.configure(previousLogConfig);
        }
    }

    @Test
    public void shutdownFlushRetriesPriorCloseWithoutDiscardingBufferedReplacement()
            throws Exception {
        FailedCloseManager manager = new FailedCloseManager();
        ChargingConfig enabled = new ChargingConfig();
        enabled.setEnabled(true);
        setObject(manager, "config", enabled);
        setObject(manager, "socDb", SocHistoryDatabase.getInstance());
        setObject(manager, "sampler", new NonExecutingScheduler());
        DaemonLogger.Config previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        try {
            manager.onFusedChargingChanged(true, "session-a");
            invoke(manager, "prepareClosedSessionCleanup");
            setBoolean(manager, "charging", false);
            manager.failClose = true;
            manager.onFusedChargingChanged(true, "session-b");
            assertTrue(getBoolean(manager, "startAfterPendingClose"));
            assertEquals(1, manager.closeAttempts);

            manager.failClose = false;
            setBoolean(manager, "shuttingDown", true);
            invoke(manager, "flushPendingDatabaseLifecycleForShutdown");

            assertFalse(getBoolean(manager, "startAfterPendingClose"));
            assertTrue(manager.closeAttempts >= 2);
            assertTrue(manager.openAttempts >= 2);
        } finally {
            invoke(manager, "stopSampling");
            invoke(manager, "shutdownSamplerExecutor");
            ChargeRateResolver.onSessionEnded();
            DaemonLogger.configure(previousLogConfig);
        }
    }

    @Test
    public void reenableAfterPendingCloseOpensReplacementOnlyOnce() throws Exception {
        FailedCloseManager manager = new FailedCloseManager();
        setObject(manager, "config", new EnablingConfig());
        setObject(manager, "socDb", SocHistoryDatabase.getInstance());
        setObject(manager, "pendingDatabaseEdge", Boolean.FALSE);
        setBoolean(manager, "charging", true);
        DaemonLogger.Config previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        try {
            manager.onConfigChanged();

            assertEquals(1, manager.closeAttempts);
            assertEquals(1, manager.openAttempts);
        } finally {
            invoke(manager, "stopSampling");
            invoke(manager, "shutdownSamplerExecutor");
            ChargeRateResolver.onSessionEnded();
            DaemonLogger.configure(previousLogConfig);
        }
    }

    @Test
    public void restoreReloadsChargingRuntimeOnlyAfterConfigCommit()
            throws IOException {
        String backup = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/ConfigBackupService.kt");
        int lock = backup.indexOf(
                "UnifiedConfigManager.runUnderConfigLock {");
        int lockResult = backup.indexOf(
                "if (!transaction.success)", lock);
        int callback = backup.indexOf(
                "chargingManager?.onConfigRestored()", lockResult);
        int warning = backup.indexOf(
                "Settings were restored, but charging analytics could not",
                callback);

        assertTrue(lock >= 0);
        assertTrue(lockResult > lock);
        assertTrue(callback > lockResult);
        assertTrue(warning > callback);
        assertFalse(backup.substring(lock, lockResult)
                .contains("onConfigRestored()"));
    }

    @Test
    public void runtimeReloadRejectsFailedConfigReadAndReportsTariffRead()
            throws IOException {
        String reload = sourceBetween(
                "private boolean reloadRuntimeConfig()",
                "// ==================== ACCESSORS");
        int configLoad = reload.indexOf("if (!config.load())");
        int failedReturn = reload.indexOf("return false;", configLoad);
        int latch = reload.indexOf(
                "latchDesiredAnalyticsStateLocked(enabled)");
        int tariffLoad = reload.indexOf(
                "TariffManager.getInstance().load()", latch);
        int result = reload.lastIndexOf(
                "return tariffsReloaded;");

        assertTrue(configLoad >= 0);
        assertTrue(failedReturn > configLoad);
        assertTrue(latch > failedReturn);
        assertTrue(tariffLoad > latch);
        assertTrue(result > tariffLoad);
        assertFalse(reload.contains("flushPendingUsage()"));
        assertTrue(managerSource().contains(
                "public synchronized boolean onConfigRestored()"));
    }

    @Test
    public void obsoleteBoundaryCannotBlockReplacementSession() throws IOException {
        String retry = sourceBetween(
                "private boolean retryPendingBoundary()",
                "/** Release everything scoped");
        String clear = sourceBetween(
                "private void clearPendingBoundaryLocked()",
                "private boolean retryPendingBoundary()");

        assertTrue(retry.contains("openSessionStart != pendingBoundarySessionStart"));
        assertTrue(retry.contains("clearPendingBoundaryLocked()"));
        assertTrue(retry.indexOf("openSessionStart != pendingBoundarySessionStart")
                < retry.indexOf("persistBoundaryLocked("));
        assertTrue(clear.contains("pendingBoundarySessionStart = -1L"));
        assertTrue(clear.contains("pendingBoundaryAtMs = 0L"));
        assertTrue(clear.contains(
                "pendingBoundaryPowerKw = SocHistoryDatabase.STOP_BOUNDARY_POWER_KW"));
        assertTrue(clear.contains("pendingBoundarySoc = Double.NaN"));
        assertTrue(clear.contains("pendingBoundaryTemp = -999"));
        assertTrue(clear.contains("pendingBoundaryTempHigh = -999"));
        assertTrue(clear.contains("pendingBoundaryTempLow = -999"));
    }

    @Test
    public void targetlessPhysicalOffCallsAndVerifiesFullDatabaseDrain()
            throws IOException {
        String apply = sourceBetween(
                "boolean applyDatabaseEdgeAndVerify",
                "static boolean databaseEdgeReachedDesiredState");

        assertTrue(apply.contains(
                "socDb.onChargingEdge(\n"
                        + "                            false, Math.max(0L, expectedCloseStart)"));
        assertTrue(apply.contains("rowOpen = socDb.hasOpenChargingSessionRow()"));
        assertTrue(apply.contains(
                "deferredDrained = !socDb.hasDeferredPhysicalGenerations()"));
        assertTrue(apply.contains(
                "exactCloseSatisfied = !rowOpen && deferredDrained"));
    }

    @Test
    public void physicalTruthIsPushedBeforeDuplicateEdgeAndAuthoritativeDrain()
            throws IOException {
        String fused = sourceBetween(
                "public synchronized void onFusedChargingChanged",
                "private void beginChargingSession");
        String authoritative = sourceBetween(
                "public synchronized void onAuthoritativeChargingStop",
                "private boolean isPendingDatabaseEdge");

        int push = fused.indexOf("socDb.setPhysicalChargingNow(isCharging)");
        int duplicate = fused.indexOf("isCharging == this.charging");
        assertTrue(push >= 0);
        assertTrue(duplicate > push);
        assertTrue(authoritative.indexOf("socDb.setPhysicalChargingNow(false)")
                > authoritative.indexOf("charging = false;"));
        assertTrue(authoritative.indexOf("socDb.setPhysicalChargingNow(false)")
                < authoritative.indexOf("deferStoppedSession(FINAL_COUNTER_GRACE_MS)"));
    }

    @Test
    public void analyticsDesiredStateIsConfirmedRetriedAndFencesOpenAndSampling()
            throws IOException {
        String source = managerSource();
        String apply = sourceBetween(
                "boolean applyDesiredAnalyticsStateLocked",
                "private void scheduleAnalyticsStateRetryLocked");
        String retry = sourceBetween(
                "private void scheduleAnalyticsStateRetryLocked",
                "private void cancelAnalyticsStateRetryLocked");
        String begin = sourceBetween(
                "private void beginChargingSession",
                "private void activateSessionScopedState");
        String sampler = sourceBetween(
                "private synchronized void startSampling",
                "private synchronized void stopSampling");

        assertTrue(source.contains("desiredAnalyticsEnabled"));
        assertTrue(source.contains("analyticsStateConfirmed"));
        assertTrue(apply.contains(
                "applied = socDb.setChargingAnalyticsEnabled(desiredAnalyticsEnabled)"));
        assertTrue(apply.contains("analyticsStateConfirmed = false"));
        assertTrue(apply.contains("scheduleAnalyticsStateRetryLocked()"));
        assertTrue(retry.contains("EDGE_RETRY_MAX_MS"));
        assertTrue(retry.contains("generation != analyticsStateRetryGeneration"));
        assertTrue(retry.contains("config.isEnabled() != desired"));
        assertTrue(source.contains("cancelAnalyticsStateRetryLocked();"));
        assertTrue(begin.contains("!isAnalyticsRecordingReady()"));
        assertTrue(sampler.contains("!isAnalyticsRecordingReady()"));
    }

    @Test
    public void restoredPendingCloseIsDrainedBeforeStartupOnCanOpen()
            throws IOException {
        String edge = sourceBetween(
                "public synchronized void onFusedChargingChanged",
                "private void beginChargingSession");

        int restored = edge.indexOf("socDb.hasPendingChargingCloseBoundary()");
        int queue = edge.indexOf("socDb.deferPhysicalChargingStart()", restored);
        int close = edge.indexOf("finishStoppedSession()", queue);
        assertTrue(restored >= 0);
        assertTrue(queue > restored);
        assertTrue(close > queue);
    }

    @Test
    public void authoritativeStopRequestsDurableResumeBarrier()
            throws IOException {
        String authoritative = sourceBetween(
                "public synchronized void onAuthoritativeChargingStop",
                "private boolean isPendingDatabaseEdge");

        assertTrue(authoritative.contains(
                "socDb.deferPhysicalChargingStop(true)"));
        assertTrue(authoritative.contains(
                "socDb.capturePendingChargingClose(true)"));
    }

    @Test
    public void firstStartOutageSamplesTheJournaledDeferredGeneration()
            throws IOException {
        String begin = sourceBetween(
                "private void beginChargingSession",
                "private void activateSessionScopedState");
        String sample = sourceBetween(
                "private void sampleOnce(int generation)",
                "// ==================== CONFIG");
        String boundary = sourceBetween(
                "private void writeGapBoundary()",
                "private boolean persistBoundaryLocked");

        assertTrue(begin.contains(
                "if (socDb.hasDeferredPhysicalGenerations()) startSampling(true)"));
        assertTrue(sample.contains(
                "boolean deferredTarget = socDb.hasDeferredPhysicalGenerations()"));
        assertFalse(sample.contains(
                "startAfterPendingClose && socDb.hasDeferredPhysicalGenerations()"));
        assertTrue(boundary.contains(
                "if (socDb.hasDeferredPhysicalGenerations())"));
    }

    private static String managerSource() throws IOException {
        return readRepositoryFile(
                "app/src/main/java/com/overdrive/app/charging/ChargingSessionManager.java");
    }

    private static String sourceBetween(String startMarker, String endMarker) throws IOException {
        String source = managerSource();
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0) throw new AssertionError("Could not locate source markers");
        return source.substring(start, end);
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = ChargingSessionManager.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes,
                                 Object... arguments) throws Exception {
        Method method = ChargingSessionManager.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = ChargingSessionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static boolean getBoolean(Object target, String fieldName) throws Exception {
        Field field = ChargingSessionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static int getInt(Object target, String fieldName) throws Exception {
        Field field = ChargingSessionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static long getLong(Object target, String fieldName) throws Exception {
        Field field = ChargingSessionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static void setObject(Object target, String fieldName, Object value) throws Exception {
        Field field = ChargingSessionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FailedCloseManager extends ChargingSessionManager {
        boolean failClose;
        int closeAttempts;
        int openAttempts;
        int retryRequests;

        @Override
        boolean applyDatabaseEdgeAndVerify(boolean desiredOpen, String operation) {
            if (desiredOpen) {
                openAttempts++;
                return true;
            }
            closeAttempts++;
            return !failClose;
        }

        @Override
        synchronized void scheduleDatabaseEdgeRetryLocked(boolean desiredOpen) {
            assertFalse(desiredOpen);
            retryRequests++;
        }

        @Override
        boolean isAnalyticsRecordingReady() {
            ChargingConfig current = getConfig();
            return current != null && current.isEnabled();
        }

        @Override
        boolean applyDesiredAnalyticsStateLocked(String source) {
            return true;
        }
    }

    private static final class EnablingConfig extends ChargingConfig {
        EnablingConfig() {
            setEnabled(false);
        }

        @Override
        public boolean load() {
            setEnabled(true);
            return true;
        }
    }

    private static final class NonExecutingScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            // Deliberately do not execute: lifecycle callbacks are driven synchronously by the test.
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return new PendingScheduledFuture<>(null);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return new PendingScheduledFuture<>(null);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            return new PendingScheduledFuture<>(null);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            return new PendingScheduledFuture<>(null);
        }
    }

    private static final class PendingScheduledFuture<V> implements ScheduledFuture<V> {
        private final V value;
        private boolean cancelled;

        PendingScheduledFuture(V value) {
            this.value = value;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS),
                    other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public V get() {
            return value;
        }

        @Override
        public V get(long timeout, TimeUnit unit) {
            return value;
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
