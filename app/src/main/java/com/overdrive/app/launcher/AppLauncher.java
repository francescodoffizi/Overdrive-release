package com.overdrive.app.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared helper for launching an installed Android app and enumerating the
 * launchable apps for a picker UI. Used by BOTH the automation "open app"
 * action and the key-mapping "openApp" action so there is a single launch
 * path and a single app-list source of truth.
 *
 * <p>Launch strategy: prefer {@link PackageManager#getLaunchIntentForPackage}
 * + {@code startActivity} from the daemon's shared app Context (works on this
 * SDK-25 target with no package-visibility {@code <queries>} needed), and fall
 * back to a shell {@code am start} — the same mechanism the daemon already uses
 * to (re)launch our own app and the sidecar services. Runs in the daemon
 * (UID 2000 = shell), so both paths are permitted.
 */
public final class AppLauncher {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AppLauncher");

    private AppLauncher() {}

    /**
     * Launch the given package (full-screen) by resolving its default launcher activity.
     *
     * @param pkg the target application package name
     * @return true if a launch was dispatched (Intent or shell), false otherwise
     */
    public static boolean launch(String pkg) {
        return launch(pkg, false);
    }

    /**
     * Launch the given package, optionally into split-screen (multi-window).
     *
     * <p>Full-screen ({@code split=false}) keeps the original behaviour: a clean
     * {@code getLaunchIntentForPackage} + {@code startActivity}, falling back to
     * {@code monkey}.
     *
     * <p>Split-screen ({@code split=true}) is handled by {@link #dockSplitScreen}:
     * a cold app is docked with the field-tested {@code am start --windowingMode 3
     * -n <component>}, while an already-running app is re-docked by seeding a
     * split-screen-primary stack with a transparent ghost activity and moving the
     * app's task into it (a plain windowing-mode-3 start only splits on first launch).
     * Windowing mode 3 is SPLIT_SCREEN_PRIMARY. It REQUIRES the explicit launcher
     * {@code <package>/<activity>} component, so we resolve it first via the
     * PackageManager, then via {@code cmd package resolve-activity}. If the component
     * can't be resolved (or the dock fails at any step) we fall back to a normal
     * full-screen launch rather than silently doing nothing.
     *
     * @param pkg   the target application package name
     * @param split true to dock into split-screen, false for a normal launch
     * @return true if a launch was dispatched, false otherwise
     */
    /**
     * Launch {@code pkg} onto a specific display ({@code displayId}: head-unit 0 /
     * cluster 1). Resolves the launcher component (trusted PackageManager/cmd resolver)
     * and dispatches {@code am start-activity --display}. Package charset is validated
     * and the component shell-quoted, exactly like the split-screen path. Returns false
     * on a bad package or if the component can't be resolved / the launch fails.
     */
    public static boolean launchOnDisplay(String pkg, int displayId) {
        if (pkg == null || pkg.trim().isEmpty() || !isValidPackageName(pkg.trim())) {
            logger.warn("launchOnDisplay: missing/invalid package");
            return false;
        }
        pkg = pkg.trim();
        String component = resolveLauncherComponent(pkg);
        if (component == null) {
            logger.warn("launchOnDisplay: could not resolve component for " + pkg);
            return false;
        }
        boolean ok = runShell("am start-activity --user 0 --display " + displayId
                + " -n " + shellQuote(component));
        logger.info("launchOnDisplay: " + pkg + " → display " + displayId + " (" + component + ") ok=" + ok);
        return ok;
    }

    public static boolean launch(String pkg, boolean split) {
        if (pkg == null || pkg.trim().isEmpty()) {
            logger.warn("openApp: missing package");
            return false;
        }
        pkg = pkg.trim();

        // Validate the package-name charset before it ever reaches getLaunchIntentForPackage
        // or the `monkey -p <pkg>` shell. This is the last line of defense for the keymap
        // path (which does not go through AppType validation) — a package name is
        // [A-Za-z0-9._], so anything else is rejected rather than passed to the shell.
        if (!isValidPackageName(pkg)) {
            logger.warn("openApp: rejected invalid package name");
            return false;
        }

        if (split) {
            String component = resolveLauncherComponent(pkg);
            if (component != null) {
                if (dockSplitScreen(pkg, component)) {
                    logger.info("openApp: docked " + pkg + " in split-screen (" + component + ")");
                    return true;
                }
                logger.warn("openApp: split-screen dock failed for " + component + " — falling back to full-screen");
            } else {
                logger.warn("openApp: could not resolve launcher component for " + pkg + " — falling back to full-screen");
            }
            // Fall through to the normal full-screen path below.
        }

        // 1) Intent path via the daemon's app Context (cleanest; no shell).
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    // NEW_TASK required because the daemon Context is not an Activity.
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // Force the head unit (display 0). A plain startActivity RESUMES the
                    // app's EXISTING task on its last-bound display — after a cluster cast
                    // (am start --display <fissionId>) that affinity sticks to the task, so
                    // reopening from the launcher / a keymap button would resume it on the
                    // now-invisible cluster display and nothing shows on infotainment.
                    // setLaunchDisplayId(0) pulls the task back to display 0. Targeting the
                    // DEFAULT display needs no permission; for a never-cast app already on 0
                    // it's a no-op resume. Best-effort — fall through to shell if it throws.
                    try {
                        android.os.Bundle opts = android.app.ActivityOptions.makeBasic()
                                .setLaunchDisplayId(0).toBundle();
                        ctx.startActivity(launch, opts);
                        logger.info("openApp: launched " + pkg + " via Intent (display 0)");
                        return true;
                    } catch (Throwable optErr) {
                        logger.warn("openApp: setLaunchDisplayId(0) start failed (" + optErr.getMessage()
                                + ") — retrying plain start");
                        ctx.startActivity(launch);
                        logger.info("openApp: launched " + pkg + " via Intent (no display opt)");
                        return true;
                    }
                }
                logger.info("openApp: no launch intent for " + pkg + " — trying shell");
            } catch (Throwable t) {
                logger.warn("openApp: Intent launch failed for " + pkg + " (" + t.getMessage() + ") — trying shell");
            }
        }

        // 2) Shell fallback. Prefer the explicit display-0 component launch — the SAME
        //    `am start-activity --user 0 --display 0 -n <component>` the move-to-head-unit
        //    path already uses — so a task carrying a stale cluster affinity (from a prior
        //    cast) is pulled back to the head unit on reopen. Bare `monkey` is the last
        //    resort only when the component can't be resolved; such apps were never castable
        //    (ClusterCast.start bails on a null component) so they can't hold a stale
        //    cluster affinity anyway.
        if (launchOnDisplay(pkg, 0)) {
            logger.info("openApp: launched " + pkg + " via am start-activity (display 0)");
            return true;
        }
        if (runShell("monkey -p " + shellQuote(pkg) + " -c android.intent.category.LAUNCHER 1")) {
            logger.info("openApp: launched " + pkg + " via monkey");
            return true;
        }
        logger.warn("openApp: all launch paths failed for " + pkg);
        return false;
    }

    /** Ghost activity component used to seed a split-screen-primary stack for the
     *  re-dock path (must match the AndroidManifest entry). */
    private static final String GHOST_COMPONENT =
            "com.overdrive.app/com.overdrive.app.launcher.AppLauncherGhostActivity";
    /** 
     * Bounded polls waiting for the ghost stack / re-docked task to materialise. 
     * Adjusted to 8 attempts of 400ms to reduce system load from dumpsys and allow
     * the automotive hardware more time to process the split-screen creation. 
     */
    private static final int SPLIT_POLL_ATTEMPTS = 8;
    private static final long SPLIT_POLL_MS = 400L;

    /**
     * Dock {@code pkg} into split-screen (SPLIT_SCREEN_PRIMARY / windowingMode 3).
     *
     * <p>Two cases, because {@code am start --windowingMode 3} only docks an app on
     * its INITIAL launch — if the app is already running/backgrounded/full-screen,
     * that same command just re-focuses it full-screen and the screen never splits
     * (the bug this method fixes):
     * <ol>
     *   <li><b>Not running:</b> the plain {@code am start --windowingMode 3 -n
     *   <component>} docks it directly — the original field-tested path. We keep it
     *   verbatim (shell-quoted component) and treat its exit-0 as success.
     *   <li><b>Already running:</b> we launch the transparent {@link
     *   AppLauncherGhostActivity} into windowingMode 3 to FORCE a split-screen-primary
     *   stack into existence, find that stack's id, then {@code am stack move-task}
     *   the app's existing task into it so it fills the other half.
     * </ol>
     *
     * <p>The stack id is read from {@code dumpsys activity activities} (which emits
     * {@code mWindowingMode=<mode>} on this firmware — verified on-car by
     * ClusterMapProjector), NOT from {@code am stack list} (whose API-29 StackInfo
     * output carries no windowing mode, which is why the previous attempt always
     * parsed an empty id). We locate the stack by OUR ghost component rather than by
     * matching a mode string, so we never depend on the exact mode spelling.
     *
     * <p>Best-effort: ANY failure (no task found, ghost stack didn't appear,
     * move-task non-zero) returns false so the caller falls through to a normal
     * full-screen launch — never worse than before this change. Runs on the caller's
     * daemon/HTTP worker thread (never the UI thread); all shells are bounded by
     * {@code runShell}'s 5s cap.
     */
    private static boolean dockSplitScreen(String pkg, String component) {
        // Case 1: app not already running → the original single-shot dock, which is
        // the ONLY thing that works for a cold app. Component is trusted + shell-quoted.
        int existingTask = findTaskId(pkg);
        if (existingTask < 0) {
            return runShell("am start --user 0 --windowingMode 3 -n " + shellQuote(component));
        }

        // Case 2: app already running → seed a split-primary stack with the ghost,
        // then move the app's existing task onto it.
        logger.info("openApp: " + pkg + " already running (task " + existingTask
                + ") — re-docking via ghost split stack");
        if (!runShell("am start --user 0 --windowingMode 3 -n " + GHOST_COMPONENT)) {
            logger.warn("openApp: ghost split-stack launch failed");
            return false;
        }

        // The ghost's stack is created asynchronously; poll for it rather than a fixed
        // sleep (mirrors ClusterMapProjector's launch→verify pattern).
        int ghostStack = -1;
        for (int i = 0; i < SPLIT_POLL_ATTEMPTS && ghostStack < 0; i++) {
            sleepQuietly(SPLIT_POLL_MS);
            ghostStack = findGhostStackId();
        }
        if (ghostStack < 0) {
            logger.warn("openApp: ghost split stack did not appear — re-dock aborted");
            return false;
        }

        // Wait for the Ghost Activity window to stabilize on screen before moving the task.
        // This prevents a race condition where the ghost activity finishes rendering *after*
        // the task was moved, covering the target application.
        sleepQuietly(150L);

        // move-task <taskId> <stackId> true  → the trailing bool is toTop. Re-read the
        // task id: it is stable for a running app, but a re-resolve costs nothing and
        // guards against the app dying between the two dumpsys reads.
        int task = findTaskId(pkg);
        if (task < 0) task = existingTask;
        boolean moved = runShell("am stack move-task " + task + " " + ghostStack + " true");
        if (!moved) {
            logger.warn("openApp: move-task " + task + " → stack " + ghostStack + " failed");
            return false;
        }

        // Force the app's activity to the front. On DiLink 3 (Android 10), AMS moves the 
        // background task to the split stack silently without triggering a focus transition.
        // A direct start command wakes the activity and forces the system to render it on top.
        //runShell("am start --user 0 -n " + shellQuote(component));

        logger.info("openApp: re-docked task " + task + " into split stack " + ghostStack);
        return true;
    }

    /**
     * Task id of the (top) task hosting {@code pkg}, or -1 if the package has no task
     * (i.e. not currently running). Parsed from {@code dumpsys activity activities},
     * whose task headers look like {@code * Task{... #123 ... A=<affinity> U=0 ...}}
     * followed by the task's activities. We match the task whose recorded package is
     * {@code pkg} by scanning each task block's first component reference.
     */
    private static int findTaskId(String pkg) {
        // Grep to the task-header + component lines so the parse stays cheap and the
        // output small. On API 29 the task-header class is `TaskRecord{...#<id>...}`
        // (newer platforms print `Task{...}`); match BOTH so this works across the
        // firmware range. A following "<pkg>/" component line ties a task to its
        // package. (`.` in pkg is a regex any-char here but charset-limited so the
        // over-match is benign; attribution below re-checks with a literal "<pkg>/".)
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'TaskRecord\\{|Task\\{|" + pkg + "/'");
        if (out == null) return -1;
        int pendingTaskId = -1;
        for (String line : out.split("\\r?\\n")) {
            line = line.trim();
            int hashTask = extractTaskId(line);
            if (hashTask >= 0) {
                pendingTaskId = hashTask;          // remember the task we're inside
                continue;
            }
            // A component line "<pkg>/<activity>" inside the current task block.
            // Literal "<pkg>/" (not a bare contains(pkg)) so a sibling package like
            // "com.foobar" can't be mistaken for "com.foo".
            if (pendingTaskId >= 0 && line.contains(pkg + "/")) {
                return pendingTaskId;
            }
        }
        return -1;
    }

    /**
     * Stack id of the split-screen-primary stack we just seeded — the stack that
     * currently hosts OUR ghost activity. Located by ghost component, not by a
     * windowing-mode string, so it is robust to mode-name spelling. -1 if not found.
     */
    private static int findGhostStackId() {
        // Ghost activity's simple name is enough to pin the block; scan upward from it
        // to the enclosing "Stack #<id>:" / "ActivityStack{... #<id>" header.
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'Stack #|ActivityStack|AppLauncherGhostActivity'");
        if (out == null) return -1;
        int currentStack = -1;
        for (String line : out.split("\\r?\\n")) {
            line = line.trim();
            int stackId = extractStackId(line);
            if (stackId >= 0) {
                currentStack = stackId;
                continue;
            }
            if (line.contains("AppLauncherGhostActivity") && currentStack >= 0) {
                return currentStack;
            }
        }
        return -1;
    }

    /**
     * Reparent {@code pkg}'s task onto a fullscreen stack of display 0 (the head unit)
     * WITHOUT bringing it to the foreground, so a task carrying a stale cluster-display
     * affinity (from a prior {@link ClusterCast}) resumes on the infotainment the next time
     * it is opened — instead of on the now-invisible fission cluster display.
     *
     * <p><b>Why this exists.</b> On Android 10, {@code setLaunchDisplayId(0)} /
     * {@code am start --display 0} reliably place a NEW task on display 0 but do NOT
     * reparent an ALREADY-RUNNING task off a still-registered secondary display — a matched
     * standard task is merely brought forward on its CURRENT display. The OEM fission
     * VirtualDisplay outlives its projection close (invisible, not removed), so AMS never
     * auto-reparents the cast task, and every reopen (launcher tap, keymap, notification)
     * resumes it on a dead display. {@code am stack move-task <task> <display-0 stack> false}
     * is the operation that actually relocates a live task; {@code toTop=false} keeps it in
     * the background so nothing pops onto the head unit while driving.
     *
     * <p>Must be called WHILE the fission display is still live (a supported live-to-live
     * reparent) — {@link ClusterCast#stop(boolean)} invokes it before releasing the
     * projection hold. Best-effort: any parse/stack-resolution failure is a logged no-op
     * (never a foregrounding fallback that could surface the app while driving). Runs on the
     * caller's daemon/HTTP worker thread; all shells are bounded by {@link #runShell}'s 5s cap.
     *
     * @return true if a move-task was dispatched (exit 0), false otherwise
     */
    static boolean reparentToDisplay0(String pkg) {
        if (pkg == null || pkg.trim().isEmpty() || !isValidPackageName(pkg.trim())) {
            logger.warn("reparentToDisplay0: missing/invalid package");
            return false;
        }
        pkg = pkg.trim();
        int task = findTaskId(pkg);
        if (task < 0) {
            logger.info("reparentToDisplay0: no live task for " + pkg + " — nothing to reparent");
            return false;
        }
        int stack = findFullscreenStackIdOnDisplay(0);
        if (stack < 0) {
            logger.warn("reparentToDisplay0: no display-0 fullscreen stack found — skipping (no clobber)");
            return false;
        }
        // toTop=false: reparent in the BACKGROUND so we never pop the foreign app onto the
        // head unit while driving; it simply resumes there on the user's next open.
        boolean moved = runShell("am stack move-task " + task + " " + stack + " false");
        logger.info("reparentToDisplay0: move-task " + task + " → display-0 stack " + stack
                + " (" + pkg + ") ok=" + moved);
        return moved;
    }

    /**
     * Stack id of a stack on {@code displayId}, parsed from {@code dumpsys activity
     * activities}. Uses the {@code Display #<id>} block boundary — the SAME structure
     * {@link ClusterCast#isResumedOnDisplay} parses and which is VALIDATED on this firmware
     * (per-stack {@code mDisplayId=}/{@code mWindowingMode=} field names are NOT relied on,
     * as their presence/ordering is firmware-specific and unverified here). We enter the
     * target display's block on its {@code Display #<id>} header, leave it on the next
     * {@code Display #} header, and return the FIRST {@code Stack #<id>} inside it (on a car
     * head unit, display 0's top stack is the standard/fullscreen stack — the right reparent
     * target). Returns -1 if the block or a stack within it isn't found → caller no-ops
     * safely (never a foregrounding fallback).
     */
    private static int findFullscreenStackIdOnDisplay(int displayId) {
        String out = runShellCapture(
                "dumpsys activity activities | grep -E 'Display #|Stack #'");
        if (out == null) return -1;
        String displayHeader = "display #" + displayId;
        boolean inTargetDisplay = false;
        for (String line : out.split("\\r?\\n")) {
            line = line.trim();
            String low = line.toLowerCase(java.util.Locale.US);
            int hdr = low.indexOf("display #");
            if (hdr >= 0) {
                // Enter the target display's block; leave it at any other Display # header.
                // The trailing non-digit guard stops "Display #1" matching "Display #10".
                inTargetDisplay = low.startsWith(displayHeader, hdr)
                        && !Character.isDigit(charAt(low, hdr + displayHeader.length()));
                continue;
            }
            if (inTargetDisplay) {
                int stackId = extractStackId(line);
                if (stackId >= 0) return stackId;   // first stack inside Display #<id>
            }
        }
        return -1;
    }

    /** Char at index {@code i}, or a space if out of range (safe bounds for the
     *  Display-header digit-boundary guard in {@link #findFullscreenStackIdOnDisplay}). */
    private static char charAt(String s, int i) {
        return (i >= 0 && i < s.length()) ? s.charAt(i) : ' ';
    }

    /** Parse the "#<n>" id from a {@code Task{...#123...}} header line, else -1. */
    private static int extractTaskId(String line) {
        if (!line.contains("Task{") && !line.contains("TaskRecord{")) return -1;
        return extractHashNumber(line);
    }

    /** Parse the id from a {@code Stack #123} / {@code ActivityStack{...#123}} header, else -1. */
    private static int extractStackId(String line) {
        if (!line.contains("Stack #") && !line.contains("ActivityStack")) return -1;
        return extractHashNumber(line);
    }

    /** First {@code #<digits>} run in {@code line} as an int, or -1 if none. */
    private static int extractHashNumber(String line) {
        int hash = line.indexOf('#');
        while (hash >= 0 && hash + 1 < line.length()) {
            int j = hash + 1;
            while (j < line.length() && Character.isDigit(line.charAt(j))) j++;
            if (j > hash + 1) {
                try {
                    return Integer.parseInt(line.substring(hash + 1, j));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            hash = line.indexOf('#', hash + 1);
        }
        return -1;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * List launchable installed apps as a JSON array of {package, label},
     * sorted by label. Used to populate the picker in the automation and
     * key-mapping UIs.
     */
    public static JSONArray listLaunchableApps() {
        List<JSONObject> apps = new ArrayList<>();
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
                launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> resolved = pm.queryIntentActivities(launcherIntent, 0);
                for (ResolveInfo ri : resolved) {
                    if (ri == null || ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null) continue;
                    String label = null;
                    try {
                        CharSequence l = ri.loadLabel(pm);
                        if (l != null) label = l.toString();
                    } catch (Throwable ignored) { }
                    if (label == null || label.isEmpty()) label = pkg;
                    apps.add(makeApp(pkg, label));
                }
            } catch (Throwable t) {
                logger.warn("listLaunchableApps: PackageManager query failed (" + t.getMessage() + ")");
            }
        }
        // Shell fallback if the PackageManager path produced nothing (e.g. broken
        // daemon Context) — package names only, no friendly labels.
        if (apps.isEmpty()) {
            for (String pkg : listPackagesViaShell()) {
                apps.add(makeApp(pkg, pkg));
            }
        }

        // De-dup by package FIRST (a package can expose several launcher
        // activities, sometimes with different labels — those would not be
        // adjacent after a label sort, so an adjacent-only pass would miss them),
        // then sort by label. We launch by package, so one entry per package.
        java.util.LinkedHashMap<String, JSONObject> byPkg = new java.util.LinkedHashMap<>();
        for (JSONObject app : apps) {
            String pkg = app.optString("package", "");
            if (pkg.isEmpty() || byPkg.containsKey(pkg)) continue;
            byPkg.put(pkg, app);
        }
        List<JSONObject> unique = new ArrayList<>(byPkg.values());
        Collections.sort(unique, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return a.optString("label", "").compareToIgnoreCase(b.optString("label", ""));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject app : unique) out.put(app);
        return out;
    }

    private static JSONObject makeApp(String pkg, String label) {
        JSONObject o = new JSONObject();
        try {
            o.put("package", pkg);
            o.put("label", label);
        } catch (JSONException ignored) { }
        return o;
    }

    /**
     * Shell fallback for enumerating packages when PackageManager is unavailable.
     * {@code pm list packages} lists all installed packages (we can't cheaply
     * filter to launchable-only over shell, so the picker labels are package
     * names in this degraded path).
     */
    private static List<String> listPackagesViaShell() {
        List<String> pkgs = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("sh", "-c", "pm list packages -3")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
            p.waitFor(5, TimeUnit.SECONDS);
            try { is.close(); } catch (Throwable ignored) { }
            for (String line : sb.toString().split("\\r?\\n")) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    String pkg = line.substring("package:".length()).trim();
                    if (!pkg.isEmpty()) pkgs.add(pkg);
                }
            }
        } catch (Throwable t) {
            logger.warn("listPackagesViaShell failed: " + t.getMessage());
        }
        return pkgs;
    }

    /** Run a short shell command bounded at 5s; true if it exited 0. */
    private static boolean runShell(String cmd) {
        try {
            final Process p = new ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true).start();
            final InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] b = new byte[4096];
                try { while (is.read(b) != -1) { /* discard */ } } catch (Throwable ignored) { }
            }, "applauncher-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); }
            drain.join(500);
            try { is.close(); } catch (Throwable ignored) { }
            return done && p.exitValue() == 0;
        } catch (Throwable t) {
            logger.warn("runShell failed: " + t.getMessage());
            return false;
        }
    }
    
    /**
     * Run a short shell command bounded at 5s and return its (stdout+stderr) output,
     * or null on failure/timeout. Output is capped at {@link #SHELL_CAPTURE_CAP_BYTES}
     * so a large {@code dumpsys} dump can't balloon memory — callers grep upstream to
     * keep the stream small, and this is a hard backstop. Used by the split-screen
     * re-dock parse; mirrors the capture pattern already in {@link #resolveLauncherComponent}.
     */
    private static String runShellCapture(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                if (sb.length() < SHELL_CAPTURE_CAP_BYTES) {
                    sb.append(new String(buf, 0, n));
                }
                // keep draining past the cap so the child never blocks on a full pipe
            }
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            try { is.close(); } catch (Throwable ignored) { }
            if (!done) { p.destroyForcibly(); return null; }
            return sb.toString();
        } catch (Throwable t) {
            logger.warn("runShellCapture failed: " + t.getMessage());
            return null;
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) { } }
        }
    }

    /** Hard cap on captured shell output (see {@link #runShellCapture}). */
    private static final int SHELL_CAPTURE_CAP_BYTES = 256 * 1024;

    /**
     * Resolve the {@code package/activity} launcher component for a package.
     * Split-screen's {@code am start -n} needs the explicit component. Tries the
     * PackageManager (daemon Context) first, then a shell
     * {@code cmd package resolve-activity} fallback. Returns null if neither
     * yields a component.
     *
     * <p>Package-private so the same-package {@link ClusterCast} can reuse this
     * trusted resolver for the projection-aware driver-cluster launch.
     */
    static String resolveLauncherComponent(String pkg) {
        Context ctx = CameraDaemon.getAppContext();
        if (ctx != null) {
            try {
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null && launch.getComponent() != null) {
                    return launch.getComponent().flattenToShortString();
                }
            } catch (Throwable t) {
                logger.warn("resolveLauncherComponent: PackageManager failed for " + pkg + " (" + t.getMessage() + ")");
            }
        }
        // Shell fallback: parse `cmd package resolve-activity --brief` for the component.
        try {
            Process p = new ProcessBuilder("sh", "-c",
                    "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + shellQuote(pkg))
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n));
            p.waitFor(5, TimeUnit.SECONDS);
            try { is.close(); } catch (Throwable ignored) { }
            for (String line : sb.toString().split("\\r?\\n")) {
                line = line.trim();
                // A resolved component line looks like "pkg/.Activity" or "pkg/pkg.Activity".
                if (line.startsWith(pkg + "/")) return line;
            }
        } catch (Throwable t) {
            logger.warn("resolveLauncherComponent: shell resolve failed for " + pkg + " (" + t.getMessage() + ")");
        }
        return null;
    }

    /**
     * True if {@code pkg} is installed and has a resolvable launcher activity (i.e. it can
     * actually be cast/launched). Used by callers to distinguish an "app no longer installed"
     * failure from a generic one. Charset-validated first so a bad name never hits the resolver.
     */
    public static boolean isLaunchable(String pkg) {
        if (pkg == null || pkg.trim().isEmpty() || !isValidPackageName(pkg.trim())) return false;
        return resolveLauncherComponent(pkg.trim()) != null;
    }

    /** Minimal shell quoting for a package name (defensive; pkg names are [A-Za-z0-9._]). */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** True if the string is a plausible Android package name ([A-Za-z0-9._], 1..255).
     *  Package-private so {@link ClusterCast} validates before its projection open. */
    static boolean isValidPackageName(String s) {
        if (s == null || s.isEmpty() || s.length() > 255) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
