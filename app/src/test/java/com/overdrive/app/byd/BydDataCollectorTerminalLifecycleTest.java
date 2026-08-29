package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class BydDataCollectorTerminalLifecycleTest {

    @Test
    public void terminalBarrierRejectsCallbackOnlyLifecycleEpochs() {
        assertFalse(BydDataCollector.shouldPublishBmsCallbackTransition(2, 1, true));
        assertFalse(BydDataCollector.shouldPublishBmsCallbackTransition(15, 2, true));
        assertTrue(BydDataCollector.shouldPublishBmsCallbackTransition(2, 2, true));
        assertTrue(BydDataCollector.shouldPublishBmsCallbackTransition(2, 1, false));
    }

    @Test
    public void terminalCounterUsesNewestValidObservationEvenWhenLower() {
        double nearCapacityCeiling =
                BydDataCollector.CHARGING_CAPACITY_MAX_KWH - 0.1;
        assertEquals(0.25, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_CAPACITY, nearCapacityCeiling, 0.25), 0.0);
        assertEquals(0.0, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_CAPACITY, 4.25, 0.0), 0.0);
        assertEquals(3.0, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_EXTERNAL, 499.5, 3.0), 0.0);
    }

    @Test
    public void invalidTerminalCounterCannotReplaceExistingValue() {
        assertEquals(4.25, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_CAPACITY, 4.25,
                BydDataCollector.CHARGING_CAPACITY_MAX_KWH + 0.001), 0.0);
        assertEquals(4.25, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_CAPACITY, 4.25, Double.NaN), 0.0);
    }

    @Test
    public void reservationBatchKeepsLatestObservationInsteadOfNumericMaximum()
            throws Exception {
        Class<?> orderClass = Class.forName(
                "com.overdrive.app.byd.BydDataCollector$ChargingObservationOrder");
        java.lang.reflect.Constructor<?> constructor = orderClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object order = constructor.newInstance();
        Method reserve = orderClass.getDeclaredMethod(
                "reserveCounterCallback", long.class, String.class, double.class);
        reserve.setAccessible(true);
        Method settle = orderClass.getDeclaredMethod(
                "settleCounterCallbacks", long.class, long.class, boolean.class);
        settle.setAccessible(true);

        reserve.invoke(order, 7L, ChargeSourceClassifier.SRC_CAPACITY,
                BydDataCollector.CHARGING_CAPACITY_MAX_KWH - 0.1);
        reserve.invoke(order, 7L, ChargeSourceClassifier.SRC_CAPACITY, 0.25);
        Object batch = settle.invoke(order, Long.MAX_VALUE, 7L, true);

        Field hasCapacity = batch.getClass().getDeclaredField("hasCapacity");
        Field capacityKwh = batch.getClass().getDeclaredField("capacityKwh");
        hasCapacity.setAccessible(true);
        capacityKwh.setAccessible(true);
        assertTrue(hasCapacity.getBoolean(batch));
        assertEquals(0.25, capacityKwh.getDouble(batch), 0.0);
    }
}
