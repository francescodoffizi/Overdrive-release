package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the cross-process and cross-surface screenshot privacy wiring. */
public class ScreenshotPrivacyModeAssetTest {

    @Test
    public void settingPersistsInUnifiedNativeShellConfig() throws IOException {
        String config = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/settings/SettingsPrivacyFragment.kt");
        String layout = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings_privacy.xml");

        assertTrue(config.contains("setScreenshotPrivacyModeEnabled"));
        assertTrue(config.contains("\"screenshotPrivacyMode\""));
        assertTrue(fragment.contains("applyScreenshotPrivacyMode(enabled)"));
        assertTrue(layout.contains("@+id/switchScreenshotPrivacy"));
    }

    @Test
    public void daemonStatusDrivesOneSharedWebMasker() throws IOException {
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String core = readRepositoryFile("app/src/main/assets/web/shared/core.js");

        assertTrue(server.contains("\"screenshotPrivacyMode\""));
        assertTrue(server.contains("isScreenshotPrivacyModeEnabled()"));
        assertTrue(core.contains("BYD.screenshotPrivacy = (function ()"));
        assertTrue(core.contains("setEnabled(!!status.screenshotPrivacyMode)"));
        assertTrue(core.contains("new MutationObserver(queueScan)"));
        assertTrue(core.contains("data-screenshot-private"));
    }

    @Test
    public void roundedChargingCoordinatesAreStillSensitive() throws IOException {
        String core = readRepositoryFile("app/src/main/assets/web/shared/core.js");

        assertTrue(core.contains("\\.\\d{3,}\\s*[,;\\/]\\s*"));
    }

    @Test
    public void changedPrPagesAllLoadTheSharedCore() throws IOException {
        assertTrue(readRepositoryFile("app/src/main/assets/web/local/index.html")
                .contains("../shared/core.js"));
        assertTrue(readRepositoryFile("app/src/main/assets/web/local/events.html")
                .contains("../shared/core.js"));
        assertTrue(readRepositoryFile("app/src/main/assets/web/local/charging.html")
                .contains("../shared/core.js"));
    }

    @Test
    public void nativeTextureIsClippedToEachSensitiveView() throws IOException {
        String controller = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/privacy/ScreenshotPrivacyController.kt");

        int clip = controller.indexOf("canvas.clipPath(clipPath)");
        int stripe = controller.indexOf("canvas.drawLine(");
        int restore = controller.indexOf("canvas.restoreToCount(checkpoint)");
        assertTrue(clip >= 0 && stripe > clip && restore > stripe);
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
