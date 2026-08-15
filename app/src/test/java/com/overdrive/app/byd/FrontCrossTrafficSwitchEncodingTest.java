package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the wire values shared by front cross-traffic alert and braking controls. */
public class FrontCrossTrafficSwitchEncodingTest {

    @Test
    public void enabledAndDisabledUseTheProtocolValues() {
        assertEquals(2, BydDataCollector.frontCrossTrafficSwitchValue(true));
        assertEquals(1, BydDataCollector.frontCrossTrafficSwitchValue(false));
    }
}
