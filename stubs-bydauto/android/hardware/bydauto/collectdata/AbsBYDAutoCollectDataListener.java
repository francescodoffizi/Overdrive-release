package android.hardware.bydauto.collectdata;

// Compile-only stub. Real class (concrete) provided at runtime (DiLink-5 dilink5-sdk.jar; absent on
// DiLink-3 — harmless, listener just never fires). collectdata getters are dead; these EVENTS carry
// HV bus V/I + motor temp/RPM/torque. Signature: (int a, int b) where b = value, a = signal tag.
public class AbsBYDAutoCollectDataListener {
    public AbsBYDAutoCollectDataListener() {}
    public void onMotorMCUGeneratrixVolt(int a, int b) {}
    public void onMotorMCUGeneratrixCurrent(int a, int b) {}
    public void onDriverMotorTemperature(int a, int b) {}
    public void onDriverMotorSpeed(int a, int b) {}
    public void onDriverMotorTorque(int a, int b) {}
    public void onError(int code, String msg) {}
}
