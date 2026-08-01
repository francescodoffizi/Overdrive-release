package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.overdrive.app.R;

import java.lang.reflect.Method;

/**
 * App-process actuator for exterior-mirror fold (and HUD), which do NOT actuate from the
 * headless daemon even though the daemon's reflection call is byte-identical to the OEM.
 *
 * <p><b>Why this exists — and what is actually known.</b> The daemon
 * ({@code app_process}, UID 2000, synthetic {@code PermissionBypassContext}) makes the OEM's
 * exact {@code bodyworkDevice.getMethod("setMirrorFoldState", int).invoke(fold?1:0)} call and
 * the mirrors do not move. One plausible remaining variable is the calling process/Context
 * identity, so — like {@link MediaPlaybackService} (which exists because a daemon MediaPlayer
 * genuinely cannot prepare) — the daemon shells {@code am start-foreground-service} to reach
 * this exported service and repeats the write with a real app Context.
 *
 * <p><b>Honest status: this is a hypothesis, not a demonstrated fix.</b> The claim that the
 * OEM app "does this and it works" does not hold up: in the OEM vehicle-control app,
 * {@code setMirrorsFolded} has no callers anywhere (no UI, automation, or key-mapping path
 * reaches it), and its DiLink-5 controller implements it as a hard {@code return false}. So
 * the reference behaviour is untested dead code. A likelier gate is the BYD platform signing
 * key — {@code BYDAUTO_BODYWORK_SET} is signature-protected and {@code pm grant} cannot grant
 * it, which would refuse the write in BOTH processes equally. Treat this service as a
 * belt-and-braces second attempt; the daemon-side logs plus this service's log are what will
 * settle it on a real device.
 *
 * <p>Intent extras: {@code action=mirror} + {@code fold}=true|false; {@code action=hud} +
 * {@code level}=0..100 (brightness); {@code action=hud_power} + {@code on}=true|false (the
 * dedicated HUD switch). Fire-and-forget: do the write, log the outcome, self-stop.
 */
public final class VehicleActuatorService extends Service {

    private static final String TAG = "VehicleActuator";
    private static final String CHANNEL_ID = "overdrive_vehicle_actuator";
    private static final int NOTIFICATION_ID = 9973;

    private static final String SETTING_DEVICE = "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    private static final String BODYWORK_DEVICE = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice";
    private static final String INSTRUMENT_DEVICE = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getStringExtra("action");
        try {
            if ("mirror".equals(action)) {
                boolean fold = intent.getBooleanExtra("fold", false);
                Log.i(TAG, "mirror fold=" + fold + " -> ok=" + setMirrorsFolded(fold));
            } else if ("hud".equals(action)) {
                int level = intent.getIntExtra("level", -1);
                Log.i(TAG, "hud level=" + level + " -> ok=" + setHud(level));
            } else if ("hud_power".equals(action)) {
                boolean on = intent.getBooleanExtra("on", false);
                Log.i(TAG, "hud_power on=" + on + " -> ok=" + setHudPower(on));
            } else {
                Log.w(TAG, "unknown action: " + action);
            }
        } catch (Throwable t) {
            Log.w(TAG, "actuation failed (" + action + "): " + t.getMessage());
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    /**
     * Fold ({@code true}) / unfold ({@code false}) the exterior mirrors via
     * {@code BYDAutoBodyworkDevice.setMirrorFoldState(int)} (1=fold / 0=unfold) — the same
     * reflection call the OEM reference makes, run here in the REAL app process, acquiring the
     * device with {@code getApplicationContext()}.
     *
     * <p>The return value IS inspected (the OEM does not bother, which is why its own code
     * could never notice a refusal): {@code BODYWORK_COMMAND_SUCCESS} is 0 and the documented
     * failures are large negatives returned WITHOUT throwing, so accept-on-no-throw made this
     * method's {@code ok=true} log meaningless as evidence of actuation. A void/null return is
     * the one case with no signal available and is treated as accepted. On an explicit
     * refusal we fall through to the only mirror feature-id the OEM SDK defines.
     */
    private boolean setMirrorsFolded(boolean fold) {
        Object device = com.overdrive.app.byd.BydDeviceHelper.getDevice(BODYWORK_DEVICE, getApplicationContext());
        if (device == null) { Log.w(TAG, "bodywork device unavailable"); return false; }
        int val = fold ? 1 : 0;
        try {
            Method m = device.getClass().getMethod("setMirrorFoldState", int.class);
            Object r = m.invoke(device, val);
            // CHECK the result. This used to `return true` on any non-throwing invoke, so a
            // HAL that refused the write (BODYWORK_COMMAND_FAILED = -2147482648, returned
            // WITHOUT throwing) still logged "ok=true" — making this log useless as evidence
            // of actuation. The OEM's own predicate is equality with
            // BODYWORK_COMMAND_SUCCESS == 0; a void/null return means "returned without
            // throwing", which is the best signal available on trims that declare it void.
            boolean ok = !(r instanceof Integer) || ((Integer) r) == 0;
            if (r instanceof Boolean) ok = (Boolean) r;
            Log.i(TAG, "setMirrorFoldState(" + val + ") returned " + r + " -> "
                    + (ok ? "ACCEPTED" : "REFUSED"));
            if (ok) return true;
        } catch (NoSuchMethodException nsme) {
            Log.w(TAG, "setMirrorFoldState absent on this trim");
        } catch (Throwable t) {
            Log.w(TAG, "setMirrorFoldState failed: " + t.getMessage());
        }
        // Named method missing or refused — try the one mirror feature-id the OEM SDK
        // defines (Mirror.BODYWORK_REARVIEW_MIRROR_SET, 0x4EF32010) on the same device.
        try {
            int code = com.overdrive.app.byd.BydDeviceHelper.sendSetCommandRaw(
                    device, com.overdrive.app.byd.BydFeatureIds.MIRROR_REARVIEW_SET, val);
            Log.i(TAG, "BODYWORK_REARVIEW_MIRROR_SET(" + val + ") -> code=" + code
                    + (code == 0 ? " ACCEPTED" : " REFUSED"));
            return code == 0;
        } catch (Throwable t) {
            Log.w(TAG, "mirror feature-id write failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * HUD on/off + brightness (0..100). The OEM reference calls
     * {@code BYDAutoSettingDevice.setHUDBrightness(int)} from the app process; run it here in
     * the same environment. Accept-on-no-throw.
     */
    private boolean setHud(int level) {
        if (level < 0 || level > 100) return false;
        Object device = com.overdrive.app.byd.BydDeviceHelper.getDevice(SETTING_DEVICE, getApplicationContext());
        if (device == null) { Log.w(TAG, "setting device unavailable"); return false; }
        try {
            Method m = device.getClass().getMethod("setHUDBrightness", int.class);
            m.invoke(device, level);
            return true;
        } catch (NoSuchMethodException nsme) {
            Log.w(TAG, "setHUDBrightness absent on this trim");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "setHUDBrightness failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * HUD power on/off — the DEDICATED switch, distinct from brightness. Writes the
     * {@code SET_HUD_SWITCH_SET} setting feature-id as a {@code BYDAutoEventValue} via the
     * standard {@code BYDAutoSettingDevice.set(int[], EventValue)} path (the same
     * sendSetCommand mechanism every other setting write uses), from the REAL app process
     * where setting-HAL writes actually land (the UID-2000 daemon's silently no-op). The
     * OEM contract is value 1 = on, 2 = off (NOT 0). Best-effort: acquires the setting
     * device via {@code getApplicationContext()} and returns whether the write was accepted.
     */
    private boolean setHudPower(boolean on) {
        Object device = com.overdrive.app.byd.BydDeviceHelper.getDevice(SETTING_DEVICE, getApplicationContext());
        if (device == null) { Log.w(TAG, "setting device unavailable"); return false; }
        int val = on ? 1 : 2; // OEM: 1=on, 2=off
        boolean ok = com.overdrive.app.byd.BydDeviceHelper.sendSetCommand(
                device, com.overdrive.app.byd.BydFeatureIds.SETTING_HUD_SWITCH_SET, val);
        Log.i(TAG, "setHudPower SET_HUD_SWITCH_SET(" + val + ") accepted=" + ok);
        return ok;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        // Tier exactly as MediaPlaybackService/MessageOverlayService: SPECIAL_USE is API-34;
        // pass DATA_SYNC on Q..33 (this API-29 head unit), bare below. try/catch → bare
        // fallback so a rejected type can never leave us non-foreground.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t.getMessage());
            try { startForeground(NOTIFICATION_ID, n); } catch (Throwable ignored) {}
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Vehicle Control", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Vehicle control")
                .setContentText("OverDrive")
                .setSmallIcon(R.drawable.ic_play_circle)
                .setOngoing(false)
                .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }
}
