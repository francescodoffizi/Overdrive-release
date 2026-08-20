package com.overdrive.app.communication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RemoteVoicePcmConverterTest {

    @Test
    public void duplicatesMonoSamplesIntoBothStereoChannels() {
        byte[] mono = pcm((short) 0x1234, (short) -0x1234);
        byte[] stereo = new byte[
                RemoteVoicePcmConverter.requiredStereoBytes(
                        mono.length, 16_000, 16_000)];

        int written = RemoteVoicePcmConverter.mono16LeToStereo16Le(
                mono, mono.length, 16_000, 16_000, stereo);

        assertEquals(8, written);
        assertArrayEquals(
                new byte[]{
                        0x34, 0x12, 0x34, 0x12,
                        (byte) 0xCC, (byte) 0xED,
                        (byte) 0xCC, (byte) 0xED
                },
                stereo);
    }

    @Test
    public void convertsSixteenKhzVoiceToFortyEightKhzStereo() {
        byte[] mono = pcm((short) 0, (short) 3_000);
        byte[] stereo = new byte[
                RemoteVoicePcmConverter.requiredStereoBytes(
                        mono.length, 16_000, 48_000)];

        int written = RemoteVoicePcmConverter.mono16LeToStereo16Le(
                mono, mono.length, 16_000, 48_000, stereo);

        assertEquals(24, written);
        assertEquals(0, sample(stereo, 0));
        assertEquals(1_000, sample(stereo, 1));
        assertEquals(2_000, sample(stereo, 2));
        assertEquals(3_000, sample(stereo, 3));
        assertEquals(3_000, sample(stereo, 4));
        assertEquals(3_000, sample(stereo, 5));
    }

    @Test
    public void reportsInputSignalPeak() {
        byte[] mono = pcm((short) -32_768, (short) 100, (short) 20_000);

        assertEquals(32_768,
                RemoteVoicePcmConverter.peakAbsoluteSample(
                        mono, mono.length));
    }

    private static byte[] pcm(short... samples) {
        byte[] result = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            result[i * 2] = (byte) (samples[i] & 0xFF);
            result[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xFF);
        }
        return result;
    }

    private static int sample(byte[] stereo, int frame) {
        int offset = frame * 4;
        int left = (short) ((stereo[offset] & 0xFF)
                | (stereo[offset + 1] << 8));
        int right = (short) ((stereo[offset + 2] & 0xFF)
                | (stereo[offset + 3] << 8));
        assertEquals(left, right);
        return left;
    }
}
