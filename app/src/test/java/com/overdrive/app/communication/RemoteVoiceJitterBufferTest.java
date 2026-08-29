package com.overdrive.app.communication;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteVoiceJitterBufferTest {

    @Test
    public void dropsOldestFramesWhenQueueWouldExceedLatencyBound()
            throws Exception {
        RemoteVoiceJitterBuffer buffer = new RemoteVoiceJitterBuffer(8);

        assertTrue(buffer.offer(pcm(1, 2)));
        assertTrue(buffer.offer(pcm(3, 4)));
        assertTrue(buffer.offer(pcm(5, 6)));

        assertArrayEquals(pcm(3, 4), buffer.poll(0));
        assertArrayEquals(pcm(5, 6), buffer.poll(0));
        assertNull(buffer.poll(0));
        assertEquals(1, buffer.snapshot().droppedFrames);
        assertEquals(4, buffer.snapshot().droppedBytes);
    }

    @Test
    public void oversizedFrameKeepsItsNewestSamples() throws Exception {
        RemoteVoiceJitterBuffer buffer = new RemoteVoiceJitterBuffer(6);

        assertTrue(buffer.offer(pcm(1, 2, 3, 4, 5)));

        assertArrayEquals(pcm(3, 4, 5), buffer.poll(0));
        assertEquals(1, buffer.snapshot().droppedFrames);
        assertEquals(4, buffer.snapshot().droppedBytes);
    }

    @Test
    public void closedBufferRejectsNewAudio() throws Exception {
        RemoteVoiceJitterBuffer buffer = new RemoteVoiceJitterBuffer(8);
        assertTrue(buffer.offer(pcm(1, 2)));

        buffer.close();

        assertFalse(buffer.offer(pcm(3, 4)));
        assertNull(buffer.poll(0));
    }

    @Test
    public void finishedBufferDrainsQueuedAudioBeforeEnding()
            throws Exception {
        RemoteVoiceJitterBuffer buffer = new RemoteVoiceJitterBuffer(8);
        assertTrue(buffer.offer(pcm(1, 2)));

        buffer.finish();

        assertFalse(buffer.offer(pcm(3, 4)));
        assertFalse(buffer.isFinished());
        assertArrayEquals(pcm(1, 2), buffer.poll(0));
        assertTrue(buffer.isFinished());
        assertNull(buffer.poll(0));
    }

    private static byte[] pcm(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            bytes[index * 2] = (byte) samples[index];
        }
        return bytes;
    }
}
