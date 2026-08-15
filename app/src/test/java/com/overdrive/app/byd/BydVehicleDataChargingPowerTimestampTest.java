package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BydVehicleDataChargingPowerTimestampTest {

    @Test
    public void powerObservationTimesSurviveCopiesAndClearWithValues() {
        long before = System.currentTimeMillis();
        BydVehicleData first = new BydVehicleData.Builder()
                .chargingPowerKw(6.5)
                .externalChargingPowerKw(7.0)
                .chargePowerKw(6.4)
                .build();

        assertTrue(first.chargingPowerAtMs >= before);
        assertTrue(first.externalChargingPowerAtMs >= before);
        assertTrue(first.chargePowerAtMs >= before);

        BydVehicleData copied = first.toBuilder()
                .chargingState(1)
                .chargingPowerKw(6.5)
                .externalChargingPowerKw(7.0)
                .chargePowerKw(6.4)
                .build();
        assertEquals(first.chargingPowerAtMs, copied.chargingPowerAtMs);
        assertEquals(first.externalChargingPowerAtMs, copied.externalChargingPowerAtMs);
        assertEquals(first.chargePowerAtMs, copied.chargePowerAtMs);

        BydVehicleData cleared = copied.toBuilder()
                .chargingPowerKw(Double.NaN)
                .externalChargingPowerKw(Double.NaN)
                .chargePowerKw(Double.NaN)
                .build();
        assertEquals(0L, cleared.chargingPowerAtMs);
        assertEquals(0L, cleared.externalChargingPowerAtMs);
        assertEquals(0L, cleared.chargePowerAtMs);
    }

    @Test
    public void explicitTimesCanMarkSameValueAsARealCallbackObservation() {
        BydVehicleData first = new BydVehicleData.Builder()
                .chargingPowerKw(6.5)
                .externalChargingPowerKw(7.0)
                .chargePowerKw(6.4)
                .clusterChargePowerKw(6.3)
                .build();
        long observedAt = Math.max(System.currentTimeMillis(), first.timestamp) + 10L;

        BydVehicleData refreshed = first.toBuilder()
                .chargingPowerKw(6.5).chargingPowerAtMs(observedAt)
                .externalChargingPowerKw(7.0).externalChargingPowerAtMs(observedAt)
                .chargePowerKw(6.4).chargePowerAtMs(observedAt)
                .clusterChargePowerKw(6.3).clusterChargePowerAtMs(observedAt)
                .build();

        assertEquals(observedAt, refreshed.chargingPowerAtMs);
        assertEquals(observedAt, refreshed.externalChargingPowerAtMs);
        assertEquals(observedAt, refreshed.chargePowerAtMs);
        assertEquals(observedAt, refreshed.clusterChargePowerAtMs);
        assertEquals(0L, refreshed.chargingPowerChangedAtMs);
        assertEquals(0L, refreshed.externalChargingPowerChangedAtMs);
        assertEquals(0L, refreshed.chargePowerChangedAtMs);
        assertEquals(0L, refreshed.clusterChargePowerChangedAtMs);
    }

    @Test
    public void finishedClearRetainsBaselineButUnchangedCallbackIsNotMovement() {
        BydVehicleData active = new BydVehicleData.Builder()
                .chargingPowerKw(3.0)
                .externalChargingPowerKw(3.0)
                .chargePowerKw(3.0)
                .clusterChargePowerKw(3.0)
                .build();
        BydVehicleData cleared = active.toBuilder()
                .chargingPowerKw(Double.NaN)
                .externalChargingPowerKw(Double.NaN)
                .chargePowerKw(Double.NaN)
                .clusterChargePowerKw(Double.NaN)
                .build();
        BydVehicleData repeated = cleared.toBuilder()
                .chargingPowerKw(3.0)
                .externalChargingPowerKw(3.0)
                .chargePowerKw(3.0)
                .clusterChargePowerKw(3.0)
                .build();

        assertEquals(0L, repeated.chargingPowerChangedAtMs);
        assertEquals(0L, repeated.externalChargingPowerChangedAtMs);
        assertEquals(0L, repeated.chargePowerChangedAtMs);
        assertEquals(0L, repeated.clusterChargePowerChangedAtMs);
    }

    @Test
    public void materialPostClearTaperMovementIsRecordedForEveryRateSource() {
        BydVehicleData active = new BydVehicleData.Builder()
                .chargingPowerKw(3.2)
                .externalChargingPowerKw(3.2)
                .chargePowerKw(3.2)
                .clusterChargePowerKw(3.2)
                .build();
        BydVehicleData cleared = active.toBuilder()
                .chargingPowerKw(Double.NaN)
                .externalChargingPowerKw(Double.NaN)
                .chargePowerKw(Double.NaN)
                .clusterChargePowerKw(Double.NaN)
                .build();
        BydVehicleData taper = cleared.toBuilder()
                .chargingPowerKw(3.0)
                .externalChargingPowerKw(3.0)
                .chargePowerKw(3.0)
                .clusterChargePowerKw(3.0)
                .build();

        assertTrue(taper.chargingPowerChangedAtMs > 0L);
        assertTrue(taper.externalChargingPowerChangedAtMs > 0L);
        assertTrue(taper.chargePowerChangedAtMs > 0L);
        assertTrue(taper.clusterChargePowerChangedAtMs > 0L);
    }

    @Test
    public void lifecycleResetDropsMovementAndItsRetainedBaseline() {
        BydVehicleData moved = new BydVehicleData.Builder()
                .chargingPowerKw(3.2)
                .build()
                .toBuilder()
                .chargingPowerKw(3.0)
                .build();
        assertTrue(moved.chargingPowerChangedAtMs > 0L);

        BydVehicleData reset = moved.toBuilder()
                .clearChargingRateMovement()
                .build();
        BydVehicleData nextSessionFirstObservation = reset.toBuilder()
                .chargingPowerKw(2.7)
                .build();

        assertEquals(0L, reset.chargingPowerChangedAtMs);
        assertTrue(Double.isNaN(reset.chargingPowerLastObservedKw));
        assertEquals(0L, nextSessionFirstObservation.chargingPowerChangedAtMs);
    }

    @Test
    public void repeatedFinishedPollDoesNotCreateANewStateEpoch() {
        BydVehicleData finished = new BydVehicleData.Builder()
                .chargingState(2)
                .chargingStateAtMs(12_345L)
                .build();

        BydVehicleData firstPoll = finished.toBuilder().chargingState(2).build();
        BydVehicleData secondPoll = firstPoll.toBuilder().chargingState(2).build();

        assertEquals(12_345L, firstPoll.chargingStateAtMs);
        assertEquals(12_345L, secondPoll.chargingStateAtMs);
    }
}
