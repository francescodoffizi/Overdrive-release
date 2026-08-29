package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class ChargeSourceClassifierCapacityTest {

    @Test
    public void documentedCapacityCounterCanNeverBecomeRate() {
        long now = 1_000L;
        for (int i = 0; i < 8; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    ChargeSourceClassifier.SRC_CAPACITY, 0.0, now);
            now += 2 * 60_000L;
        }
        for (int i = 1; i <= 8; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    ChargeSourceClassifier.SRC_CAPACITY, i * 0.5, now);
            now += 60_000L;
        }

        assertEquals(ChargeSourceClassifier.Kind.COUNTER,
                ChargeSourceClassifier.kindOf(ChargeSourceClassifier.SRC_CAPACITY));
        assertFalse(ChargeSourceClassifier.isRate(ChargeSourceClassifier.SRC_CAPACITY));
    }

    @Test
    public void frameworkChargingDevicePowerIsAlwaysRateWithoutLearning() {
        assertEquals(ChargeSourceClassifier.Kind.RATE,
                ChargeSourceClassifier.kindOf(ChargeSourceClassifier.SRC_DEVICE));

        long now = 1_000L;
        for (int i = 0; i < 20; i++) {
            ChargeSourceClassifier.observeWhileCharging(
                    ChargeSourceClassifier.SRC_DEVICE, 7.2, now);
            now += 60_000L;
        }

        assertEquals(ChargeSourceClassifier.Kind.RATE,
                ChargeSourceClassifier.kindOf(ChargeSourceClassifier.SRC_DEVICE));
        assertFalse(ChargeSourceClassifier.isCounter(ChargeSourceClassifier.SRC_DEVICE));
    }
}
