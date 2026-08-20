package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ChargeSourceClassifierSteadyRateTest {

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void disableAndroidAndFileLogging() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void restoreLogging() {
        ChargeSourceClassifier.onSessionEnded();
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void riseThenPlateauRemainsUnknownWithoutCorroboration() {
        String source = "testFrozenCounter-" + System.nanoTime();
        long start = 1_000L;

        ChargeSourceClassifier.observeWhileCharging(source, 1.0, start);
        ChargeSourceClassifier.observeWhileCharging(source, 1.1, start + 1_000L);
        for (int i = 0; i < 6; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    source, 1.1, start + 2_000L + i * 120_000L);
        }

        assertEquals(ChargeSourceClassifier.Kind.UNKNOWN,
                ChargeSourceClassifier.kindOf(source));
    }

    @Test
    public void levelOnlySourceNeedsFreshPlateauCorroboration() {
        String source = "testSteadyRate-" + System.nanoTime();
        long start = 1_000L;

        ChargeSourceClassifier.observeWhileCharging(source, 6.5, start);
        for (int i = 0; i < 6; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    source, 6.5, start + 1_000L + i * 120_000L);
        }
        long observedAt = start + 1_000L + 5 * 120_000L;

        assertEquals(ChargeSourceClassifier.Kind.UNKNOWN,
                ChargeSourceClassifier.kindOf(source));
        assertTrue(ChargeSourceClassifier.isSteadyRateCandidate(
                source, 6.5, observedAt));
        assertTrue(ChargeSourceClassifier.classifySteadyRateWithCorroboration(
                source, 6.5, observedAt, observedAt));
        assertEquals(ChargeSourceClassifier.Kind.RATE,
                ChargeSourceClassifier.kindOf(source));
    }

    @Test
    public void currentSessionRiseThenPlateauClassifiesFromPostPlateauMovement() {
        String source = "testRampThenSteadyRate-" + System.nanoTime();
        long start = 1_000L;

        ChargeSourceClassifier.observeWhileCharging(source, 2.7, start);
        ChargeSourceClassifier.observeWhileCharging(source, 3.2, start + 1_000L);
        for (int i = 0; i < 6; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    source, 3.2, start + 2_000L + i * 120_000L);
        }
        long observedAt = start + 2_000L + 5 * 120_000L;

        assertTrue(ChargeSourceClassifier.isSteadyRateCandidate(
                source, 3.2, observedAt));
        assertFalse(ChargeSourceClassifier.classifySteadyRateWithCorroboration(
                source, 3.2, observedAt, start + 1_500L));
        assertEquals(ChargeSourceClassifier.Kind.UNKNOWN,
                ChargeSourceClassifier.kindOf(source));

        assertTrue(ChargeSourceClassifier.classifySteadyRateWithCorroboration(
                source, 3.2, observedAt, observedAt));
        assertEquals(ChargeSourceClassifier.Kind.RATE,
                ChargeSourceClassifier.kindOf(source));
    }

    @Test
    public void sessionEndInvalidatesCompletedPlateau() {
        String source = "testEndedPlateau-" + System.nanoTime();
        long start = 1_000L;
        ChargeSourceClassifier.observeWhileCharging(source, 10.0, start);
        for (int i = 0; i < 6; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    source, 10.0, start + 1_000L + i * 120_000L);
        }
        long observedAt = start + 1_000L + 5 * 120_000L;
        assertTrue(ChargeSourceClassifier.isSteadyRateCandidate(
                source, 10.0, observedAt));

        ChargeSourceClassifier.onSessionEnded();

        assertFalse(ChargeSourceClassifier.classifySteadyRateWithCorroboration(
                source, 10.0, observedAt, observedAt));
        assertEquals(ChargeSourceClassifier.Kind.UNKNOWN,
                ChargeSourceClassifier.kindOf(source));
    }

    @Test
    public void counterFallsMustRepeatWithinOneSessionBeforeDemotion() {
        String source = "testSessionFalls-" + System.nanoTime();
        long start = 1_000L;

        // Nine observations over 24 minutes establish a dense, monotonic COUNTER run.
        for (int i = 0; i <= 8; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    source, i, start + i * 3 * 60_000L);
        }
        assertEquals(ChargeSourceClassifier.Kind.COUNTER,
                ChargeSourceClassifier.kindOf(source));

        // One non-reset fall is allowed as HAL noise.
        ChargeSourceClassifier.observeWhileCharging(
                source, 7.8, start + 25 * 60_000L);
        assertEquals(ChargeSourceClassifier.Kind.COUNTER,
                ChargeSourceClassifier.kindOf(source));

        ChargeSourceClassifier.onSessionEnded();

        // A second isolated fall in another physical charge must not combine with the first.
        long next = start + 60 * 60_000L;
        ChargeSourceClassifier.observeWhileCharging(source, 0.0, next);
        ChargeSourceClassifier.observeWhileCharging(source, 1.0, next + 60_000L);
        ChargeSourceClassifier.observeWhileCharging(source, 0.8, next + 2 * 60_000L);
        assertEquals(ChargeSourceClassifier.Kind.COUNTER,
                ChargeSourceClassifier.kindOf(source));

        // A second fall in this same session is genuine contrary behavior and still demotes.
        ChargeSourceClassifier.observeWhileCharging(source, 0.7, next + 3 * 60_000L);
        assertEquals(ChargeSourceClassifier.Kind.RATE,
                ChargeSourceClassifier.kindOf(source));
    }
}
