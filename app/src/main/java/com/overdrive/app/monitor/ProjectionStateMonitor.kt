package com.overdrive.app.monitor

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Monitors whether a high-throughput projection app (CarPlay, Android Auto)
 * or full-screen vehicle camera view (360 Panorama, Reverse Camera) is in the foreground.
 *
 * When projection is active, overlay windows (TYPE_APPLICATION_OVERLAY) force SurfaceFlinger
 * to composite transparent hardware layers on top of a 60fps video projection stream,
 * which exhausts Qualcomm Adreno GPU memory pools and leads to fatal SIGSEGV in
 * libadreno_utils.so (validate_resource_memory_layout_metadata).
 *
 * Overlay services subscribe here to gracefully detach their windows while projection is active
 * and re-attach when returning to the home screen or other apps.
 */
object ProjectionStateMonitor {

    private const val TAG = "ProjectionMonitor"

    /**
     * Packages that require overlay suppression to prevent GPU memory exhaustion.
     */
    private val PROJECTION_PACKAGE_PREFIXES = listOf(
        "com.ts.carplay",                      // BYD DiLink Apple CarPlay
        "com.ts.androidauto",                   // BYD DiLink Android Auto
        "com.google.android.projection.gearhead", // Android Auto standalone / AAOS
        "com.byd.panoramic",                    // BYD 360 Panoramic Camera
        "com.byd.backcamera",                   // BYD Reverse Camera
        "com.byd.carcamera"                     // BYD Dashcam preview fullscreen
    )

    fun interface Listener {
        fun onProjectionStateChanged(active: Boolean, packageName: String?)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isProjectionActive: Boolean = false
        private set

    @Volatile
    var activeProjectionPackage: String? = null
        private set

    fun addListener(listener: Listener) {
        listeners.add(listener)
        // Immediately notify the newly registered listener with current state
        val active = isProjectionActive
        val pkg = activeProjectionPackage
        mainHandler.post {
            listener.onProjectionStateChanged(active, pkg)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Called by KeepAliveAccessibilityService whenever TYPE_WINDOW_STATE_CHANGED fires.
     */
    @JvmStatic
    fun onForegroundPackageChanged(packageName: String?) {
        val trimmedPkg = packageName?.trim()?.takeIf { it.isNotEmpty() }
        val matchesProjection = trimmedPkg != null && PROJECTION_PACKAGE_PREFIXES.any { prefix ->
            trimmedPkg.startsWith(prefix)
        }

        val wasActive = isProjectionActive
        if (matchesProjection != wasActive || (matchesProjection && trimmedPkg != activeProjectionPackage)) {
            isProjectionActive = matchesProjection
            activeProjectionPackage = if (matchesProjection) trimmedPkg else null

            Log.i(
                TAG,
                "Projection state changed: active=$matchesProjection, pkg=$trimmedPkg" +
                        if (matchesProjection) " — suppressing overlays" else " — restoring overlays"
            )

            // Dispatch to listeners on the main looper for safe UI/WindowManager access
            mainHandler.post {
                for (listener in listeners) {
                    try {
                        listener.onProjectionStateChanged(matchesProjection, activeProjectionPackage)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Listener threw in onProjectionStateChanged: ${t.message}", t)
                    }
                }
            }
        }
    }
}
