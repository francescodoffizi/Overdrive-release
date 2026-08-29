package android.hardware.bydauto.safetybelt;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

public class BYDAutoSafetyBeltDevice extends AbsBYDAutoDevice {
    public static final int SAFETY_BELT_UNBUCKLED = 0;
    public static final int SAFETY_BELT_BUCKLED = 1;

    public static final int SEAT_DRIVER = 0;
    public static final int SEAT_FRONT_PASSENGER = 1;

    private static BYDAutoSafetyBeltDevice sInstance;

    protected BYDAutoSafetyBeltDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoSafetyBeltDevice getInstance(Context context) {
        BYDAutoSafetyBeltDevice bYDAutoSafetyBeltDevice;
        synchronized (BYDAutoSafetyBeltDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoSafetyBeltDevice(context);
            }
            bYDAutoSafetyBeltDevice = sInstance;
        }
        return bYDAutoSafetyBeltDevice;
    }

    /**
     * Compile-time stub. Returns -1 (a HAL failure code), NOT the 0 the other stubs return.
     *
     * <p>0 is not a neutral placeholder for this call — it is
     * {@code SAFETY_BELT_PASSENGER_STATE_NOBODY}, a firm "the seat is empty". Callers act on
     * that: the passenger seatbelt occupancy gate forces the belt to UNBUCKLED after two
     * consecutive NOBODY reads, so a fabricated 0 would suppress a real buckled passenger and
     * publish a permanent "empty" occupancy.
     *
     * <p>This normally cannot be reached — on a BYD head unit the boot classloader's real class
     * wins over this one. But {@code Class.forName} resolves THIS class on a trim whose
     * framework lacks it, and the primary device path has no stub guard (only
     * {@code BydManagerChannel}'s tier-B fallback checks {@code isOurOwnStub}), so the stub can
     * become the live handle. -1 is filtered to UNAVAILABLE by the caller and therefore fails
     * OPEN — "no occupancy data, let the belt sensor drive" — which is the only safe answer a
     * stub can give. (-1 rather than INVALID(2) to match the sibling
     * {@code BYDAutoInstrumentDevice.getSafetyBeltStatus} stub, where 2 is deliberately read as
     * UNBUCKLED for the driver and so would only half-fix the same problem.)
     */
    public int getPassengerStatus(int seatPosition) {
        return -1; // HAL failure code → UNAVAILABLE; deliberately not NOBODY(0)
    }

    public int getType() {
        return 0;
    }
}
