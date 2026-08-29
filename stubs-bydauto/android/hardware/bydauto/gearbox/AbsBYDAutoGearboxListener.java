package android.hardware.bydauto.gearbox;

import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.IBYDAutoEvent;

public abstract class AbsBYDAutoGearboxListener {
    public void onBrakeFluidLevelChanged(int level) {}
    public void onBrakePedalStateChanged(int state) {}
    public void onCurrentGearChanged(int gear) {}
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onDataEventChanged(int eventId, BYDAutoEventValue value) {}
    public void onEPBStateChanged(int state) {}
    public void onError(int code, String msg) {}
    public void onGearboxAutoModeTypeChanged(int type) {}
    public void onGearboxManualModeLevelChanged(int level) {}
    public void onGearboxStateChanged(int state) {}
    public void onParkBrakeSwitchChanged(int state) {}
}
