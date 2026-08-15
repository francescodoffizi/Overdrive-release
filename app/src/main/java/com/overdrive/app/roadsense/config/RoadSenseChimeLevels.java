package com.overdrive.app.roadsense.config;

/** Shared RoadSense chime-volume policy for live warnings and the test endpoint. */
public final class RoadSenseChimeLevels {

    public static final int MIN_MASTER_PERCENT = 10;
    public static final int MAX_MASTER_PERCENT = 100;
    public static final int DEFAULT_MASTER_PERCENT = 75;

    private RoadSenseChimeLevels() {}

    public static int normalizeMasterPercent(int raw) {
        return Math.max(MIN_MASTER_PERCENT, Math.min(MAX_MASTER_PERCENT, raw));
    }

    /**
     * Strict validation for values crossing an API or persistence boundary. Runtime
     * readers still clamp defensively, but writers must not report success after storing
     * a value the UI can never represent.
     */
    public static Integer validatedMasterPercent(Object raw) {
        if (!(raw instanceof Number)) return null;
        Number number = (Number) raw;
        double value = number.doubleValue();
        int integer = number.intValue();
        if (!Double.isFinite(value) || value != integer) return null;
        if (integer < MIN_MASTER_PERCENT || integer > MAX_MASTER_PERCENT) return null;
        return integer;
    }

    /**
     * The configured master is the severe-alert ceiling. Less severe cues remain
     * distinguishable without becoming more intrusive than the selected level.
     */
    public static int effectivePercent(int masterPercent, int severityLevel) {
        int master = normalizeMasterPercent(masterPercent);
        int severityScale = severityLevel <= 1 ? 70 : (severityLevel == 2 ? 85 : 100);
        return Math.max(1, (master * severityScale + 50) / 100);
    }
}
