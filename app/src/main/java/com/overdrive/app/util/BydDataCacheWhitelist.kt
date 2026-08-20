package com.overdrive.app.util

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import com.overdrive.app.shell.HiddenApiBypass

/**
 * Utility to whitelist the app from BYD's background killing mechanism.
 * 
 * This sets app startup/ops data via BYD data cache services and ACC whitelist,
 * which prevents the app from being killed when running in the background.
 * 
 * Should be called once when the app launches.
 */
object BydDataCacheWhitelist {
    
    private const val TAG = "BydDataCacheWhitelist"
    private const val PKG = "com.overdrive.app"
    
    /**
     * Apply all BYD whitelist mechanisms.
     * Calls both ACC whitelist and data cache whitelist.
     * 
     * IMPORTANT: Calls HiddenApiBypass.bypass() first to unlock reflection access
     * to hidden system APIs before attempting any whitelist operations.
     * 
     * @param context Application or Activity context
     */
    fun applyAll(context: Context) {
        // 1. UNLOCK REFLECTION - Must be called before any hidden API access
        val bypassed = HiddenApiBypass.bypass()
        Log.i(TAG, "HiddenApiBypass result: $bypassed")
        val systemContext = createSystemPackageContext(context.packageName)
        if (systemContext != null) {
            whitelistAccPackage(systemContext)
            applyDataCache(systemContext)
        } else {
            Log.w(TAG, "Failed to create system context - trying with app context")
            whitelistAccPackage(context)
            applyDataCache(context)
        }
    }

    private fun createSystemPackageContext(packageName: String): Context? {
        try {
            Log.d(TAG, "createSystemPackageContext: Starting...")
            
            // Get ActivityThread class
            val activityThreadClass = Class.forName("android.app.ActivityThread")

            // CRITICAL: systemMain() can block indefinitely on boot
            // Run it in a separate thread with a timeout
            Log.d(TAG, "createSystemPackageContext: Trying systemMain with timeout...")
            
            val systemMainMethod = activityThreadClass.getMethod("systemMain")
            var activityThread: Any? = null
            
            val systemMainThread = Thread {
                try {
                    activityThread = systemMainMethod.invoke(null)
                } catch (e: Exception) {
                    Log.e(TAG, "createSystemPackageContext: systemMain exception: ${e.message}")
                }
            }
            
            systemMainThread.start()
            systemMainThread.join(10000) // 10 second timeout
            
            if (systemMainThread.isAlive) {
                Log.e(TAG, "createSystemPackageContext: systemMain TIMEOUT after 10s - aborting")
                systemMainThread.interrupt()
                return null
            }
            
            if (activityThread == null) {
                Log.e(TAG, "createSystemPackageContext: activityThread is null")
                return null
            }
            
            Log.d(TAG, "createSystemPackageContext: systemMain = $activityThread")

            // Get system context
            val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
            val systemContext = getSystemContextMethod.invoke(activityThread) as Context
            Log.d(TAG, "createSystemPackageContext: systemContext = $systemContext")

            // Create package context with CONTEXT_INCLUDE_CODE | CONTEXT_IGNORE_SECURITY
            val result = systemContext.createPackageContext(packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            Log.d(TAG, "createSystemPackageContext: Success")
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create system package context: ${e.message}")
            return null
        }
    }

    /**
     * Whitelist app from ACC power management killing.
     * Uses reflection to access mService field and call setPkg2AccWhiteList directly.
     * 
     * @param context Application or Activity context
     */
    fun whitelistAccPackage(context: Context) {
        Log.i(TAG, "Whitelisting package $PKG via accmodemanager...")
        
        try {
            // Use PermissionBypassContext for BYD service access
            val permissiveContext = PermissionBypassContext(context)
            val accManager = permissiveContext.getSystemService("accmodemanager")
            
            if (accManager == null) {
                Log.w(TAG, "accmodemanager service not found")
                return
            }
            
            // Get mService field
            val serviceField = accManager.javaClass.getDeclaredField("mService")
            serviceField.isAccessible = true
            val mService = serviceField.get(accManager)
            
            // Call setPkg2AccWhiteList on mService
            val method = mService.javaClass.getDeclaredMethod("setPkg2AccWhiteList", String::class.java)
            method.isAccessible = true
            method.invoke(mService, PKG)
            
            Log.i(TAG, "Whitelisted successfully via mService reflection!")
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "ACC Whitelist failed: $errorMsg")
            e.cause?.let { Log.w(TAG, "  Caused by: ${it.message}") }
        }
    }
    
    /**
     * Set app data cache whitelist for background protection.
     * Uses PermissionBypassContext to access system services without signature permissions.
     * 
     * @param context Application or Activity context
     */
    fun applyDataCache(context: Context) {
        Log.i(TAG, "Setting app data cache whitelist for $PKG...")
        
        try {
            // Use PermissionBypassContext for BYD service access
            val permissiveContext = PermissionBypassContext(context)
            val pm = permissiveContext.packageManager
            val uid = pm.getApplicationInfo(PKG, 0).uid
            val opsValue = 0
            
            // SDK >= 31 uses byd_datacached, otherwise bg_datacache.
            // Oem gates on >= 31 (C0241c.m941c). DiLink 4 ROMs that ship
            // Android 12 base (API 31) have the new service available;
            // routing them through bg_datacache instead hits ACCESS_APPOPSDATA
            // which the BYD ROM denies post-ACC-OFF.
            val useNewService = Build.VERSION.SDK_INT >= 31
            
            if (useNewService) {
                val dataCacheService = permissiveContext.getSystemService("byd_datacached")
                if (dataCacheService != null) {
                    val setMethod = dataCacheService.javaClass.getMethod(
                        "setAppStartupData",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    setMethod.invoke(dataCacheService, uid.toString(), opsValue)
                    Log.i(TAG, "setAppStartupData SUCCESS for UID $uid")
                } else {
                    Log.w(TAG, "byd_datacached service not found")
                }
            } else {
                val dataCacheService = permissiveContext.getSystemService("bg_datacache")
                if (dataCacheService != null) {
                    val setMethod = dataCacheService.javaClass.getMethod(
                        "setAppOpsData",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                    setMethod.invoke(dataCacheService, uid.toString(), opsValue)
                    Log.i(TAG, "setAppOpsData SUCCESS for UID $uid")
                } else {
                    Log.w(TAG, "bg_datacache service not found")
                }
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            Log.w(TAG, "setAppDataCacheWhitelist REJECTED: $cause")
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "setAppDataCacheWhitelist failed: $errorMsg")
            e.cause?.let { Log.w(TAG, "  Caused by: ${it.message}") }
        }
    }
    
    /**
     * Context wrapper that bypasses permission checks for BYD hardware access.
     * Note: Permission checks happen at the system service level via Binder IPC,
     * not in the Context class. This wrapper is used to ensure consistent context
     * usage across BYD service calls.
     */
    private class PermissionBypassContext(base: Context) : ContextWrapper(base)
}
