package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.action.Actions;
import com.overdrive.app.automation.action.VehicleControlAction;
import com.overdrive.app.byd.routing.VehicleCommandRouter.MirrorAutoFollowUpCommand;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Pins the persistent OEM mirror follow-up setting exposed to controls and automations. */
public class MirrorAutoFollowUpControlTest {

    @Test
    public void catalogBuildsPersistentMirrorFollowUpCommands() {
        VehicleControlCatalog.ControlEntity entity =
                VehicleControlCatalog.get("mirror_auto_follow_up");
        assertNotNull("OEM auto mirror follow-up must be controllable", entity);

        VehicleControlCatalog.ControlAction on = entity.toAction(null, "on", null);
        assertNotNull(on);
        assertTrue(on.command instanceof MirrorAutoFollowUpCommand);
        assertTrue(((MirrorAutoFollowUpCommand) on.command).enabled);

        VehicleControlCatalog.ControlAction off = entity.toAction(null, "off", null);
        assertNotNull(off);
        assertTrue(off.command instanceof MirrorAutoFollowUpCommand);
        assertEquals(false, ((MirrorAutoFollowUpCommand) off.command).enabled);
    }

    @Test
    public void automationExposesEnableAndDisableForMirrorFollowUp() throws Exception {
        VehicleControlAction action = (VehicleControlAction) new Actions()
                .getAction("mirror_auto_follow_up");
        assertNotNull(action);

        JSONObject schema = action.toJson();
        assertEquals("mirror_auto_follow_up", schema.getString("id"));
        JSONArray options = schema.getJSONArray("variables")
                .getJSONObject(0)
                .getJSONArray("options");
        assertEquals("on", options.getJSONObject(0).getString("id"));
        assertEquals("off", options.getJSONObject(1).getString("id"));
    }
}
