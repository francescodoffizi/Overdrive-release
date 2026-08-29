package android.hardware.bydauto.radar;

import android.hardware.IBYDAutoListener;

/**
 * Stub for BYD Radar Listener.
 */
public class AbsBYDAutoRadarListener implements IBYDAutoListener {
    
    public void onRadarProbeStateChanged(int area, int state) {
    }
    
    public void onReverseRadarSwitchStateChanged(int state) {
    }
    
    public void onRadarSystemStateChanged(int state) {
    }
    
    public void onRadarVolumeChanged(int volume) {
    }
    
    public void onRadarMuteStateChanged(int state) {
    }
    
    public void onFrontRadarSwitchStateChanged(int state) {
    }
    
    public void onSideRadarSwitchStateChanged(int state) {
    }
}
