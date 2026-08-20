package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class SmartChargeCacheTest {

    @Test
    public void cloudSnapshotReplacesAbsentScheduleDtosAndKeepsVin() throws Exception {
        JSONObject homePage = new JSONObject()
                .put("smartChargeDto", new JSONObject()
                        .put("startChargeTime", "22:00")
                        .put("endChargeTime", "06:00")
                        .put("chargeWay", "e")
                        .put("status", "1"))
                .put("smartJourneyDto", new JSONObject().put("enabled", true));

        JSONObject populated = SmartChargeCache.cloudSnapshot("VIN-A", homePage);
        assertTrue(SmartChargeCache.matchesVin(populated, "VIN-A"));
        assertTrue(populated.has("startChargeTime"));
        assertTrue(populated.has("smartJourneyDto"));

        JSONObject cleared = SmartChargeCache.cloudSnapshot("VIN-A", new JSONObject());
        assertTrue(SmartChargeCache.matchesVin(cleared, "VIN-A"));
        assertTrue(!SmartChargeCache.matchesVin(cleared, "VIN-B"));
        assertFalse(cleared.has("startChargeTime"));
        assertFalse(cleared.has("endChargeTime"));
        assertFalse(cleared.has("chargeWay"));
        assertFalse(cleared.has("enabled"));
        assertFalse(cleared.has("smartJourneyDto"));
    }

    @Test
    public void supportsBooleanAndJourneyOnlyStatus() throws Exception {
        assertTrue(SmartChargeCache.cloudEnabled(new JSONObject()
                .put("smartChargeDto", new JSONObject().put("status", true))));
        assertTrue(SmartChargeCache.cloudEnabled(new JSONObject()
                .put("smartChargeDto", new JSONObject().put("enabled", true))));
        assertTrue(SmartChargeCache.cloudEnabled(new JSONObject()
                .put("smartJourneyDto", new JSONObject().put("status", "1"))));
        assertTrue(SmartChargeCache.cloudEnabled(new JSONObject()
                .put("smartJourneyDto", new JSONObject().put("enabled", true))));
        JSONObject journeyOnly = SmartChargeCache.cloudSnapshot("VIN-A", new JSONObject()
                .put("smartJourneyDto", new JSONObject().put("enabled", true)));
        assertTrue(journeyOnly.optBoolean("enabled"));
        assertTrue(journeyOnly.has("smartJourneyDto"));
        assertFalse(journeyOnly.has("startChargeTime"));
        assertNull(SmartChargeCache.cloudEnabled(new JSONObject()));
    }

    @Test
    public void preservesOnlyRecentConfirmedScheduleWhenCloudHasNotCaughtUp() throws Exception {
        long now = 1_000_000L;
        JSONObject confirmed = new JSONObject()
                .put("vin", "VIN-A")
                .put("startChargeTime", "22:00")
                .put("endChargeTime", "06:00")
                .put("chargeWay", "e")
                .put("enabled", true)
                .put("confirmedScheduleAt", now - 1);
        JSONObject lagging = new JSONObject();
        JSONObject matching = new JSONObject().put("smartChargeDto", new JSONObject()
                .put("startChargeTime", "22:00")
                .put("endChargeTime", "06:00")
                .put("chargeWay", "e")
                .put("status", true));

        assertTrue(SmartChargeCache.shouldPreserveConfirmedSchedule(confirmed, lagging, now));
        assertFalse(SmartChargeCache.shouldPreserveConfirmedSchedule(confirmed, matching, now));
        confirmed.put("confirmedScheduleAt", now - (2L * 60L * 1000L) - 1);
        assertFalse(SmartChargeCache.shouldPreserveConfirmedSchedule(confirmed, lagging, now));
    }

    @Test
    public void savedScheduleConfirmationRequiresCompleteCloudEcho() throws Exception {
        JSONObject matching = new JSONObject().put("smartChargeDto", new JSONObject()
                .put("startChargeTime", "22:00")
                .put("endChargeTime", "06:00")
                .put("chargeWay", "e")
                .put("status", "1"));
        assertTrue(BydCloudClient.confirmsSavedSchedule(
                matching, "22:00", "06:00", "e", true));
        assertFalse(BydCloudClient.confirmsSavedSchedule(
                new JSONObject(), "22:00", "06:00", "e", true));
        assertFalse(BydCloudClient.confirmsSavedSchedule(
                matching, "23:00", "06:00", "e", true));
    }

    @Test
    public void confirmedToggleSupersedesRecentScheduleSaveGrace() throws Exception {
        long now = 1_000_000L;
        JSONObject saved = new JSONObject()
                .put("vin", "VIN-A")
                .put("startChargeTime", "22:00")
                .put("endChargeTime", "06:00")
                .put("chargeWay", "e")
                .put("enabled", true)
                .put("confirmedScheduleAt", now - 1L);

        JSONObject toggled = SmartChargeCache.confirmedToggleSnapshot(saved, false);

        assertFalse(toggled.has("confirmedScheduleAt"));
        assertFalse(toggled.optBoolean("enabled"));
        assertFalse(SmartChargeCache.shouldPreserveConfirmedSchedule(
                toggled, new JSONObject(), now));
    }

    @Test
    public void staleHomePageRequestCannotReplaceNewerSnapshot() throws Exception {
        JSONObject current = new JSONObject().put("lastCloudRequestOrder", 200L);

        assertTrue(SmartChargeCache.isOlderCloudResponse(current, 199L));
        assertFalse(SmartChargeCache.isOlderCloudResponse(current, 200L));
        assertFalse(SmartChargeCache.isOlderCloudResponse(current, 201L));
        assertFalse(SmartChargeCache.isOlderCloudResponse(current, 0L));
    }

    @Test
    public void unsupportedTombstoneRejectsOlderInFlightHomePageResponse() throws Exception {
        JSONObject unsupported = SmartChargeCache.unsupportedSnapshot("VIN-A", 200L);

        assertTrue(unsupported.optBoolean("unsupported"));
        assertTrue(SmartChargeCache.isOlderCloudResponse(unsupported, 199L));
        assertFalse(SmartChargeCache.isOlderCloudResponse(unsupported, 201L));
    }

    @Test
    public void retainedConfirmedScheduleStillRecordsTheNewestCloudRequest() throws Exception {
        JSONObject confirmed = new JSONObject()
                .put("vin", "VIN-A")
                .put("confirmedScheduleAt", 1_000_000L)
                .put("enabled", true);

        JSONObject preserved = SmartChargeCache.withCloudRequestOrder(confirmed, 200L);

        assertTrue(SmartChargeCache.isOlderCloudResponse(preserved, 199L));
        assertFalse(SmartChargeCache.isOlderCloudResponse(preserved, 201L));
    }
}
