package com.overdrive.app.ui.view

import com.overdrive.app.ui.view.ProjectionBoundsGeometry as G
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive tests for the cluster-space pane geometry. The whole point of moving the box into
 * cluster space is that the shape is always panel-correct and the daemon window rect is derived
 * from the same source of truth, so these tests pin the invariants that were previously violated
 * by the square-box regression.
 */
class ProjectionBoundsGeometryTest {

    private val CW = 1920
    private val CH = 720

    @Test
    fun presetsCoverTheExpectedRegions() {
        assertEquals(G.Rect(0, 0, 1920, 720), G.preset(G.PRESET_FULL, CW, CH))
        assertEquals(G.Rect(0, 0, 960, 720), G.preset(G.PRESET_LEFT, CW, CH))
        assertEquals(G.Rect(960, 0, 1920, 720), G.preset(G.PRESET_RIGHT, CW, CH))

        val center = G.preset(G.PRESET_CENTER, CW, CH)
        // 0.6 of each axis, centred.
        assertEquals(1152, center.width)
        assertEquals(432, center.height)
        assertEquals((CW - 1152) / 2, center.left)
        assertEquals((CH - 432) / 2, center.top)
    }

    @Test
    fun moveKeepsSizeAndStaysOnPanel() {
        val start = G.Rect(100, 100, 700, 400)   // 600×300
        val moved = G.applyDrag(G.HANDLE_MOVE, start, dx = 5000, dy = 5000, CW, CH, aspect = null)
        assertEquals(600, moved.width)
        assertEquals(300, moved.height)
        assertTrue(moved.right <= CW)
        assertTrue(moved.bottom <= CH)
        assertEquals(CW, moved.right)   // pinned to the right/bottom edge
        assertEquals(CH, moved.bottom)
    }

    @Test
    fun cornerResizeHonoursMinSize() {
        val start = G.Rect(0, 0, 1000, 600)
        // Drag the bottom-right corner far past the top-left → collapses to MIN_SIZE, not negative.
        val r = G.applyDrag(G.HANDLE_BR, start, dx = -5000, dy = -5000, CW, CH, aspect = null)
        assertEquals(G.MIN_SIZE, r.width)
        assertEquals(G.MIN_SIZE, r.height)
        assertEquals(0, r.left)
        assertEquals(0, r.top)
    }

    @Test
    fun freeFormResizeKeepsIndependentAxes() {
        val start = G.Rect(100, 100, 500, 300)   // 400×200
        val r = G.applyDrag(G.HANDLE_BR, start, dx = 300, dy = 20, CW, CH, aspect = null)
        // width grew by ~300, height by ~20 (independent), modulo grid snap (10px).
        assertTrue("width grew", r.width in 690..710)
        assertTrue("height grew a little", r.height in 210..230)
    }

    @Test
    fun aspectLockedResizeKeepsRatioWithinPanel() {
        val aspect = CW.toFloat() / CH.toFloat()   // 8:3
        val start = G.Rect(0, 0, 640, 240)          // already 8:3
        val r = G.applyDrag(G.HANDLE_BR, start, dx = 400, dy = 0, CW, CH, aspect = aspect)
        // Height must follow width at 8:3 (within grid rounding).
        val ratio = r.width.toFloat() / r.height.toFloat()
        assertEquals(aspect, ratio, 0.05f)
        assertTrue(r.right <= CW)
        assertTrue(r.bottom <= CH)
    }

    @Test
    fun edgeSnapsToHalfGuide() {
        val start = G.Rect(0, 0, 900, 720)
        // Drag right edge to ~958 (within 24px of the 960 half-line) → snaps to 960.
        val r = G.applyDrag(G.HANDLE_R, start, dx = 58, dy = 0, clusterW = CW, clusterH = CH, aspect = null)
        assertEquals(960, r.right)
    }

    @Test
    fun clampRestoresRatioAndFitsPanelForOddSavedRect() {
        // A saved rect that is too wide/short for 8:3 gets refit to ratio, inside the panel.
        val aspect = 8f / 3f
        val r = G.clampToCluster(G.Rect(0, 0, 5000, 100), CW, CH, aspect)
        assertTrue(r.right <= CW)
        assertTrue(r.bottom <= CH)
        val ratio = r.width.toFloat() / r.height.toFloat()
        assertEquals(aspect, ratio, 0.05f)
    }

    @Test
    fun clampNeverProducesNegativeOrOffPanel() {
        val r = G.clampToCluster(G.Rect(-500, -500, -100, -100), CW, CH, null)
        assertTrue(r.left >= 0)
        assertTrue(r.top >= 0)
        assertTrue(r.width >= 1)
        assertTrue(r.height >= 1)
        assertTrue(r.right <= CW)
        assertTrue(r.bottom <= CH)
    }

    @Test
    fun presetsClampOnDegeneratePanel() {
        // A 1×1 (unresolved) panel must still yield a valid, on-panel rect — no divide-by-zero,
        // no square-box crash. This is the exact defensive case the old code mishandled.
        val r = G.preset(G.PRESET_CENTER, 1, 1)
        assertTrue(r.width >= 1)
        assertTrue(r.height >= 1)
        assertTrue(r.right <= 1)
        assertTrue(r.bottom <= 1)
    }

    @Test
    fun refitAspectShrinksToFitPanel() {
        val r = G.refitAspect(G.Rect(0, 0, 1900, 1900), 8f / 3f, CW, CH)
        assertTrue(r.bottom <= CH)
        assertTrue(r.right <= CW)
        val ratio = r.width.toFloat() / r.height.toFloat()
        assertEquals(8f / 3f, ratio, 0.05f)
    }
}
