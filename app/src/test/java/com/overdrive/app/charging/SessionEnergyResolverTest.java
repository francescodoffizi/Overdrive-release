package com.overdrive.app.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.BeforeClass;
import org.junit.Test;

public class SessionEnergyResolverTest {

    @BeforeClass
    public static void disableAndroidLogging() {
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    /**
     * The captured field failure (log {@code AL37RNJ9}): a counter running at exactly half scale whose
     * ratio against the SOC estimate read 0.58 — PAST the 0.55 band, because the estimate is scaled by
     * an SOH that the halved sessions had themselves dragged down. The band alone therefore priced the
     * session at half and offered it for calibration, where it computed 55.8% pack health.
     */
    @Test
    public void behaviouralScaleSuspicionCatchesWhatTheRatioBandMisses() {
        SessionEnergyResolver.Result missed = SessionEnergyResolver.resolve(
                30.374, false, false, 0.0, 52.4, false, false);
        assertEquals(SessionEnergyResolver.SRC_METERED, missed.source);
        assertEquals(30.374, missed.energyKwh, 0.0001);
        assertTrue("ratio 0.58 sits above HALF_SCALE_MAX_RATIO, so the band cannot catch it",
                30.374 / 52.4 > SessionEnergyResolver.HALF_SCALE_MAX_RATIO);

        SessionEnergyResolver.Result caught = SessionEnergyResolver.resolve(
                30.374, false, false, 0.0, 52.4, false, true);
        assertEquals(SessionEnergyResolver.SRC_SOC_FALLBACK, caught.source);
        assertEquals(52.4, caught.energyKwh, 0.0001);
        assertFalse("a scale fault must never calibrate SOH", caught.canCalibrateSoh());
    }

    /** With no SOC cross-check available, suspicion must still block pricing and calibration. */
    @Test
    public void scaleSuspicionFlagsRowWhenNoSocCrossCheckExists() {
        SessionEnergyResolver.Result result = SessionEnergyResolver.resolve(
                19.5, false, false, 0.0, Double.NaN, false, true);

        assertEquals(SessionEnergyResolver.SRC_METERED, result.source);
        assertTrue(result.incomplete);
        assertFalse(result.canCalibrateSoh());
    }

    /** A clean counter is unaffected: the flag defaults off and the old behaviour is preserved. */
    @Test
    public void healthyCounterIsUnaffectedByTheSuspicionPath() {
        SessionEnergyResolver.Result result = SessionEnergyResolver.resolve(
                10.4, false, false, 10.2, 10.0, false, false);

        assertEquals(SessionEnergyResolver.SRC_METERED, result.source);
        assertEquals(10.4, result.energyKwh, 0.0001);
        assertFalse(result.incomplete);
        assertTrue(result.canCalibrateSoh());
    }

    @Test
    public void halfScaleFallbackPreservesIntegratedTruncation() {
        SessionEnergyResolver.Result result = SessionEnergyResolver.resolve(
                0.5, false, false, 1.0, Double.NaN, true);

        assertEquals(SessionEnergyResolver.SRC_INTEGRATED, result.source);
        assertEquals(1.0, result.energyKwh, 0.0001);
        assertTrue(result.incomplete);
        assertFalse(result.canCalibrateSoh());
    }

    @Test
    public void completeHalfScaleFallbackRemainsCompleteAndCalibratable() {
        SessionEnergyResolver.Result result = SessionEnergyResolver.resolve(
                0.5, false, false, 1.0, Double.NaN, false);

        assertEquals(SessionEnergyResolver.SRC_INTEGRATED, result.source);
        assertFalse(result.incomplete);
        assertTrue(result.canCalibrateSoh());
    }

    @Test
    public void overReadingIntegralCannotCalibrateSoh() {
        SessionEnergyResolver.Result result = SessionEnergyResolver.resolve(
                Double.NaN, false, false, 14.0, 10.0, false);

        assertEquals(SessionEnergyResolver.SRC_SOC, result.source);
        assertFalse(result.canCalibrateSoh());
    }

    @Test
    public void integralInsideFullRatioBandCanCalibrateSoh() {
        SessionEnergyResolver.Result result = SessionEnergyResolver.resolve(
                Double.NaN, false, false, 12.0, 10.0, false);

        assertEquals(SessionEnergyResolver.SRC_SOC, result.source);
        assertTrue(result.canCalibrateSoh());
    }

    @Test
    public void continuationSelectsWrapWhenCounterFallsAcrossOutage() {
        double energy = SessionEnergyResolver.continuationCounterEnergyKwh(
                60.0, 5.0, ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH, 10.5);

        assertEquals(10.534, energy, 0.0001);
    }

    @Test
    public void continuationCanRecoverAFullCycleWhenEndpointStillRises() {
        double energy = SessionEnergyResolver.continuationCounterEnergyKwh(
                10.0, 20.0, ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH, 75.0);

        assertEquals(75.534, energy, 0.0001);
    }

    @Test
    public void continuationWithoutSocProofUsesConservativeCandidate() {
        double energy = SessionEnergyResolver.continuationCounterEnergyKwh(
                10.0, 20.0, ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH, Double.NaN);

        assertEquals(10.0, energy, 0.0001);
    }
}
