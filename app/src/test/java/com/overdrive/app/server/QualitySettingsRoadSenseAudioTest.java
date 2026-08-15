package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class QualitySettingsRoadSenseAudioTest {

    @Test
    public void acceptsAndCanonicalizesRepresentableVolume() throws Exception {
        JSONObject data = new JSONObject().put("warnAudioVolume", 75.0);

        assertNull(QualitySettingsApiHandler.validateRoadSenseAudioSettings(data));
        assertEquals(75, data.getInt("warnAudioVolume"));
        assertEquals(Integer.class, data.get("warnAudioVolume").getClass());
    }

    @Test
    public void rejectsValuesTheSliderCannotRepresent() throws Exception {
        assertNotNull(QualitySettingsApiHandler.validateRoadSenseAudioSettings(
                new JSONObject().put("warnAudioVolume", 9)));
        assertNotNull(QualitySettingsApiHandler.validateRoadSenseAudioSettings(
                new JSONObject().put("warnAudioVolume", 101)));
        assertNotNull(QualitySettingsApiHandler.validateRoadSenseAudioSettings(
                new JSONObject().put("warnAudioVolume", 75.5)));
        assertNotNull(QualitySettingsApiHandler.validateRoadSenseAudioSettings(
                new JSONObject().put("warnAudioVolume", "75")));
    }
}
