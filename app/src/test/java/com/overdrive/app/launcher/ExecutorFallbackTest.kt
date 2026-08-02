package com.overdrive.app.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Submit-policy tests.
 *
 * The property that matters: a task refused by the owning executor must still RUN, because
 * "owner shut down" means the bootManager→Activity handoff took its executor away
 * mid-chain, not that the app is exiting. Before this, such a task was logged once and
 * dropped — and the dropped step was a daemon that then never started.
 */
class ExecutorFallbackTest {

    /** Runs inline and records that it did. */
    private class Recording : Executor {
        var ran = 0
        override fun execute(command: Runnable) {
            ran++
            command.run()
        }
    }

    /** Always refuses, like a shut-down ThreadPoolExecutor. */
    private object Refusing : Executor {
        override fun execute(command: Runnable) = throw RejectedExecutionException("shut down")
    }

    @Test
    fun healthyOwnerRunsTheTaskAndNeverTouchesTheShared() {
        val owner = Recording()
        val shared = Recording()
        var ran = false

        val outcome = ExecutorFallback.submit({ ran = true }, owner, shared)

        assertEquals(ExecutorFallback.Outcome.OWNER, outcome)
        assertTrue(ran)
        assertEquals(1, owner.ran)
        assertEquals("shared executor must not be touched on the happy path", 0, shared.ran)
    }

    @Test
    fun deadOwnerReroutesAndTheTaskStillRuns() {
        // The regression this exists for: the work must survive the handoff.
        val shared = Recording()
        var ran = false

        val outcome = ExecutorFallback.submit({ ran = true }, Refusing, shared)

        assertEquals(ExecutorFallback.Outcome.REROUTED, outcome)
        assertTrue("a rerouted task must actually run, not just be reported", ran)
        assertEquals(1, shared.ran)
    }

    @Test
    fun bothDeadIsReportedAndTheTaskDoesNotRun() {
        var ran = false

        val outcome = ExecutorFallback.submit({ ran = true }, Refusing, Refusing)

        assertEquals(ExecutorFallback.Outcome.REFUSED, outcome)
        assertTrue("nothing should have run", !ran)
    }

    @Test
    fun neverThrowsOnRejection() {
        // Load-bearing: execute() is called from inside onSuccess/onError on a worker
        // thread, where an uncaught RejectedExecutionException kills the process.
        ExecutorFallback.submit({}, Refusing, Refusing)
        ExecutorFallback.submit({}, Refusing, Recording())
    }

    @Test
    fun nonRejectionFailuresPropagate() {
        // A thread factory that blows up is a real defect, not a handoff. It must NOT be
        // silently rerouted — that would turn a crash into a mysterious double execution.
        val exploding = Executor { throw IllegalStateException("thread factory broken") }
        val shared = Recording()

        val thrown = try {
            ExecutorFallback.submit({}, exploding, shared)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue("IllegalStateException must propagate", thrown != null)
        assertEquals("must not have fallen through to shared", 0, shared.ran)
    }

    @Test
    fun theTaskIsSubmittedOnceNotTwice() {
        // Guards against a reroute that leaves the task queued on both executors — which on
        // the daemon paths would mean starting the same daemon twice.
        val shared = Recording()
        var runs = 0

        ExecutorFallback.submit({ runs++ }, Refusing, shared)

        assertEquals(1, runs)
    }
}
