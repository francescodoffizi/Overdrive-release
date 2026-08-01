package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins {@link BydDataCollector#isPlausibleOemSohPercent(Integer)} — the accept band for the OEM
 * state-of-health index.
 *
 * <p><b>Why 0 must be rejected.</b> Every failure mode in the SOH read cascade lands on 0: an
 * absent feature id, and a probe ladder whose requested width disagrees with the extractor
 * (BYDAutoEventValue's intValue/doubleValue are independent and the HAL fills only the one asked
 * for, so a Double-first probe leaves intValue at its default — see
 * {@link BydDeviceHelperGetTypeTest#intFirstProbeIsUsedForTheOemSohIndex}). Unlike most sentinels
 * 0 is IN the natural 0..100 percentage range, so a naive range check passes it through. The
 * consequences are not cosmetic: {@code sohPercent} feeds the BATTERY_SOH automation trigger
 * (which accepts {@code >= 0}, so a 0 fires user automations) and the {@code soh_oem} MQTT field
 * (which would publish "0% health" for a healthy pack).
 *
 * <p>This predicate gates SIX sites in the cascade — three tier entry checks, the two per-tier
 * accepts, and the final publish. They must all agree, which is why the band lives in one
 * function instead of being spelled out at each site.
 */
public class OemSohBandTest {

    /** 0 is the value every read failure produces, and it is never a real pack health. */
    @Test
    public void zeroIsRejected() {
        assertFalse("0% health is impossible — it means 'no reading'",
                BydDataCollector.isPlausibleOemSohPercent(0));
    }

    /** A tier that has not produced a value yet must not be treated as a reading. */
    @Test
    public void nullIsRejected() {
        assertFalse(BydDataCollector.isPlausibleOemSohPercent(null));
    }

    /**
     * Negatives are rejected. Tier 1 casts a Double/Float getter result to int, so a HAL error
     * code (BYD uses large negatives) can arrive here as a negative percentage.
     */
    @Test
    public void negativesAreRejected() {
        assertFalse(BydDataCollector.isPlausibleOemSohPercent(-1));
        assertFalse(BydDataCollector.isPlausibleOemSohPercent(-40));
        assertFalse(BydDataCollector.isPlausibleOemSohPercent(Integer.MIN_VALUE));
    }

    /** Above 100 is not a percentage — commonly a raw sentinel or a different unit. */
    @Test
    public void aboveFullHealthIsRejected() {
        assertFalse(BydDataCollector.isPlausibleOemSohPercent(101));
        assertFalse(BydDataCollector.isPlausibleOemSohPercent(1000));
        assertFalse("the BYD 16-bit sentinel must not pass",
                BydDataCollector.isPlausibleOemSohPercent(65535));
        assertFalse(BydDataCollector.isPlausibleOemSohPercent(Integer.MAX_VALUE));
    }

    /** The real range, including both edges: 1% (a dead-but-present pack) and 100% (new). */
    @Test
    public void realReadingsAreAccepted() {
        assertTrue("1 is a legal, if alarming, reading",
                BydDataCollector.isPlausibleOemSohPercent(1));
        assertTrue(BydDataCollector.isPlausibleOemSohPercent(87));
        assertTrue("a healthy in-warranty pack",
                BydDataCollector.isPlausibleOemSohPercent(92));
        assertTrue("100 is the top of the band, not past it",
                BydDataCollector.isPlausibleOemSohPercent(100));
    }
}
