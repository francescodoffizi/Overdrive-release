package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Verifies the bulk BYD-cloud wipe uses the same lifecycle cleanup as the normal clear API. */
public class PerformanceApiHandlerContractTest {

    @Test
    public void bulkCloudWipeClearsRemoteClimateAndStopsCloudTransport() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/PerformanceApiHandler.java");
        int cloudCase = source.indexOf("case \"bydCloud\"");
        assertTrue(cloudCase >= 0);
        String cloudWipe = source.substring(cloudCase, source.indexOf("break;", cloudCase));
        assertTrue(cloudWipe.contains("clearRemoteClimateSession()"));
        assertTrue(cloudWipe.contains("BydCloudConfig.clearCredentials()"));
        assertTrue(cloudWipe.contains("BydCloudDataProvider.getInstance().reset()"));
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
