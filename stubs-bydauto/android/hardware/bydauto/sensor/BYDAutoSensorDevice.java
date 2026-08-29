package android.hardware.bydauto.sensor;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

public class BYDAutoSensorDevice extends AbsBYDAutoDevice {
    private static BYDAutoSensorDevice sInstance;

    protected BYDAutoSensorDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoSensorDevice getInstance(Context context) {
        BYDAutoSensorDevice bYDAutoSensorDevice;
        synchronized (BYDAutoSensorDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoSensorDevice(context);
            }
            bYDAutoSensorDevice = sInstance;
        }
        return bYDAutoSensorDevice;
    }

    public int getType() {
        return 0;
    }
}
