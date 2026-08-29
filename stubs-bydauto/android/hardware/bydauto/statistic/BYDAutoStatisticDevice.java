package android.hardware.bydauto.statistic;
import android.content.Context;
// DiLink-5 compile-only stub (D5 signatures: getTotalMileageValue is float).
public class BYDAutoStatisticDevice {
    public static BYDAutoStatisticDevice getInstance(Context context) { return null; }
    public void registerListener(AbsBYDAutoStatisticListener l) {}
    public void registerListener(AbsBYDAutoStatisticListener l, int[] featureIds) {}
    public void unregisterListener(AbsBYDAutoStatisticListener l) {}
    public double getElecPercentageValue() { return 0.0; }
    public float getTotalMileageValue() { return 0.0f; }
    public int getElecDrivingRangeValue() { return 0; }
    public float getEVRemainingBatteryPower() { return 0.0f; }
    public int getEVMileageValue() { return 0; }
    public int getType() { return 0; }
}
