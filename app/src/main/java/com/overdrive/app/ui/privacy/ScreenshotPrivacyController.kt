package com.overdrive.app.ui.privacy

import android.app.Activity
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.overdrive.app.config.UnifiedConfigManager
import kotlin.math.max

/**
 * Draws a non-interactive, fuzzy privacy layer above sensitive native views.
 *
 * The source views keep their real values and remain interactive. Only the
 * pixels presented on screen (and therefore in screenshots) are obscured.
 * Matching is deliberately centralised so newly-added URL, location, QR,
 * recording-thumbnail, or camera-preview views inherit the same behaviour.
 */
class ScreenshotPrivacyController(private val activity: Activity) {
    private val contentRoot: ViewGroup by lazy {
        activity.findViewById(android.R.id.content)
    }
    private val overlay = PrivacyOverlayView(activity)
    private var started = false

    fun start() {
        if (started) return
        started = true
        contentRoot.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setEnabled(UnifiedConfigManager.isScreenshotPrivacyModeEnabled())
    }

    fun setEnabled(enabled: Boolean) {
        overlay.setPrivacyEnabled(enabled)
    }

    fun stop() {
        if (!started) return
        overlay.setPrivacyEnabled(false)
        if (overlay.parent === contentRoot) contentRoot.removeView(overlay)
        started = false
    }

    private inner class PrivacyOverlayView(activity: Activity) : View(activity) {
        private val density = resources.displayMetrics.density
        private val scanIntervalMs = 350L
        private val maskRects = ArrayList<RectF>()
        private var privacyEnabled = false

        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 24, 30, 36)
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(10f * density, BlurMaskFilter.Blur.NORMAL)
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 36, 43)
            style = Paint.Style.FILL
        }
        private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(62, 153, 166, 178)
            strokeWidth = max(1f, density)
            style = Paint.Style.STROKE
        }

        private val scan = object : Runnable {
            override fun run() {
                if (!privacyEnabled || !isAttachedToWindow) return
                refreshRects()
                postDelayed(this, scanIntervalMs)
            }
        }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        fun setPrivacyEnabled(enabled: Boolean) {
            if (privacyEnabled == enabled) {
                if (enabled) refreshRects()
                return
            }
            privacyEnabled = enabled
            removeCallbacks(scan)
            visibility = if (enabled) VISIBLE else GONE
            if (enabled) {
                bringToFront()
                refreshRects()
                postDelayed(scan, scanIntervalMs)
            } else {
                maskRects.clear()
                invalidate()
            }
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(scan)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!privacyEnabled) return
            val radius = 9f * density
            val stripeStep = 9f * density
            for (rect in maskRects) {
                canvas.drawRoundRect(rect, radius, radius, edgePaint)
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                val checkpoint = canvas.save()
                val clipPath = Path().apply {
                    addRoundRect(rect, radius, radius, Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
                var x = rect.left - rect.height()
                while (x < rect.right) {
                    canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, texturePaint)
                    x += stripeStep
                }
                canvas.restoreToCount(checkpoint)
            }
        }

        private fun refreshRects() {
            if (width <= 0 || height <= 0) return
            val rootOnScreen = IntArray(2)
            getLocationOnScreen(rootOnScreen)
            val found = ArrayList<RectF>()
            collectSensitiveViews(contentRoot, rootOnScreen, found)

            found.sortByDescending { it.width() * it.height() }
            val deduped = ArrayList<RectF>()
            for (candidate in found) {
                if (deduped.none { contains(it, candidate) }) deduped.add(candidate)
            }
            if (sameRects(maskRects, deduped)) return
            maskRects.clear()
            maskRects.addAll(deduped)
            invalidate()
        }

        private fun collectSensitiveViews(
            view: View,
            rootOnScreen: IntArray,
            result: MutableList<RectF>
        ) {
            if (view === this || view.visibility != VISIBLE || !view.isShown || view.alpha <= 0.02f) {
                return
            }

            val resourceName = resourceEntryName(view)
            val namedSensitive = ScreenshotPrivacyPolicy.isSensitiveResourceName(resourceName)
            val textSensitive = view is TextView &&
                ScreenshotPrivacyPolicy.isSensitiveText(view.text)
            val descriptionSensitive = ScreenshotPrivacyPolicy.isSensitiveText(view.contentDescription) ||
                ScreenshotPrivacyPolicy.isSensitiveResourceName(view.contentDescription?.toString())

            if (namedSensitive || textSensitive || descriptionSensitive) {
                visibleRect(view, rootOnScreen)?.let(result::add)
                // A sensitive container is already covered; avoid stacking a
                // second blur over each of its children.
                if (view is ViewGroup) return
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    collectSensitiveViews(view.getChildAt(index), rootOnScreen, result)
                }
            }
        }

        private fun resourceEntryName(view: View): String? {
            if (view.id == NO_ID) return null
            return try {
                view.resources.getResourceEntryName(view.id)
            } catch (_: Exception) {
                null
            }
        }

        private fun visibleRect(view: View, rootOnScreen: IntArray): RectF? {
            val global = Rect()
            if (!view.getGlobalVisibleRect(global) || global.isEmpty) return null
            val pad = 4f * density
            val left = (global.left - rootOnScreen[0]).toFloat() - pad
            val top = (global.top - rootOnScreen[1]).toFloat() - pad
            val right = (global.right - rootOnScreen[0]).toFloat() + pad
            val bottom = (global.bottom - rootOnScreen[1]).toFloat() + pad
            return RectF(
                left.coerceAtLeast(0f),
                top.coerceAtLeast(0f),
                right.coerceAtMost(width.toFloat()),
                bottom.coerceAtMost(height.toFloat())
            ).takeUnless { it.isEmpty }
        }

        private fun contains(outer: RectF, inner: RectF): Boolean =
            outer.left <= inner.left && outer.top <= inner.top &&
                outer.right >= inner.right && outer.bottom >= inner.bottom

        private fun sameRects(a: List<RectF>, b: List<RectF>): Boolean {
            if (a.size != b.size) return false
            return a.indices.all { a[it] == b[it] }
        }
    }
}
