package com.overdrive.app.communication;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Small bounded queue between the remote PCM socket and the car audio writer.
 *
 * <p>When a stalled connection releases a burst, the oldest speech is dropped
 * so remote voice stays live instead of accumulating unbounded latency.
 */
public final class RemoteVoiceJitterBuffer {

    public static final class Stats {
        public final long droppedFrames;
        public final long droppedBytes;
        public final int queuedBytes;

        private Stats(long droppedFrames, long droppedBytes, int queuedBytes) {
            this.droppedFrames = droppedFrames;
            this.droppedBytes = droppedBytes;
            this.queuedBytes = queuedBytes;
        }
    }

    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();
    private final int maxBytes;
    private int queuedBytes;
    private long droppedFrames;
    private long droppedBytes;
    private boolean accepting = true;
    private boolean aborted;

    public RemoteVoiceJitterBuffer(int maxBytes) {
        if (maxBytes < 2 || (maxBytes & 1) != 0) {
            throw new IllegalArgumentException(
                    "PCM queue capacity must contain whole 16-bit samples");
        }
        this.maxBytes = maxBytes;
    }

    public synchronized boolean offer(byte[] pcm) {
        if (!accepting) return false;
        if (pcm == null || pcm.length == 0 || (pcm.length & 1) != 0) {
            throw new IllegalArgumentException(
                    "PCM frame must contain whole 16-bit samples");
        }

        byte[] accepted = pcm;
        if (accepted.length > maxBytes) {
            int discarded = accepted.length - maxBytes;
            accepted = Arrays.copyOfRange(
                    accepted, accepted.length - maxBytes, accepted.length);
            droppedFrames++;
            droppedBytes += discarded;
        }

        while (!frames.isEmpty()
                && queuedBytes + accepted.length > maxBytes) {
            byte[] removed = frames.removeFirst();
            queuedBytes -= removed.length;
            droppedFrames++;
            droppedBytes += removed.length;
        }

        frames.addLast(accepted);
        queuedBytes += accepted.length;
        notifyAll();
        return true;
    }

    public synchronized byte[] poll(long timeoutMs)
            throws InterruptedException {
        if (timeoutMs < 0L) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (frames.isEmpty() && accepting && timeoutMs > 0L) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) break;
            wait(remaining);
        }
        if (frames.isEmpty()) return null;
        byte[] frame = frames.removeFirst();
        queuedBytes -= frame.length;
        return frame;
    }

    public synchronized Stats snapshot() {
        return new Stats(droppedFrames, droppedBytes, queuedBytes);
    }

    public synchronized boolean isClosed() {
        return aborted;
    }

    public synchronized boolean isFinished() {
        return !accepting && frames.isEmpty();
    }

    public synchronized void finish() {
        accepting = false;
        notifyAll();
    }

    public synchronized void close() {
        accepting = false;
        aborted = true;
        frames.clear();
        queuedBytes = 0;
        notifyAll();
    }
}
