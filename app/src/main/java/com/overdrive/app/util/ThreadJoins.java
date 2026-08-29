package com.overdrive.app.util;

/**
 * Thread-join helpers for teardown paths that must honour their FULL deadline
 * even when the calling thread is (or arrives) interrupted.
 *
 * <p>Rationale (EGL-leak audit): a plain {@code thread.join(timeoutMs)} returns
 * immediately with an {@link InterruptedException} if the caller is interrupted,
 * and callers that swallowed it then read a HEALTHY teardown as wedged (or, worse,
 * proceeded as if the thread had exited and dropped the reference). For GL-owning
 * threads that misread pins an EGL context in the driver's context table; for a
 * codec drainer it races the camera close into a FORTIFY destroyed-mutex abort.
 * These helpers wait out the real deadline across interrupts, record any swallowed
 * interrupt in {@code interruptedHolder[0]} so the caller can restore the thread's
 * interrupt status after teardown, and report honestly whether the thread exited.
 */
public final class ThreadJoins {

    private ThreadJoins() {}

    /**
     * Join {@code thread} honouring the full {@code timeoutMs} across interrupts.
     *
     * @param interruptedHolder single-element array; set to {@code true} if an
     *        interrupt was swallowed while waiting, so the caller can restore the
     *        interrupt status once teardown completes.
     * @return {@code true} iff the thread exited within the real deadline.
     */
    public static boolean joinFullDeadline(Thread thread, long timeoutMs,
            boolean[] interruptedHolder) {
        final long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return !thread.isAlive();
            }
            try {
                thread.join(remainingMs);
                return !thread.isAlive();
            } catch (InterruptedException ie) {
                interruptedHolder[0] = true;
            }
        }
    }
}
