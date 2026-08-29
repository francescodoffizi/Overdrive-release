package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.ai.Detection;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Targeted tests for {@link DetectionBaseline#shadowContainmentDiag} — the
 * log-only diagnostic validating the containment-suppression candidate
 * (parked-car split-box FP audit).
 *
 * Locks the review-mandated properties:
 * <ul>
 *   <li>Uncensored: EVERY valid call emits a line, including no-baseline
 *       ("entries=0") and zero-overlap ("contain=0.00" / "noOverlap") cases,
 *       so the field distribution has no missing left tail.</li>
 *   <li>Unmasked: the same-canonical-class slot is always reported for
 *       same-class candidates; a larger cross-class overlap is surfaced as
 *       bestOther{} instead of silently replacing it.</li>
 *   <li>Signature fidelity: a split-box fragment reads contain=1.00 against
 *       the stored full-car entry while genuinely bypassing isInBaseline —
 *       the exact geometry the candidate rule targets.</li>
 * </ul>
 *
 * Coordinates are quadrant pixels (320x240), matching the engine's
 * baseline-space call sites; the diag normalizes internally.
 */
public class DetectionBaselineShadowDiagTest {

    private static final int QW = 320;
    private static final int QH = 240;

    /** Full parked car: normalized box [0.25..0.75] x [0.25..0.75]. */
    private static Detection car() {
        return new Detection(2, 0.9f, 80, 60, 160, 120);
    }

    private static DetectionBaseline seededWithCar(int quadrant) {
        DetectionBaseline b = new DetectionBaseline();
        b.seedFromDetections(quadrant, Collections.singletonList(car()), QW, QH);
        return b;
    }

    @Test
    public void splitBoxFragmentReportsFullContainmentAndBypassesBaseline() {
        DetectionBaseline b = seededWithCar(0);
        // Left half of the car: fully inside the entry (contain = 1.00) but
        // IoU = 0.50 < 0.7 and foot-point off by 0.125 > 0.04 — the split-box
        // FP shape that PASSES the baseline today.
        Detection half = new Detection(2, 0.55f, 80, 60, 80, 120);

        assertFalse("split fragment must bypass isInBaseline (the FP under audit)",
                b.isInBaseline(half, 0, QW, QH));

        String diag = b.shadowContainmentDiag(half, 0, QW, QH);
        assertNotNull(diag);
        assertTrue("same-class slot expected: " + diag, diag.contains(" same{cls=2"));
        assertTrue("full containment expected: " + diag, diag.contains("contain=1.00"));
        assertTrue("iou below match threshold expected: " + diag, diag.contains("iou=0.50"));
    }

    @Test
    public void personOverParkedCarReportsCrossClassOverlap() {
        DetectionBaseline b = seededWithCar(0);
        // "Car part misread as person": class 0 has no same-class entries
        // (persons are never promoted), so the cross-class slot carries the
        // full geometry of the vehicle entry the person box sits on.
        Detection person = new Detection(0, 0.30f, 120, 80, 60, 90);

        String diag = b.shadowContainmentDiag(person, 0, QW, QH);
        assertNotNull(diag);
        assertTrue("cross-class slot expected: " + diag, diag.contains(" any{cls=2"));
        assertTrue("person box fully inside car entry: " + diag, diag.contains("contain=1.00"));
        assertFalse("no same-class slot for person: " + diag, diag.contains(" same{"));
    }

    @Test
    public void emptyBaselineStillEmitsLine() {
        DetectionBaseline b = new DetectionBaseline();
        Detection det = new Detection(2, 0.6f, 80, 60, 80, 120);

        String diag = b.shadowContainmentDiag(det, 0, QW, QH);
        assertNotNull("no-baseline case must not be censored", diag);
        assertTrue(diag, diag.contains("entries=0"));
    }

    @Test
    public void zeroOverlapSameClassReportsContainZeroNotSilence() {
        DetectionBaseline b = seededWithCar(0);
        // Same class, top-left corner, no overlap with the car entry: the
        // line must still emit with contain=0.00 (plus foot distance), or the
        // containment distribution is right-censored.
        Detection far = new Detection(2, 0.6f, 0, 0, 32, 24);

        String diag = b.shadowContainmentDiag(far, 0, QW, QH);
        assertNotNull(diag);
        assertTrue(diag, diag.contains(" same{cls=2"));
        assertTrue(diag, diag.contains("contain=0.00"));
    }

    @Test
    public void zeroOverlapCrossClassReportsNoOverlapMarker() {
        DetectionBaseline b = seededWithCar(0);
        Detection farPerson = new Detection(0, 0.3f, 0, 0, 32, 24);

        String diag = b.shadowContainmentDiag(farPerson, 0, QW, QH);
        assertNotNull(diag);
        assertTrue(diag, diag.contains(" any{noOverlap entries=1}"));
    }

    @Test
    public void largerCrossClassOverlapIsSurfacedNotMasking() {
        DetectionBaseline b = new DetectionBaseline();
        // Car on the left, bench (class 13, promotable) on the right.
        b.seedFromDetections(2, Arrays.asList(
                new Detection(2, 0.9f, 0, 60, 100, 120),
                new Detection(13, 0.9f, 180, 60, 120, 120)), QW, QH);
        // Same-class (car) det that overlaps the BENCH more (0.25) than the
        // car (0.08): the same-class slot must still report the car, with the
        // bench surfaced as bestOther — not silently replacing it.
        Detection det = new Detection(2, 0.5f, 90, 60, 120, 120);

        String diag = b.shadowContainmentDiag(det, 2, QW, QH);
        assertNotNull(diag);
        assertTrue("same-class slot must survive masking: " + diag,
                diag.contains(" same{cls=2"));
        assertTrue("larger cross-class overlap must be surfaced: " + diag,
                diag.contains("bestOther{cls=13 contain=0.25}"));
    }
}
