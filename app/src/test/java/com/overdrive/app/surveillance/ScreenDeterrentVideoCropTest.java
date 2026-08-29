package com.overdrive.app.surveillance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class ScreenDeterrentVideoCropTest {

    @Test
    public void matchingAspectUsesTheWholeFrame() {
        assertArrayEquals(new int[] { 0, 0, 1920, 1080 },
                ScreenDeterrentVideo.coverCropBounds(1920, 1080, 1920, 1080));
    }

    @Test
    public void mismatchedAspectsCropInsteadOfLetterboxing() {
        assertArrayEquals(new int[] { 320, 0, 2240, 1080 },
                ScreenDeterrentVideo.coverCropBounds(2560, 1080, 1920, 1080));
        assertArrayEquals(new int[] { 0, 236, 1080, 844 },
                ScreenDeterrentVideo.coverCropBounds(1080, 1080, 1920, 1080));
    }

    @Test
    public void cropNeverEscapesTheSourceFrame() {
        int[][] cases = {
                { 1920, 1080, 1080, 1920 },
                { 640, 480, 1920, 1080 },
                { 1080, 1920, 1920, 1080 },
                { 1, 1, 1920, 1080 }
        };
        for (int[] value : cases) {
            int[] crop = ScreenDeterrentVideo.coverCropBounds(
                    value[0], value[1], value[2], value[3]);
            assertTrue(crop[0] >= 0);
            assertTrue(crop[1] >= 0);
            assertTrue(crop[2] <= value[0]);
            assertTrue(crop[3] <= value[1]);
            assertTrue(crop[2] > crop[0]);
            assertTrue(crop[3] > crop[1]);
        }
    }

    @Test
    public void rotationMetadataIsAppliedBeforeCoverCropping() {
        assertArrayEquals(new int[] { 0, 0, 1080, 1920 },
                ScreenDeterrentVideo.coverCropBounds(
                        1080, 1920, 1920, 1080, 90));
        assertArrayEquals(new int[] { 0, 0, 1080, 1920 },
                ScreenDeterrentVideo.coverCropBounds(
                        1080, 1920, 1920, 1080, 270));
    }

    @Test
    public void loopOffsetsUseTrackDurationAndNeverOverlap() {
        assertTrue(ScreenDeterrentVideo.nextLoopOffsetUs(
                0, 10_000_000, 9_600_000, 33_333) == 10_000_000);
        assertTrue(ScreenDeterrentVideo.nextLoopOffsetUs(
                0, 9_500_000, 9_600_000, 33_333) == 9_633_333);
        assertTrue(ScreenDeterrentVideo.nextLoopOffsetUs(
                10_000_000, -1, 9_600_000, 33_333) == 19_633_333);
    }

    @Test
    public void mp4DetectionUsesBytesRatherThanTheUploadName() {
        byte[] mp4 = new byte[] {
                0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'
        };
        byte[] png = new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0
        };
        assertTrue(ScreenDeterrentVideo.isMp4(mp4));
        assertFalse(ScreenDeterrentVideo.isMp4(png));
    }

    @Test
    public void decoderSelectionUsesTheActualTrackFormat() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/ScreenDeterrentVideo.java");
        assertTrue(source.contains("findDecoderForFormat(format)"));
        assertTrue(source.contains("MediaCodec.createByCodecName(decoderName)"));
        assertTrue(source.contains("layer.setBufferRotation(rotation)"));
        assertTrue(source.contains("visibleWidth"));
        assertTrue(source.contains("right >= coded"));
        assertTrue(source.contains("shouldStop, onFrame"));
        assertTrue(source.contains("SystemClock.elapsedRealtime()"));
        int hide = source.indexOf("layer.hide()");
        int stop = source.indexOf("codec.stop()", hide);
        assertTrue(hide >= 0);
        assertTrue(stop > hide);
        assertFalse(source.contains("MAX_FRAME_WAIT_MS"));
        assertFalse(source.contains("System.currentTimeMillis()"));
        assertFalse(source.contains(
                "MediaFormat.createVideoFormat(mime, width, height)"));
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
