package com.overdrive.app.communication;

import com.overdrive.app.logging.DaemonLogger;

/** Shell-daemon lifecycle bridge for app-UID cabin microphone capture. */
public final class CabinAudioController {

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("CabinAudio");
    private static final String SERVICE =
            "com.overdrive.app/.services.CabinAudioCaptureService";

    private CabinAudioController() {}

    public static void start(String token) {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action start --es token " + q(token));
    }

    public static void stop(String token) {
        exec("am startservice -n " + SERVICE
                + " --es action stop --es token " + q(token));
    }

    /** Administrative stop used when the setting or daemon is disabled. */
    public static void stop() {
        exec("am stopservice -n " + SERVICE);
    }

    private static String q(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void exec(String command) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
        } catch (Throwable t) {
            logger.warn("Cabin audio service command failed: " + t.getMessage());
        }
    }
}
