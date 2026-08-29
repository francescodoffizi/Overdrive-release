package com.overdrive.app.server;

import android.content.Context;

import com.overdrive.app.byd.BodyworkSeatProbe;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.daemon.DaemonBootstrap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * READ-ONLY debug endpoint for {@code BYDAutoBodyworkDevice} feature-id getters,
 * backing {@link BodyworkSeatProbe}. Exposes the absolute seat-geometry reads
 * (System B — the profile/account positions the DiLink app applies) and the
 * keyfob-identity reads over HTTP so they can be tested on a parked car without
 * a decompiler in the loop.
 *
 * <p>Endpoints (all GET, JSON, NO writes, NO {@code confirm} gate — nothing here
 * actuates):
 * <ul>
 *   <li>{@code GET /api/debug/seat/read} — read the 7 seat-geometry axes.</li>
 *   <li>{@code GET /api/debug/seat/read?group=seat|keyfob|all} — read a named
 *       group. {@code keyfob} = the 5 unlock-identity ids; {@code all} = both.</li>
 *   <li>{@code GET /api/debug/seat/read?ids=0x3E8FA010,988807176,...} — read an
 *       arbitrary comma-separated id list (decimal or {@code 0x}-hex). Lets the
 *       keyfob-per-person probe run against any id without a rebuild.</li>
 * </ul>
 *
 * <p>Returns 503 if the daemon Context isn't available (must run inside the
 * uid-2000 cam_daemon, which is where the HTTP server lives).
 */
public final class SeatDebugApiHandler {

    private static final String TAG = "SeatDebug";

    private SeatDebugApiHandler() {}

    /** Same context-resolution order as {@link LightDebugApiHandler}. */
    private static Context resolveContext() {
        Context ctx = null;
        try { ctx = CameraDaemon.getAppContext(); } catch (Throwable ignore) {}
        if (ctx == null) {
            try { ctx = DaemonBootstrap.getContext(); } catch (Throwable ignore) {}
        }
        return ctx;
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }

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

        if (pathOnly.equals("/api/debug/seat/read")) {
            return handleRead(out, q);
        }
        if (pathOnly.equals("/api/debug/seat/write")) {
            return handleWrite(out, q);
        }

        HttpResponse.sendError(out, 404, "Unknown seat debug endpoint");
        return true;
    }

    private static boolean handleRead(OutputStream out, Map<String, String> q) throws Exception {
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503,
                new JSONObject().put("error", "Daemon Context unavailable — run inside cam_daemon").toString());
            return true;
        }

        // Explicit ids= wins; else the named group (default "seat").
        BodyworkSeatProbe.Id[] ids;
        String source;
        String rawIds = q.get("ids");
        if (rawIds != null && !rawIds.trim().isEmpty()) {
            ids = BodyworkSeatProbe.parseIds(java.net.URLDecoder.decode(rawIds, "UTF-8"));
            source = "ids";
            if (ids.length == 0) {
                HttpResponse.sendJsonError(out, "No parseable ids in '" + rawIds + "' (use decimal or 0x-hex, comma-separated)");
                return true;
            }
        } else {
            String group = q.getOrDefault("group", "seat").toLowerCase();
            switch (group) {
                case "keyfob": ids = BodyworkSeatProbe.keyfobIds(); break;
                case "all":    ids = concat(BodyworkSeatProbe.seatIds(), BodyworkSeatProbe.keyfobIds()); break;
                case "seat":   ids = BodyworkSeatProbe.seatIds(); break;
                default:
                    HttpResponse.sendJsonError(out, "Unknown group '" + group + "' (expected seat, keyfob, or all)");
                    return true;
            }
            source = "group:" + group;
        }

        // Optional device selector. Default = bodywork. "mirror" tries the candidate
        // mirror fqcns until one resolves; an explicit fqcn is passed straight through.
        String device = q.get("device");
        log("read " + source + " (" + ids.length + " ids) device=" + (device == null ? "bodywork" : device));
        JSONObject result;
        if (device == null || device.equalsIgnoreCase("bodywork") || device.equalsIgnoreCase("seat")) {
            result = BodyworkSeatProbe.readIds(ctx, ids);
        } else if (device.equalsIgnoreCase("mirror") || device.equalsIgnoreCase("rearviewmirror")) {
            result = null;
            org.json.JSONArray tried = new org.json.JSONArray();
            for (String fqcn : BodyworkSeatProbe.MIRROR_DEVICE_CANDIDATES) {
                tried.put(fqcn);
                JSONObject attempt = BodyworkSeatProbe.readIdsOnDevice(ctx, fqcn, ids);
                if (!attempt.has("error")) { result = attempt; break; }
            }
            if (result == null) {
                result = new JSONObject();
                result.put("error", "no mirror device class resolved");
                result.put("candidatesTried", tried);
            }
        } else {
            // Treat as an explicit fully-qualified class name.
            result = BodyworkSeatProbe.readIdsOnDevice(ctx, device, ids);
        }
        result.put("source", source);
        log("read " + source + " -> " + summarize(result));
        HttpResponse.sendJson(out, result.toString());
        return true;
    }

    /**
     * WRITE seat geometry — MOVES THE SEAT. Parked-gated + {@code confirm=YES} required.
     * Forms:
     *   /api/debug/seat/write?axis=horizontal&value=52&confirm=YES        (single axis)
     *   /api/debug/seat/write?apply=HORIZONTAL:52,BACKREST:56,...&confirm=YES  (batch)
     */
    private static boolean handleWrite(OutputStream out, Map<String, String> q) throws Exception {
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing to move the seat without confirm=YES");
            return true;
        }
        Context ctx = resolveContext();
        if (ctx == null) {
            HttpResponse.sendJson(out, 503,
                new JSONObject().put("error", "Daemon Context unavailable — run inside cam_daemon").toString());
            return true;
        }

        // Full-position apply: ?full=LEFT_H:15,HORIZONTAL:52,...  — replicates spi.p7.m()
        // (read current for all axes, override the given labels, write mirror+steering then
        // seat as two bodywork/float batches 50ms apart). This is the real native apply.
        String full = q.get("full");
        if (full != null && !full.trim().isEmpty()) {
            java.util.LinkedHashMap<String,Float> ov = new java.util.LinkedHashMap<>();
            for (String pair : java.net.URLDecoder.decode(full, "UTF-8").split(",")) {
                int c = pair.indexOf(':');
                if (c <= 0) continue;
                try { ov.put(pair.substring(0, c).trim().toUpperCase(), Float.parseFloat(pair.substring(c + 1).trim())); }
                catch (NumberFormatException e) { HttpResponse.sendJsonError(out, "Bad value in full: '" + pair + "'"); return true; }
            }
            for (float v : ov.values()) { if (v < 0 || v > 255) { HttpResponse.sendJsonError(out, "Value out of 0..255"); return true; } }
            log("apply FULL overrides=" + ov);
            JSONObject fres = BodyworkSeatProbe.applyFull(ctx, ov);
            HttpResponse.sendJson(out, fres.toString());
            return true;
        }

        // Mirror write: ?mirror=LEFT_H:45,RIGHT_V:30,...  (doormirror device, int values)
        String mirror = q.get("mirror");
        if (mirror != null && !mirror.trim().isEmpty()) {
            java.util.List<Integer> mids = new java.util.ArrayList<>();
            java.util.List<Integer> mvals = new java.util.ArrayList<>();
            for (String pair : java.net.URLDecoder.decode(mirror, "UTF-8").split(",")) {
                int c = pair.indexOf(':');
                if (c <= 0) continue;
                Integer id = BodyworkSeatProbe.writeIdForMirror(pair.substring(0, c));
                if (id == null) { HttpResponse.sendJsonError(out, "Unknown mirror axis: '" + pair + "' (LEFT_H/LEFT_V/RIGHT_H/RIGHT_V)"); return true; }
                try { mids.add(id); mvals.add(Integer.parseInt(pair.substring(c + 1).trim())); }
                catch (NumberFormatException e) { HttpResponse.sendJsonError(out, "Bad mirror value: '" + pair + "'"); return true; }
            }
            if (mids.isEmpty()) { HttpResponse.sendJsonError(out, "No mirror axes to write"); return true; }
            for (int v : mvals) { if (v < 0 || v > 255) { HttpResponse.sendJsonError(out, "Mirror value " + v + " out of 0..255"); return true; } }
            // Physical-mirror cars (spi.p7.m() else-branch): mirror SET ids go to the BODYWORK
            // device as FLOATS (via h()=i(map,true)), NOT the doormirror device as ints (that's
            // the CMS/stream-mirror path). CRUCIALLY the native batches the 4 mirror ids TOGETHER
            // WITH the 2 steering SET ids (0x4C116030/038) in one h() call — and a partial group
            // is accepted (code 0) but does NOT actuate (same "whole linkage group" rule the seat
            // showed). So append steering to complete the group. Steering reads 127.5 on cars with
            // no electric column (Pål's) and the HAL ignores those writes, so 127.5 is a safe,
            // native-matching filler unless the caller overrode it via ST_H/ST_V.
            java.util.LinkedHashMap<Integer,Float> batch = new java.util.LinkedHashMap<>();
            for (int i = 0; i < mids.size(); i++) batch.put(mids.get(i), (float) mvals.get(i));
            if (!batch.containsKey(0x4C116030)) batch.put(0x4C116030, 127.5f);   // ST_H
            if (!batch.containsKey(0x4C116038)) batch.put(0x4C116038, 127.5f);   // ST_V
            int[] mi = new int[batch.size()]; float[] mv = new float[batch.size()];
            int bi = 0; for (java.util.Map.Entry<Integer,Float> e : batch.entrySet()) { mi[bi] = e.getKey(); mv[bi] = e.getValue(); bi++; }
            log("write MIRROR+steering (bodywork/float) ids=" + batch.keySet() + " vals=" + batch.values());
            JSONObject mres = BodyworkSeatProbe.writeAxes(ctx, mi, mv);
            HttpResponse.sendJson(out, mres.toString());
            return true;
        }

        // Build (ids, values) from either axis+value or apply=LABEL:VAL,LABEL:VAL
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        java.util.List<Float> vals = new java.util.ArrayList<>();
        String apply = q.get("apply");
        if (apply != null && !apply.trim().isEmpty()) {
            for (String pair : java.net.URLDecoder.decode(apply, "UTF-8").split(",")) {
                int c = pair.indexOf(':');
                if (c <= 0) continue;
                Integer id = BodyworkSeatProbe.writeIdForAxis(pair.substring(0, c));
                if (id == null) { HttpResponse.sendJsonError(out, "Unknown axis in apply: '" + pair + "'"); return true; }
                try { ids.add(id); vals.add(Float.parseFloat(pair.substring(c + 1).trim())); }
                catch (NumberFormatException e) { HttpResponse.sendJsonError(out, "Bad value in apply: '" + pair + "'"); return true; }
            }
        } else {
            String axis = q.get("axis");
            String value = q.get("value");
            if (axis == null || value == null) {
                HttpResponse.sendJsonError(out, "Need axis=<name>&value=<float> or apply=LABEL:VAL,...");
                return true;
            }
            Integer id = BodyworkSeatProbe.writeIdForAxis(axis);
            if (id == null) { HttpResponse.sendJsonError(out, "Unknown axis '" + axis + "'"); return true; }
            try { ids.add(id); vals.add(Float.parseFloat(value.trim())); }
            catch (NumberFormatException e) { HttpResponse.sendJsonError(out, "Bad value '" + value + "'"); return true; }
        }
        if (ids.isEmpty()) { HttpResponse.sendJsonError(out, "No axes to write"); return true; }

        // Sanity clamp: reads ranged 0..127.5, so reject values outside a generous band
        // to avoid a fat-fingered absurd setpoint driving the motor to a rail.
        for (float v : vals) {
            if (v < 0f || v > 255f) { HttpResponse.sendJsonError(out, "Value " + v + " out of sane range 0..255"); return true; }
        }

        int[] idArr = new int[ids.size()];
        float[] valArr = new float[vals.size()];
        for (int i = 0; i < ids.size(); i++) { idArr[i] = ids.get(i); valArr[i] = vals.get(i); }
        log("write ids=" + ids + " vals=" + vals);
        JSONObject result = BodyworkSeatProbe.writeAxes(ctx, idArr, valArr);
        log("write -> " + result.optString("skipped", "").isEmpty()
                + " code=" + result.opt("resultCode") + " blocked=" + result.opt("movementBlocked"));
        HttpResponse.sendJson(out, result.toString());
        return true;
    }

    private static BodyworkSeatProbe.Id[] concat(BodyworkSeatProbe.Id[] a, BodyworkSeatProbe.Id[] b) {
        BodyworkSeatProbe.Id[] out = new BodyworkSeatProbe.Id[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Compact one-line summary for the daemon log: how many ids read back. */
    private static String summarize(JSONObject result) {
        try {
            JSONArray vals = result.optJSONArray("values");
            if (vals == null) return result.optString("error", "no values");
            int read = 0;
            for (int i = 0; i < vals.length(); i++) {
                if (vals.getJSONObject(i).optBoolean("read", false)) read++;
            }
            return read + "/" + vals.length() + " read";
        } catch (Throwable t) {
            return "summary-failed";
        }
    }

    private static void log(String s) {
        try { CameraDaemon.log(TAG + ": " + s); } catch (Throwable ignore) {}
    }
}
