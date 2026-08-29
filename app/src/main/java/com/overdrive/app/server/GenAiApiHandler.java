package com.overdrive.app.server;

import com.overdrive.app.automation.Automation;
import com.overdrive.app.automation.Automations;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.genai.GenAiAction;
import com.overdrive.app.genai.GenAiAutomation;
import com.overdrive.app.genai.GenAiConfig;
import com.overdrive.app.genai.GenAiContext;
import com.overdrive.app.genai.GenAiInsights;
import com.overdrive.app.genai.GenAiRuntime;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;

/** Authenticated local HTTP surface for GenAI settings and explicit requests. */
public final class GenAiApiHandler {

    private static final int MAX_MESSAGES = 24;
    private static final int MAX_MESSAGE_CHARS = 12_000;
    private static final int MAX_TOTAL_MESSAGE_CHARS = 64_000;

    private static final String AUTOMATION_INSTRUCTIONS =
            "Create one safe OverDrive automation draft from the conversation. "
            + "Return exactly one JSON object and no Markdown. "
            + "If details required to choose valid trigger/action values are missing, "
            + "return {\"summary\":\"short explanation\",\"questions\":[\"question\"],\"automationJson\":\"\"}. "
            + "Otherwise return {\"summary\":\"what it does and safety caveats\","
            + "\"questions\":[],\"automationJson\":\"{...}\"}, where automationJson "
            + "is the complete automation object encoded as a JSON string. "
            + "Use only ids, variables, comparators, and values present in the supplied catalog. "
            + "Never use shell or actionGroup. Set disabled=true and manualOnly=true. "
            + "Do not claim the draft has been saved or enabled.";

    private GenAiApiHandler() {
    }

    public static boolean handle(
            String method, String path, String body, OutputStream out)
            throws Exception {
        String pathOnly = stripQuery(path);

        if ("/api/genai/status".equals(pathOnly) && "GET".equals(method)) {
            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime != null) {
                HttpResponse.sendJsonNoCors(out, runtime.statusJson().toString());
            }
            return true;
        }

        if ("/api/genai/config".equals(pathOnly) && "GET".equals(method)) {
            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime != null) {
                HttpResponse.sendJsonNoCors(out, runtime.statusJson().toString());
            }
            return true;
        }

        if ("/api/genai/config".equals(pathOnly)
                && ("POST".equals(method) || "PUT".equals(method))) {
            JSONObject input = parseBody(body, out);
            if (input == null) return true;
            GenAiConfig.SaveResult saved = GenAiConfig.save(input);
            if (!saved.success) {
                sendError(out, 400, "invalid_config", saved.error);
                return true;
            }
            boolean scheduleSynced =
                    GenAiInsights.syncSchedule(saved.config);
            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime != null) {
                JSONObject response = runtime.statusJson();
                response.put("success", true);
                response.put("insightScheduleSynced",
                        scheduleSynced);
                HttpResponse.sendJsonNoCors(out, response.toString());
            }
            return true;
        }

        if ("/api/genai/config".equals(pathOnly) && "DELETE".equals(method)) {
            JSONObject clear = new JSONObject()
                    .put("enabled", false)
                    .put("clearApiKey", true);
            GenAiConfig.SaveResult saved = GenAiConfig.save(clear);
            if (!saved.success) {
                sendError(out, 500, "clear_failed", saved.error);
            } else {
                GenAiInsights.syncSchedule(saved.config);
                GenAiRuntime runtime = CameraDaemon.getGenAiRuntime();
                JSONObject response = runtime == null
                        ? saved.config.toPublicJson() : runtime.statusJson();
                response.put("success", true);
                HttpResponse.sendJsonNoCors(out, response.toString());
            }
            return true;
        }

        if ("/api/genai/insights".equals(pathOnly)
                && "GET".equals(method)) {
            HttpResponse.sendJsonNoCors(out, GenAiInsights.listJson(
                    queryInt(path, "limit", 20)).toString());
            return true;
        }

        if ("/api/genai/insights/dashboard".equals(pathOnly)
                && "GET".equals(method)) {
            JSONObject response = new JSONObject()
                    .put("success", true)
                    .put("enabled",
                            GenAiConfig.isDashboardPresentationEnabled());
            if (response.optBoolean("enabled")) {
                JSONObject latest = GenAiInsights.latestJson(
                        queryParam(path, "lang"));
                if (latest != null) response.put("item", latest);
            }
            HttpResponse.sendJsonNoCors(out, response.toString());
            return true;
        }

        if ("/api/genai/insights".equals(pathOnly)
                && "DELETE".equals(method)) {
            if (!GenAiInsights.clear()) {
                sendError(out, 500, "insights_clear_failed",
                        "Could not clear AI insight history.");
            } else {
                HttpResponse.sendJsonNoCors(out, new JSONObject()
                        .put("success", true)
                        .put("items", new JSONArray())
                        .toString());
            }
            return true;
        }

        if ("/api/genai/insights/settings".equals(pathOnly)
                && "POST".equals(method)) {
            JSONObject input = parseBody(body, out);
            if (input == null) return true;
            JSONObject update = new JSONObject();
            copyIfPresent(input, update, "insightSchedule");
            copyIfPresent(input, update, "insightHour");
            copyIfPresent(input, update, "insightMinute");
            copyIfPresent(input, update, "insightDay");
            copyIfPresent(input, update, "insightMode");
            copyIfPresent(input, update, "insightDashboard");
            copyIfPresent(input, update, "insightNotifications");
            GenAiConfig.SaveResult saved = GenAiConfig.save(update);
            if (!saved.success) {
                sendError(out, 400, "invalid_insight_settings",
                        saved.error);
                return true;
            }
            boolean synced = GenAiInsights.syncSchedule(saved.config);
            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime != null) {
                JSONObject response = runtime.statusJson();
                response.put("success", true);
                response.put("insightScheduleSynced", synced);
                    HttpResponse.sendJsonNoCors(out, response.toString());
            }
            return true;
        }

        if ("/api/genai/insights/generate".equals(pathOnly)
                && "POST".equals(method)) {
            JSONObject input = parseBody(body, out);
            if (input == null) return true;
            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime == null) return true;
            try {
                JSONObject response = GenAiInsights.generate(
                        runtime,
                        input.optString(
                                "mode", GenAiContext.OVERVIEW),
                        input.optString("prompt", ""),
                        input.optBoolean("notify", false),
                        "manual",
                        input.optString("language", ""));
                HttpResponse.sendJsonNoCors(out, response.toString());
            } catch (GenAiRuntime.GenAiException e) {
                sendError(out, e.status, e.code, e.getMessage());
            }
            return true;
        }

        if ("/api/genai/test".equals(pathOnly) && "POST".equals(method)) {
            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime == null) return true;
            try {
                HttpResponse.sendJsonNoCors(
                        out, runtime.testConnection().toString());
            } catch (GenAiRuntime.GenAiException e) {
                sendError(out, e.status, e.code, e.getMessage());
            }
            return true;
        }

        if ("/api/genai/automation/draft".equals(pathOnly)
                && "POST".equals(method)) {
            JSONObject input = parseBody(body, out);
            if (input == null) return true;
            JSONArray messages = sanitizeMessages(input, out);
            if (messages == null) return true;
            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime != null) {
                handleAutomationDraft(
                        runtime, messages, requestId(input),
                        input.optString("language", ""), out);
            }
            return true;
        }

        if ("/api/genai/automation/commit".equals(pathOnly)
                && "POST".equals(method)) {
            JSONObject input = parseBody(body, out);
            if (input == null) return true;
            handleAutomationCommit(input, out);
            return true;
        }

        if ("/api/genai/action/execute".equals(pathOnly)
                && "POST".equals(method)) {
            JSONObject input = parseBody(body, out);
            if (input == null) return true;
            handleActionExecute(input, out);
            return true;
        }

        if ("/api/genai/chat".equals(pathOnly) && "POST".equals(method)) {
            JSONObject input = parseBody(body, out);
            if (input == null) return true;
            JSONArray messages = sanitizeMessages(input, out);
            if (messages == null) return true;
            if (!GenAiConfig.fromUnifiedConfig().enabled) {
                sendError(out, 409, "genai_disabled",
                        "GenAI is disabled.");
                return true;
            }

            GenAiRuntime runtime = runtimeOrError(out);
            if (runtime == null) return true;
            try {
                GenAiContext.Snapshot snapshot = GenAiContext.build(
                        input.optString("mode", GenAiContext.GENERAL),
                        latestUserMessage(messages));
                if (GenAiContext.AUTOMATION_DRAFT.equals(snapshot.mode)) {
                    handleAutomationDraft(
                            runtime, messages, requestId(input),
                            input.optString("language", ""), out);
                    return true;
                }
                if (GenAiContext.VEHICLE_ACTION.equals(snapshot.mode)) {
                    HttpResponse.sendJsonNoCors(out,
                            createActionProposal(
                                    runtime, messages, snapshot,
                                    input.optString("language", ""),
                                    null).toString());
                    return true;
                }
                JSONObject response = runtime.complete(
                        messages, snapshot.context,
                        GenAiContext.withResponseLanguage(
                                snapshot.instructions,
                                input.optString("language", "")));
                response.put("mode", snapshot.mode);
                response.put("contextAttached", snapshot.hasContext());
                copyFields(snapshot.clientData, response);
                HttpResponse.sendJsonNoCors(out, response.toString());
            } catch (GenAiAction.ValidationException e) {
                sendError(out, 422, "invalid_action_proposal",
                        e.getMessage());
            } catch (GenAiRuntime.GenAiException e) {
                sendError(out, e.status, e.code, e.getMessage());
            }
            return true;
        }

        return false;
    }

    static JSONObject createActionProposal(
            GenAiRuntime runtime, JSONArray messages,
            GenAiContext.Snapshot snapshot,
            String language,
            GenAiRuntime.Cancellation cancellation)
            throws GenAiRuntime.GenAiException,
            GenAiAction.ValidationException {
        JSONObject provider = runtime.completeStructured(
                messages, snapshot.context,
                GenAiContext.withResponseLanguage(
                        snapshot.instructions, language),
                "overdrive_action_proposal",
                GenAiAction.responseSchema(), cancellation);
        GenAiAction.Proposal proposal =
                GenAiAction.parseProviderProposal(
                        provider.optString("text", ""));
        try {
            provider.put("text", proposal.reply);
            provider.put("mode", GenAiContext.VEHICLE_ACTION);
            provider.put("contextAttached", true);
            provider.put("needsInput", proposal.needsInput);
            if (proposal.action != null) {
                provider.put("actionProposal", proposal.action);
            }
        } catch (Exception e) {
            throw new GenAiRuntime.GenAiException(
                    500, "response_build_failed",
                    "Could not build the action proposal.");
        }
        return provider;
    }

    private static void handleActionExecute(
            JSONObject input, OutputStream out) throws Exception {
        if (!GenAiConfig.fromUnifiedConfig().enabled) {
            sendError(out, 409, "genai_disabled",
                    "GenAI is disabled.");
            return;
        }
        final JSONObject action;
        try {
            action = GenAiAction.validateClientAction(
                    input.optJSONObject("action"));
        } catch (GenAiAction.ValidationException e) {
            sendError(out, 400, "invalid_action", e.getMessage());
            return;
        }

        String type = action.optString("type", "");
        if ("run_automation".equals(type)) {
            String id = action.optString("automationId", "");
            AutomationApiHandler.dispatchExplicitAutomation(id, false);
            HttpResponse.sendJsonNoCors(out, new JSONObject()
                    .put("success", true)
                    .put("accepted", true)
                    .put("action", type)
                    .put("automationId", id)
                    .put("message", "Automation run accepted.")
                    .toString());
            return;
        }

        VehicleCommandRouter.VehicleCommand command;
        String commandName;
        if ("climate_temperature".equals(type)) {
            command = new VehicleCommandRouter.ClimateSetTempCommand(
                    action.optInt("zone", 0),
                    action.optDouble("temperatureC"));
            commandName = "set_temp";
        } else if ("sunshade".equals(type)) {
            command = new VehicleCommandRouter.SunshadeCommand(
                    "open".equals(action.optString("operation"))
                            ? 1 : 2);
            commandName = "sunshade";
        } else {
            sendError(out, 400, "invalid_action",
                    "The proposed action is unsupported.");
            return;
        }
        CommandResult result =
                VehicleCommandRouter.getInstance().execute(command);
        JSONObject response = VehicleControlApiHandler.routedResponse(
                result, commandName);
        response.put("confirmed", true);
        HttpResponse.sendJsonNoCors(out, response.toString());
    }

    private static void handleAutomationDraft(
            GenAiRuntime runtime, JSONArray messages,
            String requestId, String language, OutputStream out)
            throws Exception {
        try {
            JSONObject provider = runtime.completeStructured(
                    messages, GenAiAutomation.schemaContext(),
                    GenAiContext.withResponseLanguage(
                            AUTOMATION_INSTRUCTIONS, language),
                    "overdrive_automation_draft",
                    GenAiAutomation.responseSchema());
            GenAiAutomation.Draft draft =
                    GenAiAutomation.parseProviderDraft(
                            provider.optString("text", ""));
            provider.put("text", draft.responseText());
            provider.put("mode", GenAiContext.AUTOMATION_DRAFT);
            provider.put("contextAttached", true);
            provider.put("needsInput", draft.needsInput());
            provider.put("questions", draft.questions);
            if (!draft.needsInput()) {
                JSONObject saved = saveManualAutomation(
                        draft.automation, requestId);
                if (saved == null) {
                    sendError(out, 500, "automation_persist_failed",
                            "The manual automation was added in memory but could not be written to storage.");
                    return;
                }
                provider.put("draft", new JSONObject()
                        .put("automation", draft.automation)
                        .put("summary", draft.summary)
                        .put("reviewRequired", true)
                        .put("executionMode", Automation.MODE_MANUAL)
                        .put("saved", true)
                        .put("id", saved.optString("id")));
            }
            HttpResponse.sendJsonNoCors(out, provider.toString());
        } catch (GenAiAutomation.ValidationException e) {
            sendError(out, 422, "invalid_automation_draft",
                    e.getMessage());
        } catch (GenAiRuntime.GenAiException e) {
            sendError(out, e.status, e.code, e.getMessage());
        }
    }

    private static void handleAutomationCommit(
            JSONObject input, OutputStream out) throws Exception {
        if (!GenAiConfig.fromUnifiedConfig().enabled) {
            sendError(out, 409, "genai_disabled",
                    "GenAI is disabled.");
            return;
        }
        JSONObject proposed = input.optJSONObject("automation");
        try {
            JSONObject response = saveManualAutomation(
                    proposed, requestId(input));
            if (response == null) {
                sendError(out, 500, "automation_persist_failed",
                        "The manual draft was added in memory but could not be written to storage.");
                return;
            }
            HttpResponse.sendJsonNoCors(out, response.toString());
        } catch (GenAiAutomation.ValidationException e) {
            sendError(out, 400, "invalid_automation", e.getMessage());
        }
    }

    private static JSONObject saveManualAutomation(
            JSONObject proposed, String requestId)
            throws GenAiAutomation.ValidationException, JSONException {
        Automation parsed = GenAiAutomation.validateForSave(proposed);
        String id = UUID.nameUUIDFromBytes(
                ("genai:" + requestId).getBytes(
                        StandardCharsets.UTF_8)).toString();
        if (!Automations.updateAutomation(id, parsed)) return null;
        return new JSONObject()
                .put("success", true)
                .put("id", id)
                .put("name", parsed.getName())
                .put("executionMode", Automation.MODE_MANUAL)
                .put("message",
                        "Created and saved as manual-only. Review it before enabling automatic execution.");
    }

    private static String requestId(JSONObject input) {
        String value = input == null
                ? "" : input.optString("requestId", "").trim();
        return value.matches("[A-Za-z0-9_-]{1,80}")
                ? value : UUID.randomUUID().toString();
    }

    private static GenAiRuntime runtimeOrError(OutputStream out)
            throws Exception {
        GenAiRuntime runtime = CameraDaemon.getGenAiRuntime();
        if (runtime == null) {
            sendError(out, 503, "runtime_unavailable",
                    "GenAI runtime is not ready.");
        }
        return runtime;
    }

    private static JSONObject parseBody(String body, OutputStream out)
            throws Exception {
        if (body == null || body.trim().isEmpty()) {
            sendError(out, 400, "missing_body", "Missing JSON body.");
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            sendError(out, 400, "invalid_json", "Malformed JSON body.");
            return null;
        }
    }

    private static JSONArray sanitizeMessages(
            JSONObject input, OutputStream out) throws Exception {
        try {
            return validateMessages(input);
        } catch (RequestValidationException e) {
            sendError(out, 400, e.code, e.getMessage());
            return null;
        }
    }

    static JSONArray validateMessages(JSONObject input)
            throws RequestValidationException {
        JSONArray source = input.optJSONArray("messages");
        if (source == null) {
            String message = input.optString("message", "").trim();
            if (message.isEmpty()) {
                throw new RequestValidationException(
                        "message_required", "Enter a message.");
            }
            try {
                source = new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("content", message));
            } catch (Exception e) {
                throw new RequestValidationException(
                        "invalid_message", "Could not read the message.");
            }
        }
        if (source.length() == 0 || source.length() > MAX_MESSAGES) {
            throw new RequestValidationException(
                    "invalid_messages",
                    "Conversation must contain 1-" + MAX_MESSAGES
                            + " messages.");
        }

        JSONArray clean = new JSONArray();
        int totalChars = 0;
        boolean hasUser = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject message = source.optJSONObject(i);
            if (message == null) {
                throw new RequestValidationException(
                        "invalid_messages",
                        "Every message must be an object.");
            }
            String role = message.optString("role", "").trim();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new RequestValidationException(
                        "invalid_role",
                        "Message role must be user or assistant.");
            }
            String content = message.optString("content", "").trim();
            if (content.isEmpty() || content.length() > MAX_MESSAGE_CHARS) {
                throw new RequestValidationException(
                        "invalid_message",
                        "A message is empty or too long.");
            }
            totalChars += content.length();
            if (totalChars > MAX_TOTAL_MESSAGE_CHARS) {
                throw new RequestValidationException(
                        "conversation_too_long",
                        "Conversation is too long.");
            }
            if ("user".equals(role)) hasUser = true;
            try {
                clean.put(new JSONObject()
                        .put("role", role)
                        .put("content", content));
            } catch (Exception e) {
                throw new RequestValidationException(
                        "invalid_message", "Could not read a message.");
            }
        }
        if (!hasUser) {
            throw new RequestValidationException(
                    "user_message_required",
                    "Conversation must contain a user message.");
        }
        return clean;
    }

    static String latestUserMessage(JSONArray messages) {
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null
                    && "user".equals(message.optString("role", ""))) {
                return message.optString("content", "");
            }
        }
        return "";
    }

    private static void copyFields(JSONObject source, JSONObject destination)
            throws Exception {
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            destination.put(key, source.opt(key));
        }
    }

    private static void copyIfPresent(
            JSONObject source, JSONObject destination, String key)
            throws Exception {
        if (source.has(key)) destination.put(key, source.opt(key));
    }

    private static int queryInt(
            String path, String key, int fallback) {
        if (path == null) return fallback;
        int query = path.indexOf('?');
        if (query < 0 || query == path.length() - 1) return fallback;
        String[] pairs = path.substring(query + 1).split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            String name = equals >= 0
                    ? pair.substring(0, equals) : pair;
            if (!key.equals(name)) continue;
            try {
                return Integer.parseInt(equals >= 0
                        ? pair.substring(equals + 1) : "");
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static String queryParam(String path, String key) {
        if (path == null) return "";
        int query = path.indexOf('?');
        if (query < 0 || query == path.length() - 1) return "";
        String[] pairs = path.substring(query + 1).split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !key.equals(
                    pair.substring(0, equals))) {
                continue;
            }
            try {
                return java.net.URLDecoder.decode(
                        pair.substring(equals + 1), "UTF-8");
            } catch (Exception ignored) {
                return "";
            }
        }
        return "";
    }

    private static void sendError(
            OutputStream out, int status, String code, String message)
            throws Exception {
        JSONObject error = new JSONObject()
                .put("success", false)
                .put("code", code)
                .put("error", message == null ? "GenAI request failed." : message);
        HttpResponse.sendJsonNoCors(out, status, error.toString());
    }

    private static String stripQuery(String path) {
        if (path == null) return "";
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    static final class RequestValidationException extends Exception {
        final String code;

        RequestValidationException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
