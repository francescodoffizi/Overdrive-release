package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Regression coverage for the generic charge-stop SDK transaction lock. */
public class BydDataCollectorChargeCapConcurrencyTest {

    private DaemonLogger.Config originalLoggerConfig;

    @Before
    public void setUp() {
        originalLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void tearDown() {
        DaemonLogger.configure(originalLoggerConfig);
    }

    @Test
    public void concurrentCapacityWritesConfirmTheirOwnReadback() throws Exception {
        FakeChargingDevice device = new FakeChargingDevice();
        BydDataCollector collector = collectorWith(device);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> collector.setChargeCapPercent(80));
            assertTrue("first capacity write did not reach the fake HAL",
                    device.capacityWritten.await(2, TimeUnit.SECONDS));

            Future<Boolean> second = executor.submit(() -> collector.setChargeCapPercent(90));

            assertTrue(first.get(3, TimeUnit.SECONDS));
            assertTrue(second.get(3, TimeUnit.SECONDS));
            assertEquals(90, collector.getLastAppliedCapPercent());
            assertTrue(collector.isChargeCapSupported());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void restartReprobeRevalidatesTheExistingChargeCapWithoutChangingItsState()
            throws Exception {
        FakeChargingDevice device = new FakeChargingDevice();
        device.chargeStopCapacityState = 85;
        device.chargeStopSwitchState = 1;
        BydDataCollector collector = collectorWith(device);

        assertTrue(collector.reprobeChargeCapFromCurrentState());
        assertTrue(collector.isChargeCapSupported());
        assertEquals(85, collector.getChargeCapPercent());
        assertEquals(1, collector.getChargeCapEnabled());
        assertEquals(java.util.Collections.singletonList("capacity:85"), device.operations());
    }

    @Test
    public void enableWaitsForConcurrentCapacityVerification() throws Exception {
        FakeChargingDevice device = new FakeChargingDevice();
        BydDataCollector collector = collectorWith(device);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> capacity = executor.submit(() -> collector.setChargeCapPercent(80));
            assertTrue("capacity write did not reach the fake HAL",
                    device.capacityWritten.await(2, TimeUnit.SECONDS));

            Future<Boolean> enable = executor.submit(() -> collector.setChargeCapEnabled(true));

            assertTrue(capacity.get(3, TimeUnit.SECONDS));
            assertTrue(enable.get(3, TimeUnit.SECONDS));
            assertEquals(1, device.chargeStopSwitchState);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void combinedUpdateKeepsCapacityAndSwitchTogetherBeforeOtherWrites() throws Exception {
        FakeChargingDevice device = new FakeChargingDevice();
        BydDataCollector collector = collectorWith(device);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> combined = executor.submit(
                    () -> collector.setChargeCapPercentAndEnabled(80, true));
            assertTrue("combined capacity write did not reach the fake HAL",
                    device.capacityWritten.await(2, TimeUnit.SECONDS));

            Future<Boolean> laterCapacity = executor.submit(() -> collector.setChargeCapPercent(90));

            assertTrue(combined.get(3, TimeUnit.SECONDS));
            assertTrue(laterCapacity.get(3, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("capacity:80", "switch:1", "capacity:90"),
                    device.operations());
            assertEquals(90, collector.getChargeCapPercent());
            assertEquals(1, collector.getChargeCapEnabled());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void combinedUpdateRequiresFinalReadbacksForBothRegisters() throws Exception {
        FakeChargingDevice device = new FakeChargingDevice();
        device.switchStateChangesAfterConfirmation = true;
        BydDataCollector collector = collectorWith(device);

        BydDataCollector.ChargeCapUpdateResult result =
                collector.setChargeCapPercentAndEnabledWithResult(80, true);

        assertFalse(result.fullyApplied);
        assertEquals(80, result.capacityPercent);
        assertEquals(0, result.enabledState);
        assertTrue(result.partiallyApplied(80, true));
    }

    private static BydDataCollector collectorWith(FakeChargingDevice device) throws Exception {
        Constructor<BydDataCollector> constructor = BydDataCollector.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BydDataCollector collector = constructor.newInstance();

        Field chargingDevice = BydDataCollector.class.getDeclaredField("chargingDevice");
        chargingDevice.setAccessible(true);
        chargingDevice.set(collector, device);
        return collector;
    }

    /**
     * The production confirmation delay lets a second unguarded write replace
     * this state before the first write reads it back.
     */
    public static final class FakeChargingDevice {
        final CountDownLatch capacityWritten = new CountDownLatch(1);
        final List<String> operations = Collections.synchronizedList(new ArrayList<>());
        volatile int chargeStopCapacityState = 50;
        volatile int chargeStopSwitchState = 0;
        volatile boolean switchStateChangesAfterConfirmation;
        private int switchReadCount;

        public int setChargeStopCapacityState(int percent) {
            chargeStopCapacityState = percent;
            operations.add("capacity:" + percent);
            capacityWritten.countDown();
            return 0;
        }

        public int getChargeStopCapacityState() {
            return chargeStopCapacityState;
        }

        public int setChargeStopSwitchState(int state) {
            chargeStopSwitchState = state;
            operations.add("switch:" + state);
            return 0;
        }

        public int getChargeStopSwitchState() {
            switchReadCount++;
            if (switchStateChangesAfterConfirmation && switchReadCount > 1) return 0;
            return chargeStopSwitchState;
        }

        List<String> operations() {
            synchronized (operations) {
                return new ArrayList<>(operations);
            }
        }
    }
}
