package com.overdrive.app.genai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class GenAiConfigTest {

    @Test
    public void publicConfigNeverContainsCredential() {
        GenAiConfig config = new GenAiConfig(
                true,
                GenAiConfig.PROVIDER_OPENAI,
                "https://api.openai.com/",
                "example-model",
                "example-realtime",
                "secret-value",
                1200);

        JSONObject publicJson = config.toPublicJson();
        assertFalse(publicJson.has("apiKey"));
        assertFalse(publicJson.toString().contains("secret-value"));
        assertTrue(publicJson.optBoolean("apiKeyConfigured"));
        assertEquals("https://api.openai.com",
                publicJson.optString("baseUrl"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void providerUrlRejectsEmbeddedCredentials() {
        GenAiConfig.normalizeBaseUrlOrDefault(
                "https://user:secret@example.com", GenAiConfig.PROVIDER_OPENAI);
    }

    @Test(expected = IllegalArgumentException.class)
    public void firstPartyProviderRejectsCleartextHttp() {
        GenAiConfig.normalizeBaseUrlOrDefault(
                "http://api.openai.com", GenAiConfig.PROVIDER_OPENAI);
    }

    @Test(expected = IllegalArgumentException.class)
    public void compatibleProviderRejectsPublicCleartextHttp() {
        GenAiConfig.normalizeBaseUrlOrDefault(
                "http://example.com", GenAiConfig.PROVIDER_OPENAI_COMPATIBLE);
    }

    @Test
    public void compatibleProviderAllowsPrivateCleartextHttp() {
        assertEquals("http://192.168.1.20:11434",
                GenAiConfig.normalizeBaseUrlOrDefault(
                        "http://192.168.1.20:11434/",
                        GenAiConfig.PROVIDER_OPENAI_COMPATIBLE));
    }

    @Test
    public void providerSwitchRequiresANewCredential() throws Exception {
        assertFalse(GenAiConfig.hasNewApiKey(new JSONObject()));
        assertFalse(GenAiConfig.hasNewApiKey(
                new JSONObject().put("apiKey", "   ")));
        assertTrue(GenAiConfig.hasNewApiKey(
                new JSONObject().put("apiKey", "new-provider-key")));
    }

    @Test
    public void credentialScopeIsBoundToSchemeHostAndPort() {
        assertTrue(GenAiConfig.sameCredentialOrigin(
                "https://api.openai.com",
                "https://api.openai.com/v1"));
        assertTrue(GenAiConfig.sameCredentialOrigin(
                "https://api.openai.com",
                "https://api.openai.com:443"));
        assertFalse(GenAiConfig.sameCredentialOrigin(
                "https://api.openai.com",
                "https://proxy.example"));
        assertFalse(GenAiConfig.sameCredentialOrigin(
                "http://192.168.1.20:11434",
                "http://192.168.1.20:8080"));
    }
}
