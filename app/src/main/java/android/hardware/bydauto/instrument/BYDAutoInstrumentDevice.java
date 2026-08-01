package android.hardware.bydauto.instrument;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;
import java.util.ArrayList;
import java.util.List;

public class BYDAutoInstrumentDevice extends AbsBYDAutoDevice {
    public static final int HP = 2;
    public static final int KW = 1;
    public static final int POWER_UNIT = 0;
    private static BYDAutoInstrumentDevice sInstance;
    private final List<AbsBYDAutoInstrumentListener> listeners = new ArrayList();

    protected BYDAutoInstrumentDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoInstrumentDevice getInstance(Context context) {
        BYDAutoInstrumentDevice bYDAutoInstrumentDevice;
        synchronized (BYDAutoInstrumentDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoInstrumentDevice(context);
            }
            bYDAutoInstrumentDevice = sInstance;
        }
        return bYDAutoInstrumentDevice;
    }

    public double getLast50KmPowerConsume() {
        return 0.0d;
    }

    public int getMileageUnit() {
        return 1;
    }

    public int getOutCarTemperature() {
        return 25;
    }

    /**
     * Compile-time stub. Returns -1 (a HAL failure code), NOT the 0 the other stubs return.
     *
     * <p>Same reasoning as {@code BYDAutoSafetyBeltDevice.getPassengerStatus}: 0 is not a neutral
     * placeholder here — it is UNBUCKLED, a state automations act on. A fabricated 0 fires a
     * permanent "seatbelt unbuckled" for BOTH seats at boot and stops any "when buckled" rule
     * from ever firing.
     *
     * <p>-1 rather than INVALID(2), deliberately: {@code sanitizeSeatbelt} maps 2 to UNBUCKLED
     * for the driver (a best-effort rule so the driver's off-edge publishes), so 2 would only
     * half-fix this. -1 is in {@code SEATBELT_FAILURE_CODES}, so BOTH seats read UNAVAILABLE and
     * nothing is published — the only safe answer a stub can give.
     *
     * <p>Normally unreachable: on a BYD head unit the boot classloader's real class wins. But
     * {@code Class.forName} resolves THIS class on a trim whose framework lacks it, and the
     * primary device path has no stub guard (only {@code BydManagerChannel}'s tier-B fallback
     * checks {@code isOurOwnStub}), so the stub can become the live handle.
     */
    public int getSafetyBeltStatus(int i) {
        return -1; // HAL failure code → UNAVAILABLE for both seats; deliberately not UNBUCKLED(0)
    }

    public int getType() {
        return 0;
    }

    public int getUnit(int i) {
        return 1;
    }

    /**
     * Gets the external charging power in KW.
     * This is the actual charging power from the charger.
     * @return Charging power in KW (positive = charging, negative = discharging)
     */
    public double getExternalChargingPower() {
        return 0.0d;
    }

    public void registerListener(AbsBYDAutoInstrumentListener absBYDAutoInstrumentListener) {
        if (absBYDAutoInstrumentListener != null) {
            synchronized (this.listeners) {
                if (!this.listeners.contains(absBYDAutoInstrumentListener)) {
                    this.listeners.add(absBYDAutoInstrumentListener);
                }
            }
        }
    }
}
