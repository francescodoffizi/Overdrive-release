package com.overdrive.app.launcher

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Submit policy for work whose owning executor may have been shut down under it.
 *
 * `AdbShellExecutor` holds a per-instance executor that dies with its owning
 * `DaemonStartupManager`. `initializeOnAppLaunch()` shuts the previous manager's executor
 * down during the bootManager→Activity handoff, and the daemon-start paths are *chains* —
 * each ADB reply drives the next command — so a chain in flight at that moment loses every
 * remaining step.
 *
 * The distinction the original code missed is that **ownership transfer is not process
 * teardown**. "The executor is dead, so the remaining commands are moot" holds when the app
 * is exiting and is wrong when a new owner has just taken over: the commands are exactly as
 * live as they were a millisecond earlier, and dropping them is how a daemon silently fails
 * to start.
 *
 * Kept as a pure function over two injected [Executor]s so the policy is unit-testable
 * without a Context, an ADB connection, or a real thread pool — same reasoning as
 * `DaemonStopGate`.
 */
object ExecutorFallback {

    /** What happened to a submitted task. Named, so callers can log the cases apart. */
    enum class Outcome {
        /** Accepted by the owning executor — the normal path. */
        OWNER,

        /** Owner refused (shut down); the shared executor took it. */
        REROUTED,

        /** Both refused. The process really is going away. */
        REFUSED
    }

    /**
     * Try [owner] first, then [shared].
     *
     * Never throws: [RejectedExecutionException] from either executor is converted into an
     * [Outcome]. That is load-bearing, not defensive — `AdbShellExecutor.execute()` is
     * called from inside onSuccess/onError callbacks running on a worker thread, where an
     * uncaught rejection kills the process.
     *
     * Only [RejectedExecutionException] is caught. Anything else (an executor whose
     * thread factory throws, say) is a real defect and must not be silently rerouted.
     */
    fun submit(task: Runnable, owner: Executor, shared: Executor): Outcome {
        try {
            owner.execute(task)
            return Outcome.OWNER
        } catch (e: RejectedExecutionException) {
            // Owner is gone; the work is not.
        }
        return try {
            shared.execute(task)
            Outcome.REROUTED
        } catch (e: RejectedExecutionException) {
            Outcome.REFUSED
        }
    }
}
