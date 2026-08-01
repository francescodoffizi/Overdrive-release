package com.overdrive.app.ui.view

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure pane geometry for the Projection screen, expressed in CLUSTER coordinate space.
 *
 * <h3>Why cluster space (and not view px)</h3>
 * The box the user drags maps 1:1 onto TWO things: the destination rect the head-unit
 * mirror is letterboxed into, AND — new in the SOTA rework — the FREEFORM window bounds the
 * cast app is given ON the real cluster. Both consumers speak cluster pixels, so the single
 * source of truth is a rect in cluster space ({@code 0..clusterW}, {@code 0..clusterH}).
 *
 * The head-unit preview simply scales that rect by {@code viewW/clusterW} (a UNIFORM scale on
 * both axes because the preview stage is itself panel-shaped). This is exactly DashCast's
 * ResizeFrameView model, and it is why the preview box is ALWAYS panel-shaped by construction:
 * there is no separately-resolved "panel aspect" that can disagree with the stage and collapse
 * the box to a square (the regression this replaces).
 *
 * <p>All functions here are pure and side-effect-free so they are exhaustively unit-tested
 * without an Android runtime ([ProjectionBoundsGeometryTest]). Rectangles are
 * {@code left, top, right, bottom} in cluster px (matching {@code android.graphics.Rect} and
 * the daemon's {@code resizeTask} contract). Dimensions are always kept positive and inside
 * the cluster bounds.
 */
object ProjectionBoundsGeometry {

    /** A cluster-space rectangle (px), left/top/right/bottom — matching android.graphics.Rect. */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Corner + side + move grab handles (matches ProjectionBoundsView / DashCast ResizeFrameView). */
    const val HANDLE_NONE = 0
    const val HANDLE_TL = 1
    const val HANDLE_T = 2
    const val HANDLE_TR = 3
    const val HANDLE_L = 4
    const val HANDLE_R = 5
    const val HANDLE_BL = 6
    const val HANDLE_B = 7
    const val HANDLE_BR = 8
    const val HANDLE_MOVE = 9

    /** Layout presets the UI exposes as one-tap buttons. */
    const val PRESET_FULL = 0
    const val PRESET_LEFT = 1
    const val PRESET_RIGHT = 2
    const val PRESET_CENTER = 3

    /** Snap grid step (cluster px) applied to every edge, plus the soft-snap tolerance to the
     *  panel's half/quarter guide lines. Mirrors DashCast's GRID_STEP=10 / SNAP_TOL_PX=24. */
    const val GRID_STEP = 10
    const val SNAP_TOLERANCE = 24

    /** Smallest window the daemon will accept without the OEM clamping it oddly; also the
     *  smallest useful preview. Cluster px. */
    const val MIN_SIZE = 200

    /** Center preset covers this fraction of each axis (matches DashCast's 0.6). */
    private const val CENTER_FRAC = 0.6f

    /**
     * Preset rect in cluster space. FULL is the whole panel; LEFT/RIGHT are exact halves;
     * CENTER is a centred {@code 0.6×0.6} box. Always clamped to the panel and to MIN_SIZE.
     */
    fun preset(which: Int, clusterW: Int, clusterH: Int): Rect {
        val cw = max(1, clusterW)
        val ch = max(1, clusterH)
        val r = when (which) {
            PRESET_LEFT -> Rect(0, 0, cw / 2, ch)
            PRESET_RIGHT -> Rect(cw / 2, 0, cw, ch)
            PRESET_CENTER -> {
                val w = (cw * CENTER_FRAC).roundToInt()
                val h = (ch * CENTER_FRAC).roundToInt()
                val l = (cw - w) / 2
                val t = (ch - h) / 2
                Rect(l, t, l + w, t + h)
            }
            else -> Rect(0, 0, cw, ch)
        }
        return clampToCluster(r, cw, ch, null)
    }

    /**
     * Apply a drag of one handle to {@code start} (the rect at gesture-DOWN), given the total
     * cluster-space delta {@code dx,dy}. Enforces panel bounds, MIN_SIZE, optional aspect lock,
     * grid snap and soft guide snap. Returns the new rect. This is the whole resize/move engine;
     * ProjectionBoundsView is a thin translation layer that converts touch px → cluster px and
     * calls this.
     *
     * @param aspect width/height to lock corner+side resizes to, or null for free-form.
     */
    fun applyDrag(
        handle: Int,
        start: Rect,
        dx: Int,
        dy: Int,
        clusterW: Int,
        clusterH: Int,
        aspect: Float?
    ): Rect {
        val cw = max(1, clusterW)
        val ch = max(1, clusterH)
        if (handle == HANDLE_MOVE) {
            // Translate the whole rect, clamped so it never leaves the panel (size preserved).
            val w = start.width
            val h = start.height
            var l = start.left + dx
            var t = start.top + dy
            l = l.coerceIn(0, max(0, cw - w))
            t = t.coerceIn(0, max(0, ch - h))
            l = snap(l, snapGuidesX(cw))
            t = snap(t, snapGuidesY(ch))
            l = l.coerceIn(0, max(0, cw - w))
            t = t.coerceIn(0, max(0, ch - h))
            return Rect(l, t, l + w, t + h)
        }

        // Edge deltas: which edges this handle moves.
        val movesLeft = handle == HANDLE_TL || handle == HANDLE_L || handle == HANDLE_BL
        val movesRight = handle == HANDLE_TR || handle == HANDLE_R || handle == HANDLE_BR
        val movesTop = handle == HANDLE_TL || handle == HANDLE_T || handle == HANDLE_TR
        val movesBottom = handle == HANDLE_BL || handle == HANDLE_B || handle == HANDLE_BR

        var l = start.left + if (movesLeft) dx else 0
        var t = start.top + if (movesTop) dy else 0
        var r = start.right + if (movesRight) dx else 0
        var b = start.bottom + if (movesBottom) dy else 0

        // Snap the moving edges to the grid + guide lines BEFORE enforcing bounds/min/aspect.
        if (movesLeft) l = snap(l, snapGuidesX(cw))
        if (movesRight) r = snap(r, snapGuidesX(cw))
        if (movesTop) t = snap(t, snapGuidesY(ch))
        if (movesBottom) b = snap(b, snapGuidesY(ch))

        // Clamp edges to the panel.
        l = l.coerceIn(0, cw)
        r = r.coerceIn(0, cw)
        t = t.coerceIn(0, ch)
        b = b.coerceIn(0, ch)

        // Enforce MIN_SIZE by pushing the moving edge back toward its fixed anchor.
        if (r - l < MIN_SIZE) {
            if (movesLeft) l = (r - MIN_SIZE) else r = (l + MIN_SIZE)
        }
        if (b - t < MIN_SIZE) {
            if (movesTop) t = (b - MIN_SIZE) else b = (t + MIN_SIZE)
        }

        var rect = Rect(min(l, r), min(t, b), max(l, r), max(t, b))
        if (aspect != null && aspect.isFinite() && aspect > 0f) {
            rect = enforceAspect(rect, handle, aspect, cw, ch)
        }
        return clampToCluster(rect, cw, ch, aspect)
    }

    /**
     * Re-fit a rect to a new aspect ratio (used when the user re-enables aspect lock or the
     * panel aspect is (re)resolved), anchored at the top-left, preserving width where it fits.
     */
    fun refitAspect(rect: Rect, aspect: Float, clusterW: Int, clusterH: Int): Rect {
        if (!aspect.isFinite() || aspect <= 0f) return rect
        val cw = max(1, clusterW)
        val ch = max(1, clusterH)
        var w = rect.width
        var h = (w / aspect).roundToInt()
        if (h > ch) { h = ch; w = (h * aspect).roundToInt() }
        if (w > cw) { w = cw; h = (w / aspect).roundToInt() }
        return clampToCluster(Rect(rect.left, rect.top, rect.left + w, rect.top + h), cw, ch, aspect)
    }

    /**
     * Clamp an arbitrary rect into the panel with positive, MIN_SIZE-respecting dimensions and,
     * when {@code aspect} is given, the locked ratio (shrunk to fit both axes). Used on restore,
     * on preset apply, and as the final step of every drag.
     */
    fun clampToCluster(rect: Rect, clusterW: Int, clusterH: Int, aspect: Float?): Rect {
        val cw = max(1, clusterW)
        val ch = max(1, clusterH)
        val minW = min(MIN_SIZE, cw)
        val minH = min(MIN_SIZE, ch)

        var w = rect.width.coerceIn(minW, cw)
        var h = rect.height.coerceIn(minH, ch)
        if (aspect != null && aspect.isFinite() && aspect > 0f) {
            // Fit the largest ratio-correct box that is no bigger than the requested w/h and the
            // panel. Prefer the requested width; derive height; if that overflows, drive from height.
            w = min(w, (ch * aspect).roundToInt()).coerceAtLeast(1)
            h = (w / aspect).roundToInt().coerceIn(1, ch)
            w = (h * aspect).roundToInt().coerceIn(1, cw)
        }
        var l = rect.left.coerceIn(0, max(0, cw - w))
        var t = rect.top.coerceIn(0, max(0, ch - h))
        return Rect(l, t, l + w, t + h)
    }

    // ── internals ─────────────────────────────────────────────────────────────────────

    /** Enforce an aspect ratio on a freshly-resized rect, keeping the handle's anchor fixed. */
    private fun enforceAspect(rect: Rect, handle: Int, aspect: Float, cw: Int, ch: Int): Rect {
        // Drive the coupled dimension from the dominant dragged axis so corner drags feel natural
        // and side drags keep the box centred on the fixed edge.
        val widthDriven = handle == HANDLE_L || handle == HANDLE_R
        var w = rect.width
        var h = rect.height
        if (widthDriven) {
            h = (w / aspect).roundToInt()
        } else if (handle == HANDLE_T || handle == HANDLE_B) {
            w = (h * aspect).roundToInt()
        } else {
            // Corner: use the axis that grew more, in ratio terms.
            if (w >= (h * aspect).roundToInt()) h = (w / aspect).roundToInt()
            else w = (h * aspect).roundToInt()
        }
        w = w.coerceIn(1, cw)
        h = h.coerceIn(1, ch)

        // Anchor: corners pin the opposite corner; sides pin the opposite edge and centre the
        // free axis so the box doesn't jump.
        val anchorRight = handle == HANDLE_TL || handle == HANDLE_L || handle == HANDLE_BL
        val anchorBottom = handle == HANDLE_TL || handle == HANDLE_T || handle == HANDLE_TR
        val left = when {
            handle == HANDLE_T || handle == HANDLE_B -> rect.left + (rect.width - w) / 2  // centre X
            anchorRight -> rect.right - w
            else -> rect.left
        }
        val top = when {
            handle == HANDLE_L || handle == HANDLE_R -> rect.top + (rect.height - h) / 2   // centre Y
            anchorBottom -> rect.bottom - h
            else -> rect.top
        }
        return Rect(left, top, left + w, top + h)
    }

    /** Snap a coordinate to the nearest grid step, then to a guide line if within tolerance. */
    private fun snap(value: Int, guides: IntArray): Int {
        var v = (value.toFloat() / GRID_STEP).roundToInt() * GRID_STEP
        var best = v
        var bestDist = SNAP_TOLERANCE + 1
        for (g in guides) {
            val d = abs(v - g)
            if (d <= SNAP_TOLERANCE && d < bestDist) { bestDist = d; best = g }
        }
        return best
    }

    /** Vertical guide lines (X): panel edges, half, quarters. */
    private fun snapGuidesX(cw: Int): IntArray = intArrayOf(0, cw / 4, cw / 2, cw * 3 / 4, cw)

    /** Horizontal guide lines (Y): panel edges, half, quarters. */
    private fun snapGuidesY(ch: Int): IntArray = intArrayOf(0, ch / 4, ch / 2, ch * 3 / 4, ch)
}
