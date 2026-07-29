package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the Vehicle screen's old-WebView spacing and model-fit behaviour. */
public class VehicleControlAssetTest {

    @Test
    public void tyreHeadersReuseSharedLegacyWebViewSpacing() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertEquals(4, count(html, "vc-tyre-head compact-status-pill"));
        assertEquals(4, count(html, "vc-tyre-dot compact-status-pill__dot"));
        assertFalse(ruleFor(css, ".vc-tyre-head").contains("\n    gap:"));
        assertTrue(ruleFor(css, ".vc-tyre-head")
                .contains("--compact-status-pill-dot-gap: 8px"));
        assertTrue(ruleFor(css, ".vc-tyre-psi-unit").contains("margin-left: 7px"));
    }

    @Test
    public void tyreCardsHaveReadableDashboardSizingAndHealthyState() throws IOException {
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");

        assertTrue(ruleFor(css, ".vc-tyre-callout").contains("width: 168px"));
        assertTrue(ruleFor(css, ".vc-tyre-psi-val").contains("font-size: 28px"));
        assertTrue(css.contains("rgba(0, 212, 170, 0.28)"));
    }

    @Test
    public void modelCorrectionIsManifestDrivenRatherThanVehicleBranched() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String manifest = readRepositoryFile(
                "app/src/main/assets/web/shared/models/manifest.json");

        assertTrue(manifest.contains("\"displayScale\": [0.9, 1.0, 1.0]"));
        assertTrue(script.contains("entry.displayScale"));
        assertTrue(script.contains("_fitLoadedCarModel"));
        assertFalse(script.contains("activeModelId === 'atto3'"));
        assertFalse(script.contains("activeModelId == 'atto3'"));
    }

    @Test
    public void explicitNormalTpmsStateWinsOverGenericPressureFallback() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(script.contains("typeof corner.pressureState === 'number'"));
        assertTrue(script.contains("if (corner.pressureState >= 1) return 'warn'"));
        assertTrue(script.contains("return 'normal';"));
        assertFalse(script.contains("corner.psi < 34"));
    }

    private static int count(String text, String needle) {
        int result = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            result++;
            from += needle.length();
        }
        return result;
    }

    private static String ruleFor(String css, String selector) {
        int start = css.indexOf(selector);
        if (start < 0) return "";
        int end = css.indexOf('}', start);
        return end < 0 ? css.substring(start) : css.substring(start, end + 1);
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
