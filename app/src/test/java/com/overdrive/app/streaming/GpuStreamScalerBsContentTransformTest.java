package com.overdrive.app.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The blind-spot card's free-angle CONTENT transform
 * ({@link GpuStreamScaler#setBsContentTransform}).
 *
 * <p>That method hands the shader the INVERSE of the framing a viewer describes, so
 * an error in it is invisible in review and shows up only as a subtly wrong picture on
 * a car. These tests pin it from the other direction: they take the affine the shader
 * would receive, apply it to output points, and assert which SOURCE point each one
 * lands on — the question the shader actually asks.
 *
 * <p>Every case is checked at points whose answer is known without redoing the
 * algebra: the centre maps to the crop centre, a mirror swaps the horizontal
 * neighbours, a quarter turn moves a corner to the next corner round.
 */
public class GpuStreamScalerBsContentTransformTest {

    private static final float EPS = 1e-4f;

    /** A 4:3 card, the shape the blind-spot card normally has. */
    private static final float ASPECT_4_3 = 4f / 3f;

    private static GpuStreamScaler newScaler() {
        // 640x480 only sizes the output; the transform math is resolution-independent
        // and no GL call is made by the constructor or by setBsContentTransform.
        return new GpuStreamScaler(640, 480);
    }

    /** Apply the affine the shader would apply: source = M·uv + off. */
    private static float[] map(float[] m6, float u, float v) {
        // m6 is {m00, m10, m01, m11, offX, offY} — column-major mat2 then offset.
        return new float[] {
                m6[0] * u + m6[2] * v + m6[4],
                m6[1] * u + m6[3] * v + m6[5]
        };
    }

    private static void assertMaps(float[] m6, float u, float v,
                                   float expectedX, float expectedY, String what) {
        float[] got = map(m6, u, v);
        assertEquals(what + " (x)", expectedX, got[0], EPS);
        assertEquals(what + " (y)", expectedY, got[1], EPS);
    }

    private static float[] transformOf(GpuStreamScaler s) {
        float[] out = new float[6];
        assertTrue("transform should be active", s.copyBsContentTransform(out));
        return out;
    }

    // ==================== identity ====================

    @Test
    public void neutralSettingsLeaveSamplingUnchanged() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(0f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        // Neutral framing must be the identity map, or every "inactive" claim about
        // the surrounding paths would be resting on luck rather than on the math.
        assertMaps(m, 0f, 0f, 0f, 0f, "top-left");
        assertMaps(m, 1f, 1f, 1f, 1f, "bottom-right");
        assertMaps(m, 0.5f, 0.5f, 0.5f, 0.5f, "centre");
    }

    @Test
    public void clearingReturnsToInactive() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(40f, 0.5f, 0f, 0f, 0f, true, false, 1.4f, 0f, -0.05f, ASPECT_4_3);
        assertTrue(s.isBsContentTransformActive());
        s.clearBsContentTransform();
        assertFalse(s.isBsContentTransformActive());
        float[] out = new float[6];
        s.copyBsContentTransform(out);
        // Identity + zero offset, so a stale preset can never colour a later frame.
        assertEquals(1f, out[0], EPS);
        assertEquals(0f, out[1], EPS);
        assertEquals(0f, out[2], EPS);
        assertEquals(1f, out[3], EPS);
        assertEquals(0f, out[4], EPS);
        assertEquals(0f, out[5], EPS);
    }

    @Test
    public void degenerateCropFallsBackToInactiveInsteadOfASingularMatrix() {
        GpuStreamScaler s = newScaler();
        // 0.95 is the per-edge clamp, so both edges together remove everything.
        s.setBsContentTransform(0f, 0.95f, 0f, 0.95f, 0f, false, false, 1f, 0f, 0f, ASPECT_4_3);
        assertFalse("no source rectangle left → must not stay active",
                s.isBsContentTransformActive());
    }

    // ==================== mirror ====================

    @Test
    public void horizontalFlipMirrorsAcrossTheVerticalAxis() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(0f, 0f, 0f, 0f, 0f, true, false, 1f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        assertMaps(m, 0f, 0.5f, 1f, 0.5f, "left edge reads the right edge");
        assertMaps(m, 1f, 0.5f, 0f, 0.5f, "right edge reads the left edge");
        assertMaps(m, 0.5f, 0.25f, 0.5f, 0.25f, "centre column and rows are fixed");
    }

    @Test
    public void verticalFlipMirrorsAcrossTheHorizontalAxis() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(0f, 0f, 0f, 0f, 0f, false, true, 1f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        assertMaps(m, 0.5f, 0f, 0.5f, 1f, "top edge reads the bottom edge");
        assertMaps(m, 0.5f, 1f, 0.5f, 0f, "bottom edge reads the top edge");
    }

    // ==================== rotation ====================

    @Test
    public void oneEightyTurnsThePictureThroughTheCentre() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(180f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        assertMaps(m, 0f, 0f, 1f, 1f, "top-left reads bottom-right");
        assertMaps(m, 1f, 1f, 0f, 0f, "bottom-right reads top-left");
        assertMaps(m, 0.5f, 0.5f, 0.5f, 0.5f, "centre is fixed");
    }

    @Test
    public void quarterTurnOnASquareCardMovesCornersRoundClockwise() {
        GpuStreamScaler s = newScaler();
        // Square card: the aspect correction is 1, so the corner mapping is exact and
        // the direction of the turn is unambiguous.
        s.setBsContentTransform(90f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, 1f);
        float[] m = transformOf(s);
        // Output top-left reads source bottom-left; output top-right reads source
        // top-left. The source's top edge therefore appears down the output's right
        // edge — a clockwise turn, the same sense as a positive Android rotation.
        assertMaps(m, 0f, 0f, 0f, 1f, "top-left reads bottom-left");
        assertMaps(m, 1f, 0f, 0f, 0f, "top-right reads top-left");
        assertMaps(m, 1f, 1f, 1f, 0f, "bottom-right reads top-right");
        assertMaps(m, 0.5f, 0.5f, 0.5f, 0.5f, "centre is fixed");
    }

    /**
     * The no-shear rule, stated as a test.
     *
     * <p>Fitting the kept crop onto the card scales the two axes independently, so a
     * turn only stays rigid when the card already has the shape of the region being
     * shown: {@code cardAspect == sourceAspect * keptWidth / keptHeight}. Off that
     * condition the picture is stretched as well as turned, which is why a preset that
     * keeps half the width must be paired with a card half as wide. This is the
     * executable form of that pairing rule - get the card shape wrong and it fires.
     */
    @Test
    public void turningIsRigidWhenTheCardMatchesTheShapeBeingShown() {
        // A 4:3 source with half its width cropped away is showing a 2:3 region, so the
        // card must be 2:3. That is exactly the field preset shape.
        assertRigidTurn(41f, /*sourceAspect=*/ASPECT_4_3, /*cropRight=*/0.5f, /*cardAspect=*/2f / 3f);
        // Uncropped: the card simply matches the source.
        assertRigidTurn(41f, ASPECT_4_3, 0f, ASPECT_4_3);
        // A square source, to show the rule is not tied to 4:3.
        assertRigidTurn(41f, 1f, 0.5f, 0.5f);
    }

    @Test
    public void turningShearsWhenTheCardDoesNotMatchTheShapeBeingShown() {
        // Same crop, but the card was left at the source full 4:3 - the stretch the
        // rule warns about. Asserting it explicitly keeps the rule from being quietly
        // "fixed" by making the transform aspect-agnostic.
        assertSheared(41f, ASPECT_4_3, /*cropRight=*/0.5f, /*cardAspect=*/ASPECT_4_3);
    }

    /** Two card offsets of equal displayed length must map to source offsets of equal
     *  displayed length - the definition of a rigid turn. */
    private void assertRigidTurn(float rot, float sourceAspect, float cropRight, float cardAspect) {
        assertEquals("expected a rigid turn", 1.0,
                turnAnisotropy(rot, sourceAspect, cropRight, cardAspect), 1e-3);
    }

    private void assertSheared(float rot, float sourceAspect, float cropRight, float cardAspect) {
        double r = turnAnisotropy(rot, sourceAspect, cropRight, cardAspect);
        assertTrue("expected a sheared turn, got ratio " + r, Math.abs(r - 1.0) > 0.05);
    }

    private double turnAnisotropy(float rot, float sourceAspect, float cropRight, float cardAspect) {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(rot, 0f, 0f, cropRight, 0f, false, false, 1f, 0f, 0f, cardAspect);
        return anisotropyOf(transformOf(s), cardAspect, sourceAspect);
    }

    /** Ratio of the source-space lengths produced by two equal-length card offsets.
     *  1 means rigid; anything else is the stretch factor. */
    private static double anisotropyOf(float[] m, float cardAspect, float sourceAspect) {
        // Card uv is normalised on both axes, so a step of d across a card that is
        // cardAspect times wider than it is tall covers d*cardAspect of on-screen
        // length in the height units. Stepping d*cardAspect DOWN covers the same
        // on-screen length - that pairing is what makes the two steps comparable.
        float d = 0.1f;
        float[] c = map(m, 0.5f, 0.5f);
        float[] alongX = map(m, 0.5f + d, 0.5f);
        float[] alongY = map(m, 0.5f, 0.5f + d * cardAspect);
        // Measure the resulting source steps in the SOURCE own displayed metric.
        double lx = Math.hypot((alongX[0] - c[0]) * sourceAspect, alongX[1] - c[1]);
        double ly = Math.hypot((alongY[0] - c[0]) * sourceAspect, alongY[1] - c[1]);
        return (ly > 1e-9) ? lx / ly : Double.NaN;
    }

    // ==================== crop ====================

    @Test
    public void croppingTheRightHalfCentresOnTheLeftHalfOfTheSource() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(0f, 0f, 0f, 0.5f, 0f, false, false, 1f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        // With the right half removed, the card shows source x ∈ [0, 0.5] across its
        // full width — so the card centre sits at source x = 0.25.
        assertMaps(m, 0.5f, 0.5f, 0.25f, 0.5f, "centre reads the kept half's centre");
        assertMaps(m, 0f, 0.5f, 0f, 0.5f, "left edge reads the source left edge");
        assertMaps(m, 1f, 0.5f, 0.5f, 0.5f, "right edge reads the crop boundary");
    }

    @Test
    public void croppingTheLeftHalfCentresOnTheRightHalfOfTheSource() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(0f, 0.5f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        assertMaps(m, 0.5f, 0.5f, 0.75f, 0.5f, "centre reads the kept half's centre");
        assertMaps(m, 0f, 0.5f, 0.5f, 0.5f, "left edge reads the crop boundary");
        assertMaps(m, 1f, 0.5f, 1f, 0.5f, "right edge reads the source right edge");
    }

    // ==================== zoom and translate ====================

    @Test
    public void zoomShowsLessOfTheSourceAroundAFixedCentre() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(0f, 0f, 0f, 0f, 0f, false, false, 2f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        assertMaps(m, 0.5f, 0.5f, 0.5f, 0.5f, "centre stays put");
        // 2x zoom → the card spans half the source on each axis, centred: [0.25, 0.75].
        assertMaps(m, 0f, 0f, 0.25f, 0.25f, "top-left reads a quarter in");
        assertMaps(m, 1f, 1f, 0.75f, 0.75f, "bottom-right reads a quarter in");
    }

    @Test
    public void translateMovesThePictureNotTheSamplingWindow() {
        GpuStreamScaler s = newScaler();
        // +0.25 of card width means the PICTURE shifts right by a quarter card, which
        // is the same as the sampling window moving a quarter LEFT.
        s.setBsContentTransform(0f, 0f, 0f, 0f, 0f, false, false, 1f, 0.25f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        assertMaps(m, 0.5f, 0.5f, 0.25f, 0.5f, "card centre now shows source x=0.25");
        assertMaps(m, 0.75f, 0.5f, 0.5f, 0.5f, "source centre has moved right");
    }

    @Test
    public void verticalTranslateUsesTheCardHeightFraction() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(0f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, -0.25f, ASPECT_4_3);
        float[] m = transformOf(s);
        // Negative = picture moves up, so the card centre shows a point lower down.
        assertMaps(m, 0.5f, 0.5f, 0.5f, 0.75f, "card centre now shows source y=0.75");
    }

    // ==================== the two field presets ====================

    @Test
    public void leftPresetFramesTheKeptHalfMirroredAndTurned() {
        GpuStreamScaler s = newScaler();
        // Reference framing for the left camera: keep the left half, mirror, turn 310°,
        // zoom 1.4, nudge slightly left and up.
        s.setBsContentTransform(310f, 0f, 0f, 0.5f, 0f, true, false, 1.4f,
                -0.02f, -0.04f, ASPECT_4_3);
        float[] m = transformOf(s);
        float[] centre = map(m, 0.5f, 0.5f);
        // The nudge is small, so the card centre must still be looking near the middle
        // of the KEPT half (source x ≈ 0.25) rather than at the frame centre.
        assertTrue("centre should sample the kept left half, got x=" + centre[0],
                centre[0] > 0.15f && centre[0] < 0.35f);
        assertTrue("centre should sample mid-height, got y=" + centre[1],
                centre[1] > 0.35f && centre[1] < 0.65f);
        // Mirrored: moving right across the card must move LEFT across the source,
        // i.e. the horizontal component of the x-derivative is negative.
        float[] right = map(m, 0.75f, 0.5f);
        assertTrue("horizontal mirror expected", right[0] < centre[0]);
    }

    @Test
    public void rightPresetFramesTheOppositeHalf() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(40f, 0.5f, 0f, 0f, 0f, true, false, 1.4f,
                0f, -0.04f, ASPECT_4_3);
        float[] m = transformOf(s);
        float[] centre = map(m, 0.5f, 0.5f);
        assertTrue("centre should sample the kept right half, got x=" + centre[0],
                centre[0] > 0.65f && centre[0] < 0.85f);
        float[] right = map(m, 0.75f, 0.5f);
        assertTrue("horizontal mirror expected", right[0] < centre[0]);
    }

    /**
     * The two preset angles are NOT an exact mirror pair, and that is on purpose.
     *
     * <p>310° is 50° short of a full turn while 40° is 40° past zero, so the right
     * view is turned 10° less than the left one is. It reads as an oversight — the
     * symmetric choice would be 320°/40° — but these are the reference values as
     * measured, and the asymmetry is visible in the framing. Pinning it here means a
     * later "tidy-up" to a symmetric pair has to be a deliberate decision with this
     * test updated, rather than a silent change to what the driver sees.
     */
    @Test
    public void thePresetAnglesAreDeliberatelyNotSymmetric() {
        GpuStreamScaler left = newScaler();
        GpuStreamScaler right = newScaler();
        left.setBsContentTransform(310f, 0f, 0f, 0.5f, 0f, true, false, 1.4f,
                0f, 0f, ASPECT_4_3);
        right.setBsContentTransform(40f, 0.5f, 0f, 0f, 0f, true, false, 1.4f,
                0f, 0f, ASPECT_4_3);
        float[] lm = transformOf(left);
        float[] rm = transformOf(right);
        // Both centres sit at their kept half's centre — that part IS symmetric.
        assertMaps(lm, 0.5f, 0.5f, 0.25f, 0.5f, "left centre");
        assertMaps(rm, 0.5f, 0.5f, 0.75f, 0.5f, "right centre");
        // The turns are not: |cos 310| != |cos 40| because 310 is -50, not -40.
        float leftCos = Math.abs(lm[3]);    // m11 carries cos/zoom for both
        float rightCos = Math.abs(rm[3]);
        assertTrue("310 and 40 are not the same turn off horizontal",
                Math.abs(leftCos - rightCos) > 0.05f);
    }

    // ==================== preset: card shape + content together ====================

    @Test
    public void presetNarrowsTheCardToTheShapeItIsShowing() {
        GpuStreamScaler s = newScaler();   // 640x480 buffer = 4:3
        s.setBsPreset(310f, 0f, 0f, 0.5f, 0f, true, false, 1.4f, 0f, 0f, 0f, 1);
        // Half the width kept from a 4:3 source is a 2:3 region, so that is the shape
        // the card must take. Checked against what the preset published, not against a
        // re-derivation of the same formula.
        assertEquals("card should be reshaped to the kept region", 2f / 3f,
                s.getBsCardAspect(), 5e-3f);
        // Roughly half the buffer width, the small excess being the card margin, which
        // is a fixed inset rather than a fraction of the narrowed card.
        float frac = s.getBsCardWidthFrac();
        assertTrue("card should be about half the buffer width, got " + frac,
                frac > 0.48f && frac < 0.56f);
    }

    @Test
    public void presetProducesARigidTurn() {
        GpuStreamScaler s = newScaler();
        s.setBsPreset(310f, 0f, 0f, 0.5f, 0f, true, false, 1.4f, 0f, 0f, 0f, 1);
        // The whole point of reshaping the card: with the shape it chose, the turn must
        // come out rigid against the 4:3 source. A mirror flips orientation but not
        // length, so the ratio is still 1.
        double r = anisotropyOf(transformOf(s), s.getBsCardAspect(), ASPECT_4_3);
        assertEquals("preset should turn without stretching", 1.0, r, 5e-3);
    }

    @Test
    public void bothPresetsProduceRigidTurns() {
        GpuStreamScaler right = newScaler();
        right.setBsPreset(40f, 0.5f, 0f, 0f, 0f, true, false, 1.4f, 0f, 0f, 0f, -1);
        double r = anisotropyOf(transformOf(right), right.getBsCardAspect(), ASPECT_4_3);
        assertEquals("right preset should turn without stretching", 1.0, r, 5e-3);
    }

    @Test
    public void clearingThePresetRestoresAFullWidthCard() {
        GpuStreamScaler s = newScaler();
        s.setBsPreset(310f, 0f, 0f, 0.5f, 0f, true, false, 1.4f, 0f, 0f, 0f, 1);
        assertTrue(s.isBsContentTransformActive());
        s.clearBsPreset();
        assertFalse("content transform must go inactive", s.isBsContentTransformActive());
        assertEquals("card must go back to full width", 1.0f, s.getBsCardWidthFrac(), EPS);
    }

    @Test
    public void aPresetWithNoCropLeavesTheCardFullWidth() {
        GpuStreamScaler s = newScaler();
        // Turning without cropping needs no reshape - the card already has the shape of
        // what it is showing. This is the case that must not narrow the card.
        s.setBsPreset(30f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, 0f, 0);
        assertEquals("no crop must not narrow the card", 1.0f, s.getBsCardWidthFrac(), 5e-3f);
    }

    @Test
    public void aDegeneratePresetFallsBackToAPlainCard() {
        GpuStreamScaler s = newScaler();
        s.setBsPreset(40f, 0.95f, 0f, 0.95f, 0f, true, false, 1.4f, 0f, 0f, 0f, 1);
        assertFalse(s.isBsContentTransformActive());
        assertEquals(1.0f, s.getBsCardWidthFrac(), EPS);
    }

    @Test
    public void presentingTheSourceAtAWiderShapeReshapesTheCardToMatch() {
        GpuStreamScaler s = newScaler();   // 4:3 buffer
        // The field presets present the source at 16:9 regardless of the shape it
        // arrives in, because that is the shape the reference framing was composed
        // against. Half of a 16:9 presentation is 8:9, so that is the card.
        s.setBsPreset(310f, 0f, 0f, 0.5f, 0f, true, false, 1.4f, 0f, 0f, 16f / 9f, 1);
        assertEquals("card should follow the PRESENTED shape, not the arriving one",
                8f / 9f, s.getBsCardAspect(), 5e-3f);
        double r = anisotropyOf(transformOf(s), s.getBsCardAspect(), 16f / 9f);
        assertEquals("turn must stay rigid against the presented source", 1.0, r, 5e-3);
    }

    // ==================== robustness ====================

    @Test
    public void outOfRangeInputsAreClampedRatherThanTrusted() {
        GpuStreamScaler s = newScaler();
        // A zoom of 1000 and a negative crop would come from a corrupt config, not from
        // the UI. Clamping keeps the card readable instead of collapsing it to a texel.
        s.setBsContentTransform(0f, -1f, 0f, 0f, 0f, false, false, 1000f, 0f, 0f, ASPECT_4_3);
        float[] m = transformOf(s);
        float[] c = map(m, 0.5f, 0.5f);
        assertEquals("centre still centred after clamping", 0.5f, c[0], EPS);
        assertEquals("centre still centred after clamping", 0.5f, c[1], EPS);
        // Clamped to zoom 10, so the card spans a tenth of the source, not a thousandth.
        float[] corner = map(m, 0f, 0f);
        assertEquals("zoom clamped to 10", 0.45f, corner[0], EPS);
    }

    @Test
    public void anAspectOfZeroIsTreatedAsSquareRatherThanDividingByZero() {
        GpuStreamScaler s = newScaler();
        s.setBsContentTransform(90f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, 0f);
        float[] m = transformOf(s);
        for (float v : m) {
            assertTrue("no NaN/Inf may reach the shader", !Float.isNaN(v) && !Float.isInfinite(v));
        }
        assertMaps(m, 0.5f, 0.5f, 0.5f, 0.5f, "centre still fixed");
    }

    @Test
    public void rotationWrapsSoEquivalentAnglesAgree() {
        GpuStreamScaler a = newScaler();
        GpuStreamScaler b = newScaler();
        a.setBsContentTransform(-50f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, ASPECT_4_3);
        b.setBsContentTransform(310f, 0f, 0f, 0f, 0f, false, false, 1f, 0f, 0f, ASPECT_4_3);
        float[] ma = transformOf(a);
        float[] mb = transformOf(b);
        for (int i = 0; i < 6; i++) {
            assertEquals("-50 and 310 must be the same turn", ma[i], mb[i], EPS);
        }
    }
}
