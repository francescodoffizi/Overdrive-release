package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.json.JSONObject;
import org.junit.Test;

/** Contract coverage for pyBYD-compatible BOOKINGAIR parameter maps. */
public class ClimateScheduleParamsTest {

    @Test
    public void remoteClimateStartUsesRequestedDurationWithoutChangingTheTarget() throws Exception {
        JSONObject params = BydCloudClient.climateStartParams(15D, 25);

        assertEquals(1, params.getInt("mainSettingTemp"));
        assertEquals(1, params.getInt("copilotSettingTemp"));
        assertEquals(4, params.getInt("timeSpan"));
        assertEquals(4, params.getInt("remoteMode"));
    }

    @Test
    public void createUsesRemoteClimateDefaultsAndConvertsDuration() throws Exception {
        JSONObject params = BydCloudClient.climateScheduleParams(
                1, null, Long.valueOf(1_800_000_000L), Double.valueOf(22D), Integer.valueOf(25));

        assertEquals(1, params.getInt("remoteMode"));
        assertEquals(1_800_000_000L, params.getLong("bookingTime"));
        assertEquals(8, params.getInt("mainSettingTemp"));
        assertEquals(8, params.getInt("copilotSettingTemp"));
        assertEquals(4, params.getInt("timeSpan"));
        assertEquals(2, params.getInt("cycleMode"));
        assertEquals(1, params.getInt("airAccuracy"));
        assertEquals(1, params.getInt("airConditioningMode"));
        assertEquals(0, params.getInt("acSwitch"));
        assertFalse(params.has("airSet"));
    }

    @Test
    public void removeCarriesOnlyTheBookingSelectorAndSharedDefaults() throws Exception {
        JSONObject params = BydCloudClient.climateScheduleParams(
                3, Long.valueOf(1_216_038_691_305_533_440L), null, null, null);

        assertEquals(3, params.getInt("remoteMode"));
        assertEquals(1_216_038_691_305_533_440L, params.getLong("bookingId"));
        assertFalse(params.has("bookingTime"));
        assertFalse(params.has("mainSettingTemp"));
        assertFalse(params.has("timeSpan"));
        assertEquals(0, params.getInt("acSwitch"));
    }

    @Test
    public void rejectsInvalidBookingShapeBeforeCloudDispatch() {
        try {
            BydCloudClient.climateScheduleParams(
                    1, null, Long.valueOf(1L), Double.valueOf(22D), Integer.valueOf(12));
            throw new AssertionError("invalid duration must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("duration"));
        }
    }
}
