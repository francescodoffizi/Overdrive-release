package com.overdrive.app.telemetry;

import org.json.JSONArray;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable selection of which telemetry fields the burn-in overlay draws.
 *
 * <p>Each recording flow (ACC-on trips, ACC-off surveillance, OEM dashcam)
 * carries its OWN {@code TelemetryFields} — they are never shared. The set is
 * resolved from {@code UnifiedConfigManager.telemetryOverlay.fields.<flow>} and
 * pushed to {@link OverlayBitmapRenderer#setActiveFields}.
 *
 * <p><b>No-regression contract:</b> when the config has no explicit selection
 * for a flow (fresh install, or an install that predates this feature),
 * {@link #legacyDefault()} is used — the exact eight fields the overlay drew
 * before this feature existed, all enabled. The new fields (battery %, 12V,
 * low/high beam, location) default OFF, so an app update never changes what an
 * existing overlay shows.
 *
 * <p>The class is a thin wrapper over an {@link EnumSet}; construction and
 * {@link #has} are allocation-free on the render hot path.
 */
public final class TelemetryFields {

    /**
     * Every field the overlay can draw. Wire keys are stable strings persisted
     * in config and exchanged with the web UI — do NOT rename them.
     */
    public enum Field {
        // ---- Legacy set (drawn before this feature; default ON) ----
        SPEED("speed", true),
        GEAR("gear", true),
        ACCEL_PEDAL("accelPedal", true),
        BRAKE_PEDAL("brakePedal", true),
        SEATBELT_DRIVER("seatbeltDriver", true),
        SEATBELT_PASSENGER("seatbeltPassenger", true),
        TURN_SIGNALS("turnSignals", true),
        TIMESTAMP("timestamp", true),
        // ---- New optional fields (default OFF) ----
        BATTERY_PERCENT("batteryPercent", false),
        VOLTAGE_12V("voltage12v", false),
        LOW_BEAM("lowBeam", false),
        HIGH_BEAM("highBeam", false),
        LOCATION("location", false),
        // VIN is a persistent vehicle identifier burned into the footage, which
        // may be auto-uploaded (Telegram). Default OFF like every other new
        // field, so it only ever appears when the user explicitly opts in.
        VIN("vin", false);

        private final String key;
        private final boolean legacyDefault;

        Field(String key, boolean legacyDefault) {
            this.key = key;
            this.legacyDefault = legacyDefault;
        }

        public String getKey() {
            return key;
        }

        /** Whether this field is part of the pre-feature default (all ON). */
        public boolean isLegacyDefault() {
            return legacyDefault;
        }

        public static Field fromKey(String key) {
            if (key == null) return null;
            for (Field f : values()) {
                if (f.key.equalsIgnoreCase(key)) return f;
            }
            return null;
        }
    }

    private final Set<Field> fields;

    private TelemetryFields(Set<Field> fields) {
        // Store an unmodifiable snapshot so instances are safe to publish across
        // threads (the renderer reads this from its 2 Hz worker via a volatile).
        this.fields = Collections.unmodifiableSet(fields.isEmpty()
                ? EnumSet.noneOf(Field.class)
                : EnumSet.copyOf(fields));
    }

    /**
     * The exact set the overlay drew before this feature — all eight legacy
     * fields ON, every new field OFF. Used whenever a flow has no persisted
     * selection so an app update is visually identical for existing overlays.
     */
    public static TelemetryFields legacyDefault() {
        EnumSet<Field> s = EnumSet.noneOf(Field.class);
        for (Field f : Field.values()) {
            if (f.isLegacyDefault()) s.add(f);
        }
        return new TelemetryFields(s);
    }

    /** Empty selection — draws nothing. */
    public static TelemetryFields none() {
        return new TelemetryFields(EnumSet.noneOf(Field.class));
    }

    /**
     * Parse a JSON array of field keys (e.g. {@code ["speed","gear",...]}).
     * Unknown keys are ignored. A {@code null} array yields
     * {@link #legacyDefault()} — the safe no-regression fallback — while an
     * explicit empty array yields {@link #none()} (the user deselected all).
     */
    public static TelemetryFields fromJsonArray(JSONArray arr) {
        if (arr == null) return legacyDefault();
        EnumSet<Field> s = EnumSet.noneOf(Field.class);
        for (int i = 0; i < arr.length(); i++) {
            Field f = Field.fromKey(arr.optString(i, null));
            if (f != null) s.add(f);
        }
        return new TelemetryFields(s);
    }

    /**
     * Like {@link #fromJsonArray} but treats {@code null} as an explicit empty
     * selection ({@link #none()}) rather than the legacy default. Use when the
     * caller has already confirmed the list is present (e.g. a POST body that
     * contains the key) so a deliberately-empty selection is honored.
     */
    public static TelemetryFields fromJsonArrayStrict(JSONArray arr) {
        if (arr == null) return none();
        return fromJsonArray(arr);
    }

    /** Serialize the current selection as a JSON array of stable field keys. */
    public JSONArray toJsonArray() {
        JSONArray arr = new JSONArray();
        // Iterate the enum (stable order) rather than the set so output order
        // is deterministic regardless of how the set was built.
        for (Field f : Field.values()) {
            if (fields.contains(f)) arr.put(f.getKey());
        }
        return arr;
    }

    public boolean has(Field f) {
        return fields.contains(f);
    }

    /** True if any of the given fields is enabled — used to gate slow reads. */
    public boolean hasAny(Field... candidates) {
        for (Field f : candidates) {
            if (fields.contains(f)) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TelemetryFields)) return false;
        return fields.equals(((TelemetryFields) o).fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return "TelemetryFields" + fields;
    }
}
