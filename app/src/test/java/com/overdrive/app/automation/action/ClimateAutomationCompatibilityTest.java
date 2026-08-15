package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.overdrive.app.automation.AutomationAction;

import org.json.JSONObject;
import org.junit.Test;

/** Regression coverage for the numeric climate-zone schema migration. */
public class ClimateAutomationCompatibilityTest {

    @Test
    public void absoluteTemperatureAcceptsBothSdkZone() throws Exception {
        Action action = new Actions().getAction("setAcTemp");

        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22).put("zone", "0")));

        assertNotNull(parsed);
        assertEquals("0", parsed.getVariables().get("zone"));
    }

    @Test
    public void legacyClimateBothAndMissingZoneMigrateToSdkZoneZero() throws Exception {
        Action action = new Actions().getAction("setAcTemp");

        AutomationAction legacyBoth = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22).put("zone", "both")));
        AutomationAction legacyMissing = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22)));

        assertNotNull(legacyBoth);
        assertNotNull(legacyMissing);
        assertEquals("0", legacyBoth.getVariables().get("zone"));
        assertEquals("0", legacyMissing.getVariables().get("zone"));
    }

    @Test
    public void migrationDoesNotAcceptOtherClimateZoneTokens() throws Exception {
        Action action = new Actions().getAction("setAcTemp");

        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "setAcTemp")
                .put("variables", new JSONObject().put("temperature", 22).put("zone", "all")));

        assertNull(parsed);
    }

    @Test
    public void ambientZoneFallbackRemainsBothForSavedActions() throws Exception {
        Action action = new Actions().getAction("ambientPower");

        AutomationAction parsed = action.fromJson(new JSONObject()
                .put("type", "ambientPower")
                .put("variables", new JSONObject().put("state", "100")));

        assertNotNull(parsed);
        assertEquals("both", parsed.getVariables().get("zone"));
    }
}
