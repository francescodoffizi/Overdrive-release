package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.ai.Detection;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FisheyeDewarp contract tests.
 *
 * The dewarp is on the protected detection path (S3 input), so its contracts
 * are pinned here: identity where disabled, fail-open on bad input, and the
 * geometric properties of the box back-mapping (I5 — coordinates must return
 * to the warped source space the rest of the pipeline lives in).
 */
public class FisheyeDewarpTest {

    private static final int W = 320, H = 240;

    private static byte[] gradientRgb() {
        byte[] rgb = new byte[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 3;
                rgb[i] = (byte) (x & 0xFF);
                rgb[i + 1] = (byte) (y & 0xFF);
                rgb[i + 2] = (byte) ((x + y) & 0xFF);
            }
        }
        return rgb;
    }

    // ── Quadrant gating ────────────────────────────────────────────────────

    @Test
    public void frontAndRearAreNeverDewarped() {
        byte[] rgb = gradientRgb();
        assertNull("front (Q0) must be identity", FisheyeDewarp.dewarpForDetector(rgb, W, H, 0));
        assertNull("rear (Q2) must be identity", FisheyeDewarp.dewarpForDetector(rgb, W, H, 2));
    }

    @Test
    public void sideCamsAreDewarped() {
        byte[] rgb = gradientRgb();
        assertNotNull("right (Q1) must dewarp", FisheyeDewarp.dewarpForDetector(rgb, W, H, 1));
        assertNotNull("left (Q3) must dewarp", FisheyeDewarp.dewarpForDetector(rgb, W, H, 3));
    }

    @Test
    public void invalidQuadrantFailsOpen() {
        byte[] rgb = gradientRgb();
        assertNull(FisheyeDewarp.dewarpForDetector(rgb, W, H, -1));
        assertNull(FisheyeDewarp.dewarpForDetector(rgb, W, H, 4));
    }

    // ── Fail-open on bad input (I1) ────────────────────────────────────────

    @Test
    public void nullOrShortBufferFailsOpen() {
        assertNull(FisheyeDewarp.dewarpForDetector(null, W, H, 3));
        assertNull(FisheyeDewarp.dewarpForDetector(new byte[10], W, H, 3));
    }

    @Test
    public void boxMappingFailsOpenForDisabledQuadrantAndEmptyList() {
        List<Detection> dets = new ArrayList<>();
        dets.add(new Detection(0, 0.9f, 10, 10, 50, 100));
        // Disabled quadrant → same list object back (identity, not a copy).
        assertEquals(dets, FisheyeDewarp.mapDetectionsToSource(dets, W, H, 0));
        // Null / empty → passed through untouched.
        assertNull(FisheyeDewarp.mapDetectionsToSource(null, W, H, 3));
        List<Detection> empty = Collections.emptyList();
        assertEquals(empty, FisheyeDewarp.mapDetectionsToSource(empty, W, H, 3));
    }

    // ── Scratch-buffer reuse (audit R3b, detector-native F5) ──────────────

    @Test
    public void dewarpReusesScratchBufferAcrossCalls() {
        byte[] rgb = gradientRgb();
        byte[] a = FisheyeDewarp.dewarpForDetector(rgb, W, H, 3);
        byte[] b = FisheyeDewarp.dewarpForDetector(rgb, W, H, 1);
        assertNotNull(a);
        // Same dims → the SAME scratch instance must come back. The reuse IS
        // the fix: a silent regression to per-call allocation re-creates the
        // ~0.92 MB/s large-object GC churn at the close-subject cadence.
        assertSame("scratch must be reused across same-dims calls", a, b);
        assertEquals(W * H * 3, b.length);
    }

    @Test
    public void dewarpScratchHandlesDimsChange() {
        byte[] rgb = gradientRgb();
        byte[] a = FisheyeDewarp.dewarpForDetector(rgb, W, H, 3);
        assertNotNull(a);
        // Different dims → correctly-sized fresh buffer (the length check in
        // dewarpForDetector, not a stale 320×240 scratch).
        int w2 = 160, h2 = 120;
        byte[] small = new byte[w2 * h2 * 3];
        byte[] b = FisheyeDewarp.dewarpForDetector(small, w2, h2, 3);
        assertNotNull(b);
        assertEquals(w2 * h2 * 3, b.length);
        // And back again — never serves a wrong-sized scratch.
        byte[] c = FisheyeDewarp.dewarpForDetector(rgb, W, H, 3);
        assertNotNull(c);
        assertEquals(W * H * 3, c.length);
    }

    // ── Pixel-path geometry ────────────────────────────────────────────────

    @Test
    public void dewarpPreservesTileCenterAndSize() {
        byte[] rgb = gradientRgb();
        byte[] out = FisheyeDewarp.dewarpForDetector(rgb, W, H, 3);
        assertNotNull(out);
        assertEquals(rgb.length, out.length);
        // The division model is radial around the tile center: the exact
        // center pixel samples (approximately) itself.
        int cx = W / 2, cy = H / 2;
        int ci = (cy * W + cx) * 3;
        // Allow 1px of rounding: compare against the 3×3 neighborhood.
        boolean centerStable = false;
        for (int dy = -1; dy <= 1 && !centerStable; dy++) {
            for (int dx = -1; dx <= 1 && !centerStable; dx++) {
                int ni = ((cy + dy) * W + (cx + dx)) * 3;
                if (out[ci] == rgb[ni] && out[ci + 1] == rgb[ni + 1]) centerStable = true;
            }
        }
        assertTrue("tile-center pixel must be (near-)fixed under dewarp", centerStable);
    }

    @Test
    public void cornerContentPullsInwardAndEdgeMidpointIsFixed() {
        // The shader's zoom-to-fill is calibrated at the aspect-corrected
        // radius r = aspect, i.e. the TOP/BOTTOM EDGE MIDPOINTS are fixed
        // points of the net transform, while CORNERS (r > aspect) sample
        // strictly inward — that is the barrel correction. (Content between
        // center and mid-radius shifts slightly OUTWARD along the x-axis;
        // asserting "everything magnifies" would contradict the shipped
        // recorder shader this class mirrors.)
        byte[] rgb = gradientRgb();
        byte[] out = FisheyeDewarp.dewarpForDetector(rgb, W, H, 3);
        assertNotNull(out);

        // Top edge midpoint (W/2, 0): fixed within 1px of rounding.
        // G channel encodes source y (gradientRgb).
        int topMid = ((0) * W + (W / 2)) * 3;
        int srcYTopMid = out[topMid + 1] & 0xFF;
        assertTrue("top edge midpoint must be a fixed point, srcY=" + srcYTopMid,
                srcYTopMid <= 1);

        // Top-right corner region (300, 20): source sample strictly inward
        // (srcY pulled DOWN toward the horizontal midline, but not past it).
        int corner = (20 * W + 300) * 3;
        int srcYCorner = out[corner + 1] & 0xFF;
        assertTrue("corner must sample inward: srcY=" + srcYCorner,
                srcYCorner > 20 && srcYCorner < H / 2);
    }

    // ── Box back-mapping geometry (I5) ─────────────────────────────────────

    @Test
    public void mappedBoxStaysInBoundsAndKeepsFields() {
        List<Detection> dets = new ArrayList<>();
        dets.add(new Detection(0, 0.87f, 250, 180, 60, 55));   // near corner
        dets.add(new Detection(2, 0.44f, 0, 0, 40, 30));       // at origin
        List<Detection> mapped = FisheyeDewarp.mapDetectionsToSource(dets, W, H, 3);
        assertEquals(2, mapped.size());
        for (int i = 0; i < 2; i++) {
            Detection in = dets.get(i), out = mapped.get(i);
            assertEquals(in.getClassId(), out.getClassId());
            assertEquals(in.getConfidence(), out.getConfidence(), 0f);
            assertTrue(out.getX() >= 0 && out.getX() < W);
            assertTrue(out.getY() >= 0 && out.getY() < H);
            assertTrue(out.getX() + out.getW() <= W);
            assertTrue(out.getY() + out.getH() <= H);
            assertTrue(out.getW() >= 0 && out.getH() >= 0);
        }
    }

    @Test
    public void centeredBoxIsNearIdentityUnderMapping() {
        // A box straddling the tile center maps ~onto itself (radial
        // transform, r≈0 ⇒ identity), so a centered subject's box moves
        // by at most a couple of pixels.
        Detection centered = new Detection(0, 0.9f, W / 2 - 20, H / 2 - 30, 40, 60);
        List<Detection> mapped =
                FisheyeDewarp.mapDetectionsToSource(Collections.singletonList(centered), W, H, 3);
        Detection m = mapped.get(0);
        assertTrue(Math.abs(m.getX() - centered.getX()) <= 3);
        assertTrue(Math.abs(m.getY() - centered.getY()) <= 3);
        assertTrue(Math.abs(m.getW() - centered.getW()) <= 6);
        assertTrue(Math.abs(m.getH() - centered.getH()) <= 6);
    }

    @Test
    public void offCenterBoxMapsTowardCenterConsistentlyWithPixels() {
        // The SAME forward transform moves pixels and boxes: a box whose
        // content was pulled center-ward by the pixel dewarp must map back
        // OUTWARD toward its true warped position — i.e. the mapped box's
        // outer edge sits strictly closer to the center than the dewarped
        // box's outer edge would suggest... concretely, for the right half
        // of the tile the mapped x-extent must shrink toward the center
        // (r_src < r_out on every point of the box).
        Detection offCenter = new Detection(0, 0.8f, 240, 100, 60, 40);
        List<Detection> mapped =
                FisheyeDewarp.mapDetectionsToSource(Collections.singletonList(offCenter), W, H, 3);
        Detection m = mapped.get(0);
        // Outer-right edge of the box must move toward the center (smaller x2).
        assertTrue("outer edge must map center-ward: x2=" + (m.getX() + m.getW()),
                m.getX() + m.getW() < offCenter.getX() + offCenter.getW());
        // And must not cross the center — the subject stays on its side.
        assertTrue(m.getX() + m.getW() > W / 2);
    }

    @Test
    public void roundTripPixelBoxAgreement() {
        // End-to-end coherence: paint a bright square in the WARPED source,
        // dewarp the pixels, find the square's bbox in the dewarped image
        // (what YOLO would see), map it back, and require it to land on the
        // original square within rounding tolerance.
        int sx = 210, sy = 60, sw = 50, sh = 44;   // square in warped source
        byte[] rgb = new byte[W * H * 3];          // black background
        for (int y = sy; y < sy + sh; y++) {
            for (int x = sx; x < sx + sw; x++) {
                int i = (y * W + x) * 3;
                rgb[i] = (byte) 255; rgb[i + 1] = (byte) 255; rgb[i + 2] = (byte) 255;
            }
        }
        byte[] out = FisheyeDewarp.dewarpForDetector(rgb, W, H, 3);
        assertNotNull(out);
        int minX = W, minY = H, maxX = -1, maxY = -1;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if ((out[(y * W + x) * 3] & 0xFF) > 128) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        assertTrue("square must be visible in dewarped image", maxX > minX && maxY > minY);
        Detection seen = new Detection(0, 0.9f, minX, minY, maxX - minX + 1, maxY - minY + 1);
        Detection back = FisheyeDewarp
                .mapDetectionsToSource(Collections.singletonList(seen), W, H, 3).get(0);
        // Mapped-back box must cover the original square within a few px of
        // nearest-neighbor rounding slack on each edge.
        int tol = 4;
        assertTrue("x low edge", Math.abs(back.getX() - sx) <= tol);
        assertTrue("y low edge", Math.abs(back.getY() - sy) <= tol);
        assertTrue("x high edge", Math.abs((back.getX() + back.getW()) - (sx + sw)) <= tol);
        assertTrue("y high edge", Math.abs((back.getY() + back.getH()) - (sy + sh)) <= tol);
    }
}
