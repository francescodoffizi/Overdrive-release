package com.overdrive.app.byd;

import android.content.ContentResolver;
import android.content.Context;

import com.overdrive.app.daemon.DaemonBootstrap;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local read/write access to BYD's in-car settings (the "carsettings" provider),
 * exposed so a curated allowlist of CAN-backed vehicle settings can be surfaced as
 * controllable Home Assistant entities. Fully local — no BYD cloud involved.
 *
 * BYD's own settings UI reads/writes through {@code android.provider.CarSettings.UserTableData}
 * (e.g. {@code getSystemInt(cr,key,def)} / {@code putInt(cr,key,value)}). That class is
 * provided by the system framework at runtime, so we reach it by reflection — exactly
 * like the BYDAuto* HAL devices — and fail closed (read = default, write = false) when
 * it's unavailable (e.g. on a dev emulator).
 *
 * Only the curated {@link #registry()} keys are ever read or written; arbitrary keys are
 * rejected to keep CAN writes safe and predictable.
 */
public final class BydCarSettings {

    private static final String TAG = "BydCarSettings";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CLS = "android.provider.CarSettings$UserTableData";
    /**
     * The provider exposes SEVERAL independent tables — {@code UserTableData} (the per-user
     * settings the curated {@link #REGISTRY} lives in), {@code Config}, and {@code Global}.
     * They are NOT interchangeable: a key written to the wrong table is simply a different
     * key, so it either fails or silently stores a value nothing reads.
     *
     * <p>{@code atmosphere_lamp} — the ambient main-switch flag — lives in {@code Config}
     * (verified against the reference app, which reads/writes it via
     * {@code CarSettings$Config.getInt/putInt}), hence this second class name.
     */
    private static final String CLS_CONFIG = "android.provider.CarSettings$Config";

    /** How a setting maps onto a Home Assistant entity. */
    public enum Kind { BOOL, INT_RANGE, INT_ENUM }

    /** One curated, controllable car setting. */
    public static final class CarSetting {
        public final String key;       // provider key
        public final Kind kind;
        public final String name;      // HA friendly name
        public final String icon;      // mdi:...
        public final int min, max, step;   // INT_RANGE
        public final int[] options;        // INT_ENUM allowed values
        public final String unit;          // INT_RANGE unit (nullable)
        CarSetting(String key, Kind kind, String name, String icon,
                   int min, int max, int step, int[] options, String unit) {
            this.key = key; this.kind = kind; this.name = name; this.icon = icon;
            this.min = min; this.max = max; this.step = step; this.options = options; this.unit = unit;
        }
    }

    // ── Curated allowlist (verified key names from the BYD carsettings provider) ──
    private static final List<CarSetting> REGISTRY = buildRegistry();

    private static List<CarSetting> buildRegistry() {
        List<CarSetting> r = new ArrayList<>();
        // Locking / security / convenience
        r.add(bool("children_lock", "Child Lock", "mdi:car-door-lock"));
        r.add(bool("shut_window_after_locking", "Close Windows on Lock", "mdi:window-closed-variant"));
        r.add(bool("auto_mirror_for_lock", "Fold Mirrors on Lock", "mdi:car-side"));
        r.add(bool("rain_close_window", "Auto-close Windows in Rain", "mdi:weather-rainy"));
        r.add(enumInt("auto_lock_time", "Auto-lock Delay", "mdi:lock-clock", new int[]{0, 10, 30, 60, 120}));
        // Driving dynamics / ADAS
        r.add(bool("esp_assist", "Stability Control (ESP)", "mdi:car-traction-control"));
        r.add(bool("avh_assist", "Auto Vehicle Hold", "mdi:car-brake-hold"));
        r.add(bool("aeb", "Automatic Emergency Braking", "mdi:car-emergency"));
        r.add(bool("lane_keeping", "Lane Keeping Assist", "mdi:road-variant"));
        r.add(bool("daytime_running_lamp", "Daytime Running Lights", "mdi:car-light-dimmed"));
        r.add(enumInt("energy_recycle_setting", "Regen Level", "mdi:battery-charging", new int[]{0, 1, 2, 3}));
        r.add(enumInt("power_management", "Drive Mode", "mdi:car-sports", new int[]{0, 1, 2}));
        r.add(enumInt("auto_wipe", "Auto Wiper Sensitivity", "mdi:wiper", new int[]{0, 1, 2, 3}));
        // Charging / units / comfort
        r.add(enumInt("charge_limit", "Charge Limit %", "mdi:battery-charging-80", new int[]{50, 60, 70, 80, 90, 100}));
        r.add(enumInt("unit_temperature", "Temperature Unit (0=C,1=F)", "mdi:temperature-celsius", new int[]{0, 1}));
        r.add(range("lighting_ambient_brightness", "Ambient Light Brightness", "mdi:track-light", 0, 10, 1, null));
        return Collections.unmodifiableList(r);
    }

    /**
     * Keys this class may WRITE but which are NOT surfaced as their own Home Assistant
     * entity. The {@link #REGISTRY} above doubles as the HA entity list (see
     * {@code VehicleControlCatalog}'s tier-3 loop), so a key that already has a richer
     * dedicated control must not go there — it would publish a second, weaker entity for
     * the same function and the two would disagree.
     *
     * <p>{@code atmosphere_lamp} is the interior-ambient main switch as BYD's own settings
     * UI stores it. It is the LAST tier of {@link BydDataCollector#setAmbientLightEnabled}
     * (after the Light-device main switch and the Bodywork execute feature), matching the
     * reference app's three-tier chain. Ambient power already has a dedicated control that
     * drives all three tiers, hence write-only here.
     */
    private static final java.util.Set<String> INTERNAL_WRITABLE =
            java.util.Set.of("atmosphere_lamp");

    /**
     * Provider key for the interior-ambient main switch (1 = on, 0 = off).
     *
     * <p>Lives in the {@code Config} table, NOT {@code UserTableData} — see {@link #CLS_CONFIG}.
     */
    public static final String KEY_ATMOSPHERE_LAMP = "atmosphere_lamp";

    /** Which provider table a key belongs to. Getting this wrong reads/writes a different key. */
    private static String tableFor(String key) {
        return INTERNAL_WRITABLE.contains(key) ? CLS_CONFIG : CLS;
    }

    private static CarSetting bool(String k, String n, String i) {
        return new CarSetting(k, Kind.BOOL, n, i, 0, 1, 1, null, null);
    }
    private static CarSetting enumInt(String k, String n, String i, int[] opts) {
        return new CarSetting(k, Kind.INT_ENUM, n, i, 0, 0, 0, opts, null);
    }
    private static CarSetting range(String k, String n, String i, int min, int max, int step, String unit) {
        return new CarSetting(k, Kind.INT_RANGE, n, i, min, max, step, null, unit);
    }

    public static List<CarSetting> registry() { return REGISTRY; }

    private static CarSetting find(String key) {
        for (CarSetting s : REGISTRY) if (s.key.equals(key)) return s;
        return null;
    }

    // ── Singleton ────────────────────────────────────────────────────────
    private static volatile BydCarSettings instance;
    public static BydCarSettings getInstance() {
        if (instance == null) {
            synchronized (BydCarSettings.class) {
                if (instance == null) instance = new BydCarSettings();
            }
        }
        return instance;
    }

    private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();
    private volatile long lastRefreshMs = 0;
    private static final long REFRESH_TTL_MS = 30_000;
    private volatile boolean unavailable = false;

    private BydCarSettings() {}

    private ContentResolver resolver() {
        try {
            Context ctx = DaemonBootstrap.getContext();
            return ctx != null ? ctx.getContentResolver() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read an int setting via the BYD framework helper (reflection). */
    public int readInt(String key, int def) {
        ContentResolver cr = resolver();
        if (cr == null) return def;
        boolean internal = INTERNAL_WRITABLE.contains(key);
        try {
            Class<?> cls = Class.forName(tableFor(key));
            Method m;
            try {
                m = cls.getMethod("getSystemInt", ContentResolver.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                try {
                    m = cls.getMethod("getInt", ContentResolver.class, String.class, int.class);
                } catch (NoSuchMethodException e2) {
                    // The Config table's getInt is the 2-arg form (no default) — see the
                    // reference app. Fall back to it and treat "no value" as the default.
                    Method m2 = cls.getMethod("getInt", ContentResolver.class, String.class);
                    Object v2 = m2.invoke(null, cr, key);
                    return (v2 instanceof Integer) ? (Integer) v2 : def;
                }
            }
            Object v = m.invoke(null, cr, key, def);
            return (v instanceof Integer) ? (Integer) v : def;
        } catch (Throwable t) {
            // Only a REGISTRY-key failure marks the whole provider unavailable (that flag
            // suppresses refresh() for every curated setting). An internal key lives in a
            // different table, so its absence says nothing about UserTableData — letting it
            // set the flag would silently stop all car-settings refreshes on a trim that
            // simply lacks this one key.
            if (!internal) unavailable = true;
            logger.debug("readInt(" + key + ") failed: " + t.getMessage());
            return def;
        }
    }

    /** Write an int setting (only allowlisted keys). Returns true on success. */
    public boolean writeInt(String key, int value) {
        CarSetting s = find(key);
        if (s == null) {
            // A write-only internal key (see INTERNAL_WRITABLE) is allowlisted for writing
            // but has no CarSetting descriptor, so it is domain-checked as a plain 0/1 flag
            // here rather than through isValid. Anything else is still refused.
            if (INTERNAL_WRITABLE.contains(key)) {
                if (value != 0 && value != 1) {
                    logger.warn("Refusing out-of-domain value " + value + " for " + key);
                    return false;
                }
                return putInt(key, value);
            }
            logger.warn("Refusing to write non-allowlisted setting: " + key);
            return false;
        }
        if (!isValid(s, value)) {
            logger.warn("Refusing out-of-domain value " + value + " for " + key);
            return false;
        }
        return putInt(key, value);
    }

    /**
     * As {@link #writeInt}, but true ONLY when the provider explicitly CONFIRMED the write
     * (returned {@code Boolean.TRUE}). A void/unknown return — which {@code writeInt} treats
     * optimistically as success — reports false here.
     *
     * <p>For a fallback CHAIN this distinction is the whole point: an optimistic "true" from
     * the last tier would report overall success while nothing physically moved, and callers
     * that surface that boolean (or use it to pick the next tier) would be misled. Entities
     * that merely echo their commanded value keep using the lenient {@link #writeInt}, whose
     * behaviour is deliberately unchanged.
     */
    public boolean writeIntConfirmed(String key, int value) {
        CarSetting s = find(key);
        if (s == null && !INTERNAL_WRITABLE.contains(key)) {
            logger.warn("Refusing to write non-allowlisted setting: " + key);
            return false;
        }
        if (s != null ? !isValid(s, value) : (value != 0 && value != 1)) {
            logger.warn("Refusing out-of-domain value " + value + " for " + key);
            return false;
        }
        return putIntRaw(key, value) == CONFIRMED;
    }

    /** The raw provider write, shared by the registry and internal-key paths above. */
    private boolean putInt(String key, int value) {
        // Optimistic: anything short of an explicit "false" / a throw counts as success,
        // because this provider's putInt is void on some trims. Unchanged behaviour.
        return putIntRaw(key, value) != REJECTED;
    }

    private static final int CONFIRMED = 1;   // provider returned Boolean.TRUE
    private static final int UNCONFIRMED = 0; // void / non-boolean return — cannot tell
    private static final int REJECTED = -1;   // explicit Boolean.FALSE, or the call threw

    /**
     * The single provider write. Returns {@link #CONFIRMED} / {@link #UNCONFIRMED} /
     * {@link #REJECTED} so each caller can choose its own strictness rather than collapsing
     * "it didn't contradict us" and "it said yes" into one boolean.
     */
    private int putIntRaw(String key, int value) {
        ContentResolver cr = resolver();
        if (cr == null) return REJECTED;
        // Only REGISTRY keys belong in the cache — it is dumped wholesale into telemetry as
        // setting_<key> by snapshotInto, so caching an internal key would publish a phantom
        // field with no matching entity.
        boolean cacheable = !INTERNAL_WRITABLE.contains(key);
        try {
            Class<?> cls = Class.forName(tableFor(key));
            Method m;
            try {
                m = cls.getMethod("putInt", ContentResolver.class, String.class, int.class);
            } catch (NoSuchMethodException e) {
                m = cls.getMethod("setSystemInt", ContentResolver.class, String.class, int.class);
            }
            Object r = m.invoke(null, cr, key, value);
            if (r instanceof Boolean) {
                if (!(Boolean) r) {
                    logger.debug("writeInt(" + key + "=" + value + ") refused by provider");
                    return REJECTED;
                }
                if (cacheable) cache.put(key, value);
                return CONFIRMED;
            }
            // Void / unknown return: cache the intent (the pre-existing behaviour) but tell
            // strict callers we could not confirm it.
            if (cacheable) cache.put(key, value);
            return UNCONFIRMED;
        } catch (Throwable t) {
            logger.warn("writeInt(" + key + "=" + value + ") failed: " + t.getMessage());
            return REJECTED;
        }
    }

    private static boolean isValid(CarSetting s, int value) {
        switch (s.kind) {
            case BOOL: return value == 0 || value == 1;
            case INT_RANGE: return value >= s.min && value <= s.max;
            case INT_ENUM:
                if (s.options == null) return true;
                for (int o : s.options) if (o == value) return true;
                return false;
            default: return false;
        }
    }

    /** Refresh the cache from the provider (throttled). */
    public void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastRefreshMs) < REFRESH_TTL_MS) return;
        lastRefreshMs = now;
        if (unavailable && !force) return;
        for (CarSetting s : REGISTRY) {
            int v = readInt(s.key, Integer.MIN_VALUE);
            if (v != Integer.MIN_VALUE) cache.put(s.key, v);
        }
    }

    /**
     * Append the cached settings as {@code setting_<key>} fields to the telemetry
     * snapshot so they flow through the per-field state topics for HA read-back.
     */
    public void snapshotInto(JSONObject payload) {
        refresh(false);
        for (CarSetting s : REGISTRY) {
            Integer v = cache.get(s.key);
            if (v != null) {
                try { payload.put("setting_" + s.key, (int) v); } catch (Exception ignored) {}
            }
        }
    }

    public boolean isUnavailable() { return unavailable; }
}
