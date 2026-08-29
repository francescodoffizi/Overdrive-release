package com.overdrive.app.genai;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.Automations;

import org.json.JSONArray;
import org.json.JSONObject;

/** Safe draft/validation bridge between an untrusted model and Automations. */
public final class GenAiAutomation {

    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_SUMMARY_CHARS = 1200;

    private GenAiAutomation() {
    }

    public static JSONObject schemaContext() {
        JSONObject context = new JSONObject();
        try {
            context.put("format", new JSONObject()
                    .put("name", "Human-readable name, at most 64 characters")
                    .put("triggers", new JSONArray().put(new JSONObject()
                            .put("type", "trigger catalog id")
                            .put("variables", new JSONObject())))
                    .put("conditions", new JSONArray().put(new JSONObject()
                            .put("type", "condition catalog id")
                            .put("variables", new JSONObject())
                            .put("comparator", "allowed comparator id")
                            .put("value", "allowed value")))
                    .put("conditionLogic", "AND or OR")
                    .put("delay", "integer seconds")
                    .put("actions", new JSONArray().put(new JSONObject()
                            .put("type", "action catalog id")
                            .put("variables", new JSONObject())))
                    .put("elseActions", new JSONArray())
                    .put("disabled", true)
                    .put("manualOnly", true));

            JSONArray schema = Automations.schemaJson();
            JSONObject catalog = new JSONObject();
            for (int i = 0; i < schema.length(); i++) {
                JSONObject section = schema.optJSONObject(i);
                if (section == null) continue;
                String id = section.optString("id", "");
                if ("triggers".equals(id)) {
                    // Trigger and condition options are the same event catalog.
                    // Sending it twice wastes roughly 8 KB of provider context.
                    continue;
                }
                boolean eventCatalog = "conditions".equals(id);
                if (!eventCatalog && !"actions".equals(id)) {
                    continue;
                }
                JSONArray options = section.optJSONArray("options");
                JSONArray compact = new JSONArray();
                if (options != null) {
                    for (int j = 0; j < options.length(); j++) {
                        JSONObject option = options.optJSONObject(j);
                        if (option == null || isForbiddenType(
                                option.optString("id",
                                        option.optString("type", "")))) {
                            continue;
                        }
                        compact.put(compactCatalogOption(
                                option, eventCatalog));
                    }
                }
                catalog.put(eventCatalog ? "events" : id, compact);
            }
            context.put("catalog", catalog);
            context.put("safety",
                    "The events catalog is shared by triggers and conditions; use an event id as the output type and omit comparator/value for triggers. Use an action id as the output type. Only listed ids/options are valid. Shell and action-group actions are forbidden. The app re-validates and saves only as manual-only.");
        } catch (Exception ignored) {
        }
        return context;
    }

    /** Provider-native envelope schema; the automation itself stays JSON text. */
    public static JSONObject responseSchema() {
        try {
            return new JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put("required", new JSONArray()
                            .put("summary")
                            .put("questions")
                            .put("automationJson"))
                    .put("properties", new JSONObject()
                            .put("summary", new JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", MAX_SUMMARY_CHARS))
                            .put("questions", new JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", 4)
                                    .put("items", new JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", 300)))
                            .put("automationJson", new JSONObject()
                                    .put("type", "string")));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static Draft parseProviderDraft(String raw)
            throws ValidationException {
        JSONObject wrapper = extractObject(raw);
        if (wrapper == null) {
            throw new ValidationException(
                    "The provider did not return a JSON automation draft.");
        }

        String summary = clamp(
                wrapper.optString("summary", "").trim(),
                MAX_SUMMARY_CHARS);
        JSONArray questions = cleanQuestions(
                wrapper.optJSONArray("questions"));
        JSONObject proposed = wrapper.optJSONObject("automation");
        if (proposed == null) {
            String encoded = wrapper.optString(
                    "automationJson", "").trim();
            if (!encoded.isEmpty()) proposed = extractObject(encoded);
        }
        if (questions.length() > 0) {
            return new Draft(summary, questions, null);
        }
        if (proposed == null) {
            throw new ValidationException(
                    "The provider returned neither a draft nor a clarification question.");
        }

        if (containsForbiddenAction(proposed)) {
            throw new ValidationException(
                    "AI-created automations cannot contain shell or action-group actions.");
        }
        Automation parsed = Automation.fromJson(proposed);
        if (parsed == null) {
            throw new ValidationException(
                    "The provider draft does not match OverDrive's automation schema.");
        }
        parsed.setName(clamp(parsed.getName(), MAX_NAME_CHARS));
        parsed.setMode(Automation.MODE_MANUAL);
        JSONObject canonical = parsed.toJson();
        if (containsForbiddenAction(canonical)) {
            throw new ValidationException(
                    "The validated draft contains a forbidden action.");
        }
        return new Draft(summary, questions, canonical);
    }

    public static Automation validateForSave(JSONObject proposed)
            throws ValidationException {
        if (proposed == null || containsForbiddenAction(proposed)) {
            throw new ValidationException(
                    "Automation is missing or contains a forbidden action.");
        }
        Automation parsed = Automation.fromJson(proposed);
        if (parsed == null) {
            throw new ValidationException(
                    "Automation does not match OverDrive's schema.");
        }
        parsed.setName(clamp(parsed.getName(), MAX_NAME_CHARS));
        parsed.setMode(Automation.MODE_MANUAL);
        return parsed;
    }

    static JSONObject extractObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine >= 0 && closing > firstLine) {
                text = text.substring(firstLine + 1, closing).trim();
            }
        }
        try {
            return new JSONObject(text);
        } catch (Exception ignored) {
        }

        int start = text.indexOf('{');
        if (start < 0) return null;
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                try {
                    return new JSONObject(text.substring(start, i + 1));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static boolean containsForbiddenAction(JSONObject rules) {
        if (rules == null) return false;
        return scanActions(rules.optJSONArray("actions"))
                || scanActions(rules.optJSONArray("elseActions"));
    }

    private static boolean scanActions(JSONArray actions) {
        if (actions == null) return false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject action = actions.optJSONObject(i);
            if (action == null) continue;
            if (isForbiddenType(action.optString("type", ""))) return true;
            if (scanActions(action.optJSONArray("childActions"))
                    || scanActions(action.optJSONArray("elseActions"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForbiddenType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase();
        return type.contains("shell") || "actiongroup".equals(type);
    }

    private static JSONObject compactCatalogOption(
            JSONObject input, boolean eventCatalog) throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", input.optString("id", ""));
        String label = input.optString("label", "");
        if (!label.isEmpty()) out.put("label", label);

        JSONArray variables = input.optJSONArray("variables");
        if (variables != null && variables.length() > 0) {
            out.put("variables", compactTypes(variables));
        }
        if (eventCatalog) {
            JSONObject comparator = input.optJSONObject("comparator");
            if (comparator != null) {
                JSONArray options = comparator.optJSONArray("options");
                JSONArray ids = new JSONArray();
                if (options != null) {
                    for (int i = 0; i < options.length(); i++) {
                        JSONObject option = options.optJSONObject(i);
                        if (option != null) {
                            String id = option.optString("id", "");
                            if (!id.isEmpty()) ids.put(id);
                        }
                    }
                }
                if (ids.length() > 0) out.put("comparators", ids);
            }
            JSONObject value = input.optJSONObject("value");
            if (value != null) out.put("value", compactType(value));
        } else {
            if (input.optBoolean("hasChildActions")) {
                out.put("childActions", true);
            }
            if (input.optBoolean("hasElseActions")) {
                out.put("elseActions", true);
            }
        }
        return out;
    }

    private static JSONArray compactTypes(JSONArray input) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < input.length(); i++) {
            JSONObject type = input.optJSONObject(i);
            if (type != null) out.put(compactType(type));
        }
        return out;
    }

    private static JSONObject compactType(JSONObject input) throws Exception {
        JSONObject out = new JSONObject();
        String id = input.optString("id", "");
        if (!id.isEmpty()) out.put("id", id);
        String kind = input.optString("type", "");
        if (!kind.isEmpty()) out.put("kind", kind);

        copyConstraint(input, out, "min");
        copyConstraint(input, out, "max");
        copyConstraint(input, out, "maxLength");
        if (input.optBoolean("dynamic")) out.put("dynamic", true);
        if (input.optBoolean("signalAddress")) {
            out.put("signalAddress", true);
        }
        JSONArray colourCodes = input.optJSONArray("colourCodes");
        if (colourCodes != null) out.put("colourCodes", colourCodes);

        JSONArray options = input.optJSONArray("options");
        if (options != null && options.length() > 0) {
            JSONObject allowed = new JSONObject();
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i);
                if (option == null) continue;
                String optionId = option.optString("id", "");
                if (optionId.isEmpty()) continue;
                String optionLabel = option.optString("label", optionId);
                allowed.put(optionId,
                        optionLabel.isEmpty() ? optionId : optionLabel);
            }
            if (allowed.length() > 0) out.put("options", allowed);
        }
        return out;
    }

    private static void copyConstraint(
            JSONObject input, JSONObject out, String key) throws Exception {
        if (input.has(key)) out.put(key, input.opt(key));
    }

    private static JSONArray cleanQuestions(JSONArray source) {
        JSONArray out = new JSONArray();
        if (source == null) return out;
        for (int i = 0; i < source.length() && out.length() < 4; i++) {
            String question = clamp(
                    source.optString(i, "").trim(), 300);
            if (!question.isEmpty()) out.put(question);
        }
        return out;
    }

    private static String clamp(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    public static final class Draft {
        public final String summary;
        public final JSONArray questions;
        public final JSONObject automation;

        Draft(String summary, JSONArray questions, JSONObject automation) {
            this.summary = summary == null ? "" : summary;
            this.questions =
                    questions == null ? new JSONArray() : questions;
            this.automation = automation;
        }

        public boolean needsInput() {
            return automation == null;
        }

        public String responseText() {
            StringBuilder text = new StringBuilder(summary);
            for (int i = 0; i < questions.length(); i++) {
                if (text.length() > 0) text.append('\n');
                text.append("• ").append(questions.optString(i));
            }
            return text.length() == 0
                    ? "Review the generated automation draft." : text.toString();
        }
    }

    public static final class ValidationException extends Exception {
        ValidationException(String message) {
            super(message);
        }
    }
}
