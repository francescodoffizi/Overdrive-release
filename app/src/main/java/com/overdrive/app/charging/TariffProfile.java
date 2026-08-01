package com.overdrive.app.charging;

import org.json.JSONObject;

import java.util.UUID;

/**
 * A named electricity tariff bound to a place.
 *
 * <p>A profile answers "what does a kWh cost <i>here</i>?". Each carries a
 * user-set {@code label} ("Home", "Office garage", "Ionity A9"), a centroid +
 * radius that {@link TariffManager} matches the charge location against, and an
 * AC rate with an optional DC-fast rate for sites billed differently by gun.
 *
 * <p>Modelled on {@link com.overdrive.app.surveillance.SafeLocation} — same
 * short-UUID id, same clamp-on-both-ctor-and-setter discipline (the config file
 * is world-writable, so a hand-edited radius or a negative rate must not be
 * honoured), same JSON round-trip shape.
 *
 * <p>{@code radiusMeters} allows a wider span than a SafeLocation zone (up to
 * 2 km): a tariff is a <i>billing</i> region, not a privacy fence — one
 * apartment-complex or campus rate can legitimately cover every parking level
 * and both entrances, and a coarse match here is harmless (it only picks a
 * price) where a coarse safe-zone would wrongly disarm surveillance.
 */
public class TariffProfile {

    /** Widest allowed match radius (m). See class doc for why this exceeds SafeLocation's 500. */
    public static final int MAX_RADIUS_M = 2000;
    /** Narrowest allowed match radius (m) — below typical GPS error a profile could never match. */
    public static final int MIN_RADIUS_M = 25;
    /**
     * Default radius for a profile captured from the current position: 50 m.
     *
     * <p>Tight enough that a tariff means "this charger", not "this
     * neighbourhood" — two chargers on different tariffs across a street stay
     * distinct — while still comfortably covering normal GPS scatter for a car
     * re-parked in a different bay on the same driveway. Widen it per-profile for
     * a large site (a campus or multi-level garage).
     */
    public static final int DEFAULT_RADIUS_M = 50;

    /** Upper bound on a per-kWh / per-litre price, mirroring ChargingConfig's clamp. */
    private static final double MAX_RATE = 100000;

    private String id;
    private String label;
    private double latitude;
    private double longitude;
    private int radiusMeters;
    /** Per-kWh price for AC / unknown-gun sessions. 0 = unset (falls back to the global rate). */
    private double acRate;
    /** Per-kWh price for confidently-DC sessions. 0 = unset (uses this profile's acRate). */
    private double dcRate;
    /** Currency symbol snapshot ("$", "₹", "CHF "). Empty = use the global currency. */
    private String currency;
    private boolean enabled;
    private long createdAt;
    /** Last time this profile priced a session — drives the "recently used" ordering. */
    private long lastUsedAt;
    /** How many sessions this profile has priced. Shown as provenance in the UI. */
    private int useCount;

    public TariffProfile(String label, double lat, double lng, int radiusMeters,
                         double acRate, double dcRate, String currency) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.label = sanitizeLabel(label);
        this.latitude = clampLat(lat);
        this.longitude = clampLng(lng);
        this.radiusMeters = clampRadius(radiusMeters);
        this.acRate = clampRate(acRate);
        this.dcRate = clampRate(dcRate);
        this.currency = currency != null ? currency : "";
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedAt = 0;
        this.useCount = 0;
    }

    /** Deserialize from JSON. Clamps on load — the config file is user-writable. */
    public TariffProfile(JSONObject json) {
        this.id = json.optString("id", UUID.randomUUID().toString().substring(0, 8));
        this.label = sanitizeLabel(json.optString("label", ""));
        this.latitude = clampLat(json.optDouble("lat", 0.0));
        this.longitude = clampLng(json.optDouble("lng", 0.0));
        this.radiusMeters = clampRadius(json.optInt("radiusM", DEFAULT_RADIUS_M));
        this.acRate = clampRate(json.optDouble("acRate", 0));
        this.dcRate = clampRate(json.optDouble("dcRate", 0));
        this.currency = json.optString("currency", "");
        this.enabled = json.optBoolean("enabled", true);
        this.createdAt = json.optLong("createdAt", System.currentTimeMillis());
        this.lastUsedAt = json.optLong("lastUsedAt", 0);
        this.useCount = Math.max(0, json.optInt("useCount", 0));
    }

    /** Serialize to JSON (both the config file and the API response use this shape). */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("label", label);
            json.put("lat", latitude);
            json.put("lng", longitude);
            json.put("radiusM", radiusMeters);
            json.put("acRate", acRate);
            json.put("dcRate", dcRate);
            json.put("currency", currency);
            json.put("enabled", enabled);
            json.put("createdAt", createdAt);
            json.put("lastUsedAt", lastUsedAt);
            json.put("useCount", useCount);
        } catch (Exception ignored) {}
        return json;
    }

    /**
     * Per-kWh price this profile charges for a session, given the tri-state DC
     * verdict used everywhere else ({@code 1}=DC, {@code 0}=AC, {@code -1}=unknown).
     *
     * <p>Only a CONFIDENT DC verdict earns the DC premium — an unknown gun falls
     * back to the AC rate, matching {@code SocHistoryDatabase.priceSession}'s
     * safe default. Returns 0 when this profile prices nothing for that gun,
     * which callers treat as "fall through to the global rate".
     */
    public double rateFor(int isDc) {
        if (isDc == 1 && dcRate > 0) return dcRate;
        return acRate;
    }

    /** True when this profile can price at least one kind of session. */
    public boolean hasAnyRate() {
        return acRate > 0 || dcRate > 0;
    }

    /** Record that this profile priced a session (drives ordering + provenance). */
    public void markUsed(long whenMs) {
        if (whenMs > this.lastUsedAt) this.lastUsedAt = whenMs;
        this.useCount++;
    }

    // ==================== CLAMPS ====================

    /**
     * Reject NaN / out-of-range coordinates, collapsing them to 0.
     *
     * <p>Coordinates were the only field left unclamped while rates and radius were
     * validated on every path — and the config file is world-writable. A NaN here is
     * worse than a wrong number: every distance comparison against NaN is false, so
     * a hand-edited coordinate could make one tariff appear to contain the whole
     * planet. 0 is already the "no location" sentinel the matcher skips.
     */
    private static double clampLat(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < -90 || v > 90) return 0;
        return v;
    }

    private static double clampLng(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < -180 || v > 180) return 0;
        return v;
    }

    private static int clampRadius(int r) {
        return Math.max(MIN_RADIUS_M, Math.min(MAX_RADIUS_M, r));
    }

    /** Reject NaN / negative / absurd prices, collapsing them to 0 ("unset"). */
    private static double clampRate(double r) {
        if (Double.isNaN(r) || Double.isInfinite(r)) return 0;
        return (r > 0 && r < MAX_RATE) ? r : 0;
    }

    /**
     * Labels are rendered into the UI and persisted to a world-writable file, so
     * strip control characters and cap the length. Empty stays empty — the UI
     * substitutes the resolved place name when a label wasn't set.
     */
    private static String sanitizeLabel(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return out.length() > 48 ? out.substring(0, 48) : out;
    }

    // ==================== GETTERS ====================

    public String getId() { return id; }
    public String getLabel() { return label; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getRadiusMeters() { return radiusMeters; }
    public double getAcRate() { return acRate; }
    public double getDcRate() { return dcRate; }
    public String getCurrency() { return currency; }
    public boolean isEnabled() { return enabled; }
    public long getCreatedAt() { return createdAt; }
    public long getLastUsedAt() { return lastUsedAt; }
    public int getUseCount() { return useCount; }

    // ==================== SETTERS ====================

    public void setLabel(String label) { this.label = sanitizeLabel(label); }
    public void setLatitude(double lat) { this.latitude = clampLat(lat); }
    public void setLongitude(double lng) { this.longitude = clampLng(lng); }
    public void setRadiusMeters(int r) { this.radiusMeters = clampRadius(r); }
    public void setAcRate(double r) { this.acRate = clampRate(r); }
    public void setDcRate(double r) { this.dcRate = clampRate(r); }
    public void setCurrency(String c) { this.currency = c != null ? c : ""; }
    public void setEnabled(boolean e) { this.enabled = e; }

    @Override
    public String toString() {
        return "TariffProfile{" + label + " @" + latitude + "," + longitude
                + " r=" + radiusMeters + "m ac=" + acRate + " dc=" + dcRate + "}";
    }
}
