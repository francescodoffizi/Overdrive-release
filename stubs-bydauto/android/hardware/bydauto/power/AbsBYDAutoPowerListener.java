package android.hardware.bydauto.power;

import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.IBYDAutoEvent;

/** Minimal listener shape used by the platform adapter. */
public abstract class AbsBYDAutoPowerListener {
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onDataEventChanged(int eventId, BYDAutoEventValue value) {}
    public void onError(int code, String msg) {}
    // Optional platform callbacks.
    public void onMcuStatusChanged(int status) {}
    public void onPowerCtlStatusChanged(int index, int status) {}
    public void onBatteryRemainPowerEVChanged(double power) {}
    public void onBatteryLowVoltageStateChanged(int state) {}
    public void onShutdownInfoChanged(int index, Object info) {}
}
