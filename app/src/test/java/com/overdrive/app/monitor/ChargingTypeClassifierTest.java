package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ChargingTypeClassifierTest {

    @Test
    public void explicitAcGunRemainsAcEvenWithStaleHighPower() {
        assertEquals(ChargingTypeClassifier.AC,
                ChargingTypeClassifier.classify(2, 359.4));
    }

    @Test
    public void dcGunRequiresMeasuredSanityFloor() {
        assertEquals(ChargingTypeClassifier.DC,
                ChargingTypeClassifier.classify(3, 15.0));
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classify(3, 14.9));
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classify(3, Double.NaN));
    }

    @Test
    public void comboOrUnavailableGunUsesPowerOnlyDcThreshold() {
        assertEquals(ChargingTypeClassifier.DC,
                ChargingTypeClassifier.classify(4, 25.0));
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classify(4, 24.9));
        assertEquals(ChargingTypeClassifier.DC,
                ChargingTypeClassifier.classify(-10011, 25.0));
    }

    @Test
    public void highEstimatedRateOnlyCorroboratesExplicitDcGun() {
        assertEquals(ChargingTypeClassifier.DC,
                ChargingTypeClassifier.classifyWithCorroboratingPower(
                        3, Double.NaN, 82.0));
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classifyWithCorroboratingPower(
                        3, Double.NaN, 7.0));
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classifyWithCorroboratingPower(
                        4, Double.NaN, 82.0));
        assertEquals(ChargingTypeClassifier.AC,
                ChargingTypeClassifier.classifyWithCorroboratingPower(
                        2, Double.NaN, 82.0));
    }

    @Test
    public void noConnectionAndV2lNeverBecomeDc() {
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classify(1, 500.0));
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classify(5, 500.0));
    }

    @Test
    public void provenOpenSessionVerdictSurvivesDcTaper() {
        assertEquals(ChargingTypeClassifier.DC,
                ChargingTypeClassifier.classifyLive(
                        3, 8.0, ChargingTypeClassifier.DC));
        assertEquals(ChargingTypeClassifier.AC,
                ChargingTypeClassifier.classifyLive(
                        2, 50.0, ChargingTypeClassifier.AC));
        assertEquals(ChargingTypeClassifier.UNKNOWN,
                ChargingTypeClassifier.classifyLive(
                        3, 8.0, ChargingTypeClassifier.UNKNOWN));
    }

    @Test
    public void binaryTelemetryPreservesUnknownVerdict() {
        assertEquals(Integer.valueOf(1),
                ChargingTypeClassifier.toBinaryFlag(
                        ChargingTypeClassifier.DC));
        assertEquals(Integer.valueOf(0),
                ChargingTypeClassifier.toBinaryFlag(
                        ChargingTypeClassifier.AC));
        assertNull(ChargingTypeClassifier.toBinaryFlag(
                ChargingTypeClassifier.UNKNOWN));
    }
}
