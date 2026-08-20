package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.overdrive.app.automation.AutomationAction;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Pins the automation snapshots used to remember media volume and SOC. */
public class CaptureVariableActionTest {

    @Test
    public void catalogOffersMediaVolumeAndSocSnapshots() throws Exception {
        Action action = new Actions().getAction("captureVariable");
        assertNotNull(action);

        JSONObject schema = action.toJson();
        assertEquals("captureVariable", schema.getString("id"));
        JSONArray variables = schema.getJSONArray("variables");
        assertEquals("source", variables.getJSONObject(0).getString("id"));
        JSONArray sources = variables.getJSONObject(0).getJSONArray("options");
        assertEquals("mediaVolume", sources.getJSONObject(0).getString("id"));
        assertEquals("batterySoc", sources.getJSONObject(1).getString("id"));
        assertEquals("name", variables.getJSONObject(1).getString("id"));
    }

    @Test
    public void acceptsSocCaptureWithANamedVariable() throws Exception {
        String name = "captured_soc_test";
        Action action = new Actions().getAction("captureVariable");
        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "captureVariable")
                .put("variables", new JSONObject()
                        .put("source", "batterySoc")
                        .put("name", name)));

        assertNotNull(parsed);
        assertEquals("batterySoc", parsed.getVariables().get("source"));
        assertEquals(name, parsed.getVariables().get("name"));
    }

    @Test
    public void volumeActionsAcceptASavedVariableReference() throws Exception {
        Action action = new Actions().getAction("mediaVolume");
        assertNotNull(action);

        JSONObject level = action.toJson().getJSONArray("variables").getJSONObject(0);
        assertEquals("int", level.getString("type"));
        assertEquals(true, level.getBoolean("dynamic"));

        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "mediaVolume")
                .put("variables", new JSONObject().put("level", "${var:captured_volume}")));
        assertNotNull("a saved volume variable must be accepted by the volume action", parsed);
        assertEquals("${var:captured_volume}", parsed.getVariables().get("level"));
    }
}
