package com.overdrive.app.automation.action;

import com.overdrive.app.automation.AutomationAction;
import com.overdrive.app.automation.type.Type;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseAction implements Action {
    protected static final DaemonLogger logger = DaemonLogger.getInstance("AutomationAction");

    /**
     * A string id for this action
     *
     * @return String representing this action
     */
    public abstract String getType();

    /**
     * The description for this action
     * Should be translated using the language files
     *
     * @return The translated description for this action
     */
    public abstract String getDescription();

    /**
     * The variables for this action
     * Can be an empty list for no variables
     *
     * @return The variables for this action
     */
    public abstract List<Type> getVariables();

    /**
     * Create a JSON object with the fields required for the frontend to display
     * This can be overridden if needed
     *
     * @return JSON representation of this action
     */
    public JSONObject toJson() {
        JSONObject json = getLabel().toJson();

        try {
            json.put("type", getType());
            JSONArray variables = new JSONArray();
            for (Type variable : getVariables()) {
                variables.put(variable.toJson());
            }
            json.put("variables", variables);
            json.put("description", getDescription());
            // Tell the web form this action carries nested child action lists (loop
            // body / if-then), so it renders a nested action editor. Emitted only for
            // control-flow actions; ordinary actions omit it (unchanged schema).
            if (hasChildActions()) {
                json.put("hasChildActions", true);
                // "if" additionally has an else branch; "loop" does not.
                if ("if".equals(getType())) json.put("hasElseActions", true);
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }

        return json;
    }

    /**
     * An automation action with the id of this instance and the variables needed for the trigger
     * This can be overridden if needed
     *
     * @param input The JSON passed from the frontend
     * @return An AutomationAction that can later be used to trigger this action
     */
    public AutomationAction fromJson(JSONObject input) {
        try {
            String type = getLabel().getId();
            Map<String, Object> variables = new HashMap<>();
            JSONObject variablesJson = input.optJSONObject("variables");
            for (Type variable : getVariables()) {
                String key = variable.getLabel().getId();
                // An ABSENT key is not always the same as an invalid one. variablesJson.get()
                // throws when the key is missing, and the catch below turns that into null —
                // which makes Automation.parseActions reject the WHOLE automation, losing its
                // triggers, conditions and every other action, and (once any later save rewrites
                // the file) erasing it from disk. That is what happens to an automation saved
                // BEFORE a new variable was added to an existing action: the stored JSON cannot
                // possibly contain a key that did not exist when it was written.
                //
                // But defaulting EVERY absent variable would be dangerous, not just lenient: a
                // malformed action would start DOING something instead of being rejected, and
                // several first-options are physical operations (tailgate → "open", mirror_fold →
                // "on", adas_aeb → "on", child_lock/esp_control → "off"). So only variables
                // RETROFITTED onto a pre-existing action default; everything else still rejects.
                if (variablesJson == null || !variablesJson.has(key)) {
                    Object fallback = RETROFITTED_DEFAULTS.get(key);
                    if (fallback == null || !variable.isValid(fallback)) return null;
                    variables.put(key, fallback);
                    continue;
                }
                Object value = variablesJson.get(key);
                if (variable.isValid(value)) {
                    variables.put(key, value);
                } else {
                    return null;
                }
            }

            return new AutomationAction(type, variables);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Variables ADDED to an already-shipped action, mapped to the value that reproduces the
     * behaviour the action had before the variable existed.
     *
     * <p>An automation saved before the variable was introduced cannot contain the key, so
     * without an entry here it would fail to parse and the whole automation would be discarded.
     * This is deliberately an explicit allowlist rather than "default any absent variable":
     * silently substituting a value turns a malformed action into one that ACTS, and many
     * first-options are real vehicle operations.
     *
     * <p>{@code zone} — the ambient colour/brightness actions gained a zone selector. "both"
     * means all zones, which is exactly what those actions did when they had no zone concept
     * (and matches the daemon's own {@code optString("zone", "both")} default).
     *
     * <p>{@code autoOffMinutes} — the AC power action ({@code setAc}) gained an optional "switch off
     * again after N minutes" window (the standalone {@code acAutoOff} action declares the same
     * variable, but it is new, so no saved automation can be missing the key there). "0" means stay on indefinitely, which is exactly what those
     * actions did before the window existed (and what the daemon's own
     * {@code optInt("autoOffMinutes", 0)} default does). Stored as the STRING "0" because the
     * variable is an EnumType, whose isValid() matches option ids — an Integer 0 would fail
     * validation and drop the automation, the very thing this map exists to prevent.
     *
     * <p>Only add a key here when the variable was retrofitted onto an existing action AND the
     * value provably reproduces the old behaviour.
     */
    private static final Map<String, Object> RETROFITTED_DEFAULTS =
            Map.of("zone", "both", "autoOffMinutes", "0");
}
