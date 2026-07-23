package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.routing.DrivingSafetyGuard.GearReading;

import org.junit.Test;

public class DrivingSafetyGuardTest {

    @Test
    public void accConfidentlyOffIsNeverBlocked() {
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.NOT_PARK, false, true, 40.0));
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.UNKNOWN, false, true, Double.NaN));
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.PARK, false, true, 0.0));
    }

    @Test
    public void unauthoritativeAccFailsClosed() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, false, 0.0));
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, false, false, 0.0));
    }

    @Test
    public void parkedAndStationaryIsNotBlocked() {
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, 0.0));
    }

    @Test
    public void parkWithMissingSpeedFailsClosed() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, Double.NaN));
    }

    @Test
    public void rollingInParkIsBlocked() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, 2.1));
    }

    @Test
    public void justBelowThresholdIsNotBlocked() {
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, 1.9));
    }

    @Test
    public void drivingGearIsBlocked() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.NOT_PARK, true, true, 0.0));
    }

    @Test
    public void unknownGearIsBlocked() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.UNKNOWN, true, true, 0.0));
    }
}
