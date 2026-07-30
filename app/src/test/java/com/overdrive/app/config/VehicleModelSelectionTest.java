package com.overdrive.app.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VehicleModelSelectionTest {

    @Test
    public void rendererFallbackIsNotASelectedPhysicalVehicle() {
        assertFalse(VehicleModelSelection.isResolvedSelection(
                "seal", VehicleModelSelection.SOURCE_UNSET));
        assertNull(VehicleModelSelection.resolvedModelId(
                "seal", VehicleModelSelection.SOURCE_UNSET));
    }

    @Test
    public void explicitAndAutoSelectionsAreResolved() {
        assertTrue(VehicleModelSelection.isResolvedSelection(
                "atto3", VehicleModelSelection.SOURCE_USER));
        assertEquals("atto3", VehicleModelSelection.resolvedModelId(
                " atto3 ", VehicleModelSelection.SOURCE_AUTO));
    }

    @Test
    public void preProvenanceConfigsRemainCompatible() {
        assertEquals(VehicleModelSelection.SOURCE_LEGACY,
                VehicleModelSelection.normalizeSource(null, "atto3"));
        assertTrue(VehicleModelSelection.isResolvedSelection("atto3", null));
    }

    @Test
    public void missingModelCannotBecomeASelection() {
        assertEquals(VehicleModelSelection.SOURCE_UNSET,
                VehicleModelSelection.normalizeSource(null, ""));
        assertFalse(VehicleModelSelection.isResolvedSelection(
                " ", VehicleModelSelection.SOURCE_USER));
    }
}
