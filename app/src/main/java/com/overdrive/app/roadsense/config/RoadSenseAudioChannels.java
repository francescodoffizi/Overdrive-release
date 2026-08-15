package com.overdrive.app.roadsense.config;

import java.util.Locale;

/** Canonical channel names shared by RoadSense configuration and its test endpoint. */
public final class RoadSenseAudioChannels {

    public static final String DEFAULT = "navigation";

    private RoadSenseAudioChannels() {}

    public static boolean isSupported(String raw) {
        if (raw == null) return false;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "navigation":
            case "media":
            case "voice":
            case "alarm":
                return true;
            default:
                return false;
        }
    }

    /** Return a supported channel, falling back to the live-warning default. */
    public static String normalize(String raw) {
        if (!isSupported(raw)) return DEFAULT;
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
