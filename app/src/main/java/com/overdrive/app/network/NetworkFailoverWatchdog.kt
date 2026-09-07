package com.overdrive.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import com.overdrive.app.logging.LogManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watchdog for robust network failover between external Wi-Fi (e.g. mobile 4G hotspot/saponetta)
 * and the internal BYD SIM (rmnet_data0 via sing-box).
 *
 * Problems Solved:
 * 1. "Wi-Fi Zombie": When an in-car 4G Wi-Fi hotspot loses cell signal or freezes, it remains
 *    powered via USB and broadcasts its SSID. Android remains connected to wlan0 as the default
 *    route, trapping all traffic in a black hole and preventing fallback to the internal BYD SIM.
 *    This watchdog detects real internet loss on Wi-Fi and forces a temporary disconnection
 *    (`cmd wifi disconnect`) so Android immediately promotes mobile data (rmnet_data0).
 *
 * 2. Resilient Failback: Periodically tests whether the Wi-Fi hotspot has recovered internet
 *    connectivity (`cmd wifi reconnect`), with exponential backoff and 2-strike anti-flapping hysteresis.
 *
 * 3. Network Change Trigger: Reacts to network transition events to ensure proxy sockets settle cleanly.
 */
class NetworkFailoverWatchdog private constructor(private val context: Context) {

    private val log = LogManager.getInstance()
    private val isRunning = AtomicBoolean(false)

    private val workerThread = HandlerThread("NetworkFailoverWatchdog").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    // State tracking
    private var isWifiAssociated = false
    private var consecutiveWifiFailures = 0
    private var consecutiveWifiSuccesses = 0
    private var isForcedDisconnected = false

    // Recovery backoff settings (in ms)
    private var recoveryBackoffMs = INITIAL_RECOVERY_INTERVAL_MS

    companion object {
        private const val TAG = "NetworkFailover"

        private const val CHECK_INTERVAL_MS = 20_000L // 20s between checks
        private const val PROBE_TIMEOUT_MS = 3_500     // 3.5s socket timeout
        private const val STRIKES_FOR_DISCONNECT = 3   // 3 consecutive failures = 60s
        private const val STRIKES_FOR_RECOVERY = 2     // 2 consecutive successes to confirm stable WLAN

        private const val INITIAL_RECOVERY_INTERVAL_MS = 120_000L // 2 minutes initial backoff
        private const val MAX_RECOVERY_INTERVAL_MS = 300_000L     // 5 minutes max backoff

        @Volatile
        private var instance: NetworkFailoverWatchdog? = null

        fun getInstance(context: Context): NetworkFailoverWatchdog {
            return instance ?: synchronized(this) {
                instance ?: NetworkFailoverWatchdog(context.applicationContext).also { instance = it }
            }
        }
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        log.info(TAG, "Starting NetworkFailoverWatchdog...")
        registerNetworkCallback()
        scheduleNextCheck(5000L)
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        log.info(TAG, "Stopping NetworkFailoverWatchdog...")
        workerHandler.removeCallbacksAndMessages(null)
    }

    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    workerHandler.post {
                        if (isWifi) {
                            log.info(TAG, "Wi-Fi link became available")
                            isWifiAssociated = true
                            if (isForcedDisconnected) {
                                log.info(TAG, "Wi-Fi re-connected during recovery window; verifying internet...")
                            }
                        }
                    }
                }

                override fun onLost(network: Network) {
                    workerHandler.post {
                        log.info(TAG, "Network link lost")
                        // Immediate probe on next tick to adapt
                        scheduleNextCheck(2000L)
                    }
                }
            })
        } catch (e: Exception) {
            log.warn(TAG, "Failed to register ConnectivityManager callback: ${e.message}")
        }
    }

    private fun scheduleNextCheck(delayMs: Long) {
        if (!isRunning.get()) return
        workerHandler.removeCallbacks(checkRunnable)
        workerHandler.postDelayed(checkRunnable, delayMs)
    }

    private val checkRunnable = Runnable {
        runWatchdogTick()
        scheduleNextCheck(CHECK_INTERVAL_MS)
    }

    private fun runWatchdogTick() {
        try {
            // 1. Check if wlan0 interface is active
            val wlanActive = isWlanInterfaceUp()
            isWifiAssociated = wlanActive

            if (isForcedDisconnected) {
                // We are currently in forced disconnection (suppressing zombie Wi-Fi)
                // The recovery timer handles attempts to reconnect.
                return
            }

            if (!wlanActive) {
                // Wi-Fi is physically off or disconnected naturally
                consecutiveWifiFailures = 0
                consecutiveWifiSuccesses = 0
                return
            }

            // 2. Wi-Fi is associated. Verify if real internet is reachable through it!
            val hasInternet = probeInternetConnectivity()

            if (hasInternet) {
                consecutiveWifiSuccesses++
                if (consecutiveWifiFailures > 0) {
                    log.info(TAG, "Wi-Fi connectivity restored (successes=$consecutiveWifiSuccesses)")
                }
                consecutiveWifiFailures = 0
                recoveryBackoffMs = INITIAL_RECOVERY_INTERVAL_MS
            } else {
                consecutiveWifiFailures++
                consecutiveWifiSuccesses = 0
                log.warn(TAG, "Wi-Fi associated but NO internet detected (strike $consecutiveWifiFailures/$STRIKES_FOR_DISCONNECT)")

                if (consecutiveWifiFailures >= STRIKES_FOR_DISCONNECT) {
                    handleZombieWifiDetected()
                }
            }
        } catch (e: Exception) {
            log.warn(TAG, "Error in watchdog tick: ${e.message}")
        }
    }

    /**
     * Wi-Fi is stuck in "Zombie" state: associated to local hotspot with no internet gateway.
     * Force disconnect wlan0 to release Android's default route to cellular (rmnet_data0).
     */
    private fun handleZombieWifiDetected() {
        log.warn(TAG, ">>> Zombie Wi-Fi detected! Forcing disconnect to failover to internal SIM (rmnet_data0) <<<")
        isForcedDisconnected = true
        consecutiveWifiFailures = 0

        // Passive observation only; avoid invasive shell svc/cmd calls that deadlock system_server
        log.warn(TAG, "Wi-Fi link unroutable to internet, staying passive to protect system_server")

        // Schedule periodic recovery test with backoff
        log.info(TAG, "Scheduled Wi-Fi recovery probe in ${recoveryBackoffMs / 1000}s")
        workerHandler.postDelayed({
            attemptWifiRecovery()
        }, recoveryBackoffMs)

        // Increment backoff for subsequent attempts (up to MAX_RECOVERY_INTERVAL_MS)
        recoveryBackoffMs = (recoveryBackoffMs * 1.5).toLong().coerceAtMost(MAX_RECOVERY_INTERVAL_MS)
    }

    /**
     * Periodic test to see if the Wi-Fi hotspot has regained cell reception.
     */
    private fun attemptWifiRecovery() {
        if (!isRunning.get() || !isForcedDisconnected) return

        log.info(TAG, "Attempting Wi-Fi recovery (cmd wifi reconnect)...")
        execShell("cmd wifi reconnect 2>/dev/null")

        // Give the radio 12 seconds to re-associate and acquire DHCP
        workerHandler.postDelayed({
            val wlanUp = isWlanInterfaceUp()
            if (!wlanUp) {
                log.info(TAG, "Wi-Fi recovery attempt: wlan0 did not associate; staying on SIM BYD")
                scheduleNextRecoveryAttempt()
                return@postDelayed
            }

            // Probe internet on the newly reconnected Wi-Fi
            val internetOk = probeInternetConnectivity()
            if (internetOk) {
                log.info(TAG, "Wi-Fi recovery attempt: Internet REACHABLE! Validating stability (1/2)...")
                // Test second probe after 5 seconds to prevent flapping
                workerHandler.postDelayed({
                    if (probeInternetConnectivity()) {
                        log.info(TAG, ">>> Wi-Fi recovery CONFIRMED! Restoring primary WLAN operation <<<")
                        isForcedDisconnected = false
                        consecutiveWifiFailures = 0
                        consecutiveWifiSuccesses = STRIKES_FOR_RECOVERY
                        recoveryBackoffMs = INITIAL_RECOVERY_INTERVAL_MS
                    } else {
                        log.warn(TAG, "Wi-Fi recovery second strike failed; suppressing unstable Wi-Fi")
                        reDisconnectZombieWifi()
                    }
                }, 5000L)
            } else {
                log.warn(TAG, "Wi-Fi recovery attempt: wlan0 associated but STILL NO INTERNET; staying on SIM BYD")
                reDisconnectZombieWifi()
            }
        }, 12000L)
    }

    private fun reDisconnectZombieWifi() {
        execShell("cmd wifi disconnect 2>/dev/null")
        scheduleNextRecoveryAttempt()
    }

    private fun scheduleNextRecoveryAttempt() {
        log.info(TAG, "Next Wi-Fi recovery attempt in ${recoveryBackoffMs / 1000}s")
        workerHandler.postDelayed({
            attemptWifiRecovery()
        }, recoveryBackoffMs)
        recoveryBackoffMs = (recoveryBackoffMs * 1.5).toLong().coerceAtMost(MAX_RECOVERY_INTERVAL_MS)
    }

    /**
     * Probe internet connectivity using fast socket connections.
     * Uses Cloudflare (1.1.1.1:53) and Google DNS (8.8.8.8:53).
     */
    private fun probeInternetConnectivity(): Boolean {
        val targets = listOf(
            InetSocketAddress("1.1.1.1", 53),
            InetSocketAddress("8.8.8.8", 53),
            InetSocketAddress("80.225.224.92", 443) // Primary VLESS reality server
        )

        for (target in targets) {
            try {
                Socket().use { socket ->
                    socket.connect(target, PROBE_TIMEOUT_MS)
                    return true
                }
            } catch (ignored: Exception) {}
        }
        return false
    }

    private fun isWlanInterfaceUp(): Boolean {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name == "wlan0" && iface.isUp) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return true
                        }
                    }
                }
            }
        } catch (ignored: Exception) {}
        return false
    }

    private fun execShell(cmd: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (ignored: Exception) {}
    }
}
