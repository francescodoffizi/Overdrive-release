package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class SocHistoryDatabaseSohMigrationTest {

    @Test
    public void legacyTargetUsesRecordedRowSohBeforeCurrentFallback() {
        assertEquals(5.0,
                SocHistoryDatabase.legacyRemainingKwhTarget(
                        50.0, 12.5, 80.0, 96.0),
                0.000001);
    }

    @Test
    public void legacyTargetFallsBackOnlyWhenRowHasNoValidSoh() {
        assertEquals(6.0,
                SocHistoryDatabase.legacyRemainingKwhTarget(
                        50.0, 12.5, -999.0, 96.0),
                0.000001);
        assertEquals(6.25,
                SocHistoryDatabase.legacyRemainingKwhTarget(
                        50.0, 12.5, Double.NaN, Double.NaN),
                0.000001);
        assertTrue(Double.isNaN(
                SocHistoryDatabase.legacyRemainingKwhTarget(
                        0.0, 12.5, 80.0, 96.0)));
    }

    @Test
    public void migrationIsLegacyOnlyVersionedAndAtomic() throws IOException {
        String source = databaseSource();
        String schema = between(source,
                "private void createTables()", "One-time repair for databases");
        String migration = between(source,
                "public synchronized boolean fixStaleRemainingKwh(",
                "public com.overdrive.app.abrp.SohEstimator getSohEstimator()");
        String transactionBody = between(migration,
                "runInTransaction(() ->", "noteWriteOk();");

        assertTrue(schema.contains("remaining_kwh_format_version INTEGER DEFAULT"));
        assertTrue(schema.contains("TABLE_DATA_MIGRATIONS"));
        assertTrue(source.contains(
                "pstmt.setInt(14, REMAINING_KWH_FORMAT_VERSION)"));

        int migrate = transactionBody.indexOf(
                "migrateLegacyRemainingKwhRows(expectedSnapshot)");
        int marker = transactionBody.indexOf(
                "markRemainingKwhMigrationComplete();", migrate);
        int finalValidation = transactionBody.indexOf(
                "requireCurrentMigrationSnapshot(expectedEstimator, expectedSnapshot);",
                marker);
        int guardedCommit = transactionBody.indexOf(
                "commitRemainingKwhMigrationWithSnapshot(",
                finalValidation);
        int legacyRead = migration.indexOf(
                "remaining_kwh_format_version IS NULL");
        int rowSoh = migration.indexOf("rowSohPercent", legacyRead);
        int rowVersion = migration.indexOf(
                "SET remaining_kwh = ?, remaining_kwh_format_version = ?", rowSoh);

        assertTrue(migrate >= 0);
        assertTrue(marker > migrate);
        assertTrue(finalValidation > marker);
        assertTrue(guardedCommit > finalValidation);
        assertTrue(legacyRead >= 0);
        assertTrue(rowSoh > legacyRead);
        assertTrue(rowVersion > rowSoh);
        assertTrue(migration.contains(
                "SELECT version FROM \" + TABLE_DATA_MIGRATIONS"));
        assertTrue(migration.contains(
                "MERGE INTO \" + TABLE_DATA_MIGRATIONS"));
    }

    @Test
    public void snapshotChangeRollsBackAndRetriesBeforeMarkerCanCommit()
            throws IOException {
        String migration = between(databaseSource(),
                "public synchronized boolean fixStaleRemainingKwh(",
                "public com.overdrive.app.abrp.SohEstimator getSohEstimator()");

        assertTrue(migration.contains(
                "catch (EstimatorSnapshotChangedException changed)"));
        assertTrue(migration.contains(
                "attemptSnapshot = captureCurrentCapacitySohSnapshot()"));
        assertTrue(migration.contains(
                "Double.doubleToLongBits(expected.getNominalCapacityKwh())"));
        assertFalse(migration.contains(
                "Double.doubleToLongBits(expected.getDisplaySoh())"));
        assertTrue(migration.contains(
                "rowSohPercent,\n"
                        + "                            // Never rewrite historical rows from today's"
                        + " mutable display SOH."));
        assertTrue(migration.contains(
                "Double.NaN);"));
        assertTrue(migration.contains(
                "expected.getEstimatorGeneration()"));
        assertTrue(migration.contains(
                "current.getEstimatorGeneration()"));
        assertTrue(migration.contains(
                "expected.getResetModelEpoch()"));
        assertTrue(migration.contains(
                "current.getResetModelEpoch()"));
        assertTrue(migration.contains(
                "expectedEstimator.runWithEstimatorGenerationGuard("));
        assertTrue(migration.contains(
                "transactionConnection::commit"));
        assertTrue(migration.contains(
                "REMAINING_KWH_MIGRATION_ATTEMPTS"));
    }

    @Test
    public void sohConsumersUseOneDisplaySnapshot() throws IOException {
        String source = databaseSource();
        String record = between(source,
                "// SOH from SohEstimator (via AbrpTelemetryService)",
                "long now = System.currentTimeMillis();");
        String daily = between(source,
                "private void foldSessionIntoDaily(long endTime",
                "// Read current row (if any), accumulate");

        assertTrue(record.contains("CapacitySohSnapshot capacitySoh"));
        assertTrue(record.contains("capacitySoh.hasDisplaySoh()"));
        assertTrue(record.contains("capacitySoh.getDisplaySoh()"));
        assertFalse(record.contains("sohEst.hasDisplaySoh()"));
        assertFalse(record.contains("sohEst.getDisplaySoh()"));

        assertTrue(daily.contains("CapacitySohSnapshot capacitySoh"));
        assertTrue(daily.contains("capacitySoh.hasDisplaySoh()"));
        assertTrue(daily.contains("capacitySoh.getDisplaySoh()"));
        assertFalse(daily.contains("est.hasEstimate()"));
        assertFalse(daily.contains("est.getDisplaySoh()"));
    }

    private static String databaseSource() throws IOException {
        return readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/SocHistoryDatabase.java");
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue("Missing start marker: " + start, from >= 0);
        assertTrue("Missing end marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
