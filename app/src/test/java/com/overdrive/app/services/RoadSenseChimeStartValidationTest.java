package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RoadSenseChimeStartValidationTest {

    @Test
    public void parsesOnlyExplicitSupportedChannelTypes() {
        assertEquals("navigation",
                RoadSenseChimePlaybackService.parseChannel(null, false));
        assertEquals("navigation",
                RoadSenseChimePlaybackService.parseChannel("  ", true));
        assertEquals("voice",
                RoadSenseChimePlaybackService.parseChannel(" Voice ", true));
        assertNull(RoadSenseChimePlaybackService.parseChannel("system", true));
        assertNull(RoadSenseChimePlaybackService.parseChannel(14, true));
    }

    @Test
    public void parsesStringOrIntegerVolumeWithoutTypeDefaulting() {
        assertEquals(Integer.valueOf(100),
                RoadSenseChimePlaybackService.parseVolumePercent(null, false));
        assertEquals(Integer.valueOf(75),
                RoadSenseChimePlaybackService.parseVolumePercent(" 75 ", true));
        assertEquals(Integer.valueOf(75),
                RoadSenseChimePlaybackService.parseVolumePercent(75, true));
        assertNull(RoadSenseChimePlaybackService.parseVolumePercent(75L, true));
        assertNull(RoadSenseChimePlaybackService.parseVolumePercent(true, true));
        assertNull(RoadSenseChimePlaybackService.parseVolumePercent("", true));
        assertNull(RoadSenseChimePlaybackService.parseVolumePercent(0, true));
        assertNull(RoadSenseChimePlaybackService.parseVolumePercent(101, true));
    }
}
