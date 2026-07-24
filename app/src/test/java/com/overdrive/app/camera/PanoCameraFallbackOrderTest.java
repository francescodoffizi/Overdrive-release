package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fallback ordering for a panoramic slot that opens but never streams
 * (issue #170 — BYD Seal on 2602 firmware, profile camera ID 1 probes 0x0).
 */
public class PanoCameraFallbackOrderTest {

    @Test
    public void halTagMappingIsPreferredOverRawStrip() {
        assertEquals(Arrays.asList(2, 0), PanoCameraFallbackOrder.candidates(2));
    }

    @Test
    public void rawStripIsTheOnlyCandidateWhenHalMappingIsUnavailable() {
        // BmmCameraInfo is absent on DiLink 3.0, so discoverPanoCameraId
        // returns -1. Camera 0 must still be reachable — this is the exact
        // path that fixes the reported Seal.
        assertEquals(Collections.singletonList(0), PanoCameraFallbackOrder.candidates(-1));
    }

    @Test
    public void halMappingOfZeroIsNotDuplicated() {
        // The reported board maps pano_h -> 0, which is also the raw-strip
        // fallback. One attempt, not two.
        assertEquals(Collections.singletonList(0), PanoCameraFallbackOrder.candidates(0));
    }

    @Test
    public void outOfRangeHalMappingIsIgnored() {
        assertEquals(Collections.singletonList(0), PanoCameraFallbackOrder.candidates(6));
        assertEquals(Collections.singletonList(0), PanoCameraFallbackOrder.candidates(Integer.MIN_VALUE));
    }

    @Test
    public void sealOn2602FallsBackFromDeadIdOneToZero() {
        // The issue-#170 scenario end to end: profile picked 1, it opened but
        // never delivered a frame, BmmCameraInfo is unavailable.
        Set<Integer> tried = new HashSet<>(Collections.singletonList(1));
        assertEquals(0, PanoCameraFallbackOrder.next(-1, tried));
    }

    @Test
    public void exhaustedCandidatesReportNoCandidate() {
        Set<Integer> tried = new HashSet<>(Arrays.asList(0, 1, 2));
        assertEquals(PanoCameraFallbackOrder.NO_CANDIDATE,
                PanoCameraFallbackOrder.next(2, tried));
        assertEquals(PanoCameraFallbackOrder.NO_CANDIDATE,
                PanoCameraFallbackOrder.next(-1, tried));
    }

    @Test
    public void nullTriedSetYieldsFirstCandidate() {
        assertEquals(3, PanoCameraFallbackOrder.next(3, null));
        assertEquals(0, PanoCameraFallbackOrder.next(-1, null));
    }

    @Test
    public void walkVisitsEachCandidateExactlyOnce() {
        Set<Integer> tried = new HashSet<>(Collections.singletonList(1));
        List<Integer> expected = Arrays.asList(4, 0);
        for (int expectedId : expected) {
            int next = PanoCameraFallbackOrder.next(4, tried);
            assertEquals(expectedId, next);
            tried.add(next);
        }
        assertEquals(PanoCameraFallbackOrder.NO_CANDIDATE,
                PanoCameraFallbackOrder.next(4, tried));
    }
}
