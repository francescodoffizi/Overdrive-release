package com.overdrive.app.surveillance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Crop geometry for the deterrent's MP4 layer. The invariant that matters: the
 * crop always covers the full panel, since letterbox bars would expose BYD's
 * AccAnimation layer through a warning that is supposed to hide it.
 */
public class ScreenDeterrentVideoCropTest {

    @Test
    public void matchingAspectUsesTheWholeFrame() {
        assertArrayEquals(new int[] { 0, 0, 1920, 1080 },
                ScreenDeterrentVideo.coverCropBounds(1920, 1080, 1920, 1080));
    }

    @Test
    public void widerVideoOnNarrowerPanelCropsTheSides() {
        assertArrayEquals(new int[] { 320, 0, 2240, 1080 },
                ScreenDeterrentVideo.coverCropBounds(2560, 1080, 1920, 1080));
    }

    @Test
    public void landscapeVideoOnPortraitPanelCropsTheSides() {
        assertArrayEquals(new int[] { 656, 0, 1264, 1080 },
                ScreenDeterrentVideo.coverCropBounds(1920, 1080, 1080, 1920));
    }

    @Test
    public void squareVideoOnWidePanelKeepsFullWidth() {
        assertArrayEquals(new int[] { 0, 236, 1080, 844 },
                ScreenDeterrentVideo.coverCropBounds(1080, 1080, 1920, 1080));
    }

    @Test
    public void cropAspectMatchesThePanel() {
        int[][] panels = { { 1920, 1080 }, { 1080, 1920 }, { 1280, 720 }, { 1024, 600 } };
        int[][] videos = { { 1920, 1080 }, { 1080, 1920 }, { 1280, 720 }, { 640, 640 } };
        for (int[] p : panels) {
            for (int[] v : videos) {
                int[] c = ScreenDeterrentVideo.coverCropBounds(v[0], v[1], p[0], p[1]);
                double cropAspect = (double) (c[2] - c[0]) / (c[3] - c[1]);
                double panelAspect = (double) p[0] / p[1];
                String label = v[0] + "x" + v[1] + " on " + p[0] + "x" + p[1];
                // 1px of rounding on the shorter axis shifts the ratio slightly.
                assertTrue(label + ": crop aspect " + cropAspect + " vs panel " + panelAspect,
                        Math.abs(cropAspect - panelAspect) / panelAspect < 0.01);
            }
        }
    }

    @Test
    public void cropNeverEscapesTheSourceFrame() {
        int[][] cases = {
                { 1920, 1080, 1920, 720 }, { 640, 480, 1920, 1080 },
                { 1080, 1920, 1920, 1080 }, { 1, 1, 1920, 1080 },
                { 1920, 1080, 1, 1000 },
        };
        for (int[] t : cases) {
            int[] c = ScreenDeterrentVideo.coverCropBounds(t[0], t[1], t[2], t[3]);
            String label = t[0] + "x" + t[1] + " on " + t[2] + "x" + t[3];
            assertTrue(label + ": left >= 0", c[0] >= 0);
            assertTrue(label + ": top >= 0", c[1] >= 0);
            assertTrue(label + ": right <= vw", c[2] <= t[0]);
            assertTrue(label + ": bottom <= vh", c[3] <= t[1]);
            assertTrue(label + ": non-empty width", c[2] > c[0]);
            assertTrue(label + ": non-empty height", c[3] > c[1]);
        }
    }
}
