package com.overdrive.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

import com.overdrive.app.daemon.CameraDaemon;

/**
 * Daemon-side watcher that recovers gracefully when the app currently cast onto the driver
 * cluster (see {@link com.overdrive.app.launcher.ClusterCast}) — or the persisted ACC-on
 * auto-start app — is UNINSTALLED.
 *
 * <h3>Why the daemon owns this</h3>
 * The cast state ({@code ClusterCast.castPkg}) and the head-unit mirror
 * ({@link com.overdrive.app.surveillance.ClusterMirrorController}) live in the daemon
 * process (uid 2000). If a foreign cast app is uninstalled while its cast is LIVE, the OS
 * kills its activity on the fission display but the {@code "castapp"} projection hold stays
 * held, so the gauges stay blanked and the mirror keeps compositing a now-dead display with
 * NO recovery until a manual stop / ACC-off. Nothing else catches this — {@code isActive()}'s
 * token self-reconcile can't (the token is still held). So we listen for the package removal
 * and tear the cast down.
 *
 * <h3>Load-bearing teardown ORDER</h3>
 * Mirror FIRST (synchronously unbind + destroy its virtual display that reads the fission
 * SOURCE layerStack), THEN release the cast hold (which closes the OEM projection + restores
 * the gauges) — the same ordering the ACC-off path uses (see
 * {@link com.overdrive.app.monitor.AccMonitor}). Tearing the projection down while the mirror
 * VD is still bound faults SurfaceFlinger and cascades to kill the daemon + app.
 *
 * <h3>Update vs. true removal</h3>
 * {@code ACTION_PACKAGE_REMOVED} also fires for an app UPDATE (reinstall) with
 * {@code EXTRA_REPLACING=true}. We only CLEAR the persisted auto-start package on a TRUE
 * removal (not an update, so a routine update doesn't silently disable the user's auto-start);
 * a live cast during a replace is disrupted regardless, so we tear it down either way (simplest
 * safe behaviour; the app relaunches itself post-update if it wants).
 *
 * <h3>Registration</h3>
 * MUST be registered at runtime on the daemon's app context — a manifest-declared receiver
 * for {@code PACKAGE_REMOVED} does not fire on API 26+ (implicit-broadcast restrictions).
 * {@link #register(Context)} / {@link #unregister(Context)} are called from CameraDaemon
 * startup / shutdown. No-op if the context is null (headless daemon).
 */
public final class CastPackageWatcher extends BroadcastReceiver {

    private static final String TAG = "CastPackageWatcher";
    private static volatile CastPackageWatcher instance;

    private CastPackageWatcher() {}

    /** Register on the daemon app context. Idempotent; no-op if ctx is null or already registered. */
    public static synchronized void register(Context ctx) {
        if (ctx == null) { CameraDaemon.log(TAG + ": no app context — not registering"); return; }
        if (instance != null) return;
        try {
            CastPackageWatcher r = new CastPackageWatcher();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
            filter.addDataScheme("package");
            ctx.getApplicationContext().registerReceiver(r, filter);
            instance = r;
            CameraDaemon.log(TAG + ": registered (PACKAGE_REMOVED / FULLY_REMOVED)");
        } catch (Throwable t) {
            CameraDaemon.log(TAG + ": register failed: " + t.getMessage());
        }
    }

    /** Unregister on daemon shutdown. Best-effort; never throws. */
    public static synchronized void unregister(Context ctx) {
        CastPackageWatcher r = instance;
        if (r == null || ctx == null) return;
        try {
            ctx.getApplicationContext().unregisterReceiver(r);
        } catch (Throwable ignored) {
        } finally {
            instance = null;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || intent.getAction() == null) return;
            final String pkg = packageOf(intent);
            if (pkg == null || pkg.isEmpty()) return;
            final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            // Do the teardown + config work on a short-lived worker, NOT the broadcast
            // dispatch thread: forceCloseIfActive is SYNCHRONOUS (awaits the mirror VD
            // unbind, bounded ~2.5s) and clearAutoStartIfMatches does a config read+write —
            // both would otherwise block the daemon's broadcast/Looper thread. Fire-and-forget
            // daemon thread; every step is independently guarded so it never crashes.
            Thread t = new Thread(() -> handleRemoval(pkg, replacing), "CastPkgWatcher");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CameraDaemon.log(TAG + ": onReceive failed: " + t.getMessage());
        }
    }

    private static void handleRemoval(String pkg, boolean replacing) {
        try {
            // 1) If the LIVE cast is this package, tear it down (order is load-bearing).
            String castPkg = com.overdrive.app.launcher.ClusterCast.getCastPackage();
            if (pkg.equals(castPkg)) {
                CameraDaemon.log(TAG + ": cast app " + pkg + " removed (replacing=" + replacing
                        + ") — tearing down cluster cast + mirror");
                // Mirror FIRST (synchronous unbind+destroy of its VD), THEN the cast hold —
                // same SurfaceFlinger-safe ordering as ACC-off.
                try {
                    com.overdrive.app.surveillance.ClusterViewMirrorService
                            .forceDetachIfActive("cast-app-uninstalled");
                    com.overdrive.app.surveillance.ClusterMirrorController
                            .forceCloseIfActive("cast-app-uninstalled");
                } catch (Throwable t) {
                    CameraDaemon.log(TAG + ": mirror teardown failed: " + t.getMessage());
                }
                try {
                    // ACC-off-safe stop: release the hold only, no am/shell reparent work.
                    com.overdrive.app.launcher.ClusterCast.stopForAccOff();
                } catch (Throwable t) {
                    CameraDaemon.log(TAG + ": cast stop failed: " + t.getMessage());
                }
            }

            // 2) On a TRUE removal (not an update), clear a stale persisted auto-start
            //    package so ACC-on doesn't re-fail every ignition on a ghost app.
            if (!replacing) {
                clearAutoStartIfMatches(pkg);
                // Also drop the app's remembered cluster box. Not doing so leaves an entry per
                // uninstalled app in the config forever, and a reinstall would silently reopen at
                // a scale the user set for the previous install.
                clearSavedClusterWindow(pkg);
            }
        } catch (Throwable t) {
            CameraDaemon.log(TAG + ": handleRemoval failed: " + t.getMessage());
        }
    }

    private static String packageOf(Intent intent) {
        Uri data = intent.getData();
        if (data == null) return null;
        return data.getSchemeSpecificPart();   // "package:com.foo" → "com.foo"
    }

    /** Clear projection.autoStartPackage (+ turn off autoStartOnAcc) when it named the
     *  now-removed app, so a later ACC-on doesn't try to cast a ghost. Off-UI-thread
     *  (broadcast thread), honouring the no-UI-thread unified-write rule. Best-effort. */
    private static void clearAutoStartIfMatches(String removedPkg) {
        try {
            org.json.JSONObject proj =
                    com.overdrive.app.config.UnifiedConfigManager.forceReload().optJSONObject("projection");
            if (proj == null) return;
            String saved = proj.optString("autoStartPackage", "");
            if (saved.isEmpty() || !saved.equals(removedPkg)) return;
            org.json.JSONObject patch = new org.json.JSONObject();
            patch.put("autoStartPackage", "");
            patch.put("autoStartOnAcc", false);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("projection", patch);
            CameraDaemon.log(TAG + ": cleared stale auto-start package " + removedPkg + " (uninstalled)");
        } catch (Throwable t) {
            CameraDaemon.log(TAG + ": clearAutoStart failed: " + t.getMessage());
        }
    }

    /** Drop {@code projection.windows.<pkg>} — the per-app cluster box a headless cast applies —
     *  when the app is uninstalled for good. Read-before-write so the common "nothing saved for
     *  this package" case costs no config rewrite. Best-effort. */
    private static void clearSavedClusterWindow(String removedPkg) {
        try {
            org.json.JSONObject proj =
                    com.overdrive.app.config.UnifiedConfigManager.loadConfig().optJSONObject("projection");
            org.json.JSONObject windows = proj != null ? proj.optJSONObject("windows") : null;
            if (windows == null || !windows.has(removedPkg)) return;
            org.json.JSONObject merged = new org.json.JSONObject();
            java.util.Iterator<String> it = windows.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (!k.equals(removedPkg)) merged.put(k, windows.get(k));
            }
            com.overdrive.app.config.UnifiedConfigManager.updateSection(
                    "projection", new org.json.JSONObject().put("windows", merged));
            CameraDaemon.log(TAG + ": cleared saved cluster box for " + removedPkg + " (uninstalled)");
        } catch (Throwable t) {
            CameraDaemon.log(TAG + ": clearSavedClusterWindow failed: " + t.getMessage());
        }
    }
}
