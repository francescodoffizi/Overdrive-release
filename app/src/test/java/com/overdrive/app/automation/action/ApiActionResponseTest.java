package com.overdrive.app.automation.action;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Keeps API-backed action failure reporting aligned with the in-process HTTP response. */
public class ApiActionResponseTest {

    @Test
    public void acceptsSuccessfulAndAsyncResponses() {
        assertTrue(ApiAction.responseSucceeded(http(200, "{\"success\":true}")));
        assertTrue(ApiAction.responseSucceeded(http(200, "{\"status\":\"ok\"}")));
        assertTrue(ApiAction.responseSucceeded(http(200,
                "{\"success\":false,\"starting\":true}")));
        assertTrue(ApiAction.responseSucceeded("plain 2xx handler body"));
    }

    @Test
    public void rejectsUnroutedHttpAndExplicitApplicationFailures() {
        assertFalse(ApiAction.responseSucceeded(null));
        assertFalse(ApiAction.responseSucceeded(http(404, "Not Found")));
        assertFalse(ApiAction.responseSucceeded(http(500, "{\"success\":true}")));
        assertFalse(ApiAction.responseSucceeded(http(200,
                "{\"success\":false,\"error\":\"rejected\"}")));
        assertFalse(ApiAction.responseSucceeded(http(200, "{\"status\":\"error\"}")));
    }

    private static String http(int status, String body) {
        return "HTTP/1.1 " + status + " Test\r\nContent-Type: application/json\r\n\r\n" + body;
    }
}
