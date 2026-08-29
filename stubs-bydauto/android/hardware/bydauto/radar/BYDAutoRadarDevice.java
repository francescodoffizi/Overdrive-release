package android.hardware.bydauto.radar;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;

/**
 * Stub for BYD Radar Device - actual implementation is in BYD system framework.
 * This stub allows compilation; at runtime the real class from the device is used.
 * 
 * The RadarManager uses reflection to call these methods, so they won't be invoked
 * directly from this stub at runtime.
 */
public class BYDAutoRadarDevice extends AbsBYDAutoDevice {
    
    private static BYDAutoRadarDevice sInstance;
    
    public static BYDAutoRadarDevice getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BYDAutoRadarDevice(context);
        }
        return sInstance;
    }
    
    private BYDAutoRadarDevice(Context context) {
        super(context);
    }
    
    public void registerListener(AbsBYDAutoRadarListener listener) {
        // Stub - real implementation in BYD framework
    }
    
    public void unregisterListener(AbsBYDAutoRadarListener listener) {
        // Stub - real implementation in BYD framework
    }
    
    public int[] getAllRadarProbeStates() {
        // Stub - returns empty array, real implementation in BYD framework
        return new int[8];
    }
    
    public int getRadarProbeState(int area) {
        // Stub
        return 0;
    }
    
    public int getReverseRadarSwitchState() {
        // Stub
        return 0;
    }
    
    public int getFrontRadarSwitchState() {
        // Stub
        return 0;
    }
    
    public int getSideRadarSwitchState() {
        // Stub
        return 0;
    }
    
    public int getRadarVolume() {
        // Stub
        return 0;
    }
    
    public void setRadarVolume(int volume) {
        // Stub
    }
    
    public void setReverseRadarSwitch(int state) {
        // Stub
    }
    
    public void setFrontRadarSwitch(int state) {
        // Stub
    }
    
    public void setSideRadarSwitch(int state) {
        // Stub
    }
}
