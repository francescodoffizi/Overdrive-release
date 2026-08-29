package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the model-specific fallbacks used to expose vehicle hotspot credentials. */
public class HotspotCredentialResolutionContractTest {

    @Test
    public void statusUsesPropertyAndFrameworkCredentialSources() throws IOException {
        String handler = read(
                "app/src/main/java/com/overdrive/app/server/HotspotApiHandler.java");

        assertTrue(handler.contains("Class.forName(\"android.os.SystemProperties\")"));
        assertTrue(handler.contains("new ProcessBuilder(\"/system/bin/getprop\", name)"));
        assertTrue(handler.contains("getMethod(\"getWifiApConfiguration\")"));
        assertTrue(handler.contains("cleanCredential(config.preSharedKey)"));
        assertTrue(handler.contains("readFrameworkCredentials()"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
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
