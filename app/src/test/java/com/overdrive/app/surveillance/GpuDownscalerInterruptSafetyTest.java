package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;

/**
 * Verifies the interrupt discipline of GpuDownscaler's teardown waits
 * (audit follow-up: "false downscaler restarts"). An interrupted caller of
 * release()/init() must NOT be misread as a wedged EGL thread:
 *
 * <ul>
 *   <li>awaitFullDeadline / joinFullDeadline honour their REAL deadline even
 *       when the calling thread arrives pre-interrupted (previously the wait
 *       threw instantly, cleanupComplete read false, and a healthy teardown
 *       triggered a trip-safe process restart).</li>
 *   <li>Swallowed interrupts are reported via the holder so release()/init()
 *       can restore the caller's interrupt status.</li>
 * </ul>
 *
 * The helpers are private statics exercised via reflection — same pattern as
 * HardwareEventRecorderGpuPreRecordOwnershipTest (pure-JVM state machine, no
 * Android framework touched).
 */
public class GpuDownscalerInterruptSafetyTest {

    private static boolean callAwaitFullDeadline(CountDownLatch latch, long timeoutMs,
            boolean[] holder) throws Exception {
        Method m = GpuDownscaler.class.getDeclaredMethod("awaitFullDeadline",
                CountDownLatch.class, long.class, boolean[].class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, latch, timeoutMs, holder);
    }

    private static boolean callJoinFullDeadline(Thread thread, long timeoutMs,
            boolean[] holder) {
        // joinFullDeadline moved to the shared com.overdrive.app.util.ThreadJoins
        // helper (audit follow-up: PanoramicCameraGpu and the encoder drainer now
        // need the same interrupt-honest join) — call it directly, no reflection.
        return com.overdrive.app.util.ThreadJoins.joinFullDeadline(thread, timeoutMs, holder);
    }

    @Test
    public void preInterruptedAwaitStillWaitsOutRealDeadlineAndSucceeds() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        // "Cleanup" completes 300ms in — well inside the 2s-style deadline.
        Thread opener = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            latch.countDown();
        });
        opener.start();

        boolean[] interrupted = { false };
        Thread.currentThread().interrupt();  // caller arrives pre-interrupted
        try {
            long t0 = System.nanoTime();
            // The await itself throws InterruptedException internally (flag was
            // set) — the fix must swallow it, record it, and KEEP WAITING.
            boolean opened = callAwaitFullDeadline(latch, 2000, interrupted);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertTrue("latch open within real deadline must report success "
                    + "(previously the pre-set interrupt made this read false "
                    + "and triggered a false process restart)", opened);
            assertTrue("wait must not return before the cleanup landed (~300ms), "
                    + "was " + elapsedMs + "ms", elapsedMs >= 250);
            assertTrue("swallowed interrupt must be reported to the caller",
                    interrupted[0]);
        } finally {
            Thread.interrupted();  // clear for other tests
            opener.join(2000);
        }
    }

    @Test
    public void awaitReportsGenuineTimeoutOnlyAfterFullDeadline() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        boolean[] interrupted = { false };
        Thread.currentThread().interrupt();
        try {
            long t0 = System.nanoTime();
            boolean opened = callAwaitFullDeadline(never, 300, interrupted);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertFalse("latch never opened — must report timeout", opened);
            assertTrue("timeout must be the REAL 300ms deadline, not an instant "
                    + "interrupt-throw (was " + elapsedMs + "ms)", elapsedMs >= 250);
            assertTrue(interrupted[0]);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void preInterruptedJoinStillSeesHealthyThreadExit() throws Exception {
        Thread worker = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        });
        worker.start();

        boolean[] interrupted = { false };
        Thread.currentThread().interrupt();
        try {
            boolean exited = callJoinFullDeadline(worker, 2000, interrupted);
            assertTrue("healthy thread exiting within the real deadline must not "
                    + "be misread as wedged", exited);
            assertTrue(interrupted[0]);
        } finally {
            Thread.interrupted();
            worker.join(2000);
        }
    }

    @Test
    public void joinReportsWedgedThreadOnlyAfterFullDeadline() throws Exception {
        CountDownLatch unblock = new CountDownLatch(1);
        Thread wedged = new Thread(() -> {
            try { unblock.await(); } catch (InterruptedException ignored) {}
        });
        wedged.start();

        boolean[] interrupted = { false };
        try {
            long t0 = System.nanoTime();
            boolean exited = callJoinFullDeadline(wedged, 300, interrupted);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertFalse("genuinely-blocked thread must report not-exited", exited);
            assertTrue("verdict only after the real 300ms deadline (was "
                    + elapsedMs + "ms)", elapsedMs >= 250);
        } finally {
            unblock.countDown();
            wedged.join(2000);
        }
    }
}
