package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydVehicleData;

import org.junit.Test;

public class BatteryMonitorFreshnessTest {

    @Test
    public void voltageRequiresAValidRecentObservation() {
        long observedAt = 100_000L;

        assertTrue(BatteryMonitor.isVoltageFresh(
                12.6, observedAt, observedAt + 60_000L));
        assertFalse(BatteryMonitor.isVoltageFresh(
                12.6, observedAt, observedAt + 60_001L));
        assertFalse(BatteryMonitor.isVoltageFresh(
                0.0, observedAt, observedAt + 1_000L));
        assertFalse(BatteryMonitor.isVoltageFresh(
                Double.NaN, observedAt, observedAt + 1_000L));
        assertFalse(BatteryMonitor.isVoltageFresh(
                12.6, 0L, observedAt));
        assertFalse(BatteryMonitor.isVoltageFresh(
                12.6, observedAt, observedAt - 1L));
    }

    @Test
    public void voltageObservationTimeSurvivesUnrelatedSnapshotUpdates() {
        long observedAt = 100_000L;
        BydVehicleData initial = new BydVehicleData.Builder()
                .voltage12v(12.6, observedAt)
                .build();

        BydVehicleData carried = initial.toBuilder()
                .socPercent(70.0)
                .build();

        assertEquals(12.6, carried.voltage12v, 0.001);
        assertEquals(observedAt, carried.voltage12vAtMs);
    }
}
