package com.overdrive.app.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression contract for the ACC-OFF arm/disarm notification storm
 * (field log 2026-08-21): a slow/absent SD card blocked the ACC-OFF SD
 * force-mount for up to 30s inside the 20s ACC effect lease; the lease
 * revocation interrupted the mount, marked the transition for retry, and
 * every retry pass re-ran the full OFF lifecycle — RecordingModeManager
 * stopped the armed surveillance pipeline ("disarmed" automation), then
 * the pass re-armed it ("armed" automation), alternating every ~7s.
 *
 * The fix contract, in three parts:
 *  1. The ACC-OFF SD mount is ASYNC + SINGLE-FLIGHT (mirror of the ACC-ON
 *     startAccOnRemountAsync fix) and mount failure never marks the ACC
 *     transition for retry — the VolumeWatchdog owns mount recovery.
 *  2. The RecordingModeManager ACC-OFF dispatch is a once-per-generation
 *     durable effect: commit after success, release (stay retryable) only
 *     on throw — a retry pass of the same OFF generation can no longer
 *     stop an already-armed pipeline.
 *  3. The surveillance-enable storage check is BOUNDED so it cannot blow
 *     the 15s surveillance-enable lease on a wedged mount.
 */
public class CameraDaemonAccOffMountLifecycleContractTest {

    @Test
    public void accOffMountIsAsyncSingleFlightAndNeverMarksAccRetry()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        // The bounded-join-on-the-reconciler-thread idiom must stay gone: it
        // is what put a 30s wait inside the 20s ACC effect lease.
        assertFalse(source.contains("ensureSdCardMountedBounded"));
        assertFalse(source.contains("ACC_OFF_SD_MOUNT_TIMEOUT_MS"));

        // Async worker exists, is single-flight, and marks the storage phase
        // so the probe-edge machinery treats a slow mount as slow storage.
        int method = source.indexOf(
                "private static void startAccOffSdMountAsync(");
        assertTrue(method >= 0);
        int methodEnd = source.indexOf(
                "private static void finishAccTransitionLease(", method);
        assertTrue(methodEnd > method);
        String methodBody = source.substring(method, methodEnd);
        assertTrue(methodBody.contains(
                "accOffSdMountInFlight.compareAndSet(false, true)"));
        assertTrue(methodBody.contains("enterAccStoragePhase();"));
        assertTrue(methodBody.contains("exitAccStoragePhase();"));
        assertTrue(methodBody.contains("worker.setDaemon(true);"));
        // The regression: mount failure marked the ACC transition for retry,
        // re-running the whole OFF lifecycle. The async worker must never do
        // that — the VolumeWatchdog owns mount recovery.
        assertFalse(methodBody.contains("markCurrentAccApplyRetry"));
        assertTrue(source.contains(
                "private static final java.util.concurrent.atomic.AtomicBoolean"
                        + " accOffSdMountInFlight"));

        // The ACC-OFF dispatch site fires the async mount and re-arms the
        // watchdog; no blocking mount and no retry mark remain between them.
        int callSite = source.indexOf("startAccOffSdMountAsync(storage);");
        assertTrue(callSite >= 0);
        int watchdog = source.indexOf(
                "storage.startSdCardWatchdog();", callSite);
        assertTrue(watchdog > callSite);
        String between = source.substring(callSite, watchdog);
        assertFalse(between.contains("markCurrentAccApplyRetry"));
        assertFalse(between.contains("worker.join"));
    }

    @Test
    public void rmmAccOffDispatchIsOncePerGenerationCommitOnSuccessRetryOnThrow()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");

        // Dedicated ledger exists alongside the other durable ACC effects.
        assertTrue(source.contains(
                "private static final AccEffectLedger rmmAccOffDispatchGenerations"));

        // Claim → dispatch → commit; catch → release → retry. Ordering is the
        // contract: commit only after onAccStateChanged(false) returned, and
        // the throw path stays retryable.
        int claim = source.indexOf(
                "AccEffectClaim rmmAccOffClaim = claimAccEffectOnce(");
        assertTrue(claim >= 0);
        int ledger = source.indexOf(
                "rmmAccOffDispatchGenerations, transitionGeneration);", claim);
        int dispatch = source.indexOf(
                "recordingModeManager.onAccStateChanged(false);", ledger);
        int commit = source.indexOf(
                "commitAccEffect(rmmAccOffClaim);", dispatch);
        int caught = source.indexOf("catch (Throwable t)", commit);
        int release = source.indexOf(
                "releaseAccEffect(rmmAccOffClaim);", caught);
        int retry = source.indexOf("markCurrentAccApplyRetry();", release);
        assertTrue(ledger > claim);
        assertTrue(dispatch > ledger);
        assertTrue(commit > dispatch);
        assertTrue(caught > commit);
        assertTrue(release > caught);
        assertTrue(retry > release);

        // A throw leaves the pipeline mid-stop: the pass must ABORT, not
        // continue into arming over an indeterminate pipeline (the retry
        // flag re-drives the chain from the top). The return must directly
        // follow the retry mark inside the catch block.
        int catchEnd = source.indexOf("}", retry);
        String catchTail = source.substring(retry, catchEnd);
        assertTrue(catchTail.contains("return;"));

        // claimAccEffectOnce returns null for BOTH "committed" and "still
        // running on a revoked apply thread". Committed may continue into
        // arming (the stop fully landed); BUSY must NOT — the owner's
        // in-flight pipeline stop would race the arm (stop-after-arm). The
        // caller distinguishes via isAccEffectCommitted and returns on busy.
        int busyCheck = source.indexOf(
                "} else if (!isAccEffectCommitted(", claim);
        assertTrue(busyCheck > claim);
        int busyLedger = source.indexOf(
                "rmmAccOffDispatchGenerations, transitionGeneration))", busyCheck);
        assertTrue(busyLedger > busyCheck);
        int busyReturn = source.indexOf("return;", busyLedger);
        int busyBranchEnd = source.indexOf("} else {", busyLedger);
        assertTrue(busyReturn > busyLedger);
        assertTrue("busy branch must return before the committed branch",
                busyReturn < busyBranchEnd);

        // Exactly one RMM ACC-OFF dispatch site — a second unguarded call
        // would reintroduce the duplicate pipeline stop.
        assertTrue(count(source,
                "recordingModeManager.onAccStateChanged(false);") == 1);
    }

    @Test
    public void surveillanceEnableStorageCheckIsBounded() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "GpuSurveillancePipeline.java");

        int enable = source.indexOf("public void enableSurveillance()");
        assertTrue(enable >= 0);
        // Next method boundary bounds the search window.
        int enableEnd = source.indexOf("public void disableSurveillance(", enable);
        if (enableEnd < 0) {
            enableEnd = source.indexOf("\n    public ", enable + 1);
        }
        assertTrue(enableEnd > enable);
        String enableBody = source.substring(enable, enableEnd);

        // The bounded probe replaced the raw call: ensureStorageReady(true)
        // can block for minutes on a FUSE-bridged SD, blowing the 15s
        // surveillance-enable lease and re-running the OFF lifecycle.
        assertTrue(enableBody.contains("ensureStorageReadyBounded(true)"));
        assertFalse(enableBody.contains("storage.ensureStorageReady(true)"));

        // The bounded helper's budget must sit under the surveillance-enable
        // lease (15s in CameraDaemon), or the bound is no bound at all.
        int budget = source.indexOf("ENSURE_STORAGE_READY_TIMEOUT_MS = ");
        assertTrue(budget >= 0);
        int end = source.indexOf(";", budget);
        String value = source
                .substring(budget + "ENSURE_STORAGE_READY_TIMEOUT_MS = ".length(), end)
                .replace("_", "").replace("L", "").trim();
        assertTrue(Long.parseLong(value) < 15_000L);
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
