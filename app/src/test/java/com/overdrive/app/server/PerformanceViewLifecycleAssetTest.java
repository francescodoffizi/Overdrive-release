package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level ownership guards for the request-scoped process monitor. */
public class PerformanceViewLifecycleAssetTest {

    @Test
    public void processMonitorRunsOnlyWhileSystemSubviewIsVisible() throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/performance.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/performance.js");

        assertTrue(page.contains(
                "<section class=\"process-monitor\" data-tab=\"system\" id=\"topProcessPanel\">"));
        assertTrue(script.contains(
                "return this._pageIsVisible() && this._activeTab === 'system';"));
        assertTrue(script.contains(
                "document.addEventListener('ot-tabs:active-changed'"));
        assertTrue(script.contains(
                "document.addEventListener('visibilitychange'"));
        assertTrue(script.contains("window.addEventListener('pagehide'"));
        assertTrue(script.contains(
                "if (this._shouldRunTop()) this.startTopPolling();"));
        assertTrue(script.contains("else this.stopTopPolling();"));

        String stopTop = between(
                script,
                "stopTopPolling() {",
                "refreshTopNow() {");
        assertTrue(stopTop.contains("clearInterval(this.topPollInterval)"));
        assertTrue(stopTop.contains("this._topRequestGeneration++;"));
        assertTrue(stopTop.contains("this._setTopLiveState(false);"));
    }

    @Test
    public void backendTopCaptureIsBoundedAndRequestScoped() throws IOException {
        String handler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/PerformanceApiHandler.java");
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/TopSnapshotCollector.java");

        assertTrue(handler.contains("/api/performance/top"));
        assertTrue(handler.contains("TopSnapshotCollector.capture(limit)"));
        assertTrue(collector.contains("new ProcessBuilder(\"top\", \"-b\", \"-n\", \"1\""));
        assertTrue(collector.contains("COMMAND_TIMEOUT_MS"));
        assertTrue(collector.contains("process.destroyForcibly()"));
        assertTrue(collector.contains("drain.setDaemon(true)"));
        assertFalse(collector.contains("ScheduledExecutor"));
        assertFalse(collector.contains("setInterval"));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
