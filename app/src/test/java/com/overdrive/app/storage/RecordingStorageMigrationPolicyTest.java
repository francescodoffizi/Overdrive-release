package com.overdrive.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.storage.RecordingStorageMigrationPolicy.Decision;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Regression contract for the internal→external mid-session recording
 * migration (the "ACC-ON wins the async SD mount race" bug).
 *
 * <p>Scenario being pinned: recordings configured for SD, ACC turns on while
 * the card is still mounting, the CONTINUOUS/DRIVE session opens on the
 * internal fallback and is path-latched there by segment rotation. When the
 * card comes online, the post-mount hook must migrate exactly that session —
 * and ONLY that session: never a surveillance event on the shared recorder,
 * never a manual or proximity session RMM cannot restart, and never onto a
 * target that did not actually resolve or pass the bounded write probe.
 *
 * <p>Also pinned here: the write probe is LAZY (it touches disk, so it must
 * run only when every other gate passed — and always BEFORE anything is
 * stopped), and the transient-vs-terminal skip classification that drives
 * the edge's bounded retry loop (the online edge is one-shot while directory
 * init can commit seconds later).
 */
public class RecordingStorageMigrationPolicyTest {

    private static final String INTERNAL_ROOT =
            "/storage/emulated/0/Android/data/com.overdrive.app/files/recordings";
    private static final String SD_ROOT = "/storage/ABCD-1234/OverDrive/recordings";
    private static final String INTERNAL_SEGMENT = INTERNAL_ROOT + "/cam_20260826_081502.mp4";
    private static final String SD_SEGMENT = SD_ROOT + "/cam_20260826_081502.mp4";

    private static final BooleanSupplier WRITABLE = () -> true;
    private static final BooleanSupplier NOT_WRITABLE = () -> false;
    private static final BooleanSupplier MUST_NOT_PROBE = () -> {
        throw new AssertionError("write probe must not run when an earlier gate fails");
    };

    // ── The bug scenario ────────────────────────────────────────────────────

    @Test
    public void accOnInternalFallbackSessionMigratesWhenConfiguredSdComesOnline() {
        assertEquals(Decision.MIGRATE,
                RecordingStorageMigrationPolicy.evaluate(
                        true,   // configured SD just came online
                        true,   // pipeline in NORMAL_RECORDING
                        true,   // RMM CONTINUOUS/DRIVE owns the session
                        true,   // recorder actively writing
                        INTERNAL_SEGMENT,
                        INTERNAL_ROOT,
                        SD_ROOT,
                        WRITABLE));
    }

    @Test
    public void repeatOnlineEdgeAfterMigrationIsIdempotentNoOp() {
        // After a successful migration the active output is external; a mount
        // flap or duplicate discovery pass re-fires the edge and must skip.
        assertEquals(Decision.SKIP_OUTPUT_NOT_ON_INTERNAL,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        SD_SEGMENT, INTERNAL_ROOT, SD_ROOT, MUST_NOT_PROBE));
    }

    // ── Ownership / shared-recorder guards ──────────────────────────────────

    @Test
    public void surveillanceOwnedRecorderIsNeverInterrupted() {
        // Parked sentry event mid-write on the shared recorder: pipeline is
        // NOT in NORMAL_RECORDING, so an SD remount must leave it alone.
        assertEquals(Decision.SKIP_NOT_NORMAL_RECORDING_MODE,
                RecordingStorageMigrationPolicy.evaluate(
                        true, false, false, true,
                        "/storage/emulated/0/Android/data/com.overdrive.app/files/surveillance/event_20260826_031500.mp4",
                        INTERNAL_ROOT, SD_ROOT, MUST_NOT_PROBE));
    }

    @Test
    public void manualOrProximitySessionRmmCannotRestartIsNotStopped() {
        // NORMAL_RECORDING but not an RMM CONTINUOUS/DRIVE session (manual
        // /api/start, proximity event clip): stopping it would be data loss
        // because the forced reactivation would never restart it.
        assertEquals(Decision.SKIP_SESSION_NOT_RMM_OWNED,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, false, true,
                        INTERNAL_SEGMENT, INTERNAL_ROOT, SD_ROOT, MUST_NOT_PROBE));
    }

    // ── Nothing-to-do guards ────────────────────────────────────────────────

    @Test
    public void onlineVolumeThatIsNotTheConfiguredTargetIsIgnored() {
        assertEquals(Decision.SKIP_ONLINE_VOLUME_NOT_CONFIGURED_TARGET,
                RecordingStorageMigrationPolicy.evaluate(
                        false, true, true, true,
                        INTERNAL_SEGMENT, INTERNAL_ROOT, SD_ROOT, MUST_NOT_PROBE));
    }

    @Test
    public void idleRecorderHasNothingToMigrate() {
        assertEquals(Decision.SKIP_RECORDER_NOT_RECORDING,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, false,
                        null, INTERNAL_ROOT, SD_ROOT, MUST_NOT_PROBE));
    }

    @Test
    public void missingActiveOutputPathSkips() {
        assertEquals(Decision.SKIP_NO_ACTIVE_OUTPUT_PATH,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        null, INTERNAL_ROOT, SD_ROOT, MUST_NOT_PROBE));
        assertEquals(Decision.SKIP_NO_ACTIVE_OUTPUT_PATH,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        "", INTERNAL_ROOT, SD_ROOT, MUST_NOT_PROBE));
    }

    // ── Fail-closed guards ──────────────────────────────────────────────────

    @Test
    public void unresolvedOrUnwritableExternalTargetNeverStealsTheSession() {
        // Target resolved back to internal (dir fields not committed yet) →
        // caller passes null root; the probe must not even run.
        assertEquals(Decision.SKIP_EXTERNAL_TARGET_UNAVAILABLE,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        INTERNAL_SEGMENT, INTERNAL_ROOT, null, MUST_NOT_PROBE));
        // Target resolved but the bounded write probe failed.
        assertEquals(Decision.SKIP_EXTERNAL_TARGET_UNAVAILABLE,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        INTERNAL_SEGMENT, INTERNAL_ROOT, SD_ROOT, NOT_WRITABLE));
    }

    @Test
    public void throwingOrMissingProbeFailsClosed() {
        assertEquals(Decision.SKIP_EXTERNAL_TARGET_UNAVAILABLE,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        INTERNAL_SEGMENT, INTERNAL_ROOT, SD_ROOT,
                        () -> { throw new IllegalStateException("probe blew up"); }));
        assertEquals(Decision.SKIP_EXTERNAL_TARGET_UNAVAILABLE,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        INTERNAL_SEGMENT, INTERNAL_ROOT, SD_ROOT, null));
    }

    @Test
    public void uninitializedInternalRootFailsClosed() {
        // A null/empty internal root cannot prove the session is on the
        // internal fallback — must skip, not migrate on a guess.
        assertEquals(Decision.SKIP_OUTPUT_NOT_ON_INTERNAL,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        INTERNAL_SEGMENT, null, SD_ROOT, MUST_NOT_PROBE));
        assertEquals(Decision.SKIP_OUTPUT_NOT_ON_INTERNAL,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        INTERNAL_SEGMENT, "", SD_ROOT, MUST_NOT_PROBE));
    }

    // ── Probe laziness contract ─────────────────────────────────────────────

    @Test
    public void writeProbeRunsExactlyOnceAndOnlyWhenAllOtherGatesPass() {
        AtomicInteger probes = new AtomicInteger();
        BooleanSupplier countingProbe = () -> {
            probes.incrementAndGet();
            return true;
        };
        assertEquals(Decision.MIGRATE,
                RecordingStorageMigrationPolicy.evaluate(
                        true, true, true, true,
                        INTERNAL_SEGMENT, INTERNAL_ROOT, SD_ROOT, countingProbe));
        assertEquals(1, probes.get());

        // Every earlier-gate failure path is exercised above with
        // MUST_NOT_PROBE; here just pin one representative case with the
        // counter for a readable failure message.
        probes.set(0);
        RecordingStorageMigrationPolicy.evaluate(
                true, true, true, false,
                null, INTERNAL_ROOT, SD_ROOT, countingProbe);
        assertEquals("probe must not run when the recorder is idle", 0, probes.get());
    }

    // ── Retry classification (one-shot edge vs slow directory init) ─────────

    @Test
    public void slowDirectoryInitAndMidTransitionStatesAreRetryable() {
        assertTrue(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.SKIP_EXTERNAL_TARGET_UNAVAILABLE));
        assertTrue(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.SKIP_NOT_NORMAL_RECORDING_MODE));
        assertTrue(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.SKIP_RECORDER_NOT_RECORDING));
        assertTrue(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.SKIP_NO_ACTIVE_OUTPUT_PATH));
    }

    @Test
    public void configOwnershipAndPlacementDecisionsAreTerminal() {
        assertFalse(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.SKIP_ONLINE_VOLUME_NOT_CONFIGURED_TARGET));
        assertFalse(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.SKIP_SESSION_NOT_RMM_OWNED));
        assertFalse(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.SKIP_OUTPUT_NOT_ON_INTERNAL));
        assertFalse(RecordingStorageMigrationPolicy.isRetryableSkip(
                Decision.MIGRATE));
    }

    // ── Path containment contract ───────────────────────────────────────────

    @Test
    public void isUnderRootMatchesRootItselfAndDescendants() {
        assertTrue(RecordingStorageMigrationPolicy.isUnderRoot(INTERNAL_ROOT, INTERNAL_ROOT));
        assertTrue(RecordingStorageMigrationPolicy.isUnderRoot(INTERNAL_SEGMENT, INTERNAL_ROOT));
        assertTrue(RecordingStorageMigrationPolicy.isUnderRoot(
                INTERNAL_ROOT + "/sub/cam_1.mp4.tmp", INTERNAL_ROOT));
    }

    @Test
    public void isUnderRootIsSegmentBoundarySafe() {
        // "/…/recordings2/…" must NOT read as under "/…/recordings".
        assertFalse(RecordingStorageMigrationPolicy.isUnderRoot(
                INTERNAL_ROOT + "2/cam_1.mp4", INTERNAL_ROOT));
        assertFalse(RecordingStorageMigrationPolicy.isUnderRoot(SD_SEGMENT, INTERNAL_ROOT));
    }

    @Test
    public void isUnderRootNormalizesTrailingSlashOnRoot() {
        assertTrue(RecordingStorageMigrationPolicy.isUnderRoot(
                INTERNAL_SEGMENT, INTERNAL_ROOT + "/"));
        assertFalse(RecordingStorageMigrationPolicy.isUnderRoot(
                INTERNAL_ROOT + "2/cam_1.mp4", INTERNAL_ROOT + "/"));
    }

    @Test
    public void isUnderRootFailsClosedOnNullOrEmpty() {
        assertFalse(RecordingStorageMigrationPolicy.isUnderRoot(null, INTERNAL_ROOT));
        assertFalse(RecordingStorageMigrationPolicy.isUnderRoot("", INTERNAL_ROOT));
        assertFalse(RecordingStorageMigrationPolicy.isUnderRoot(INTERNAL_SEGMENT, null));
        assertFalse(RecordingStorageMigrationPolicy.isUnderRoot(INTERNAL_SEGMENT, ""));
    }
}
