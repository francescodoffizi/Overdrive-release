package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Guards the paint-independent contrast treatment used by the Live selector.
 */
public class LiveCameraSelectorContrastAssetTest {

    @Test
    public void ringsUseLightEdgeDarkFillAndDarkHalo() throws IOException {
        String liveView = readRepositoryFile(
            "app/src/main/assets/web/local/live-view.html");

        assertTrue(liveView.contains(
            "--lv-hotspot-ring-border: rgba(255,255,255,0.96)"));
        assertTrue(liveView.contains(
            "--lv-hotspot-ring-bg: rgba(5,10,12,0.82)"));
        assertTrue(liveView.contains(
            "--lv-hotspot-ring-halo: rgba(0,0,0,0.88)"));
        assertTrue(liveView.contains(
            "0 0 0 2px var(--lv-hotspot-ring-halo)"));

        // Light theme must not replace the paint-independent ring tokens.
        String lightTheme = between(
            liveView,
            ":root[data-theme=\"light\"] .live-view-page {",
            "/* SOTA Seamless Camera View */");
        assertFalse(lightTheme.contains("--lv-hotspot-ring-border"));
        assertFalse(lightTheme.contains("--lv-hotspot-ring-bg"));
    }

    @Test
    public void labelsHaveTheirOwnHighContrastSurface() throws IOException {
        String liveView = readRepositoryFile(
            "app/src/main/assets/web/local/live-view.html");

        assertTrue(liveView.contains("--lv-hotspot-label-bg: rgba(5,9,12,0.92)"));
        assertTrue(liveView.contains("--lv-hotspot-label-text: #ffffff"));
        assertTrue(liveView.contains("background: var(--lv-hotspot-label-bg)"));
        assertTrue(liveView.contains("border: 1px solid var(--lv-hotspot-label-border)"));
        assertTrue(liveView.contains("border-radius: 999px"));
        assertTrue(liveView.contains("font-size: 11px"));
        assertTrue(liveView.contains("color: var(--lv-hotspot-active-text)"));
    }

    @Test
    public void appShellKeepsReadableSizeAndOpposingSideAnchors() throws IOException {
        String fragment = readRepositoryFile(
            "app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt");

        assertTrue(fragment.contains(
            "max-width: 74px; white-space: nowrap; overflow: hidden;"));
        assertTrue(fragment.contains(
            "text-overflow: ellipsis; padding: 4px 6px; font-size: 11px;"));
        assertTrue(fragment.contains(
            "left: auto !important; right: calc(100% + 4px) !important;"));
        assertTrue(fragment.contains(
            "right: auto !important; left: calc(100% + 4px) !important;"));
        assertFalse(fragment.contains(
            ".cam-hotspot[data-cam=\"4\"] .hotspot-label { left: -34px"));
        assertFalse(fragment.contains(
            ".cam-hotspot[data-cam=\"2\"] .hotspot-label { right: -34px"));
    }

    private static String between(String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(end, startIndex + start.length());
        assertTrue("Missing start marker", startIndex >= 0);
        assertTrue("Missing end marker", endIndex > startIndex);
        return value.substring(startIndex, endIndex);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
