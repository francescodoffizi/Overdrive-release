package com.overdrive.app.config

import android.content.Context
import android.content.SharedPreferences
import com.overdrive.app.logging.LogConfig
import com.overdrive.app.logging.LogLevel
import org.json.JSONObject

/**
 * Centralized configuration manager.
 * 
 * Manages all application configuration with persistence via SharedPreferences.
 */
class ConfigManager private constructor(private val context: Context) {
    
    companion object {
        @Volatile
        private var instance: ConfigManager? = null
        
        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext)
                    .also { instance = it }
            }
        }
        
        private const val PREFS_NAME = "overdrive_config"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_OUTPUT_DIR = "output_dir"
        private const val KEY_STREAM_MODE = "stream_mode"
        private const val KEY_SENTRY_MODE = "sentry_mode"
        private const val KEY_LOCATION_SIDECAR_ENABLED = "location_sidecar_enabled"
        private const val KEY_LOG_RETENTION = "log_retention_hours"
        private const val KEY_LOG_CLEANUP_INTERVAL = "log_cleanup_interval_hours"
        private const val KEY_LOG_MAX_SIZE = "log_max_size_mb"
        private const val KEY_LOG_ROTATION_COUNT = "log_rotation_count"
        private const val KEY_LOG_MIN_LEVEL = "log_min_level"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listeners = mutableListOf<ConfigChangeListener>()
    
    // In-memory cache
    private var appConfigCache: AppConfig? = null
    private var loggingConfigCache: LogConfig? = null
    
    /**
     * Get application configuration.
     */
    fun getAppConfig(): AppConfig {
        return appConfigCache ?: loadAppConfig().also { appConfigCache = it }
    }
    
    /**
     * Update application configuration.
     */
    fun updateAppConfig(config: AppConfig) {
        val old = getAppConfig()
        
        prefs.edit().apply {
            putString(KEY_DEVICE_ID, config.deviceId)
            putString(KEY_OUTPUT_DIR, config.outputDir)
            putString(KEY_STREAM_MODE, config.streamMode.name)
            putBoolean(KEY_SENTRY_MODE, config.sentryModeEnabled)
            putBoolean(KEY_LOCATION_SIDECAR_ENABLED, config.locationSidecarEnabled)
            apply()
        }
        
        appConfigCache = config
        notifyListeners("appConfig", old, config)
    }
    
    /**
     * Get logging configuration.
     */
    fun getLoggingConfig(): LogConfig {
        return loggingConfigCache ?: loadLoggingConfig().also { loggingConfigCache = it }
    }
    
    /**
     * Update logging configuration.
     */
    fun updateLoggingConfig(config: LogConfig) {
        if (!config.isValid()) {
            return
        }
        
        val old = getLoggingConfig()
        
        prefs.edit().apply {
            putInt(KEY_LOG_RETENTION, config.retentionHours)
            putInt(KEY_LOG_CLEANUP_INTERVAL, config.cleanupIntervalHours)
            putInt(KEY_LOG_MAX_SIZE, config.maxFileSizeMB)
            putInt(KEY_LOG_ROTATION_COUNT, config.rotationCount)
            putString(KEY_LOG_MIN_LEVEL, config.minLevel.name)
            apply()
        }
        
        loggingConfigCache = config
        notifyListeners("loggingConfig", old, config)
    }
    
    /**
     * Get daemon configuration.
     */
    fun getDaemonConfig(type: DaemonType): DaemonConfig {
        val autoStart = prefs.getBoolean("daemon_${type.name.lowercase()}_autostart", false)
        val retryAttempts = prefs.getInt("daemon_${type.name.lowercase()}_retry", 3)
        val retryDelay = prefs.getLong("daemon_${type.name.lowercase()}_retry_delay", 5000)
        
        return DaemonConfig(
            type = type,
            autoStart = autoStart,
            retryAttempts = retryAttempts,
            retryDelayMs = retryDelay
        )
    }
    
    /**
     * Update daemon configuration.
     */
    fun updateDaemonConfig(type: DaemonType, config: DaemonConfig) {
        if (!config.isValid()) {
            return
        }
        
        val old = getDaemonConfig(type)
        
        prefs.edit().apply {
            putBoolean("daemon_${type.name.lowercase()}_autostart", config.autoStart)
            putInt("daemon_${type.name.lowercase()}_retry", config.retryAttempts)
            putLong("daemon_${type.name.lowercase()}_retry_delay", config.retryDelayMs)
            apply()
        }
        
        notifyListeners("daemonConfig_${type.name}", old, config)
    }
    
    /**
     * Get stream mode.
     */
    fun getStreamMode(): StreamMode {
        val modeName = prefs.getString(KEY_STREAM_MODE, StreamMode.PRIVATE.name)
        return try {
            StreamMode.valueOf(modeName ?: StreamMode.PRIVATE.name)
        } catch (e: Exception) {
            StreamMode.PRIVATE
        }
    }
    
    /**
     * Set stream mode.
     */
    fun setStreamMode(mode: StreamMode) {
        val old = getStreamMode()
        prefs.edit().putString(KEY_STREAM_MODE, mode.name).apply()
        appConfigCache = appConfigCache?.copy(streamMode = mode)
        notifyListeners("streamMode", old, mode)
    }
    
    /**
     * Add configuration change listener.
     */
    fun addConfigChangeListener(listener: ConfigChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }
    
    /**
     * Remove configuration change listener.
     */
    fun removeConfigChangeListener(listener: ConfigChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
    
    /**
     * Export configuration as JSON.
     */
    fun exportConfig(): String {
        val json = JSONObject()
        json.put("app", JSONObject().apply {
            val app = getAppConfig()
            put("deviceId", app.deviceId)
            put("outputDir", app.outputDir)
            put("streamMode", app.streamMode.name)
            put("sentryMode", app.sentryModeEnabled)
            put("locationSidecar", app.locationSidecarEnabled)
        })
        json.put("logging", JSONObject().apply {
            val logging = getLoggingConfig()
            put("retentionHours", logging.retentionHours)
            put("cleanupIntervalHours", logging.cleanupIntervalHours)
            put("maxFileSizeMB", logging.maxFileSizeMB)
            put("rotationCount", logging.rotationCount)
            put("minLevel", logging.minLevel.name)
        })
        return json.toString(2)
    }
    
    /**
     * Import configuration from JSON.
     */
    fun importConfig(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            
            // Import app config
            if (root.has("app")) {
                val app = root.getJSONObject("app")
                val config = AppConfig(
                    deviceId = app.optString("deviceId", "unknown"),
                    outputDir = app.optString("outputDir", "/sdcard/DCIM/Overdrive"),
                    streamMode = StreamMode.valueOf(app.optString("streamMode", "PRIVATE")),
                    sentryModeEnabled = app.optBoolean("sentryMode", false),
                    locationSidecarEnabled = app.optBoolean("locationSidecar", false)
                )
                updateAppConfig(config)
            }
            
            // Import logging config
            if (root.has("logging")) {
                val logging = root.getJSONObject("logging")
                val config = LogConfig(
                    retentionHours = logging.optInt("retentionHours", 24),
                    cleanupIntervalHours = logging.optInt("cleanupIntervalHours", 4),
                    maxFileSizeMB = logging.optInt("maxFileSizeMB", 5),
                    rotationCount = logging.optInt("rotationCount", 3),
                    // "minLevel": "DEBUG" switches on a field debug session without a
                    // rebuild. Omitted or misspelled ⇒ INFO.
                    minLevel = parseMinLevel(logging.optString("minLevel").takeIf { it.isNotEmpty() })
                )
                if (config.isValid()) {
                    updateLoggingConfig(config)
                }
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // Private helpers
    
    private fun loadAppConfig(): AppConfig {
        return AppConfig(
            deviceId = prefs.getString(KEY_DEVICE_ID, "unknown") ?: "unknown",
            outputDir = prefs.getString(KEY_OUTPUT_DIR, "/sdcard/DCIM/Overdrive") ?: "/sdcard/DCIM/Overdrive",
            streamMode = getStreamMode(),
            sentryModeEnabled = prefs.getBoolean(KEY_SENTRY_MODE, false),
            locationSidecarEnabled = prefs.getBoolean(KEY_LOCATION_SIDECAR_ENABLED, false)
        )
    }
    
    private fun loadLoggingConfig(): LogConfig {
        // Defaults mirror LogConfig's own defaults (5MB cap, 4h cleanup) so the
        // app-side and daemon-side rotation policy stay coherent. See
        // DaemonLauncher.LOG_MAX_BYTES / LOG_POLL_INTERVAL_SEC.
        return LogConfig(
            retentionHours = prefs.getInt(KEY_LOG_RETENTION, 24),
            cleanupIntervalHours = prefs.getInt(KEY_LOG_CLEANUP_INTERVAL, 4),
            maxFileSizeMB = prefs.getInt(KEY_LOG_MAX_SIZE, 5),
            rotationCount = prefs.getInt(KEY_LOG_ROTATION_COUNT, 3),
            minLevel = parseMinLevel(prefs.getString(KEY_LOG_MIN_LEVEL, null))
        )
    }

    /**
     * Read a persisted/imported [LogLevel] name, falling back to INFO. Unrecognised input
     * must NOT throw — this is the one setting a user is likely to hand-edit over ADB, and
     * a typo that broke config load would take the whole app's logging with it.
     */
    private fun parseMinLevel(name: String?): LogLevel =
        name?.let { runCatching { LogLevel.valueOf(it.uppercase()) }.getOrNull() } ?: LogLevel.INFO
    
    private fun notifyListeners(key: String, oldValue: Any?, newValue: Any?) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onConfigChanged(key, oldValue, newValue)
                } catch (e: Exception) {
                    // Ignore listener errors
                }
            }
        }
    }
}

/**
 * Configuration change listener interface.
 */
interface ConfigChangeListener {
    fun onConfigChanged(key: String, oldValue: Any?, newValue: Any?)
}
