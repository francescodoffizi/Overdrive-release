package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two free-angle framings and the angle predicate that gates them.
 *
 * <p>These values are transcribed measurements, not derived ones, so the tests pin what
 * they ARE rather than re-deriving them — a changed number here changes what a driver
 * sees, and should have to be a deliberate edit with a test updated alongside it.
 */
public class BsAnglePresetTest {

    private static final float EPS = 1e-5f;

    // ==================== the angle gate ====================

    @Test
    public void onlyTheTwoFreeAnglesArePresets() {
        assertTrue(BsAnglePreset.isPresetAngle(40));
        assertTrue(BsAnglePreset.isPresetAngle(310));
        // The quarter turns must NOT be presets: they keep the developer's original
        // path, including the fisheye setting behaving exactly as it always did.
        assertFalse(BsAnglePreset.isPresetAngle(0));
        assertFalse(BsAnglePreset.isPresetAngle(90));
        assertFalse(BsAnglePreset.isPresetAngle(180));
        assertFalse(BsAnglePreset.isPresetAngle(270));
        assertFalse(BsAnglePreset.isPresetAngle(41));
        assertFalse(BsAnglePreset.isPresetAngle(311));
    }

    @Test
    public void anglesAreNormalisedBeforeTheyAreJudged() {
        assertTrue("-50 is 310", BsAnglePreset.isPresetAngle(-50));
        assertTrue("400 is 40", BsAnglePreset.isPresetAngle(400));
        assertTrue("670 is 310", BsAnglePreset.isPresetAngle(670));
        assertTrue("-320 is 40", BsAnglePreset.isPresetAngle(-320));
    }

    @Test
    public void lookupIsByAngleNotBySide() {
        assertSame(BsAnglePreset.LEFT, BsAnglePreset.forAngle(310));
        assertSame(BsAnglePreset.RIGHT, BsAnglePreset.forAngle(40));
        assertSame("normalised too", BsAnglePreset.LEFT, BsAnglePreset.forAngle(-50));
        // A non-preset angle has no framing, which is what keeps the quarter turns on
        // their original path.
        assertNull(BsAnglePreset.forAngle(90));
        assertNull(BsAnglePreset.forAngle(0));
    }

    // ==================== the measured values ====================

    @Test
    public void leftFramingKeepsTheLeftHalfMirroredAndTurned() {
        BsAnglePreset p = BsAnglePreset.LEFT;
        assertEquals(310f, p.rotationDeg, EPS);
        assertEquals("nothing off the left edge", 0f, p.cropLeft, EPS);
        assertEquals("right half dropped", 0.5f, p.cropRight, EPS);
        assertEquals(0f, p.cropTop, EPS);
        assertEquals(0f, p.cropBottom, EPS);
        assertTrue(p.flipHorizontal);
        assertFalse(p.flipVertical);
        assertEquals(1.4f, p.zoom, EPS);
    }

    @Test
    public void rightFramingKeepsTheRightHalf() {
        BsAnglePreset p = BsAnglePreset.RIGHT;
        assertEquals(40f, p.rotationDeg, EPS);
        assertEquals("left half dropped", 0.5f, p.cropLeft, EPS);
        assertEquals(0f, p.cropRight, EPS);
        assertTrue(p.flipHorizontal);
        assertFalse(p.flipVertical);
        assertEquals(1.4f, p.zoom, EPS);
    }

    @Test
    public void theTwoFramingsAreNotASymmetricPair() {
        // 310 is 50 short of a full turn; 40 is 40 past zero. The right view therefore
        // turns 10 less than the left. It reads like an oversight - the symmetric
        // choice would be 320/40 - but this is how the framing was measured, and the
        // difference is visible on screen. A later tidy-up has to update this test.
        float leftOffHorizontal = 360f - BsAnglePreset.LEFT.rotationDeg;   // 50
        float rightOffHorizontal = BsAnglePreset.RIGHT.rotationDeg;        // 40
        assertEquals(50f, leftOffHorizontal, EPS);
        assertEquals(40f, rightOffHorizontal, EPS);
        assertTrue("asymmetry is deliberate", leftOffHorizontal != rightOffHorizontal);
    }

    @Test
    public void bothFramingsPresentTheSourceAtSixteenNine() {
        // The reference composed against a 16:9 window and never asked its camera for a
        // matching preview size, so whatever arrived was stretched to fill it.
        // Reproducing the framing means reproducing that stretch.
        assertEquals(16f / 9f, BsAnglePreset.PRESENT_ASPECT, EPS);
    }

    // ==================== derived helpers ====================

    @Test
    public void keptFractionsFollowTheCrop() {
        assertEquals(0.5f, BsAnglePreset.LEFT.keptWidthFraction(), EPS);
        assertEquals(1.0f, BsAnglePreset.LEFT.keptHeightFraction(), EPS);
        assertEquals(0.5f, BsAnglePreset.RIGHT.keptWidthFraction(), EPS);
        assertEquals(1.0f, BsAnglePreset.RIGHT.keptHeightFraction(), EPS);
    }

    @Test
    public void cropCentreLandsOnTheKeptHalfNotTheFrameCentre() {
        // The quickest check that a framing hit the intended half. 0.5 here would mean
        // the crop was being ignored.
        assertEquals(0.25f, BsAnglePreset.LEFT.cropCentreX(), EPS);
        assertEquals(0.5f, BsAnglePreset.LEFT.cropCentreY(), EPS);
        assertEquals(0.75f, BsAnglePreset.RIGHT.cropCentreX(), EPS);
        assertEquals(0.5f, BsAnglePreset.RIGHT.cropCentreY(), EPS);
    }

    @Test
    public void namesDistinguishTheTwoFramingsInLogs() {
        assertEquals("LEFT-310", BsAnglePreset.LEFT.name());
        assertEquals("RIGHT-40", BsAnglePreset.RIGHT.name());
    }

    @Test
    public void toStringCarriesEveryValueForTheLogsReferenceLine() {
        // The log prints this verbatim as the "reference" line so a reader can compare
        // against the applied values without leaving the file. If it stopped naming a
        // value, that comparison would quietly lose a term.
        String s = BsAnglePreset.LEFT.toString();
        assertNotNull(s);
        for (String needle : new String[] {"rot=", "crop=", "flipH=", "flipV=",
                                           "zoom=", "translate="}) {
            assertTrue("reference line should mention " + needle + ", got: " + s,
                    s.contains(needle));
        }
    }
}
