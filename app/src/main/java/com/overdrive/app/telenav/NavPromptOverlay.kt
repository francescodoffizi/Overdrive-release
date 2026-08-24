package com.overdrive.app.telenav

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.R as MR
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.overdrive.app.R
import com.overdrive.app.config.UnifiedConfigManager
import kotlin.math.abs

/**
 * Floating "Navigate in the car?" prompt drawn over everything via the granted
 * SYSTEM_ALERT_WINDOW. Shown in the APP process by [TelenavIpcServer] when the
 * daemon's [DeferredNavManager] reports, on ACC-on, a target queued while the car
 * was off.
 *
 * Styled with OverDrive's own Material 3 theme ([R.style.Theme_Overdrive_M3]) so it
 * matches the rest of the app and flips day/night with the head unit: the views are
 * built against a ContextThemeWrapper whose configuration carries the current night
 * state, and colours are resolved from theme attributes (surface / onSurface /
 * primary / outline), not hardcoded. Buttons are MaterialButtons.
 *
 * Draggable by its header (position persisted). Auto-dismisses after 20s. "Yes" runs
 * the live navigate-here path. Must be shown on the main thread.
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
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
            Log.w(TAG, "no draw-overlay permission — cannot show prompt")
            return
        }
        dismiss() // never stack two

        // Theme the context: match OverDrive's night choice (AppCompatDelegate has no
        // Activity here, so resolve it ourselves), then wrap in Theme.Overdrive.M3 so
        // the same day/night palette as the rest of the app resolves from attributes.
        val nightYes = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val cfg = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (nightYes) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val ctx: Context = ContextThemeWrapper(
            app.createConfigurationContext(cfg), R.style.Theme_Overdrive_M3,
        )

        fun dp(v: Int): Int = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
        ).toInt()
        fun attr(a: Int, fallback: Int): Int = MaterialColors.getColor(ctx, a, fallback)

        val surface = attr(MR.attr.colorSurfaceContainer, attr(MR.attr.colorSurface, 0xFF2A2A2E.toInt()))
        val onSurface = attr(MR.attr.colorOnSurface, 0xFFFFFFFF.toInt())
        val onSurfaceVar = attr(MR.attr.colorOnSurfaceVariant, 0xFFDDDDDD.toInt())
        val outline = attr(MR.attr.colorOutlineVariant, 0x55FFFFFF)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(surface)
                setStroke(dp(1), outline)
            }
            elevation = dp(8).toFloat()
        }
        val title = TextView(ctx).apply {
            text = ctx.getString(R.string.carnav_deferred_prompt_title)
            setTextColor(onSurface)
            textSize = 16f
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
        val body = TextView(ctx).apply {
            text = name
            setTextColor(onSurfaceVar)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(14))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = MaterialButton(ctx, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = ctx.getString(R.string.carnav_deferred_cancel)
            setOnClickListener { dismiss() }
        }
        val yes = MaterialButton(ctx).apply {
            text = ctx.getString(R.string.carnav_deferred_yes)
            setOnClickListener {
                dismiss()
                Thread {
                    try {
                        TelenavActions.navigate(app, name, lat, lng, true)
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
            dp(320),
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

        // Drag by the header (title + body); buttons keep their own clicks.
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

        wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm?.addView(card, lp)
            view = card
            main.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
            Log.i(TAG, "prompt shown for '$name' (night=$nightYes)")
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
