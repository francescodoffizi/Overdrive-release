package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AutomationPlaybackStartValidationTest {

    @Test
    public void acceptsOnlyExplicitStringExtras() {
        assertEquals("voice", MediaPlaybackService.parseStringExtra("voice", true));
        assertEquals("", MediaPlaybackService.parseStringExtra("", true));
        assertNull(MediaPlaybackService.parseStringExtra(null, false));
        assertNull(MediaPlaybackService.parseStringExtra(16, true));
        assertNull(MediaPlaybackService.parseStringExtra(true, true));
    }

    @Test
    public void acceptsOnlyExplicitBooleanLoopExtras() {
        assertEquals(Boolean.TRUE,
                MediaPlaybackService.parseBooleanExtra(Boolean.TRUE, true));
        assertEquals(Boolean.FALSE,
                MediaPlaybackService.parseBooleanExtra(Boolean.FALSE, true));
        assertFalse(MediaPlaybackService.parseBooleanExtra(null, false));
        assertNull(MediaPlaybackService.parseBooleanExtra("false", true));
        assertNull(MediaPlaybackService.parseBooleanExtra(0, true));
    }
}
