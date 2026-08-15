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
 * from the Trips page. {@link #load()} migrates the old resilience mirror when
 * needed, then removes it; {@link #save()} writes shared values only to
 * {@code tripAnalytics} so there is no second copy that can become stale.
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

    /** Matches the charging/trip database currency snapshot columns (VARCHAR(8)). */
    public static final int MAX_CURRENCY_LENGTH = 8;

    private static final int DEFAULT_FAST_SAMPLE_SEC = 12;
    private static final int MIN_FAST_SAMPLE_SEC = 10;
    private static final int MAX_FAST_SAMPLE_SEC = 30;

    private boolean enabled = false;         // recording is opt-in (zero extra work when disabled)
    private double dcRate = 0;               // optional separate DC tariff; 0 = use base rate
    private int fastSampleSec = DEFAULT_FAST_SAMPLE_SEC;

    // Read-through copies from tripAnalytics (single source of truth).
    private double electricityRate = 0;
    private String currency = "";

    @FunctionalInterface
    interface Persistence {
        boolean save(ChargingConfig config);

        default boolean save(ChargingConfig config,
                             boolean writeElectricityRate,
                             boolean writeCurrency) {
            return save(config);
        }

        default boolean save(ChargingConfig config,
                             boolean writeEnabled,
                             boolean writeDcRate,
                             boolean writeFastSampleSec,
                             boolean writeElectricityRate,
                             boolean writeCurrency) {
            return save(config, writeElectricityRate, writeCurrency);
        }

        default ChargingConfig loadSnapshot() {
            return null;
        }
    }

    private final Persistence persistence;

    public ChargingConfig() {
        this(null);
    }

    ChargingConfig(Persistence persistence) {
        this.persistence = persistence;
    }

    /** Load from UnifiedConfigManager. Returns true if either section was present. */
    public boolean load() {
        try {
            return loadFromRoot(loadRootWithPricingMigration(false));
        } catch (Exception e) {
            logger.error("ChargingConfig load error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Resolve the legacy chargingAnalytics pricing mirror under the same
     * cross-process lock as normal config writes. The detached fallback still
     * exposes legacy values for this process if persistence is temporarily
     * unavailable; a later load retries the idempotent migration.
     */
    private JSONObject loadRootWithPricingMigration(boolean forceReload) {
        JSONObject loaded = detached(
                (forceReload
                        ? UnifiedConfigManager.forceReload()
                        : UnifiedConfigManager.loadConfig()));
        if (!pricingMirrorNeedsReconciliation(loaded)) return loaded;

        try {
            return UnifiedConfigManager.runUnderConfigLock(() -> {
                JSONObject fresh = detached(
                        UnifiedConfigManager.forceReload());
                if (!reconcilePricingMirror(fresh)) return fresh;
                if (!UnifiedConfigManager.saveConfig(fresh)) {
                    logger.warn("Legacy charging pricing migration deferred");
                    return fresh;
                }
                // saveConfig treats a post-rename directory-sync failure as a
                // committed result and publishes its reconciled destination.
                return detached(UnifiedConfigManager.forceReload());
            });
        } catch (Exception unavailable) {
            logger.warn("Legacy charging pricing migration lock unavailable: "
                    + unavailable.getMessage());
            reconcilePricingMirror(loaded);
            return loaded;
        }
    }

    /**
     * Save current configuration. Charging-only keys go to {@code chargingAnalytics};
     * rate/currency go to {@code tripAnalytics} (the source of truth) so the value
     * stays consistent with the Trips page.
     */
    public boolean save() {
        return save(true, true, true, true, true);
    }

    /**
     * Persist a partial API update without rewriting omitted shared pricing
     * fields. The merge runs under the config lock, so a concurrent Trips write
     * between snapshot load and save is preserved.
     */
    boolean save(boolean writeElectricityRate, boolean writeCurrency) {
        return save(
                true, true, true,
                writeElectricityRate, writeCurrency);
    }

    boolean save(boolean writeEnabled,
                 boolean writeDcRate,
                 boolean writeFastSampleSec,
                 boolean writeElectricityRate,
                 boolean writeCurrency) {
        if (!isValidCurrency(currency)) return false;
        if (persistence != null) {
            return persistence.save(
                    this,
                    writeEnabled, writeDcRate, writeFastSampleSec,
                    writeElectricityRate, writeCurrency);
        }
        try {
            // Both sections form one logical setting. Commit them in one whole-config
            // write under the same cross-process lock used by updateSection; otherwise
            // the first section can persist while the second fails. Work on a detached
            // root because forceReload() returns the live cached JSONObject, which must
            // remain unchanged if saveConfig() rejects the write.
            boolean ok = UnifiedConfigManager.runUnderConfigLock(() -> {
                try {
                    JSONObject root =
                            new JSONObject(UnifiedConfigManager.forceReload().toString());
                    mergeIntoRoot(
                            root,
                            writeEnabled, writeDcRate, writeFastSampleSec,
                            writeElectricityRate, writeCurrency);
                    return UnifiedConfigManager.saveConfig(root);
                } catch (Exception e) {
                    logger.error("ChargingConfig atomic merge error: " + e.getMessage());
                    return false;
                }
            });
            if (ok) logger.info("ChargingConfig saved: enabled=" + enabled);
            return ok;
        } catch (Exception e) {
            logger.error("ChargingConfig save error: " + e.getMessage());
            return false;
        }
    }

    /** Merge this config's owned keys while preserving every sibling key/section. */
    void mergeIntoRoot(JSONObject root) {
        mergeIntoRoot(root, true, true);
    }

    void mergeIntoRoot(JSONObject root,
                       boolean writeElectricityRate,
                       boolean writeCurrency) {
        mergeIntoRoot(
                root,
                true, true, true,
                writeElectricityRate, writeCurrency);
    }

    void mergeIntoRoot(JSONObject root,
                       boolean writeEnabled,
                       boolean writeDcRate,
                       boolean writeFastSampleSec,
                       boolean writeElectricityRate,
                       boolean writeCurrency) {
        try {
            JSONObject trip = root.optJSONObject(TRIP_SECTION);
            if (trip == null) trip = new JSONObject();
            JSONObject section = root.optJSONObject(SECTION);
            if (section == null) section = new JSONObject();

            double persistedRate = trip.has("electricityRate")
                    ? trip.optDouble("electricityRate", electricityRate)
                    : section.optDouble("electricityRate", electricityRate);
            String persistedCurrency = trip.has("currency")
                    ? trip.optString("currency", currency)
                    : section.optString("currency", currency);
            double effectiveRate = writeElectricityRate
                    ? electricityRate
                    : persistedRate;
            String effectiveCurrency = writeCurrency
                    ? currency
                    : persistedCurrency;
            if (!isValidCurrency(effectiveCurrency)) effectiveCurrency = "";

            boolean effectiveEnabled = writeEnabled
                    ? enabled : section.optBoolean("enabled", enabled);
            double effectiveDcRate = writeDcRate
                    ? dcRate : section.optDouble("dcRate", dcRate);
            int effectiveFastSampleSec = writeFastSampleSec
                    ? fastSampleSec
                    : clampSample(section.optInt("fastSampleSec", fastSampleSec));
            section.put("enabled", effectiveEnabled);
            section.put("dcRate", effectiveDcRate);
            section.put("fastSampleSec", effectiveFastSampleSec);
            section.remove("electricityRate");
            section.remove("currency");
            root.put(SECTION, section);

            // tripAnalytics is authoritative. Omitted API fields resolve from
            // the current durable root (or the one-time legacy mirror) above.
            trip.put("electricityRate", effectiveRate);
            trip.put("currency", effectiveCurrency);
            root.put(TRIP_SECTION, trip);
        } catch (Exception e) {
            throw new IllegalStateException("Could not merge charging config", e);
        }
    }

    /**
     * Load a fresh, detached durable snapshot. The manager-owned instance is
     * never mutated by API reads or by staging a POST.
     */
    ChargingConfig loadSnapshot() {
        if (persistence != null) {
            ChargingConfig supplied = persistence.loadSnapshot();
            return supplied != null ? supplied : copy();
        }
        try {
            ChargingConfig out = new ChargingConfig();
            out.loadFromRoot(loadRootWithPricingMigration(true));
            return out;
        } catch (Exception e) {
            logger.error("ChargingConfig snapshot load error: " + e.getMessage());
            return null;
        }
    }

    boolean loadFromRoot(JSONObject cfg) {
        boolean found = false;
        JSONObject section = cfg != null ? cfg.optJSONObject(SECTION) : null;
        if (section != null) {
            enabled = section.optBoolean("enabled", false);
            dcRate = section.optDouble("dcRate", 0);
            fastSampleSec = clampSample(
                    section.optInt("fastSampleSec", DEFAULT_FAST_SAMPLE_SEC));
            found = true;
        }

        // Rate/currency read-through to tripAnalytics (source of truth).
        JSONObject trip = cfg != null ? cfg.optJSONObject(TRIP_SECTION) : null;
        if (trip != null) {
            electricityRate = trip.has("electricityRate")
                    ? trip.optDouble("electricityRate", 0)
                    : section != null
                            ? section.optDouble("electricityRate", 0) : 0;
            String loadedCurrency = trip.has("currency")
                    ? trip.optString("currency", "")
                    : section != null
                            ? section.optString("currency", "") : "";
            currency = isValidCurrency(loadedCurrency)
                    ? loadedCurrency : "";
            found = true;
        } else if (section != null) {
            // In-memory compatibility if the locked migration was deferred.
            electricityRate = section.optDouble("electricityRate", 0);
            String loadedCurrency = section.optString("currency", "");
            currency = isValidCurrency(loadedCurrency)
                    ? loadedCurrency : "";
        }
        logger.info("ChargingConfig loaded: enabled=" + enabled + " rate=" + electricityRate
                + " " + currency + " dcRate=" + dcRate + " fastSampleSec=" + fastSampleSec);
        return found;
    }

    static boolean pricingMirrorNeedsReconciliation(JSONObject root) {
        if (root == null) return false;
        JSONObject charging = root.optJSONObject(SECTION);
        return charging != null
                && (charging.has("electricityRate")
                        || charging.has("currency"));
    }

    /**
     * Migrate only absent trip keys from the legacy mirror, then remove the
     * mirror so future Trips-side writes cannot leave a stale second copy.
     */
    static boolean reconcilePricingMirror(JSONObject root) {
        if (root == null) return false;
        try {
            JSONObject charging = root.optJSONObject(SECTION);
            if (charging == null) return false;
            JSONObject trip = root.optJSONObject(TRIP_SECTION);
            boolean hasLegacyPricing =
                    charging.has("electricityRate") || charging.has("currency");
            if (trip == null) {
                if (!hasLegacyPricing) return false;
                trip = new JSONObject();
                root.put(TRIP_SECTION, trip);
            }

            boolean changed = false;
            if (!trip.has("electricityRate")
                    && charging.has("electricityRate")) {
                trip.put(
                        "electricityRate",
                        charging.opt("electricityRate"));
                changed = true;
            }
            if (!trip.has("currency") && charging.has("currency")) {
                trip.put(
                        "currency",
                        canonicalCurrency(charging.opt("currency")));
                changed = true;
            }

            if (trip.has("currency")) {
                String canonical = canonicalCurrency(trip.opt("currency"));
                changed |= putIfDifferent(trip, "currency", canonical);
            }
            if (charging.has("electricityRate")) {
                charging.remove("electricityRate");
                changed = true;
            }
            if (charging.has("currency")) {
                charging.remove("currency");
                changed = true;
            }
            root.put(SECTION, charging);
            root.put(TRIP_SECTION, trip);
            return changed;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not reconcile legacy charging pricing", e);
        }
    }

    private static boolean putIfDifferent(
            JSONObject target, String key, Object value) {
        if (target.has(key) && sameJsonValue(target.opt(key), value)) {
            return false;
        }
        try {
            target.put(key, value);
            return true;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not update charging pricing key " + key, e);
        }
    }

    private static boolean sameJsonValue(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null
                || left == JSONObject.NULL || right == JSONObject.NULL) {
            return false;
        }
        return left.toString().equals(right.toString());
    }

    private static String canonicalCurrency(Object value) {
        String candidate = value instanceof String ? (String) value : "";
        return isValidCurrency(candidate) ? candidate : "";
    }

    private static JSONObject detached(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not detach unified config", e);
        }
    }

    /** Detached copy used to stage an API update before any live mutation. */
    ChargingConfig copy() {
        ChargingConfig out = new ChargingConfig(persistence);
        out.enabled = enabled;
        out.dcRate = dcRate;
        out.fastSampleSec = fastSampleSec;
        out.electricityRate = electricityRate;
        out.currency = currency;
        return out;
    }

    public static boolean isValidCurrency(String value) {
        return value != null && value.length() <= MAX_CURRENCY_LENGTH;
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
    public void setCurrency(String currency) {
        this.currency = isValidCurrency(currency) ? currency : "";
    }

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
