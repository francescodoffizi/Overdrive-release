package com.overdrive.app.launcher

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Submit policy for work whose owning executor may have been shut down under it.
 *
 * The per-instance executor dies with its owning DaemonStartupManager, and
 * `initializeOnAppLaunch()` shuts the previous manager's down during the
 * bootManager→Activity handoff. Ownership transfer is not process teardown, so the
 * in-flight commands are still live and dropping them loses a daemon start.
 *
 * Pure function over two injected [Executor]s so the policy is unit-testable.
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
     * Never throws: an uncaught rejection would kill the process, because
     * `AdbShellExecutor.execute()` is called from inside onSuccess/onError on a worker
     * thread. Only [RejectedExecutionException] is caught — a thread factory that throws
     * is a real defect and must not be silently rerouted.
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
