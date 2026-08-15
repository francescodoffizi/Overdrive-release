package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Ensures fullscreen video keeps the media-only audio contract. */
public class VideoAutomationActionTest {

    @Test
    public void playVideoDoesNotOfferAnUnsupportedAudioChannel() throws Exception {
        Action action = new Actions().getAction("playVideo");
        assertNotNull(action);

        JSONArray variables = action.toJson().getJSONArray("variables");
        assertEquals(2, variables.length());
        assertEquals("name", variables.getJSONObject(0).getString("id"));
        assertEquals("loop", variables.getJSONObject(1).getString("id"));
    }

    @Test
    public void legacyVideoChannelIsIgnoredWhenAnAutomationLoads() throws Exception {
        Action action = new Actions().getAction("playVideo");
        assertNotNull(action);

        com.overdrive.app.automation.AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "playVideo")
                .put("variables", new JSONObject()
                        .put("name", "clip.mp4")
                        .put("channel", "navigation")
                        .put("loop", "false")));

        assertNotNull(parsed);
        assertFalse(parsed.getVariables().containsKey("channel"));
    }
}
