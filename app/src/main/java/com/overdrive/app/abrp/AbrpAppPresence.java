package com.overdrive.app.abrp;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Detects whether the ABRP app is currently running on this head unit, so
 * telemetry can be streamed only while the user is actually route-planning.
 *
 * "Active" has two modes (read live from {@link AbrpConfig}):
 *   - foreground : ABRP was the resumed/top activity within a grace window
 *                  (survives quick app-switches; pauses shortly after leaving ABRP).
 *   - running    : the ABRP process is alive at all (better for background navigation).
 *
 * The grace window only smooths the telemetry *gate* ({@link #isActive()}) so a quick
 * app-switch doesn't tear the stream down. The status string ({@link #describe()})
 * reports the *current* observed state instead, so closing ABRP flips it away from
 * "foreground" on the next poll rather than lingering for the whole grace window.
 *
 * No installed-check — if ABRP isn't on the device it simply won't appear as
 * foreground or running, so the gate naturally returns false without extra logic.
 *
 * Foreground detection uses {@code dumpsys activity activities} via a shell — the
 * same privileged idiom the rest of the app uses (AccSentryDaemon, ServiceLauncher).
 * Results are cached briefly so we never hammer dumpsys.
 */
public class AbrpAppPresence {

    private static final String TAG = "AbrpAppPresence";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final long CHECK_TTL_MS = 8000;

    private final AbrpConfig config;

    private volatile long lastCheckMs = 0;
    private volatile long lastForegroundSeenMs = 0;
    private volatile boolean lastForegroundNow = false;
    private volatile boolean lastProcessAlive = false;

    public AbrpAppPresence(AbrpConfig config) {
        this.config = config;
    }

    /**
     * Human-readable presence for the status panel: "foreground" / "background" / "not running".
     *
     * Reflects the most recent observation, NOT the grace window — so when the user leaves
     * ABRP the panel stops saying "foreground" within one check cycle ({@link #CHECK_TTL_MS}),
     * even though {@link #isActive()} may keep the stream alive through the grace period.
     */
    public String describe() {
        refreshIfStale();
        if (lastForegroundNow) return "foreground";
        if (lastProcessAlive) return "background";
        return "not running";
    }

    /** True if telemetry should be allowed to flow right now. */
    public boolean isActive() {
        refreshIfStale();
        boolean foregroundMode = !"running".equalsIgnoreCase(config.getAppActiveMode());
        return foregroundMode ? isForegroundWithinGrace() : lastProcessAlive;
    }

    private boolean isForegroundWithinGrace() {
        long graceMs = Math.max(0, config.getAppGraceSeconds()) * 1000L;
        return lastForegroundSeenMs > 0 && (System.currentTimeMillis() - lastForegroundSeenMs) <= graceMs;
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_TTL_MS) return;
        lastCheckMs = now;

        String pkg = config.getAppPackage();
        if (pkg == null || pkg.isEmpty()) pkg = "com.iternio.abrpapp";

        boolean alive = isProcessAlive(pkg);
        lastProcessAlive = alive;
        if (!alive) {
            lastForegroundNow = false;
            return;
        }

        String top = readForegroundPackage();
        boolean foregroundNow = top != null && top.contains(pkg);
        lastForegroundNow = foregroundNow;
        if (foregroundNow) {
            lastForegroundSeenMs = now;
        }
    }

    /** Parse the resumed/top activity package from dumpsys (bounded by -t 2). */
    private String readForegroundPackage() {
        String out = runShell("dumpsys -t 2 activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity|mCurrentFocus' | head -n 5");
        if (out == null || out.isEmpty()) {
            out = runShell("dumpsys -t 2 window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 5");
        }
        if (out == null) return null;
        for (String line : out.split("\n")) {
            int slash = line.indexOf('/');
            if (slash <= 0) continue;
            int start = slash;
            while (start > 0) {
                char ch = line.charAt(start - 1);
                if (ch == ' ' || ch == '{' || ch == '=') break;
                start--;
            }
            String token = line.substring(start, slash);
            if (token.contains(".")) return token;
        }
        return null;
    }

    private boolean isProcessAlive(String pkg) {
        String out = runShell("pidof " + pkg);
        return out != null && !out.trim().isEmpty();
    }

    private String runShell(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int lines = 0;
                while ((line = r.readLine()) != null && lines++ < 20) {
                    sb.append(line).append('\n');
                }
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            logger.debug("shell failed: " + e.getMessage());
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
