package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class AppUpdaterRestartSafetyContractTest {

    @Test
    public void onlySuccessfulPrepareResponsesPermitCameraKill() {
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(0));
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(199));
        assertTrue(AppUpdater.isSuccessfulCameraPrepareStatus(200));
        assertTrue(AppUpdater.isSuccessfulCameraPrepareStatus(204));
        assertTrue(AppUpdater.isSuccessfulCameraPrepareStatus(299));
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(300));
        assertFalse(AppUpdater.isSuccessfulCameraPrepareStatus(503));
    }

    @Test
    public void coreInstallPreparesBeforeMetadataAndBothKillPaths()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String install = methodBody(
                source,
                "public void downloadAndInstall(InstallCallback callback)",
                "// ==================== COMPANION APK INSTALL");

        int prepare = install.indexOf(
                "String prepareFailure = prepareCameraDaemonForUpdate();");
        int rejection = install.indexOf(
                "if (prepareFailure != null)", prepare);
        int metadata = install.indexOf(
                "android.content.SharedPreferences.Editor ie", prepare);
        int detached = install.indexOf(
                "runDetachedInstall(", prepare);
        int synchronous = install.indexOf(
                "boolean cameraStopped = stopAllDaemons();", prepare);

        assertTrue(prepare >= 0);
        assertTrue(rejection > prepare);
        assertTrue(metadata > rejection);
        assertTrue(detached > metadata);
        assertTrue(synchronous > detached);

        String rejectionBlock = install.substring(rejection, metadata);
        assertTrue(rejectionBlock.contains("abortPreparedCameraRestart();"));
        assertFalse(rejectionBlock.contains("cleanup(APK_PATH);"));
        assertTrue(rejectionBlock.contains("return;"));
    }

    @Test
    public void failedPreparedHandoffResumesDaemonAndTripSampling()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String install = methodBody(
                source,
                "public void downloadAndInstall(InstallCallback callback)",
                "// ==================== COMPANION APK INSTALL");

        int detachedCall = install.indexOf(
                "boolean detachedStarted = runDetachedInstall(");
        int detachedFailure = install.indexOf(
                "if (!detachedStarted)", detachedCall);
        int detachedAbort = install.indexOf(
                "abortPreparedCameraRestart();", detachedFailure);
        int detachedReturn = install.indexOf("return;", detachedAbort);

        assertTrue(detachedCall >= 0);
        assertTrue(detachedFailure > detachedCall);
        assertTrue(detachedAbort > detachedFailure);
        assertTrue(detachedReturn > detachedAbort);
        assertTrue(install.contains(
                "if (cameraRestartPrepared) {\n"
                + "                    abortPreparedCameraRestart();"));
        int synchronousCall = install.indexOf(
                "boolean cameraStopped = stopAllDaemons();");
        int synchronousFailure = install.indexOf(
                "if (!cameraStopped)", synchronousCall);
        int synchronousAbort = install.indexOf(
                "abortPreparedCameraRestart();", synchronousFailure);
        int synchronousRollback = install.indexOf(
                "rollbackPreparedUpdateMetadata(", synchronousAbort);
        int synchronousReturn = install.indexOf(
                "return;", synchronousRollback);
        assertTrue(synchronousCall >= 0);
        assertTrue(synchronousFailure > synchronousCall);
        assertTrue(synchronousAbort > synchronousFailure);
        assertTrue(synchronousRollback > synchronousAbort);
        assertTrue(synchronousReturn > synchronousRollback);

        String detached = methodBody(
                source,
                "private boolean runDetachedInstall(",
                "private void cleanup(String path)");
        assertTrue(detached.contains("pb.start();"));
        assertTrue(detached.contains("return true;"));
        assertTrue(detached.contains("return false;"));
    }

    @Test
    public void allUpdaterCameraKillsRemainBehindTheSharedGate()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        int detachedStart = source.indexOf(
                "private boolean runDetachedInstall(");
        int detachedEnd = source.indexOf(
                "private void cleanup(String path)", detachedStart);
        int stopStart = source.indexOf(
                "private boolean stopAllDaemons()");
        int stopEnd = source.indexOf(
                "private static final java.util.regex.Pattern VALID_ALPHA_TAG",
                stopStart);

        assertTrue(detachedStart >= 0);
        assertTrue(detachedEnd > detachedStart);
        assertTrue(stopStart > detachedEnd);
        assertTrue(stopEnd > stopStart);

        String cameraKill = "psAwkKillLine(\"cam_daemon\")";
        int count = 0;
        int from = 0;
        int position;
        while ((position = source.indexOf(cameraKill, from)) >= 0) {
            assertTrue(inRange(position, detachedStart, detachedEnd)
                    || inRange(position, stopStart, stopEnd));
            count++;
            from = position + cameraKill.length();
        }
        assertEquals(3, count);

        int killall = source.indexOf(
                "killall -9 byd_cam_daemon 2>/dev/null");
        assertTrue(inRange(killall, detachedStart, detachedEnd));
        assertEquals(-1, source.indexOf(
                "killall -9 byd_cam_daemon 2>/dev/null",
                killall + 1));

        String install = methodBody(
                source,
                "public void downloadAndInstall(InstallCallback callback)",
                "// ==================== COMPANION APK INSTALL");
        int prepare = install.indexOf("prepareCameraDaemonForUpdate()");
        assertTrue(prepare >= 0);
        assertTrue(install.indexOf("runDetachedInstall(", prepare) > prepare);
        assertTrue(install.indexOf("stopAllDaemons();", prepare) > prepare);

        String stop = source.substring(stopStart, stopEnd);
        int finalKill = stop.lastIndexOf(cameraKill);
        int verification = stop.indexOf(
                "CAMERA_PIDS=$(ps -A -o PID,ARGS", finalKill);
        int confirmedReturn = stop.indexOf(
                "return cameraStopConfirmed[0];", verification);
        assertTrue(verification > finalKill);
        assertTrue(confirmedReturn > verification);
    }

    private static boolean inRange(int value, int start, int end) {
        return value >= start && value < end;
    }

    private static String methodBody(
            String source, String methodStart, String followingMarker) {
        int start = source.indexOf(methodStart);
        int end = source.indexOf(followingMarker, start);
        assertTrue("Missing method start: " + methodStart, start >= 0);
        assertTrue("Missing following marker: " + followingMarker, end > start);
        return source.substring(start, end);
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
