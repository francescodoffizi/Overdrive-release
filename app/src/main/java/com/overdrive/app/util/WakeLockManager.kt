package com.overdrive.app.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.overdrive.app.logging.LogManager

/**
 * Manages wake locks and WiFi locks to keep the device active.
 * 
 * Extracted from RecordingService for better separation of concerns.
 */
class WakeLockManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WakeLockManager"
        private const val WAKE_LOCK_TAG = "BYDCam::WakeLock"
        private const val WIFI_LOCK_TAG = "BYDCam::WifiLock"
    }
    
    private val logManager = LogManager.getInstance()
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    /**
     * Acquire wake lock to keep CPU running.
     */
    fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            logManager.debug(TAG, "Wake lock already held")
            return
        }
        
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            acquire()
        }
        
        logManager.info(TAG, "Wake lock acquired")
    }
    
    /**
     * Release wake lock.
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                logManager.info(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }
    
    /**
     * Acquire WiFi lock to keep WiFi active.
     */
    @Suppress("DEPRECATION")
    fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) {
            logManager.debug(TAG, "WiFi lock already held")
            return
        }
        
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            WIFI_LOCK_TAG
        ).apply {
            acquire()
        }
        
        logManager.info(TAG, "WiFi lock acquired")
    }
    
    /**
     * Release WiFi lock.
     */
    fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
                logManager.info(TAG, "WiFi lock released")
            }
        }
        wifiLock = null
    }
    
    /**
     * Acquire both wake lock and WiFi lock.
     */
    fun acquireAll() {
        acquireWakeLock()
        acquireWifiLock()
    }
    
    /**
     * Release all locks.
     */
    fun releaseAll() {
        releaseWakeLock()
        releaseWifiLock()
    }
    
    /**
     * Check if wake lock is held.
     */
    fun isWakeLockHeld(): Boolean = wakeLock?.isHeld == true
    
    /**
     * Check if WiFi lock is held.
     */
    fun isWifiLockHeld(): Boolean = wifiLock?.isHeld == true

    /**
     * Safe finalizer to prevent Android's FinalizerDaemon from logging
     * Log.wtf: 'WakeLock finalized while still held' if an instance is orphaned.
     */
    @Suppress("DEPRECATION")
    protected fun finalize() {
        try {
            releaseAll()
        } catch (_: Throwable) {}
    }
}
