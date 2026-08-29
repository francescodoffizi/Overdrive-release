package com.overdrive.app.automation;

import com.overdrive.app.automation.condition.EventData;
import com.overdrive.app.automation.value.BaseValue;
import com.overdrive.app.automation.value.Value;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds a bounded, read-only automation snapshot for explicit diagnostics. */
final class AutomationDiagnostics {

    private static final int MAX_SUMMARIES = 30;
    private static final int MAX_DETAILS = 8;
    private static final int MAX_ACTIONS = 32;

    private AutomationDiagnostics() {
    }

    static JSONObject build(
            Map<String, Automation> definitions,
            Map<EventData, Value> state,
            String query) {
        JSONObject out = new JSONObject();
        try {
            List<RankedAutomation> ranked = new ArrayList<>();
            for (Map.Entry<String, Automation> entry : definitions.entrySet()) {
                if (entry.getValue() != null) {
                    ranked.add(new RankedAutomation(
                            entry.getKey(), entry.getValue(),
                            score(entry.getKey(), entry.getValue(), query)));
                }
            }
            ranked.sort(Comparator
                    .comparingInt((RankedAutomation item) -> item.score)
                    .reversed()
                    .thenComparing(item -> displayName(item.id, item.automation),
                            String.CASE_INSENSITIVE_ORDER));

            JSONArray summaries = new JSONArray();
            for (int i = 0; i < ranked.size() && i < MAX_SUMMARIES; i++) {
                summaries.put(summary(ranked.get(i)));
            }

            boolean matched = !ranked.isEmpty() && ranked.get(0).score > 0;
            String normalizedQuery = normalize(query);
            boolean shellHelp = normalizedQuery.contains("shell")
                    || normalizedQuery.contains("command");
            JSONArray details = new JSONArray();
            for (RankedAutomation item : ranked) {
                if (details.length() >= MAX_DETAILS) break;
                if (matched && item.score <= 0) break;
                details.put(detail(
                        item, state,
                        shellHelp && matched && item.score > 0));
            }

            out.put("available", !ranked.isEmpty());
            out.put("automationCount", ranked.size());
            out.put("summaries", summaries);
            out.put("diagnosedAutomations", details);
            out.put("selection", ranked.isEmpty()
                    ? "No saved automation is available."
                    : matched
                            ? "Matched saved automation names or ids from the question."
                            : "No specific name or id matched; returned a bounded set. Ask with the automation name for a narrower diagnosis.");
            out.put("shellActionHelp", new JSONObject()
                    .put("executionAllowed", false)
                    .put("permissionGate", "automation.allowShell")
                    .put("substitution",
                            "${var:NAME} and ${signal:TYPE} are numeric-only")
                    .put("runtimeLimitSeconds", 5)
                    .put("note",
                            "The assistant may explain or suggest a reviewed command, but cannot execute it or enable the shell permission."));
            out.put("privacy",
                    "Most action parameters are omitted. A shell preview is included only when the question explicitly asks for shell help and identifies a saved automation; it is locally bounded and redacted.");
        } catch (Exception ignored) {
        }
        return out;
    }

    private static JSONObject summary(RankedAutomation item) throws Exception {
        Automation automation = item.automation;
        return new JSONObject()
                .put("id", safe(item.id))
                .put("name", displayName(item.id, automation))
                .put("mode", automation.getMode())
                .put("lastTriggered", automation.getLastTriggered())
                .put("triggerCount", automation.getTriggerCount());
    }

    private static JSONObject detail(
            RankedAutomation item, Map<EventData, Value> state,
            boolean includeShellPreview)
            throws Exception {
        Automation automation = item.automation;
        JSONObject out = summary(item)
                .put("delaySeconds", automation.getDelay())
                .put("triggers", triggers(automation, state))
                .put("conditionTree", conditionTree(automation, state))
                .put("primaryActions",
                        actions(automation.getActions(), new int[]{0},
                                includeShellPreview))
                .put("elseActions",
                        actions(automation.getElseActions(), new int[]{0},
                                includeShellPreview));
        if (automation.isFullyDisabled()) {
            out.put("modeNote",
                    "Disabled automations cannot run automatically or manually.");
        } else if (automation.isManualOnly()) {
            out.put("modeNote",
                    "Manual automations do not react to vehicle events.");
        }
        return out;
    }

    private static JSONArray triggers(
            Automation automation, Map<EventData, Value> state)
            throws Exception {
        JSONArray out = new JSONArray();
        for (EventData trigger : automation.getTriggers()) {
            Value current = state.get(trigger);
            out.put(new JSONObject()
                    .put("signal", event(trigger))
                    .put("currentAvailable", current != null)
                    .put("current", safeValue(current)));
        }
        return out;
    }

    private static JSONObject conditionTree(
            Automation automation, Map<EventData, Value> state)
            throws Exception {
        JSONArray groups = new JSONArray();
        for (ConditionGroup group : automation.getConditionGroups()) {
            groups.put(group(group, state));
        }
        return new JSONObject()
                .put("logic", automation.getConditionLogic())
                .put("metNow", automation.conditionsMet(state))
                .put("conditions",
                        conditions(automation.getConditions(), state))
                .put("groups", groups);
    }

    private static JSONObject group(
            ConditionGroup group, Map<EventData, Value> state)
            throws Exception {
        JSONArray children = new JSONArray();
        for (ConditionGroup child : group.getGroups()) {
            children.put(group(child, state));
        }
        return new JSONObject()
                .put("logic", group.getLogic())
                .put("metNow", group.evaluate(state))
                .put("conditions", conditions(group.getConditions(), state))
                .put("groups", children);
    }

    private static JSONArray conditions(
            List<AutomationCondition> conditions,
            Map<EventData, Value> state) throws Exception {
        JSONArray out = new JSONArray();
        for (AutomationCondition condition : conditions) {
            if (condition == null) continue;
            Value current = state.get(condition.getEventData());
            out.put(new JSONObject()
                    .put("signal", event(condition.getEventData()))
                    .put("comparator", safe(condition.getComparator()))
                    .put("expected", safeObject(condition.getValue()))
                    .put("currentAvailable", current != null)
                    .put("current", safeValue(current))
                    .put("metNow", condition.compare(current)));
        }
        return out;
    }

    private static JSONArray actions(
            List<AutomationAction> actions, int[] count,
            boolean includeShellPreview) throws Exception {
        JSONArray out = new JSONArray();
        if (actions == null) return out;
        for (AutomationAction action : actions) {
            if (action == null || count[0] >= MAX_ACTIONS) break;
            count[0]++;
            JSONObject item = new JSONObject()
                    .put("type", safe(action.getType()));
            JSONObject timing = timingParameters(action);
            if (timing.length() > 0) item.put("parameters", timing);
            if (includeShellPreview && "shell".equals(action.getType())) {
                Map<String, Object> values = action.getVariables();
                item.put("commandPreview", redactShell(
                        values == null ? null : values.get("command")));
                item.put("permissionGate", "automation.allowShell");
            }
            JSONArray children = actions(
                    action.getChildActions(), count, includeShellPreview);
            JSONArray elseChildren =
                    actions(action.getElseChildActions(), count,
                            includeShellPreview);
            if (children.length() > 0) item.put("children", children);
            if (elseChildren.length() > 0) {
                item.put("elseChildren", elseChildren);
            }
            out.put(item);
        }
        return out;
    }

    private static JSONObject timingParameters(AutomationAction action)
            throws Exception {
        JSONObject out = new JSONObject();
        Map<String, Object> values = action.getVariables();
        if (values == null) return out;
        if ("pause".equals(action.getType())) {
            putSafe(out, "milliseconds", values.get("milliseconds"));
        } else if ("waitUntil".equals(action.getType())) {
            putSafe(out, "event", values.get("event"));
            putSafe(out, "comparator", values.get("comparator"));
            putSafe(out, "value", values.get("value"));
            putSafe(out, "timeout", values.get("timeout"));
        } else if ("waitUntilState".equals(action.getType())) {
            putSafe(out, "event", values.get("event"));
            putSafe(out, "state", values.get("state"));
            putSafe(out, "timeout", values.get("timeout"));
        }
        return out;
    }

    private static void putSafe(
            JSONObject out, String key, Object value) throws Exception {
        if (value != null) out.put(key, safeObject(value));
    }

    private static JSONObject event(EventData event) throws Exception {
        JSONObject variables = new JSONObject();
        int count = 0;
        for (Map.Entry<String, String> variable
                : event.getVariables().entrySet()) {
            if (count++ >= 12) break;
            variables.put(safe(variable.getKey()), safe(variable.getValue()));
        }
        return new JSONObject()
                .put("type", safe(event.getType()))
                .put("variables", variables);
    }

    private static Object safeValue(Value value) {
        if (value == null) return JSONObject.NULL;
        Object raw = value instanceof BaseValue<?>
                ? ((BaseValue<?>) value).getValue() : value.toString();
        return safeObject(raw);
    }

    private static Object safeObject(Object value) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Number || value instanceof Boolean) return value;
        return safe(value.toString());
    }

    private static int score(String id, Automation automation, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return 0;
        String safeId = normalize(id);
        String name = normalize(automation.getName());
        if (!safeId.isEmpty() && q.contains(safeId)) return 1_000;
        if (safeId.length() >= 8
                && q.contains(safeId.substring(0, 8))) return 900;
        if (!name.isEmpty() && q.contains(name)) {
            return 700 + Math.min(100, name.length());
        }
        int overlap = 0;
        for (String token : name.split("\\s+")) {
            if (token.length() >= 3 && q.contains(token)) overlap++;
        }
        return overlap * 10;
    }

    private static String displayName(String id, Automation automation) {
        String name = safe(automation.getName());
        if (!name.isEmpty()) return name;
        String value = safe(id);
        return "Automation "
                + (value.length() > 8 ? value.substring(0, 8) : value);
    }

    private static String normalize(String value) {
        return value == null
                ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String safe(String value) {
        String out = value == null ? "" : value.trim();
        out = out.replaceAll("(?i)https?://[^\\s]+", "[REDACTED_URL]");
        out = out.replaceAll(
                "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
                "[REDACTED_EMAIL]");
        out = out.replaceAll(
                "\\b[A-Za-z0-9_\\-+/=]{40,}\\b",
                "[REDACTED_OPAQUE]");
        return out.length() > 160 ? out.substring(0, 160) : out;
    }

    private static String redactShell(Object command) {
        String out = command == null ? "" : command.toString().trim();
        out = out.replaceAll(
                "(?i)((?:password|passwd|token|secret|api[_-]?key|authorization|cookie)\\s*=\\s*)[^\\s]+",
                "$1[REDACTED]");
        out = out.replaceAll(
                "(?i)(--?(?:password|passwd|token|secret|api[_-]?key|authorization|cookie)(?:=|\\s+))[^\\s]+",
                "$1[REDACTED]");
        out = safe(out);
        return out.length() > 500 ? out.substring(0, 500) : out;
    }

    private static final class RankedAutomation {
        final String id;
        final Automation automation;
        final int score;

        RankedAutomation(String id, Automation automation, int score) {
            this.id = id;
            this.automation = automation;
            this.score = score;
        }
    }
}
