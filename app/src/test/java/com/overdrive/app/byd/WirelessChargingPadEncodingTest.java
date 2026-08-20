package com.overdrive.app.byd;

import android.hardware.bydauto.BYDAutoEventValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Pins the OEM's distinct combined and direct right-pad command contracts. */
public class WirelessChargingPadEncodingTest {

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void disableAndroidAndFileLogging() {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void restoreLogging() {
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void combinedSetterUsesPadSpecificCodes() {
        assertEquals(1, BydDataCollector.wirelessPadPrimaryCode(
                BydDataCollector.WIRELESS_PAD_LEFT, true));
        assertEquals(2, BydDataCollector.wirelessPadPrimaryCode(
                BydDataCollector.WIRELESS_PAD_LEFT, false));
        assertEquals(4, BydDataCollector.wirelessPadPrimaryCode(
                BydDataCollector.WIRELESS_PAD_RIGHT, true));
        assertEquals(5, BydDataCollector.wirelessPadPrimaryCode(
                BydDataCollector.WIRELESS_PAD_RIGHT, false));
    }

    @Test
    public void rightFallbackUsesDirectFeatureAndOneTwoValues() {
        assertEquals(0x4C110026, BydFeatureIds.CHARGING_WIRELESS_RIGHT_SWITCH_DIRECT);
        assertEquals(BydFeatureIds.CHARGING_WIRELESS_RIGHT_SWITCH_DIRECT,
                BydDataCollector.wirelessPadFallbackFeatureId(
                        BydDataCollector.WIRELESS_PAD_RIGHT));
        assertEquals(1, BydDataCollector.wirelessPadFallbackValue(
                BydDataCollector.WIRELESS_PAD_RIGHT, true));
        assertEquals(2, BydDataCollector.wirelessPadFallbackValue(
                BydDataCollector.WIRELESS_PAD_RIGHT, false));
    }

    @Test
    public void rightFallbackRunsOnlyForReturnedRejection() {
        assertFalse(BydDataCollector.shouldFallbackWirelessPad(
                BydDataCollector.WIRELESS_PAD_RIGHT,
                BydDataCollector.WirelessPrimaryOutcome.ABSENT));
        assertFalse(BydDataCollector.shouldFallbackWirelessPad(
                BydDataCollector.WIRELESS_PAD_RIGHT,
                BydDataCollector.WirelessPrimaryOutcome.ERROR));
        assertTrue(BydDataCollector.shouldFallbackWirelessPad(
                BydDataCollector.WIRELESS_PAD_RIGHT,
                BydDataCollector.WirelessPrimaryOutcome.REJECTED));
        assertFalse(BydDataCollector.shouldFallbackWirelessPad(
                BydDataCollector.WIRELESS_PAD_RIGHT,
                BydDataCollector.WirelessPrimaryOutcome.ACCEPTED));
    }

    @Test
    public void leftPadNeverUsesRightOnlyFallback() {
        assertFalse(BydDataCollector.shouldFallbackWirelessPad(
                BydDataCollector.WIRELESS_PAD_LEFT,
                BydDataCollector.WirelessPrimaryOutcome.ABSENT));
        assertFalse(BydDataCollector.shouldFallbackWirelessPad(
                BydDataCollector.WIRELESS_PAD_LEFT,
                BydDataCollector.WirelessPrimaryOutcome.REJECTED));
        assertFalse(BydDataCollector.shouldFallbackWirelessPad(
                BydDataCollector.WIRELESS_PAD_LEFT,
                BydDataCollector.WirelessPrimaryOutcome.ERROR));
    }

    @Test
    public void oemResultJudgeRequiresANonnegativeInteger() {
        assertTrue(BydDataCollector.isOemWirelessResultAccepted(Integer.valueOf(0)));
        assertTrue(BydDataCollector.isOemWirelessResultAccepted(Integer.valueOf(2)));
        assertFalse(BydDataCollector.isOemWirelessResultAccepted(Integer.valueOf(-1)));
        assertFalse(BydDataCollector.isOemWirelessResultAccepted(null));
        assertFalse(BydDataCollector.isOemWirelessResultAccepted(Boolean.TRUE));
        assertFalse(BydDataCollector.isOemWirelessResultAccepted(Long.valueOf(0L)));
    }

    @Test
    public void rightNullPrimaryDoesNotInvokeDirectFallback() throws Exception {
        FakeChargingDevice device = new FakeChargingDevice(null, Integer.valueOf(0));

        assertFalse(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_RIGHT, false));
        assertEquals(5, device.primaryValue);
        assertEquals(0, device.fallbackCalls);
    }

    @Test
    public void rightNegativePrimaryInvokesDirectFallback() throws Exception {
        FakeChargingDevice device =
                new FakeChargingDevice(Integer.valueOf(-2147482648), Integer.valueOf(2));

        assertTrue(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_RIGHT, true));
        assertEquals(4, device.primaryValue);
        assertEquals(1, device.fallbackCalls);
        assertEquals(1, device.fallbackValue);
    }

    @Test
    public void acceptedPrimaryNeverInvokesFallback() throws Exception {
        FakeChargingDevice device =
                new FakeChargingDevice(Integer.valueOf(2), Integer.valueOf(0));

        assertTrue(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_RIGHT, true));
        assertEquals(4, device.primaryValue);
        assertEquals(0, device.fallbackCalls);
    }

    @Test
    public void rightMissingPrimaryDoesNotInvokeFallback() throws Exception {
        FallbackOnlyChargingDevice device =
                new FallbackOnlyChargingDevice(Integer.valueOf(0));

        assertFalse(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_RIGHT, true));
        assertEquals(0, device.fallbackCalls);
    }

    @Test
    public void rightThrowingPrimaryDoesNotInvokeFallback() throws Exception {
        ThrowingPrimaryChargingDevice device =
                new ThrowingPrimaryChargingDevice(Integer.valueOf(0));

        assertFalse(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_RIGHT, false));
        assertEquals(5, device.primaryValue);
        assertEquals(0, device.fallbackCalls);
    }

    @Test
    public void nullFallbackResultIsNotManufacturedIntoSuccess() throws Exception {
        FakeChargingDevice device = new FakeChargingDevice(Integer.valueOf(-1), null);

        assertFalse(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_RIGHT, true));
        assertEquals(1, device.fallbackCalls);
    }

    @Test
    public void booleanFallbackResultIsNotManufacturedIntoSuccess() throws Exception {
        FakeChargingDevice device =
                new FakeChargingDevice(Integer.valueOf(-1), Boolean.TRUE);

        assertFalse(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_RIGHT, true));
        assertEquals(1, device.fallbackCalls);
    }

    @Test
    public void missingLeftPrimaryFailsWithoutFallback() throws Exception {
        FallbackOnlyChargingDevice device =
                new FallbackOnlyChargingDevice(Integer.valueOf(0));

        assertFalse(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_LEFT, false));
        assertEquals(0, device.fallbackCalls);
    }

    @Test
    public void leftPrimaryRefusalDoesNotUseRightOnlyFallback() throws Exception {
        FakeChargingDevice device = new FakeChargingDevice(Integer.valueOf(-1), Integer.valueOf(0));

        assertFalse(collectorWith(device).setWirelessChargingPad(
                BydDataCollector.WIRELESS_PAD_LEFT, true));
        assertEquals(1, device.primaryValue);
        assertEquals(0, device.fallbackCalls);
    }

    private static BydDataCollector collectorWith(Object chargingDevice) throws Exception {
        Constructor<BydDataCollector> constructor =
                BydDataCollector.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BydDataCollector collector = constructor.newInstance();
        Field field = BydDataCollector.class.getDeclaredField("chargingDevice");
        field.setAccessible(true);
        field.set(collector, chargingDevice);
        return collector;
    }

    public static class FallbackOnlyChargingDevice {
        private final Object fallbackResult;
        int fallbackCalls;
        int fallbackFeatureId;
        int fallbackValue;

        FallbackOnlyChargingDevice(Object fallbackResult) {
            this.fallbackResult = fallbackResult;
        }

        public Object set(int[] featureIds, BYDAutoEventValue value) {
            fallbackCalls++;
            fallbackFeatureId = featureIds[0];
            fallbackValue = value.intValue;
            return fallbackResult;
        }
    }

    public static final class FakeChargingDevice extends FallbackOnlyChargingDevice {
        private final Object primaryResult;
        int primaryValue = -1;

        FakeChargingDevice(Object primaryResult, Object fallbackResult) {
            super(fallbackResult);
            this.primaryResult = primaryResult;
        }

        public Object setWirelessChargingSwitchState(int value) {
            primaryValue = value;
            return primaryResult;
        }

    }

    public static final class ThrowingPrimaryChargingDevice
            extends FallbackOnlyChargingDevice {
        int primaryValue = -1;

        ThrowingPrimaryChargingDevice(Object fallbackResult) {
            super(fallbackResult);
        }

        public Object setWirelessChargingSwitchState(int value) {
            primaryValue = value;
            throw new IllegalStateException("simulated OEM failure");
        }
    }
}
