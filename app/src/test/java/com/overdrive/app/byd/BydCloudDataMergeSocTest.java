package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.byd.cloud.VehicleCloudSnapshot;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Verifies that BydDataCollector properly merges cloud SoC and range
 * when the vehicle is parked/sleeping (ACC OFF) or when the HAL returned no value.
 */
public class BydCloudDataMergeSocTest {

    @Test
    public void parkedVehicleMergesCloudSocAndRangeEvenWithCarriedForwardSnapshot() throws Exception {
        // Builder initialized with stale previous poll data (e.g. 67% SoC from yesterday)
        BydVehicleData.Builder b = new BydVehicleData.Builder();
        b.socPercent(67.0);
        b.elecRangeKm(350);
        b.insideTempC(21.0, 1000L);

        // Fresh cloud telemetry reporting 85% SoC and 420 km EV range
        JSONObject vi = new JSONObject();
        vi.put("elecPercent", 85.0);
        vi.put("enduranceMileage", 420);
        VehicleCloudSnapshot cs = VehicleCloudSnapshot.fromVehicleInfo(vi).build();

        // Vehicle is parked (accIsOn = false), HAL didn't read anything new (socHalSucceeded = false)
        BydDataCollector.mergeCloudDataSnapshot(b, cs, false, false, false, false, false);

        BydVehicleData built = b.build();
        assertEquals(85.0, built.socPercent, 0.001);
        assertEquals(420, built.elecRangeKm);
    }

    @Test
    public void drivingVehicleKeepsFreshHalSocOverCloud() throws Exception {
        // Builder initialized with fresh HAL reading of 72% during an active drive
        BydVehicleData.Builder b = new BydVehicleData.Builder();
        b.socPercent(72.0);
        b.elecRangeKm(380);

        // Cloud snapshot with slightly older 71%
        JSONObject vi = new JSONObject();
        vi.put("elecPercent", 71.0);
        vi.put("enduranceMileage", 375);
        VehicleCloudSnapshot cs = VehicleCloudSnapshot.fromVehicleInfo(vi).build();

        // Vehicle is driving (accIsOn = true) and HAL succeeded this cycle (socHalSucceeded = true, rangeHalSucceeded = true)
        BydDataCollector.mergeCloudDataSnapshot(b, cs, true, false, true, true, false);

        BydVehicleData built = b.build();
        // Live HAL values are preserved
        assertEquals(72.0, built.socPercent, 0.001);
        assertEquals(380, built.elecRangeKm);
    }

    @Test
    public void unpopulatedHalFallbacksToCloudDuringDrive() throws Exception {
        // Builder has NaN SoC (or HAL failed to read)
        BydVehicleData.Builder b = new BydVehicleData.Builder();
        b.socPercent(Double.NaN);
        b.elecRangeKm(BydVehicleData.UNAVAILABLE);

        JSONObject vi = new JSONObject();
        vi.put("elecPercent", 90.0);
        vi.put("enduranceMileage", 450);
        VehicleCloudSnapshot cs = VehicleCloudSnapshot.fromVehicleInfo(vi).build();

        // accIsOn = true, but HAL failed (socHalSucceeded = false)
        BydDataCollector.mergeCloudDataSnapshot(b, cs, true, false, false, false, false);

        BydVehicleData built = b.build();
        assertEquals(90.0, built.socPercent, 0.001);
        assertEquals(450, built.elecRangeKm);
    }

    @Test
    public void phevFuelMergedWhenParked() throws Exception {
        BydVehicleData.Builder b = new BydVehicleData.Builder();
        b.fuelPercent(40.0);
        b.fuelRangeKm(250);

        JSONObject vi = new JSONObject();
        vi.put("oilPercent", 80.0);
        vi.put("oilEndurance", 500);
        VehicleCloudSnapshot cs = VehicleCloudSnapshot.fromVehicleInfo(vi).build();

        // Parked (accIsOn = false)
        BydDataCollector.mergeCloudDataSnapshot(b, cs, false, false, false, false, false);

        BydVehicleData built = b.build();
        assertEquals(80.0, built.fuelPercent, 0.001);
        assertEquals(500, built.fuelRangeKm);
    }
}
