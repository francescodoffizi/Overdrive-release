package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for how {@link BydDeviceHelper} calls the BYD HAL's
 * {@code get(int[], Class)} feature-ID reader.
 *
 * <p><b>Why this matters.</b> The HAL dispatches on the EXACT {@code Class} object handed to it
 * and recognises the PRIMITIVE ones ({@code Double.TYPE}); the boxed wrappers
 * ({@code Double.class}) are a different {@code Class} instance and are not matched. Call sites
 * here historically passed the wrapper, so reads that should have worked returned nothing — the
 * charging-power feature ID (0x32300018) among them, which is why PHEV charge power fell back to
 * an estimate. These tests pin the two behaviours that fix it:
 *
 * <ol>
 *   <li>{@code callGet} normalizes a wrapper argument to its primitive, so every existing call
 *       site is fixed without being touched.</li>
 *   <li>{@code callGetProbing} walks the primitive widths until one answers, for values whose
 *       HAL-side type differs between trims.</li>
 * </ol>
 *
 * <p>The fake device below stands in for the real HAL: it accepts ONLY primitive Class
 * arguments and throws {@code IllegalArgumentException} otherwise, which is the behaviour the
 * OEM app's own probe-and-catch reader implies.
 */
public class BydDeviceHelperGetTypeTest {

    /** Records every Class the caller passed, and answers only for primitives. */
    public static class PrimitiveOnlyDevice {
        public final List<Class<?>> seen = new ArrayList<>();
        /** Which primitive this "firmware" stores the value as. */
        private final Class<?> accepts;
        private final Object value;

        PrimitiveOnlyDevice(Class<?> accepts, Object value) {
            this.accepts = accepts;
            this.value = value;
        }

        public Object get(int[] ids, Class<?> type) {
            seen.add(type);
            if (type != null && !type.isPrimitive()) {
                // The real HAL does not match a boxed wrapper.
                throw new IllegalArgumentException("unsupported type " + type);
            }
            if (type == accepts) return value;
            throw new IllegalArgumentException("wrong width " + type);
        }
    }

    /** A wrapper Class must be converted to its primitive before reaching the HAL. */
    @Test
    public void callGetNormalizesWrapperToPrimitive() {
        PrimitiveOnlyDevice dev = new PrimitiveOnlyDevice(Double.TYPE, 189.5d);

        Object r = BydDeviceHelper.callGet(dev, 0x32300018, Double.class);

        assertNotNull("passing Double.class must still read a Double.TYPE value", r);
        assertEquals(189.5d, BydDeviceHelper.getDoubleValue(r), 1e-9);
        assertEquals("HAL must have been handed the PRIMITIVE class",
                Double.TYPE, dev.seen.get(0));
        assertTrue("no wrapper Class may reach the HAL",
                dev.seen.stream().noneMatch(c -> c != null && !c.isPrimitive()));
    }

    /** Same for the int path, which other feature reads use. */
    @Test
    public void callGetNormalizesIntegerWrapper() {
        PrimitiveOnlyDevice dev = new PrimitiveOnlyDevice(Integer.TYPE, 87);

        Object r = BydDeviceHelper.callGet(dev, 0x44400028, Integer.class);

        assertNotNull(r);
        assertEquals(87, BydDeviceHelper.getIntValue(r));
        assertEquals(Integer.TYPE, dev.seen.get(0));
    }

    /**
     * The probing read must find the value even when the trim stores it as a width the caller
     * did not guess — here Integer while the ladder starts at Double.
     */
    @Test
    public void callGetProbingWalksWidthsUntilOneAnswers() {
        PrimitiveOnlyDevice dev = new PrimitiveOnlyDevice(Integer.TYPE, 1900);

        Object r = BydDeviceHelper.callGetProbing(dev, 0x32300018);

        assertNotNull("probing must succeed on an Integer-typed feature", r);
        assertEquals(1900, BydDeviceHelper.getIntValue(r));
        assertTrue("Double.TYPE should have been tried before Integer.TYPE",
                dev.seen.indexOf(Double.TYPE) < dev.seen.indexOf(Integer.TYPE));
        assertTrue("every probe must use a primitive",
                dev.seen.stream().allMatch(c -> c != null && c.isPrimitive()));
    }

    /** A feature no width answers for must read as absent, not throw. */
    @Test
    public void callGetProbingReturnsNullWhenNoWidthMatches() {
        // Accepts a width that is not on the ladder, so every probe is refused.
        PrimitiveOnlyDevice dev = new PrimitiveOnlyDevice(Short.TYPE, (short) 5);

        assertNull(BydDeviceHelper.callGetProbing(dev, 0x32300018));
        assertTrue("all four ladder widths should have been attempted",
                dev.seen.size() >= 4);
    }

    /** Null device must be tolerated on both paths (daemons call these before init). */
    @Test
    public void nullDeviceIsSafe() {
        assertNull(BydDeviceHelper.callGet(null, 0x32300018, Double.class));
        assertNull(BydDeviceHelper.callGetProbing(null, 0x32300018));
    }

    /** A device exposing ONLY get(int,int) (the shape our compile-time stubs use). */
    public static class IntIntOnlyDevice {
        public int calls = 0;
        public int lastId = 0;
        public int get(int deviceType, int featureId) {
            calls++;
            lastId = featureId;
            return 42;
        }
    }

    /**
     * On a device with only the {@code get(int,int)} overload there is no type argument to
     * probe, so both readers must fall back to a single call and return the value — not skip
     * it. This is the shape the in-tree SDK stubs declare, so a regression here would silently
     * kill every feature read on any trim exposing only that form.
     */
    @Test
    public void intIntOnlyDeviceStillReads() {
        IntIntOnlyDevice a = new IntIntOnlyDevice();
        Object viaCallGet = BydDeviceHelper.callGet(a, 0x32300018, Double.class);
        assertNotNull("callGet must use the (int,int) fallback", viaCallGet);
        assertEquals(42, BydDeviceHelper.getIntValue(viaCallGet));
        assertEquals("exactly one HAL call", 1, a.calls);

        IntIntOnlyDevice b = new IntIntOnlyDevice();
        Object viaProbing = BydDeviceHelper.callGetProbing(b, 0x32300018);
        assertNotNull("callGetProbing must use the same (int,int) fallback", viaProbing);
        assertEquals(42, BydDeviceHelper.getIntValue(viaProbing));
        assertEquals("must NOT probe four widths on a device with no type arg", 1, b.calls);
    }

    /**
     * callGetWithDeviceType must target the (int,int) overload with the caller's EXPLICIT
     * device type — the tier our generic reader skips on devices exposing both signatures.
     */
    @Test
    public void callGetWithDeviceTypeUsesExplicitType() {
        IntIntOnlyDevice d = new IntIntOnlyDevice();
        Object r = BydDeviceHelper.callGetWithDeviceType(d, 1014, 0x44400028);
        assertNotNull(r);
        assertEquals(42, BydDeviceHelper.getIntValue(r));
        assertEquals(0x44400028, d.lastId);
        assertNull("null device must be safe",
                BydDeviceHelper.callGetWithDeviceType(null, 1014, 0x44400028));
    }

    /**
     * A fake BYDAutoEventValue: independent intValue/doubleValue fields, exactly like the real
     * one. The HAL fills ONLY the field matching the requested type.
     */
    public static class FakeEventValue {
        public double doubleValue;
        public int intValue;
    }

    /** Answers any primitive width, but only populates the matching field. */
    public static class FieldWiseDevice {
        public final List<Class<?>> seen = new ArrayList<>();
        private final int trueValue;
        FieldWiseDevice(int trueValue) { this.trueValue = trueValue; }
        public Object get(int[] ids, Class<?> type) {
            seen.add(type);
            FakeEventValue ev = new FakeEventValue();
            if (type == Integer.TYPE || type == Long.TYPE) ev.intValue = trueValue;
            else if (type == Double.TYPE || type == Float.TYPE) ev.doubleValue = trueValue;
            return ev;   // never throws — this HAL accepts every width
        }
    }

    /**
     * REGRESSION: an int-extracting caller must probe INT FIRST.
     *
     * <p>BYDAutoEventValue's intValue/doubleValue are independent, and the HAL fills only the
     * requested one, while {@code getIntValue} always reads intValue. So a Double-first ladder
     * on a HAL that accepts every width returns an object whose intValue is still 0. For a
     * cell-temperature read (raw 0 → -40 °C) that 0 is IN BAND: it passes the range check and
     * publishes -40 °C for a healthy pack, and silently blocks SOH calibration (needs 15-35 °C).
     */
    @Test
    public void intFirstProbeKeepsExtractionAndRequestAligned() {
        FieldWiseDevice dev = new FieldWiseDevice(27);   // 27 raw = -13 C after the -40 offset

        Object intFirst = BydDeviceHelper.callGetProbing(dev, 0x44400028, true);
        assertEquals("int-first must populate the field getIntValue reads",
                27, BydDeviceHelper.getIntValue(intFirst));
        assertEquals("Integer.TYPE must be tried first", Integer.TYPE, dev.seen.get(0));

        // And the default (double-first) ladder demonstrates the hazard this guards against:
        FieldWiseDevice dev2 = new FieldWiseDevice(27);
        Object doubleFirst = BydDeviceHelper.callGetProbing(dev2, 0x44400028);
        assertEquals("double-first leaves intValue at its default — the bug being prevented",
                0, BydDeviceHelper.getIntValue(doubleFirst));
        assertEquals(Double.TYPE, dev2.seen.get(0));
        // ...while the double extraction of that same object is correct:
        assertEquals(27.0, BydDeviceHelper.getDoubleValue(doubleFirst), 1e-9);
    }

    /**
     * Same hazard, second instance: the OEM state-of-health index.
     *
     * <p>Its tier-3 read extracts with {@code getIntValue} and its accept band is 0..100, so a
     * Double-first probe returning intValue=0 lands IN band and reports a healthy pack as
     * "SOH 0%". Worse, the PHEV display chain prefers any OEM value strictly above 0, so the
     * 0 is then discarded as "not reported" — the trim looks like it has no OEM index at all
     * and SOH stays pinned at the 100% seed. Both symptoms come from the ladder disagreeing
     * with the extractor, which is what this pins.
     */
    @Test
    public void intFirstProbeIsUsedForTheOemSohIndex() {
        // 87% health, stored as an int by the HAL.
        FieldWiseDevice dev = new FieldWiseDevice(87);

        Object r = BydDeviceHelper.callGetProbing(dev, 0x44400028, true);
        assertEquals("SOH must read back as the int the HAL holds",
                87, BydDeviceHelper.getIntValue(r));

        // The double-first ladder is what the bug looked like: 0, which is IN the 0..100
        // accept band and therefore published rather than rejected.
        FieldWiseDevice dev2 = new FieldWiseDevice(87);
        int wrong = BydDeviceHelper.getIntValue(BydDeviceHelper.callGetProbing(dev2, 0x44400028));
        assertEquals("double-first yields 0 — in band, so it would be published as SOH 0%",
                0, wrong);
        assertTrue("...and 0 passes the collector's 0..100 range check", wrong >= 0 && wrong <= 100);
    }
}
