package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guards for cloud commands that must not claim dispatch is success. */
public class CloudCommandConfirmationContractTest {

    @Test
    public void routerRemoteCommandsAlwaysWaitForTerminalResult() throws Exception {
        String source = readSource("byd/routing/VehicleCommandRouter.java");
        assertTrue(source.contains(
                "client.executeRemoteCommandWithCode(vin, commandType, extra, true)"));
        assertFalse(source.contains("remoteCommand(client, vin, \"FINDCAR\", null, false)"));
        assertFalse(source.contains(
                "remoteCommand(client, vin, \"FLASHLIGHTNOWHISTLE\", null, false)"));
        assertTrue(source.contains("cancelled.set(true);"));
        assertTrue(source.contains("future.cancel(true);"));
    }

    @Test
    public void testEndpointUsesRouterAndReportsTerminalResultOnly() throws Exception {
        String source = readSource("server/BydCloudApiHandler.java");
        assertTrue(source.contains("new VehicleCommandRouter.FindCarCommand()"));
        assertTrue(source.contains("new VehicleCommandRouter.FlashLightsCommand()"));
        assertTrue(source.contains("VehicleCommandRouter.getInstance().execute(command)"));
        assertTrue(source.contains("response.put(\"success\", confirmed);"));
        assertTrue(source.contains("response.put(\"confirmed\", confirmed);"));
        assertFalse(source.contains("client.findCar(config.vin)"));
        assertFalse(source.contains("client.flashLights(config.vin)"));
    }

    @Test
    public void deterrentUsesRouterInsteadOfDirectCloudCalls() throws Exception {
        String source = readSource("byd/cloud/BydCloudDeterrent.java");
        assertTrue(source.contains("VehicleCommandRouter.getInstance().execute(command)"));
        assertTrue(source.contains("new VehicleCommandRouter.FindCarCommand()"));
        assertTrue(source.contains("new VehicleCommandRouter.FlashLightsCommand()"));
        assertFalse(source.contains(".findCar("));
        assertFalse(source.contains(".flashLights("));
    }

    @Test
    public void transportCancelsRequestsThatRaceRouterTimeout() throws Exception {
        String source = readSource("byd/cloud/BydCloudTransport.java");
        assertTrue(source.contains("throwIfRequestCancelled();"));
        assertTrue(source.contains("activeCalls.put(owner, call);"));
        assertTrue(source.contains("call.cancel();"));
    }

    @Test
    public void interruptedSessionSetupNeverSleepsThenRetriesLogin() throws Exception {
        String source = readSource("byd/cloud/BydCloudClient.java");
        assertTrue(source.contains("throwIfInterrupted();"));
        assertTrue(source.contains("Thread.currentThread().interrupt();"));
        assertTrue(source.contains("BYD cloud session request cancelled"));
    }

    @Test
    public void unsupportedSmartChargeEndpointInvalidatesTheOfflineCache() throws Exception {
        String source = readSource("byd/cloud/BydCloudClient.java");
        assertTrue(source.contains("SmartChargeCache.invalidate(vin, requestOrder);"));
        assertTrue(source.contains("SMART_CHARGE_REQUEST_ORDER_KEY"));
    }

    @Test
    public void smartChargeVehicleUnreachableIsPreservedForCallerRetryPolicy() throws Exception {
        String client = readSource("byd/cloud/BydCloudClient.java");
        String router = readSource("byd/routing/VehicleCommandRouter.java");

        assertTrue(client.contains("CLOUD_CODE_VEHICLE_UNREACHABLE = \"6002\""));
        assertTrue(client.contains("SmartChargeVehicleUnreachableException"));
        assertTrue(router.contains("VEHICLE_UNREACHABLE"));
        assertTrue(router.contains("CommandResult.vehicleUnreachable"));
    }

    @Test
    public void sdkFirstClimateSetsBothLocalZonesAndRejectsCloudClamping() throws Exception {
        String source = readSource("byd/routing/VehicleCommandRouter.java");
        assertTrue(source.contains("return c.setAcTemperature(0, tempCelsius) && c.setAcPower(true);"));
        assertTrue(source.contains("return isCloudTemperatureRepresentable()"));
        assertTrue(source.contains("tempCelsius == Math.rint(tempCelsius)"));
    }

    private static String readSource(String relative) throws Exception {
        Path fromModule = Paths.get("src/main/java/com/overdrive/app").resolve(relative);
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }
        Path fromRepository = Paths.get("app/src/main/java/com/overdrive/app").resolve(relative);
        return new String(Files.readAllBytes(fromRepository), StandardCharsets.UTF_8);
    }
}
