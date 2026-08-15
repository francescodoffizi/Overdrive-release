package com.overdrive.app.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/** Persistence and downgrade-safety coverage for manual-only execution mode. */
public class AutomationManualModeTest {

    @BeforeClass
    public static void muteAndroidLog() {
        com.overdrive.app.logging.DaemonLogger.Config cfg =
                new com.overdrive.app.logging.DaemonLogger.Config();
        cfg.enableConsoleLog = false;
        cfg.enableFileLog = false;
        cfg.enableStdoutLog = true;
        com.overdrive.app.logging.DaemonLogger.configure(cfg);
    }

    @Test
    public void legacyAutomaticAndDisabledJsonKeepTheirExactModes() throws Exception {
        Automation automatic = Automation.fromJson(validJson().put("disabled", false));
        assertEquals(Automation.MODE_AUTOMATIC, automatic.getMode());
        assertFalse(automatic.isDisabled());
        assertTrue(automatic.allowsExplicitRun());
        assertFalse(automatic.toJson().has("manualOnly"));

        Automation disabled = Automation.fromJson(validJson().put("disabled", true));
        assertEquals(Automation.MODE_DISABLED, disabled.getMode());
        assertTrue(disabled.isDisabled());
        assertTrue(disabled.isFullyDisabled());
        assertFalse(disabled.allowsExplicitRun());
        assertFalse(disabled.toJson().has("manualOnly"));
    }

    @Test
    public void manualModeRoundTripsAsDisabledForOlderBuilds() throws Exception {
        Automation manual = Automation.fromJson(validJson()
                .put("disabled", true)
                .put("manualOnly", true));

        assertEquals(Automation.MODE_MANUAL, manual.getMode());
        assertTrue("manual mode must stay outside autonomous polling", manual.isDisabled());
        assertTrue(manual.isManualOnly());
        assertFalse(manual.isFullyDisabled());
        assertTrue(manual.allowsExplicitRun());

        JSONObject stored = manual.toJson();
        assertTrue("older builds must see a manual rule as disabled",
                stored.getBoolean("disabled"));
        assertTrue(stored.getBoolean("manualOnly"));
    }

    @Test
    public void manualMarkerWinsOverConflictingEnabledFlag() throws Exception {
        Automation manual = Automation.fromJson(validJson()
                .put("disabled", false)
                .put("manualOnly", true));

        assertEquals("safe interpretation must never arm a manual rule",
                Automation.MODE_MANUAL, manual.getMode());
        assertTrue(manual.toJson().getBoolean("disabled"));
    }

    @Test
    public void legacyEnableDisableCallsClearManualMode() throws Exception {
        Automation automation = Automation.fromJson(validJson());
        automation.setMode(Automation.MODE_MANUAL);
        assertTrue(automation.isManualOnly());

        automation.setDisabled(true);
        assertEquals(Automation.MODE_DISABLED, automation.getMode());
        assertFalse(automation.toJson().has("manualOnly"));

        automation.setMode(Automation.MODE_MANUAL);
        automation.setDisabled(false);
        assertEquals(Automation.MODE_AUTOMATIC, automation.getMode());
        assertFalse(automation.isDisabled());
        assertFalse(automation.toJson().has("manualOnly"));
    }

    private static JSONObject validJson() throws Exception {
        return new JSONObject()
                .put("triggers", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "callState")
                                .put("variables", new JSONObject())))
                .put("conditions", new JSONArray())
                .put("delay", 0)
                .put("actions", new JSONArray()
                        .put(new JSONObject()
                                .put("type", "setVariable")
                                .put("variables", new JSONObject()
                                        .put("name", "manual_mode_test")
                                        .put("value", "fired"))));
    }
}
