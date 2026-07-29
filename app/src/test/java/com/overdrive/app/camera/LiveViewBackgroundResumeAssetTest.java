package com.overdrive.app.camera;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Guards the WebView lifecycle contract for Live camera streaming.
 */
public class LiveViewBackgroundResumeAssetTest {

    @Test
    public void visibilityLifecyclePreservesAndRestoresSelectedCamera() throws IOException {
        String stream = readRepositoryFile("app/src/main/assets/web/shared/stream.js");

        assertTrue(stream.contains("pauseForBackground()"));
        assertTrue(stream.contains("this.stopStream({ preserveBackgroundResume: true })"));
        assertTrue(stream.contains("this._backgroundResumeMode = mode"));
        assertTrue(stream.contains("resumeAfterBackground()"));
        assertTrue(stream.contains("await this.selectCamera(mode)"));
        assertTrue(stream.contains("if (document.hidden)"));
        assertTrue(stream.contains("this.pauseForBackground()"));
        assertTrue(stream.contains("this.resumeAfterBackground()"));
    }

    @Test
    public void manualStopClearsPendingLifecycleResume() throws IOException {
        String stream = readRepositoryFile("app/src/main/assets/web/shared/stream.js");

        assertTrue(stream.contains(
            "const preserveBackgroundResume = options && options.preserveBackgroundResume === true"));
        assertTrue(stream.contains("if (!preserveBackgroundResume)"));
        assertTrue(stream.contains("this._backgroundResumeMode = null"));
        assertTrue(stream.contains("clearTimeout(this._backgroundResumeTimer)"));
    }

    @Test
    public void androidResumeSignalsStreamFallback() throws IOException {
        String fragment = readRepositoryFile(
            "app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt");

        assertTrue(fragment.contains("BYD.stream.resumeAfterBackground(true)"));
        assertTrue(fragment.contains("webView?.onResume()"));
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
