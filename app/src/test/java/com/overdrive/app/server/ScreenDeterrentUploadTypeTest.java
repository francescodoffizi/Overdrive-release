package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class ScreenDeterrentUploadTypeTest {

    private static final byte[] MP4 = {
            0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'
    };
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0
    };
    private static final byte[] GIF = {
            'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0
    };

    @Test
    public void contentSniffingCannotBeBypassedByTheFilename() {
        assertEquals("mp4",
                SurveillanceApiHandler.deterrentAssetExtension(MP4));
        assertEquals("png",
                SurveillanceApiHandler.deterrentAssetExtension(PNG));
        assertEquals("gif",
                SurveillanceApiHandler.deterrentAssetExtension(GIF));
        assertNull(SurveillanceApiHandler.deterrentAssetExtension(
                new byte[] { 1, 2, 3, 4 }));
    }

    @Test
    public void assetSwapCommitsConfigBeforeDeletingThePreviousFile() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        int persistMethod = source.indexOf("persistScreenDeterrentAsset(");
        int uniqueTemp = source.indexOf("java.io.File.createTempFile(", persistMethod);
        int uniqueFinal = source.indexOf("java.util.UUID.randomUUID()", persistMethod);
        int configCommit = source.indexOf("boolean persisted =", persistMethod);
        int cleanup = source.indexOf(
                "deleteDeterrentAssetsExcept(dir, outFile)", configCommit);

        assertTrue(source.contains("SCREEN_DETERRENT_ASSET_LOCK"));
        assertTrue(source.contains("ScreenDeterrentAsset"));
        assertTrue(source.contains(".isAllowedPath(path)"));
        assertTrue(source.contains("java.io.FileInputStream input;"));
        assertTrue(source.contains("X-Content-Type-Options: nosniff"));
        assertTrue(source.contains("validateDeterrentImage(tmpFile"));
        assertTrue(source.contains("boolean staging = name.endsWith(\".tmp\")"));
        assertTrue(uniqueTemp > persistMethod);
        assertTrue(uniqueFinal > uniqueTemp);
        assertTrue(configCommit > uniqueFinal);
        assertTrue(cleanup > configCommit);
        assertTrue(source.substring(configCommit, cleanup).contains("if (!persisted)"));

        int clear = source.indexOf(
                "if (configJson.has(\"clearScreenDeterrentImage\")");
        int clearCommit = source.indexOf("boolean persisted =", clear);
        int clearDelete = source.indexOf(
                "deleteDeterrentAssetsExcept(dir, null)", clearCommit);
        assertTrue(clearCommit > clear);
        assertTrue(clearDelete > clearCommit);
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
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
