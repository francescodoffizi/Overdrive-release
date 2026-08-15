package com.overdrive.app.services;

/** Tracks the active playback request and unique TTS callback identifiers. */
final class PlaybackSessionGuard {

    private long generation;
    private long utteranceSequence;
    private String activeUtteranceId;

    synchronized long begin() {
        activeUtteranceId = null;
        return ++generation;
    }

    synchronized void invalidate() {
        activeUtteranceId = null;
        ++generation;
    }

    synchronized boolean isCurrent(long token) {
        return token == generation;
    }

    synchronized String nextTtsUtteranceId() {
        activeUtteranceId =
                "overdrive-tts-" + generation + "-" + (++utteranceSequence);
        return activeUtteranceId;
    }

    synchronized void invalidateTtsCallbacks() {
        activeUtteranceId = null;
    }

    synchronized boolean claimCurrentTtsCallback(long expectedGeneration, String expectedId,
                                                 String callbackId) {
        boolean current = expectedGeneration == generation
                && expectedId != null
                && expectedId.equals(activeUtteranceId)
                && expectedId.equals(callbackId);
        if (current) activeUtteranceId = null;
        return current;
    }

    synchronized boolean hasActiveTtsUtterance(long expectedGeneration) {
        return expectedGeneration == generation && activeUtteranceId != null;
    }
}
