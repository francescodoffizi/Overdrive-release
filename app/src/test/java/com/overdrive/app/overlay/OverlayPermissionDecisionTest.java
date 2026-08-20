package com.overdrive.app.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverlayPermissionDecisionTest {

    @Test
    public void explicitAppOpsDenialOverridesFrameworkFalsePositive() {
        assertFalse(OverlayPermissionDecision.resolve(Boolean.FALSE, true));
    }

    @Test
    public void explicitAppOpsGrantOverridesFrameworkFalseNegative() {
        assertTrue(OverlayPermissionDecision.resolve(Boolean.TRUE, false));
    }

    @Test
    public void frameworkIsOnlyFallbackWhenAppOpsIsUnavailable() {
        assertTrue(OverlayPermissionDecision.resolve(null, true));
        assertFalse(OverlayPermissionDecision.resolve(null, false));
    }
}
