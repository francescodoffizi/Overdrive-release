package com.overdrive.app.roadsense.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RoadSenseAudioChannelsTest {

    @Test
    public void acceptsEveryChannelThatThePlayerRoutes() {
        assertTrue(RoadSenseAudioChannels.isSupported("navigation"));
        assertTrue(RoadSenseAudioChannels.isSupported("media"));
        assertTrue(RoadSenseAudioChannels.isSupported("voice"));
        assertTrue(RoadSenseAudioChannels.isSupported("alarm"));
    }

    @Test
    public void canonicalizesWhitespaceAndCase() {
        assertEquals("voice", RoadSenseAudioChannels.normalize(" Voice "));
    }

    @Test
    public void unknownOrMissingChannelsFallBackToNavigation() {
        assertFalse(RoadSenseAudioChannels.isSupported("system"));
        assertFalse(RoadSenseAudioChannels.isSupported(null));
        assertEquals("navigation", RoadSenseAudioChannels.normalize("system"));
        assertEquals("navigation", RoadSenseAudioChannels.normalize(null));
    }
}
