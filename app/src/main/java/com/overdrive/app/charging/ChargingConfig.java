package com.overdrive.app.charging;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONObject;

/**
 * Persistent configuration for Charging Analytics.
 *
 * <p>Stored in the {@code "chargingAnalytics"} section of
 * {@code /data/local/tmp/overdrive_config.json} via {@link UnifiedConfigManager}.
 *
 * <p><b>Rate/currency are NOT owned here.</b> Electricity rate and currency are
 * the same value used by Trips, so they live in the {@code "tripAnalytics"}
 * section (see {@link com.overdrive.app.trips.TripConfig}) as the single source
 * of truth — otherwise the per-kWh cost shown on the Charging page would diverge
 * from the Trips page. {@link #load()} reads them through from {@code tripAnalytics};
 * {@link #save()} mirror-writes any change back into {@code tripAnalytics} so a
 * rate edit on either page stays consistent, and also keeps a resilience copy in
 * {@code chargingAnalytics}.
 *
 * <p>{@code chargingAnalytics} owns only charging-specific keys: {@code enabled},
 * an optional separate DC tariff ({@code dcRate}; 0 = use base rate), and the
 * in-session fast-sampler interval ({@code fastSampleSec}, clamped 10–30 s).
 */
public class ChargingConfig {

    private static final String TAG = "ChargingConfig";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String SECTION = "chargingAnalytics";
    private static final String TRIP_SECTION = "tripAnalytics";

    private static final int DEFAULT_FAST_SAMPLE_SEC = 12;
    private static final int MIN_FAST_SAMPLE_SEC = 10;
    private static final int MAX_FAST_SAMPLE_SEC = 30;

    private boolean enabled = false;         // recording is opt-in (zero extra work when disabled)
    private double dcRate = 0;               // optional separate DC tariff; 0 = use base rate
    private int fastSampleSec = DEFAULT_FAST_SAMPLE_SEC;

    // Read-through copies from tripAnalytics (single source of truth).
    private double electricityRate = 0;
    private String currency = "";

    public ChargingConfig() { }

    /** Load from UnifiedConfigManager. Returns true if either section was present. */
    public boolean load() {
        boolean found = false;
        try {
            JSONObject cfg = UnifiedConfigManager.loadConfig();

            JSONObject section = cfg != null ? cfg.optJSONObject(SECTION) : null;
            if (section != null) {
                enabled = section.optBoolean("enabled", false);
                dcRate = section.optDouble("dcRate", 0);
                fastSampleSec = clampSample(section.optInt("fastSampleSec", DEFAULT_FAST_SAMPLE_SEC));
                found = true;
            }

            // Rate/currency read-through to tripAnalytics (source of truth).
            JSONObject trip = cfg != null ? cfg.optJSONObject(TRIP_SECTION) : null;
            if (trip != null) {
                electricityRate = trip.optDouble("electricityRate", 0);
                currency = trip.optString("currency", "");
                found = true;
            }
            logger.info("ChargingConfig loaded: enabled=" + enabled + " rate=" + electricityRate
                    + " " + currency + " dcRate=" + dcRate + " fastSampleSec=" + fastSampleSec);
        } catch (Exception e) {
            logger.error("ChargingConfig load error: " + e.getMessage());
        }
        return found;
    }

    /**
     * Save current configuration. Charging-only keys go to {@code chargingAnalytics};
     * rate/currency are mirror-written into {@code tripAnalytics} (the source of truth)
     * so the value stays consistent with the Trips page.
     */
    public boolean save() {
        boolean ok = true;
        try {
            // Write a DELTA of only the keys this class owns. updateSection MERGES
            // each key into the on-disk section under a cross-process file lock, so
            // TariffManager's sibling "tariffs"/"defaultTariffId" survive without
            // being re-sent. Do NOT read-modify-write the object loadConfig()
            // returns: that is the live CACHED instance other daemon readers price
            // from, and re-sending a stale copy of it could resurrect a tariff the
            // user just deleted.
            JSONObject section = new JSONObject();
            section.put("enabled", enabled);
            section.put("dcRate", dcRate);
            section.put("fastSampleSec", fastSampleSec);
            // resilience copy
            section.put("electricityRate", electricityRate);
            section.put("currency", currency);
            ok &= UnifiedConfigManager.updateSection(SECTION, section);

            // Mirror rate/currency into tripAnalytics (source of truth). Delta
            // again — updateSection's merge preserves the other Trips keys
            // (distanceUnit, tankCapacityL, fuelPricePerL, fuelUnit, enabled).
            JSONObject trip = new JSONObject();
            trip.put("electricityRate", electricityRate);
            trip.put("currency", currency);
            ok &= UnifiedConfigManager.updateSection(TRIP_SECTION, trip);

            if (ok) logger.info("ChargingConfig saved: enabled=" + enabled);
        } catch (Exception e) {
            logger.error("ChargingConfig save error: " + e.getMessage());
            ok = false;
        }
        return ok;
    }

    private static int clampSample(int v) {
        if (v < MIN_FAST_SAMPLE_SEC) return MIN_FAST_SAMPLE_SEC;
        if (v > MAX_FAST_SAMPLE_SEC) return MAX_FAST_SAMPLE_SEC;
        return v;
    }

    // ==================== GETTERS ====================

    public boolean isEnabled() { return enabled; }
    public double getDcRate() { return dcRate; }
    public int getFastSampleSec() { return fastSampleSec; }
    public double getElectricityRate() { return electricityRate; }
    public String getCurrency() { return currency; }

    // Rate selection for cost lives in SocHistoryDatabase.priceSession(isDc,
    // lat, lng) — the single point shared by every cost-writing path — since
    // that is where sessions are priced and the is_dc verdict is computed. It
    // consults the location-matched TariffProfile first and falls back to these
    // global values. ChargingConfig owns only the stored global dcRate (see
    // getDcRate); per-location rates belong to TariffManager.

    // ==================== SETTERS ====================

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setDcRate(double rate) { this.dcRate = (rate > 0 && rate < 100000) ? rate : 0; }
    public void setFastSampleSec(int sec) { this.fastSampleSec = clampSample(sec); }
    public void setElectricityRate(double rate) { this.electricityRate = (rate > 0 && rate < 100000) ? rate : 0; }
    public void setCurrency(String currency) { this.currency = currency != null ? currency : ""; }

    // ==================== UTILITY ====================

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("enabled", enabled);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency);
            json.put("dcRate", dcRate);
            json.put("fastSampleSec", fastSampleSec);
        } catch (Exception e) {
            logger.error("toJson error: " + e.getMessage());
        }
        return json;
    }

    @Override
    public String toString() {
        return "ChargingConfig{enabled=" + enabled + ", rate=" + electricityRate + "}";
    }
}
