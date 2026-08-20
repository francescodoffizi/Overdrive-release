package com.overdrive.app.automation.condition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.overdrive.app.byd.BydVehicleData;

import org.junit.Test;

/** Keeps measured cabin temperature distinct from outside air and the HVAC setpoint. */
public class BydTemperatureSignalTest {

    @Test
    public void outsideOnlySnapshotDoesNotBecomeCabinTemperature() {
        BydVehicleData data = new BydVehicleData.Builder()
                .outsideTempC(32)
                .build();

        assertNull(BydEvent.freshCabinTemperatureForAutomation(data));
    }

    @Test
    public void freshMeasuredCabinTemperatureIsPublished() {
        BydVehicleData data = new BydVehicleData.Builder()
                .outsideTempC(32)
                .insideTempC(19.4, System.currentTimeMillis())
                .build();

        assertEquals(Integer.valueOf(19),
                BydEvent.freshCabinTemperatureForAutomation(data));
    }

    @Test
    public void staleCabinMeasurementBecomesUnavailable() {
        long staleAt = System.currentTimeMillis()
                - BydVehicleData.CABIN_TEMP_MAX_AGE_MS - 1L;
        BydVehicleData data = new BydVehicleData.Builder()
                .insideTempC(24, staleAt)
                .outsideTempC(32)
                .build();

        assertNull(BydEvent.freshCabinTemperatureForAutomation(data));
    }

    @Test
    public void parkedEditorCanReconstructRetainedCabinMeasurement() {
        long retainedAt = System.currentTimeMillis()
                - BydVehicleData.CABIN_TEMP_MAX_AGE_MS - 60_000L;
        BydVehicleData data = new BydVehicleData.Builder()
                .insideTempC(41.6, retainedAt)
                .build();

        assertNull(BydEvent.freshCabinTemperatureForAutomation(data));
        assertEquals(Integer.valueOf(42),
                BydEvent.retainedCabinTemperatureForAutomation(data));
    }

    @Test
    public void retainedCabinMeasurementExpiresAfterThirtyMinutes() {
        long expiredAt = System.currentTimeMillis() - 31L * 60_000L;
        BydVehicleData data = new BydVehicleData.Builder()
                .insideTempC(35, expiredAt)
                .build();

        assertNull(BydEvent.retainedCabinTemperatureForAutomation(data));
    }
}
