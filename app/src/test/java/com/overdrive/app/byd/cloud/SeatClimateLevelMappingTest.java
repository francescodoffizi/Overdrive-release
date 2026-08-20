package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.json.JSONObject;
import org.junit.Test;

/** Guards the three-level local contract against a cloud wire-off regression. */
public class SeatClimateLevelMappingTest {

    @Test
    public void mapsOnlyThePublishedOffLowHighLevels() {
        assertEquals(3, BydCloudClient.uiToWireSeatLevel(0));
        assertEquals(2, BydCloudClient.uiToWireSeatLevel(1));
        assertEquals(1, BydCloudClient.uiToWireSeatLevel(2));
    }

    @Test
    public void normalizesLegacyRawThreeToHighInsteadOfWireOff() {
        assertEquals(1, BydCloudClient.uiToWireSeatLevel(3));
    }

    @Test
    public void seatPayloadUsesPyBydNumericWireFields() throws Exception {
        JSONObject payload = BydCloudClient.seatClimateParams("1", 2, 1, 0, 2, 1);

        assertEquals("1", payload.getString("chairType"));
        assertTrue(payload.get("remoteMode") instanceof Number);
        assertTrue(payload.get("mainHeat") instanceof Number);
        assertTrue(payload.get("mainVentilation") instanceof Number);
        assertTrue(payload.get("copilotHeat") instanceof Number);
        assertTrue(payload.get("copilotVentilation") instanceof Number);
        assertTrue(payload.get("lrSeatHeatState") instanceof Number);
        assertTrue(payload.get("lrThirdHeatState") instanceof Number);
        assertTrue(payload.get("lrThirdVentilationState") instanceof Number);
        assertTrue(payload.get("rrThirdHeatState") instanceof Number);
        assertTrue(payload.get("rrThirdVentilationState") instanceof Number);
        assertTrue(payload.get("steeringWheelHeatState") instanceof Number);
        assertEquals(1, payload.getInt("mainHeat"));
        assertEquals(2, payload.getInt("mainVentilation"));
        assertEquals(3, payload.getInt("copilotHeat"));
        assertEquals(1, payload.getInt("copilotVentilation"));
        assertEquals(0, payload.getInt("lrThirdHeatState"));
        assertEquals(0, payload.getInt("rrThirdVentilationState"));
        assertEquals(1, payload.getInt("steeringWheelHeatState"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesLevelsOutsideTheLegacyAndPublishedRanges() {
        BydCloudClient.uiToWireSeatLevel(4);
    }

    @Test
    public void refusesAnUnknownSteeringWheelStateInsteadOfTurningItOff() {
        try {
            BydCloudClient.seatClimateParams("1", 0, 0, 0, 0, 2);
            throw new AssertionError("unknown steering-wheel state must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Steering wheel"));
        }
    }
}
