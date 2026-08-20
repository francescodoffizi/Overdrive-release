package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the connected head unit's public setter values and raw getter encoding. */
public class BydDriveModeEncodingTest {

    @Test
    public void configModesMapToPublicEnergyOperationEnum() {
        assertEquals(3, BydDataCollector.energyOperationModeForDriveConfig(1));
        assertEquals(1, BydDataCollector.energyOperationModeForDriveConfig(2));
        assertEquals(2, BydDataCollector.energyOperationModeForDriveConfig(3));
        assertEquals(-1, BydDataCollector.energyOperationModeForDriveConfig(4));
    }

    @Test
    public void rawEnergyGetterMapsBackToConfigModes() {
        assertEquals(1, BydDataCollector.driveModeFromEnergyAxis(1, 1));
        assertEquals(2, BydDataCollector.driveModeFromEnergyAxis(2, 1));
        assertEquals(3, BydDataCollector.driveModeFromEnergyAxis(3, 1));
        assertEquals(4, BydDataCollector.driveModeFromEnergyAxis(2, 2));
        assertEquals(4, BydDataCollector.driveModeFromEnergyAxis(4, 1));
        assertEquals(-1, BydDataCollector.driveModeFromEnergyAxis(0, 1));
    }

    @Test
    public void liveEnergyReadbackBeatsStaleSettingAxis() {
        assertEquals(1, BydDataCollector.chooseDriveModeReadback(1, 1, 3));
        assertEquals(1, BydDataCollector.chooseDriveModeReadback(1, -1, 3));
    }

    @Test
    public void commandAndSettingRemainFallbacksWhenEnergyIsUnavailable() {
        assertEquals(2, BydDataCollector.chooseDriveModeReadback(-1, 2, 3));
        assertEquals(3, BydDataCollector.chooseDriveModeReadback(-1, -1, 3));
        assertEquals(-1, BydDataCollector.chooseDriveModeReadback(-1, -1, 0));
    }
}
