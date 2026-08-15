package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EmptyMotionDiscardPolicyTest {
    @Test
    public void highMotionSourceRemainsDiscardEligible() {
        assertTrue(EmptyMotionDiscardPolicy.isMotionSourceTrigger("motion"));
    }

    @Test
    public void trackerAndDeferredTriggersRemainProtected() {
        assertFalse(EmptyMotionDiscardPolicy.isMotionSourceTrigger("tracker"));
        assertFalse(EmptyMotionDiscardPolicy.isMotionSourceTrigger("baseline-person"));
        assertFalse(EmptyMotionDiscardPolicy.isMotionSourceTrigger(null));
    }

    @Test
    public void preTriggerRawDetectionCarriesOnlyFromCurrentSequence() {
        long sequenceStartMs = 10_000L;

        assertFalse(EmptyMotionDiscardPolicy.rawDetectionBelongsToSequence(
                9_999L, sequenceStartMs));
        assertTrue(EmptyMotionDiscardPolicy.rawDetectionBelongsToSequence(
                10_000L, sequenceStartMs));
        assertTrue(EmptyMotionDiscardPolicy.rawDetectionBelongsToSequence(
                10_250L, sequenceStartMs));
        assertFalse(EmptyMotionDiscardPolicy.rawDetectionBelongsToSequence(
                0L, sequenceStartMs));
    }

    @Test
    public void genuinelyYoloBlindAiTimeoutStillKeepsEvent() {
        assertTrue(EmptyMotionDiscardPolicy.shouldKeepAsYoloBlind(true, false));
        assertFalse(EmptyMotionDiscardPolicy.shouldKeepAsYoloBlind(true, true));
        assertFalse(EmptyMotionDiscardPolicy.shouldKeepAsYoloBlind(false, false));
    }

    @Test
    public void observedHighShadowSignatureReachesRemainingDiscardChecks() {
        boolean eligibleTrigger =
                EmptyMotionDiscardPolicy.isMotionSourceTrigger("motion");
        boolean yoloBlindKeep =
                EmptyMotionDiscardPolicy.shouldKeepAsYoloBlind(true, true);

        assertTrue(eligibleTrigger);
        assertFalse(yoloBlindKeep);
    }
}
