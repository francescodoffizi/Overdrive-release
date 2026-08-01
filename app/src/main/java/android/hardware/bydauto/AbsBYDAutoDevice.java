package android.hardware.bydauto;

import android.content.Context;
import android.hardware.IBYDAutoListener;

public class AbsBYDAutoDevice {
    public AbsBYDAutoDevice(Context context) {
    }

    public int get(int i, int i2) {
        return 0;
    }

    // ==================== TYPED READS (deviceType, featureId) ====================
    // The platform's AbsBYDAutoDevice declares these directly, so EVERY BYD device inherits them.
    // They were absent from this stub, which made the whole accessor family invisible to us — we
    // only ever probed the per-device get(int[], Class) form. A trim may implement one and not the
    // other, and a device-level read needs no manager handle at all, so this is the cheapest second
    // channel available. Reached reflectively (BydDeviceHelper.callGetDouble); declared here so the
    // surface is documented and discoverable rather than guessed at.
    public double getDouble(int deviceType, int featureId) {
        return Double.NaN;
    }

    public double[] getDoubleArray(int deviceType, int[] featureIds) {
        return null;
    }

    public int[] getIntArray(int deviceType, int[] featureIds) {
        return null;
    }

    public byte[] getBuffer(int deviceType, int featureId) {
        return null;
    }

    public void registerListener(IBYDAutoListener iBYDAutoListener) {
    }

    public void registerListener(IBYDAutoListener iBYDAutoListener, int[] iArr) {
    }

    public int set(int i, int i2, int i3) {
        return 0;
    }
}
