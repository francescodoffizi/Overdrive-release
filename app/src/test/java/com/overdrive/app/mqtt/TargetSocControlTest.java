package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.AutomationCategories;
import com.overdrive.app.automation.action.Action;
import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.action.VehicleControlAction;
import com.overdrive.app.automation.condition.SignalMqttMap;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.routing.VehicleCommandRouter;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

public class TargetSocControlTest {

    @Test
    public void phevTargetSocIsSharedByAutomationAndMqtt() throws Exception {
        Action action = new Actions().getAction("target_soc");
        assertTrue(action instanceof VehicleControlAction);
        IntType input = (IntType) ((VehicleControlAction) action).getVariables().get(0);
        assertEquals(BydDataCollector.SOC_TARGET_MIN, input.getMin());
        assertEquals(BydDataCollector.SOC_TARGET_MAX, input.getMax());
        assertEquals(AutomationCategories.DRIVE, AutomationCategories.forId("target_soc"));
        assertTrue(AutomationCategories.isHybridOnly("target_soc"));
        assertEquals("target_soc", SignalMqttMap.forId("targetSoc"));

        VehicleControlCatalog.ControlEntity entity = VehicleControlCatalog.get("target_soc");
        assertNotNull(entity);
        assertEquals("target_soc", entity.stateKey);
        assertEquals(15.0, entity.min, 0.0);
        assertEquals(70.0, entity.max, 0.0);
        assertNull(entity.toAction(null, "14", null));
        assertNull(entity.toAction(null, "71", null));
        assertTrue(entity.toAction(null, "42", null).command
                instanceof VehicleCommandRouter.SocTargetPercentCommand);

        VehicleControlAction holdAction =
                (VehicleControlAction) new Actions().getAction("battery_hold");
        EnumType holdModes = (EnumType) holdAction.getVariables().get(0);
        assertTrue(holdModes.getOptions().stream()
                .anyMatch(option -> "at_target".equals(option.getId())));
        VehicleControlCatalog.ControlAction savedTarget = VehicleControlCatalog
                .get("battery_hold").toAction(null, "at_target", null);
        assertTrue(savedTarget.command instanceof VehicleCommandRouter.SocHoldToggleCommand);
        assertTrue(((VehicleCommandRouter.SocHoldToggleCommand) savedTarget.command).enabled);

        JSONObject absent = components(new JSONObject());
        assertFalse(absent.has("ctl_target_soc"));
        assertFalse(absent.has("ctl_battery_hold"));
        assertFalse(components(new JSONObject().put("target_soc", 71)).has("ctl_battery_hold"));
        JSONObject present = components(new JSONObject().put("target_soc", 42));
        assertTrue(present.has("target_soc"));
        assertTrue(present.has("ctl_target_soc"));
        assertTrue(present.has("ctl_battery_hold"));
    }

    private static JSONObject components(JSONObject snapshot) throws Exception {
        return new JSONObject(HomeAssistantDiscovery.buildBundle(
                "test-device", null, null, null, "overdrive/test", snapshot,
                Collections.<String>emptySet(), true)).getJSONObject("cmps");
    }
}
