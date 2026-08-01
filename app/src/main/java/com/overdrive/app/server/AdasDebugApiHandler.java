package com.overdrive.app.server;

import com.overdrive.app.byd.AdasBlindSpotProbe;

import java.io.OutputStream;

/**
 * Read-only debug endpoints for the radar blind-spot ALERT registers, which feed the
 * {@code blindSpot} automation signal and have never been confirmed on a car.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/debug/adas/resolve} — which feature ids came from a real SDK
 *       constant vs a hardcoded literal. No device access.</li>
 *   <li>{@code GET /api/debug/adas/read} — raw per-id register dump plus what the shipped
 *       detection logic would conclude.</li>
 * </ul>
 *
 * <p>No writes and no {@code confirm} gate: nothing here actuates the car. Exists because
 * the whole read path logs at DEBUG only, and R8 strips those calls in release builds — so
 * returning the values is the only way to observe them on a shipping unit.
 */
public final class AdasDebugApiHandler {

    private AdasDebugApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }

        int qIdx = path.indexOf('?');
        String pathOnly = qIdx >= 0 ? path.substring(0, qIdx) : path;

        try {
            if (pathOnly.equals("/api/debug/adas/resolve")) {
                HttpResponse.sendJson(out, AdasBlindSpotProbe.resolve().toString());
                return true;
            }
            if (pathOnly.equals("/api/debug/adas/read")) {
                HttpResponse.sendJson(out, AdasBlindSpotProbe.read().toString());
                return true;
            }
        } catch (Throwable t) {
            // A reflective probe against an absent/hostile HAL must report the failure as
            // data, not close the socket on the operator mid-diagnosis.
            HttpResponse.sendJson(out, 500, new org.json.JSONObject()
                    .put("error", String.valueOf(t))
                    .put("path", pathOnly).toString());
            return true;
        }

        HttpResponse.sendError(out, 404, "Unknown adas debug endpoint");
        return true;
    }
}
