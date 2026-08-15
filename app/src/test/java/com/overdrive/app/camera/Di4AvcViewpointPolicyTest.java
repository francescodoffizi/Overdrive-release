package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Di4AvcViewpointPolicyTest {

    @Test
    public void onlyDilink4EnablesNativeAvcArbitration() {
        assertTrue(Di4AvcViewpointPolicy.isEnabledForCameraMode("dilink4"));
        assertTrue(Di4AvcViewpointPolicy.isEnabledForCameraMode("DiLink4"));
        assertFalse(Di4AvcViewpointPolicy.isEnabledForCameraMode("default"));
        assertFalse(Di4AvcViewpointPolicy.isEnabledForCameraMode(null));

        Di4AvcViewpointPolicy policy = new Di4AvcViewpointPolicy();
        policy.beginSession(false);
        assertEquals(Di4AvcViewpointPolicy.Action.NONE,
            policy.onNativeAvcForeground(true, true));
        assertFalse(policy.isViewpointYielded());
    }

    @Test
    public void dilink4YieldsToForegroundAvcAndRestoresAfterItLeaves() {
        Di4AvcViewpointPolicy policy = new Di4AvcViewpointPolicy();
        policy.beginSession(true);

        assertEquals(Di4AvcViewpointPolicy.Action.YIELD,
            policy.onNativeAvcForeground(true, true));
        policy.markViewpointYielded();
        assertTrue(policy.isViewpointYielded());
        assertEquals(Di4AvcViewpointPolicy.Action.NONE,
            policy.onNativeAvcForeground(true, true));

        assertEquals(Di4AvcViewpointPolicy.Action.RESTORE,
            policy.onNativeAvcForeground(false, true));
        assertTrue(policy.isRestorePending(true));
        policy.markViewpointRestored();
        assertFalse(policy.isViewpointYielded());
    }

    @Test
    public void failedHandoffRemainsRetryableAndUnknownStateDoesNotChangeOwnership() {
        Di4AvcViewpointPolicy policy = new Di4AvcViewpointPolicy();
        policy.beginSession(true);

        assertEquals(Di4AvcViewpointPolicy.Action.YIELD,
            policy.onNativeAvcForeground(true, true));
        assertEquals(Di4AvcViewpointPolicy.Action.YIELD,
            policy.onNativeAvcForeground(true, true));
        policy.markViewpointYielded();

        assertEquals(Di4AvcViewpointPolicy.Action.NONE,
            policy.onNativeAvcForeground(null, true));
        assertTrue(policy.isViewpointYielded());
        assertEquals(Di4AvcViewpointPolicy.Action.RESTORE,
            policy.onNativeAvcForeground(false, true));
        assertEquals(Di4AvcViewpointPolicy.Action.RESTORE,
            policy.onNativeAvcForeground(false, true));
    }
}
