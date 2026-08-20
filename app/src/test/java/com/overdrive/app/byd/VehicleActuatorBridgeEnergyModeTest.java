package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/** Pins ordered, conflating delivery from the daemon process to the actuator service. */
public class VehicleActuatorBridgeEnergyModeTest {

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
    public void delayedOlderGenerationCannotReplaceNewerDispatch() {
        VehicleActuatorBridge.EnergyGenerationGate gate =
                new VehicleActuatorBridge.EnergyGenerationGate();

        assertTrue(gate.claim(100L));
        assertTrue(gate.claim(300L));
        assertFalse(gate.claim(200L));
        assertFalse(gate.claim(300L));
        assertTrue(gate.isCurrent(300L));
    }

    @Test
    public void definiteLaunchFailureRetriesOnceAndReturnsRetryOutcome() {
        VehicleActuatorBridge.EnergyGenerationGate gate =
                new VehicleActuatorBridge.EnergyGenerationGate();
        int[] calls = {0};

        boolean launched = VehicleActuatorBridge.dispatchEnergyMode(
                3, 11L, gate, (command, tag, timeoutMs) ->
                        calls[0]++ == 0
                                ? VehicleActuatorBridge.LaunchOutcome.FAILURE
                                : VehicleActuatorBridge.LaunchOutcome.SUCCESS);

        assertTrue(launched);
        assertEquals(2, calls[0]);
    }

    @Test
    public void timeoutIsNotRetriedOrReportedAsSuccess() {
        VehicleActuatorBridge.EnergyGenerationGate gate =
                new VehicleActuatorBridge.EnergyGenerationGate();
        int[] calls = {0};

        boolean launched = VehicleActuatorBridge.dispatchEnergyMode(
                1, 12L, gate, (command, tag, timeoutMs) -> {
                    calls[0]++;
                    return VehicleActuatorBridge.LaunchOutcome.TIMEOUT;
                });

        assertFalse(launched);
        assertEquals(1, calls[0]);
    }

    @Test
    public void bridgeCommandCarriesModeAndSourceGenerationAsStrings() {
        String command = VehicleActuatorBridge.buildEnergyCommand(
                new VehicleActuatorBridge.EnergyDispatch(3, 9876543210L));

        assertTrue(command.contains("--es action energy_mode"));
        assertTrue(command.contains("--es mode 3"));
        assertTrue(command.contains("--es request_generation 9876543210"));
    }

    @Test
    public void kernelBootIdIsTheSameTokenForEveryProcessInTheBoot() {
        assertEquals(
                "boot-12345678-abcd-4321-abcd-1234567890ab",
                VehicleActuatorBridge.buildEnergyBootToken(
                        "12345678-abcd-4321-abcd-1234567890ab"));
    }

    @Test
    public void androidUidMapsToItsSettingsProviderUser() {
        assertEquals(0, VehicleActuatorBridge.userIdForUid(2000));
        assertEquals(10, VehicleActuatorBridge.userIdForUid(1_012_345));
    }

    @Test
    public void bridgeGenerationPlausibilityUsesBoundedBootClockWindow() {
        long now = 100_000_000_000L;

        assertTrue(VehicleActuatorBridge.isPlausibleEnergyGeneration(now, now));
        assertTrue(VehicleActuatorBridge.isPlausibleEnergyGeneration(
                now - TimeUnit.SECONDS.toNanos(60L), now));
        assertFalse(VehicleActuatorBridge.isPlausibleEnergyGeneration(
                now - TimeUnit.SECONDS.toNanos(61L), now));
        assertFalse(VehicleActuatorBridge.isPlausibleEnergyGeneration(
                now + TimeUnit.SECONDS.toNanos(31L), now));
    }

    @Test
    public void staleDesiredMarkersAreBlockedButCancellationCanStillFinish() {
        long now = TimeUnit.MINUTES.toNanos(10L);
        VehicleActuatorBridge.PublishedEnergyRequest desired =
                VehicleActuatorBridge.PublishedEnergyRequest.desired(
                        now - TimeUnit.MINUTES.toNanos(5L), 3);

        assertFalse(VehicleActuatorBridge.isPlausibleEnergyMarker(desired, now));
        assertTrue(VehicleActuatorBridge.isPlausibleEnergyMarker(
                desired.asCancelled(), now));
    }

    @Test
    public void bridgeDispatchAcceptsOnlyFieldValidatedUserModes() {
        assertTrue(VehicleActuatorBridge.isUserWritableEnergyMode(1));
        assertTrue(VehicleActuatorBridge.isUserWritableEnergyMode(3));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(0));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(2));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(4));
        assertFalse(VehicleActuatorBridge.isUserWritableEnergyMode(5));

        int[] launches = {0};
        for (int mode : new int[] {2, 4, 5}) {
            assertFalse(VehicleActuatorBridge.dispatchEnergyMode(
                    mode,
                    100L + mode,
                    new VehicleActuatorBridge.EnergyGenerationGate(),
                    (command, tag, timeoutMs) -> {
                        launches[0]++;
                        return VehicleActuatorBridge.LaunchOutcome.SUCCESS;
                    }));
        }
        assertEquals(0, launches[0]);
    }

    @Test
    public void externalSettingsUsesShellIdentityUserAndReleasesProvider() {
        RecordingExternalSettingsAccess access = new RecordingExternalSettingsAccess();

        VehicleActuatorBridge.GlobalSettingCall result =
                VehicleActuatorBridge.callExternalGlobalSetting(
                        2000,
                        "PUT_global",
                        "overdrive_test",
                        "value",
                        access);

        assertTrue(result.success);
        assertEquals("settings", access.authority);
        assertEquals(0, access.userId);
        assertEquals("OverDriveEnergyState", access.tag);
        assertEquals("com.android.shell", access.callingPackage);
        assertEquals("PUT_global", access.method);
        assertEquals("overdrive_test", access.key);
        assertEquals("value", access.value);
        assertTrue(access.released);
    }

    @Test
    public void externalSettingsReleasesProviderWhenCallFails() {
        RecordingExternalSettingsAccess access = new RecordingExternalSettingsAccess();
        access.failCall = true;

        VehicleActuatorBridge.GlobalSettingCall result =
                VehicleActuatorBridge.callExternalGlobalSetting(
                        2000,
                        "GET_global",
                        "overdrive_test",
                        null,
                        access);

        assertFalse(result.success);
        assertNotNull(result.failure);
        assertTrue(access.released);
    }

    @Test
    public void externalSettingsRejectsNonShellUidBeforeAcquisition() {
        RecordingExternalSettingsAccess access = new RecordingExternalSettingsAccess();

        VehicleActuatorBridge.GlobalSettingCall result =
                VehicleActuatorBridge.callExternalGlobalSetting(
                        10000,
                        "GET_global",
                        "overdrive_test",
                        null,
                        access);

        assertFalse(result.success);
        assertEquals(0, access.acquireCalls);
        assertFalse(access.released);
    }

    private static final class RecordingExternalSettingsAccess
            implements VehicleActuatorBridge.ExternalSettingsAccess {
        private final Object holder = new Object();
        int acquireCalls;
        String authority;
        int userId = -1;
        String tag;
        String callingPackage;
        String method;
        String key;
        String value;
        boolean released;
        boolean failCall;

        @Override
        public Object acquire(String authority, int userId, String tag) {
            acquireCalls++;
            this.authority = authority;
            this.userId = userId;
            this.tag = tag;
            return holder;
        }

        @Override
        public VehicleActuatorBridge.GlobalSettingCall call(
                Object holder,
                String callingPackage,
                String authority,
                String method,
                String key,
                int userId,
                String value) {
            this.callingPackage = callingPackage;
            this.authority = authority;
            this.method = method;
            this.key = key;
            this.userId = userId;
            this.value = value;
            if (failCall) throw new IllegalStateException("provider call failed");
            return VehicleActuatorBridge.GlobalSettingCall.success(null);
        }

        @Override
        public void release(String authority, Object holder) {
            released = this.holder == holder && "settings".equals(authority);
        }
    }
}
