package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class GenAiVoiceWebSocketTest {

    @Test
    public void providerSetupsUseNativeAudioWithoutInputTranscription() throws Exception {
        JSONObject openAi = GenAiVoiceWebSocket.openAiSetup(
                "gpt-realtime-2.1", "grounded");
        JSONObject session = openAi.getJSONObject("session");
        assertEquals("audio",
                session.getJSONArray("output_modalities").getString(0));
        assertEquals(24_000, session.getJSONObject("audio")
                .getJSONObject("input")
                .getJSONObject("format")
                .getInt("rate"));
        assertEquals("semantic_vad", session.getJSONObject("audio")
                .getJSONObject("input")
                .getJSONObject("turn_detection")
                .getString("type"));
        assertEquals("propose_overdrive_action",
                session.getJSONArray("tools")
                        .getJSONObject(0).getString("name"));
        assertEquals("get_overdrive_context",
                session.getJSONArray("tools")
                        .getJSONObject(1).getString("name"));
        assertFalse(openAi.toString().contains("transcription"));

        JSONObject gemini = GenAiVoiceWebSocket.geminiSetup(
                "gemini-3.1-flash-live-preview", "grounded");
        JSONObject setup = gemini.getJSONObject("setup");
        assertEquals("models/gemini-3.1-flash-live-preview",
                setup.getString("model"));
        assertTrue(setup.has("outputAudioTranscription"));
        assertFalse(setup.has("inputAudioTranscription"));
        assertEquals("propose_overdrive_action",
                setup.getJSONArray("tools").getJSONObject(0)
                        .getJSONArray("functionDeclarations")
                        .getJSONObject(0).getString("name"));
        assertEquals("get_overdrive_context",
                setup.getJSONArray("tools").getJSONObject(0)
                        .getJSONArray("functionDeclarations")
                        .getJSONObject(1).getString("name"));
    }

    @Test
    public void approvedVoiceContextIsStrictlyBounded() throws Exception {
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 60_000; i++) large.append('x');

        String bounded = GenAiVoiceWebSocket.boundedToolOutput(
                new JSONObject().put("data", large.toString()));

        assertTrue(bounded.length() <= 48_000);
        assertTrue(new JSONObject(bounded).optBoolean("truncated"));
    }
}
