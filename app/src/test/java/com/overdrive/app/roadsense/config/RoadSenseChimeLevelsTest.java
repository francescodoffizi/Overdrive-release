package com.overdrive.app.roadsense.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RoadSenseChimeLevelsTest {

    @Test
    public void clampsTheUserMasterLevel() {
        assertEquals(10, RoadSenseChimeLevels.normalizeMasterPercent(-50));
        assertEquals(75, RoadSenseChimeLevels.normalizeMasterPercent(75));
        assertEquals(100, RoadSenseChimeLevels.normalizeMasterPercent(500));
    }

    @Test
    public void persistenceValidationRejectsUnrepresentableValues() {
        assertEquals(Integer.valueOf(75), RoadSenseChimeLevels.validatedMasterPercent(75));
        assertEquals(Integer.valueOf(75), RoadSenseChimeLevels.validatedMasterPercent(75.0));
        assertNull(RoadSenseChimeLevels.validatedMasterPercent(9));
        assertNull(RoadSenseChimeLevels.validatedMasterPercent(101));
        assertNull(RoadSenseChimeLevels.validatedMasterPercent(75.5));
        assertNull(RoadSenseChimeLevels.validatedMasterPercent("75"));
        assertNull(RoadSenseChimeLevels.validatedMasterPercent(null));
    }

    @Test
    public void severityScalesRemainBelowTheSelectedCeiling() {
        assertEquals(53, RoadSenseChimeLevels.effectivePercent(75, 1));
        assertEquals(64, RoadSenseChimeLevels.effectivePercent(75, 2));
        assertEquals(75, RoadSenseChimeLevels.effectivePercent(75, 3));
    }

    @Test
    public void maximumSettingRestoresTheFullSeverityRange() {
        assertEquals(70, RoadSenseChimeLevels.effectivePercent(100, 1));
        assertEquals(85, RoadSenseChimeLevels.effectivePercent(100, 2));
        assertEquals(100, RoadSenseChimeLevels.effectivePercent(100, 3));
    }
}
