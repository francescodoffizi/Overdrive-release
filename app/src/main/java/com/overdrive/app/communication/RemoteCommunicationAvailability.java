package com.overdrive.app.communication;

/**
 * Android-free availability policy shared by HTTP status, PTT, messages, and
 * unit tests. The first matching result is the user-visible root cause.
 */
public final class RemoteCommunicationAvailability {

    public static final String SETTINGS_PATH =
            "Settings > Status overlay > Remote communication";

    public static final class Result {
        public final boolean ready;
        public final String code;
        public final String reason;
        public final String guidance;

        private Result(boolean ready, String code, String reason, String guidance) {
            this.ready = ready;
            this.code = code;
            this.reason = reason;
            this.guidance = guidance;
        }
    }

    private RemoteCommunicationAvailability() {}

    public static Result voice(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean busy,
            Boolean overlayPermission) {
        if (carKnownOff) return carOff();
        if (settings.emergencyDisabled) return emergencyDisabled();
        if (!settings.voiceEnabled) {
            return unavailable(
                    "voice_disabled",
                    "Remote voice is disabled in the car settings",
                    inCar("enable Remote voice."));
        }
        if (busy) {
            return unavailable(
                    "busy",
                    "Another talk session is already active",
                    "Release the other talk session before starting a new one.");
        }
        if (Boolean.FALSE.equals(overlayPermission)) return overlayPermission();
        return ready();
    }

    public static Result messages(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            Boolean overlayPermission) {
        if (carKnownOff) return carOff();
        if (settings.emergencyDisabled) return emergencyDisabled();
        if (!settings.messagesEnabled) {
            return unavailable(
                    "messages_disabled",
                    "Remote messages are disabled in the car settings",
                    inCar("enable Remote messages."));
        }
        if (Boolean.FALSE.equals(overlayPermission)) return overlayPermission();
        return ready();
    }

    public static Result listener(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean busy) {
        if (carKnownOff) return carOff();
        if (settings.emergencyDisabled) return emergencyDisabled();
        if (!settings.listenerEnabled) {
            return unavailable(
                    "listener_disabled",
                    "Cabin listening is disabled in the car settings",
                    inCar("enable Cabin listener."));
        }
        if (busy) {
            return unavailable(
                    "listener_busy",
                    "Another cabin listener is already active",
                    "Stop the other listener before starting a new one.");
        }
        return ready();
    }

    public static boolean shouldCheckVoiceOverlay(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean busy) {
        return !carKnownOff
                && !settings.emergencyDisabled
                && settings.voiceEnabled
                && !busy;
    }

    public static boolean shouldCheckMessageOverlay(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings) {
        return !carKnownOff
                && !settings.emergencyDisabled
                && settings.messagesEnabled;
    }

    public static boolean shouldCheckAnyOverlay(
            boolean carKnownOff,
            RemoteCommunicationSettings.Snapshot settings,
            boolean voiceBusy) {
        return shouldCheckVoiceOverlay(carKnownOff, settings, voiceBusy)
                || shouldCheckMessageOverlay(carKnownOff, settings);
    }

    private static Result ready() {
        return new Result(true, "ready", "", "");
    }

    private static Result carOff() {
        return unavailable(
                "car_off",
                "The car is off, so remote communication is unavailable",
                "Turn the car on and wait for the infotainment system to finish starting.");
    }

    private static Result emergencyDisabled() {
        return unavailable(
                "emergency_disabled",
                "Remote communication is emergency-disabled in the car",
                inCar("turn off Emergency disable."));
    }

    private static Result overlayPermission() {
        return unavailable(
                "overlay_permission",
                "Display-over-other-apps permission is not granted in the car",
                inCar("tap Overlay permission, then allow Display over other apps."));
    }

    private static Result unavailable(
            String code, String reason, String guidance) {
        return new Result(false, code, reason, guidance);
    }

    private static String inCar(String action) {
        return "In the car app, open " + SETTINGS_PATH + ", then " + action;
    }
}
