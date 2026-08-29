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
        RemoteCommunicationAvailability.Result listener =
                RemoteCommunicationAvailability.listener(
                        true, allDisabled, true);

        assertFalse(voice.ready);
        assertEquals("car_off", voice.code);
        assertEquals("car_off", messages.code);
        assertEquals("car_off", listener.code);
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
        RemoteCommunicationSettings.Snapshot listenerEnabled =
                new RemoteCommunicationSettings.Snapshot(
                        true, 70, false, true, true, false);
        assertTrue(RemoteCommunicationAvailability.listener(
                false, listenerEnabled, false).ready);
        assertEquals("listener_busy",
                RemoteCommunicationAvailability.listener(
                        false, listenerEnabled, true).code);
        assertEquals("listener_disabled",
                RemoteCommunicationAvailability.listener(
                        false, ENABLED, false).code);
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
