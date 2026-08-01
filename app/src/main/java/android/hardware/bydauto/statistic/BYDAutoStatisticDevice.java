package android.hardware.bydauto.statistic;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

import java.util.ArrayList;
import java.util.List;

public class BYDAutoStatisticDevice extends AbsBYDAutoDevice {
    private static BYDAutoStatisticDevice sInstance;
    private final List<AbsBYDAutoStatisticListener> listeners = new ArrayList<>();

    protected BYDAutoStatisticDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoStatisticDevice getInstance(Context context) {
        BYDAutoStatisticDevice bYDAutoStatisticDevice;
        synchronized (BYDAutoStatisticDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoStatisticDevice(context);
            }
            bYDAutoStatisticDevice = sInstance;
        }
        return bYDAutoStatisticDevice;
    }

    public int getEVMileageValue() {
        return 0;
    }

    public double getElecPercentageValue() {
        return 0.0d;
    }

    public double getTotalElecConValue() {
        return 0.0d;
    }

    public double getTotalFuelConValue() {
        return 0.0d;
    }

    /**
     * Get the lifetime average fuel consumption per 100 km.
     * @return L/100km (SDK range 0.0-51.1)
     */
    public double getTotalFuelConPHMValue() {
        return 0.0d;
    }

    /**
     * Get the last trip's average fuel consumption per 100 km.
     * @return L/100km (SDK range 0.0-51.1)
     */
    public double getLastFuelConPHMValue() {
        return 0.0d;
    }

    /**
     * Get the lifetime average electricity consumption per 100 km.
     * @return kWh/100km
     */
    public double getTotalElecConPHMValue() {
        return 0.0d;
    }

    /**
     * Get the last trip's average electricity consumption per 100 km.
     * @return kWh/100km
     */
    public double getLastElecConPHMValue() {
        return 0.0d;
    }

    public int getTotalMileageValue() {
        return 0;
    }

    /**
     * Get a distance register in 0.1 km units — finer than
     * {@link #getTotalMileageValue()}, which reports whole km.
     * @param kind which register to read (2 = cumulative total distance)
     * @return distance in 0.1 km units
     */
    public int getMileageNumber(int kind) {
        return 0;
    }
    /**
     * Get the coolant/battery temperature.
     * On BYD EVs with liquid cooling, this closely tracks HV battery temperature.
     * @return Temperature value (raw int, may need conversion)
     */
    public int getWaterTemperature() {
        return 0;
    }

    public int getType() {
        return 0;
    }
    
    /**
     * Get the electric driving range value in km.
     * @return Electric driving range (0-999 km typically)
     */
    public int getElecDrivingRangeValue() {
        return 0;
    }
    
    /**
     * Get the fuel driving range value in km.
     * @return Fuel driving range (0-999 km typically)
     */
    public int getFuelDrivingRangeValue() {
        return 0;
    }
    
    /**
     * Get the fuel percentage value.
     * @return Fuel percentage (0-100)
     */
    public int getFuelPercentageValue() {
        return 0;
    }
    
    /**
     * Get the current driving time.
     * @return Driving time in hours
     */
    public double getDrivingTimeValue() {
        return 0.0d;
    }
    
    /**
     * Get the key battery level.
     * @return Key battery level (0=low, 1=normal)
     */
    public int getKeyBatteryLevel() {
        return 1;
    }
    
    /**
     * Register a listener for statistic changes.
     * @param listener The listener to register
     */
    public void registerListener(AbsBYDAutoStatisticListener listener) {
        if (listener != null) {
            synchronized (this.listeners) {
                if (!this.listeners.contains(listener)) {
                    this.listeners.add(listener);
                }
            }
        }
    }
    
    /**
     * Unregister a listener.
     * @param listener The listener to unregister
     */
    public void unregisterListener(AbsBYDAutoStatisticListener listener) {
        if (listener != null) {
            synchronized (this.listeners) {
                this.listeners.remove(listener);
            }
        }
    }
}
