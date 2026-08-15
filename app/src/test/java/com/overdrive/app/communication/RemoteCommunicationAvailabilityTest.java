package com.overdrive.app.communication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteCommunicationAvailabilityTest {

    private static final RemoteCommunicationSettings.Snapshot ENABLED =
            new RemoteCommunicationSettings.Snapshot(true, 70, true, false);

    @Test
    public void carOffTakesPriorityOverPermissionAndSettingsErrors() {
        RemoteCommunicationSettings.Snapshot allDisabled =
                new RemoteCommunicationSettings.Snapshot(false, 70, false, true);

        RemoteCommunicationAvailability.Result voice =
                RemoteCommunicationAvailability.voice(
                        true, allDisabled, true, false);
        RemoteCommunicationAvailability.Result messages =
                RemoteCommunicationAvailability.messages(
                        true, allDisabled, false);

        assertFalse(voice.ready);
        assertEquals("car_off", voice.code);
        assertEquals("car_off", messages.code);
        assertTrue(voice.reason.contains("car is off"));
    }

    @Test
    public void permissionFailureIncludesTheExactCarSettingsPath() {
        RemoteCommunicationAvailability.Result result =
                RemoteCommunicationAvailability.voice(
                        false, ENABLED, false, false);

        assertEquals("overlay_permission", result.code);
        assertTrue(result.guidance.contains(
                RemoteCommunicationAvailability.SETTINGS_PATH));
        assertTrue(result.guidance.contains("Display over other apps"));
    }

    @Test
    public void enabledConfigurationIsReadyWhenTheCarIsAwake() {
        assertTrue(RemoteCommunicationAvailability.voice(
                false, ENABLED, false, true).ready);
        assertTrue(RemoteCommunicationAvailability.messages(
                false, ENABLED, true).ready);
    }

    @Test
    public void overlayProbeIsSkippedWhenPolicyAlreadyBlocksCommunication() {
        RemoteCommunicationSettings.Snapshot allDisabled =
                new RemoteCommunicationSettings.Snapshot(false, 70, false, false);
        RemoteCommunicationSettings.Snapshot emergencyDisabled =
                new RemoteCommunicationSettings.Snapshot(true, 70, true, true);

        assertFalse(RemoteCommunicationAvailability.shouldCheckAnyOverlay(
                true, ENABLED, false));
        assertFalse(RemoteCommunicationAvailability.shouldCheckAnyOverlay(
                false, allDisabled, false));
        assertFalse(RemoteCommunicationAvailability.shouldCheckAnyOverlay(
                false, emergencyDisabled, false));
        assertFalse(RemoteCommunicationAvailability.shouldCheckVoiceOverlay(
                false, ENABLED, true));
        assertTrue(RemoteCommunicationAvailability.shouldCheckAnyOverlay(
                false, ENABLED, false));
    }
}
