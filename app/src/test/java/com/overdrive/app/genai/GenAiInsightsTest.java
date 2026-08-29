package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.AutomationCondition;
import com.overdrive.app.automation.condition.BydEvent;

import org.junit.Test;

public class GenAiInsightsTest {

    @Test
    public void weeklyPresetUsesExistingAutomationClockAndAction() {
        GenAiConfig config = new GenAiConfig(
                true, GenAiConfig.PROVIDER_OPENAI,
                "https://api.openai.com", "text-model", "",
                "key", 1200,
                GenAiConfig.INSIGHT_SCHEDULE_WEEKLY,
                20, 45, 7, GenAiContext.ROADSENSE, true, true);

        Automation automation =
                GenAiInsights.buildScheduledAutomation(config);
        assertFalse(automation.isDisabled());
        assertEquals(2, automation.getConditions().size());

        AutomationCondition time = automation.getConditions().get(0);
        assertEquals(BydEvent.TIME, time.getEventData());
        assertEquals(20 * 60 + 45, time.getValue());

        AutomationCondition day = automation.getConditions().get(1);
        assertEquals(BydEvent.DAY, day.getEventData());
        assertEquals("sunday", day.getValue());

        AutomationAction action = automation.getActions().get(0);
        assertEquals("genAiInsight", action.getType());
        assertEquals(GenAiContext.ROADSENSE,
                action.getVariables().get("mode"));
        assertEquals(GenAiInsights.DELIVERY_NOTIFICATION,
                action.getVariables().get("delivery"));

        assertNull(GenAiInsights.buildScheduledAutomation(
                new GenAiConfig(
                        false, GenAiConfig.PROVIDER_OPENAI,
                        "https://api.openai.com", "", "", "",
                        1200)));
    }

    @Test
    public void insightOutputIsStructuredAndLanguageIsNormalized() {
        assertEquals("de",
                GenAiInsights.normalizeLanguage("de-DE"));
        assertEquals("zh-CN",
                GenAiInsights.normalizeLanguage("zh-Hans"));

        org.json.JSONObject schema =
                GenAiInsights.responseSchema();
        assertTrue(schema.optBoolean(
                "additionalProperties") == false);
        org.json.JSONObject properties =
                schema.optJSONObject("properties");
        assertTrue(properties != null
                && properties.has("title")
                && properties.has("text"));
    }
}
