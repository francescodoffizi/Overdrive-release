package com.overdrive.app.services;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackSessionGuardTest {

    @Test
    public void aNewRequestInvalidatesOlderPlayerCallbacks() {
        PlaybackSessionGuard guard = new PlaybackSessionGuard();
        long first = guard.begin();
        long second = guard.begin();

        assertFalse(guard.isCurrent(first));
        assertTrue(guard.isCurrent(second));
    }

    @Test
    public void flushedTtsCallbackCannotMatchTheNewUtterance() {
        PlaybackSessionGuard guard = new PlaybackSessionGuard();
        long flushedGeneration = guard.begin();
        String flushed = guard.nextTtsUtteranceId();
        long currentGeneration = guard.begin();
        String current = guard.nextTtsUtteranceId();

        assertNotEquals(flushed, current);
        assertFalse(guard.claimCurrentTtsCallback(flushedGeneration, flushed, flushed));
        assertFalse(guard.claimCurrentTtsCallback(currentGeneration, current, flushed));
        assertTrue(guard.hasActiveTtsUtterance(currentGeneration));
        assertTrue(guard.claimCurrentTtsCallback(currentGeneration, current, current));
        assertFalse(guard.hasActiveTtsUtterance(currentGeneration));
        assertFalse(guard.claimCurrentTtsCallback(currentGeneration, current, current));
    }

    @Test
    public void explicitlyPausedTtsRejectsItsLateCallback() {
        PlaybackSessionGuard guard = new PlaybackSessionGuard();
        long generation = guard.begin();
        String paused = guard.nextTtsUtteranceId();

        guard.invalidateTtsCallbacks();

        assertFalse(guard.hasActiveTtsUtterance(generation));
        assertFalse(guard.claimCurrentTtsCallback(generation, paused, paused));
    }
}
