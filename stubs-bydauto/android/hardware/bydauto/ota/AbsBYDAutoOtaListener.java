package android.hardware.bydauto.ota;

/**
 * Stub listener for BYDAutoOtaDevice.
 * 
 * Since BYDAutoOtaDevice doesn't support listeners in the actual API,
 * this is a placeholder that won't be used.
 */
public abstract class AbsBYDAutoOtaListener {
    
    /**
     * Called when battery power voltage changes.
     * Note: This callback is never actually invoked since OTA device doesn't support listeners.
     */
    public void onBatteryPowerVoltageChanged(double voltage) {
        // Stub - not called
    }
}
