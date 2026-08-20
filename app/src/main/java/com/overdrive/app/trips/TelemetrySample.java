package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Immutable value object for a single telemetry reading.
 * Thread-safe by design — all fields are final.
 *
 * Serialized to compact JSON keys for storage efficiency in .jsonl.gz files.
 */
public class TelemetrySample {

    public final long timestampMs;
    public final int speedKmh;
    public final int accelPedalPercent;   // 0-100
    public final int brakePedalPercent;   // 0-100
    public final boolean brakePedalPressed;
    public final int gearMode;            // 1=P, 2=R, 3=N, 4=D, 5=M, 6=S
    public final double lat;
    public final double lon;
    public final double altitude;
    /** Reported 1-sigma GPS vertical accuracy in metres; 0.0 = unreported
     *  (older telemetry files, or a HAL that doesn't populate it). Used to
     *  gate elevation accumulation (see ElevationEstimator). */
    public final double verticalAccuracyM;
    /** True when {@link #altitude} is mean-sea-level (geoid-corrected) rather
     *  than WGS84-ellipsoidal. The two differ by the local geoid undulation
     *  (tens of metres); a mid-trip source flip must reset elevation deltas. */
    public final boolean altitudeIsMsl;

    public TelemetrySample(long timestampMs, int speedKmh, int accelPedalPercent,
                           int brakePedalPercent, boolean brakePedalPressed,
                           int gearMode, double lat, double lon, double altitude,
                           double verticalAccuracyM, boolean altitudeIsMsl) {
        this.timestampMs = timestampMs;
        this.speedKmh = speedKmh;
        this.accelPedalPercent = accelPedalPercent;
        this.brakePedalPercent = brakePedalPercent;
        this.brakePedalPressed = brakePedalPressed;
        this.gearMode = gearMode;
        this.lat = lat;
        this.lon = lon;
        this.altitude = altitude;
        this.verticalAccuracyM = verticalAccuracyM;
        this.altitudeIsMsl = altitudeIsMsl;
    }

    /**
     * Serialize to JSON with compact keys for storage.
     * Keys: t, s, a, b, bp, g, la, lo, al, va, am
     * "va"/"am" are written only when informative (va > 0 / am == true) to keep
     * the common-case line size unchanged; fromJson() defaults cover absence.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("t", timestampMs);
            json.put("s", speedKmh);
            json.put("a", accelPedalPercent);
            json.put("b", brakePedalPercent);
            json.put("bp", brakePedalPressed);
            json.put("g", gearMode);
            json.put("la", lat);
            json.put("lo", lon);
            json.put("al", altitude);
            if (verticalAccuracyM > 0) json.put("va", verticalAccuracyM);
            if (altitudeIsMsl) json.put("am", true);
        } catch (Exception e) {
            // JSONObject.put only throws on null key, which won't happen here
        }
        return json;
    }

    /**
     * Deserialize from compact JSON.
     */
    public static TelemetrySample fromJson(JSONObject json) {
        return new TelemetrySample(
                json.optLong("t", 0),
                json.optInt("s", 0),
                json.optInt("a", 0),
                json.optInt("b", 0),
                json.optBoolean("bp", false),
                json.optInt("g", 1),
                json.optDouble("la", 0.0),
                json.optDouble("lo", 0.0),
                json.optDouble("al", 0.0),
                json.optDouble("va", 0.0),
                json.optBoolean("am", false)
        );
    }
}
