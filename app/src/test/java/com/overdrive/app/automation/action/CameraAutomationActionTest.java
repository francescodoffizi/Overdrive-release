package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCategories;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Pins the additive camera automation catalog and the saved-action compatibility contract. */
public class CameraAutomationActionTest {

    @Test
    public void simpleCameraActionsExposeIntentBasedOptions() throws Exception {
        Actions actions = new Actions();

        JSONObject cameraFeed = actionSchema(actions, "showCameraFeed");
        assertOptionIds(variable(cameraFeed, "camera"),
                "all", "front", "rear", "left", "right");
        assertOptionIds(variable(cameraFeed, "target"), "head_unit", "cluster");
        assertOptionIds(variable(cameraFeed, "duration"), "5", "10", "30", "0");

        JSONObject cameraViewSize = actionSchema(actions, "setCameraViewSize");
        JSONObject cameraSize = variable(cameraViewSize, "size");
        assertEquals("int", cameraSize.getString("type"));
        assertEquals(15, cameraSize.getInt("min"));
        assertEquals(90, cameraSize.getInt("max"));
        assertOptionIds(variable(cameraViewSize, "target"), "head_unit", "cluster");

        JSONObject blindSpotSize = actionSchema(actions, "setBlindSpotOverlaySize");
        JSONObject size = variable(blindSpotSize, "size");
        assertEquals("int", size.getString("type"));
        assertEquals(15, size.getInt("min"));
        assertEquals(90, size.getInt("max"));
        assertOptionIds(variable(blindSpotSize, "target"), "head_unit", "cluster");
    }

    @Test
    public void legacyCameraActionStillAcceptsSavedBlindSpotComposite() throws Exception {
        Action legacy = new Actions().getAction("showCameraView");
        assertNotNull("the legacy action id must remain available", legacy);

        JSONObject saved = new JSONObject()
                .put("type", "showCameraView")
                .put("variables", new JSONObject()
                        .put("cam", "side_rear_left")
                        .put("target", "head_unit")
                        .put("size", "45")
                        .put("position", "tr"));

        AutomationAction parsed = legacy.fromJson(saved);
        assertNotNull("an existing composite camera automation must still load", parsed);
        assertEquals("side_rear_left", parsed.getVariables().get("cam"));
        assertEquals("head_unit", parsed.getVariables().get("target"));
        assertEquals("45", parsed.getVariables().get("size"));
        assertEquals("tr", parsed.getVariables().get("position"));

        assertOptionIds(variable(legacy.toJson(), "cam"),
                "all", "front", "rear", "left", "right", "side_rear_left", "side_rear_right");
    }

    @Test
    public void cameraActionsAreGroupedWithoutChangingTheirIds() {
        assertEquals(AutomationCategories.SURVEILLANCE,
                AutomationCategories.forId("showCameraFeed"));
        assertEquals(AutomationCategories.SURVEILLANCE,
                AutomationCategories.forId("setCameraViewSize"));
        assertEquals(AutomationCategories.SURVEILLANCE,
                AutomationCategories.forId("setBlindSpotOverlaySize"));
        assertEquals(AutomationCategories.SURVEILLANCE,
                AutomationCategories.forId("hideCameraView"));
        assertEquals(AutomationCategories.SURVEILLANCE,
                AutomationCategories.forId("blindSpotEnable"));

        assertEquals(AutomationCategories.ADVANCED,
                AutomationCategories.forId("showCameraView"));
        assertEquals(AutomationCategories.ADVANCED,
                AutomationCategories.forId("showBlindSpotCameraFeed"));
        assertEquals(AutomationCategories.ADVANCED,
                AutomationCategories.forId("blindSpotFisheye"));
        assertEquals(AutomationCategories.ADVANCED,
                AutomationCategories.forId("blindSpotFisheyeStep"));
        assertEquals(AutomationCategories.ADVANCED,
                AutomationCategories.forId("blindSpotCameras"));
        assertEquals(AutomationCategories.ADVANCED,
                AutomationCategories.forId("blindSpotRotation"));
    }

    private static JSONObject actionSchema(Actions actions, String id) {
        Action action = actions.getAction(id);
        assertNotNull("missing action " + id, action);
        return action.toJson();
    }

    private static JSONObject variable(JSONObject action, String id) throws Exception {
        JSONArray variables = action.getJSONArray("variables");
        for (int i = 0; i < variables.length(); i++) {
            JSONObject variable = variables.getJSONObject(i);
            if (id.equals(variable.getString("id"))) return variable;
        }
        throw new AssertionError("missing variable " + id);
    }

    private static void assertOptionIds(JSONObject variable, String... expectedIds) throws Exception {
        JSONArray options = variable.getJSONArray("options");
        assertEquals(expectedIds.length, options.length());
        for (int i = 0; i < expectedIds.length; i++) {
            assertEquals(expectedIds[i], options.getJSONObject(i).getString("id"));
        }
    }
}
