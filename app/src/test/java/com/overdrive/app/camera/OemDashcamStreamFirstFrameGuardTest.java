package com.overdrive.app.camera;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Guards the DVR live-view handoff against selecting view 6 before the OEM
 * SurfaceTexture has published a first frame transform.
 */
public class OemDashcamStreamFirstFrameGuardTest {

    @Test
    public void firstOemFrameActivatesDeferredViewSix() throws IOException {
        String scaler = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/GpuStreamScaler.java");

        assertTrue(scaler.contains("requestOemViewWhenReady()"));
        assertTrue(scaler.contains("private final Object oemViewLock = new Object();"));
        assertTrue(scaler.contains("if (oemViewRequested && oemTextureId != 0"));
        assertTrue(scaler.contains("this.oemTexMatrixSnapshot = matrix;"));
        assertTrue(scaler.contains("setViewMode(6);"));

        int setViewMode = scaler.indexOf("public void setViewMode(int mode)");
        int endOfMethod = scaler.indexOf("\n    /**", setViewMode);
        String method = scaler.substring(setViewMode, endOfMethod);
        int cancel = method.indexOf("oemViewRequested = false;");
        int duplicate = method.indexOf(
                "if (mode != 7 && mode != 8 && mode == this.currentViewMode) return;");
        assertTrue("a non-DVR selection must cancel a pending DVR handoff", cancel >= 0);
        assertTrue("cancellation must happen before the idempotency return", cancel < duplicate);
    }

    @Test
    public void streamRoutesWaitForTheFirstOemFrame() throws IOException {
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        String streamApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/StreamingApiHandler.java");
        String httpServer = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");

        assertTrue(pipeline.contains("activateOemStreamViewWhenReady()"));
        assertTrue(streamApi.contains(
                "boolean viewActivated = routed && pano.activateOemStreamViewWhenReady();"));
        assertTrue(streamApi.contains("if (!routed || !viewActivated)"));
        assertTrue(httpServer.contains(
                "viewActivated = rerouted && pipeline.activateOemStreamViewWhenReady();"));
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
        throw new AssertionError("Could not locate " + relativePath);
    }
}
