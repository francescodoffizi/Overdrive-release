package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class PlaybackDuckCoordinatorTest {

    @Test
    public void firstHoldDucksAndLastReleaseRestores() {
        PlaybackDuckCoordinator coordinator = new PlaybackDuckCoordinator();
        List<Boolean> states = new ArrayList<>();
        coordinator.attach(states::add);

        coordinator.begin();
        coordinator.begin();
        coordinator.end();
        assertTrue(coordinator.isActive());
        coordinator.end();

        assertFalse(coordinator.isActive());
        assertEquals(Arrays.asList(false, true, false), states);
    }

    @Test
    public void attachingDuringAWarningAppliesCurrentState() {
        PlaybackDuckCoordinator coordinator = new PlaybackDuckCoordinator();
        coordinator.begin();
        List<Boolean> states = new ArrayList<>();

        coordinator.attach(states::add);

        assertEquals(Arrays.asList(true), states);
    }

    @Test
    public void ducksEveryAttachedPlaybackSurfaceIndependently() {
        PlaybackDuckCoordinator coordinator = new PlaybackDuckCoordinator();
        List<Boolean> audioStates = new ArrayList<>();
        List<Boolean> videoStates = new ArrayList<>();
        PlaybackDuckCoordinator.Target audio = audioStates::add;
        PlaybackDuckCoordinator.Target video = videoStates::add;
        coordinator.attach(audio);
        coordinator.attach(video);

        coordinator.begin();
        coordinator.detach(audio);
        coordinator.end();

        assertEquals(Arrays.asList(false, true), audioStates);
        assertEquals(Arrays.asList(false, true, false), videoStates);
    }

    @Test
    public void brokenTargetDoesNotBlockOtherPlaybackSurfaces() {
        PlaybackDuckCoordinator coordinator = new PlaybackDuckCoordinator();
        List<Boolean> states = new ArrayList<>();
        coordinator.attach(ducked -> { throw new IllegalStateException("broken target"); });
        coordinator.attach(states::add);

        coordinator.begin();
        coordinator.end();

        assertEquals(Arrays.asList(false, true, false), states);
    }
}
