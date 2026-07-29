package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class LiveCameraSelectorLoadingAssetTest {

    @Test
    public void liveSelectorShowsImmediateFallbackUntilVehicleRenderIsReady() throws Exception {
        String liveView = readWebAsset("local/live-view.html");

        assertTrue(liveView.contains("class=\"car-image-fallback\""));
        assertTrue(liveView.contains("src=\"../shared/car-icon.webp\""));
        assertTrue(liveView.contains("onReady: function ()"));
        assertTrue(liveView.contains("classList.add('vehicle-render-ready')"));
    }

    @Test
    public void auxiliaryCanvasWaitsForSelectionBeforeCheckingSpriteCache() throws Exception {
        String appShell = readWebAsset("shared/app-shell.js");
        int mountStart = appShell.indexOf(
                "window.OverdriveAppShell.mountVehicleCanvas");
        int resolvedMount = appShell.indexOf(
                "function mountResolvedSelection()", mountStart);
        int cacheCheck = appShell.indexOf(
                "tryCache(function (hit) {", resolvedMount);
        int threeLoader = appShell.indexOf("ensureEv3d();", cacheCheck);
        int selectionGate = appShell.indexOf(
                "if (lastEv3dModel && lastEv3dColor) {", resolvedMount);

        assertTrue("resolved-selection mount must be present", resolvedMount > mountStart);
        assertTrue("cache check must run inside resolved-selection mount", cacheCheck > resolvedMount);
        assertTrue("3D loader must start only after a cache miss", threeLoader > cacheCheck);
        assertTrue("selection gate must wrap the resolved mount", selectionGate > threeLoader);

        String eagerLoaderBlock = appShell.substring(mountStart, resolvedMount);
        assertFalse("mount must not eagerly start the 3D loader",
                eagerLoaderBlock.contains("ensureEv3d();"));
    }

    @Test
    public void embeddedAppDoesNotRenderTheHiddenWebSidebarVehicle() throws Exception {
        String appShell = readWebAsset("shared/app-shell.js");

        assertTrue(appShell.contains(
                "typeof window.AndroidBridge !== 'undefined'"));
        assertTrue(appShell.contains(
                "var sidebarCanvas = embeddedInNativeApp"));
        assertTrue(appShell.contains(
                ": document.getElementById('evCardCanvas');"));
        assertTrue(appShell.contains(
                "if (embeddedInNativeApp) return;"));
        assertTrue(appShell.contains(
                "} else if (!embeddedInNativeApp) {"));
    }

    private static String readWebAsset(String relativePath) throws IOException {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve("src/main/assets/web").resolve(relativePath);
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }

        Path fromRepository = current.resolve("app/src/main/assets/web").resolve(relativePath);
        if (Files.exists(fromRepository)) {
            return new String(Files.readAllBytes(fromRepository), StandardCharsets.UTF_8);
        }

        throw new AssertionError("Could not locate web asset: " + relativePath);
    }
}
