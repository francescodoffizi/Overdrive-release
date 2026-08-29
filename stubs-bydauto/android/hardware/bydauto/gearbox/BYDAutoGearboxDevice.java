package android.hardware.bydauto.gearbox;

import android.content.Context;
public class BYDAutoGearboxDevice {
    public static BYDAutoGearboxDevice getInstance(Context context) { return null; }
    public void registerListener(AbsBYDAutoGearboxListener l) {}
    public void unregisterListener(AbsBYDAutoGearboxListener l) {}
    public String getGearboxCode() { return "P"; }
    public int getGearboxState() { return 0; }
}