package android.hardware.bydauto.ota;

import android.content.Context;

public class BYDAutoOtaDevice {
    private static BYDAutoOtaDevice sInstance;

    public static synchronized BYDAutoOtaDevice getInstance(Context context) {
        BYDAutoOtaDevice bYDAutoOtaDevice;
        synchronized (BYDAutoOtaDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoOtaDevice();
            }
            bYDAutoOtaDevice = sInstance;
        }
        return bYDAutoOtaDevice;
    }

    public double getBatteryPowerVoltage() {
        return 12.0d;
    }

    public double getBatteryVoltage() {
        return 0.0d;
    }
    
    /**
     * Register listener (stub - not actually supported by OTA device).
     */
    public void registerListener(AbsBYDAutoOtaListener listener) {
        // Stub - OTA device doesn't support listeners
    }
    
    /**
     * Unregister listener (stub - not actually supported by OTA device).
     */
    public void unregisterListener(AbsBYDAutoOtaListener listener) {
        // Stub - OTA device doesn't support listeners
    }
}
