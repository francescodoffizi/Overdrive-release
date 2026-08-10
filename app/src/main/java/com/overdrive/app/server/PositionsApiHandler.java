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
 *       full spi.p7.m() two-batch sequence via {@link BodyworkSeatProbe#applyFull}). Parked-gated.</li>
 *   <li>{@code POST /api/positions/delete?id=..}        — remove a stored position</li>
 * </ul>
 */
public final class PositionsApiHandler {

    private static final String TAG = "PositionsApi";

    private PositionsApiHandler() {}

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
            HttpResponse.sendJson(out, r.toString());
            return true;
        }
        if (pathOnly.equals("/api/positions/capture")) return handleCapture(out, q);
        if (pathOnly.equals("/api/positions/apply"))   return handleApply(out, q);
        if (pathOnly.equals("/api/positions/delete"))  return handleDelete(out, q);

        HttpResponse.sendError(out, 404, "Unknown positions endpoint");
        return true;
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
        log("captured profile=" + profile + " slot=" + slot + " name=" + name);
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

    /** Apply a stored position (moves seat + mirrors). */
    private static boolean handleApply(OutputStream out, Map<String, String> q) throws Exception {
        String id = q.get("id");
        JSONObject pos = PositionStore.getInstance().getById(id);
        if (pos == null) { HttpResponse.sendJsonError(out, "no position with id=" + id); return true; }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503, new JSONObject().put("error", "Daemon Context unavailable").toString());
            return true;
        }
        JSONObject axes = pos.optJSONObject("axes");
        if (axes == null || axes.length() == 0) { HttpResponse.sendJsonError(out, "position has no axes"); return true; }
        Map<String, Float> overrides = new LinkedHashMap<>();
        for (java.util.Iterator<String> it = axes.keys(); it.hasNext(); ) {
            String k = it.next();
            overrides.put(k, (float) axes.optDouble(k, Double.NaN));
        }
        boolean force = "YES".equals(q.get("force"));
        JSONObject res = BodyworkSeatProbe.applyFull(ctx, overrides, force);
        res.put("appliedId", id);
        log("apply " + id + " -> batch1=" + res.optJSONObject("batch1") + " batch2=" + res.optJSONObject("batch2"));
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
