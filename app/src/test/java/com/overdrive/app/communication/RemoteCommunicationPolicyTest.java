package com.overdrive.app.communication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteCommunicationPolicyTest {

    @Test
    public void enforcesHardVoiceLimits() {
        assertFalse(RemoteCommunicationPolicy.shouldStopForLimit(29_999L));
        assertTrue(RemoteCommunicationPolicy.shouldStopForLimit(30_000L));
        assertFalse(RemoteCommunicationPolicy.shouldStopForInactivity(2_999L));
        assertTrue(RemoteCommunicationPolicy.shouldStopForInactivity(3_000L));
    }

    @Test
    public void validatesTheTwoHundredCharacterMessageLimit() {
        assertEquals("Message is required",
                RemoteCommunicationPolicy.validateMessage("   "));
        assertNull(RemoteCommunicationPolicy.validateMessage(repeat('a', 200)));
        assertEquals("Message must be 200 characters or fewer",
                RemoteCommunicationPolicy.validateMessage(repeat('a', 201)));
    }

    @Test
    public void dialogsAreDowngradedUnlessTheVehicleIsParked() {
        assertEquals("dialog",
                RemoteCommunicationPolicy.effectiveKind("dialog", true));
        assertEquals("toast",
                RemoteCommunicationPolicy.effectiveKind("dialog", false));
        assertEquals("toast",
                RemoteCommunicationPolicy.effectiveKind("unknown", true));
    }

    @Test
    public void normalizesMessageOptionsAndOutputLevel() {
        assertEquals("alert",
                RemoteCommunicationPolicy.normalizeSeverity("danger"));
        assertEquals("info",
                RemoteCommunicationPolicy.normalizeSeverity("other"));
        assertEquals("bottom",
                RemoteCommunicationPolicy.normalizePosition("other"));
        assertEquals("long",
                RemoteCommunicationPolicy.normalizeDuration("LONG"));
        assertEquals(0, RemoteCommunicationPolicy.clampOutputLevel(-1));
        assertEquals(100, RemoteCommunicationPolicy.clampOutputLevel(101));
        assertEquals(100,
                RemoteCommunicationPolicy.effectiveOutputLevel(false, 25));
        assertEquals(25,
                RemoteCommunicationPolicy.effectiveOutputLevel(true, 25));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
