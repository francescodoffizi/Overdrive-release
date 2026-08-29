package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.Automations;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class GenAiAutomationTest {

    @Test
    public void extractsJsonWithoutTrustingProviderFormatting() {
        JSONObject parsed = GenAiAutomation.extractObject(
                "Here is the draft:\n```json\n"
                        + "{\"summary\":\"brace } inside string\",\"automation\":null}"
                        + "\n```");
        assertNotNull(parsed);
        assertEquals("brace } inside string",
                parsed.optString("summary"));
    }

    @Test
    public void forbiddenActionsAreRejectedAtAnyDepth() throws Exception {
        JSONObject safe = new JSONObject()
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "notification")));
        assertFalse(GenAiAutomation.containsForbiddenAction(safe));

        JSONObject nestedShell = new JSONObject()
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "if")
                        .put("childActions", new JSONArray()
                                .put(new JSONObject().put("type", "shell")))));
        assertTrue(GenAiAutomation.containsForbiddenAction(nestedShell));

        JSONObject group = new JSONObject()
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "actionGroup")));
        assertTrue(GenAiAutomation.containsForbiddenAction(group));
    }

    @Test
    public void validatedAutomationsAreForcedToManualMode() throws Exception {
        JSONObject proposed = new JSONObject()
                .put("name", "AI draft")
                .put("triggers", new JSONArray().put(new JSONObject()
                        .put("type", "callState")
                        .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "setVariable")
                        .put("variables", new JSONObject()
                                .put("name", "ai_draft_test")
                                .put("value", "created"))))
                .put("disabled", false);

        Automation validated = GenAiAutomation.validateForSave(proposed);

        assertEquals(Automation.MODE_MANUAL, validated.getMode());
        assertTrue(validated.toJson().optBoolean("disabled"));
        assertTrue(validated.toJson().optBoolean("manualOnly"));
    }

    @Test
    public void clarificationQuestionsPreventPrematureAutoSave()
            throws Exception {
        JSONObject wrapper = new JSONObject()
                .put("summary", "I need one detail.")
                .put("questions", new JSONArray()
                        .put("Which seat should be heated?"))
                .put("automation", new JSONObject()
                        .put("name", "Incomplete"));

        GenAiAutomation.Draft draft =
                GenAiAutomation.parseProviderDraft(wrapper.toString());

        assertTrue(draft.needsInput());
        assertEquals(1, draft.questions.length());
    }

    @Test
    public void structuredAutomationJsonIsParsedBeforeValidation()
            throws Exception {
        JSONObject automation = new JSONObject()
                .put("name", "AI manual action")
                .put("triggers", new JSONArray().put(new JSONObject()
                        .put("type", "callState")
                        .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("type", "setVariable")
                        .put("variables", new JSONObject()
                                .put("name", "ai_structured_test")
                                .put("value", "ok"))))
                .put("disabled", false);
        GenAiAutomation.Draft draft =
                GenAiAutomation.parseProviderDraft(
                        new JSONObject()
                                .put("summary", "Ready")
                                .put("questions", new JSONArray())
                                .put("automationJson",
                                        automation.toString())
                                .toString());

        assertFalse(draft.needsInput());
        assertEquals("AI manual action",
                draft.automation.optString("name"));
    }

    @Test
    public void automationCatalogFitsTheProviderContextBudget() {
        JSONObject context = GenAiAutomation.schemaContext();
        JSONObject catalog = context.optJSONObject("catalog");
        assertNotNull(catalog);
        JSONArray events = catalog.optJSONArray("events");
        JSONArray actions = catalog.optJSONArray("actions");
        assertNotNull(events);
        assertNotNull(actions);
        int length = context.toString().length();
        assertTrue("automation catalog length=" + length
                        + ", events=" + events.toString().length()
                        + ", actions=" + actions.toString().length(),
                length <= 48_000);
    }

    @Test
    public void compactCatalogKeepsEverySupportedEventAndAction() {
        JSONObject catalog = GenAiAutomation.schemaContext()
                .optJSONObject("catalog");
        JSONArray schema = Automations.schemaJson();
        int expectedEvents = 0;
        int expectedActions = 0;
        for (int i = 0; i < schema.length(); i++) {
            JSONObject section = schema.optJSONObject(i);
            if (section == null) continue;
            JSONArray options = section.optJSONArray("options");
            if (options == null) continue;
            if ("conditions".equals(section.optString("id"))) {
                expectedEvents = options.length();
            } else if ("actions".equals(section.optString("id"))) {
                for (int j = 0; j < options.length(); j++) {
                    JSONObject option = options.optJSONObject(j);
                    String id = option == null
                            ? "" : option.optString("id", "")
                                    .toLowerCase();
                    if (!id.contains("shell")
                            && !"actiongroup".equals(id)) {
                        expectedActions++;
                    }
                }
            }
        }

        assertEquals(expectedEvents,
                catalog.optJSONArray("events").length());
        assertEquals(expectedActions,
                catalog.optJSONArray("actions").length());
    }
}
