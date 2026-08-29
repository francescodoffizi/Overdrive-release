package android.hardware;

import android.content.Context;

public class BYDAutoManager {

    public interface OnBYDAutoListener {
        void onChanged(int i, int i2, float f, Object obj);

        void onChanged(int i, int i2, int i3, Object obj);

        void onChanged(int i, int i2, byte[] bArr, Object obj);

        void onError(int i, String str);
    }

    public BYDAutoManager(Context context) {
    }

    public byte[] getBuffer(int i, int i2) {
        return new byte[0];
    }

    public double getDouble(int i, int i2) {
        return 0.0d;
    }

    public int getInt(int i, int i2) {
        return 0;
    }

    public int setBuffer(int i, int i2, byte[] bArr) {
        return 0;
    }

    public int setDouble(int i, int i2, double d) {
        return 0;
    }

    public int setInt(int i, int i2, int i3) {
        return 0;
    }
}
