package android.hardware.bydauto.charging;

import android.content.Context;
public class BYDAutoChargingDevice {
    public static BYDAutoChargingDevice getInstance(Context context) { return null; }
    public void registerListener(AbsBYDAutoChargingListener l) {}
    public void unregisterListener(AbsBYDAutoChargingListener l) {}
    public int getChargeState() { return 0; }
    public double getChargePower() { return 0; }
    public double getBatteryTemp() { return 0; }
    public int getRemainChargingTime() { return 0; }
}