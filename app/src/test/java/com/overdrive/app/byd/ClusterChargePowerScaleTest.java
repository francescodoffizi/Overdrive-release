package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins {@link BydDataCollector#scaleClusterChargePowerKw(double)}.
 *
 * <p>The cluster charge-power feature id (0x32300018) is reported in TWO different units
 * depending on firmware family — hectowatts on the family where a 1.8 kW charge reads ~189.5,
 * and plain kW on others. There is no unit flag, so the scale has to be inferred from the
 * magnitude, and getting it wrong is silent: the result is a plausible-looking but wrong number
 * on the top-priority charging-power source.
 *
 * <p>The boundary is a genuine judgement call, not a derivable fact: our only field evidence
 * (a Seal U DM-i reporting ~221.7 raw for a ~1.9 kW charge) says hectowatts, while the OEM app
 * reads this same feature id with NO division at all and accepts 0..500 as kW. Both cannot hold
 * for one firmware, so the split is: at or below the AC/onboard-charger ceiling (22 kW) the
 * value is taken as kW; above it, as hectowatts. These tests pin that split exactly, so the
 * accepted trade-off stays deliberate instead of drifting on a later edit.
 */
public class ClusterChargePowerScaleTest {

    private static double kw(double raw) {
        return BydDataCollector.scaleClusterChargePowerKw(raw);
    }

    /** The field-observed case: a 1.8 kW AC charge reports ~189.5 raw (hectowatts). */
    @Test
    public void hectowattReadingScalesDown() {
        assertEquals(1.895, kw(189.5), 1e-6);
        assertEquals(0.5, kw(50.0000001), 0.01);   // just above the AC ceiling → hectowatts
        assertEquals(1.2, kw(120.0), 1e-6);
    }

    /**
     * A DC fast charge expressed in HECTOWATTS must scale to the right kW.
     *
     * <p>Note what this does NOT claim. The 22..500 raw band is resolved as hectowatts by
     * design — see {@code scaleClusterChargePowerKw}'s javadoc: our only field evidence
     * (a Seal U DM-i reporting ~221.7 raw for a ~1.9 kW charge) is hectowatts, so a raw 60 is
     * read as 0.6 kW, not 60 kW.
     *
     * <p>That residual ambiguity is contained by the CONSUMER rather than here:
     * {@code VehicleDataMonitor.getChargingState()} uses this value on PHEV only, and a PHEV
     * onboard charger cannot reach the ambiguous band, so the guess can never be wrong on the
     * drivetrain that consumes it. A BEV keeps using {@code chargePowerKw}, which needs no
     * scale guess. This test pins the chosen conversion exactly so the trade stays a deliberate
     * decision rather than drifting.
     *
     * <p>(An earlier version of this test asserted `out >= 0.2` over that band and claimed it
     * guarded a "60 → 0.6" bug — it could not: 0.6 satisfies 0.2. Assert exact values.)
     */
    @Test
    public void dcFastChargeInHectowattsScalesCorrectly() {
        assertEquals(60.0, kw(6000.0), 1e-6);     // 60 kW as hectowatts
        assertEquals(150.0, kw(15000.0), 1e-6);   // 150 kW as hectowatts
        assertEquals(250.0, kw(25000.0), 1e-6);   // 250 kW as hectowatts

        // The 22..500 band is hectowatts by design — pin the exact conversions.
        assertEquals(0.23, kw(23.0), 1e-9);
        assertEquals(0.60, kw(60.0), 1e-9);
        assertEquals(2.50, kw(250.0), 1e-9);
    }

    /** At or below the AC/onboard-charger ceiling the value is already kW. */
    @Test
    public void acRangeIsTakenAsKw() {
        assertEquals(1.8, kw(1.8), 1e-6);
        assertEquals(2.9, kw(2.9), 1e-6);
        assertEquals(7.13, kw(7.13), 1e-6);
        assertEquals(11.0, kw(11.0), 1e-6);
        assertEquals(22.0, kw(22.0), 1e-6);        // exactly the ceiling → kW
    }

    /** Above the max plausible kW the value can only be a smaller unit. */
    @Test
    public void impossiblyLargeRawIsHectowatts() {
        assertEquals(60.0, kw(6000.0), 1e-6);
        assertEquals(120.0, kw(12000.0), 1e-6);
        // The BYD sentinel: whatever it scales to must be out of the caller's accept band
        // (>0.1 && <=500), so it can never be published.
        double sentinel = kw(104857.5);
        assertTrue("sentinel must not land in the accept band", sentinel > 500 || sentinel <= 0.1);
    }

    /** Sign is preserved — a negative reading must not silently become positive power. */
    @Test
    public void signIsPreserved() {
        assertTrue(kw(-189.5) < 0);
        assertTrue(kw(-5.0) < 0);
    }
}
