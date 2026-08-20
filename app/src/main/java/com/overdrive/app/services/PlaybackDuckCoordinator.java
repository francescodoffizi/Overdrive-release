package com.overdrive.app.services;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Coordinates temporary RoadSense ducking of automation media inside the app process.
 * Android audio focus does not automatically attenuate two players owned by the same UID.
 */
public final class PlaybackDuckCoordinator {

    public interface Target {
        void setDucked(boolean ducked);
    }

    private final Set<Target> targets =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int holds;

    synchronized void attach(Target next) {
        if (next == null) return;
        targets.add(next);
        notifyTarget(next, holds > 0);
    }

    synchronized void detach(Target expected) {
        targets.remove(expected);
    }

    synchronized void begin() {
        boolean changed = holds++ == 0;
        if (changed) notifyTargets(true);
    }

    synchronized void end() {
        if (holds == 0) return;
        boolean changed = --holds == 0;
        if (changed) notifyTargets(false);
    }

    synchronized boolean isActive() {
        return holds > 0;
    }

    private void notifyTargets(boolean ducked) {
        for (Target target : targets) notifyTarget(target, ducked);
    }

    private static void notifyTarget(Target target, boolean ducked) {
        try {
            target.setDucked(ducked);
        } catch (Throwable ignored) {
            // One playback surface must not prevent the others from being attenuated.
        }
    }
}
