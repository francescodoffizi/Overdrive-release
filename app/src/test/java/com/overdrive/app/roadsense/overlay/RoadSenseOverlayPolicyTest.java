package com.overdrive.app.roadsense.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RoadSenseOverlayPolicyTest {

    @Test
    public void enabledAndVisible_showsOverlay() {
        assertTrue(RoadSenseOverlayPolicy.shouldShow(true, true));
    }

    @Test
    public void masterDisabled_hidesOverlayEvenWhenPreferenceRemainsOn() {
        assertFalse(RoadSenseOverlayPolicy.shouldShow(false, true));
    }

    @Test
    public void visibilityDisabled_hidesOverlayWithoutDisablingRoadSense() {
        assertFalse(RoadSenseOverlayPolicy.shouldShow(true, false));
    }

    @Test
    public void bothDisabled_hidesOverlay() {
        assertFalse(RoadSenseOverlayPolicy.shouldShow(false, false));
    }
}
