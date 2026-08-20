package com.overdrive.app.roadsense.warn

import com.overdrive.app.roadsense.detect.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgedAudioCueTest {

    @Test
    fun forwardsEverySeverityResourceChannelAndEffectiveVolume() {
        val calls = mutableListOf<Triple<String, String, Int>>()
        val cue = BridgedAudioCue(
            channelSupplier = { "voice" },
            volumeSupplier = { 80 },
            dispatcher = BridgedChimeDispatcher { resource, channel, volume ->
                calls += Triple(resource, channel, volume)
                true
            },
        )

        cue.chime(Severity.MINOR)
        cue.chime(Severity.MODERATE)
        cue.chime(Severity.SEVERE)

        assertEquals(
            listOf(
                Triple("roadsense_chime_minor", "voice", 56),
                Triple("roadsense_chime_moderate", "voice", 68),
                Triple("roadsense_chime_severe", "voice", 80),
            ),
            calls,
        )
    }

    @Test
    fun supplierFailuresUseSafeDefaults() {
        val calls = mutableListOf<Triple<String, String, Int>>()
        val cue = BridgedAudioCue(
            channelSupplier = { error("channel unavailable") },
            volumeSupplier = { error("volume unavailable") },
            dispatcher = BridgedChimeDispatcher { resource, channel, volume ->
                calls += Triple(resource, channel, volume)
                true
            },
        )

        cue.chime(Severity.SEVERE)

        assertEquals(
            listOf(Triple("roadsense_chime_severe", "navigation", 75)),
            calls,
        )
    }
}
