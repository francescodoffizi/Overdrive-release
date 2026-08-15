package com.overdrive.app.communication;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** File-backed settings shared by the daemon and the real app process. */
public final class RemoteCommunicationSettings {

    public static final class Snapshot {
        public final boolean voiceEnabled;
        public final int outputLevel;
        public final boolean outputLevelOverrideEnabled;
        public final boolean messagesEnabled;
        public final boolean emergencyDisabled;

        Snapshot(boolean voiceEnabled, int outputLevel, boolean messagesEnabled,
                 boolean emergencyDisabled) {
            this(voiceEnabled, outputLevel, false, messagesEnabled,
                    emergencyDisabled);
        }

        Snapshot(boolean voiceEnabled, int outputLevel,
                 boolean outputLevelOverrideEnabled, boolean messagesEnabled,
                 boolean emergencyDisabled) {
            this.voiceEnabled = voiceEnabled;
            this.outputLevel = outputLevel;
            this.outputLevelOverrideEnabled = outputLevelOverrideEnabled;
            this.messagesEnabled = messagesEnabled;
            this.emergencyDisabled = emergencyDisabled;
        }
    }

    private RemoteCommunicationSettings() {}

    public static Snapshot load() {
        JSONObject config = UnifiedConfigManager.getRemoteCommunication();
        return new Snapshot(
                config.optBoolean("voiceEnabled", true),
                RemoteCommunicationPolicy.clampOutputLevel(
                        config.optInt("outputLevel",
                                RemoteCommunicationPolicy.DEFAULT_OUTPUT_LEVEL)),
                config.optBoolean("outputLevelOverrideEnabled", false),
                config.optBoolean("messagesEnabled", true),
                config.optBoolean("emergencyDisabled", false));
    }

    public static boolean update(Boolean voiceEnabled, Integer outputLevel,
                                 Boolean messagesEnabled, Boolean emergencyDisabled) {
        return update(voiceEnabled, outputLevel, null, messagesEnabled,
                emergencyDisabled);
    }

    public static boolean update(
            Boolean voiceEnabled,
            Integer outputLevel,
            Boolean outputLevelOverrideEnabled,
            Boolean messagesEnabled,
            Boolean emergencyDisabled) {
        Map<String, Object> values = new HashMap<>();
        if (voiceEnabled != null) values.put("voiceEnabled", voiceEnabled);
        if (outputLevel != null) {
            values.put("outputLevel",
                    RemoteCommunicationPolicy.clampOutputLevel(outputLevel));
        }
        if (outputLevelOverrideEnabled != null) {
            values.put("outputLevelOverrideEnabled",
                    outputLevelOverrideEnabled);
        }
        if (messagesEnabled != null) values.put("messagesEnabled", messagesEnabled);
        if (emergencyDisabled != null) {
            values.put("emergencyDisabled", emergencyDisabled);
        }
        return values.isEmpty()
                || UnifiedConfigManager.updateValues("remoteCommunication", values);
    }

    public static String voiceUnavailableReason(Snapshot settings, boolean busy,
                                                Boolean overlayPermission) {
        RemoteCommunicationAvailability.Result result =
                RemoteCommunicationAvailability.voice(
                        false, settings, busy, overlayPermission);
        return result.ready ? null : result.reason;
    }

    public static String messagesUnavailableReason(Snapshot settings,
                                                   Boolean overlayPermission) {
        RemoteCommunicationAvailability.Result result =
                RemoteCommunicationAvailability.messages(
                        false, settings, overlayPermission);
        return result.ready ? null : result.reason;
    }
}
