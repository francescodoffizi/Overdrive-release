package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RoadSenseChimeApiHandlerTest {
    private DaemonLogger.Config originalLogConfig;

    @Before
    public void muteAndroidLogger() {
        originalLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false)
                .withStdoutLog(false));
    }

    @After
    public void restoreAndroidLogger() {
        DaemonLogger.configure(originalLogConfig);
    }

    @Test
    public void testChimeMapsEverySeverityWithoutClaimingAudiblePlayback() throws Exception {
        Object[][] cases = {
                {"minor", "roadsense_chime_minor", 56},
                {"moderate", "roadsense_chime_moderate", 68},
                {"severe", "roadsense_chime_severe", 80}
        };

        for (Object[] testCase : cases) {
            String severity = (String) testCase[0];
            String expectedResource = (String) testCase[1];
            int expectedVolume = (Integer) testCase[2];
            String response = invokeTestChime(
                    "{\"severity\":\"" + severity
                            + "\",\"channel\":\"voice\",\"volumePercent\":80}",
                    (resourceName, channel, volumePercent) -> {
                        assertEquals(expectedResource, resourceName);
                        assertEquals("voice", channel);
                        assertEquals(expectedVolume, volumePercent);
                        return true;
                    });

            assertTrue(response.contains("\"success\":true"));
            assertTrue(response.contains("\"dispatched\":true"));
            assertTrue(response.contains("\"playbackConfirmed\":false"));
            assertTrue(response.contains("\"severity\":\"" + severity + "\""));
            assertTrue(response.contains("\"channel\":\"voice\""));
            assertTrue(response.contains("\"masterVolumePercent\":80"));
            assertTrue(response.contains("\"volumePercent\":" + expectedVolume));
        }
    }

    @Test
    public void testChimeRejectsUnknownChannel() throws Exception {
        String response = invokeTestChime("{\"channel\":\"system\"}",
                (resourceName, channel, volumePercent) -> true);

        assertTrue(response.contains("\"success\":false"));
        assertTrue(response.contains("Unsupported channel: system"));
    }

    @Test
    public void testChimeRejectsUnknownSeverity() throws Exception {
        boolean[] dispatched = { false };
        String response = invokeTestChime("{\"severity\":\"critical\"}",
                (resourceName, channel, volumePercent) -> {
                    dispatched[0] = true;
                    return true;
                });

        assertTrue(response.contains("\"success\":false"));
        assertTrue(response.contains("Unsupported severity: critical"));
        assertFalse(dispatched[0]);
    }

    @Test
    public void testChimeRejectsMalformedOrOutOfRangeVolume() throws Exception {
        boolean[] dispatched = { false };
        RoadSenseApiHandler.ChimeDispatcher dispatcher =
                (resourceName, channel, volumePercent) -> {
                    dispatched[0] = true;
                    return true;
                };

        String tooQuiet = invokeTestChime("{\"volumePercent\":9}", dispatcher);
        String fractional = invokeTestChime("{\"volumePercent\":75.5}", dispatcher);
        String text = invokeTestChime("{\"volumePercent\":\"75\"}", dispatcher);

        assertTrue(tooQuiet.contains("\"success\":false"));
        assertTrue(fractional.contains("\"success\":false"));
        assertTrue(text.contains("\"success\":false"));
        assertFalse(dispatched[0]);
    }

    @Test
    public void testChimeChannelParsingDoesNotUseTheDeviceLocale() throws Exception {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            String response = invokeTestChime(
                    "{\"severity\":\"MINOR\",\"channel\":\"VOICE\",\"volumePercent\":80}",
                    (resourceName, channel, volumePercent) -> {
                        assertTrue(resourceName.equals("roadsense_chime_minor"));
                        assertTrue(channel.equals("voice"));
                        assertTrue(volumePercent == 56);
                        return true;
                    });

            assertTrue(response.contains("\"success\":true"));
            assertTrue(response.contains("\"severity\":\"minor\""));
            assertTrue(response.contains("\"channel\":\"voice\""));
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    private static String invokeTestChime(String body, RoadSenseApiHandler.ChimeDispatcher dispatcher)
            throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        RoadSenseApiHandler.handleTestChime(out, body, dispatcher);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
