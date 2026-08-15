package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.ChargeSourceClassifier;
import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ChargeRateResolverTransactionTest {

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void openFreshSession() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        ChargeRateResolver.discardEvidenceTransaction();
        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();
    }

    @After
    public void closeSession() {
        ChargeRateResolver.discardEvidenceTransaction();
        ChargeRateResolver.onSessionEnded();
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void discardedReadCannotLatchDivisorOrProof() {
        String source = "discarded-" + System.nanoTime();

        ChargeRateResolver.beginEvidenceTransaction();
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 0.0);
        assertTrue(ChargeRateResolver.isScaleVerified(source, 6.5));
        ChargeRateResolver.discardEvidenceTransaction();

        assertTrue(Double.isNaN(ChargeRateResolver.rateKw(source, 650.0)));
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(source, 6.5));
    }

    @Test
    public void validatedReadPublishesStagedEvidence() {
        String source = "committed-" + System.nanoTime();

        ChargeRateResolver.beginEvidenceTransaction();
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 0.0);
        assertTrue(ChargeRateResolver.commitEvidenceTransaction());

        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0), 0.0);
        assertTrue(ChargeRateResolver.isSessionRateCorroborated(source, 6.5));
    }

    @Test
    public void sessionBoundaryRejectsPendingEvidence() {
        String source = "boundary-" + System.nanoTime();

        ChargeRateResolver.beginEvidenceTransaction();
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 0.0);
        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.onSessionStarted();

        assertFalse(ChargeRateResolver.commitEvidenceTransaction());
        assertTrue(Double.isNaN(ChargeRateResolver.rateKw(source, 650.0)));
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(source, 6.5));
    }

    @Test
    public void concurrentEvidenceCommitForcesRetry() throws Exception {
        String pendingSource = "pending-" + System.nanoTime();
        String concurrentSource = "concurrent-" + System.nanoTime();

        ChargeRateResolver.beginEvidenceTransaction();
        assertEquals(6.5,
                ChargeRateResolver.rateKw(pendingSource, 650.0, 6.5), 0.0);

        Thread concurrent = new Thread(() ->
                ChargeRateResolver.rateKw(concurrentSource, 320.0, 3.2));
        concurrent.start();
        concurrent.join(2_000L);
        assertFalse(concurrent.isAlive());

        assertFalse(ChargeRateResolver.commitEvidenceTransaction());
        assertTrue(Double.isNaN(
                ChargeRateResolver.rateKw(pendingSource, 650.0)));
        assertEquals(3.2,
                ChargeRateResolver.rateKw(concurrentSource, 320.0), 0.0);
    }

    @Test
    public void closedSessionObservationCannotSeedNextSessionSlope() {
        long start = 1_000_000L;
        ChargeRateResolver.onSessionEnded();
        ChargeRateResolver.observe(
                ChargeSourceClassifier.SRC_CAPACITY, 0.0, start);

        ChargeRateResolver.onSessionStarted();
        ChargeRateResolver.observe(
                ChargeSourceClassifier.SRC_CAPACITY, 0.02, start + 20_000L);

        assertTrue(Double.isNaN(ChargeRateResolver.rateKw(
                ChargeSourceClassifier.SRC_CAPACITY, 0.02)));
    }

    @Test
    public void identicalConcurrentReadersDoNotInvalidateEachOther() throws Exception {
        String source = "stable-readers-" + System.nanoTime();
        ChargeRateResolver.beginEvidenceTransaction();
        assertEquals(6.5, ChargeRateResolver.rateKw(source, 650.0, 6.5), 0.0);
        assertTrue(ChargeRateResolver.commitEvidenceTransaction());

        CountDownLatch evaluated = new CountDownLatch(2);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicBoolean firstCommitted = new AtomicBoolean();
        AtomicBoolean secondCommitted = new AtomicBoolean();
        Thread first = reader(source, evaluated, commit, firstCommitted);
        Thread second = reader(source, evaluated, commit, secondCommitted);
        first.start();
        second.start();

        assertTrue(evaluated.await(2, TimeUnit.SECONDS));
        commit.countDown();
        first.join(2_000L);
        second.join(2_000L);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertTrue(firstCommitted.get());
        assertTrue(secondCommitted.get());
    }

    @Test
    public void rejectedComponentRetryCannotLeakItsCalibration() {
        ChargingDetector detector = new ChargingDetector(30L, 30L, 40L);
        String discardedSource = "rejected-build-" + System.nanoTime();
        AtomicInteger builds = new AtomicInteger();

        String result = VehicleDataMonitor.readStableChargingComponent(detector, () -> {
            int build = builds.incrementAndGet();
            if (build == 1) {
                assertEquals(6.5,
                        ChargeRateResolver.rateKw(
                                discardedSource, 650.0, 6.5), 0.0);
                try (ChargingDetector.PublicationMutation ignored =
                             ChargingDetector.beginPublicationMutation()) {
                    // Invalidates this build after it staged calibration evidence.
                }
            }
            return "build-" + build;
        });

        assertEquals("build-2", result);
        assertTrue(Double.isNaN(
                ChargeRateResolver.rateKw(discardedSource, 650.0)));
        assertFalse(ChargeRateResolver.isSessionRateCorroborated(
                discardedSource, 6.5));
    }

    private static Thread reader(String source,
                                 CountDownLatch evaluated,
                                 CountDownLatch commit,
                                 AtomicBoolean committed) {
        return new Thread(() -> {
            ChargeRateResolver.beginEvidenceTransaction();
            assertEquals(6.5,
                    ChargeRateResolver.rateKw(source, 650.0, 6.5), 0.0);
            evaluated.countDown();
            try {
                assertTrue(commit.await(2, TimeUnit.SECONDS));
                committed.set(ChargeRateResolver.commitEvidenceTransaction());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                ChargeRateResolver.discardEvidenceTransaction();
            }
        });
    }
}
