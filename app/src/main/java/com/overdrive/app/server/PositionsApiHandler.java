package com.overdrive.app.server;

import android.content.Context;

import com.overdrive.app.byd.BodyworkSeatProbe;
import com.overdrive.app.byd.PositionStore;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.daemon.DaemonBootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OverDrive-native seat/mirror position store API (feature: "seat positions").
 * Runs in the uid-2000 daemon — the only process that can read/write BYD geometry —
 * and is the endpoint the a11y "record on long-press" trigger (in the app UI process)
 * POSTs to via {@link com.overdrive.app.util.DaemonHttpClient}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/positions}                    — list stored positions</li>
 *   <li>{@code POST /api/positions/capture?slot=N&name=..} — read the live full bundle and
 *       upsert it as the captured entry for native slot N (1..3). Fired by the long-press hook.</li>
 *   <li>{@code POST /api/positions/apply?id=..}         — apply a stored position (moves seat+mirrors,
 *       full spi.p7.m() two-batch sequence via {@link BodyworkSeatProbe#applyFull}). Movement-gated,
 *       {@code force=YES} overrides. Also accepts the id in a JSON body, which is how the
 *       automation action reaches it.</li>
 *   <li>{@code POST /api/positions/delete?id=..}        — remove a stored position</li>
 *   <li>{@code POST /api/positions/create?name=..}      — save the live geometry as a new user entry</li>
 *   <li>{@code POST /api/positions/save?id=..}          — overwrite a user entry with the live geometry</li>
 *   <li>{@code POST /api/positions/rename?id=..&name=..} — rename a user entry, id unchanged</li>
 * </ul>
 *
 * <p>Only {@code /api/positions/apply} is reachable from an automation. Everything else here
 * creates, overwrites or destroys stored positions, which an {@code ApiAction} has no business
 * doing — see {@code HttpServer.AUTOMATION_ALLOWED_PREFIXES}, which lists the exact apply path
 * rather than the {@code /api/positions/} prefix for precisely that reason.
 */
public final class PositionsApiHandler {

    private static final String TAG = "PositionsApi";

    private PositionsApiHandler() {}

    /**
     * The owner's selected vehicle model, or null when unset. Null is deliberately NOT treated as
     * a Seal: {@code VehicleModelSelection} exists because fresh installs used to write
     * {@code modelId=seal} and camera auto-configuration then treated every unconfigured BYD as
     * one. Unknown means unknown.
     */
    private static String resolvedModel() {
        try {
            return com.overdrive.app.config.UnifiedConfigManager.getSelectedVehicleModelId();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context resolveContext() {
        Context ctx = null;
        try { ctx = CameraDaemon.getAppContext(); } catch (Throwable ignore) {}
        if (ctx == null) { try { ctx = DaemonBootstrap.getContext(); } catch (Throwable ignore) {} }
        return ctx;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String pathOnly = path;
        Map<String, String> q = new LinkedHashMap<>();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            pathOnly = path.substring(0, qIdx);
            for (String pair : path.substring(qIdx + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) q.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }

        // GET /api/positions  (list)
        if (pathOnly.equals("/api/positions") && "GET".equals(method)) {
            JSONObject r = new JSONObject();
            r.put("positions", PositionStore.getInstance().list());
            // Car state rides along with the list so the management page can gate its
            // buttons without a second round trip. Both are advisory for the UI — the
            // authority is still applyFull's own gate, which the UI cannot talk its way past.
            // ACC off matters most: the seat motors are unpowered, so a write is accepted
            // (code 0) and does nothing, which would otherwise look like success.
            try { r.put("acc", com.overdrive.app.monitor.AccMonitor.isAccOn()); }
            catch (Throwable ignore) { }
            try { r.put("movementBlocked", com.overdrive.app.byd.routing.DrivingSafetyGuard.isMovementBlocked()); }
            catch (Throwable ignore) { }
            String model = resolvedModel();
            r.put("modelId", model != null ? model : JSONObject.NULL);
            r.put("modelConfirmed", PositionStore.isModelConfirmed(model));
            r.put("modelAcknowledged", PositionStore.getInstance().isModelAcknowledged(model));
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        // Live geometry, so the management page can show what the car is in right now and
        // the user can pose the seat and then save what they see. Read-only; deliberately
        // separate from /api/debug/seat/read, which is a probe with a debug posture.
        if (pathOnly.equals("/api/positions/current") && "GET".equals(method)) {
            JSONObject axes = readLive(out);
            if (axes == null) return true;
            HttpResponse.sendJson(out, new JSONObject().put("axes", axes).toString());
            return true;
        }
        if (pathOnly.equals("/api/positions/capture")) return handleCapture(out, q);
        if (pathOnly.equals("/api/positions/apply"))   return handleApply(out, q, body);
        if (pathOnly.equals("/api/positions/delete"))  return handleDelete(out, q);
        if (pathOnly.equals("/api/positions/create"))  return handleCreate(out, q, body);
        if (pathOnly.equals("/api/positions/save"))    return handleSave(out, q, body);
        if (pathOnly.equals("/api/positions/rename"))  return handleRename(out, q, body);

        HttpResponse.sendError(out, 404, "Unknown positions endpoint");
        return true;
    }

    /**
     * Read a parameter from either the query string or a JSON body, query first.
     *
     * <p>Both forms are needed. The management UI posts query parameters, but an automation
     * {@code ApiAction} renders a JSON body from a template ({@code {"id":"${id}"}}) like every
     * other action in the catalog, so accepting only {@code ?id=} would make this endpoint the
     * odd one out. Query values are URL-decoded here — the splitter above deliberately does not
     * decode, so a name with a space or a Norwegian vowel would otherwise arrive percent-encoded.
     */
    private static String param(Map<String, String> q, String body, String key) {
        String v = q.get(key);
        if (v != null && !v.isEmpty()) {
            try {
                return java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Throwable t) {
                return v;
            }
        }
        if (body != null && !body.trim().isEmpty()) {
            try {
                String s = new JSONObject(body).optString(key, "");
                if (!s.isEmpty()) return s;
            } catch (Throwable ignore) {
                // not JSON, or key absent — fall through
            }
        }
        return null;
    }

    /** Read the live geometry and upsert it under native slot N. */
    private static boolean handleCapture(OutputStream out, Map<String, String> q) throws Exception {
        Integer slot = parseInt(q.get("slot"));
        if (slot == null || slot < 1 || slot > 3) {
            HttpResponse.sendJsonError(out, "capture needs slot=1..3");
            return true;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("error", "Daemon Context unavailable").toString());
            return true;
        }
        JSONObject axes = BodyworkSeatProbe.readFullBundle(ctx);
        if (axes.length() == 0) {
            HttpResponse.sendJsonError(out, "read of live geometry returned nothing (bodywork device unavailable?)");
            return true;
        }
        // BYD's Pos 1/2/3 are per-logged-in-profile, so key captures by profile. A captured
        // entry MIRRORS the car: its name is "<nickName> - <car slot name>", both read live
        // from the DiLink account content provider, and it is NOT user-renameable in our UI
        // (source="captured"). Freely-named entries are the separate user-created profiles.
        String[] ps = readProfileSlot(ctx, slot);   // [nickName, slotName]
        String profile = ps[0];
        String slotName = (ps[1] != null) ? ps[1] : ("Posisjon " + slot);
        String name = (profile != null ? profile : "default") + " - " + slotName;
        long now = System.currentTimeMillis();
        JSONObject entry = PositionStore.getInstance().upsertCaptured(profile, slot, name, axes, now);
        log("captured profile=" + profile + " slot=" + slot + " name=" + name
                + " model=" + resolvedModel() + " axes=" + axes);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * Read the current DiLink account nickName + the car's name for a given slot from the
     * account content provider (content://com.byd.accountProvider/driver_pos_msg → columns
     * "nickName", "driverPos_1|2|3"). Returns {nickName, slotName}; either element is null if
     * unavailable.
     *
     * <p>Shells out to the {@code content} binary rather than using our own
     * {@code ContentResolver}: the daemon's synthetic Context reports its package as
     * "android", so an in-process query throws {@code SecurityException: Given calling package
     * android does not match caller's uid 2000}. The {@code content} shim carries the correct
     * shell package identity for uid 2000 (exactly what `adb shell content query` uses, which
     * is confirmed to read this provider). Output line looks like:
     * {@code Row: 0 nickName=foo@bar, driverPos_1=Posisjon 1, driverPos_2=..., driverPos_3=...}
     */
    private static String[] readProfileSlot(Context ctx, int slot) {
        try {
            Process p = new ProcessBuilder(
                    "content", "query", "--uri", "content://com.byd.accountProvider/driver_pos_msg")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            p.waitFor();
            String out = sb.toString();
            return new String[]{ field(out, "nickName"), field(out, "driverPos_" + slot) };
        } catch (Throwable t) {
            log("readProfileSlot (content shell) failed: " + t);
        }
        return new String[]{ null, null };
    }

    /**
     * Pull one {@code key=value} field out of a `content query` row. Values run to the next
     * ", <key>=" boundary or end of line, so a value may itself contain commas. Returns null
     * if absent/blank/the literal "NULL".
     */
    private static String field(String out, String key) {
        if (out == null) return null;
        // Boundary is the next ", <key>=" — the key can contain digits (driverPos_1),
        // so the char class MUST include 0-9 or the boundary misses and .*? runs to EOL.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(key) + "=(.*?)(?:, [A-Za-z0-9_]+=|$)",
                        java.util.regex.Pattern.MULTILINE)
                .matcher(out);
        if (m.find()) {
            String v = m.group(1).trim();
            if (!v.isEmpty() && !"NULL".equals(v)) return v;
        }
        return null;
    }

    /**
     * Save the live geometry as a NEW user-owned position. The management UI's "Save as new":
     * the user poses the seat with the physical controls, then names what the car is currently in.
     * There is no axis-level editing anywhere — a hand-typed value is a seat pose nobody chose.
     */
    private static boolean handleCreate(OutputStream out, Map<String, String> q, String body) throws Exception {
        String name = param(q, body, "name");
        if (name == null) { HttpResponse.sendJsonError(out, "create needs a name"); return true; }
        JSONObject axes = readLive(out);
        if (axes == null) return true;
        JSONObject entry = PositionStore.getInstance().createUser(name, axes, System.currentTimeMillis());
        if (entry == null) { HttpResponse.sendJsonError(out, "name must be 1..60 characters"); return true; }
        log("created " + entry.optString("id") + " name=" + name);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * Overwrite an existing USER position with the live geometry ("Save here" on the row).
     * Captured entries are rejected by the store: they mirror the car, so their geometry only
     * ever comes from a capture.
     */
    private static boolean handleSave(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        if (id == null) { HttpResponse.sendJsonError(out, "save needs an id"); return true; }
        JSONObject axes = readLive(out);
        if (axes == null) return true;
        JSONObject entry = PositionStore.getInstance().updateAxes(id, axes, System.currentTimeMillis());
        if (entry == null) {
            HttpResponse.sendJsonError(out, "no user position with id=" + id + " (captured positions cannot be overwritten)");
            return true;
        }
        log("saved over " + id);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /** Rename a USER position. The id is untouched so automations referencing it keep working. */
    private static boolean handleRename(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        String name = param(q, body, "name");
        if (id == null || name == null) { HttpResponse.sendJsonError(out, "rename needs an id and a name"); return true; }
        JSONObject entry = PositionStore.getInstance().rename(id, name);
        if (entry == null) {
            HttpResponse.sendJsonError(out, "no user position with id=" + id + ", or the name is not 1..60 characters");
            return true;
        }
        log("renamed " + id + " to " + name);
        HttpResponse.sendJson(out, entry.toString());
        return true;
    }

    /**
     * Read the live 13-axis bundle, writing the error response itself and returning null when it
     * cannot. Shared by create and save.
     */
    private static JSONObject readLive(OutputStream out) throws Exception {
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("error", "Daemon Context unavailable").toString());
            return null;
        }
        JSONObject axes = BodyworkSeatProbe.readFullBundle(ctx);
        if (axes.length() == 0) {
            HttpResponse.sendJsonError(out, "read of live geometry returned nothing (bodywork device unavailable?)");
            return null;
        }
        return axes;
    }

    /** Apply a stored position (moves seat + mirrors). */
    private static boolean handleApply(OutputStream out, Map<String, String> q, String body) throws Exception {
        String id = param(q, body, "id");
        JSONObject pos = PositionStore.getInstance().getById(id);
        if (pos == null) { HttpResponse.sendJsonError(out, "no position with id=" + id); return true; }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("error", "Daemon Context unavailable").toString());
            return true;
        }
        JSONObject axes = pos.optJSONObject("axes");
        if (axes == null || axes.length() == 0) { HttpResponse.sendJsonError(out, "position has no axes"); return true; }

        // Applying on a model the axis map has not been confirmed against needs one explicit
        // acknowledgement, not a refusal. The write is a round trip — every value was read from
        // these same properties on this same car when the position was captured — so the realistic
        // worst case on a mismatched id map is restoring some other property's own earlier value,
        // on a parked car. Blocking it outright would leave the confirmed-model list frozen at the
        // one car it was written on. Answered as 200 with needsModelAck so the UI can explain and
        // ask, rather than as an error the user has to decode.
        String model = resolvedModel();
        PositionStore store = PositionStore.getInstance();
        boolean acked = "YES".equals(q.get("ack")) || "1".equals(q.get("ack"));
        if (!PositionStore.isModelConfirmed(model) && !store.isModelAcknowledged(model)) {
            if (!acked) {
                JSONObject r = new JSONObject();
                r.put("needsModelAck", true);
                r.put("modelId", model != null ? model : JSONObject.NULL);
                r.put("appliedId", id);
                HttpResponse.sendJson(out, r.toString());
                return true;
            }
            store.acknowledgeModel(model);
        }
        Map<String, Float> overrides = new LinkedHashMap<>();
        for (java.util.Iterator<String> it = axes.keys(); it.hasNext(); ) {
            String k = it.next();
            overrides.put(k, (float) axes.optDouble(k, Double.NaN));
        }
        boolean force = "YES".equals(q.get("force"));
        JSONObject res = BodyworkSeatProbe.applyFull(ctx, overrides, force);
        res.put("appliedId", id);
        log("apply " + id + " model=" + model + " confirmed=" + PositionStore.isModelConfirmed(model)
                + " -> batch1=" + res.optJSONObject("batch1") + " batch2=" + res.optJSONObject("batch2"));
        HttpResponse.sendJson(out, res.toString());
        return true;
    }

    private static boolean handleDelete(OutputStream out, Map<String, String> q) throws Exception {
        String id = q.get("id");
        boolean removed = PositionStore.getInstance().remove(id);
        HttpResponse.sendJson(out, new JSONObject().put("removed", removed).put("id", id).toString());
        return true;
    }

    private static Integer parseInt(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static void log(String s) {
        try { CameraDaemon.log(TAG + ": " + s); } catch (Throwable ignore) {}
    }
}
