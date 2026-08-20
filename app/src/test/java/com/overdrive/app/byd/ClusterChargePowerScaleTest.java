package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins {@link BydDataCollector#scaleClusterChargePowerKw(double)} as an IDENTITY.
 *
 * <p>This function previously divided any reading above 22 by 100, inferring the unit from the
 * magnitude. That inference is unsound: the same numeric band holds both a genuine DC-charging
 * rate (60-500 kW) and a plausible cumulative-counter value, so no threshold can separate them.
 * Applying it displayed a real 150 kW session as 1.5 kW, and it was only contained by restricting
 * the field to one drivetrain — which denied the other drivetrain the vehicle's own dash figure.
 *
 * <p>The unit question now belongs to {@link ChargeSourceClassifier}, which decides from how a
 * value MOVES across a charge (a counter only rises; a rate dips). These tests exist to stop the
 * magnitude heuristic being reintroduced: any scaling here is a regression, because a caller
 * cannot tell a scaled value from an unscaled one and would silently price the difference.
 */
public class ClusterChargePowerScaleTest {

    private static double kw(double raw) {
        return BydDataCollector.scaleClusterChargePowerKw(raw);
    }

    /** Values are passed through untouched — no divide, no multiply, at any magnitude. */
    @Test
    public void readingIsNeverScaled() {
        assertEquals(1.8, kw(1.8), 1e-9);
        assertEquals(22.0, kw(22.0), 1e-9);
        assertEquals(23.0, kw(23.0), 1e-9);       // old code: 0.23
        assertEquals(60.0, kw(60.0), 1e-9);       // old code: 0.60
        assertEquals(119.0, kw(119.0), 1e-9);     // the field-captured counter value
        assertEquals(189.5, kw(189.5), 1e-9);     // old code: 1.895
        assertEquals(250.0, kw(250.0), 1e-9);     // a real DC rate; old code: 2.50
        assertEquals(359.4, kw(359.4), 1e-9);
    }

    /**
     * A genuine DC fast-charge rate survives intact.
     *
     * <p>This is the case the old heuristic could not express: on the drivetrain that can
     * actually DC-charge, every rate from 22 kW up landed in the "divide by 100" band.
     */
    @Test
    public void dcFastChargeRateIsPreserved() {
        assertEquals(60.0, kw(60.0), 1e-9);
        assertEquals(150.0, kw(150.0), 1e-9);
        assertEquals(250.0, kw(250.0), 1e-9);
    }

    /**
     * Sentinels are rejected by the caller's bounds, not by arithmetic here.
     *
     * <p>Worth pinning: the old code divided 104857.5 into 1048.575, which fell outside the
     * accept band only by luck of that particular constant. Unscaled, every documented sentinel
     * is far outside the +/-500 envelope the admission gate enforces, so rejection no longer
     * depends on what a division happens to produce.
     */
    @Test
    public void sentinelsStayOutOfBand() {
        assertTrue("104857.5 must stay out of the +/-500 envelope", Math.abs(kw(104857.5)) > 500);
        assertTrue("65535 must stay out of the +/-500 envelope", Math.abs(kw(65535.0)) > 500);
        assertTrue("-10011 must stay out of the +/-500 envelope", Math.abs(kw(-10011.0)) > 500);
    }

    /** Sign is preserved — a discharge reading must not become positive charging power. */
    @Test
    public void signIsPreserved() {
        assertEquals(-189.5, kw(-189.5), 1e-9);
        assertEquals(-5.0, kw(-5.0), 1e-9);
    }
}
