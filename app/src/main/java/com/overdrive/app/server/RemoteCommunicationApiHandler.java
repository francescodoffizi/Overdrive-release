package com.overdrive.app.server;

import com.overdrive.app.byd.MessageOverlayController;
import com.overdrive.app.communication.CabinAudioController;
import com.overdrive.app.communication.RemoteCommunicationAvailability;
import com.overdrive.app.communication.RemoteCommunicationPolicy;
import com.overdrive.app.communication.RemoteCommunicationSettings;
import com.overdrive.app.communication.RemoteVoiceController;
import com.overdrive.app.communication.VehicleCommunicationSafety;

import org.json.JSONObject;

import java.io.OutputStream;

/** HTTP control/status surface for the dedicated Communicate web page. */
public final class RemoteCommunicationApiHandler {

    private RemoteCommunicationApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if ("/api/communicate/status".equals(clean) && "GET".equals(method)) {
            sendStatus(out);
            return true;
        }
        if ("/api/communicate/settings".equals(clean)) {
            if ("GET".equals(method)) {
                sendSettings(out);
            } else if ("POST".equals(method)) {
                updateSettings(body, out);
            } else {
                HttpResponse.sendError(out, 405, "Method Not Allowed");
            }
            return true;
        }
        if ("/api/communicate/message".equals(clean) && "POST".equals(method)) {
            sendMessage(body, out);
            return true;
        }
        if ("/api/communicate/test-speaker".equals(clean) && "POST".equals(method)) {
            RemoteCommunicationSettings.Snapshot settings =
                    RemoteCommunicationSettings.load();
            boolean carOff = VehicleCommunicationSafety.isCarKnownOff();
            boolean busy = RemoteCommunicationWebSocket.isBusy();
            Boolean overlay = RemoteCommunicationAvailability.shouldCheckVoiceOverlay(
                    carOff, settings, busy)
                    ? RemoteVoiceController.hasOverlayPermission() : null;
            RemoteCommunicationAvailability.Result availability =
                    RemoteCommunicationAvailability.voice(
                            carOff,
                            settings,
                            busy,
                            overlay);
            if (!availability.ready) {
                sendAvailabilityFailure(out, 409, availability);
            } else {
                RemoteVoiceController.testSpeaker();
                HttpResponse.sendJson(out, new JSONObject()
                        .put("success", true)
                        .put("status", "queued")
                        .toString());
            }
            return true;
        }
        if ("/api/communicate/emergency-stop".equals(clean)
                && "POST".equals(method)) {
            boolean saved = RemoteCommunicationSettings.update(
                    null, null, null, true);
            RemoteCommunicationWebSocket.stopActive(
                    "Remote communication was emergency-disabled in the car");
            CabinAudioWebSocket.stopActive(
                    "Remote communication was emergency-disabled in the car");
            RemoteVoiceController.stop();
            CabinAudioController.stop();
            MessageOverlayController.dismiss();
            JSONObject response = new JSONObject()
                    .put("success", saved)
                    .put("emergencyDisabled", true);
            HttpResponse.sendJson(out, saved ? 200 : 500, response.toString());
            return true;
        }
        return false;
    }

    private static void sendStatus(OutputStream out) throws Exception {
        RemoteCommunicationSettings.Snapshot settings =
                RemoteCommunicationSettings.load();
        boolean carStateKnown =
                VehicleCommunicationSafety.isCarPowerStateKnown();
        boolean carOff = VehicleCommunicationSafety.isCarKnownOff();
        boolean busy = RemoteCommunicationWebSocket.isBusy();
        boolean listenerBusy = CabinAudioWebSocket.isBusy();
        Boolean overlay = RemoteCommunicationAvailability.shouldCheckAnyOverlay(
                carOff, settings, busy)
                ? RemoteVoiceController.hasOverlayPermission() : null;
        RemoteCommunicationAvailability.Result audio =
                RemoteCommunicationAvailability.voice(
                        carOff, settings, busy, overlay);
        RemoteCommunicationAvailability.Result messages =
                RemoteCommunicationAvailability.messages(
                        carOff, settings, overlay);
        RemoteCommunicationAvailability.Result listener =
                RemoteCommunicationAvailability.listener(
                        carOff, settings, listenerBusy);

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("reachable", true);
        response.put("online", !carOff);
        response.put("carState",
                carOff ? "off" : (carStateKnown ? "on" : "unknown"));
        response.put("busy", busy);
        response.put("listenerBusy", listenerBusy);
        putAvailability(response, "audio", "audioReady", audio);
        putAvailability(response, "listener", "listenerReady", listener);
        putAvailability(response, "message", "messagesReady", messages);
        response.put("overlayPermission",
                overlay == null ? JSONObject.NULL : overlay);
        response.put("settingsPath",
                RemoteCommunicationAvailability.SETTINGS_PATH);
        response.put("maxSeconds",
                RemoteCommunicationPolicy.MAX_SESSION_MS / 1000L);
        response.put("maxMessageChars",
                RemoteCommunicationPolicy.MAX_MESSAGE_CHARS);
        HttpResponse.sendJson(out, response.toString());
    }

    private static void sendSettings(OutputStream out) throws Exception {
        RemoteCommunicationSettings.Snapshot settings =
                RemoteCommunicationSettings.load();
        boolean carOff = VehicleCommunicationSafety.isCarKnownOff();
        JSONObject response = new JSONObject()
                .put("success", true)
                .put("voiceEnabled", settings.voiceEnabled)
                .put("outputLevel", settings.outputLevel)
                .put("outputLevelOverrideEnabled",
                        settings.outputLevelOverrideEnabled)
                .put("listenerEnabled", settings.listenerEnabled)
                .put("messagesEnabled", settings.messagesEnabled)
                .put("emergencyDisabled", settings.emergencyDisabled);
        Boolean overlay = RemoteCommunicationAvailability.shouldCheckAnyOverlay(
                carOff, settings, false)
                ? RemoteVoiceController.hasOverlayPermission() : null;
        response.put("overlayPermission",
                overlay == null ? JSONObject.NULL : overlay);
        HttpResponse.sendJson(out, response.toString());
    }

    private static void updateSettings(String body, OutputStream out) throws Exception {
        JSONObject request;
        try {
            request = body == null || body.isEmpty()
                    ? new JSONObject() : new JSONObject(body);
        } catch (Exception invalid) {
            sendFailure(out, 400, "Invalid JSON");
            return;
        }
        Boolean voice = request.has("voiceEnabled")
                ? request.optBoolean("voiceEnabled") : null;
        Integer level = request.has("outputLevel")
                ? request.optInt("outputLevel") : null;
        Boolean levelOverride = request.has("outputLevelOverrideEnabled")
                ? request.optBoolean("outputLevelOverrideEnabled") : null;
        Boolean messages = request.has("messagesEnabled")
                ? request.optBoolean("messagesEnabled") : null;
        Boolean emergency = request.has("emergencyDisabled")
                ? request.optBoolean("emergencyDisabled") : null;
        Boolean listener = request.has("listenerEnabled")
                ? request.optBoolean("listenerEnabled") : null;
        boolean saved = RemoteCommunicationSettings.update(
                voice, level, levelOverride, messages, emergency, listener);
        if (Boolean.TRUE.equals(emergency)) {
            RemoteCommunicationWebSocket.stopActive(
                    "Remote communication was emergency-disabled in the car");
            CabinAudioWebSocket.stopActive(
                    "Remote communication was emergency-disabled in the car");
            RemoteVoiceController.stop();
            CabinAudioController.stop();
            MessageOverlayController.dismiss();
        }
        if (!saved) {
            sendFailure(out, 500, "Could not save remote communication settings");
            return;
        }
        if (Boolean.FALSE.equals(voice)) {
            RemoteCommunicationWebSocket.stopActive(
                    "Remote voice was disabled in the car settings");
            RemoteVoiceController.stop();
        }
        if (Boolean.FALSE.equals(messages)) {
            MessageOverlayController.dismiss();
        }
        if (Boolean.FALSE.equals(listener)) {
            CabinAudioWebSocket.stopActive(
                    "Cabin listening was disabled in the car settings");
            CabinAudioController.stop();
        }
        sendSettings(out);
    }

    private static void sendMessage(String body, OutputStream out) throws Exception {
        RemoteCommunicationSettings.Snapshot settings =
                RemoteCommunicationSettings.load();
        boolean carOff = VehicleCommunicationSafety.isCarKnownOff();
        Boolean overlay = RemoteCommunicationAvailability.shouldCheckMessageOverlay(
                carOff, settings)
                ? RemoteVoiceController.hasOverlayPermission() : null;
        RemoteCommunicationAvailability.Result availability =
                RemoteCommunicationAvailability.messages(
                        carOff,
                        settings,
                        overlay);
        if (!availability.ready) {
            sendAvailabilityFailure(out, 409, availability);
            return;
        }

        JSONObject request;
        try {
            request = body == null || body.isEmpty()
                    ? new JSONObject() : new JSONObject(body);
        } catch (Exception invalid) {
            sendFailure(out, 400, "Invalid JSON");
            return;
        }
        String message = request.optString("message", "");
        String validation = RemoteCommunicationPolicy.validateMessage(message);
        if (validation != null) {
            sendFailure(out, 400, validation);
            return;
        }

        String requestedKind =
                RemoteCommunicationPolicy.normalizeKind(request.optString("kind"));
        boolean parked = VehicleCommunicationSafety.isParked();
        String renderedKind =
                RemoteCommunicationPolicy.effectiveKind(requestedKind, parked);
        String duration =
                RemoteCommunicationPolicy.normalizeDuration(
                        request.optString("duration"));
        String position =
                RemoteCommunicationPolicy.normalizePosition(
                        request.optString("position"));
        String severity =
                RemoteCommunicationPolicy.normalizeSeverity(
                        request.optString("severity"));

        MessageOverlayController.DisplayResult result;
        if ("dialog".equals(renderedKind)) {
            int timeoutSec = "long".equals(duration) ? 20 : 8;
            result = MessageOverlayController.showDialogAcknowledged(
                    request.optString("title", "Remote message"),
                    message,
                    "OK",
                    severity,
                    timeoutSec);
        } else {
            result = MessageOverlayController.showToastAcknowledged(
                    message, duration, position, severity);
        }

        JSONObject response = new JSONObject()
                .put("success", result.displayed)
                .put("status", result.displayed ? "displayed" : "failed")
                .put("renderedKind", renderedKind)
                .put("downgraded",
                        "dialog".equals(requestedKind)
                                && !"dialog".equals(renderedKind));
        if (result.reason != null && !result.reason.isEmpty()) {
            response.put("reason", result.reason);
        }
        HttpResponse.sendJson(out, result.displayed ? 200 : 503, response.toString());
    }

    private static void sendFailure(OutputStream out, int status, String reason)
            throws Exception {
        HttpResponse.sendJson(out, status, new JSONObject()
                .put("success", false)
                .put("status", "failed")
                .put("reason", reason)
                .toString());
    }

    private static void putAvailability(
            JSONObject response,
            String prefix,
            String readyKey,
            RemoteCommunicationAvailability.Result availability)
            throws Exception {
        response.put(readyKey, availability.ready);
        response.put(prefix + "State", availability.code);
        response.put(prefix + "Reason",
                availability.ready ? JSONObject.NULL : availability.reason);
        response.put(prefix + "Guidance",
                availability.guidance.isEmpty()
                        ? JSONObject.NULL : availability.guidance);
    }

    private static void sendAvailabilityFailure(
            OutputStream out,
            int status,
            RemoteCommunicationAvailability.Result availability)
            throws Exception {
        HttpResponse.sendJson(out, status, new JSONObject()
                .put("success", false)
                .put("status", "failed")
                .put("code", availability.code)
                .put("reason", availability.reason)
                .put("guidance", availability.guidance)
                .toString());
    }
}
