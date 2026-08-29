package com.overdrive.app.genai;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.byd.BydDataCollector;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local allowlist and validator for assistant-proposed physical actions. */
public final class GenAiAction {

    public static final String MODE = "vehicle_action";
    public static final String TOOL_NAME = "propose_overdrive_action";

    private static final String NONE = "none";
    private static final String CLIMATE = "climate_temperature";
    private static final String SUNSHADE = "sunshade";
    private static final String AUTOMATION = "run_automation";
    private static final int MAX_REPLY_CHARS = 800;

    private GenAiAction() {
    }

    public static JSONObject context() {
        JSONArray catalog = Automations.listForPicker(null);
        JSONObject stored = Automations.toJson();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.optJSONObject(i);
            if (item == null) continue;
            JSONObject automation = stored.optJSONObject(
                    item.optString("id", ""));
            try {
                item.put("mode", automationMode(automation));
            } catch (Exception ignored) {
            }
        }
        try {
            return new JSONObject()
                    .put("allowedActions", new JSONArray()
                            .put(CLIMATE)
                            .put(SUNSHADE)
                            .put(AUTOMATION))
                    .put("temperatureRangeC", new JSONArray()
                            .put(BydDataCollector.AC_SETPOINT_MIN_C)
                            .put(BydDataCollector.AC_SETPOINT_MAX_C))
                    .put("automationCatalog", catalog)
                    .put("safety",
                            "Return one proposal only. Never claim execution. "
                            + "Missing or ambiguous details require a clarification.");
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static JSONObject responseSchema() {
        try {
            JSONObject properties = actionProperties()
                    .put("reply", new JSONObject()
                            .put("type", "string")
                            .put("maxLength", MAX_REPLY_CHARS))
                    .put("needsInput", new JSONObject()
                            .put("type", "boolean"));
            return new JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put("required", new JSONArray()
                            .put("reply")
                            .put("needsInput")
                            .put("actionType")
                            .put("temperatureC")
                            .put("zone")
                            .put("operation")
                            .put("automationId")
                            .put("automationName"))
                    .put("properties", properties);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static JSONObject openAiTool() {
        try {
            return new JSONObject()
                    .put("type", "function")
                    .put("name", TOOL_NAME)
                    .put("description", toolDescription())
                    .put("parameters", toolParameters());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static JSONObject geminiTool() {
        try {
            return new JSONObject()
                    .put("name", TOOL_NAME)
                    .put("description", toolDescription())
                    .put("parameters", toolParameters());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static String realtimeInstructions() {
        return "For an explicit request to set cabin temperature, open or close "
                + "the sunshade, or run one saved automation, call "
                + TOOL_NAME + " once. The tool only creates a confirmation card; "
                + "tell the user to confirm on screen and never claim it ran. "
                + "Ask a question instead of calling the tool when any detail is "
                + "missing or ambiguous. For an automation, pass the exact name "
                + "spoken by the user; OverDrive resolves it locally and rejects "
                + "missing or ambiguous matches.";
    }

    public static Proposal parseProviderProposal(String raw)
            throws ValidationException {
        JSONObject wrapper = GenAiAutomation.extractObject(raw);
        if (wrapper == null) {
            throw new ValidationException(
                    "The provider did not return a valid action proposal.");
        }
        String reply = clamp(
                wrapper.optString("reply", "").trim(),
                MAX_REPLY_CHARS);
        String type = wrapper.optString("actionType", NONE).trim();
        if (NONE.equals(type)) {
            return new Proposal(
                    reply.isEmpty()
                            ? "What detail should I use for that action?"
                            : reply,
                    true, null);
        }
        JSONObject action = validateActionFields(wrapper);
        if (reply.isEmpty()) reply = confirmationText(action);
        return new Proposal(reply, false, action);
    }

    public static Proposal parseToolArguments(JSONObject arguments)
            throws ValidationException {
        JSONObject action = validateActionFields(arguments);
        return new Proposal(confirmationText(action), false, action);
    }

    public static JSONObject validateClientAction(JSONObject action)
            throws ValidationException {
        if (action == null) {
            throw new ValidationException("The proposed action is missing.");
        }
        JSONObject fields = new JSONObject();
        try {
            fields.put("actionType", action.optString("type", ""));
            fields.put("temperatureC", action.opt("temperatureC"));
            fields.put("zone", action.opt("zone"));
            fields.put("operation", action.optString("operation", ""));
            fields.put("automationId",
                    action.optString("automationId", ""));
            fields.put("automationName",
                    action.optString("automationName", ""));
        } catch (Exception e) {
            throw new ValidationException(
                    "The proposed action could not be read.");
        }
        return validateActionFields(fields);
    }

    private static JSONObject validateActionFields(JSONObject fields)
            throws ValidationException {
        if (fields == null) {
            throw new ValidationException("The action is missing.");
        }
        String type = fields.optString("actionType", "").trim();
        try {
            if (CLIMATE.equals(type)) {
                double temperature = fields.optDouble(
                        "temperatureC", Double.NaN);
                int zone = fields.optInt("zone", -1);
                if (Double.isNaN(temperature)
                        || Double.isInfinite(temperature)
                        || temperature < BydDataCollector.AC_SETPOINT_MIN_C
                        || temperature > BydDataCollector.AC_SETPOINT_MAX_C
                        || zone < 0 || zone > 2) {
                    throw new ValidationException(
                            "Choose a valid climate zone and a temperature from "
                                    + BydDataCollector.AC_SETPOINT_MIN_C
                                    + " to "
                                    + BydDataCollector.AC_SETPOINT_MAX_C
                                    + " °C.");
                }
                return new JSONObject()
                        .put("type", CLIMATE)
                        .put("temperatureC", temperature)
                        .put("zone", zone);
            }
            if (SUNSHADE.equals(type)) {
                String operation = fields.optString(
                        "operation", "").trim().toLowerCase();
                if (!"open".equals(operation)
                        && !"close".equals(operation)) {
                    throw new ValidationException(
                            "Choose whether to open or close the sunshade.");
                }
                return new JSONObject()
                        .put("type", SUNSHADE)
                        .put("operation", operation);
            }
            if (AUTOMATION.equals(type)) {
                String id = fields.optString(
                        "automationId", "").trim();
                if (id.isEmpty()) {
                    id = automationIdForName(fields.optString(
                            "automationName", ""));
                }
                if (id.isEmpty() || !Automations.exists(id)) {
                    throw new ValidationException(
                            "Choose one automation that is currently saved.");
                }
                JSONObject saved = Automations.toJson()
                        .optJSONObject(id);
                if (Automation.MODE_DISABLED.equals(
                        automationMode(saved))) {
                    throw new ValidationException(
                            "That automation is fully disabled. Enable or switch "
                                    + "it to manual mode before running it.");
                }
                return new JSONObject()
                        .put("type", AUTOMATION)
                        .put("automationId", id)
                        .put("automationName", automationName(id));
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException(
                    "The proposed action could not be validated.");
        }
        throw new ValidationException(
                "Only climate temperature, sunshade, and saved automation "
                        + "actions are supported.");
    }

    private static JSONObject toolParameters() throws Exception {
        return new JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", new JSONArray()
                        .put("actionType")
                        .put("temperatureC")
                        .put("zone")
                        .put("operation")
                        .put("automationId")
                        .put("automationName"))
                .put("properties", actionProperties());
    }

    private static JSONObject actionProperties() throws Exception {
        return new JSONObject()
                .put("actionType", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray()
                                .put(NONE)
                                .put(CLIMATE)
                                .put(SUNSHADE)
                                .put(AUTOMATION)))
                .put("temperatureC", new JSONObject()
                        .put("type", "number")
                        .put("minimum", -1)
                        .put("maximum",
                                BydDataCollector.AC_SETPOINT_MAX_C))
                .put("zone", new JSONObject()
                        .put("type", "integer")
                        .put("minimum", -1)
                        .put("maximum", 2))
                .put("operation", new JSONObject()
                        .put("type", "string")
                        .put("enum", new JSONArray()
                                .put(NONE)
                                .put("open")
                                .put("close")))
                .put("automationId", new JSONObject()
                        .put("type", "string")
                        .put("maxLength", 200))
                .put("automationName", new JSONObject()
                        .put("type", "string")
                        .put("maxLength", 120));
    }

    private static String toolDescription() {
        return "Prepare exactly one user-confirmed OverDrive action. Use "
                + "climate_temperature, sunshade, or run_automation only. "
                + "For unused fields send -1, none, or an empty string. "
                + "Never call this for questions or when details are ambiguous.";
    }

    private static String confirmationText(JSONObject action) {
        String type = action.optString("type", "");
        if (CLIMATE.equals(type)) {
            double value = action.optDouble("temperatureC");
            String temperature = value == Math.rint(value)
                    ? String.valueOf((int) value)
                    : String.valueOf(value);
            return "Ready to set the cabin temperature to "
                    + temperature + " °C. Confirm below to run it.";
        }
        if (SUNSHADE.equals(type)) {
            return "Ready to " + action.optString("operation")
                    + " the sunshade. Confirm below to run it.";
        }
        return "Ready to run “"
                + action.optString("automationName", "this automation")
                + "”. Confirm below to continue.";
    }

    private static String automationName(String id) {
        JSONArray catalog = Automations.listForPicker(null);
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.optJSONObject(i);
            if (item != null && id.equals(
                    item.optString("id", ""))) {
                return clamp(item.optString("name", ""), 120);
            }
        }
        return "Automation";
    }

    private static String automationIdForName(String raw) {
        String wanted = raw == null ? "" : raw.trim();
        if (wanted.isEmpty()) return "";
        JSONArray catalog = Automations.listForPicker(null);
        String match = "";
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.optJSONObject(i);
            if (item == null || !wanted.equalsIgnoreCase(
                    item.optString("name", "").trim())) {
                continue;
            }
            if (!match.isEmpty()) return "";
            match = item.optString("id", "");
        }
        return match;
    }

    private static String automationMode(JSONObject automation) {
        if (automation == null) return Automation.MODE_DISABLED;
        if (!automation.optBoolean("disabled", false)) {
            return Automation.MODE_AUTOMATIC;
        }
        return automation.optBoolean("manualOnly", false)
                ? Automation.MODE_MANUAL : Automation.MODE_DISABLED;
    }

    private static String clamp(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit
                ? value : value.substring(0, limit);
    }

    public static final class Proposal {
        public final String reply;
        public final boolean needsInput;
        public final JSONObject action;

        Proposal(String reply, boolean needsInput, JSONObject action) {
            this.reply = reply;
            this.needsInput = needsInput;
            this.action = action;
        }
    }

    public static final class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }
    }
}
