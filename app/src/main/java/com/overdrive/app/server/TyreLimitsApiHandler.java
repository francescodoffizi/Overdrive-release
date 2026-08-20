package com.overdrive.app.server;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.OutputStream;

/**
 * Tyre pressure threshold API — the user's per-axle over/under pressure limits.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/tyres/limits} — the effective (clamped) limits</li>
 *   <li>{@code POST /api/tyres/limits} — persist a full or partial update</li>
 * </ul>
 *
 * <p>All values are integers in <b>kPa</b>, the unit the BYD TPMS HAL reports.
 * Keeping the stored unit identical to the measured unit means the comparison
 * path never rounds, so the Vehicle Control corner colours can't disagree with
 * the notification thresholds. UI layers convert to PSI/bar for display.
 *
 * <p>Consumers: {@code BydDataCollector.evaluateTyreAlarms} (push + Telegram
 * notifications), {@code VehicleControlApiHandler} (echoes the limits to the
 * web UI's corner colouring), and the launcher tyre widget via
 * {@code LauncherApiHandler}.
 *
 * <p>Validation happens in two layers: this handler rejects malformed or
 * out-of-order input with a message the UI can show, while
 * {@code UnifiedConfigManager.getTyreThresholds()} additionally clamps on READ
 * so a config edited by hand (or written by an older build) can never produce
 * an un-alertable vehicle.
 */
public class TyreLimitsApiHandler {

    private static final String TAG = "TyreLimitsApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Field names accepted in the POST body / emitted in the GET response. */
    private static final String[] KEYS = {
            "frontLow", "frontHigh", "rearLow", "rearHigh", "criticalLow"
    };

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (pathOnly.equals("/api/tyres/limits")) {
            if ("GET".equals(method)) {
                handleGet(out);
                return true;
            }
            if ("POST".equals(method)) {
                handlePost(out, body);
                return true;
            }
        }
        return false;
    }

    private static void handleGet(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        // The clamped view, not the raw section: the UI must show what is
        // actually in force, otherwise a stale out-of-range stored value would
        // render as if it were being applied.
        response.put("limits", UnifiedConfigManager.getTyreThresholds());
        response.put("defaults", defaults());
        response.put("min", UnifiedConfigManager.TYRE_KPA_MIN);
        response.put("max", UnifiedConfigManager.TYRE_KPA_MAX);
        response.put("unit", "kPa");
        // Display-unit preference ("kpa" | "psi" | "bar"). Presentation only —
        // the limits above are, and stay, kPa regardless of this value.
        response.put("pressureUnit", UnifiedConfigManager.getTyrePressureUnit());
        response.put("success", true);
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handlePost(OutputStream out, String body) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.tyres_empty_body"));
            return;
        }
        JSONObject incoming;
        try {
            incoming = (JSONObject) new JSONTokener(body).nextValue();
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, Messages.get("errors.tyres_invalid_json"));
            return;
        }

        // Merge over the CURRENT effective limits so a partial body (e.g. only
        // the rear axle) is validated against the values it will actually sit
        // beside, not against defaults.
        JSONObject merged = UnifiedConfigManager.getTyreThresholds();
        JSONObject patch = new JSONObject();
        for (String key : KEYS) {
            if (!incoming.has(key)) continue;
            // optInt would silently coerce a non-numeric or fractional value to
            // 0 / truncate it; require a real integer so a client bug surfaces
            // as an error instead of a wrong threshold.
            Object raw = incoming.opt(key);
            Integer value = asInt(raw);
            if (value == null) {
                HttpResponse.sendJsonError(out,
                        Messages.get("errors.tyres_not_integer", key));
                return;
            }
            if (value < UnifiedConfigManager.TYRE_KPA_MIN
                    || value > UnifiedConfigManager.TYRE_KPA_MAX) {
                HttpResponse.sendJsonError(out, Messages.get("errors.tyres_out_of_range",
                        key,
                        String.valueOf(UnifiedConfigManager.TYRE_KPA_MIN),
                        String.valueOf(UnifiedConfigManager.TYRE_KPA_MAX)));
                return;
            }
            merged.put(key, (int) value);
            patch.put(key, (int) value);
        }

        // Display-unit preference rides along in the same section. Validated
        // against the closed token set so a client bug can't persist a value
        // no formatter understands (the read side also falls back, belt and
        // braces). Case-normalised so "PSI" and "psi" store identically.
        if (incoming.has("pressureUnit")) {
            String unit = String.valueOf(incoming.opt("pressureUnit"))
                    .trim().toLowerCase(java.util.Locale.ROOT);
            if (!unit.equals("kpa") && !unit.equals("psi") && !unit.equals("bar")) {
                HttpResponse.sendJsonError(out,
                        Messages.get("errors.tyres_invalid_unit"));
                return;
            }
            patch.put("pressureUnit", unit);
        }

        if (patch.length() == 0) {
            HttpResponse.sendJsonError(out, Messages.get("errors.tyres_nothing_to_update"));
            return;
        }

        // Invariants. Rejecting (rather than silently clamping) matters here:
        // the user typed these numbers, so a wrong one should be reported, not
        // quietly replaced by something they didn't ask for.
        int frontLow = merged.optInt("frontLow");
        int frontHigh = merged.optInt("frontHigh");
        int rearLow = merged.optInt("rearLow");
        int rearHigh = merged.optInt("rearHigh");
        int criticalLow = merged.optInt("criticalLow");
        if (frontHigh <= frontLow || rearHigh <= rearLow) {
            HttpResponse.sendJsonError(out, Messages.get("errors.tyres_order"));
            return;
        }
        if (criticalLow > Math.min(frontLow, rearLow)) {
            HttpResponse.sendJsonError(out, Messages.get("errors.tyres_critical_order"));
            return;
        }

        if (!UnifiedConfigManager.setTyres(patch)) {
            HttpResponse.sendJsonError(out, Messages.get("errors.tyres_persist_failed"));
            return;
        }
        logger.info("Tyre limits updated: " + patch);

        JSONObject response = new JSONObject();
        response.put("success", true);
        // Echo the post-write effective values so the client repaints from the
        // authoritative state rather than from what it optimistically sent.
        response.put("limits", UnifiedConfigManager.getTyreThresholds());
        response.put("pressureUnit", UnifiedConfigManager.getTyrePressureUnit());
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Strict integer coercion. Accepts a JSON number with no fractional part or
     * a numeric string (some clients stringify form fields); rejects everything
     * else — including booleans, which {@code Number} casts would let through.
     */
    private static Integer asInt(Object raw) {
        if (raw instanceof Integer) return (Integer) raw;
        if (raw instanceof Long) {
            long l = (Long) raw;
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : null;
        }
        if (raw instanceof Number) {
            double d = ((Number) raw).doubleValue();
            if (d != Math.floor(d) || Double.isInfinite(d) || Double.isNaN(d)) return null;
            if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
            return (int) d;
        }
        if (raw instanceof String) {
            try {
                return Integer.valueOf(((String) raw).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static JSONObject defaults() throws Exception {
        JSONObject d = new JSONObject();
        d.put("frontLow", UnifiedConfigManager.TYRE_LOW_DEFAULT_KPA);
        d.put("frontHigh", UnifiedConfigManager.TYRE_HIGH_DEFAULT_KPA);
        d.put("rearLow", UnifiedConfigManager.TYRE_LOW_DEFAULT_KPA);
        d.put("rearHigh", UnifiedConfigManager.TYRE_HIGH_DEFAULT_KPA);
        d.put("criticalLow", UnifiedConfigManager.TYRE_CRITICAL_LOW_DEFAULT_KPA);
        return d;
    }
}
