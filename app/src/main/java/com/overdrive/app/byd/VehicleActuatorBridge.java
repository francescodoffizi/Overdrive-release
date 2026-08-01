package com.overdrive.app.byd;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Daemon-side bridge that ALSO dispatches mirror-fold / HUD to the app-process
 * {@code VehicleActuatorService}, so the write is attempted from a real foreground-app
 * Context (UID 10xxx) — the environment where the OEM {@code setMirrorFoldState} /
 * {@code setHUDBrightness} calls (and the HUD-switch feature-id write) actually actuate.
 * The daemon's own in-process attempts (see {@link BydDataCollector#setMirrorsFolded}
 * / {@link BydDataCollector#setHudBrightness}) run in parallel; whichever environment the
 * HAL honours wins. Uses the SAME proven {@code am start-foreground-service} bridge as
 * {@link AudioPlaybackController} → {@code MediaPlaybackService}.
 */
public final class VehicleActuatorBridge {

    private static final String TAG = "VehicleActuatorBridge";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String SERVICE =
            "com.overdrive.app/.services.VehicleActuatorService";

    private VehicleActuatorBridge() {}

    /** Also fold/unfold the mirrors from the app process (the OEM's environment). */
    public static void dispatchMirror(boolean fold) {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action mirror"
                + " --ez fold " + fold);
        logger.info("mirror fold=" + fold + " also dispatched to app-process VehicleActuatorService");
    }

    /** Also set HUD brightness level (0..100) from the app process. */
    public static void dispatchHud(int level) {
        if (level < 0 || level > 100) return;
        exec("am start-foreground-service -n " + SERVICE
                + " --es action hud"
                + " --ei level " + level);
        logger.info("hud level=" + level + " also dispatched to app-process VehicleActuatorService");
    }

    /** Set the dedicated HUD power switch (on/off) from the app process — distinct from
     *  brightness. The service writes SET_HUD_SWITCH_SET (1=on/2=off) where it actuates. */
    public static void dispatchHudPower(boolean on) {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action hud_power"
                + " --ez on " + on);
        logger.info("hud_power on=" + on + " also dispatched to app-process VehicleActuatorService");
    }

    /** Fire-and-forget {@code am} exec — identical to {@link AudioPlaybackController}'s. */
    private static void exec(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable t) {
            logger.warn("exec failed [" + cmd + "]: " + t.getMessage());
        }
    }
}
