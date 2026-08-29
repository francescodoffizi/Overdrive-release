package com.overdrive.app.communication;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** File-backed settings shared by the daemon and the real app process. */
public final class RemoteCommunicationSettings {

    public static final String AUDIO_CHANNEL_MEDIA = "media";
    public static final String AUDIO_CHANNEL_NAVIGATION = "navigation";

    public static final class Snapshot {
        public final boolean voiceEnabled;
        public final boolean listenerEnabled;
        public final int outputLevel;
        public final boolean outputLevelOverrideEnabled;
        public final String audioChannel;
        public final boolean messagesEnabled;
        public final boolean emergencyDisabled;

        Snapshot(boolean voiceEnabled, int outputLevel, boolean messagesEnabled,
                 boolean emergencyDisabled) {
            this(voiceEnabled, outputLevel, false, false, messagesEnabled,
                    emergencyDisabled);
        }

        Snapshot(boolean voiceEnabled, int outputLevel,
                 boolean outputLevelOverrideEnabled, boolean messagesEnabled,
                 boolean emergencyDisabled) {
            this(voiceEnabled, outputLevel, outputLevelOverrideEnabled, false,
                    messagesEnabled, emergencyDisabled);
        }

        Snapshot(boolean voiceEnabled, int outputLevel,
                 boolean outputLevelOverrideEnabled, boolean listenerEnabled,
                 boolean messagesEnabled, boolean emergencyDisabled) {
            this(voiceEnabled, outputLevel, outputLevelOverrideEnabled,
                    listenerEnabled, messagesEnabled, emergencyDisabled,
                    AUDIO_CHANNEL_MEDIA);
        }

        Snapshot(boolean voiceEnabled, int outputLevel,
                 boolean outputLevelOverrideEnabled, boolean listenerEnabled,
                 boolean messagesEnabled, boolean emergencyDisabled,
                 String audioChannel) {
            this.voiceEnabled = voiceEnabled;
            this.listenerEnabled = listenerEnabled;
            this.outputLevel = outputLevel;
            this.outputLevelOverrideEnabled = outputLevelOverrideEnabled;
            this.audioChannel = normalizeAudioChannel(audioChannel);
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
                config.optBoolean("listenerEnabled", false),
                config.optBoolean("messagesEnabled", true),
                config.optBoolean("emergencyDisabled", false),
                config.optString("audioChannel", AUDIO_CHANNEL_MEDIA));
    }

    public static boolean update(Boolean voiceEnabled, Integer outputLevel,
                                 Boolean messagesEnabled, Boolean emergencyDisabled) {
        return update(voiceEnabled, outputLevel, null, messagesEnabled,
                emergencyDisabled, null);
    }

    public static boolean update(
            Boolean voiceEnabled,
            Integer outputLevel,
            Boolean outputLevelOverrideEnabled,
            Boolean messagesEnabled,
            Boolean emergencyDisabled) {
        return update(voiceEnabled, outputLevel, outputLevelOverrideEnabled,
                messagesEnabled, emergencyDisabled, null);
    }

    public static boolean update(
            Boolean voiceEnabled,
            Integer outputLevel,
            Boolean outputLevelOverrideEnabled,
            Boolean messagesEnabled,
            Boolean emergencyDisabled,
            Boolean listenerEnabled) {
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
        if (listenerEnabled != null) {
            values.put("listenerEnabled", listenerEnabled);
        }
        return values.isEmpty()
                || UnifiedConfigManager.updateValues("remoteCommunication", values);
    }

    public static boolean updateListenerEnabled(boolean enabled) {
        return update(null, null, null, null, null, enabled);
    }

    public static boolean updateAudioChannel(String audioChannel) {
        Map<String, Object> values = new HashMap<>();
        values.put("audioChannel", normalizeAudioChannel(audioChannel));
        return UnifiedConfigManager.updateValues("remoteCommunication", values);
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

    private static String normalizeAudioChannel(String audioChannel) {
        return AUDIO_CHANNEL_NAVIGATION.equalsIgnoreCase(
                audioChannel == null ? "" : audioChannel.trim())
                ? AUDIO_CHANNEL_NAVIGATION : AUDIO_CHANNEL_MEDIA;
    }
}
