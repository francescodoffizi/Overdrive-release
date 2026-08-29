package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Stats-query dedup semantics (audit finding: mirrored internal/SD clips were
 * double-counted). Runs {@link RecordingsIndex#STATS_SQL} against the REAL
 * schema ({@link RecordingsIndexSchema#ensure}) on in-memory H2 — the same
 * pattern as {@link RecordingsIndexSchemaTest}.
 */
public class RecordingsIndexStatsDedupTest {

    private static final long TODAY_START = 1_000_000L;

    @Test
    public void mirroredClipCountsOnce() throws Exception {
        try (Connection c = open("mirrored")) {
            RecordingsIndexSchema.ensure(c);
            // Same filename on internal (rank 0) and SD (rank 1), 100 MB each.
            insert(c, "/storage/emulated/0/Overdrive/recordings/cam_a.mp4",
                    "cam_a.mp4", 0, true, "normal", 100L, TODAY_START + 1);
            insert(c, "/storage/ABCD-1234/Overdrive/recordings/cam_a.mp4",
                    "cam_a.mp4", 1, true, "normal", 100L, TODAY_START + 1);

            Map<String, long[]> stats = runStats(c);
            assertEquals(1L, stats.get("normal")[0]);   // count: 1, not 2
            assertEquals(100L, stats.get("normal")[1]); // bytes: one copy
            assertEquals(1L, stats.get("normal")[2]);   // today: 1
        }
    }

    @Test
    public void distinctFilenamesAllCount() throws Exception {
        try (Connection c = open("distinct")) {
            RecordingsIndexSchema.ensure(c);
            insert(c, "/storage/emulated/0/Overdrive/recordings/cam_a.mp4",
                    "cam_a.mp4", 0, true, "normal", 100L, TODAY_START - 5);
            insert(c, "/storage/emulated/0/Overdrive/recordings/cam_b.mp4",
                    "cam_b.mp4", 0, true, "normal", 50L, TODAY_START + 5);
            insert(c, "/storage/emulated/0/Overdrive/surveillance/event_a.mp4",
                    "event_a.mp4", 0, true, "sentry", 25L, TODAY_START + 5);

            Map<String, long[]> stats = runStats(c);
            assertEquals(2L, stats.get("normal")[0]);
            assertEquals(150L, stats.get("normal")[1]);
            assertEquals(1L, stats.get("normal")[2]);   // only cam_b is from today
            assertEquals(1L, stats.get("sentry")[0]);
            assertEquals(25L, stats.get("sentry")[1]);
        }
    }

    @Test
    public void unavailableMirrorDoesNotShadowAvailableCopy() throws Exception {
        try (Connection c = open("unavailable")) {
            RecordingsIndexSchema.ensure(c);
            // Internal copy (rank 0) offline — e.g. row for an unmounted
            // volume — SD copy (rank 1) available. The dedup must pick the
            // AVAILABLE copy, not suppress it behind the offline lower rank.
            insert(c, "/storage/emulated/0/Overdrive/recordings/cam_a.mp4",
                    "cam_a.mp4", 0, false, "normal", 100L, TODAY_START + 1);
            insert(c, "/storage/ABCD-1234/Overdrive/recordings/cam_a.mp4",
                    "cam_a.mp4", 1, true, "normal", 100L, TODAY_START + 1);

            Map<String, long[]> stats = runStats(c);
            assertEquals(1L, stats.get("normal")[0]);
            assertEquals(100L, stats.get("normal")[1]);
        }
    }

    @Test
    public void mirroredCopiesWithDifferentSizesCountLowestRank() throws Exception {
        try (Connection c = open("sizes")) {
            RecordingsIndexSchema.ensure(c);
            // Divergent sizes across copies (partial mirror): the internal
            // (rank 0) representative wins, matching resolveByFilename.
            insert(c, "/storage/emulated/0/Overdrive/recordings/cam_a.mp4",
                    "cam_a.mp4", 0, true, "normal", 80L, TODAY_START + 1);
            insert(c, "/storage/ABCD-1234/Overdrive/recordings/cam_a.mp4",
                    "cam_a.mp4", 1, true, "normal", 100L, TODAY_START + 1);

            Map<String, long[]> stats = runStats(c);
            assertEquals(1L, stats.get("normal")[0]);
            assertEquals(80L, stats.get("normal")[1]);
        }
    }

    private static Map<String, long[]> runStats(Connection c) throws Exception {
        Map<String, long[]> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(RecordingsIndex.STATS_SQL)) {
            ps.setLong(1, TODAY_START);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1),
                            new long[]{rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                }
            }
        }
        return out;
    }

    private static void insert(Connection c, String absPath, String filename, int rank,
                               boolean available, String type, long sizeBytes, long tsMs)
            throws Exception {
        RecordingIdentity identity = RecordingIdentity.fromPath(absPath);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO recordings (recording_id, filename, abs_path, root_id,"
                + " volume_id, relative_path, root_rank, is_available, type, ts_ms,"
                + " size_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, identity.recordingId);
            ps.setString(2, filename);
            ps.setString(3, absPath);
            ps.setString(4, identity.rootId);
            ps.setString(5, identity.volumeId);
            ps.setString(6, identity.relativePath);
            ps.setInt(7, rank);
            ps.setBoolean(8, available);
            ps.setString(9, type);
            ps.setLong(10, tsMs);
            ps.setLong(11, sizeBytes);
            ps.executeUpdate();
        }
        assertTrue(identity.recordingId != null && !identity.recordingId.isEmpty());
    }

    private static Connection open(String name) throws Exception {
        Class.forName("org.h2.Driver");
        return DriverManager.getConnection(
                "jdbc:h2:mem:statsdedup_" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
}
