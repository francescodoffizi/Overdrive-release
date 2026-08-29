package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class ScreenDeterrentPreviewSafetyContractTest {

    @Test
    public void previewIsGuardedAndTouchCaptureOutlivesTheVisualLayer() throws IOException {
        String deterrent = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/ScreenDeterrent.java");
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/DeterrentActivity.java");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String ipc = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        String manifest = readRepositoryFile("app/src/main/AndroidManifest.xml");

        int preview = deterrent.indexOf("public String previewNow()");
        int admissionGuard = deterrent.indexOf("if (previewBlocked())", preview);
        int acquire = deterrent.indexOf("inFlight.compareAndSet(false, true)", preview);
        int worker = deterrent.indexOf("executor.execute(", preview);
        assertTrue(preview >= 0);
        assertTrue(admissionGuard > preview);
        assertTrue(acquire > admissionGuard);
        assertTrue(worker > acquire);
        assertTrue(deterrent.contains(
                "DrivingSafetyGuard.isMovementBlocked()"));
        assertTrue(deterrent.contains("isAccStateAuthoritative()"));
        int previewGuard = deterrent.indexOf(
                "private static boolean previewBlocked()");
        int previewGuardEnd = deterrent.indexOf(
                "private boolean waitForInputCapture", previewGuard);
        assertTrue(previewGuard >= 0);
        assertTrue(previewGuardEnd > previewGuard);
        assertTrue(deterrent.substring(previewGuard, previewGuardEnd)
                .contains("if (isAccUnsafe()) return true;"));
        assertFalse(deterrent.contains(
                "DrivingSafetyGuard.GUARD_SCREEN_MEDIA"));
        assertTrue(deterrent.contains("waitForInputCapture(true)"));
        assertTrue(deterrent.contains("waitForInputCapture(false)"));
        assertTrue(deterrent.contains("prepareInputCapture()"));
        assertTrue(deterrent.contains("openInputCapture(String token)"));
        assertTrue(deterrent.contains("activeInputCaptureId"));
        assertTrue(deterrent.contains("isInputCaptureReady()"));
        assertTrue(deterrent.contains("extendDeadlineElapsedMs"));
        assertTrue(deterrent.contains(
                "renderAsset(size.x, size.y, this::shouldStopPreview"));
        assertTrue(deterrent.contains(
                "screenDeterrentPreviewActive"));

        int touch = activity.indexOf(
                "public boolean dispatchTouchEvent(MotionEvent ev)");
        int touchEnd = activity.indexOf("private boolean shouldFinishNow()", touch);
        String touchBody = activity.substring(touch, touchEnd);
        assertTrue(touchBody.contains("screenDeterrentUserDismissed"));
        assertTrue(touchBody.contains("sendInputDismiss()"));
        assertFalse(touchBody.contains("finishCleanly()"));
        assertTrue(activity.contains("restartInputCapture()"));
        assertTrue(activity.contains("\"DETERRENT_INPUT_CAPTURE\""));
        assertTrue(activity.contains("socket.setSoTimeout(0)"));
        assertTrue(activity.contains("SystemClock.elapsedRealtime()"));
        assertFalse(activity.contains("System.currentTimeMillis()"));
        assertFalse(activity.contains("INPUT_HEARTBEAT_INTERVAL_MS"));
        assertTrue(activity.contains("scheduleFinishAfterCaptureLoss("));
        assertTrue(activity.contains("finishAfterTeardownGrace()"));
        assertTrue(activity.contains("authenticatedInputCaptureSeen"));
        assertTrue(activity.contains(
                "int generation = closeInputCapture();"));
        int finishCheck = activity.indexOf("private boolean shouldFinishNow()");
        int inputToken = activity.indexOf("private static String inputToken(", finishCheck);
        assertTrue(finishCheck >= 0);
        assertTrue(inputToken > finishCheck);
        assertTrue(activity.substring(finishCheck, inputToken).contains(
                "if (authenticatedInputCaptureSeen) return false;"));
        assertTrue(activity.contains(
                "return deadline == 0;"));
        assertFalse(activity.contains(
                "return now >= deadline;"));

        assertTrue(api.contains(".getInstance().previewNow()"));
        assertTrue(api.contains("if (error == null) HttpResponse.sendJsonSuccess(out)"));
        assertTrue(ipc.contains("handleDeterrentInputCapture("));
        assertTrue(ipc.contains(".openInputCapture(request.optString(\"token\", \"\"))"));
        assertTrue(ipc.contains(".closeInputCapture(captureId)"));
        assertTrue(ipc.contains(".isInputCaptureActive(captureId)"));
        assertTrue(ipc.contains(".dismissInputCapture(captureId)"));
        assertTrue(deterrent.contains("terminalStopRequested"));
        assertTrue(deterrent.contains("terminateCurrentSession()"));
        assertTrue(deterrent.contains(
                "extendDeadlineElapsedMs.compareAndSet(pendingDeadline, 0)"));
        assertInputCaptureClosesAfterVisualTeardown(deterrent, "cancel", "reset");
        assertInputCaptureClosesAfterVisualTeardown(
                deterrent, "reset", "// ── fire()");
        assertAssetIsPreparedBeforeSurfaceIsShown(deterrent);
        assertTrue(deterrent.contains(
                "if (!drawBitmapToSurface(surface, frame))"));
        assertTrue(deterrent.contains(
                "private static boolean drawBitmapToSurface("));
        assertTrue(deterrent.contains(
                "if (!applyTransaction(surface, Integer.MAX_VALUE, true))"));
        assertTrue(deterrent.contains(
                "private static boolean applyTransaction("));
        int cleanup = deterrent.indexOf("private void cleanup()");
        int clearGate = deterrent.indexOf("private void clearSessionGate()", cleanup);
        assertTrue(cleanup >= 0);
        assertTrue(clearGate > cleanup);
        assertFalse(deterrent.substring(cleanup, clearGate).contains(
                "ctx != null && !cancelled.get()"));
        assertTrue(deterrent.substring(cleanup, clearGate).contains(
                "boolean restorePanel = restorePanelAfterSession;"));
        assertTrue(deterrent.substring(cleanup, clearGate).contains(
                "if (restorePanel && ctx != null"));
        assertTrue(deterrent.contains(
                "restorePanelAfterSession = panelIsAlreadyDark(ctx);"));

        int activityEntry = manifest.indexOf(
                "android:name=\"com.overdrive.app.DeterrentActivity\"");
        int activityEntryEnd = manifest.indexOf("/>", activityEntry);
        assertTrue(activityEntry >= 0);
        assertTrue(activityEntryEnd > activityEntry);
        assertTrue(manifest.substring(activityEntry, activityEntryEnd).contains(
                "android:permission=\"android.permission.DUMP\""));
    }

    private static void assertInputCaptureClosesAfterVisualTeardown(
            String source, String method, String nextMarker) {
        int start = source.indexOf("public void " + method + "()");
        int end = source.indexOf(nextMarker.startsWith("//")
                ? nextMarker : "public void " + nextMarker + "()", start + 1);
        assertTrue(start >= 0);
        assertTrue(end > start);
        String body = source.substring(start, end);
        int activeBranch = body.indexOf("if (inFlight.get())");
        int idleBranch = body.indexOf("} else {", activeBranch);
        int close = body.indexOf("clearInputCapture()", idleBranch);
        assertTrue(activeBranch >= 0);
        assertTrue(idleBranch > activeBranch);
        assertTrue(close > idleBranch);
        assertFalse(body.substring(activeBranch, idleBranch)
                .contains("clearInputCapture()"));
    }

    private static void assertAssetIsPreparedBeforeSurfaceIsShown(String source) {
        int start = source.indexOf("private void renderAsset(");
        int end = source.indexOf("private void renderStaticLoop(", start);
        assertTrue(start >= 0);
        assertTrue(end > start);
        String body = source.substring(start, end);
        int decodeGif = body.indexOf("decodeGifSafe(imagePath)");
        int buildStatic = body.indexOf("buildStaticFrame(imagePath");
        int createSurface = body.indexOf("createBufferLayer(\"ScreenDeterrent\"");
        assertTrue(decodeGif >= 0);
        assertTrue(buildStatic > decodeGif);
        assertTrue(createSurface > buildStatic);
        assertTrue(body.substring(buildStatic, createSurface)
                .contains("if (stop.shouldStop()) return;"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
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
