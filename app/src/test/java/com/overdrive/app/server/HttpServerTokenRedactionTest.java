package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HttpServerTokenRedactionTest {

    @Test
    public void websocketBearerTokenIsNotWrittenToRequestLogs() {
        assertEquals(
                "GET /ws/cabin-audio?token=<redacted>&view=1 HTTP/1.1",
                HttpServer.redactQueryToken(
                        "GET /ws/cabin-audio?token=secret.jwt&view=1 HTTP/1.1"));
        assertEquals(
                "GET /status HTTP/1.1",
                HttpServer.redactQueryToken("GET /status HTTP/1.1"));
    }
}
