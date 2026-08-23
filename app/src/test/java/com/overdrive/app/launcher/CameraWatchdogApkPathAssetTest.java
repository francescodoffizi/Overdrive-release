package com.overdrive.app.launcher;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Prevents the camera watchdog from retaining a stale base.apk after replacement. */
public class CameraWatchdogApkPathAssetTest {

    @Test
    public void watchdogResolvesTheInstalledPackageBeforeEveryLaunch() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt")
                .replace("\r\n", "\n");

        assertTrue(source.contains(
                "APK_PATH=\\$(pm path com.overdrive.app 2>/dev/null"));
        assertTrue(source.contains(
                "NATIVE_LIB_DIR=\\\"\\${APK_PATH%/base.apk}/lib/arm64\\\""));
        assertTrue(source.contains(
                "CLASSPATH=/system/framework/bmmcamera.jar:\\$APK_PATH app_process"));
        assertTrue(source.contains(
                "-Djava.library.path=\\$NATIVE_LIB_DIR:/system/lib64"));
        assertTrue(source.contains(
                "Installed OverDrive APK not found, retrying in 10s"));
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
