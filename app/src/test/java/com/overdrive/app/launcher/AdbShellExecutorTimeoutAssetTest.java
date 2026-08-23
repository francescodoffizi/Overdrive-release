package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Prevents loopback ADB from regressing to Dadb's infinite socket defaults. */
public class AdbShellExecutorTimeoutAssetTest {

    @Test
    public void dadbConnectionsUseFiniteConnectAndSocketTimeouts() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/AdbShellExecutor.kt")
                .replace("\r\n", "\n");

        assertTrue(source.contains("private const val ADB_CONNECT_TIMEOUT_MS = 3_000"));
        assertTrue(source.contains("private const val ADB_SOCKET_TIMEOUT_MS = 45_000"));
        assertTrue(source.contains("ADB_CONNECT_TIMEOUT_MS,\n                    ADB_SOCKET_TIMEOUT_MS"));
        assertFalse(source.contains(
                "Dadb.create(\"127.0.0.1\", ADB_PORT, keyPair)"));
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
