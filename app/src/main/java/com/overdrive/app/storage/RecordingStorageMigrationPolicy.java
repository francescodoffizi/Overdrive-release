package com.overdrive.app.storage;

/**
 * Decision logic for the internal→external mid-session recording migration.
 *
 * <p><b>The bug this closes.</b> Recordings are configured for an external
 * volume (SD/USB) but the volume mounts asynchronously — at ACC-ON the RMM
 * activation frequently wins the race, {@code resolveActive} silently falls
 * back to internal, and the {@code cam_*} session opens on internal storage.
 * From that point the session is path-latched: segment rotation derives every
 * next segment from the session's base path, and
 * {@code updateActiveDirectories()} deliberately skips the recordings-dir
 * swap while a recording is active. The volume watchdog's SUCCESS branch
 * pushes a one-shot {@code setOutputDir} override, but that override is only
 * consumed at the next {@code startRecording()} — and for a HEALTHY session
 * nothing restarts the recording ({@code resyncFromHardware} short-circuits
 * on a healthy pipeline), so the whole drive lands on internal. The unmount
 * direction (external→internal) and the user-initiated storage-type switch
 * both already force a stop+restart; the mount direction was the missing
 * symmetric case.
 *
 * <p><b>Contract.</b> {@link #evaluate} answers: "the configured external
 * recordings volume just came online — should we finalize the current
 * segment and roll the session over to it?" It is pure (no Android, no I/O)
 * so the gate order and path comparison are pinned by plain JUnit tests. The
 * caller (StorageManager's post-mount hook) maps live state into the
 * parameters and acts only on {@link Decision#MIGRATE}:
 * {@code setOutputDir(external)} → {@code pipeline.stopRecording()} →
 * forced RMM reactivation.
 *
 * <p><b>Why each gate exists:</b>
 * <ul>
 *   <li>{@code configuredTargetCameOnline} — the volume that came online must
 *       be the CONFIGURED recordings target. A USB insert while recordings
 *       are configured for SD (or internal) must not touch the session.</li>
 *   <li>{@code pipelineInNormalRecordingMode} — the recorder is SHARED with
 *       surveillance event clips. While the pipeline is in SURVEILLANCE mode
 *       an SD remount must never finalize a parked sentry event mid-write
 *       (surveillance has its own live {@code setEventOutputDir} push).</li>
 *   <li>{@code sessionOwnedByRecordingMode} — only CONTINUOUS / DRIVE_MODE
 *       sessions are restartable by RMM. A manual {@code /api/start} session
 *       (RMM mode NONE) or a proximity event clip would be STOPPED but never
 *       RESTARTED by the forced reactivation — stopping them here would be
 *       data loss, and proximity clips re-resolve their directory per event
 *       anyway.</li>
 *   <li>{@code recorderRecording} — nothing to migrate when idle; the next
 *       start resolves the live directory (or consumes the watchdog
 *       override) by itself.</li>
 *   <li>{@code activeOutputPath} under {@code internalRecordingsRoot} — the
 *       action fires only while the actual open file sits on the internal
 *       fallback. This is what makes the whole handler idempotent without
 *       extra hysteresis: after a successful migration the output is
 *       external, so a repeat edge (mount flap, duplicate discovery pass)
 *       evaluates to {@link Decision#SKIP_OUTPUT_NOT_ON_INTERNAL}. Note:
 *       compare against the encoder's ACTUAL output path, not
 *       {@code getActiveRecordingsStorageType()} — the active type flips to
 *       SD_CARD as soon as the volume is available even while the open file
 *       remains internal.</li>
 *   <li>{@code externalTargetRoot} resolved + writable — never redirect a
 *       live session onto a volume that did not actually resolve (or whose
 *       bounded write probe failed); losing the internal session for a dead
 *       target would be strictly worse than the bug. Writability is a LAZY
 *       supplier because it is an actual bounded touch-probe against the
 *       recordings directory (not a cheap flag read): it must run before
 *       anything is stopped, but only when every other gate has already
 *       passed — an idle or surveillance-owned tick must never pay (or
 *       block on) a disk probe.</li>
 * </ul>
 *
 * <p><b>Transient vs terminal skips.</b> The online edge is one-shot: nothing
 * re-fires it if the world becomes migratable a few seconds later. Directory
 * initialization for a freshly-mounted card commits AFTER the edge on the
 * discovery-driven paths and can take several seconds on a FUSE-bridged
 * volume — a single-shot evaluation would consume the edge, skip on the
 * not-yet-committed target, and strand the session on internal for the whole
 * drive. {@link #isRetryableSkip} therefore classifies which skips a
 * bounded retry loop may re-evaluate (state that can legitimately settle
 * within the edge's retry window) and which are terminal for the edge
 * (config says no, the session is not ours to restart, or the output is
 * already placed correctly — for those, later starts resolve the live
 * directory themselves).
 */
public final class RecordingStorageMigrationPolicy {

    /** Why {@link #evaluate} did or did not ask for a migration. */
    public enum Decision {
        /** All gates passed — finalize the internal segment and roll over. */
        MIGRATE,
        /** The volume that came online is not the configured recordings target. */
        SKIP_ONLINE_VOLUME_NOT_CONFIGURED_TARGET,
        /** Pipeline not in NORMAL_RECORDING (idle, or surveillance owns the recorder). */
        SKIP_NOT_NORMAL_RECORDING_MODE,
        /** Session not owned by RMM CONTINUOUS/DRIVE_MODE (manual or proximity session). */
        SKIP_SESSION_NOT_RMM_OWNED,
        /** Wrapper reports no active recording — nothing to migrate. */
        SKIP_RECORDER_NOT_RECORDING,
        /** Encoder has no current output path (start still latching / just closed). */
        SKIP_NO_ACTIVE_OUTPUT_PATH,
        /** Output already on the configured volume (or at least not on internal). */
        SKIP_OUTPUT_NOT_ON_INTERNAL,
        /** External target dir missing, resolved back to internal, or write probe failed. */
        SKIP_EXTERNAL_TARGET_UNAVAILABLE
    }

    private RecordingStorageMigrationPolicy() {
        // static policy holder
    }

    /**
     * Evaluate the migration gates in order. See the class doc for the
     * rationale behind each gate; the ORDER is part of the contract (the
     * cheapest / most common skips first, and the external-target write
     * probe LAST — it is the only gate that touches disk, and it must only
     * ever run when the migration would otherwise proceed).
     *
     * @param externalTargetWritable lazy bounded write probe against the
     *        resolved external recordings directory; invoked at most once,
     *        and only after every other gate has passed. A throwing supplier
     *        is treated as not-writable (fail closed).
     */
    public static Decision evaluate(
            boolean configuredTargetCameOnline,
            boolean pipelineInNormalRecordingMode,
            boolean sessionOwnedByRecordingMode,
            boolean recorderRecording,
            String activeOutputPath,
            String internalRecordingsRoot,
            String externalTargetRoot,
            java.util.function.BooleanSupplier externalTargetWritable) {
        if (!configuredTargetCameOnline) {
            return Decision.SKIP_ONLINE_VOLUME_NOT_CONFIGURED_TARGET;
        }
        if (!pipelineInNormalRecordingMode) {
            return Decision.SKIP_NOT_NORMAL_RECORDING_MODE;
        }
        if (!sessionOwnedByRecordingMode) {
            return Decision.SKIP_SESSION_NOT_RMM_OWNED;
        }
        if (!recorderRecording) {
            return Decision.SKIP_RECORDER_NOT_RECORDING;
        }
        if (activeOutputPath == null || activeOutputPath.isEmpty()) {
            return Decision.SKIP_NO_ACTIVE_OUTPUT_PATH;
        }
        if (!isUnderRoot(activeOutputPath, internalRecordingsRoot)) {
            return Decision.SKIP_OUTPUT_NOT_ON_INTERNAL;
        }
        if (externalTargetRoot == null || externalTargetRoot.isEmpty()) {
            return Decision.SKIP_EXTERNAL_TARGET_UNAVAILABLE;
        }
        boolean writable;
        try {
            writable = externalTargetWritable != null
                    && externalTargetWritable.getAsBoolean();
        } catch (Throwable t) {
            // A probe that throws proves nothing about the target — fail
            // closed rather than migrate onto it.
            writable = false;
        }
        if (!writable) {
            return Decision.SKIP_EXTERNAL_TARGET_UNAVAILABLE;
        }
        return Decision.MIGRATE;
    }

    /**
     * True for skips that a bounded retry loop may legitimately re-evaluate
     * within the same online edge's retry window:
     * <ul>
     *   <li>{@link Decision#SKIP_EXTERNAL_TARGET_UNAVAILABLE} — directory
     *       initialization for the freshly-mounted card commits AFTER the
     *       discovery-driven edge and can take several seconds (FUSE mkdirs);
     *       the resolve/probe genuinely flips to ready shortly after.</li>
     *   <li>{@link Decision#SKIP_NOT_NORMAL_RECORDING_MODE} and
     *       {@link Decision#SKIP_RECORDER_NOT_RECORDING} — the edge can land
     *       mid-ACC-transition, BEFORE the normal session starts; if that
     *       start then wins the race against directory init it opens on
     *       internal with the edge already consumed. Watching these through
     *       the window catches it; if the session instead starts after init
     *       committed, it resolves external by itself and the loop exits on
     *       {@link Decision#SKIP_OUTPUT_NOT_ON_INTERNAL}.</li>
     *   <li>{@link Decision#SKIP_NO_ACTIVE_OUTPUT_PATH} — sub-second start
     *       latch window.</li>
     * </ul>
     * Everything else is terminal for the edge: the configured target did not
     * come online, the session is not RMM-restartable (stopping it would be
     * data loss with no restart), or the output is already placed correctly.
     */
    public static boolean isRetryableSkip(Decision decision) {
        switch (decision) {
            case SKIP_EXTERNAL_TARGET_UNAVAILABLE:
            case SKIP_NOT_NORMAL_RECORDING_MODE:
            case SKIP_RECORDER_NOT_RECORDING:
            case SKIP_NO_ACTIVE_OUTPUT_PATH:
                return true;
            default:
                return false;
        }
    }

    /**
     * True iff {@code path} is {@code root} itself or a descendant of it.
     * Segment-boundary safe: {@code /a/recordings2/x} is NOT under
     * {@code /a/recordings}. A null/empty root never matches (an
     * uninitialized internal root must fail closed — no migration).
     */
    static boolean isUnderRoot(String path, String root) {
        if (path == null || path.isEmpty() || root == null || root.isEmpty()) {
            return false;
        }
        String normalizedRoot = root;
        while (normalizedRoot.length() > 1 && normalizedRoot.endsWith("/")) {
            normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        }
        return path.equals(normalizedRoot) || path.startsWith(normalizedRoot + "/");
    }
}
