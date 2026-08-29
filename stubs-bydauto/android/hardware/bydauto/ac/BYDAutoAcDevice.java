package android.hardware.bydauto.ac;

import android.content.Context;

public class BYDAutoAcDevice {
    private static BYDAutoAcDevice sInstance;

    public static synchronized BYDAutoAcDevice getInstance(Context context) {
        BYDAutoAcDevice bYDAutoAcDevice;
        synchronized (BYDAutoAcDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoAcDevice();
            }
            bYDAutoAcDevice = sInstance;
        }
        return bYDAutoAcDevice;
    }

    public int getTemperatureUnit() {
        return 0;
    }

    public int getTemprature(int i) {
        return 20;
    }
}
