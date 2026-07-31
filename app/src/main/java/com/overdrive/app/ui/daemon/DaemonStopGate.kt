package com.overdrive.app.ui.daemon

/**
 * Decides whether a daemon is under a *stop intent* and must not be (re)started.
 *
 * Two stop signals, both plain files under `/data/local/tmp`:
 *  - the per-daemon **user-stop sentinel** (`DaemonType.sentinelPath`) — written by the Daemons UI
 *    or by a Telegram `/daemon <x> stop`, which records the stop ONLY in this cross-UID file and
 *    never in the app's SharedPreferences;
 *  - the **parked-shutdown marker** — in "Vehicle ON only" the whole stack is torn down on park and
 *    must stay down until the ACC-on edge clears the marker.
 *
 * The two callers want different policies, so they get named entry points rather than a boolean:
 * [isStartBlocked] (startup — sentinel only) and [isRelaunchBlocked] (health check — sentinel OR
 * park marker). Passing the wrong policy is the kind of mistake a boolean invites and a name does
 * not, and it matters: mixing them up would let the 30s health check revive a parked stack.
 *
 * ## Why this exists
 *
 * These files used to be probed with `test -f … && echo STOPPED || echo OK` sent over the app's own
 * ADB connection, with the caller's real work happening inside the reply callback. That makes a
 * required decision depend on a reply that is not guaranteed to arrive: `AdbShellExecutor.execute()`
 * swallows `RejectedExecutionException` once its executor is shut down, and `initializeOnAppLaunch()`
 * shuts down the previous manager's executor during the bootManager→Activity handoff, so work that
 * manager had already `postDelayed` can no-op with no log line.
 *
 * HONESTY NOTE — that failure mode is structural, not observed. It was originally believed to be the
 * cause of tailscaled failing to auto-start on a BYD Seal head unit, but instrumenting the executor
 * (correlation-id SUBMIT/RUN/DONE logging, plus lock- and probe-timers) disproved it: every command
 * in that chain was submitted, ran, and returned exit 0, with no rejections and no lock contention.
 * The original symptom turned out to be a measurement artifact in the diagnosis harness. This change
 * therefore removes a genuine fragility and is NOT a fix for that bug — do not cite it as one.
 *
 * ## What makes the local check valid — and its one blind spot
 *
 * `File.exists()` needs **search (`x`) permission on every parent directory**, not read permission
 * on the file; the sentinels' `0666` mode is irrelevant to it. What actually makes this work is
 * `/data/local/tmp` being `0771` plus permissive SELinux for the app domain on this head unit. That
 * is empirically established: `BootReceiver`, `DaemonKeepaliveService`, `StatusOverlayService` and
 * `LocationSidecarService` already read the park marker this way from the app process, and park
 * mode works in the field.
 *
 * **Blind spot:** `File.exists()` cannot throw here (no SecurityManager; `stat`/`access` failures
 * such as EACCES or an unsearchable parent simply return `false`). So `false` means "absent **or
 * invisible**", and this class cannot tell them apart. If a future OTA tightens `/data/local/tmp`
 * or SELinux, both signals silently read absent — resurrecting user-stopped daemons and, worse,
 * letting the health check relaunch the whole stack every 30s while parked. Nothing in-process can
 * distinguish the two cases, so callers that care should verify visibility out-of-band rather than
 * trusting a bare `false`.
 *
 * Kept as pure functions over an injected `exists` predicate so the policy is unit-testable without
 * a device, an ADB connection, or a filesystem.
 */
object DaemonStopGate {

    /**
     * Startup policy: block only on the per-daemon user-stop sentinel.
     *
     * The park marker is deliberately NOT consulted. This reproduces the previous probe exactly
     * (`test -f <sentinel>`, no marker term), and the startup paths cannot run under a live marker
     * anyway — `initializeOnAppLaunch` clears it before scheduling, and on the boot path
     * `BootReceiver.startDaemons` refuses to reach `startOnBoot` while the marker is present unless
     * the trigger is a recovery trigger.
     */
    fun isStartBlocked(sentinelPath: String?, exists: (String) -> Boolean): Boolean =
        sentinelPath != null && exists(sentinelPath)

    /** Machine-stop markers in a content-aware sentinel's first line: the ACC-off sweep and the
     *  update sweep's write-if-absent `stopAllDaemons`. Neither is a user stop. */
    private val MACHINE_STOP = Regex("ACC-on|stopAllDaemons")

    /**
     * Startup policy for a **content-aware** sentinel (Telegram): block on the user-stop sentinel
     * UNLESS its first line marks a MACHINE stop, which is not a user stop and must not suppress a
     * pref-enabled start. This is the local port of the former over-ADB probe
     * `head -1 <sentinel> | grep -qE 'ACC-on|stopAllDaemons'`.
     *
     * TELEGRAM ONLY, deliberately: its sentinel's text is an authoritative discriminator because the
     * update sweep writes it write-if-absent (`AppUpdater.stopAllDaemons`), so `stopAllDaemons` can
     * only appear when no user stop was already recorded. zrok's sentinel carries the same text but
     * is written with a clobbering `>`, so trusting its text would read a clobbered user stop as a
     * machine stop — those daemons keep the existence-only [isStartBlocked] gate.
     *
     * An existing-but-unreadable sentinel (`firstLine` returns null) is treated as a user stop
     * (blocked), matching the old probe: with no readable first line its `grep` found no machine
     * marker and echoed STOPPED. Absent sentinel → not blocked, same as [isStartBlocked].
     */
    fun isStartBlockedContentAware(
        sentinelPath: String?,
        exists: (String) -> Boolean,
        firstLine: (String) -> String?
    ): Boolean {
        if (sentinelPath == null || !exists(sentinelPath)) return false
        val head = firstLine(sentinelPath) ?: return true
        return !MACHINE_STOP.containsMatchIn(head)
    }

    /**
     * Health-check policy: block on the user-stop sentinel OR the parked-shutdown marker.
     *
     * The health check is the one path that could otherwise revive a stack that "Vehicle ON only"
     * deliberately tore down on park, so it must see the marker.
     */
    fun isRelaunchBlocked(
        sentinelPath: String?,
        markerPath: String?,
        exists: (String) -> Boolean
    ): Boolean =
        (sentinelPath != null && exists(sentinelPath)) ||
            (markerPath != null && exists(markerPath))
}
