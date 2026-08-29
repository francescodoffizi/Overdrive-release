package android.hardware.bydauto.tyre;

import android.content.Context;
public class BYDAutoTyreDevice {
    public static BYDAutoTyreDevice getInstance(Context context) { return null; }
    public void registerListener(AbsBYDAutoTyreListener l) {}
    public void unregisterListener(AbsBYDAutoTyreListener l) {}
    public float getTyrePressureLeftFront() { return 0; }
    public float getTyrePressureRightFront() { return 0; }
    public float getTyrePressureLeftRear() { return 0; }
    public float getTyrePressureRightRear() { return 0; }
}