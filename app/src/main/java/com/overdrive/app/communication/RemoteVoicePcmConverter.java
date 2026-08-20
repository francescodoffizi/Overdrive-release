package com.overdrive.app.communication;

/**
 * Converts browser PCM into the native stereo format used by the car output.
 */
public final class RemoteVoicePcmConverter {

    private RemoteVoicePcmConverter() {}

    public static int requiredStereoBytes(
            int monoBytes, int sourceRate, int outputRate) {
        validateArguments(monoBytes, sourceRate, outputRate);
        int inputSamples = monoBytes / 2;
        long outputFrames = Math.max(
                1L,
                Math.round(inputSamples * (double) outputRate / sourceRate));
        long outputBytes = outputFrames * 4L;
        if (outputBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Converted PCM frame is too large");
        }
        return (int) outputBytes;
    }

    public static int mono16LeToStereo16Le(
            byte[] source,
            int sourceBytes,
            int sourceRate,
            int outputRate,
            byte[] destination) {
        if (source == null || sourceBytes > source.length) {
            throw new IllegalArgumentException("Invalid source PCM buffer");
        }
        int outputBytes =
                requiredStereoBytes(sourceBytes, sourceRate, outputRate);
        if (destination == null || destination.length < outputBytes) {
            throw new IllegalArgumentException("Destination PCM buffer is too small");
        }

        int inputSamples = sourceBytes / 2;
        int outputFrames = outputBytes / 4;
        double sourceStep = sourceRate / (double) outputRate;
        for (int frame = 0; frame < outputFrames; frame++) {
            double sourcePosition = frame * sourceStep;
            int firstIndex = Math.min(
                    inputSamples - 1, (int) sourcePosition);
            int secondIndex = Math.min(inputSamples - 1, firstIndex + 1);
            double fraction = sourcePosition - firstIndex;
            int first = readSample(source, firstIndex);
            int second = readSample(source, secondIndex);
            int sample = (int) Math.round(first + (second - first) * fraction);
            writeStereoSample(destination, frame * 4, sample);
        }
        return outputBytes;
    }

    public static int peakAbsoluteSample(byte[] pcm, int byteCount) {
        if (pcm == null || byteCount < 0 || byteCount > pcm.length
                || (byteCount & 1) != 0) {
            throw new IllegalArgumentException("Invalid PCM buffer");
        }
        int peak = 0;
        for (int offset = 0; offset < byteCount; offset += 2) {
            int value = (short) ((pcm[offset] & 0xFF)
                    | (pcm[offset + 1] << 8));
            peak = Math.max(peak, Math.abs(value));
        }
        return peak;
    }

    private static void validateArguments(
            int monoBytes, int sourceRate, int outputRate) {
        if (monoBytes <= 0 || (monoBytes & 1) != 0) {
            throw new IllegalArgumentException("Mono PCM must contain whole samples");
        }
        if (sourceRate <= 0 || outputRate <= 0) {
            throw new IllegalArgumentException("PCM sample rates must be positive");
        }
    }

    private static int readSample(byte[] source, int sampleIndex) {
        int offset = sampleIndex * 2;
        return (short) ((source[offset] & 0xFF)
                | (source[offset + 1] << 8));
    }

    private static void writeStereoSample(
            byte[] destination, int offset, int sample) {
        short value = (short) Math.max(
                Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
        byte low = (byte) (value & 0xFF);
        byte high = (byte) ((value >>> 8) & 0xFF);
        destination[offset] = low;
        destination[offset + 1] = high;
        destination[offset + 2] = low;
        destination[offset + 3] = high;
    }
}
