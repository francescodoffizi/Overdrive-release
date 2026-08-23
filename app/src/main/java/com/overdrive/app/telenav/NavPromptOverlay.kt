package com.overdrive.app.telenav

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.overdrive.app.config.UnifiedConfigManager
import kotlin.math.abs

/**
 * Floating "Navigate to X? [Cancel] [Yes]" prompt drawn over everything via the
 * granted SYSTEM_ALERT_WINDOW (TYPE_APPLICATION_OVERLAY). Shown in the APP process
 * by [TelenavIpcServer] when the daemon's [DeferredNavManager] reports, on ACC-on,
 * a target that was queued while the car was off.
 *
 * Draggable by its header (position persisted to config so it reappears where the
 * user left it). Auto-dismisses after 20s. "Yes" runs the exact live navigate-here
 * path ([TelenavActions.navigate] with replace = fresh route). Must be shown on the
 * main thread.
 */
object NavPromptOverlay {

    private const val TAG = "NavPromptOverlay"
    private const val AUTO_DISMISS_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }
    private var view: View? = null
    private var wm: WindowManager? = null

    @JvmStatic
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, name: String, lat: Double, lng: Double) {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "no draw-overlay permission — cannot show prompt")
            return
        }
        dismiss() // never stack two

        fun dp(v: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
        ).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F02A2A2E"))
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
        }
        val title = TextView(ctx).apply {
            text = "Navigate in the car?"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        val body = TextView(ctx).apply {
            text = name
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = Button(ctx).apply {
            text = "Cancel"
            setOnClickListener { dismiss() }
        }
        val yes = Button(ctx).apply {
            text = "Yes, navigate"
            setOnClickListener {
                dismiss()
                Thread {
                    try {
                        TelenavActions.navigate(ctx, name, lat, lng, true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "navigate failed: ${t.message}")
                    }
                }.start()
            }
        }
        row.addView(
            cancel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(6) },
        )
        row.addView(
            yes,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) },
        )
        card.addView(title)
        card.addView(body)
        card.addView(row)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            val pos = runCatching { UnifiedConfigManager.getNavPromptPos() }.getOrNull()
            if (pos != null) {
                gravity = Gravity.TOP or Gravity.START
                x = pos.first
                y = pos.second
            } else {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(80)
            }
        }

        // Drag by the header (title + body). Buttons keep their own clicks because the
        // drag listener is only on these two views, not the button row.
        val dragListener = object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downRawX = 0f
            private var downRawY = 0f
            private var dragging = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        downRawX = e.rawX; downRawY = e.rawY
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downRawX).toInt()
                        val dy = (e.rawY - downRawY).toInt()
                        if (!dragging && (abs(dx) > dp(6) || abs(dy) > dp(6))) dragging = true
                        if (dragging) {
                            lp.gravity = Gravity.TOP or Gravity.START
                            lp.x = startX + dx
                            lp.y = startY + dy
                            runCatching { wm?.updateViewLayout(card, lp) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) runCatching { UnifiedConfigManager.setNavPromptPos(lp.x, lp.y) }
                        return true
                    }
                }
                return false
            }
        }
        title.setOnTouchListener(dragListener)
        body.setOnTouchListener(dragListener)

        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm?.addView(card, lp)
            view = card
            main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
            Log.i(TAG, "prompt shown for '$name'")
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed: ${t.message}")
            view = null
        }
    }

    @JvmStatic
    fun dismiss() {
        main.removeCallbacks(dismissRunnable)
        val v = view ?: return
        runCatching { wm?.removeView(v) }
        view = null
    }
}
