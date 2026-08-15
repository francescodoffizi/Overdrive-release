package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class CameraDaemonRestartSafetyContractTest {

    @Test
    public void watchdogRestartCheckpointsBeforeSettingRestartIntent()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int coordinator = source.indexOf(
                "public static void requestProcessRestartPreservingTrip");
        int checkpoint = source.indexOf(
                "prepareTripsForProcessRestart(reason)", coordinator);
        int intent = source.indexOf("processRestartIntent = true;", checkpoint);
        int exit = source.indexOf("System.exit(0);", intent);

        assertTrue(coordinator >= 0);
        assertTrue(checkpoint > coordinator);
        assertTrue(intent > checkpoint);
        assertTrue(exit > intent);
        assertTrue(source.contains("shouldFinalizeTripsOnShutdown()"));
        assertTrue(source.contains("shouldFinalizeTripsOnShutdown() && manager != null"));
    }

    @Test
    public void cameraFailurePathsUseOnlyTripSafeRestartCoordinator()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");

        assertFalse(source.contains("System.exit(0);"));
        assertFalse(source.contains("Runtime.getRuntime().halt(0);"));
        assertFalse(source.contains(
                "android.os.Process.killProcess(android.os.Process.myPid())"));
        assertTrue(count(source,
                "CameraDaemon.requestProcessRestartPreservingTrip(") == 3);
    }

    @Test
    public void everyScopedExternalKillRequiresPrepareRestart()
            throws IOException {
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        int reconfigure = activity.indexOf("private fun performCameraReconfigure()");
        int reconfigureEnd = activity.indexOf(
                "// ==================== Battery Health", reconfigure);
        String reconfigureBody = activity.substring(reconfigure, reconfigureEnd);
        assertTrue(reconfigureBody.contains(
                "restartCameraDaemonForCameraSettings()"));
        assertFalse(reconfigureBody.contains("killDaemon("));

        String appManager = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/management/DaemonManager.kt");
        int appStop = appManager.indexOf("private fun stopCameraDaemon");
        int appPrepare = appManager.indexOf("prepareCameraRestart()", appStop);
        int appKill = appManager.indexOf("adbLauncher.killDaemon(", appPrepare);
        assertTrue(appPrepare > appStop);
        assertTrue(appKill > appPrepare);
        assertTrue(appManager.contains(
                "\"/api/surveillance/prepare-restart\", \"POST\""));

        String telegram = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/telegram/impl/DaemonManager.java");
        int cameraBranch = telegram.indexOf(
                "if (\"camera\".equals(name.toLowerCase()))");
        int telegramPrepare = telegram.indexOf(
                "if (!prepareCameraRestart())", cameraBranch);
        int firstKill = telegram.indexOf("kill -9", cameraBranch);
        assertTrue(telegramPrepare > cameraBranch);
        assertTrue(firstKill > telegramPrepare);
        assertTrue(telegram.contains(
                "\"/api/surveillance/prepare-restart\", \"POST\""));
    }

    @Test
    public void restartArmsTerminalGuardBeforeExitAndDisarmsOnFailedExit()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int coordinator = source.indexOf(
                "public static void requestProcessRestartPreservingTrip");
        int intent = source.indexOf("processRestartIntent = true;", coordinator);
        // The guard must be armed AFTER the checkpoint is durable (a halt
        // before it would lose the trip) but BEFORE System.exit — the shutdown
        // hook can wedge on the failed camera/GL state and nothing else would
        // ever terminate the process.
        int arm = source.indexOf("armTerminalShutdownDeadline()", intent);
        int exit = source.indexOf("System.exit(0);", arm);
        // If exit throws and the process is deliberately left running, the
        // armed deadline must be disarmed so it can't halt us 20 s later.
        int exitFailure = source.indexOf("catch (Throwable t)", exit);
        int disarm = source.indexOf("disarmTerminalShutdownDeadline();", exitFailure);
        int coordinatorEnd = source.indexOf(
                "private static boolean prepareTripsForProcessRestart", coordinator);

        assertTrue(coordinator >= 0);
        assertTrue(intent > coordinator);
        assertTrue(arm > intent);
        assertTrue(exit > arm);
        assertTrue(exitFailure > exit);
        assertTrue(disarm > exitFailure);
        assertTrue(coordinatorEnd > disarm);
    }

    @Test
    public void shutdownHookRestoresSafetyStateBeforeRoadSenseAndNeverFinalizesRestartTrips()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int hook = source.indexOf("Runtime.getRuntime().addShutdownHook");
        assertTrue(hook >= 0);
        int hookEnd = source.indexOf("releaseSingletonLock();", hook);
        assertTrue(hookEnd > hook);
        String hookBody = source.substring(hook, hookEnd);

        // Ordering: trip quiesce (restart-aware), THEN the safety-critical
        // screen-deterrent flag reset and cluster gauge restoration, THEN
        // RoadSense/RecordingModeManager (whose teardown can block), THEN GPU.
        // If RoadSense wedges and the terminal guard halts the process, the
        // deterrent flags and cluster gauges must already be restored.
        assertOrdered(
                hookBody,
                "shutdownTripAnalyticsBeforeBlockingCleanup();",
                "ScreenDeterrent.getInstance().cancel();",
                "ClusterProjectionController.shutdownIfActive();",
                "detachRoadSenseAndRecordingMode();",
                "gpuPipeline.stop();");

        // The hook must never finalize the active trip unconditionally: a
        // trip-safe restart already checkpointed it, and finalize can route a
        // short leg to discardTrip() which deletes the telemetry file. The one
        // direct manager.shutdown() left in the hook must be gated.
        assertTrue(count(hookBody, "tripAnalyticsManager.shutdown();") == 1);
        String normalized = hookBody.replaceAll("\\s+", " ");
        assertTrue(normalized.contains(
                "if (shouldFinalizeTripsOnShutdown() && tripAnalyticsManager != null) "
                        + "{ tripAnalyticsManager.shutdown();"));

        // The RoadSense/RMM helper is shared: normal shutdown and the hook use
        // the same code path (definition + exactly two call sites).
        assertTrue(count(source, "detachRoadSenseAndRecordingMode();") == 2);
        int normalShutdown = source.indexOf(
                "private static void shutdownInternal(");
        int normalHelperCall = source.indexOf(
                "detachRoadSenseAndRecordingMode();", normalShutdown);
        assertTrue(normalShutdown >= 0);
        assertTrue(normalHelperCall > normalShutdown && normalHelperCall < hook);
    }

    private static void assertOrdered(String source, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int position = source.indexOf(needle, previous + 1);
            assertTrue("Missing or out of order: " + needle, position > previous);
            previous = position;
        }
    }

    private static int count(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
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
