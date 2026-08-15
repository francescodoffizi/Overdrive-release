package com.overdrive.app.automation.action;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.EnumType;
import com.overdrive.app.automation.type.IntType;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.automation.value.Label;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Pins {@link BaseAction#fromJson}'s absent-variable rule, which decides whether an
 * ALREADY-SAVED automation keeps working or is destroyed.
 *
 * <p><b>The stakes.</b> A null from {@code fromJson} makes {@code Automation.parseActions} reject
 * the whole automation; the load path then drops it and the next save rewrites the file without
 * it — permanent loss of the user's rule, its triggers and its other actions. That is what
 * happened when a {@code zone} variable was added to the already-shipped ambient colour/brightness
 * actions: stored JSON cannot contain a key that did not exist when it was written.
 *
 * <p>But blanket-defaulting every absent variable is the opposite hazard: a malformed action would
 * begin ACTING rather than being rejected, and many actions' first option is a physical operation
 * (tailgate → open, mirror_fold → on). Hence the explicit retrofit allowlist, which these tests
 * pin from both directions.
 */
public class RetrofittedVariableDefaultTest {

    /** Minimal concrete action with a caller-chosen variable list. */
    private static class FakeAction extends BaseAction {
        private final String actionId;
        private final List<Type> variables;
        FakeAction(Type... vars) { this("fake", vars); }
        FakeAction(String actionId, Type... vars) {
            this.actionId = actionId;
            this.variables = Arrays.asList(vars);
        }
        @Override public String getType() { return "fake"; }
        @Override public String getDescription() { return "fake"; }
        @Override public List<Type> getVariables() { return variables; }
        @Override public Label getLabel() { return new Label(actionId, "fake"); }
        @Override public void trigger(AutomationAction automationAction) { }
    }

    /** The zone selector as the real ambient actions declare it — "both" first. */
    private static EnumType zoneType() {
        return new EnumType(new Label("zone", "automation.area"),
                new Label("both", "automation.area_all"),
                new Label("front", "automation.ambient_zone_front"),
                new Label("rear", "automation.ambient_zone_rear"));
    }

    private static JSONObject action(String variablesJson) throws org.json.JSONException {
        return new JSONObject("{\"type\":\"fake\",\"variables\":" + variablesJson + "}");
    }

    /**
     * THE BACK-COMPAT CASE: a retrofitted variable is absent from stored JSON, so it takes the
     * value that reproduces the behaviour the action had before the variable existed.
     */
    @Test
    public void absentRetrofittedVariableTakesTheBackCompatDefault() throws Exception {
        AutomationAction a = new FakeAction("ambientPower", zoneType()).fromJson(action("{}"));

        assertNotNull("an automation saved before this variable existed must still parse", a);
        assertEquals("both", a.getVariables().get("zone"));
    }

    @Test
    public void legacyAcPowerRuleGetsTheOriginalRemoteTargetAndDuration() throws Exception {
        EnumType duration = new EnumType(new Label("remoteDurationMinutes", "duration"),
                new Label("10", "10"), new Label("15", "15"), new Label("20", "20"),
                new Label("25", "25"), new Label("30", "30"));
        AutomationAction a = new FakeAction("setAc",
                new IntType(new Label("temp", "automation.temperature"), 15, 33),
                duration,
                new EnumType(new Label("autoOffMinutes", "automation.ac_auto_off"),
                        new Label("0", "automation.ac_auto_off_never")))
                .fromJson(action("{}"));

        assertNotNull(a);
        assertEquals(Integer.valueOf(22), a.getVariables().get("temp"));
        assertEquals("20", a.getVariables().get("remoteDurationMinutes"));
        assertEquals("0", a.getVariables().get("autoOffMinutes"));
    }

    /** A stored value always wins over the default. */
    @Test
    public void storedValueIsNeverOverwrittenByTheDefault() throws Exception {
        AutomationAction a = new FakeAction(zoneType()).fromJson(action("{\"zone\":\"rear\"}"));

        assertNotNull(a);
        assertEquals("rear", a.getVariables().get("zone"));
    }

    /** A present-but-invalid value still rejects — the default must not paper over corruption. */
    @Test
    public void presentButInvalidValueStillRejects() throws Exception {
        assertNull(new FakeAction(zoneType()).fromJson(action("{\"zone\":\"boot\"}")));
    }

    /**
     * THE SAFETY CASE: an enum variable that was NOT retrofitted must still reject when absent.
     * Defaulting it would let a malformed automation actuate the vehicle — e.g. a tailgate action
     * whose first option is "open".
     */
    @Test
    public void absentNonRetrofittedEnumStillRejects() throws Exception {
        EnumType tailgateLike = new EnumType(new Label("action", "automation.action"),
                new Label("open", "automation.open"),
                new Label("close", "automation.close"));

        assertNull("a missing always-required variable must not default to a physical operation",
                new FakeAction(tailgateLike).fromJson(action("{}")));
    }

    /** A non-enum variable has no principled default, so an absent one still rejects. */
    @Test
    public void absentNonEnumVariableStillRejects() throws Exception {
        assertNull(new FakeAction(new IntType(new Label("percent", "automation.percent"), 0, 100))
                .fromJson(action("{}")));
    }

    /** A wholly absent "variables" object behaves like an empty one, not a crash. */
    @Test
    public void missingVariablesObjectIsHandled() throws Exception {
        AutomationAction a = new FakeAction("ambientPower", zoneType())
                .fromJson(new JSONObject("{\"type\":\"fake\"}"));

        assertNotNull("the retrofitted default still applies with no variables object at all", a);
        assertEquals("both", a.getVariables().get("zone"));

        assertNull("...but a non-retrofitted variable still rejects",
                new FakeAction(new EnumType(new Label("state", "automation.state"),
                        new Label("on", "automation.on"), new Label("off", "automation.off")))
                        .fromJson(new JSONObject("{\"type\":\"fake\"}")));
    }

    /**
     * The default is taken from DECLARATION order, so it is the one the UI offers first. Pins the
     * ordered-set contract: were options held in a plain HashSet anywhere, this default could
     * silently become "front" or "rear".
     */
    @Test
    public void defaultFollowsDeclarationOrder() throws Exception {
        EnumType reordered = new EnumType(new Label("zone", "automation.area"),
                new Label("front", "automation.ambient_zone_front"),
                new Label("both", "automation.area_all"));

        AutomationAction a = new FakeAction("ambientPower", reordered).fromJson(action("{}"));
        assertNotNull(a);
        assertEquals("the allowlisted value is used, not merely whichever option is first",
                "both", a.getVariables().get("zone"));
    }
}
