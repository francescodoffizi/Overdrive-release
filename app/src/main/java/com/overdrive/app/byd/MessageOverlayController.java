package com.overdrive.app.byd;

import com.overdrive.app.logging.DaemonLogger;

import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;

/**
 * Daemon-side bridge that shows an on-screen toast/dialog for the "Show Toast" /
 * "Show Dialog" automation + key-mapping actions.
 *
 * <p>The daemon (UID 2000, {@code app_process}) has no UI surface, so — exactly like the
 * Play Audio / Speak path in {@link AudioPlaybackController} — this shells
 * {@code am start-foreground-service} against the exported app-process
 * {@code MessageOverlayService}, which draws a {@code TYPE_APPLICATION_OVERLAY} window in
 * the real app process. Fire-and-forget (no waitFor) so a slow {@code am} never stalls the
 * HTTP-worker / keymap-fire thread.
 */
public final class MessageOverlayController {

    private static final String TAG = "MessageOverlay";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String PKG = "com.overdrive.app";
    private static final String SERVICE = "com.overdrive.app/.overlay.MessageOverlayService";
    private static final String ACTION_DISMISS = "com.overdrive.app.action.DISMISS_MESSAGE";
    private static final int ACK_TIMEOUT_MS = 3000;

    private MessageOverlayController() {}

    public static final class DisplayResult {
        public final boolean displayed;
        public final String reason;

        private DisplayResult(boolean displayed, String reason) {
            this.displayed = displayed;
            this.reason = reason == null ? "" : reason;
        }

        public static DisplayResult displayed() {
            return new DisplayResult(true, "");
        }

        public static DisplayResult failed(String reason) {
            return new DisplayResult(false, reason);
        }
    }

    /**
     * Show a toast (auto-dismissing pill). {@code duration} = short|long,
     * {@code position} = top|center|bottom, {@code severity} = info|warning|alert.
     */
    public static boolean showToast(String message, String duration, String position, String severity) {
        if (message == null || message.trim().isEmpty()) {
            logger.warn("showToast: empty message");
            return false;
        }
        exec(toastCommand(message, duration, position, severity, ""));
        logger.info("showToast: dispatched to MessageOverlayService");
        return true;
    }

    /** Display the toast and wait briefly for WindowManager.addView(). */
    public static DisplayResult showToastAcknowledged(
            String message, String duration, String position, String severity) {
        if (message == null || message.trim().isEmpty()) {
            return DisplayResult.failed("Message is required");
        }
        return dispatchAcknowledged(ackArgs ->
                toastCommand(message, duration, position, severity, ackArgs));
    }

    /**
     * Show a dialog (title + body + OK button) as a non-focus-stealing overlay.
     * {@code button} defaults to OK; {@code timeoutSec} > 0 auto-dismisses even without OK.
     */
    public static boolean showDialog(String title, String message, String button,
                                     String severity, int timeoutSec) {
        if ((title == null || title.trim().isEmpty())
                && (message == null || message.trim().isEmpty())) {
            logger.warn("showDialog: empty title+message");
            return false;
        }
        exec(dialogCommand(title, message, button, severity, timeoutSec, ""));
        logger.info("showDialog: dispatched to MessageOverlayService");
        return true;
    }

    /** Display the dialog and wait briefly for WindowManager.addView(). */
    public static DisplayResult showDialogAcknowledged(
            String title, String message, String button,
            String severity, int timeoutSec) {
        if ((title == null || title.trim().isEmpty())
                && (message == null || message.trim().isEmpty())) {
            return DisplayResult.failed("Message is required");
        }
        return dispatchAcknowledged(ackArgs ->
                dialogCommand(title, message, button, severity, timeoutSec, ackArgs));
    }

    /** Dismiss any showing toast/dialog. Idempotent (no-op if nothing is up). */
    public static void dismiss() {
        exec("am broadcast -a " + ACTION_DISMISS + " -p " + PKG);
        exec("am stopservice -n " + SERVICE);
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    private interface CommandFactory {
        String create(String acknowledgementArgs);
    }

    private static DisplayResult dispatchAcknowledged(CommandFactory factory) {
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 1);
            server.setSoTimeout(ACK_TIMEOUT_MS);
            String token = UUID.randomUUID().toString();
            String ackArgs = " --es ackPort " + q(String.valueOf(server.getLocalPort()))
                    + " --es ackToken " + q(token);
            exec(factory.create(ackArgs));

            try (Socket client = server.accept()) {
                client.setSoTimeout(1000);
                DataInputStream input = new DataInputStream(client.getInputStream());
                String receivedToken = input.readUTF();
                String status = input.readUTF();
                String reason = input.readUTF();
                if (!token.equals(receivedToken)) {
                    return DisplayResult.failed(
                            "Message acknowledgement was rejected");
                }
                return "DISPLAYED".equals(status)
                        ? DisplayResult.displayed()
                        : DisplayResult.failed(
                                reason == null || reason.trim().isEmpty()
                                        ? "The car could not display the message"
                                        : reason);
            } catch (SocketTimeoutException timeout) {
                return DisplayResult.failed(
                        "The car did not acknowledge the message");
            }
        } catch (Throwable error) {
            logger.warn("Message acknowledgement failed: " + error.getMessage());
            return DisplayResult.failed("The car message service is unavailable");
        }
    }

    private static String toastCommand(
            String message, String duration, String position, String severity,
            String ackArgs) {
        return "am start-foreground-service -n " + SERVICE
                + " --es kind toast"
                + " --es message " + q(message)
                + " --es duration " + q(orDefault(duration, "short"))
                + " --es position " + q(orDefault(position, "bottom"))
                + " --es severity " + q(orDefault(severity, "info"))
                + ackArgs;
    }

    private static String dialogCommand(
            String title, String message, String button,
            String severity, int timeoutSec, String ackArgs) {
        return "am start-foreground-service -n " + SERVICE
                + " --es kind dialog"
                + " --es title " + q(orDefault(title, ""))
                + " --es message " + q(orDefault(message, ""))
                + " --es button " + q(orDefault(button, "OK"))
                + " --es severity " + q(orDefault(severity, "info"))
                + " --ei timeoutSec " + Math.max(0, timeoutSec)
                + ackArgs;
    }

    /** Shell-quote one `am` extra value the POSIX way. */
    private static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void exec(String cmd) {
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        } catch (Throwable t) {
            logger.warn("exec failed [" + cmd + "]: " + t.getMessage());
        }
    }
}
