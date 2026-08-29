package android.hardware.bydauto.engine;

import android.hardware.IBYDAutoListener;

public abstract class AbsBYDAutoEngineListener implements IBYDAutoListener {

    public void onEngineSpeedChanged(int value) {
    }

    public void onEngineCoolantLevelChanged(int state) {
    }

    public void onOilLevelChanged(int value) {
    }
}
