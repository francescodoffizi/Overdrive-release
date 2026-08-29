package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.mqtt.VehicleControlCatalog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import org.json.JSONObject;
import org.junit.Test;

/** Climate automation compatibility and OEM encoding checks. */
public class ClimateAutomationCompatibilityTest {

    @Test
    public void absoluteTemperatureAcceptsBothSdkZone() throws Exception {
        Action action = new Actions().getAction("setAcTemp");

        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22).put("zone", "0")));

        assertNotNull(parsed);
        assertEquals("0", parsed.getVariables().get("zone"));
    }

    @Test
    public void legacyClimateBothAndMissingZoneMigrateToSdkZoneZero() throws Exception {
        Action action = new Actions().getAction("setAcTemp");

        AutomationAction legacyBoth = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22).put("zone", "both")));
        AutomationAction legacyMissing = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22)));

        assertNotNull(legacyBoth);
        assertNotNull(legacyMissing);
        assertEquals("0", legacyBoth.getVariables().get("zone"));
        assertEquals("0", legacyMissing.getVariables().get("zone"));
    }

    @Test
    public void migrationDoesNotAcceptOtherClimateZoneTokens() throws Exception {
        Action action = new Actions().getAction("setAcTemp");

        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22).put("zone", "all")));

        assertNull(parsed);
    }

    @Test
    public void ambientZoneFallbackRemainsBothForSavedActions() throws Exception {
        Action action = new Actions().getAction("ambientPower");

        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "ambientPower")
                .put("variables", new JSONObject().put("state", "100")));

        assertNotNull(parsed);
        assertEquals("both", parsed.getVariables().get("zone"));
    }

    @Test
    public void acSyncUsesOemLinkedZoneEncodingAndIsExposed() throws Exception {
        Action action = new Actions().getAction("acSync");
        assertTrue(action instanceof ApiAction);
        EnumType input = (EnumType) ((ApiAction) action).getVariables().get(0);
        assertTrue(input.isValidValue("off"));
        assertTrue(input.isValidValue("on"));
        assertFalse(input.isValidValue("toggle"));
        assertEquals(AutomationCategories.CLIMATE, AutomationCategories.forId("acSync"));

        Constructor<BydDataCollector> constructor =
                BydDataCollector.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BydDataCollector collector = constructor.newInstance();
        FakeAcDevice device = new FakeAcDevice();
        Field acDevice = BydDataCollector.class.getDeclaredField("acDevice");
        acDevice.setAccessible(true);
        acDevice.set(collector, device);

        assertTrue(collector.setAcTemperatureSync(true));
        assertEquals(1, device.area);
        assertEquals(0, device.mode);
        assertTrue(collector.setAcTemperatureSync(false));
        assertEquals(1, device.area);
        assertEquals(1, device.mode);
    }

    @Test
    public void snowRainModeUsesTheExistingDriveModeCommand() {
        VehicleControlAction automation =
                (VehicleControlAction) new Actions().getAction("drive_mode");
        EnumType input = (EnumType) automation.getVariables().get(0);
        assertTrue(input.isValidValue("snow"));

        VehicleControlCatalog.ControlEntity entity = VehicleControlCatalog.get("drive_mode");
        assertNotNull(entity);
        assertEquals(java.util.Arrays.asList("normal", "eco", "sport", "snow"),
                entity.options);
        VehicleControlCatalog.ControlAction routed = entity.toAction(null, "snow", null);
        assertNotNull(routed);
        assertTrue(routed.command instanceof VehicleCommandRouter.OperationModeCommand);
        assertEquals(4, ((VehicleCommandRouter.OperationModeCommand) routed.command).mode);
    }

    public static final class FakeAcDevice {
        int area;
        int mode;

        public int setAcTemperatureControlMode(int area, int mode) {
            this.area = area;
            this.mode = mode;
            return 0;
        }
    }

}
