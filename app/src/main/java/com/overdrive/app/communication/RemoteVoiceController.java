package com.overdrive.app.communication;

import com.overdrive.app.logging.DaemonLogger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shell-daemon bridge into the real app-process RemoteVoiceService.
 */
public final class RemoteVoiceController {

    private static final String TAG = "RemoteVoice";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String SERVICE =
            "com.overdrive.app/.services.RemoteVoiceService";
    private static final long OVERLAY_CACHE_MS = 5_000L;

    private static volatile long overlayCheckedAt;
    private static volatile Boolean overlayAllowed;

    private RemoteVoiceController() {}

    public static void start(int port, String token, int outputLevel) {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action start"
                + " --es port " + q(String.valueOf(port))
                + " --es token " + q(token)
                + " --es outputLevel " + q(String.valueOf(
                        RemoteCommunicationPolicy.clampOutputLevel(outputLevel))));
    }

    public static void stop() {
        exec("am stopservice -n " + SERVICE);
    }

    public static void testSpeaker() {
        exec("am start-foreground-service -n " + SERVICE
                + " --es action test");
    }

    /**
     * Best-effort app-op probe. Null means the firmware did not expose a
     * parseable result; the actual service handshake remains authoritative.
     */
    public static Boolean hasOverlayPermission() {
        long now = System.currentTimeMillis();
        if (now - overlayCheckedAt < OVERLAY_CACHE_MS) return overlayAllowed;
        synchronized (RemoteVoiceController.class) {
            now = System.currentTimeMillis();
            if (now - overlayCheckedAt < OVERLAY_CACHE_MS) return overlayAllowed;
            overlayAllowed = readOverlayAppOp();
            overlayCheckedAt = now;
            return overlayAllowed;
        }
    }

    public static void invalidateOverlayPermissionCache() {
        overlayCheckedAt = 0L;
        overlayAllowed = null;
    }

    private static Boolean readOverlayAppOp() {
        Process process = null;
        InputStream in = null;
        try {
            process = new ProcessBuilder(
                    "sh", "-c",
                    "appops get com.overdrive.app SYSTEM_ALERT_WINDOW")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(900, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            in = process.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = in.read(buffer)) != -1 && bytes.size() < 4096) {
                bytes.write(buffer, 0, read);
            }
            String value = bytes.toString("UTF-8").toLowerCase(Locale.US);
            if (value.contains("allow") || value.contains("foreground")) return true;
            if (value.contains("deny") || value.contains("ignore")
                    || value.contains("default")) return false;
        } catch (Throwable t) {
            logger.debug("Overlay permission probe unavailable: " + t.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (process != null) process.destroy();
        }
        return null;
    }

    private static String q(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void exec(String command) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
        } catch (Throwable t) {
            logger.warn("Remote voice service command failed: " + t.getMessage());
        }
    }
}
