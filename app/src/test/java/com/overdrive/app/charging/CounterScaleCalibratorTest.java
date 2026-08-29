package com.overdrive.app.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Pins the behavioural unit-calibration contract.
 *
 * <p>The numbers in {@link #decidesHalfScaleFromTheCapturedFieldSeries} are the real ones from device
 * log {@code AL37RNJ9}: the charged-energy counter advanced 10.675 kWh while remaining pack energy
 * advanced 20.200 kWh over 1.900 h on a BEV, with two rate accessors independently reading ~10.05 kW
 * against the counter's 5.62 kW slope.
 */
public class CounterScaleCalibratorTest {

    /**
     * A PRIVATE source key, deliberately not {@code SRC_CAPACITY}.
     *
     * <p>Verdicts are held in process-wide static state, and Gradle shares one JVM across test
     * classes. Calibrating the real capacity key here leaked a factor of 2 into every other suite that
     * feeds {@code observeCounterForScale(SRC_CAPACITY, ...)} — which doubled their yardstick and
     * failed four unrelated power tests. Keep the key private and reset around each test.
     */
    private static final String SRC = "testCalibratorCounter";
    private static final long MINUTE = 60_000L;

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @Before
    public void resetState() {
        CounterScaleCalibrator.resetForTests();
    }

    @After
    public void clearLeakedVerdicts() {
        CounterScaleCalibrator.resetForTests();
    }

    /** Feed a steady charge where the reference advances {@code factor} times as fast as the counter. */
    private static void feed(String source, double counterRateKwhPerH, double factor,
                             int minutes, long startAtMs) {
        double counter = 10.0;
        double reference = 40.0;
        for (int m = 0; m <= minutes; m++) {
            CounterScaleCalibrator.observePaired(source, counter, reference, startAtMs + m * MINUTE);
            counter += counterRateKwhPerH / 60.0;
            reference += counterRateKwhPerH * factor / 60.0;
        }
    }

    @Test
    public void decidesHalfScaleFromTheCapturedFieldSeries() {
        // 5.62 kW counter slope against a 10.63 kW independent slope — a ratio of 1.892.
        feed(SRC, 5.62, 10.63 / 5.62, 30, 1_000_000L);

        assertTrue(CounterScaleCalibrator.isCalibrated(SRC));
        assertEquals(2.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
    }

    @Test
    public void healthyCounterCalibratesToUnity() {
        feed(SRC, 10.6, 1.0, 30, 1_000_000L);

        assertTrue(CounterScaleCalibrator.isCalibrated(SRC));
        assertEquals(1.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
    }

    @Test
    public void laterSessionCanChallengeAStoredUnityVerdict() {
        feed(SRC, 10.6, 1.0, 30, 1_000_000L);
        assertEquals(1.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
        CounterScaleCalibrator.onSessionEnded();

        feed(SRC, 5.62, 2.0, 8, 4_000_000L);

        assertEquals("the correction is not final before the long baseline",
                1.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
        assertTrue("clean current-session 1:2 evidence must override stale unity confidence",
                CounterScaleCalibrator.isScaleSuspect(SRC));
    }

    @Test
    public void laterSessionCanRecalibrateUnityToHalfScale() {
        feed(SRC, 10.6, 1.0, 30, 1_000_000L);
        CounterScaleCalibrator.onSessionEnded();

        feed(SRC, 5.62, 2.0, 30, 4_000_000L);

        assertEquals(2.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
    }

    /**
     * An uncalibrated source must behave exactly as before — factor 1.0 and no suspicion — or every
     * trim with no fault would have its rate withheld.
     */
    @Test
    public void untouchedSourceIsNeutral() {
        assertFalse(CounterScaleCalibrator.isCalibrated(SRC));
        assertEquals(1.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
    }

    /**
     * The registers are quantised differently (~0.1 kWh against ~0.2 kWh in the field), so whichever
     * steps first makes the opening ratio wildly wrong on hardware with no fault at all. Suspicion
     * withholds the published rate, so a floor that admits this would blank the power card for the
     * first minutes of every charge.
     */
    @Test
    public void earlyQuantisationSkewDoesNotRaiseSuspicion() {
        long t = 1_000_000L;
        // Reference ticks a 0.2 kWh quantum while the counter has moved a single 0.1 kWh step.
        CounterScaleCalibrator.observePaired(SRC, 10.0, 40.0, t);
        CounterScaleCalibrator.observePaired(SRC, 10.1, 40.2, t + MINUTE);
        CounterScaleCalibrator.observePaired(SRC, 10.1, 40.4, t + 2 * MINUTE);

        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
        assertFalse(CounterScaleCalibrator.isCalibrated(SRC));
    }

    /** A genuine fault must be suspected well before it is proven, so nothing gets priced meanwhile. */
    @Test
    public void suspicionPrecedesTheVerdict() {
        feed(SRC, 5.62, 1.892, 8, 1_000_000L);

        assertTrue("8 min of a 2x gap should already be suspicious",
                CounterScaleCalibrator.isScaleSuspect(SRC));
        assertFalse("but 8 min is short of the 20 min baseline required to decide",
                CounterScaleCalibrator.isCalibrated(SRC));
    }

    /**
     * A ratio between two candidate widths identifies nothing. Refusing keeps the caller on a flagged
     * estimate, which is recoverable; assigning the nearer candidate would be a priced guess.
     */
    @Test
    public void ambiguousRatioIsRefusedRatherThanSnapped() {
        feed(SRC, 5.0, 1.45, 40, 1_000_000L);

        assertFalse(CounterScaleCalibrator.isCalibrated(SRC));
        assertEquals(1.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
    }

    @Test
    public void weakUnityEvidenceIsNotPersistedAsAFullScaleVerdict() {
        feed(SRC, 5.0, 1.229, 40, 1_000_000L);

        assertFalse(CounterScaleCalibrator.isCalibrated(SRC));
        assertEquals("uncalibrated behavior remains neutral",
                1.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
    }

    /** A verdict is a firmware property and survives the session; the pairing anchors must not. */
    @Test
    public void sessionEndKeepsTheVerdictAndDropsTheAnchors() {
        feed(SRC, 5.62, 1.892, 30, 1_000_000L);
        assertTrue(CounterScaleCalibrator.isCalibrated(SRC));

        CounterScaleCalibrator.onSessionEnded();

        assertEquals("the decided factor is firmware-scoped",
                2.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
        // A stale anchor would pair this session's last reading against the next session's first.
        CounterScaleCalibrator.observePaired(SRC, 0.0, 5.0, 9_000_000L);
        assertEquals(2.0, CounterScaleCalibrator.factorFor(SRC), 0.0001);
    }

    @Test
    public void sessionEndDropsUndecidedPartialEvidence() {
        feed(SRC, 5.62, 2.0, 8, 1_000_000L);
        assertTrue(CounterScaleCalibrator.isScaleSuspect(SRC));
        assertFalse(CounterScaleCalibrator.isCalibrated(SRC));

        CounterScaleCalibrator.onSessionEnded();

        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
        feed(SRC, 10.6, 1.0, 12, 3_000_000L);
        assertFalse("the next short healthy session must not inherit the prior 1:2 baseline",
                CounterScaleCalibrator.isCalibrated(SRC));
        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
    }

    /**
     * A counter reset (or a wrap) steps a series backwards. Pairing across one would corrupt the ratio
     * in an unbounded direction, so it must re-anchor instead.
     */
    @Test
    public void backwardsStepReanchorsInsteadOfCorruptingTheRatio() {
        long t = 1_000_000L;
        feed(SRC, 5.62, 1.892, 5, t);
        // Counter resets to zero mid-charge while the reference keeps climbing.
        CounterScaleCalibrator.observePaired(SRC, 0.0, 60.0, t + 6 * MINUTE);
        CounterScaleCalibrator.observePaired(SRC, 0.1, 60.2, t + 7 * MINUTE);

        // Whatever was accumulated stays valid; the discontinuity itself contributed nothing.
        assertFalse(CounterScaleCalibrator.isCalibrated(SRC));
    }

    /** An observation gap longer than the cap means the two series stopped describing one interval. */
    @Test
    public void longGapReanchors() {
        long t = 1_000_000L;
        CounterScaleCalibrator.observePaired(SRC, 10.0, 40.0, t);
        // 40 minutes unobserved: both registers advanced by an unknown amount.
        CounterScaleCalibrator.observePaired(SRC, 14.0, 48.0, t + 40 * MINUTE);

        assertFalse(CounterScaleCalibrator.isScaleSuspect(SRC));
        assertFalse(CounterScaleCalibrator.isCalibrated(SRC));
    }
}
