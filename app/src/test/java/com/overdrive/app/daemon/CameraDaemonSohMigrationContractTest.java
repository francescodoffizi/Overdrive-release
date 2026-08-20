package com.overdrive.app.daemon;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class CameraDaemonSohMigrationContractTest {

    @Test
    public void databaseInitializationPrecedesEstimatorReplayHandoff()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int database = source.indexOf(
                "SocHistoryDatabase socDb =");
        int init = source.indexOf("socDb.init();", database);
        int handoff = source.indexOf(
                "socDb.setSohEstimator(sohEstimator);", init);
        int start = source.indexOf("socDb.start();", handoff);

        assertTrue(database >= 0);
        assertTrue(init > database);
        assertTrue(handoff > init);
        assertTrue(start > handoff);
    }

    @Test
    public void deferredSohInitIsRetriedAndStillGatesAutoDetection()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int detection = source.indexOf(
                "// Now that BydDataCollector is ready");
        int readyCheck = source.indexOf(
                "if (!sohEstimator.isInitializationReady())", detection);
        int retry = source.indexOf("sohEstimator.init();", readyCheck);
        int admitted = source.indexOf(
                "if (sohEstimator.isInitializationReady())", retry);
        int autoDetect = source.indexOf(
                "sohEstimator.autoDetectCarModel", admitted);
        int replayHandoff = source.indexOf(
                ".getInstance().setSohEstimator(sohEstimator)", autoDetect);

        assertTrue(readyCheck > detection);
        assertTrue(retry > readyCheck);
        assertTrue(admitted > retry);
        assertTrue(autoDetect > admitted);
        assertTrue(replayHandoff > autoDetect);
    }

    @Test
    public void daemonPublishesCompletionOnlyAfterDatabaseCommitsMarker()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int snapshot = source.indexOf(
                "capacitySohSnapshotForMigration =");
        int migration = source.indexOf(
                "if (socDb.fixStaleRemainingKwh(capacitySohSnapshotForMigration))",
                snapshot);
        int complete = source.indexOf(
                "Stale kWh migration done in", migration);
        int deferred = source.indexOf(
                "Stale kWh migration deferred; no version marker committed",
                complete);

        assertTrue(snapshot >= 0);
        assertTrue(migration > snapshot);
        assertTrue(complete > migration);
        assertTrue(deferred > complete);
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
