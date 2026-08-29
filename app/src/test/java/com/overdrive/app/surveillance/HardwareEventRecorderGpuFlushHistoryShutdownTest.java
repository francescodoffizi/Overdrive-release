package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.media.MediaCodec;

import com.overdrive.app.logging.DaemonLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * FLUSH_HISTORY writer-job shutdown behavior (pre-record/slow-SD fix).
 *
 * <p>The disk writer owns history processing; stopDiskWriterThread flips
 * {@code diskWriterRunning}, interrupts the thread, and joins for only 2s.
 * An in-flight history job MUST observe the shutdown at its loop top and
 * bail WITHOUT consuming further ring packets — while its finally still
 * closes the cursor (releasing the ring pin) and clears flushInProgress.
 * A leaked pin would collapse the NEXT event's pre-record window; a loop
 * that ignores shutdown can outlive the join and race close/release.
 */
public class HardwareEventRecorderGpuFlushHistoryShutdownTest {

    private DaemonLogger.Config originalLoggerConfig;

    @Before
    public void setUp() {
        originalLoggerConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
    }

    @After
    public void tearDown() {
        DaemonLogger.configure(originalLoggerConfig);
    }

    @Test
    public void writerShutdownDiscardsHistoryJobAndReleasesRingPin() throws Exception {
        // Ring with one keyframe + two P-frames.
        H264ByteRingBuffer ring = new H264ByteRingBuffer(1024 * 1024, 30);
        addPacket(ring, 1_000_000L, true);
        addPacket(ring, 1_033_333L, false);
        addPacket(ring, 1_066_666L, false);

        H264ByteRingBuffer.Cursor cursor =
                ring.beginFlushRange(900_000L, 2_000_000L);
        assertNotNull("test setup: range cursor must pin", cursor);
        assertEquals("test setup: cursor must cover all packets",
                3, cursor.remaining());

        HardwareEventRecorderGpu recorder = allocate(HardwareEventRecorderGpu.class);
        setField(recorder, "flushInProgress", true);
        // Writer already shut down when the job is picked up.
        setField(recorder, "diskWriterRunning", false);
        // Unsafe allocation skips final-field initializers. Install the two
        // the history loop touches, so the loop is genuinely RUNNABLE and
        // only the shutdown check can stop it before consuming a packet —
        // otherwise an NPE on the null historyReadInfo would break the loop
        // at packet zero and this test would pass vacuously without the fix.
        setField(recorder, "historyReadInfo", allocate(MediaCodec.BufferInfo.class));
        setField(recorder, "muxerLock", new Object());

        Class<?> packetClass = Class.forName(
                "com.overdrive.app.surveillance.HardwareEventRecorderGpu$MuxerPacket");
        Object job = allocate(packetClass);
        Field cursorField = packetClass.getDeclaredField("historyCursor");
        cursorField.setAccessible(true);
        cursorField.set(job, cursor);

        Method process = HardwareEventRecorderGpu.class.getDeclaredMethod(
                "processFlushHistoryJob", packetClass);
        process.setAccessible(true);
        process.invoke(recorder, job);

        // Shutdown observed at the loop TOP: no ring packet consumed AND the
        // loop's first action (growing historyReadBuffer) never ran. The
        // buffer probe is the discriminating assertion on the JVM: without
        // the shutdown check the loop allocates the read buffer before
        // anything else can stop it (stub-jar BufferInfo methods throw
        // inside Cursor.next, so remaining() alone cannot distinguish a
        // shutdown bail-out from a stub-induced abort).
        assertEquals("no history packet may be consumed after writer shutdown",
                3, cursor.remaining());
        assertNull("history loop must not start work after writer shutdown "
                        + "(read buffer allocated => loop body entered)",
                getField(recorder, "historyReadBuffer"));
        // Ownership handoff completed even on the bail-out path.
        assertNull("job must not retain the cursor", cursorField.get(job));
        assertFalse("flushInProgress must clear (manual-replay exclusion)",
                getBooleanField(recorder, "flushInProgress"));

        // Cursor was closed → ring pin released → a new range cursor can pin.
        H264ByteRingBuffer.Cursor next =
                ring.beginFlushRange(900_000L, 2_000_000L);
        assertNotNull("ring pin must be released by the discarded job", next);
        next.close();
    }

    private static void addPacket(H264ByteRingBuffer ring, long ptsUs,
                                  boolean keyframe) throws Exception {
        // Stub-jar BufferInfo constructor throws; allocate raw and set the
        // public fields directly (same technique as the ownership test).
        MediaCodec.BufferInfo info = allocate(MediaCodec.BufferInfo.class);
        info.offset = 0;
        info.size = 64;
        info.presentationTimeUs = ptsUs;
        info.flags = keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
        ring.add(ByteBuffer.allocateDirect(64), info);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        return (T) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, type);
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean getBooleanField(Object target, String name)
            throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = HardwareEventRecorderGpu.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
