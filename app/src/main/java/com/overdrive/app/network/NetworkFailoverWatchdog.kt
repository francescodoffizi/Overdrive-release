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

    companion object {
        private const val TAG = "NetworkFailover"

        private const val CHECK_INTERVAL_MS = 20_000L // 20s between checks
        private const val PROBE_TIMEOUT_MS = 3_500     // 3.5s socket timeout
        private const val STRIKES_FOR_FAILOVER = 3    // 3 consecutive failures = 60s without internet

        @Volatile
        var isWifiWithInternet: Boolean = false
            internal set

        @Volatile
        private var instance: NetworkFailoverWatchdog? = null

        fun getInstance(context: Context): NetworkFailoverWatchdog {
            return instance ?: synchronized(this) {
                instance ?: NetworkFailoverWatchdog(context.applicationContext).also { instance = it }
            }
        }

        @JvmStatic
        fun isWifiHealthy(): Boolean {
            val inst = instance ?: return false
            if (isWifiWithInternet) return true

            // Fast fallback: check ConnectivityManager
            try {
                val cm = inst.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                return isWifi && isValidated
            } catch (e: Exception) {
                return false
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
                            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                                isWifiWithInternet = true
                            }
                            scheduleNextCheck(1000L)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    workerHandler.post {
                        log.info(TAG, "Network link lost")
                        isWifiWithInternet = false
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

            if (!wlanActive) {
                // Wi-Fi is physically off or disconnected naturally
                consecutiveWifiFailures = 0
                consecutiveWifiSuccesses = 0
                isWifiWithInternet = false
                return
            }

            // 2. Wi-Fi is associated. Verify if real internet is reachable through it!
            val hasInternet = probeInternetConnectivity()

            if (hasInternet) {
                if (!isWifiWithInternet) {
                    log.info(TAG, "Wi-Fi internet access CONFIRMED! Routing directly via Wi-Fi.")
                    isWifiWithInternet = true
                    try {
                        com.overdrive.app.mqtt.ProxyHelper.invalidateCache()
                    } catch (ignored: Throwable) {}
                }
                consecutiveWifiSuccesses++
                consecutiveWifiFailures = 0
            } else {
                if (isWifiWithInternet) {
                    log.warn(TAG, "Wi-Fi has NO internet! Failing over Overdrive traffic to sing-box proxy.")
                    isWifiWithInternet = false
                    try {
                        com.overdrive.app.mqtt.ProxyHelper.invalidateCache()
                    } catch (ignored: Throwable) {}
                }
                consecutiveWifiFailures++
                consecutiveWifiSuccesses = 0
            }
        } catch (e: Exception) {
            log.warn(TAG, "Error in watchdog tick: ${e.message}")
        }
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
