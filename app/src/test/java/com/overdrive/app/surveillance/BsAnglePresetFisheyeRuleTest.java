package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The rule that decides whether a fisheye/dewarp strength may reach a blind-spot view.
 *
 * <p>The pipeline method that applies it needs a live GL scaler, so what is pinned here
 * is the DECISION the pipeline makes, expressed against the same predicate it uses. The
 * cases are the ones a driver can actually create, in particular the mixed one: the
 * angle is per-side but the dewarp is a single global setting, so one side can be on a
 * quarter turn while the other is on a preset framing.
 *
 * <p>What makes this worth pinning is that the rule must NOT depend on which side is on
 * screen when the setting is written. Keying on the angle rather than on live render
 * state is what removes that ordering dependency, and this is where that property is
 * stated.
 */
public class BsAnglePresetFisheyeRuleTest {

    /** Mirrors GpuSurveillancePipeline.effectiveRectifyFor's decision: a view showing a
     *  preset framing gets 0; every other view gets the configured value. */
    private static int effectiveFor(int angleOfThatSide, int configuredStrength) {
        return BsAnglePreset.isPresetAngle(angleOfThatSide) ? 0 : configuredStrength;
    }

    @Test
    public void aPresetViewNeverReceivesADewarp() {
        assertEquals(0, effectiveFor(40, 50));
        assertEquals(0, effectiveFor(310, 50));
        assertEquals("even at full strength", 0, effectiveFor(40, 100));
    }

    @Test
    public void quarterTurnsKeepTheSettingExactlyAsBefore() {
        // The developer's angles must be untouched by any of this.
        assertEquals(50, effectiveFor(0, 50));
        assertEquals(50, effectiveFor(90, 50));
        assertEquals(50, effectiveFor(180, 50));
        assertEquals(50, effectiveFor(270, 50));
        assertEquals(100, effectiveFor(90, 100));
        assertEquals(0, effectiveFor(90, 0));
    }

    @Test
    public void theMixedCaseSplitsPerSide() {
        // Left on a quarter turn, right on a preset, one global fisheye of 50.
        int left = 90, right = 40, configured = 50;
        assertEquals("the quarter-turn side still gets the dewarp",
                50, effectiveFor(left, configured));
        assertEquals("the preset side is protected regardless",
                0, effectiveFor(right, configured));
    }

    @Test
    public void theOutcomeDoesNotDependOnWhichSideIsOnScreen() {
        // The same setting, evaluated for each side in either order, gives each side the
        // same answer. That is the property that makes a settings write racing a side
        // switch harmless: nothing here reads render state.
        int configured = 50;
        int rightFirst = effectiveFor(40, configured);
        int leftThen = effectiveFor(90, configured);
        int leftFirst = effectiveFor(90, configured);
        int rightThen = effectiveFor(40, configured);
        assertEquals(rightFirst, rightThen);
        assertEquals(leftThen, leftFirst);
        assertEquals(0, rightFirst);
        assertEquals(50, leftFirst);
    }

    @Test
    public void bothSidesOnPresetsMeansTheSettingReachesNothing() {
        // The case where the settings row is hidden: it could not do anything anyway.
        assertEquals(0, effectiveFor(310, 75));
        assertEquals(0, effectiveFor(40, 75));
    }

    @Test
    public void normalisedAnglesAreJudgedTheSame() {
        // A config carrying -50 rather than 310 must protect the view just the same.
        assertEquals(0, effectiveFor(-50, 50));
        assertEquals(0, effectiveFor(400, 50));
    }
}
