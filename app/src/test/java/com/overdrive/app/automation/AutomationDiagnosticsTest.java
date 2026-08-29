package com.overdrive.app.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.StringValue;
import com.overdrive.app.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AutomationDiagnosticsTest {

    @Test
    public void selectedRuleIncludesConditionsTimingAndRedactedShellHelp()
            throws Exception {
        EventData gear = new EventData("gear");
        Automation automation = new Automation(
                List.of(gear),
                List.of(new AutomationCondition(gear, "eq", "P")),
                3,
                List.of(
                        new AutomationAction(
                                "pause", Map.of("milliseconds", 1500)),
                        new AutomationAction(
                                "shell", Map.of(
                                        "command",
                                        "curl https://private.example --token secret-value"))),
                false);
        automation.setName("Night parking");

        Map<String, Automation> definitions = new LinkedHashMap<>();
        definitions.put("rule-123", automation);
        Map<EventData, Value> state =
                Map.of(gear, new StringValue("P"));

        JSONObject result = AutomationDiagnostics.build(
                definitions, state,
                "Why did Night parking shell command not run?");
        JSONObject detail = result.getJSONArray("diagnosedAutomations")
                .getJSONObject(0);

        assertEquals("Night parking", detail.getString("name"));
        assertEquals(3, detail.getInt("delaySeconds"));
        assertTrue(detail.getJSONObject("conditionTree")
                .getBoolean("metNow"));
        JSONObject condition = detail.getJSONObject("conditionTree")
                .getJSONArray("conditions").getJSONObject(0);
        assertEquals("P", condition.getString("current"));
        assertTrue(condition.getBoolean("metNow"));

        JSONArray actions = detail.getJSONArray("primaryActions");
        assertEquals(1500, actions.getJSONObject(0)
                .getJSONObject("parameters").getInt("milliseconds"));
        String preview = actions.getJSONObject(1)
                .getString("commandPreview");
        assertTrue(preview.contains("[REDACTED_URL]"));
        assertTrue(preview.contains("[REDACTED]"));
        assertFalse(actions.getJSONObject(1).has("variables"));
        assertFalse(result.getJSONObject("shellActionHelp")
                .getBoolean("executionAllowed"));
    }

    @Test
    public void matchedRuleDoesNotExposeShellWithoutExplicitShellRequest()
            throws Exception {
        EventData gear = new EventData("gear");
        Automation automation = new Automation(
                List.of(gear),
                List.of(),
                0,
                List.of(new AutomationAction(
                        "shell", Map.of("command", "echo private"))),
                false);
        automation.setName("Night parking");

        JSONObject result = AutomationDiagnostics.build(
                Map.of("rule-123", automation),
                Map.of(gear, new StringValue("P")),
                "Why did Night parking not run?");

        assertFalse(result.getJSONArray("diagnosedAutomations")
                .getJSONObject(0).getJSONArray("primaryActions")
                .getJSONObject(0).has("commandPreview"));
    }
}
