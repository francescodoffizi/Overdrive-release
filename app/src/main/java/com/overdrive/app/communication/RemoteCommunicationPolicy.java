package com.overdrive.app.communication;

/**
 * Shared, Android-free policy for remote voice and on-screen messages.
 *
 * Keeping these limits in one place lets the web transport, app receiver, API
 * validation, and local unit tests enforce the same contract.
 */
public final class RemoteCommunicationPolicy {

    public static final int PCM_SAMPLE_RATE_HZ = 16_000;
    public static final int MAX_PCM_FRAME_BYTES = 32 * 1024;
    public static final long MAX_SESSION_MS = 30_000L;
    public static final long AUDIO_INACTIVITY_MS = 3_000L;
    public static final int MAX_MESSAGE_CHARS = 200;
    public static final int DEFAULT_OUTPUT_LEVEL = 70;

    private RemoteCommunicationPolicy() {}

    public static int clampOutputLevel(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public static int effectiveOutputLevel(
            boolean overrideEnabled, int configuredLevel) {
        return overrideEnabled ? clampOutputLevel(configuredLevel) : 100;
    }

    public static String validateMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "Message is required";
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            return "Message must be 200 characters or fewer";
        }
        return null;
    }

    public static String normalizeKind(String value) {
        return "dialog".equalsIgnoreCase(value) ? "dialog" : "toast";
    }

    public static String effectiveKind(String requestedKind, boolean parked) {
        String normalized = normalizeKind(requestedKind);
        return "dialog".equals(normalized) && parked ? "dialog" : "toast";
    }

    public static String normalizeSeverity(String value) {
        if ("warning".equalsIgnoreCase(value)) return "warning";
        if ("alert".equalsIgnoreCase(value) || "danger".equalsIgnoreCase(value)) {
            return "alert";
        }
        return "info";
    }

    public static String normalizePosition(String value) {
        if ("top".equalsIgnoreCase(value)) return "top";
        if ("center".equalsIgnoreCase(value)) return "center";
        return "bottom";
    }

    public static String normalizeDuration(String value) {
        return "long".equalsIgnoreCase(value) ? "long" : "short";
    }

    public static boolean shouldStopForLimit(long elapsedMs) {
        return elapsedMs >= MAX_SESSION_MS;
    }

    public static boolean shouldStopForInactivity(long inactiveMs) {
        return inactiveMs >= AUDIO_INACTIVITY_MS;
    }
}
