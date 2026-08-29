package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenAiRequestSecurityTest {

    @Test
    public void browserOriginMustMatchEffectiveHost() {
        assertTrue(GenAiRequestSecurity.isAllowedOrigin(
                "http://127.0.0.1:8080", "127.0.0.1:8080", null));
        assertTrue(GenAiRequestSecurity.isAllowedOrigin(
                "https://car.example", "127.0.0.1:8080", "car.example"));
        assertTrue(GenAiRequestSecurity.isAllowedOrigin(
                null, "127.0.0.1:8080", null));
        assertFalse(GenAiRequestSecurity.isAllowedOrigin(
                "https://evil.example", "127.0.0.1:8080", null));
        assertFalse(GenAiRequestSecurity.isAllowedOrigin(
                "null", "127.0.0.1:8080", null));
    }

    @Test
    public void identifiesOnlyTheGenAiTrustBoundary() {
        assertTrue(GenAiRequestSecurity.isGenAiPath("/api/genai/chat"));
        assertTrue(GenAiRequestSecurity.isGenAiPath("/ws/genai"));
        assertTrue(GenAiRequestSecurity.isGenAiPath("/ws/genai/chat"));
        assertFalse(GenAiRequestSecurity.isGenAiPath("/api/community/list"));
    }
}
