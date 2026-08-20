package android.hardware.bydauto.power;

import android.hardware.IBYDAutoListener;

/**
 * SDK stub for AbsBYDAutoPowerListener. The real class lives in BYD's
 * platform jar (bmmcamera.jar) and is loaded at runtime by the daemon
 * processes via the classpath in DaemonLauncher.kt. This stub exists so
 * AccSentryDaemon can subclass at compile time.
 *
 * Source signal we care about (from oem bk/C1478c.java:71-75):
 *   onPowerCtlStatusChanged(eventId, value)
 *     eventId = -1728053193 (0x99000037) → ACC state event
 *       value 0 → ACC OFF
 *       value 1 → ACC ON
 */
public class AbsBYDAutoPowerListener implements IBYDAutoListener {
    public void onPowerCtlStatusChanged(int eventId, int value) {
    }

    public void onMcuStatusChanged(int status) {
    }

    public void onBatteryRemainPowerEVChanged(double powerKwh) {
    }

    public void onBatteryVoltageChanged(int millivolts) {
    }

    public void onChargingStateChanged(int state) {
    }
}
