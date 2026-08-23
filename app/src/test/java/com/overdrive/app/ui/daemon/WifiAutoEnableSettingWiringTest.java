package com.overdrive.app.ui.daemon;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the user-facing switch that prevents OverDrive from restoring Wi-Fi. */
public class WifiAutoEnableSettingWiringTest {

    @Test
    public void daemonsPageExposesAndPersistsThePositiveAutoEnableSetting() throws Exception {
        String layout = readRepositoryFile("app/src/main/res/layout/fragment_daemons.xml");
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/DaemonsFragment.kt");

        assertTrue(layout.contains("@+id/rowWifiAutoEnable"));
        assertTrue(layout.contains("@+id/swWifiAutoEnable"));
        assertTrue(layout.contains("@string/daemons_wifi_auto_enable_title"));
        assertTrue(fragment.contains(
                "swWifiAutoEnable.isChecked = !UnifiedConfigManager.isWifiKeepAliveSuppressed()"));
        assertTrue(fragment.contains(
                "UnifiedConfigManager.setWifiKeepAliveSuppressed(!checked)"));
    }

    @Test
    public void everyOverdriveWifiEnablePathHonoursTheSharedSuppressionFlag() throws Exception {
        String accSentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        String sentry = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/SentryDaemon.java");
        String launcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/ServiceLauncher.kt");

        assertTrue(accSentry.contains("UnifiedConfigManager.isWifiKeepAliveSuppressed()"));
        assertTrue(sentry.contains("UnifiedConfigManager.isWifiKeepAliveSuppressed()"));
        assertTrue(launcher.contains("UnifiedConfigManager.isWifiKeepAliveSuppressed()"));
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
