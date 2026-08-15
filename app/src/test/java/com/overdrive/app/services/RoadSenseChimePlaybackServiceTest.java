package com.overdrive.app.services;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.R;

import org.junit.Test;

public class RoadSenseChimePlaybackServiceTest {

    @Test
    public void resolvesOnlyTheThreePackagedRoadSenseCues() {
        assertEquals(R.raw.roadsense_chime_minor,
                RoadSenseChimePlaybackService.rawResourceId("roadsense_chime_minor"));
        assertEquals(R.raw.roadsense_chime_moderate,
                RoadSenseChimePlaybackService.rawResourceId("roadsense_chime_moderate"));
        assertEquals(R.raw.roadsense_chime_severe,
                RoadSenseChimePlaybackService.rawResourceId("roadsense_chime_severe"));
        assertEquals(0, RoadSenseChimePlaybackService.rawResourceId("unknown"));
        assertEquals(0, RoadSenseChimePlaybackService.rawResourceId(null));
    }

}
