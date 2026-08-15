package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class VideoPlaybackStartValidationTest {

    @Test
    public void acceptsOnlyExplicitStringSources() {
        assertEquals("clip.mp4", VideoPlaybackActivity.parseStringExtra("clip.mp4", true));
        assertNull(VideoPlaybackActivity.parseStringExtra(null, false));
        assertNull(VideoPlaybackActivity.parseStringExtra(42, true));
    }

    @Test
    public void acceptsOnlyExplicitBooleanLoopExtras() {
        assertEquals(Boolean.TRUE,
                VideoPlaybackActivity.parseBooleanExtra(Boolean.TRUE, true));
        assertFalse(VideoPlaybackActivity.parseBooleanExtra(null, false));
        assertNull(VideoPlaybackActivity.parseBooleanExtra("true", true));
    }
}
