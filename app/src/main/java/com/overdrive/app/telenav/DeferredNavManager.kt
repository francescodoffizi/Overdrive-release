package com.overdrive.app.telenav

import android.util.Log
import com.overdrive.app.config.UnifiedConfigManager
import com.overdrive.app.daemon.sentry.AccMonitorController
import com.overdrive.app.monitor.GearMonitor
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Deferred "navigate here". A phone / on-car "Navigate here" that arrives while the
 * car is off (ACC off) would otherwise be a silent no-op — guidance only engages
 * while Telenav is foregrounded, and the screen is off. So instead we store the
 * latest such target and, on the next ACC-on, offer it as a floating prompt.
 *
 * Runs entirely in the daemon process (byd_cam_daemon), started once from
 * [com.overdrive.app.daemon.CameraDaemon]. It uses its OWN [AccMonitorController]
 * (the same `sys.accanim.status` signal the surveillance side and the automation
 * engine watch) so it is fully isolated from the surveillance ACC state machine.
 * Gear comes from the daemon's [GearMonitor]; the overlay itself lives in the APP
 * process (Telenav bind + SYSTEM_ALERT_WINDOW), reached over 127.0.0.1:19878.
 */
object DeferredNavManager {

    private const val TAG = "DeferredNav"
    private const val SHOW_DELAY_MS = 5_000L        // let the launcher/Telenav settle after power-on
    private const val REVERSE_WAIT_MAX_MS = 30_000L // hold the prompt while reversing (rear cam)
    private const val REVERSE_POLL_MS = 1_000L

    @Volatile private var started = false
    private var accMonitor: AccMonitorController? = null

    /** Start the ACC watcher. Idempotent; safe to call from daemon boot. */
    @JvmStatic
    fun start() {
        if (started) return
        try {
            accMonitor = AccMonitorController(onAccOff = {}, onAccOn = { onAccOn() })
                .also { it.startPolling() }
            started = true
            Log.i(TAG, "started (ACC watcher for deferred navigate)")
        } catch (t: Throwable) {
            Log.w(TAG, "start failed: ${t.message}")
        }
    }

    /** Store the latest target received while the car is off. Called by the endpoint. */
    @JvmStatic
    fun storePending(name: String?, lat: Double, lng: Double) {
        try {
            UnifiedConfigManager.setPendingNav(name ?: "", lat, lng, System.currentTimeMillis())
            Log.i(TAG, "queued pending navigate: '$name' ($lat,$lng)")
        } catch (t: Throwable) {
            Log.w(TAG, "storePending failed: ${t.message}")
        }
    }

    private fun onAccOn() {
        // Off the ACC poller thread; this waits and does IPC.
        Thread({
            try {
                val d = UnifiedConfigManager.getDeferredNav()
                if (!d.optBoolean("pending", false)) return@Thread

                val receivedAt = d.optLong("receivedAt", 0L)
                val ageMs = System.currentTimeMillis() - receivedAt
                val maxMs = UnifiedConfigManager.getDeferredNavExpiryHours() * 3_600_000L
                if (receivedAt <= 0L || ageMs > maxMs) {
                    Log.i(TAG, "pending expired (age ${ageMs / 60_000}m > ${maxMs / 3_600_000}h) — clearing")
                    UnifiedConfigManager.clearPendingNav()
                    return@Thread
                }

                Thread.sleep(SHOW_DELAY_MS)

                // Don't pop over the reversing camera — wait until out of R (brief cap).
                var waited = 0L
                while (GearMonitor.getInstance().currentGear == GearMonitor.GEAR_R &&
                    waited < REVERSE_WAIT_MAX_MS
                ) {
                    Thread.sleep(REVERSE_POLL_MS)
                    waited += REVERSE_POLL_MS
                }
                if (GearMonitor.getInstance().currentGear == GearMonitor.GEAR_R) {
                    Log.i(TAG, "still in reverse after ${REVERSE_WAIT_MAX_MS / 1000}s — skipping, clearing")
                    UnifiedConfigManager.clearPendingNav()
                    return@Thread
                }

                val name = d.optString("name", "Shared location")
                val lat = d.optDouble("lat", Double.NaN)
                val lng = d.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) {
                    UnifiedConfigManager.clearPendingNav()
                    return@Thread
                }

                val shown = sendShowPrompt(name, lat, lng)
                // One-shot: clear once handed to the app (offered on the next start, not every start).
                UnifiedConfigManager.clearPendingNav()
                Log.i(TAG, "ACC-on: prompt ${if (shown) "shown" else "send-failed"}; pending cleared")
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                Log.w(TAG, "onAccOn failed: ${t.message}")
            }
        }, "deferrednav-accon").start()
    }

    /** Ask the APP process to draw the prompt overlay. Mirrors TelenavDebugApiHandler.forwardToApp. */
    private fun sendShowPrompt(name: String, lat: Double, lng: Double): Boolean {
        var socket: Socket? = null
        return try {
            val req = JSONObject()
                .put("op", "showNavPrompt")
                .put("name", name)
                .put("lat", lat)
                .put("lng", lng)
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", TelenavIpcServer.PORT), 2000)
            s.soTimeout = 8000
            socket = s
            PrintWriter(s.getOutputStream(), true).println(req.toString())
            val resp = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
            resp != null && resp.contains("\"success\":true")
        } catch (e: Exception) {
            Log.w(TAG, "sendShowPrompt failed: ${e.message}")
            false
        } finally {
            try { socket?.close() } catch (ignore: Exception) {}
        }
    }
}
