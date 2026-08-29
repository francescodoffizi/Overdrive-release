package com.overdrive.app.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.BeforeClass;
import org.junit.Test;

public class ChargeCounterAccumulatorTest {

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @Test
    public void resetCreditsFirstObservedPostResetValue() {
        ChargeCounterAccumulator accumulator = new ChargeCounterAccumulator();
        accumulator.observe(0.0, 1_000L);
        accumulator.observe(2.0, 3_601_000L);

        accumulator.observe(0.4, 3_661_000L);

        assertEquals(1, accumulator.resetCount());
        assertEquals(2.4, accumulator.energyKwh(), 0.0001);

        accumulator.observe(1.0, 4_261_000L);
        assertEquals(3.0, accumulator.energyKwh(), 0.0001);
    }

    @Test
    public void renewedRiseClearsFalseSaturation() {
        ChargeCounterAccumulator accumulator = new ChargeCounterAccumulator();
        double nearCeiling = ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH - 0.4;
        accumulator.observe(nearCeiling, 1_000L);
        accumulator.observe(nearCeiling, 2_000L);
        accumulator.observe(nearCeiling, 3_000L);
        accumulator.observe(nearCeiling, 4_000L);
        assertTrue(accumulator.isSaturated());

        accumulator.observe(nearCeiling + 0.1, 5_000L);

        assertFalse(accumulator.isSaturated());
        assertFalse(accumulator.isIncomplete());
    }

    @Test
    public void reconstructedContinuationIsExplicitlyMarked() {
        ChargeCounterAccumulator accumulator = new ChargeCounterAccumulator();
        accumulator.observe(10.0, 1_000L);
        accumulator.observe(12.0, 2_000L);

        accumulator.markReconstructedGap();

        assertTrue(accumulator.containsReconstructedGap());
    }

    @Test
    public void restoredExactZeroDeltaClearsGapWithoutBecomingIncomplete() {
        ChargeCounterAccumulator accumulator = new ChargeCounterAccumulator();
        accumulator.restore(12.5, 12.5, 0.0, false, Double.NaN);

        assertTrue(accumulator.isIncomplete());
        accumulator.observe(12.5, 5_000L);

        assertEquals(0.0, accumulator.energyKwh(), 0.0);
        assertFalse(accumulator.isIncomplete());
        assertFalse(accumulator.containsReconstructedGap());
    }

    @Test
    public void totalSessionEstimateCannotOverwriteRestartGapEstimate() {
        ChargeCounterAccumulator accumulator = new ChargeCounterAccumulator();
        accumulator.restore(0.0, 10.0, 4.0, false, 2.0);

        accumulator.setIndependentEstimate(67.0);
        accumulator.observe(12.0, 10_000L);

        assertEquals(6.0, accumulator.energyKwh(), 0.0001);
        assertTrue(accumulator.containsReconstructedGap());
        assertFalse(accumulator.isIncomplete());
    }

    @Test
    public void exactStateCopyDoesNotInventAProcessGap() {
        ChargeCounterAccumulator source = new ChargeCounterAccumulator();
        source.observe(3.0, 1_000L);
        source.observe(4.25, 2_000L);

        ChargeCounterAccumulator copy = new ChargeCounterAccumulator();
        copy.restoreState(source.snapshotState());

        assertEquals(1.25, copy.energyKwh(), 0.0001);
        assertFalse(copy.isIncomplete());
        copy.observe(4.5, 3_000L);
        assertEquals(1.5, copy.energyKwh(), 0.0001);
    }

    @Test
    public void newerH2GenerationWinsAsAWholeAcrossClockRollbackAndCountsNextDeltaOnce() {
        ChargeCounterAccumulator.State journal =
                new ChargeCounterAccumulator.State();
        journal.baseline = 10.0;
        journal.last = 12.0;
        journal.lastAtMs = 2_000L;
        journal.observationGeneration = 1L;
        journal.accumulated = 2.0;
        journal.fullScaleKwh =
                ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH;

        ChargeCounterAccumulator.State durable =
                new ChargeCounterAccumulator.State();
        durable.baseline = 10.0;
        durable.last = 13.0;
        durable.lastAtMs = 1_000L;
        durable.observationGeneration = 2L;
        durable.accumulated = 3.0;
        durable.fullScaleKwh =
                ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH;

        ChargeCounterAccumulator.State selected =
                ChargeCounterAccumulator.newestCompleteState(
                        journal, durable, false);

        assertEquals(13.0, selected.last, 0.0);
        assertEquals(1_000L, selected.lastAtMs);
        assertEquals(2L, selected.observationGeneration);
        assertEquals(3.0, selected.accumulated, 0.0);

        ChargeCounterAccumulator restored = new ChargeCounterAccumulator();
        restored.restoreState(selected);
        restored.observe(14.0, 900L);
        assertEquals(4.0, restored.energyKwh(), 0.0);
    }

    @Test
    public void legacyImagesStillSelectOneWholeNewerTimestamp() {
        ChargeCounterAccumulator.State journal =
                new ChargeCounterAccumulator.State();
        journal.baseline = 0.0;
        journal.last = 3.0;
        journal.lastAtMs = 4_000L;
        journal.accumulated = 3.0;

        ChargeCounterAccumulator.State durable =
                new ChargeCounterAccumulator.State();
        durable.baseline = 0.0;
        durable.last = 2.0;
        durable.lastAtMs = 3_000L;
        durable.accumulated = 20.0;

        ChargeCounterAccumulator.State selected =
                ChargeCounterAccumulator.newestCompleteState(
                        journal, durable, false);

        assertEquals(3.0, selected.last, 0.0);
        assertEquals(4_000L, selected.lastAtMs);
        assertEquals(3.0, selected.accumulated, 0.0);
    }

    @Test
    public void materialFallWithoutUsableElapsedTimeDefaultsToReset() {
        ChargeCounterAccumulator accumulator = new ChargeCounterAccumulator();
        accumulator.observe(50.0, 10_000L);

        accumulator.observe(10.0, 9_000L);

        assertEquals(0, accumulator.wrapCount());
        assertEquals(1, accumulator.resetCount());
        assertEquals(10.0, accumulator.energyKwh(), 0.0);
        assertTrue(accumulator.isIncomplete());
    }

    @Test
    public void nonFiniteCounterStateAndObservationsAreRejected() {
        ChargeCounterAccumulator accumulator = new ChargeCounterAccumulator();
        accumulator.setFullScaleKwh(Double.POSITIVE_INFINITY);
        assertEquals(ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH,
                accumulator.fullScaleKwh(), 0.0);

        accumulator.observe(Double.POSITIVE_INFINITY, 1_000L);
        assertFalse(accumulator.hasBaseline());
        assertEquals(0.0, accumulator.energyKwh(), 0.0);

        accumulator.restore(
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                false,
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY);
        assertFalse(accumulator.hasBaseline());
        assertEquals(0.0, accumulator.energyKwh(), 0.0);
        assertEquals(ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH,
                accumulator.fullScaleKwh(), 0.0);

        ChargeCounterAccumulator.State corrupt =
                new ChargeCounterAccumulator.State();
        corrupt.baseline = Double.POSITIVE_INFINITY;
        corrupt.last = Double.POSITIVE_INFINITY;
        corrupt.accumulated = Double.POSITIVE_INFINITY;
        corrupt.abandonedKwh = Double.POSITIVE_INFINITY;
        corrupt.gapEstimateKwh = Double.POSITIVE_INFINITY;
        corrupt.recentRateKwhPerH = Double.POSITIVE_INFINITY;
        corrupt.fullScaleKwh = Double.POSITIVE_INFINITY;
        accumulator.restoreState(corrupt);

        assertFalse(accumulator.hasBaseline());
        assertEquals(0.0, accumulator.energyKwh(), 0.0);
        assertEquals(0,
                ChargeCounterAccumulator.gapCandidatesKwh(
                        Double.POSITIVE_INFINITY, 1.0).length);
        assertTrue(Double.isNaN(
                ChargeCounterAccumulator.chooseCandidate(
                        new double[] {Double.POSITIVE_INFINITY},
                        1.0, 0.6, 1.3)));
    }
}
