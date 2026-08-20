package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.automation.action.SetVariableAction;
import com.overdrive.app.automation.value.Value;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end guard for the two explicit surfaces requested by manual-only mode:
 * Run now and Key Mapping. Also pins autonomous silence and fully-disabled behavior.
 */
public class ManualAutomationExecutionTest {

    @BeforeClass
    public static void muteAndroidLog() {
        com.overdrive.app.logging.DaemonLogger.Config cfg =
                new com.overdrive.app.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(cfg);
    }

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String automationId = "manual-exec-" + suffix;
    private final String triggerVariable = "manual_trigger_" + suffix;
    private final String outputVariable = "manual_out_" + suffix;
    private boolean registered;

    @After
    public void cleanup() {
        if (registered) Automations.deleteAutomation(automationId);
    }

    @Test
    public void manualOnlyRunsFromTestAndKeymapButNeverFromEvents() throws Exception {
        assertTrue(Automations.updateAutomation(automationId, manualAutomationJson()));
        registered = true;

        JSONObject stored = Automations.toJson().getJSONObject(automationId);
        assertTrue(stored.getBoolean("disabled"));
        assertTrue(stored.getBoolean("manualOnly"));

        // Ordinary event delivery must never execute a manual-only rule.
        forceTrigger("manual-a");
        forceTrigger("manual-b");
        Thread.sleep(400);
        assertFalse("manual-only automation fired autonomously",
                "fired".equals(outputText()));

        // Run now uses the explicit worker and intentionally does not count as a real run.
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle(
                "POST", "/api/automations/test/" + automationId, "", testOut));
        assertTrue(new String(testOut.toByteArray(), StandardCharsets.UTF_8)
                .contains("\"success\":true"));
        assertEquals("fired", awaitOutput("fired", 5000));
        assertEquals(0L, Automations.toJson().getJSONObject(automationId)
                .optLong("triggerCount", 0L));

        // A key mapping is a real manual invocation: same primary branch, with stats.
        forceOutput("reset");
        JSONObject keyResult = KeymapApiHandler.runBoundAction(new JSONObject()
                .put("kind", "automation")
                .put("id", automationId));
        assertTrue(keyResult.optBoolean("success", false));
        assertEquals("fired", awaitOutput("fired", 5000));
        assertEquals(1L, Automations.toJson().getJSONObject(automationId)
                .getLong("triggerCount"));

        // Fully disabled remains an accepted no-op, preserving the legacy API contract.
        setModeThroughApi(Automation.MODE_DISABLED);
        forceOutput("reset");
        ByteArrayOutputStream disabledOut = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle(
                "POST", "/api/automations/test/" + automationId, "", disabledOut));
        assertTrue(new String(disabledOut.toByteArray(), StandardCharsets.UTF_8)
                .contains("\"success\":true"));
        Thread.sleep(600);
        assertEquals("reset", outputText());

        // Re-arming as automatic keeps the pre-existing event behavior intact.
        setModeThroughApi(Automation.MODE_AUTOMATIC);
        Automations.update(
                SetVariableAction.variableEvent(triggerVariable), "automatic-" + suffix);
        assertEquals("fired", awaitOutput("fired", 5000));
    }

    private JSONObject manualAutomationJson() throws Exception {
        return new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "variable")
                                .put("variables", new JSONObject()
                                        .put("name", triggerVariable))))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", outputVariable)
                                        .put("value", "fired"))))
                .put("name", "manual execution test")
                .put("disabled", true)
                .put("manualOnly", true);
    }

    private void forceOutput(String value) {
        Automations.updateObservedEdge(SetVariableAction.variableEvent(outputVariable), value);
    }

    private void forceTrigger(String value) {
        Automations.updateObservedEdge(SetVariableAction.variableEvent(triggerVariable), value);
    }

    private void setModeThroughApi(String mode) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(AutomationApiHandler.handle(
                "POST", "/api/automations/mode/" + automationId,
                new JSONObject().put("mode", mode).toString(), out));
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8)
                .contains("\"success\":true"));
    }

    private String outputText() {
        Value value = Automations.getStateValue(SetVariableAction.variableEvent(outputVariable));
        return value == null ? null : value.toString();
    }

    private String awaitOutput(String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String value = outputText();
            if (expected.equals(value)) return value;
            Thread.sleep(50);
        }
        return outputText();
    }
}
