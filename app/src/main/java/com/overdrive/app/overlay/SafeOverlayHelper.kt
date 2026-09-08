package com.overdrive.app.overlay

import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.overdrive.app.monitor.ProjectionStateMonitor

/**
 * Centralized helper for managing WindowManager overlay windows safely across the SA8155P platform.
 *
 * On Qualcomm Adreno 640 (SA8155P Automotive Cockpit), hardware-accelerated overlay windows
 * with PixelFormat.TRANSLUCENT force SurfaceFlinger (GLESRenderEngine) to allocate EGL images
 * on top of live camera DMA-BUF / GraphicBuffers. This triggers a known vendor bug in
 * libadreno_utils.so (validate_resource_memory_layout_metadata -> SIGSEGV).
 *
 * SafeOverlayHelper strips FLAG_HARDWARE_ACCELERATED, forces View.LAYER_TYPE_SOFTWARE,
 * and guards against displaying overlays when high-throughput projection/camera layers
 * (CarPlay, Android Auto, BYD 360 Panoramic) are active.
 */
object SafeOverlayHelper {

    private const val TAG = "SafeOverlayHelper"

    /**
     * Prepares WindowManager.LayoutParams and View to ensure pure software canvas rendering
     * without initializing an Adreno EGL context in SurfaceFlinger.
     */
    @JvmStatic
    fun sanitizeOverlay(view: View, lp: WindowManager.LayoutParams) {
        // Strip hardware acceleration flag
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED.inv()

        // Force software layer rendering on root view and children
        try {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set LAYER_TYPE_SOFTWARE: ${t.message}")
        }
    }

    /**
     * Resolves the proper window type for application overlays.
     */
    @JvmStatic
    fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
    }

    /**
     * Checks if it is safe to display an overlay (no CarPlay, 360 camera, etc. in foreground).
     */
    @JvmStatic
    fun isSafeToDisplay(): Boolean {
        return !ProjectionStateMonitor.isProjectionActive
    }

    /**
     * Safely adds a view to WindowManager after sanitizing flags and rendering type.
     */
    @JvmStatic
    fun safeAddView(wm: WindowManager?, view: View?, lp: WindowManager.LayoutParams?): Boolean {
        if (wm == null || view == null || lp == null) {
            Log.w(TAG, "safeAddView: null arguments (wm=$wm, view=$view, lp=$lp)")
            return false
        }
        sanitizeOverlay(view, lp)
        return try {
            wm.addView(view, lp)
            Log.d(TAG, "safeAddView succeeded for view=${view.javaClass.simpleName}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "safeAddView failed: ${t.message}", t)
            false
        }
    }

    /**
     * Safely updates a view layout in WindowManager.
     */
    @JvmStatic
    fun safeUpdateViewLayout(wm: WindowManager?, view: View?, lp: WindowManager.LayoutParams?): Boolean {
        if (wm == null || view == null || lp == null) return false
        sanitizeOverlay(view, lp)
        return try {
            wm.updateViewLayout(view, lp)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "safeUpdateViewLayout failed: ${t.message}")
            false
        }
    }

    /**
     * Safely removes a view from WindowManager.
     */
    @JvmStatic
    fun safeRemoveView(wm: WindowManager?, view: View?): Boolean {
        if (wm == null || view == null) return false
        return try {
            wm.removeView(view)
            Log.d(TAG, "safeRemoveView succeeded for view=${view.javaClass.simpleName}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "safeRemoveView failed: ${t.message}")
            false
        }
    }
}
