package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.roadsense.detect.RoadSenseHazard;
import com.overdrive.app.roadsense.detect.StoredHazard;
import com.overdrive.app.roadsense.store.RoadSenseStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * RoadSense HTTP API — backs the road-sense.html settings page's destructive
 * "Data" actions (R-SET-5) AND the native RoadSense map view. Runs in the daemon
 * process, so it calls the RoadSense stores directly (no IPC hop needed — they're
 * in-process singletons).
 *
 * Endpoints:
 *  - POST /api/roadsense/delete-local        → wipe on-device hazards + ground-truth labels
 *  - POST /api/roadsense/delete-cloud        → wipe this device's uploaded cloud rows
 *  - GET  /api/roadsense/hazards?bbox=…      → GeoJSON FeatureCollection for the map viewport
 *  - POST /api/roadsense/hazard/{id}/confirm → human-confirm a hazard (deferred ground-truth)
 *  - POST /api/roadsense/hazard/{id}/reject  → human-reject (delete) a hazard
 *
 * Confirm/reject reuse the SAME store primitive as the live-drive Calibration-Mode overlay
 * card — RoadSenseStore.markHumanVerified — so map actions and live actions converge on one
 * data path. Deliberately, the map path does NOT write a GroundTruthStore label (that needs
 * raw detection features absent from a stored row; synthesizing them would poison the training
 * set) and does NOT use the roadSense.pendingConfirmResult UCM relay (that matches a transient
 * live-drive slot; a deferred map confirm would never match). See dev/roadsense-map/00-DESIGN.md §4b.
 *
 * Config (enable/warn/crowd toggles) is NOT here — that flows through the normal
 * /api/settings/unified path into the `roadSense` UCM section, which RoadSenseConfig reads.
 */
public class RoadSenseApiHandler {

    private static final String TAG = "RoadSenseApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Hard cap on rows returned per viewport query — a bbox is small, this is a safety bound. */
    private static final int MAX_HAZARDS_PER_QUERY = 2000;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/roadsense/delete-local") && method.equals("POST")) {
            handleDeleteLocal(out);
            return true;
        }
        if (path.equals("/api/roadsense/delete-cloud") && method.equals("POST")) {
            handleDeleteCloud(out);
            return true;
        }
        if (path.equals("/api/roadsense/test-chime") && method.equals("POST")) {
            handleTestChime(out, body);
            return true;
        }
        // Map view: hazards in a viewport (path may carry a ?bbox= query string).
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (pathOnly.equals("/api/roadsense/hazards") && method.equals("GET")) {
            handleListHazards(path, out);
            return true;
        }
        // Map view: per-hazard confirm / reject — /api/roadsense/hazard/{id}/{confirm|reject}
        if (pathOnly.startsWith("/api/roadsense/hazard/") && method.equals("POST")) {
            handleHazardVerdict(pathOnly, body, out);
            return true;
        }
        return false;
    }

    /**
     * "Delete local calibrations" (R-SET-5): clears the on-device hazard store AND
     * the Calibration-Mode ground-truth labels. Two SEPARATE stores, both wiped —
     * this is the local half of the two-independent-toggles requirement.
     */
    private static void handleDeleteLocal(OutputStream out) throws Exception {
        long hazards;
        int labels;
        try {
            hazards = com.overdrive.app.roadsense.store.RoadSenseStore.getInstance().deleteAllLocal();
        } catch (Throwable t) {
            logger.warn(TAG + ": delete-local hazards failed: " + t.getMessage());
            hazards = -1;
        }
        try {
            labels = com.overdrive.app.roadsense.label.GroundTruthStore.getInstance().deleteAll();
        } catch (Throwable t) {
            logger.warn(TAG + ": delete-local labels failed: " + t.getMessage());
            labels = -1;
        }
        // "Delete local" also clears route coverage — the user's mapped-tile history
        // is local calibration data too (R-SET-5). Note: per-vehicle calibration
        // (calQuietCount/calMeanSq) is intentionally NOT wiped here — it's a property
        // of the car, not the mapped routes, and re-learning it is a 10-min cost.
        try {
            com.overdrive.app.roadsense.RoadSenseController rs =
                com.overdrive.app.daemon.CameraDaemon.getRoadSense();
            if (rs != null) rs.clearCoverage();
        } catch (Throwable t) {
            logger.warn(TAG + ": delete-local coverage failed: " + t.getMessage());
        }
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("hazardsDeleted", hazards);
        resp.put("labelsDeleted", labels);
        logger.info(TAG + ": deleted local — hazards=" + hazards + " labels=" + labels);
        HttpResponse.sendJson(out, resp.toString());
    }

    /** The chime channels the UI offers — mirrors RoadSenseConfig's AUDIO_CHANNELS. */
    private static boolean isSupportedChimeChannel(String ch) {
        return com.overdrive.app.roadsense.config.RoadSenseAudioChannels.isSupported(ch);
    }

    /**
     * POST /api/roadsense/test-chime — dispatch one approach chime NOW.
     * Body: { "severity": "minor"|"moderate"|"severe",
     *         "channel": "navigation"|"media"|…, "volumePercent": 10..100 }
     *
     * <p>Exists because the real chime only fires in the DRIVING regime while approaching
     * an already-stored hazard. This invokes the SAME
     * {@code AudioPlaybackController.playRawResource} call as the live cue, with identical
     * resource names, volumes, and channel plumbing.
     *
     * <p><b>{@code success}/{@code dispatched} mean queued, not audible.</b> The cross-process
     * {@code am} launch and MediaPlayer preparation are asynchronous, so this response cannot
     * confirm that sound reached the speakers. {@code playbackConfirmed} is therefore always
     * false; the control is an on-car hearing and volume check.
     */
    private static void handleTestChime(OutputStream out, String body) throws Exception {
        handleTestChime(out, body,
                com.overdrive.app.byd.AudioPlaybackController::playRawResource);
    }

    @FunctionalInterface
    interface ChimeDispatcher {
        boolean dispatch(String resourceName, String channel, int volumePercent);
    }

    static void handleTestChime(OutputStream out, String body, ChimeDispatcher dispatcher)
            throws Exception {
        JSONObject resp = new JSONObject();
        // Guarded parse, mirroring handleAudioLibraryPlay. The try wraps ONLY the parse:
        // sendJsonError writes a COMPLETE HTTP response (headers + body), so if a
        // sendJsonError call sat inside this try and threw, the catch would write a SECOND
        // response onto the same socket and corrupt it.
        JSONObject req;
        try {
            req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return;
        }
        String severity = req.optString("severity", "moderate").trim().toLowerCase(Locale.ROOT);
        if (!severity.equals("minor")
                && !severity.equals("moderate")
                && !severity.equals("severe")) {
            HttpResponse.sendJsonError(out, "Unsupported severity: " + severity);
            return;
        }
        String channel = null;
        String ch = req.optString("channel", "").trim().toLowerCase(Locale.ROOT);
        Object rawVolume = req.has("volumePercent")
                ? req.opt("volumePercent")
                : com.overdrive.app.roadsense.config.RoadSenseChimeLevels.DEFAULT_MASTER_PERCENT;
        Integer masterVolume = com.overdrive.app.roadsense.config.RoadSenseChimeLevels
                .validatedMasterPercent(rawVolume);
        if (masterVolume == null) {
            HttpResponse.sendJsonError(out,
                    "RoadSense chime volume must be a whole number from 10 to 100");
            return;
        }
        // REJECT an unknown channel rather than passing it through. An unrecognised name
        // silently resolves to STREAM_MUSIC downstream, so a typo would play on media while
        // reporting the channel asked for — exactly the false negative this button exists
        // to rule out.
        if (!ch.isEmpty()) {
            if (!isSupportedChimeChannel(ch)) {
                HttpResponse.sendJsonError(out, "Unsupported channel: " + ch);
                return;
            }
            channel = ch;
        }
        // Same resource + per-severity level mapping as BridgedAudioCue.
        String res;
        int severityLevel;
        switch (severity) {
            case "minor":
                res = "roadsense_chime_minor";
                severityLevel = 1;
                break;
            case "severe":
                res = "roadsense_chime_severe";
                severityLevel = 3;
                break;
            case "moderate":
                res = "roadsense_chime_moderate";
                severityLevel = 2;
                break;
            default:
                throw new IllegalStateException("validated severity became unsupported");
        }
        int vol = com.overdrive.app.roadsense.config.RoadSenseChimeLevels
                .effectivePercent(masterVolume, severityLevel);
        // No channel given → use the user's configured chime channel, so the button tests
        // what the live cue would actually do. Read straight from the UCM section (the same
        // way other Java callers read config) rather than through the Kotlin RoadSenseConfig
        // object, whose snapshot() has a default argument and no @JvmStatic.
        if (channel == null) {
            try {
                JSONObject sec = com.overdrive.app.config.UnifiedConfigManager
                        .forceReload().optJSONObject("roadSense");
                channel = (sec == null) ? "" : sec.optString("warnAudioChannel", "")
                        .trim().toLowerCase(Locale.ROOT);
            } catch (Throwable t) {
                channel = "";
            }
            // Clamp exactly as RoadSenseAudioChannels.normalize does, so a stale or
            // hand-edited value tests the SAME channel the live cue would use.
            channel = com.overdrive.app.roadsense.config.RoadSenseAudioChannels.normalize(channel);
        }
        boolean ok = false;
        try {
            ok = dispatcher.dispatch(res, channel, vol);
        } catch (Throwable t) {
            logger.warn(TAG + ": test-chime failed: " + t.getMessage());
        }
        resp.put("success", ok);
        resp.put("dispatched", ok);
        resp.put("playbackConfirmed", false);
        resp.put("severity", severity);
        resp.put("channel", channel);
        resp.put("masterVolumePercent", masterVolume);
        resp.put("volumePercent", vol);
        if (!ok) resp.put("error", "could not dispatch the chime");
        logger.info(TAG + ": test-chime " + res + " channel=" + channel + " vol=" + vol + "% ok=" + ok);
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * "Delete cloud calibrations" (R-SET-5): wipe this device's uploaded rows from
     * the crowdsource backend. Wired: delegates to RoadSenseController.deleteCloudUploads()
     * → RoadSenseSyncProvider.deleteOwnUploads() (Cloudflare edge POST /delete) and
     * clears the local tile cursors. Reports success=false (not a silent OK) when
     * RoadSense isn't running or the backend call fails.
     */
    private static void handleDeleteCloud(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        com.overdrive.app.roadsense.RoadSenseController rs =
                com.overdrive.app.daemon.CameraDaemon.getRoadSense();
        if (rs == null) {
            resp.put("success", false);
            resp.put("error", "RoadSense not running");
            HttpResponse.sendJson(out, resp.toString());
            return;
        }
        boolean ok = false;
        try {
            ok = rs.deleteCloudUploads();
        } catch (Throwable t) {
            logger.warn(TAG + ": delete-cloud failed: " + t.getMessage());
        }
        resp.put("success", ok);
        if (!ok) resp.put("error", "cloud delete failed (check sync config / connectivity)");
        logger.info(TAG + ": delete-cloud ok=" + ok);
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * GET /api/roadsense/hazards?bbox=minLng,minLat,maxLng,maxLat
     *
     * Returns a GeoJSON FeatureCollection for the map viewport. Coords are [lng,lat]
     * (GeoJSON order). Each feature carries the data-driven styling inputs the map's
     * SymbolLayer needs: type (0-3), severity (1-3), confidence, status (0-2),
     * observations, humanVerified, heading. bbox is required; malformed → 400.
     */
    private static void handleListHazards(String path, OutputStream out) throws Exception {
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        String bbox = null;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals("bbox")) {
                bbox = java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
            }
        }
        if (bbox == null || bbox.isEmpty()) {
            HttpResponse.sendJsonError(out, "missing bbox=minLng,minLat,maxLng,maxLat");
            return;
        }
        double minLng, minLat, maxLng, maxLat;
        try {
            String[] p = bbox.split(",");
            if (p.length != 4) throw new IllegalArgumentException("need 4 comma-separated values");
            minLng = Double.parseDouble(p[0].trim());
            minLat = Double.parseDouble(p[1].trim());
            maxLng = Double.parseDouble(p[2].trim());
            maxLat = Double.parseDouble(p[3].trim());
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "bad bbox: " + e.getMessage());
            return;
        }

        List<StoredHazard> hazards;
        try {
            hazards = RoadSenseStore.getInstance().queryByBbox(
                    minLat, minLng, maxLat, maxLng, MAX_HAZARDS_PER_QUERY);
        } catch (Throwable t) {
            logger.warn(TAG + ": list hazards failed: " + t.getMessage());
            hazards = java.util.Collections.emptyList();
        }

        JSONArray features = new JSONArray();
        for (StoredHazard sh : hazards) {
            RoadSenseHazard h = sh.getHazard();

            JSONArray coords = new JSONArray();
            coords.put(h.getLng());   // GeoJSON: [lng, lat]
            coords.put(h.getLat());
            JSONObject geometry = new JSONObject();
            geometry.put("type", "Point");
            geometry.put("coordinates", coords);

            JSONObject props = new JSONObject();
            props.put("id", sh.getId());
            props.put("type", h.getType().ordinal());        // 0=BREAKER,1=POTHOLE,2=UNKNOWN,3=ROUGH
            props.put("severity", h.getSeverity().getLevel()); // 1=MINOR..3=SEVERE
            props.put("confidence", h.getConfidence());
            props.put("status", sh.getStatus());              // 0=candidate,1=local,2=cloud
            props.put("observations", sh.getObservations());
            props.put("humanVerified", sh.getHumanVerified());
            props.put("heading", h.getHeadingDeg());
            props.put("updatedMs", sh.getUpdatedMs());

            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            feature.put("geometry", geometry);
            feature.put("properties", props);
            features.put(feature);
        }

        JSONObject fc = new JSONObject();
        fc.put("type", "FeatureCollection");
        fc.put("features", features);
        HttpResponse.sendJson(out, fc.toString());
    }

    /**
     * POST /api/roadsense/hazard/{id}/confirm  (body optional: {"severity":1-3,"type":0-3})
     * POST /api/roadsense/hazard/{id}/reject
     *
     * Confirm → markHumanVerified(id, true, sev?, type?, now): sets human_verified=1, status=1,
     * bumps updated_ms (auto re-arms the next upload tick → uploads as humanVerified → fleet
     * consensus weight; no extra cloud call). Reject → markHumanVerified(id, false, …): physically
     * DELETEs the row (local-only — there is no per-hazard cloud downvote; matches live reject).
     */
    private static void handleHazardVerdict(String pathOnly, String body, OutputStream out) throws Exception {
        // /api/roadsense/hazard/{id}/{confirm|reject}
        String tail = pathOnly.substring("/api/roadsense/hazard/".length());
        int slash = tail.lastIndexOf('/');
        if (slash <= 0 || slash >= tail.length() - 1) {
            HttpResponse.sendJsonError(out, "expected /api/roadsense/hazard/{id}/{confirm|reject}");
            return;
        }
        String id = java.net.URLDecoder.decode(tail.substring(0, slash), "UTF-8");
        String action = tail.substring(slash + 1);

        boolean confirm;
        if (action.equals("confirm")) {
            confirm = true;
        } else if (action.equals("reject")) {
            confirm = false;
        } else {
            HttpResponse.sendJsonError(out, "unknown action '" + action + "' (confirm|reject)");
            return;
        }

        // Optional severity/type corrections on confirm (ignored on reject, per store contract).
        Integer correctedSeverity = null;
        Integer correctedType = null;
        if (confirm && body != null && !body.trim().isEmpty()) {
            try {
                JSONObject b = new JSONObject(body);
                if (b.has("severity")) correctedSeverity = b.getInt("severity");
                if (b.has("type")) correctedType = b.getInt("type");
            } catch (Exception ignored) {
                // Body is optional; a malformed body just means "no corrections".
            }
        }

        JSONObject resp = new JSONObject();
        try {
            RoadSenseStore.getInstance().markHumanVerified(
                    id, confirm, correctedSeverity, correctedType, System.currentTimeMillis());
            resp.put("success", true);
            resp.put("action", action);
            resp.put("id", id);
            logger.info(TAG + ": hazard " + id + " " + action
                    + (confirm ? " (sevΔ=" + correctedSeverity + " typeΔ=" + correctedType + ")" : ""));
        } catch (Throwable t) {
            logger.warn(TAG + ": hazard verdict failed: " + t.getMessage());
            resp.put("success", false);
            resp.put("error", String.valueOf(t.getMessage()));
        }
        HttpResponse.sendJson(out, resp.toString());
    }
}
