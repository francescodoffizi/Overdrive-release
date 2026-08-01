package com.overdrive.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Handles SCREEN_OFF events to ensure daemon survival during sleep.
 * 
 * NOTE: SCREEN_OFF cannot be registered in manifest - must be registered dynamically.
 * This receiver is registered in OverdriveApplication.
 * 
 * Delegates to BootReceiver to reuse the same daemon startup logic.
 */
class ScreenOffReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "ScreenOffReceiver"
        
        /**
         * Register this receiver dynamically.
         * Call from Application.onCreate().
         */
        fun register(context: Context): ScreenOffReceiver {
            val receiver = ScreenOffReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // Deliver on a background looper, NOT the main thread. onReceive
            // delegates to BootReceiver.onReceive, which does file stats,
            // possibly PreferencesManager.init (a file move), startService and
            // startOnBoot — all blocking. Screen off/on is exactly when the
            // native head-unit UI is animating, and this receiver is registered
            // 24/7 by DaemonKeepaliveService, so paying that on the app main
            // thread contends with system_server over binder and drops frames
            // system-wide. Safe off-main: the delegated work is already
            // async/idempotent (DaemonStartupManager.bootStarted is @Volatile and
            // startOnBoot no-ops on re-entry), so nothing depends on main-thread
            // delivery. Same actions, same ordering, same reliability.
            if (deliveryThread == null) {
                deliveryThread = android.os.HandlerThread("ScreenOffReceiver").apply { start() }
                deliveryHandler = android.os.Handler(deliveryThread!!.looper)
            }
            context.applicationContext.registerReceiver(
                receiver, filter, null, deliveryHandler)
            Log.i(TAG, "ScreenOffReceiver registered (background delivery)")
            return receiver
        }

        // Dedicated delivery looper (see register). Held statically because the
        // receiver is a process-lifetime singleton owned by DaemonKeepaliveService.
        private var deliveryThread: android.os.HandlerThread? = null
        private var deliveryHandler: android.os.Handler? = null
        
        /**
         * Unregister this receiver.
         */
        fun unregister(context: Context, receiver: ScreenOffReceiver) {
            try {
                context.applicationContext.unregisterReceiver(receiver)
                Log.i(TAG, "ScreenOffReceiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering: ${e.message}")
            }
            // NOTE: deliberately do NOT quitSafely() the delivery looper here.
            // The thread/handler are process-lifetime statics shared by every
            // registration, but `register` mints a NEW receiver per call and
            // DaemonKeepaliveService is START_STICKY (it is destroyed + respawned,
            // and parkStanddown stops it). So instance A's unregister could quit
            // the looper while instance B's receiver is still bound to it — after
            // which ActivityThread cannot enqueue B's dispatch and onReceive stops
            // firing FOREVER, silently killing screen-off-driven daemon startup.
            // A single idle HandlerThread parked in Looper.loop() costs ~nothing
            // (zero CPU, one stack); a permanently dead broadcast path costs the
            // feature. Keeping it alive also makes a later re-register work.
            // (If this ever needs real teardown, refcount registrations and quit
            // only at zero — do not quit unconditionally.)
        }
    }
    
    private val bootReceiver = BootReceiver()
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return
        
        Log.d(TAG, "SCREEN_OFF detected - delegating to BootReceiver")
        
        // Delegate to BootReceiver which handles all the daemon startup logic
        bootReceiver.onReceive(context, intent)
    }
}
