package android.hardware.bydauto.speed;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

public class BYDAutoSpeedDevice extends AbsBYDAutoDevice {
    public static final int DEEP_PERSENT_MIN = 0;
    public static final int DEEP_PERSENT_MAX = 100;

    private static BYDAutoSpeedDevice sInstance;

    protected BYDAutoSpeedDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoSpeedDevice getInstance(Context context) {
        BYDAutoSpeedDevice bYDAutoSpeedDevice;
        synchronized (BYDAutoSpeedDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoSpeedDevice(context);
            }
            bYDAutoSpeedDevice = sInstance;
        }
        return bYDAutoSpeedDevice;
    }

    public double getCurrentSpeed() {
        return 0.0d;
    }

    public int getAccelerateDeepness() {
        return 0;
    }

    public int getBrakeDeepness() {
        return 0;
    }

    public int getType() {
        return 0;
    }
}
