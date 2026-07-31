package com.overdrive.app.ui.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for the stop gate.
 *
 * The distinction that actually matters here is between the two entry points: startup must IGNORE
 * the parked-shutdown marker, and the health check must HONOUR it. Getting that backwards would let
 * the 30s health check resurrect a stack that "Vehicle ON only" deliberately tore down on park —
 * defeating the zero-compute park and draining the 12V battery. That is why the policies are named
 * functions rather than a boolean parameter: these tests pin each one to its caller's semantics.
 */
class DaemonStopGateTest {

    private val SENTINEL = "/data/local/tmp/tailscale.disabled"
    private val MARKER = "/data/local/tmp/overdrive_parked_shutdown"

    private fun present(vararg paths: String) = { p: String -> p in paths }

    // ---- startup policy: sentinel only ----

    @Test
    fun startup_allowsWhenNothingPresent() {
        assertFalse(DaemonStopGate.isStartBlocked(SENTINEL, present()))
    }

    @Test
    fun startup_blockedByUserStopSentinel() {
        assertTrue(DaemonStopGate.isStartBlocked(SENTINEL, present(SENTINEL)))
    }

    @Test
    fun startup_IGNORES_parkedMarker() {
        // The startup paths cannot run under a live marker (BootReceiver gates the boot path;
        // initializeOnAppLaunch clears it first), so a lingering marker must not block them.
        // This reproduces the previous ADB probe exactly: `test -f <sentinel>`, no marker term.
        assertFalse(DaemonStopGate.isStartBlocked(SENTINEL, present(MARKER)))
    }

    @Test
    fun startup_nullSentinelNeverBlocks() {
        assertFalse(DaemonStopGate.isStartBlocked(null, present(SENTINEL, MARKER)))
    }

    // ---- health-check policy: sentinel OR parked marker ----

    @Test
    fun relaunch_allowsWhenNothingPresent() {
        assertFalse(DaemonStopGate.isRelaunchBlocked(SENTINEL, MARKER, present()))
    }

    @Test
    fun relaunch_blockedByUserStopSentinel() {
        assertTrue(DaemonStopGate.isRelaunchBlocked(SENTINEL, MARKER, present(SENTINEL)))
    }

    @Test
    fun relaunch_HONOURS_parkedMarker() {
        // The regression that matters: without this the health check revives a parked stack.
        assertTrue(DaemonStopGate.isRelaunchBlocked(SENTINEL, MARKER, present(MARKER)))
    }

    @Test
    fun relaunch_blockedWhenBothPresent() {
        assertTrue(DaemonStopGate.isRelaunchBlocked(SENTINEL, MARKER, present(SENTINEL, MARKER)))
    }

    @Test
    fun relaunch_checksSentinelIndependentlyOfMarker() {
        // Guards against a short-circuit that only ever consults one of the two paths.
        assertFalse(DaemonStopGate.isRelaunchBlocked(SENTINEL, MARKER, present("/some/other/file")))
    }

    // ---- content-aware startup policy (Telegram): sentinel text discriminates user vs machine stop ----

    private val TELEGRAM_SENTINEL = "/data/local/tmp/telegram_bot_daemon.disabled"
    private fun firstLine(map: Map<String, String?>) = { p: String -> map[p] }

    @Test
    fun contentAware_allowsWhenSentinelAbsent() {
        assertFalse(
            DaemonStopGate.isStartBlockedContentAware(
                TELEGRAM_SENTINEL, present(), firstLine(emptyMap())
            )
        )
    }

    @Test
    fun contentAware_blocksOnUserStopText() {
        // A user stop leaves text that is NOT a machine marker → block.
        assertTrue(
            DaemonStopGate.isStartBlockedContentAware(
                TELEGRAM_SENTINEL, present(TELEGRAM_SENTINEL),
                firstLine(mapOf(TELEGRAM_SENTINEL to "stopped by user 2026-08-02"))
            )
        )
    }

    @Test
    fun contentAware_allowsMachineStopText() {
        // The update sweep's write-if-absent "stopAllDaemons" and the ACC-off sweep are machine
        // stops, not user stops — a pref-enabled daemon must start.
        assertFalse(
            DaemonStopGate.isStartBlockedContentAware(
                TELEGRAM_SENTINEL, present(TELEGRAM_SENTINEL),
                firstLine(mapOf(TELEGRAM_SENTINEL to "stopAllDaemons before update"))
            )
        )
        assertFalse(
            DaemonStopGate.isStartBlockedContentAware(
                TELEGRAM_SENTINEL, present(TELEGRAM_SENTINEL),
                firstLine(mapOf(TELEGRAM_SENTINEL to "ACC-on edge"))
            )
        )
    }

    @Test
    fun contentAware_unreadableSentinelTreatedAsUserStop() {
        // Present but unreadable (null first line) → block, matching the old `grep` echoing STOPPED.
        assertTrue(
            DaemonStopGate.isStartBlockedContentAware(
                TELEGRAM_SENTINEL, present(TELEGRAM_SENTINEL),
                firstLine(mapOf(TELEGRAM_SENTINEL to null))
            )
        )
    }

    // ---- the two policies must not be interchangeable ----

    @Test
    fun theTwoPoliciesDifferOnTheMarker() {
        val markerOnly = present(MARKER)
        assertFalse(
            "startup must ignore the park marker",
            DaemonStopGate.isStartBlocked(SENTINEL, markerOnly)
        )
        assertTrue(
            "health check must honour the park marker",
            DaemonStopGate.isRelaunchBlocked(SENTINEL, MARKER, markerOnly)
        )
    }
}
