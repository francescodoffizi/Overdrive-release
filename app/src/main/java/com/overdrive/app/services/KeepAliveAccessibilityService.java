package com.overdrive.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.overdrive.app.ui.daemon.DaemonStartupManager;

/**
 * Minimal AccessibilityService that keeps the app process alive indefinitely.
 *
 * Android's OOM killer and OEM process killers (including BYD's DiLink firmware)
 * are hardcoded to never kill a process hosting an active AccessibilityService.
 * This gives our app the highest possible process priority — same tier as the
 * keyboard or phone call — preventing the 24-hour kill cycle on newer BYD firmware.
 *
 * The service itself is a no-op for accessibility events. Its sole purpose is
 * process keep-alive. The foreground notification provides user visibility.
 *
 * Enable via ADB (one-time):
 *   settings put secure enabled_accessibility_services com.overdrive.app/com.overdrive.app.services.KeepAliveAccessibilityService
 *   settings put secure accessibility_enabled 1
 */
public class KeepAliveAccessibilityService extends AccessibilityService {

    private static final String TAG = "KeepAliveA11y";

    private static KeepAliveAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        Log.i(TAG, "AccessibilityService connected — process is now protected");

        // Start the app-process signal monitors FIRST — BEFORE the setServiceInfo() block
        // below, which early-returns if the info-set throws. These monitors are independent
        // of key-filtering (they relay phone-call + Bluetooth state to the daemon, which
        // can't read them from UID 2000); gating them behind a successful setServiceInfo
        // meant a key-filter wiring failure ALSO silently killed call/BT automations. Both
        // are idempotent + self-guarding, so starting them early is safe.
        try {
            CallStateMonitor.start(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "CallStateMonitor start failed: " + t.getMessage());
        }
        try {
            BluetoothStateMonitor.start(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "BluetoothStateMonitor start failed: " + t.getMessage());
        }

        // Config must match the WORKING shape proven on DiLink firmware (verified
        // against a known-good OEM app): a service that subscribes to ZERO event
        // types (eventTypes=0) is treated as inert by this firmware's
        // AccessibilityManager — it binds, but onKeyEvent is NEVER dispatched to
        // it (observed live: service bound, capabilities=8, yet keycode 302 hit
        // WindowManager with FLAG_PASS_TO_USER and never reached onKeyEvent). The
        // fix is to subscribe to a real event type (typeWindowStateChanged) and
        // enable window-content retrieval, which fully wires the service into the
        // input path so FLAG_REQUEST_FILTER_KEY_EVENTS actually delivers keys.
        //
        // We MODIFY the info returned by getServiceInfo() (which already carries
        // the manifest XML config) rather than rebuilding it, and only assert the
        // fields that matter — never zero out eventTypes.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        // Mirror the proven OEM config EXACTLY (no extra flags): subscribe to a
        // real event type + report-view-ids + filter-key-events. Adding more than
        // the known-good set risks a different firmware quirk, so match it 1:1.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        // Wrap the info-set and publish instance=this ONLY on success. Previously
        // `instance` was set before setServiceInfo(): if the info-set threw, the key
        // filter wasn't wired yet in-proc isRunning() reported a false "healthy" while
        // keys were dead. Setting instance only after the wiring is actually in force
        // makes the liveness signal honest (a partial connect reads as not-live, so the
        // watchdog rebinds rather than trusting a half-connected service).
        try {
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.e(TAG, "setServiceInfo failed — key filtering NOT wired; leaving instance null "
                    + "so the watchdog rebinds: " + t.getMessage());
            return;
        }
        instance = this;

        // Prime the key-mapping snapshot off-thread so the first hardware key
        // press already has its bindings (onKeyEvent never reads disk itself).
        try {
            KeyMapDispatcher.INSTANCE.warmUp();
        } catch (Throwable t) {
            Log.w(TAG, "KeyMapDispatcher warmUp failed: " + t.getMessage());
        }

        // No foreground notification needed — DaemonKeepaliveService already has one.
        // The AccessibilityService binding alone is enough to protect the process.

        // Ensure daemons are running (respawn if killed)
        try {
            DaemonStartupManager.Companion.startOnBoot(getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "Daemon startup from A11y service: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No-op — we don't process accessibility events
    }

    /**
     * Hardware key filter. With FLAG_REQUEST_FILTER_KEY_EVENTS this is invoked
     * for physical KeyEvents (steering-wheel / dash buttons) BEFORE the OEM
     * handler. Returning true CONSUMES the event so the default action does not
     * also run; false lets it pass through untouched.
     *
     * Delegates to {@link KeyMapDispatcher}, which is fast (a config lookup) and
     * punts any actual actuation to a background thread — this callback runs on
     * the platform input-dispatch path and must never block, or the whole system
     * UI would ANR. If the feature is off or the key is unmapped, the dispatcher
     * returns false and the key behaves exactly as stock.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            // Bring-up diagnostic for "does this firmware dispatch hardware keys
            // to our filter at all". Now gated behind isLoggable: this method runs
            // on the PLATFORM INPUT-DISPATCH path, so an unconditional Log.i +
            // string concat here taxes every hardware key press system-wide (and
            // fires continuously while a key is held down, via getRepeatCount).
            // Key mapping is field-confirmed, so the log stays available on demand
            // (`setprop log.tag.KeepAliveA11y DEBUG`) without paying for it on
            // every keystroke.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onKeyEvent keyCode=" + event.getKeyCode()
                        + " down=" + down + " repeat=" + event.getRepeatCount());
            }
            return KeyMapDispatcher.INSTANCE.onKey(
                    event.getKeyCode(), down, event.getRepeatCount());
        } catch (Throwable t) {
            // Never let a mapping bug swallow keys or crash input dispatch —
            // fail open (pass the key through) on any error.
            Log.w(TAG, "onKeyEvent error, passing through: " + t.getMessage());
            return false;
        }
    }

    @Override
    public void onInterrupt() {
        // No-op
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "AccessibilityService destroyed — attempting restart");
        instance = null;

        // Clear transient key-gesture state so a gesture torn down mid-flight
        // (service destroyed between a promoting DOWN and its UP) can't strand a
        // suppressLongUntilUp entry and swallow the next long-press after re-enable.
        // Honors KeyMapDispatcher.teardown()'s documented contract; the FileObserver
        // is intentionally left running (warmUp()'s watcher != null guard keeps a
        // reconnect from leaking a second observer).
        try {
            KeyMapDispatcher.INSTANCE.teardown();
        } catch (Throwable t) {
            Log.w(TAG, "KeyMapDispatcher teardown failed: " + t.getMessage());
        }

        // Self-restart: send broadcast to trigger re-enable
        try {
            Intent restartIntent = new Intent("com.overdrive.app.RESTART_ACCESSIBILITY");
            sendBroadcast(restartIntent);
        } catch (Exception e) {
            Log.e(TAG, "Restart broadcast failed: " + e.getMessage());
        }

        super.onDestroy();
    }
}
