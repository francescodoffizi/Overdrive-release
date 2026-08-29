package android.hardware.bydauto.engine;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

import java.util.ArrayList;
import java.util.List;

public class BYDAutoEngineDevice extends AbsBYDAutoDevice {

    // Engine power range (kW)
    public static final int ENGINE_POWER_MIN = -100;
    public static final int ENGINE_POWER_MAX = 300;

    // Engine speed range (RPM)
    public static final int ENGINE_SPEED_MIN = 0;
    public static final int ENGINE_SPEED_MAX = 8000;

    // Engine capacity range (kW)
    public static final int ENGINE_CAPACITY_MIN = 0;
    public static final int ENGINE_CAPACITY_MAX = 250;

    // Coolant level states
    public static final int ENGINE_COOLANT_LEVEL_NORMAL = 0;
    public static final int ENGINE_COOLANT_LEVEL_LOW = 1;

    // Engine displacement range
    public static final double ENGINE_DISPLACEMENT_MIN = 0.0d;
    public static final double ENGINE_DISPLACEMENT_MAX = 10.0d;

    // Engine oil range
    public static final int ENGINE_OIL_MIN = 0;
    public static final int ENGINE_OIL_MAX = 254;

    // Command result codes
    public static final int ENGINE_COMMAND_SUCCESS = 0;
    public static final int ENGINE_COMMAND_FAILED = -2147482648;
    public static final int ENGINE_COMMAND_TIMEOUT = -2147482646;
    public static final int ENGINE_COMMAND_BUSY = -2147482647;
    public static final int ENGINE_COMMAND_INVALID_VALUE = -2147482645;

    // Engine type codes
    public static final String ENGINE_TYPE1 = "BYD371QA";
    public static final String ENGINE_TYPE2 = "BYD473QA";
    public static final String ENGINE_TYPE3 = "BYD473QB";
    public static final String ENGINE_TYPE4 = "BYD473QE";
    public static final String ENGINE_TYPE5 = "BYD476ZQA";
    public static final String ENGINE_TYPE6 = "BYD476ZQB";
    public static final String ENGINE_TYPE7 = "BYD487ZQA";
    public static final String ENGINE_TYPE8 = "BYD487ZQB";
    public static final String ENGINE_TYPE9 = "SD13";
    public static final String ENGINE_TYPE10 = "SD15";
    public static final String ENGINE_TYPE11 = "SD20";
    public static final String ENGINE_TYPE12 = "BYD473QF";
    public static final String ENGINE_TYPE13 = "BYD473QG";
    public static final String ENGINE_TYPE14 = "BYD487ZQC";
    public static final String ENGINE_TYPE15 = "BYD487ZQD";

    protected static final String TAG = "BYDAutoEngineDevice";

    private static BYDAutoEngineDevice sInstance;
    private final List<AbsBYDAutoEngineListener> listeners = new ArrayList<>();

    protected BYDAutoEngineDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoEngineDevice getInstance(Context context) {
        BYDAutoEngineDevice bYDAutoEngineDevice;
        synchronized (BYDAutoEngineDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoEngineDevice(context);
            }
            bYDAutoEngineDevice = sInstance;
        }
        return bYDAutoEngineDevice;
    }

    public int getEnginePower() {
        return 0;
    }

    public int getEngineSpeed() {
        return 0;
    }

    public int getEngineCoolantLevel() {
        return 0;
    }

    public double getEngineDisplacement() {
        return 0.0d;
    }

    public String getEngineCode() {
        return "";
    }

    public int getOilLevel() {
        return 0;
    }

    public int getType() {
        return 0;
    }

    public void registerListener(AbsBYDAutoEngineListener listener) {
        if (listener != null) {
            synchronized (this.listeners) {
                if (!this.listeners.contains(listener)) {
                    this.listeners.add(listener);
                }
            }
        }
    }

    public void unregisterListener(AbsBYDAutoEngineListener listener) {
        if (listener != null) {
            synchronized (this.listeners) {
                this.listeners.remove(listener);
            }
        }
    }
}
