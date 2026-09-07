package com.overdrive.app.daemon.sentry

import com.overdrive.app.logging.DaemonLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Controls ACC (Accessory) mode monitoring for sentry mode.
 * 
 * Monitors sys.accanim.status property to detect ACC ON/OFF transitions.
 * When ACC goes OFF, triggers sentry mode entry.
 * 
 * Extracted from SentryDaemon for better separation of concerns.
 */
class AccMonitorController(
    private val onAccOff: () -> Unit,
    private val onAccOn: () -> Unit
) {
    
    companion object {
        private val logger = DaemonLogger.getInstance("AccMonitorController")
        
        // Power levels from BYDAutoBodyworkDevice
        const val POWER_LEVEL_OFF = 0
        const val POWER_LEVEL_ACC = 1
        const val POWER_LEVEL_ON = 2
        const val POWER_LEVEL_OK = 3
        // Adaptive polling intervals
        const val POLL_INTERVAL_SENTRY_MS = 3_000L   // 3s when car is OFF / parked in sentry
        const val POLL_INTERVAL_ACTIVE_MS = 1_500L   // 1.5s when car is ON / driving
    }
    
    @Volatile
    private var running = true
    
    @Volatile
    private var lastAccAnimStatus = "0"
    
    private var pollingThread: Thread? = null
    
    /**
     * Start polling mode for ACC status monitoring.
     */
    fun startPolling() {
        logger.info("Starting polling mode (adaptive throttle: ${POLL_INTERVAL_SENTRY_MS}ms sentry / ${POLL_INTERVAL_ACTIVE_MS}ms active)...")
        
        // Log initial state
        logAllPowerSources()
        
        pollingThread = Thread({
            var pollCount = 0
            
            // Get initial state - treat empty/"0" as ACC ON
            lastAccAnimStatus = execShell("getprop sys.accanim.status").trim()
            if (lastAccAnimStatus.isEmpty()) {
                lastAccAnimStatus = "0" // Empty means ACC ON
            }
            logger.info("Initial sys.accanim.status: '$lastAccAnimStatus' (0 or empty = ACC ON)")
            
            // If we start with ACC already OFF (status != 0), enter sentry mode
            if (lastAccAnimStatus != "0") {
                logger.info("Started with ACC OFF (status=$lastAccAnimStatus) - entering sentry mode")
                onAccOff()
            } else {
                logger.info("Started with ACC ON - waiting for ACC OFF event...")
            }
            
            var isCurrentlyAccOff = (lastAccAnimStatus != "0")
            var lastCarServiceCheckMs = 0L

            while (running) {
                try {
                    val sleepInterval = if (isCurrentlyAccOff) POLL_INTERVAL_SENTRY_MS else POLL_INTERVAL_ACTIVE_MS
                    Thread.sleep(sleepInterval)
                    pollCount++
                    
                    // Diagnostic logging: reduced frequency to once every ~10 minutes
                    if (pollCount % 200 == 0) {
                        logAllPowerSources()
                    }
                    
                    // 1. Check Display Power & Interactive State (prefer direct Binder call, zero forks)
                    val isScreenOff = checkScreenOff()

                    // 2. Fast property check
                    var accAnimStatus = execShell("getprop sys.accanim.status").trim()
                    var isStandby = (accAnimStatus == "1" || accAnimStatus == "2")

                    if (accAnimStatus.isEmpty()) {
                        accAnimStatus = if (isScreenOff || isStandby) "1" else "0"
                    }

                    // Combined ACC OFF logic: if screen is OFF, car in Standby, or accanim.status is 1
                    val isAccOffNow = (accAnimStatus != "0") || isScreenOff || isStandby
                    val wasAccOff = (lastAccAnimStatus != "0")

                    if (isAccOffNow != wasAccOff) {
                        logger.info(">>> ACC STATE CHANGED: isAccOffNow=$isAccOffNow (wasAccOff=$wasAccOff, isStandby=$isStandby, screenOff=$isScreenOff, accAnim=$accAnimStatus)")
                        if (isAccOffNow) {
                            logger.info("!!! ACC OFF DETECTED (Standby/ScreenOff) -> ENTER SENTRY !!!")
                            onAccOff()
                            scheduleWifiRearm()
                        } else {
                            logger.info("!!! ACC ON DETECTED -> EXIT SENTRY !!!")
                            onAccOn()
                        }
                        lastAccAnimStatus = if (isAccOffNow) "1" else "0"
                        isCurrentlyAccOff = isAccOffNow
                        logAllPowerSources() // Log full snapshot on actual state change
                    }
                    
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    logger.error("Polling error: ${e.message}")
                    try { Thread.sleep(1000) } catch (ignored: Exception) {}
                }
            }
        }, "PowerLevelPoller")
        
        pollingThread?.start()
    }
    
    /**
     * Check if display is OFF or non-interactive.
     * Uses direct PowerManager Binder call (0 forks, 0 dumpsys).
     */
    private fun checkScreenOff(): Boolean {
        try {
            val ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext()
            if (ctx != null) {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                if (pm != null) {
                    return !pm.isInteractive
                }
            }
        } catch (t: Throwable) {
        }
        return false
    }
    
    /**
     * Stop polling.
     */
    fun stopPolling() {
        running = false
        pollingThread?.interrupt()
        pollingThread = null
    }

    /**
     * Log power state from lightweight system properties for debugging.
     */
    private fun logAllPowerSources() {
        try {
            val accAnimStatus = execShell("getprop sys.accanim.status").trim()
            val accAnimSvc = execShell("getprop init.svc.accanim").trim()
            val bootCompleted = execShell("getprop sys.boot_completed").trim()
            logger.info("=== Power State Snapshot === sys.accanim.status=$accAnimStatus, init.svc.accanim=$accAnimSvc, boot=$bootCompleted, screenOff=${checkScreenOff()}")
        } catch (e: Exception) {
            logger.warn("Error capturing power snapshot: ${e.message}")
        }
    }
    
    /**
     * Convert power level to human-readable string.
     */
    fun powerLevelToString(level: Int): String {
        return when (level) {
            0 -> "OFF(0)"
            1 -> "ACC(1)"
            2 -> "ON(2)"
            3 -> "OK(3)"
            4 -> "FAKE_OK(4)"
            255 -> "INVALID(255)"
            else -> "UNKNOWN($level)"
        }
    }
    
    /**
     * Execute shell command.
     */
    private fun execShell(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            process.waitFor()
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            output.toString().trim()
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun scheduleWifiRearm() {
        Thread({
            try {
                Thread.sleep(2500)
                logger.info("ACC-OFF: re-arming Wi-Fi subsystem to counteract BYD TsCarPower turnOffWifi...")
                execShell("cmd wifi set-wifi-enabled enabled 2>/dev/null || svc wifi enable 2>/dev/null")
            } catch (t: Throwable) {
                logger.warn("scheduleWifiRearm error: ${t.message}")
            }
        }, "WifiRearmThread").start()
    }
}
