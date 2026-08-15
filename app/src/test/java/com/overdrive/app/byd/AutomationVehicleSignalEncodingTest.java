package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Locks automation-facing BYD enum mappings to the connected-unit SDK contracts. */
public class AutomationVehicleSignalEncodingTest {

    @Test
    public void drlAndAutoHeadlightRejectUnavailableValues() {
        assertEquals(1, BydDataCollector.normalizeDrlState(1));
        assertEquals(0, BydDataCollector.normalizeDrlState(2));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizeDrlState(0));
        assertEquals(BydVehicleData.UNAVAILABLE, BydDataCollector.normalizeDrlState(-10011));

        assertEquals(0, BydDataCollector.normalizeAutoHeadlightState(0));
        assertEquals(1, BydDataCollector.normalizeAutoHeadlightState(1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoHeadlightState(2));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoHeadlightState(-10011));
    }

    @Test
    public void autoWiperSupportsCurrentAndLegacyDomainsWithoutGuessing() {
        assertEquals(1, BydDataCollector.normalizeAutoWiperSettingState(1));
        assertEquals(0, BydDataCollector.normalizeAutoWiperSettingState(2));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoWiperSettingState(0));

        assertEquals(0, BydDataCollector.normalizeAutoWiperBodyworkState(0));
        assertEquals(1, BydDataCollector.normalizeAutoWiperBodyworkState(1));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAutoWiperBodyworkState(2));
    }

    @Test
    public void wiperActivityCombinesDedicatedAndSettingRails() {
        assertEquals(1, BydDataCollector.normalizeWiperActivity(8, 0));
        assertEquals(1, BydDataCollector.normalizeWiperActivity(9, 1));
        assertEquals(1, BydDataCollector.normalizeWiperActivity(0, 2));
        assertEquals(0, BydDataCollector.normalizeWiperActivity(0, 1));
        assertEquals(0, BydDataCollector.normalizeWiperActivity(
                BydVehicleData.UNAVAILABLE, 0));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeWiperActivity(-10011, -10011));
    }

    @Test
    public void sunroofAndSunshadeCommandsUseReferencePercentageEncoding() {
        assertEquals(100, BydDataCollector.sunWindowPositionForCommand(1));
        assertEquals(0, BydDataCollector.sunWindowPositionForCommand(2));
        assertEquals(254, BydDataCollector.sunWindowPositionForCommand(3));
        assertEquals(-1, BydDataCollector.sunWindowPositionForCommand(4));
        assertEquals(-1, BydDataCollector.sunWindowPositionForCommand(5));
    }

    @Test
    public void speedFactorPrefersDetectedHardwareUnit() throws Exception {
        BydDataCollector collector = BydDataCollector.getInstance();
        java.lang.reflect.Field detected =
                BydDataCollector.class.getDeclaredField("hwUnitDetected");
        java.lang.reflect.Field hardware =
                BydDataCollector.class.getDeclaredField("speedHwFactor");
        java.lang.reflect.Field display =
                BydDataCollector.class.getDeclaredField("distanceToKmFactor");
        detected.setAccessible(true);
        hardware.setAccessible(true);
        display.setAccessible(true);

        boolean previousDetected = detected.getBoolean(collector);
        double previousHardware = hardware.getDouble(collector);
        double previousDisplay = display.getDouble(collector);
        try {
            detected.setBoolean(collector, true);
            hardware.setDouble(collector, 1.0);
            display.setDouble(collector, 1.60934);
            assertEquals(1.0, collector.getSpeedToKmhFactor(), 0.000001);

            detected.setBoolean(collector, false);
            assertEquals(1.60934, collector.getSpeedToKmhFactor(), 0.000001);
        } finally {
            detected.setBoolean(collector, previousDetected);
            hardware.setDouble(collector, previousHardware);
            display.setDouble(collector, previousDisplay);
        }
    }

    @Test
    public void rawSpeedConversionRejectsNonReadings() {
        assertEquals(80.467,
                BydDataCollector.convertRawSpeedToKmh(50.0, 1.60934), 0.000001);
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.convertRawSpeedToKmh(
                        BydFeatureIds.SDK_NOT_AVAILABLE, 1.0)));
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.convertRawSpeedToKmh(-1.0, 1.0)));
        org.junit.Assert.assertTrue(Double.isNaN(
                BydDataCollector.convertRawSpeedToKmh(Double.NaN, 1.0)));
    }
}
