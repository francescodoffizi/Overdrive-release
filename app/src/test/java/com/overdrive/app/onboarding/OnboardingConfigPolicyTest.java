package com.overdrive.app.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.config.VehicleModelSelection;

import org.junit.Test;

public class OnboardingConfigPolicyTest {

    @Test
    public void vehicleSetupRunsBeforeCameraSetup() {
        assertEquals(OnboardingConfigPolicy.Step.VEHICLE,
                OnboardingConfigPolicy.nextStep(true, true, false, false, false));
        assertEquals(OnboardingConfigPolicy.Step.CAMERA,
                OnboardingConfigPolicy.nextStep(true, true, true, false, false));
    }

    @Test
    public void restoredVehicleAndCameraAdvanceToDashboard() {
        assertEquals(OnboardingConfigPolicy.Step.DASHBOARD,
                OnboardingConfigPolicy.nextStep(true, true, true, true, false));
        assertEquals(OnboardingConfigPolicy.Step.DONE,
                OnboardingConfigPolicy.nextStep(true, true, true, true, true));
    }

    @Test
    public void restoredManualOrValidatedCameraSkipsPreviewWalk() {
        assertTrue(OnboardingConfigPolicy.hasConfiguredCamera(0, true, false));
        assertTrue(OnboardingConfigPolicy.hasConfiguredCamera(3, false, true));
        assertFalse(OnboardingConfigPolicy.hasConfiguredCamera(0, false, false));
        assertFalse(OnboardingConfigPolicy.hasConfiguredCamera(-1, true, true));
        assertFalse(OnboardingConfigPolicy.hasConfiguredCamera(6, true, true));
    }

    @Test
    public void rendererFallbackDoesNotSkipVehicleQuestion() {
        assertFalse(OnboardingConfigPolicy.hasConfiguredVehicle(
                "seal", VehicleModelSelection.SOURCE_UNSET));
        assertTrue(OnboardingConfigPolicy.hasConfiguredVehicle(
                "atto3", VehicleModelSelection.SOURCE_USER));
    }
}
