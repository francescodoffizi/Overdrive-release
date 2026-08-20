package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SocHistoryDatabaseWindowTest {

    @Test
    public void chargingSocWindowSupportsFullThirtyDays() {
        assertEquals(1,
                SocHistoryDatabase.clampSocHistoryHours(0));
        assertEquals(168,
                SocHistoryDatabase.clampSocHistoryHours(168));
        assertEquals(720,
                SocHistoryDatabase.clampSocHistoryHours(720));
        assertEquals(720,
                SocHistoryDatabase.clampSocHistoryHours(5_000));
    }
}
