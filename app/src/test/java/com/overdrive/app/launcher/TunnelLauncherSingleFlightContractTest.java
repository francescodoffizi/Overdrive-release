package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the process-wide cloudflared launch against duplicate callers. */
public class TunnelLauncherSingleFlightContractTest {

    @Test
    public void duplicateLaunchesShareOneTerminalResult() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/TunnelLauncher.kt");

        assertTrue(source.contains("private var activeLaunch: LaunchFlight? = null"));
        assertTrue(source.contains("current.callbacks.add(callback)"));
        assertTrue(source.contains(
                "notifyCallbacks(finishLaunch(flight)) { it.onTunnelUrl(url) }"));
        assertTrue(source.contains(
                "notifyCallbacks(finishLaunch(flight)) { it.onError(error) }"));
        assertTrue(source.contains("if (activeLaunch !== flight)"));
        assertFalse(source.contains("AtomicBoolean"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
