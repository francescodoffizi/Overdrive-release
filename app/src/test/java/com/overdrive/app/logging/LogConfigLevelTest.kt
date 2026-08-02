package com.overdrive.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Severity-gate policy.
 *
 * Before [LogConfig.minLevel] existed, `LogManager.log()` gated only on the enable* flags
 * and never on severity, so a `.debug()` call wrote to disk exactly like an `.info()` one —
 * the level was purely a logcat priority. These tests pin the property that fixed it:
 * DEBUG is off unless someone asks for it, and asking for it turns it fully on.
 *
 * Deliberately tested on [LogConfig] rather than through LogManager: LogManager is a
 * file-writing singleton that calls android.util.Log, neither of which belongs in a
 * device-free unit test.
 */
class LogConfigLevelTest {

    @Test
    fun defaultSuppressesDebug() {
        // The whole point: verbose per-command tracing costs nothing out of the box.
        assertFalse(LogConfig().emits(LogLevel.DEBUG))
    }

    @Test
    fun defaultEmitsInfoAndAbove() {
        val cfg = LogConfig()
        assertTrue(cfg.emits(LogLevel.INFO))
        assertTrue(cfg.emits(LogLevel.WARN))
        assertTrue(cfg.emits(LogLevel.ERROR))
    }

    @Test
    fun loweringToDebugEmitsEverything() {
        // "when we need it" — this is the switch a field debug session flips.
        val cfg = LogConfig(minLevel = LogLevel.DEBUG)
        LogLevel.values().forEach { assertTrue("$it must be emitted", cfg.emits(it)) }
    }

    @Test
    fun raisingTheGateSuppressesEverythingBelowIt() {
        val cfg = LogConfig(minLevel = LogLevel.WARN)
        assertFalse(cfg.emits(LogLevel.DEBUG))
        assertFalse(cfg.emits(LogLevel.INFO))
        assertTrue(cfg.emits(LogLevel.WARN))
        assertTrue(cfg.emits(LogLevel.ERROR))
    }

    @Test
    fun errorIsNeverSuppressed() {
        // A gate that can silence ERROR is a gate that hides the thing you most need.
        LogLevel.values().forEach { gate ->
            assertTrue(
                "minLevel=$gate must still emit ERROR",
                LogConfig(minLevel = gate).emits(LogLevel.ERROR)
            )
        }
    }

    @Test
    fun enumOrderIsTheSeverityOrder() {
        // emits() compares ordinals, so a reordering of the enum would silently invert
        // the policy — DEBUG first is load-bearing, not cosmetic.
        assertEquals(0, LogLevel.DEBUG.ordinal)
        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            LogLevel.values().toList()
        )
    }

    @Test
    fun theGateIsIndependentOfTheSinkFlags() {
        // Severity and destination are orthogonal: a suppressed line is suppressed even
        // with both sinks on, and an emitted one is still subject to the sink flags.
        val loud = LogConfig(enableConsoleLog = true, enableFileLog = false)
        assertFalse(loud.emits(LogLevel.DEBUG))
        assertTrue(loud.emits(LogLevel.INFO))
    }
}
