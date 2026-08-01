package com.overdrive.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView
import com.overdrive.app.ui.model.NavigationRailSwipePolicy

/**
 * Vertical rail scroller that intercepts only deliberate horizontal swipes.
 *
 * Vertical gestures continue through NestedScrollView unchanged. Once a gesture is
 * clearly horizontal, child destination rows receive CANCEL rather than a click.
 */
class SwipeExpandableRailScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val minimumSwipeDistance = 48f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var interceptingHorizontalSwipe = false
    // Latched at arm time. ACTION_UP must not re-resolve from the final
    // position: a swipe that curves vertically or drifts back would score NONE
    // after the child's click was already cancelled, eating the gesture.
    private var armedAction = NavigationRailSwipePolicy.Action.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    var onRailSwipe: ((NavigationRailSwipePolicy.Action) -> Unit)? = null

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                activePointerId = event.getPointerId(0)
                interceptingHorizontalSwipe = false
                armedAction = NavigationRailSwipePolicy.Action.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                // Arm on the same distance ACTION_UP would use, and latch the
                // direction so the outcome can't change after the child's click
                // has already been cancelled.
                val action = resolveSwipe(event, minimumSwipeDistance)
                if (action != NavigationRailSwipePolicy.Action.NONE) {
                    interceptingHorizontalSwipe = true
                    armedAction = action
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Let NestedScrollView unwind the nested-scroll it began on
                    // DOWN — it never sees another event once we consume them.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try {
                        super.onTouchEvent(cancel)
                    } finally {
                        cancel.recycle()
                    }
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> endGesture()
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interceptingHorizontalSwipe) {
            return super.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val action = armedAction
                endGesture()
                if (action != NavigationRailSwipePolicy.Action.NONE) {
                    onRailSwipe?.invoke(action)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                endGesture()
                return true
            }
        }
        return true
    }

    /**
     * Mirrors deltaX in RTL so "swipe away from the rail" expands in both layout
     * directions — the rail sits on the right when the locale is RTL.
     */
    private fun resolveSwipe(
        event: MotionEvent,
        minimumDistance: Float
    ): NavigationRailSwipePolicy.Action {
        // Track the original finger: a second touch shifts pointer indices, so
        // getX(0) could otherwise start reporting a different finger.
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return NavigationRailSwipePolicy.Action.NONE
        val deltaX = event.getX(index) - downX
        val directed = if (layoutDirection == LAYOUT_DIRECTION_RTL) -deltaX else deltaX
        return NavigationRailSwipePolicy.resolve(
            directed, event.getY(index) - downY, minimumDistance)
    }

    private fun endGesture() {
        interceptingHorizontalSwipe = false
        armedAction = NavigationRailSwipePolicy.Action.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}
