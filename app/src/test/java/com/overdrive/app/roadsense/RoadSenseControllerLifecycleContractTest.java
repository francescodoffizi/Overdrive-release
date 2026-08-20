package com.overdrive.app.roadsense;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level lifecycle contract for RoadSenseController shutdown safety:
 * the IMU sidecar (an app-process foreground service) must be stopped early,
 * unconditionally on detach, and never restarted by an in-flight tick that
 * raced stop().
 */
public class RoadSenseControllerLifecycleContractTest {

    private static final String CONTROLLER =
            "app/src/main/java/com/overdrive/app/roadsense/RoadSenseController.kt";

    @Test
    public void stopIssuesSidecarStopBeforeAnythingThatCanBlock()
            throws IOException {
        String source = readRepositoryFile(CONTROLLER);
        String stop = between(source, "fun stop() {", "fun clearCoverage()");

        // The sidecar stop is a fire-and-forget `am stopservice`; issuing it
        // immediately after started=false halts the ~10 Hz inbound batch
        // stream during teardown and guarantees the app-process service is
        // torn down even if the (potentially blocking) classification drain
        // or H2/calibration flushing below wedges and the process is halted.
        assertOrdered(
                stop,
                "if (!started) return",
                "started = false",
                "RoadSenseImuSidecarService.stop()",
                "drainPendingClassifications(",
                "imuStream.stop()",
                "groundTruth.stop()",
                "store.stop()");
        assertEquals(1, count(stop, "RoadSenseImuSidecarService.stop()"));
    }

    @Test
    public void detachStopsSidecarUnconditionallyToReapOrphans()
            throws IOException {
        String source = readRepositoryFile(CONTROLLER);
        String detach = between(source, "fun detach() {", "private fun reconcile()");

        // stop() early-returns when !started, so a sidecar orphaned by a
        // forced daemon halt (Runtime.halt / kill) would never be stopped by
        // a fresh controller's detach. The unconditional stop after stop()
        // reaps that orphan; it is idempotent so a redundant call is a no-op.
        assertOrdered(
                detach,
                "removeListener",
                "enabledListener = null",
                "attached = false",
                "stop()",
                "RoadSenseImuSidecarService.stop()");
    }

    @Test
    public void regimeEdgeRechecksStartedUnderLifecycleLockBeforeSidecarStart()
            throws IOException {
        String source = readRepositoryFile(CONTROLLER);
        String poll = between(
                source,
                "fun onVehicleStatePoll() {",
                "private fun applyRegimeTransitionLocked(");

        // The ~2 Hz tick checks started at the top, but stop() can flip it
        // between that check and the transition. The edge must recheck started
        // under the same lock stop()'s callers hold, so an in-flight tick can
        // never issue a sidecar start AFTER stop() issued its stopservice.
        assertOrdered(
                poll,
                "if (!started) return",
                "if (newRegime == regime) return",
                "synchronized(lifecycleLock)",
                "if (!started) return",
                "applyRegimeTransitionLocked(");
        // No sidecar start may exist outside the locked helper on this path.
        assertFalse(poll.contains("RoadSenseImuSidecarService.start("));

        String transition = between(
                source,
                "private fun applyRegimeTransitionLocked(",
                "private fun restoreCalibration()");
        assertTrue(source.contains(
                "MUST be called under\n     * [lifecycleLock] with started=true"));
        assertTrue(transition.contains("action.startImu"));
        assertTrue(transition.contains(
                "RoadSenseImuSidecarService.start(RoadSenseImuSidecarService.ImuRate.FAST)"));
    }

    @Test
    public void stallRelaunchRechecksStartedUnderLifecycleLock()
            throws IOException {
        String source = readRepositoryFile(CONTROLLER);
        int stall = source.indexOf("SIDECAR STALL RECOVERY");
        assertTrue(stall >= 0);
        String recovery = source.substring(
                stall, source.indexOf("val rawPose", stall));

        // Same race as the regime edge: the warn-tick may have passed its
        // started check just before stop() flipped it; an unguarded relaunch
        // would resurrect the sidecar stop() just stopped.
        assertOrdered(
                recovery,
                "imuStream.isStalled(now)",
                "synchronized(lifecycleLock)",
                "if (!started) return",
                "lastSidecarRelaunchMs = now",
                "RoadSenseImuSidecarService.start(");
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

    private static String between(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        if (start < 0 || end <= start) {
            throw new AssertionError(
                    "Could not isolate source between "
                            + startNeedle + " and " + endNeedle);
        }
        return source.substring(start, end);
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
