package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.automation.condition.Conditions;

import org.junit.Test;

public class AdasWarningRoutingTest {

    @Test
    public void warningFamiliesRouteToDistinctAutomationBits() {
        assertEquals(BydDataCollector.BS_LEFT_BIT,
                BydDataCollector.adasWarningBitForId(
                        BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM));
        assertEquals(BydDataCollector.BS_LEFT_BIT,
                BydDataCollector.adasWarningBitForId(
                        BydFeatureIds.ADAS_LCA_WARNING_LEFT));
        assertEquals(BydDataCollector.RCTA_LEFT_BIT,
                BydDataCollector.adasWarningBitForId(
                        BydFeatureIds.ADAS_RCTA_WARNING_LEFT));
        assertEquals(BydDataCollector.DOW_LEFT_BIT,
                BydDataCollector.adasWarningBitForId(
                        BydFeatureIds.ADAS_DOW_WARN_LEFT));

        int blindSpotBits =
                BydDataCollector.BS_LEFT_BIT | BydDataCollector.BS_RIGHT_BIT;
        assertFalse((BydDataCollector.RCTA_LEFT_BIT & blindSpotBits) != 0);
        assertFalse((BydDataCollector.DOW_LEFT_BIT & blindSpotBits) != 0);
        assertTrue(BydDataCollector.isAdasLevelWarningId(
                BydFeatureIds.ADAS_FL_BLIND_SPOT_ALARM));
        assertFalse(BydDataCollector.isAdasLevelWarningId(
                BydFeatureIds.ADAS_RCTA_WARNING_LEFT));
    }

    @Test
    public void rctaAndDowAreExposedAsAutomationConditions() {
        Conditions conditions = new Conditions();
        assertEquals("rearCrossTraffic",
                conditions.getCondition("rearCrossTraffic").getLabel().getId());
        assertEquals("doorOpenWarning",
                conditions.getCondition("doorOpenWarning").getLabel().getId());
    }
}
