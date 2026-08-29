package android.hardware.bydauto.light;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

public class BYDAutoLightDevice extends AbsBYDAutoDevice {
    // Light TYPE list — values per the SDK javadoc constant table (doc/constant-values.html).
    public static final int LIGHT_SIDE = 1;
    public static final int LIGHT_LOW_BEAM = 2;
    public static final int LIGHT_HIGH_BEAM = 3;
    public static final int LIGHT_LEFT_TURN_SIGNAL = 4;
    public static final int LIGHT_RIGHT_TURN_SIGNAL = 5;
    public static final int LIGHT_FRONT_FOG = 6;
    public static final int LIGHT_REAR_FOG = 7;
    public static final int LIGHT_FOOT = 8;

    public static final int LIGHT_STATE_OFF = 0;
    public static final int LIGHT_STATE_ON = 1;

    private static BYDAutoLightDevice sInstance;

    protected BYDAutoLightDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoLightDevice getInstance(Context context) {
        BYDAutoLightDevice bYDAutoLightDevice;
        synchronized (BYDAutoLightDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoLightDevice(context);
            }
            bYDAutoLightDevice = sInstance;
        }
        return bYDAutoLightDevice;
    }

    public int getLightStatus(int lightType) {
        return 0;
    }

    public int getLightAutoStatus() {
        return 0;
    }

    public int getTurnLightFlashState() {
        return 1;
    }

    public int getType() {
        return 0;
    }
}
