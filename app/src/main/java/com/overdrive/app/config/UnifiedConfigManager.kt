package com.overdrive.app.config

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * SOTA Unified Configuration Manager
 * 
 * Solves the UID permission problem by using a world-accessible location
 * that both the app (via IPC) and shell daemon can read/write.
 * 
 * Architecture:
 * - Single JSON file at /data/local/tmp/overdrive_config.json
 * - App UI writes via IPC to daemon (daemon has shell UID 2000)
 * - Web UI/daemon writes directly (already has shell UID 2000)
 * - Both read from the same file
 * - Change listeners for real-time sync
 * 
 * Config sections:
 * - surveillance: Detection settings (minObjectSize, flashImmunity, etc.)
 * - recording: Recording settings (bitrate, codec, pre/post buffer)
 * - streaming: Streaming quality settings
 * - telegram: Telegram bot settings
 */
object UnifiedConfigManager {
    private const val TAG = "UnifiedConfig"
    
    // Single source of truth - world-readable location
    private const val CONFIG_PATH = "/data/local/tmp/overdrive_config.json"

    // ==================== DOUBLE-BUFFERED, SEQ-STAMPED DURABILITY ====================
    //
    // The core OTA config-loss defense. The config lives in sticky
    // /data/local/tmp, where ONLY the daemon (UID 2000) can create the sibling
    // needed for an atomic tmp+rename — the app UID (10xxx) cannot, so its
    // direct writes are a NON-atomic truncate-rewrite that `pm install -r`'s
    // SIGKILL can tear. During the pm-install window the daemon is DEAD, so the
    // app is forced onto exactly that fragile path with no atomic-capable peer
    // to forward to. Defenses, layered:
    //
    //  1. STICKY .bak  — daemon-maintained last-known-good in the shared dir.
    //                    World-readable so any process can recover from it.
    //  2. APP-PRIVATE .bak — a SECOND last-known-good the APP writes ATOMICALLY
    //                    in its own data dir (it CAN create siblings + rename
    //                    there). This is the copy that stays CURRENT across an
    //                    entire daemon-down session — the sticky .bak goes stale
    //                    because the app can't refresh it. The daemon can't read
    //                    this dir (0700 app-owned), which is fine: the app heals
    //                    itself from it, and the seq-promotion below repairs the
    //                    case where the daemon healed the live file from its
    //                    stale sticky .bak first.
    //  3. configSeq — a MONOTONIC counter embedded in the config and bumped on
    //                    every saveConfig under the cross-process lock. Recovery
    //                    and load-time promotion pick the copy with the HIGHEST
    //                    seq, so the system can NEVER silently revert to an older
    //                    snapshot — even with ext4 second-granular mtime or a
    //                    skewed RTC (BYD head units boot with a wrong clock).
    //
    // Hardcoded app-private path (UCM is a Context-free singleton object). Must
    // match the application's package — Context.filesDir resolves here.
    private const val APP_PACKAGE = "com.overdrive.app"
    private const val APP_PRIVATE_DIR = "/data/data/$APP_PACKAGE/files"
    private const val APP_PRIVATE_BAK_PATH = "$APP_PRIVATE_DIR/overdrive_config.json.bak"
    // Monotonic write sequence. Absent (legacy configs) reads as 0; saveConfig
    // bumps it. Stripped from backup bundles (see ConfigBackupService) so an
    // imported bundle never injects a foreign seq.
    private const val SEQ_KEY = "configSeq"

    // Daemon IPC: the app process (UID >= 10000) cannot create the .tmp sibling
    // needed for an atomic write in sticky /data/local/tmp/, so its direct
    // writes fall back to a NON-atomic truncate-then-rewrite that corrupts the
    // file if the process is killed mid-write (the v23.x→v24.1 wipe trigger:
    // `pm install -r` kills the app). To avoid that, app-process writes are
    // forwarded to the daemon (UID 2000, which CAN atomic-rename) over the
    // existing SurveillanceIpcServer localhost socket; the daemon applies them
    // in-process via the atomic path. Falls back to the local (guarded) write
    // only when the daemon is unreachable. See routeWriteIfApp().
    private const val DAEMON_IPC_HOST = "127.0.0.1"
    private const val DAEMON_IPC_PORT = 19877
    // The daemons (CameraDaemon, AccSentryDaemon, TelegramBotDaemon) are all
    // spawned via app_process and run as shell UID 2000 — they own the sticky
    // /data/local/tmp/ files and CAN atomic-rename, so they write locally.
    // ANY other UID (app 10xxx, or a future system-1000 writer) cannot reliably
    // create the .tmp sibling and so reroutes to the daemon. Gating on "== 2000"
    // (can-atomic-write) rather than "< 10000" deliberately routes a non-shell
    // privileged writer to the safe path instead of letting it truncate.
    private const val SHELL_DAEMON_UID = 2000
    // Localhost round-trip budget. Connect must fail fast when the daemon is
    // down (the update window) so the caller falls through to the guarded local
    // write without a long stall; read timeout covers the daemon's full-JSON
    // rewrite under CONFIG_LOCK contention.
    private const val IPC_CONNECT_TIMEOUT_MS = 1500
    private const val IPC_READ_TIMEOUT_MS = 5000
    
    // Legacy paths for migration
    private const val LEGACY_SENTRY_CONFIG = "/data/local/tmp/sentry_config.json"
    private const val LEGACY_CAMERA_SETTINGS = "/data/local/tmp/camera_settings.json"
    private const val LEGACY_SYSTEM_CONFIG = "/data/data/com.android.providers.settings/sentry_config.json"
    
    // In-memory cache
    @Volatile
    private var cachedConfig: JSONObject? = null
    private val lastModified = AtomicLong(0)
    /**
     * Record the freshness key (the file mtime) for the bytes now cached. Call
     * everywhere the cache is (re)populated. 0 = "force re-read next time".
     */
    private fun stampFreshness(mtime: Long) {
        lastModified.set(mtime)
    }

    /**
     * True iff the cache is still valid for [configFile].
     *
     * ext4 mtime is second-granular, so a cross-UID write committing in the
     * SAME wall-clock second as our cached snapshot yields an EQUAL mtime — the
     * old `fileModified <= lastModified` fast-path then served a stale config to
     * bare-loadConfig getters (getSurveillance/getRecording/…). Pairing mtime
     * with file SIZE was insufficient too: a pretty-printed same-length edit
     * (targetFps 15→30, a double 1.66→1.77, an enum of equal length) keeps the
     * byte count identical and slips through.
     *
     * Correct + cheap rule: serve the cache ONLY when the on-disk mtime is
     * UNCHANGED since we cached AND the wall clock has advanced PAST that second
     * — because a write landing after our stamped second necessarily carries a
     * strictly greater mtime, so an equal mtime in a LATER second proves no
     * write has occurred. Any other case re-reads:
     *  - mtime advanced               → a newer write, reparse.
     *  - mtime LOWER than our stamp    → the RTC stepped backward (a documented
     *                                    BYD head-unit condition the rest of this
     *                                    file already distrusts) or a recovery
     *                                    wrote an older-mtime file; the content
     *                                    may be newer, so reparse rather than
     *                                    trust the clock.
     *  - mtime equal, still IN that    → a same-second write could share this
     *    second                          mtime; reparse to be safe.
     * Cost: a bare getter polled within the 1s after a write reparses a few
     * times — negligible, and strictly safer. The monotonic configSeq remains
     * the authoritative ordering signal for recovery/promotion; this gate only
     * decides cache-hit vs reparse.
     */
    private fun isCacheFresh(configFile: File): Boolean {
        val stamped = lastModified.get()
        if (stamped <= 0L) return false                          // sentinel → always re-read
        if (configFile.lastModified() != stamped) return false   // any mismatch → re-read
        return (System.currentTimeMillis() / 1000L) > (stamped / 1000L)
    }

    // Raised when loadConfig() finds a NON-EMPTY but unparseable file on disk
    // (corruption — e.g. the non-atomic fallback write in saveConfigInternal
    // truncated by a process kill during `pm install`). While set, saveConfig()
    // refuses to overwrite the live file with defaults, so a transient
    // corruption can't cascade into a PERMANENT total settings wipe: the next
    // updateSection() would otherwise merge into a defaults-only in-memory
    // config and persist it over the user's real (recoverable) settings.
    // Cleared by a successful load or forceReload().
    @Volatile
    private var corruptionDetected = false
    
    // Change listeners
    private val listeners = CopyOnWriteArrayList<ConfigChangeListener>()
    
    interface ConfigChangeListener {
        fun onConfigChanged(section: String, config: JSONObject)
    }
    
    /**
     * Initialize and migrate from legacy configs if needed.
     */
    @JvmStatic
    fun init() {
        // Provision the cross-process lock file here. init() runs in the daemon
        // (UID 2000), which CAN create files in sticky /data/local/tmp; once it
        // exists 0666, the app UID (which canNOT create there) can still OPEN it
        // to acquire the lock. Without this, an app-UID writer in the daemon-down
        // window can't create the lock and silently degrades to monitor-only
        // (no cross-process exclusion). Best-effort — withConfigFileLock still
        // falls back gracefully if it's somehow absent.
        provisionLockFile()

        val configFile = File(CONFIG_PATH)

        if (!configFile.exists()) {
            Log.i(TAG, "Unified config not found, migrating from legacy configs...")
            migrateFromLegacy()
        } else {
            // SOTA: Always re-assert 666 if we're the daemon. If the app
            // process wrote the file during the update window, it might
            // have different permissions than we expect on some builds (DiLink 3).
            if (android.os.Process.myUid() == SHELL_DAEMON_UID) {
                try {
                    configFile.setReadable(true, false)
                    configFile.setWritable(true, false)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-assert permissions: ${e.message}")
                }
            }
            Log.i(TAG, "Unified config exists at $CONFIG_PATH")
            loadConfig()
        }
    }

    /** Create the lock file 0666 (daemon UID) so the app UID can later open it. */
    private fun provisionLockFile() {
        try {
            val lf = File(LOCK_PATH)
            if (!lf.exists()) {
                lf.parentFile?.mkdirs()
                lf.createNewFile()
            }
            lf.setReadable(true, false)
            lf.setWritable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Lock file provisioning skipped: ${e.message}")
        }
    }
    
    /**
     * Migrate from legacy config files to unified config.
     */
    private fun migrateFromLegacy() {
        val unified = JSONObject()
        
        // Initialize sections
        unified.put("surveillance", JSONObject())
        unified.put("recording", JSONObject())
        unified.put("streaming", JSONObject())
        unified.put("telegram", JSONObject())
        unified.put("camera", JSONObject())
        unified.put("proximityGuard", JSONObject())
        unified.put("telemetryOverlay", JSONObject())
        unified.put("tripAnalytics", JSONObject())
        unified.put("oemDashcam", JSONObject())
        unified.put("keymap", JSONObject())
        unified.put("automation", JSONObject())
        unified.put("version", 1)
        unified.put("lastModified", System.currentTimeMillis())
        
        // Try to migrate from legacy sentry config
        try {
            val legacySentry = File(LEGACY_SENTRY_CONFIG)
            if (legacySentry.exists()) {
                val legacy = JSONObject(legacySentry.readText())
                val surveillance = unified.getJSONObject("surveillance")
                
                // Copy surveillance settings
                copyIfExists(legacy, surveillance, "blockSize")
                copyIfExists(legacy, surveillance, "requiredBlocks")
                copyIfExists(legacy, surveillance, "sensitivity")
                copyIfExists(legacy, surveillance, "flashImmunity")
                copyIfExists(legacy, surveillance, "temporalFrames")
                copyIfExists(legacy, surveillance, "useChroma")
                copyIfExists(legacy, surveillance, "minDistanceM")
                copyIfExists(legacy, surveillance, "maxDistanceM")
                copyIfExists(legacy, surveillance, "cameraHeightM")
                copyIfExists(legacy, surveillance, "cameraTiltDeg")
                copyIfExists(legacy, surveillance, "verticalFovDeg")
                copyIfExists(legacy, surveillance, "aiConfidence")
                copyIfExists(legacy, surveillance, "minObjectSize")
                copyIfExists(legacy, surveillance, "detectPerson")
                copyIfExists(legacy, surveillance, "detectCar")
                copyIfExists(legacy, surveillance, "detectBike")
                copyIfExists(legacy, surveillance, "detectAnimal")
                copyIfExists(legacy, surveillance, "preRecordSeconds")
                copyIfExists(legacy, surveillance, "postRecordSeconds")
                
                Log.i(TAG, "Migrated surveillance settings from $LEGACY_SENTRY_CONFIG")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from legacy sentry config: ${e.message}")
        }
        
        // Try to migrate from legacy camera settings
        try {
            val legacyCamera = File(LEGACY_CAMERA_SETTINGS)
            if (legacyCamera.exists()) {
                val legacy = JSONObject(legacyCamera.readText())
                val recording = unified.getJSONObject("recording")
                val streaming = unified.getJSONObject("streaming")
                
                // Copy recording settings
                copyIfExists(legacy, recording, "recordingBitrate", "bitrate")
                copyIfExists(legacy, recording, "recordingCodec", "codec")
                copyIfExists(legacy, recording, "recordingQuality", "quality")
                
                // Copy streaming settings
                copyIfExists(legacy, streaming, "streamingQuality", "quality")
                
                Log.i(TAG, "Migrated recording/streaming settings from $LEGACY_CAMERA_SETTINGS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from legacy camera settings: ${e.message}")
        }
        
        // Try system config as fallback
        try {
            val systemConfig = File(LEGACY_SYSTEM_CONFIG)
            if (systemConfig.exists()) {
                val legacy = JSONObject(systemConfig.readText())
                val surveillance = unified.getJSONObject("surveillance")
                
                // Only copy if not already set
                if (!surveillance.has("minObjectSize")) {
                    copyIfExists(legacy, surveillance, "minObjectSize")
                }
                if (!surveillance.has("flashImmunity")) {
                    copyIfExists(legacy, surveillance, "flashImmunity")
                }
                
                Log.i(TAG, "Migrated additional settings from $LEGACY_SYSTEM_CONFIG")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate from system config: ${e.message}")
        }
        
        // Apply defaults for missing values
        applyDefaults(unified)

        // Persist cross-process-safely. This is the LAST write path that used
        // to bypass the file lock: two daemon JVMs cold-starting before the
        // file exists would both migrate-and-rename on the shared ${name}.tmp,
        // and a peer's already-committed section could be clobbered with our
        // defaults-only object. Acquire the lock and RE-READ under it: if the
        // file now exists (a peer migrated/wrote first), fold our defaults into
        // THOSE bytes for genuinely-absent keys only (applyDefaults is
        // idempotent) and keep the peer's sections; only write `unified` when
        // the file is still absent.
        withConfigFileLock {
            val cf = File(CONFIG_PATH)
            val toPersist = if (cf.exists() && cf.length() > 0L) {
                try {
                    val onDisk = JSONObject(cf.readText())
                    applyDefaults(onDisk)   // fill only absent keys; preserves peer sections
                    onDisk
                } catch (e: Exception) {
                    // On-disk bytes unparseable (mid-write/corrupt) — fall back
                    // to our freshly-migrated object rather than crash.
                    Log.w(TAG, "Migration re-read failed (${e.message}); writing migrated object")
                    unified
                }
            } else {
                unified
            }
            // Stamp the monotonic sequence on the very first persist so the
            // backups seeded below carry a seq from creation (recovery /
            // promotion can then order them). max(onDisk, mem)+1 keeps it
            // monotonic if a peer already wrote a seq we re-read above.
            toPersist.put(SEQ_KEY, nextConfigSeq(toPersist))
            if (saveConfigInternal(toPersist)) {
                cachedConfig = toPersist
                stampFreshness(File(CONFIG_PATH).lastModified())
                // Seed the last-known-good copies AT CREATION so there is never
                // a "live file exists but no .bak" window for an app-UID torn
                // write to fall into. The daemon (which runs init/migrate) can
                // atomic-rename the sticky .bak; the app-private .bak is a no-op
                // here (daemon UID) and gets seeded on the app's first save.
                writeBackupCopy(toPersist)
                writeAppPrivateBackup(toPersist)
            } else {
                cachedConfig = toPersist
                // Persist failed (e.g. app UID, tmp-create denied). Force the
                // next loadConfig to re-read rather than serve this unstamped
                // in-memory object — explicit intent, not reliance on the
                // still-initial sentinel.
                stampFreshness(0)
            }
        }

        Log.i(TAG, "Migration complete. Unified config saved to $CONFIG_PATH")
    }
    
    private fun copyIfExists(from: JSONObject, to: JSONObject, key: String, newKey: String = key) {
        if (from.has(key)) {
            to.put(newKey, from.get(key))
        }
    }
    
    /**
     * Public entry to [applyDefaults] for callers that build a config object
     * outside the normal load path (e.g. ConfigBackupService overlaying a
     * restored bundle) and need every section backfilled with current-app
     * defaults before persisting. Idempotent — only fills absent keys.
     */
    @JvmStatic
    fun ensureDefaults(config: JSONObject) = applyDefaults(config)

    /**
     * Run [body] while holding the SAME cross-process advisory lock that
     * [updateSection]/[updateValues] use, so a bulk read-modify-write done
     * outside this object (ConfigBackupService.applyBundle restoring a backup)
     * can't be interleaved by a peer daemon JVM's section write (stale-snapshot
     * lost update). Inside [body], call [forceReload] to get a fresh under-lock
     * read, then [saveConfig]; the lock is reentrant per-thread so saveConfig's
     * own paths nest harmlessly.
     */
    @JvmStatic
    fun <T> runUnderConfigLock(body: () -> T): T = withConfigFileLock(body)

    private fun applyDefaults(config: JSONObject) {
        // optJSONObject (not getJSONObject) for these three: a partially-formed
        // config missing any of them must NOT throw here. applyDefaults runs
        // inside loadConfig()'s try, whose catch falls back to
        // createDefaultConfig() — so a throw on one absent section would
        // silently reset EVERY section to defaults. Seed an empty object
        // instead and let the per-key fills below populate it.
        val surveillance = config.optJSONObject("surveillance") ?: JSONObject().also {
            config.put("surveillance", it)
        }
        val recording = config.optJSONObject("recording") ?: JSONObject().also {
            config.put("recording", it)
        }
        val streaming = config.optJSONObject("streaming") ?: JSONObject().also {
            config.put("streaming", it)
        }
        val camera = config.optJSONObject("camera") ?: JSONObject().also {
            config.put("camera", it)
        }
        val proximityGuard = config.optJSONObject("proximityGuard") ?: JSONObject().also {
            config.put("proximityGuard", it)
        }
        val blindspot = config.optJSONObject("blindspot") ?: JSONObject().also {
            config.put("blindspot", it)
        }
        // Camera-view section: an on-demand camera view (front/rear/left/right/all-4)
        // shown on the SAME native SurfaceControl lane the blind-spot feature uses,
        // arbitrated at render time with blind-spot priority. No default keys force
        // behavior (enabled defaults false, so a config without this section is
        // byte-identical to the shipping path); just guarantee the section exists so
        // per-key merges never miss the whole object. Keys (all optional):
        //   enabled(bool), mode(int 0=all/1=front/2=right/3=rear/4=left),
        //   target("head_unit"|"cluster"), geometry{sizePct,corner | x,y,w,h},
        //   geometryCluster{...}, autoHideSec(int; 0=until explicitly hidden).
        config.optJSONObject("camview") ?: JSONObject().also {
            config.put("camview", it)
        }
        // Power / battery-safety section. Holds the HV-SoC surveillance cutoff
        // read by SocCutoffMonitor.cutoffPercent() (key power.lowSocCutoffPercent).
        val power = config.optJSONObject("power") ?: JSONObject().also {
            config.put("power", it)
        }
        // Ensure the telegram section exists (createDefaultConfig seeds it, but
        // applyDefaults must also work on a partial config built elsewhere — e.g.
        // a restored backup whose telegram section was skipped on a key
        // mismatch). No default keys to seed (token/owner are user-set); just
        // guarantee the empty object is present so the persisted config is
        // never missing the whole section. Same for telegram as for bydCloud/
        // navMap below.
        config.optJSONObject("telegram") ?: JSONObject().also {
            config.put("telegram", it)
        }

        // Key-mapping section: {enabled, allowAdvanced, bindings:[...]}. Bindings
        // are user-authored (keycode + pressType + action), so there are no
        // default keys to seed — just guarantee the section object exists so a
        // partial/restored config is never missing it (same rationale as
        // telegram above). enabled/allowAdvanced default false at read time.
        config.optJSONObject("keymap") ?: JSONObject().also {
            config.put("keymap", it)
        }

        // Surveillance defaults
        if (!surveillance.has("minObjectSize")) surveillance.put("minObjectSize", 0.08)
        if (!surveillance.has("aiConfidence")) surveillance.put("aiConfidence", 0.25)
        if (!surveillance.has("flashImmunity")) surveillance.put("flashImmunity", 2)
        if (!surveillance.has("detectPerson")) surveillance.put("detectPerson", true)
        if (!surveillance.has("detectCar")) surveillance.put("detectCar", true)
        if (!surveillance.has("detectBike")) surveillance.put("detectBike", false)
        if (!surveillance.has("detectAnimal")) surveillance.put("detectAnimal", false)
        if (!surveillance.has("preRecordSeconds")) surveillance.put("preRecordSeconds", 5)
        if (!surveillance.has("postRecordSeconds")) surveillance.put("postRecordSeconds", 10)
        if (!surveillance.has("blockSize")) surveillance.put("blockSize", 32)
        if (!surveillance.has("requiredBlocks")) surveillance.put("requiredBlocks", 3)
        if (!surveillance.has("sensitivity")) surveillance.put("sensitivity", 0.04)
        if (!surveillance.has("surveillanceEnabled")) surveillance.put("surveillanceEnabled", false)
        // ACC-OFF mode: "smart" runs the existing motion + YOLO event pipeline;
        // "continuous" records a plain rolling 4-cam mosaic with no filters and
        // no AI. Branched at SurveillanceEngineGpu.enable(). Default smart so
        // behaviour matches the prior single-mode build.
        if (!surveillance.has("accOffMode")) surveillance.put("accOffMode", "smart")
        // Arm mode: WHEN surveillance arms after the car powers off.
        //   "lock"  — arm when the doors lock, disarm when they unlock (avoids
        //             recording the owner). Because most trims here can't read
        //             lock state reliably (cloud rarely fires lock events, OTA
        //             exposes only the LF door, the legacy device path returns
        //             INVALID on every field firmware), lock mode ALSO force-arms
        //             at DOOR_LOCK_ARM_TIMEOUT_MS (60s) when lock state stayed
        //             unreadable — otherwise it would never arm on those trims.
        //   "power" — arm immediately on ACC-off, disarm on ACC-on. No lock gate.
        //             Deterministic; works on every trim.
        // Branched in CameraDaemon's ACC-off dispatch + door-lock gate. Both modes
        // still honor the safe-zone and schedule suppression gates. Default "lock"
        // to preserve the owner-privacy behaviour of the prior single-mode build.
        if (!surveillance.has("armMode")) surveillance.put("armMode", "lock")
        // Keep ONLY the USB/data rail powered after ACC OFF (e.g. to charge a phone
        // while parked). DEFAULT TRUE so out-of-box behaviour is unchanged; user
        // opt-out (Surveillance → General) lets just that rail sleep on the next
        // ACC-OFF cycle to save the 12 V battery. Does NOT affect the cameras — the
        // camera/AVM/ISP power keep-alives are unconditional in AccSentryDaemon.
        if (!surveillance.has("keepUsbPowerOnAccOff")) surveillance.put("keepUsbPowerOnAccOff", true)
        // Operating mode: WHICH lifecycle phases OverDrive is active for.
        //   "onAndOff" — full current behaviour: after the vehicle powers off the
        //                daemon keeps the head unit awake (MCU/USB/AP wake, keep-alive
        //                loop, voltage/SoC monitors) AND runs post-OFF surveillance /
        //                sentry. This is the DEFAULT so out-of-box behaviour and every
        //                existing/absent config is byte-identical to the prior build.
        //   "onOnly"   — everything that runs AFTER vehicle-OFF is disabled: no
        //                keep-awake, no post-OFF surveillance/recording, revival alarms
        //                stood down — the head unit is allowed to sleep normally when the
        //                car is off. Everything that runs WHILE the vehicle is ON
        //                (ACC-on dashcam, trips, charging analytics, live telemetry)
        //                keeps working. Gated at each post-OFF choke point via
        //                isVehicleOnOnlyMode(). Read fresh (mtime-gated) so a change
        //                applies on the next ACC cycle without a daemon restart.
        if (!surveillance.has("operatingMode")) surveillance.put("operatingMode", "onAndOff")
        if (!surveillance.has("deterrentAction")) surveillance.put("deterrentAction", "silent")
        if (!surveillance.has("deterrentCooldownSeconds")) surveillance.put("deterrentCooldownSeconds", 15)
        if (!surveillance.has("screenDeterrentEnabled")) surveillance.put("screenDeterrentEnabled", false)
        if (!surveillance.has("screenDeterrentDurationSeconds")) surveillance.put("screenDeterrentDurationSeconds", 8)
        if (!surveillance.has("screenDeterrentImagePath")) surveillance.put("screenDeterrentImagePath", "")
        if (!surveillance.has("screenDeterrentMessage")) surveillance.put("screenDeterrentMessage", "")

        // HV traction-battery SoC cutoff for parked surveillance. At or below
        // this %, SocCutoffMonitor arms a 60 s grace then self-shuts-down to
        // protect the pack. User-tunable from Surveillance → General (slider
        // 0..30). 0 = Off: SocCutoffMonitor.onElecPercentageChanged() early-
        // returns on pct<=0 BEFORE the cutoff compare, so a 0 cutoff can never
        // arm. Default 10 matches SocCutoffMonitor.DEFAULT_CUTOFF_PERCENT.
        if (!power.has("lowSocCutoffPercent")) power.put("lowSocCutoffPercent", 10)

        // Recording defaults. The canonical key is `recordingQuality` (ECONOMY..MAX).
        // `quality` is the legacy mirror; `bitrate` (LOW/MEDIUM/HIGH) is no longer
        // seeded — it would drift from the active tier and confuse cross-channel
        // readers. Keep it only if a user actually has it from a pre-migration install.
        if (!recording.has("mode")) recording.put("mode", "NONE")  // Default: no recording
        if (!recording.has("recordingQuality")) recording.put("recordingQuality", "STANDARD")
        if (!recording.has("quality")) recording.put("quality", recording.optString("recordingQuality", "STANDARD"))
        if (!recording.has("codec")) recording.put("codec", "H264")
        // Surveillance (ACC-off / parked sentry) quality tier. Independent of the
        // ACC-on recordingQuality above so parked footage can use a different
        // bitrate (e.g. thriftier while parked all day, or higher for evidence).
        //
        // DELIBERATELY NOT SEEDED. It resolves via read-time fallback in each
        // consumer instead: when this key is absent, the pano pipeline falls
        // back to recording.recordingQuality and the OEM dashcam falls back to
        // its own oemDashcam.recordingQuality → recording.recordingQuality
        // chain. Physically seeding it (e.g. from recording.recordingQuality)
        // would REGRESS the OEM axis whenever oemDashcam.recordingQuality
        // diverges from the pano tier — the seeded pano value would override
        // the OEM's own slot. Absence = byte-identical to the pre-split world
        // for BOTH pipelines; presence = user explicitly chose a surveillance
        // tier. Both flows resolve bitrate against the SHARED `codec` key.
        // Recording-side dewarp strength (Fitzgibbon division model). 0..100,
        // 0 = off (default). Single source of truth for both ACC-on dashcam
        // and ACC-off surveillance flows — both pipelines read the same key
        // so one slider in the UI applies everywhere. Only effective on the
        // legacy 4-strip layout; dilink4 cars get a clean 2x2 from the HAL
        // already and the recorder's shader gates the dewarp accordingly.
        if (!recording.has("rectifyStrength")) recording.put("rectifyStrength", 0)

        // Clip segment length in minutes (2/5/10). Single source of truth for
        // BOTH the ACC-on dashcam and ACC-off / OEM surveillance flows — both
        // pipelines read this one key so a single UI control applies
        // everywhere (mirrors rectifyStrength above).
        if (!recording.has("segmentDurationMinutes")) {
            recording.put("segmentDurationMinutes",
                com.overdrive.app.util.Constants.SEGMENT_DURATION_MINUTES)
        }

        // Streaming defaults
        if (!streaming.has("quality")) streaming.put("quality", "MEDIUM")

        // Camera defaults. cameraProfile=auto lets the runtime resolver infer
        // Tang vs legacy panoramic dims from ro.product.model. Existing
        // installs that only have probedCameraId continue to work unchanged.
        if (!camera.has("cameraProfile")) {
            camera.put("cameraProfile",
                com.overdrive.app.camera.CameraProfiles.PROFILE_AUTO)
        }
        if (!camera.has("targetFps"))         camera.put("targetFps", 15)
        // Surveillance (ACC-off / parked sentry) camera fps. Independent of the
        // ACC-on targetFps so parked recording can run at a lower rate to save
        // power/storage while the car sits all day.
        //
        // DELIBERATELY NOT SEEDED (see surveillanceQuality above for the full
        // rationale). Read-time fallback only: absent ⇒ the pano pipeline uses
        // camera.targetFps (15) and the OEM dashcam uses its own
        // oemDashcam.fps (30) → recording.fps chain. Seeding a single value
        // here would drop OEM parked fps from its 30 default to 15. Absence =
        // byte-identical per pipeline; presence = an explicit user choice.
        // ---- Parked-idle low-power throttle (default OFF = byte-identical) ----
        // When ARMED surveillance sits idle (no active motion), drop the shared
        // camera HAL to surveillanceIdleFps to cut capture-rail + compose/encode
        // power, then ramp back to the surveillance fps on first-motion. Detection
        // parity is preserved by holding the AI motion cadence CONSTANT at today's
        // rate (survFps/3 Hz) via a wall-clock readback gate — independent of the
        // HAL rate — so the frame-counted native pipeline sees identical cadence.
        // Master gate defaults FALSE: absent ⇒ nothing changes vs today.
        if (!camera.has("surveillanceIdleThrottle")) camera.put("surveillanceIdleThrottle", false)
        if (!camera.has("surveillanceIdleFps"))      camera.put("surveillanceIdleFps", 5)
        if (!camera.has("probedCameraId"))    camera.put("probedCameraId", -1)
        if (!camera.has("probedSurfaceMode")) camera.put("probedSurfaceMode", -1)
        if (!camera.has("roleMappings"))      camera.put("roleMappings", JSONObject())

        // OEM Dashcam (separate forward sensor on vehicles that ship a DVR).
        // -1 = unset; 0..5 = picked AVMCamera id. The resolver in
        // resolveOemDashcamId() falls back to (panoId XOR 1) when the manual
        // override is false, so a typical Seal install (pano=1) auto-picks
        // dashcam=0 without the user touching anything.
        if (!camera.has("oemDashcamCameraId"))      camera.put("oemDashcamCameraId", -1)
        if (!camera.has("oemDashcamManualOverride")) camera.put("oemDashcamManualOverride", false)
        // Sticky probe result. -1 = unprobed, 0 = single-client only,
        // 1 = both AVMCamera ids deliver frames concurrently. Until probed,
        // the daemon defaults to single-pipeline operation (yield protocol
        // between pano and OEM dashcam).
        if (!camera.has("concurrentAvmSupported"))   camera.put("concurrentAvmSupported", -1)
        // OPT-IN gate for ConcurrentAvmProbe. The probe opens BOTH AVMCamera
        // ids (raw HAL open()+startPreview()) to test dual-client support —
        // a DESTRUCTIVE operation that truncates any in-flight recording on a
        // shared id. Its only real consumer is a minor OEM bitrate-budget
        // refinement (applyBitrateBudgetCap), and the unprobed default (-1)
        // already yields a safe sole-encoder full budget. So the probe is
        // OFF by default and only runs when the user explicitly opts in via
        // the camera-mapping dialog. Default false = never auto-probe.
        if (!camera.has("concurrentAvmProbeEnabled")) camera.put("concurrentAvmProbeEnabled", false)

        // Proximity Guard defaults
        if (!proximityGuard.has("enabled")) proximityGuard.put("enabled", false)
        if (!proximityGuard.has("triggerLevel")) proximityGuard.put("triggerLevel", "RED")
        if (!proximityGuard.has("preRecordSeconds")) proximityGuard.put("preRecordSeconds", 5)
        if (!proximityGuard.has("postRecordSeconds")) proximityGuard.put("postRecordSeconds", 10)

        // Blind Spot overlay defaults. `enabled` gates the indicator-triggered
        // native overlay; the 6 numerics are the dialed-in stitch calibration
        // for this car (rear+side panorama). See BlindSpotOverlayService.
        if (!blindspot.has("enabled")) blindspot.put("enabled", false)
        // Blind-spot global camera-fps profile, used when the camera is kept warm
        // ONLY for blind-spot (no recording mode owns it). BS renders to its own
        // SurfaceControl layer with NO encoder, so its sole quality/cost lever is
        // the shared camera HAL fps (live-settable via setCameraFps, no reopen).
        //   idleFps   — turn signal OFF: camera ticks slowly to keep the rails warm
        //               for an instant reveal while burning almost no GPU/encode.
        //   activeFps — turn signal ON: ramp up so the blind-spot view is smooth.
        // When a recording mode owns the camera, recording fps wins and these are
        // ignored (recording always gets full rate; see RecordingModeManager
        // .reconcileCameraProfile).
        if (!blindspot.has("idleFps")) blindspot.put("idleFps", 1)
        if (!blindspot.has("activeFps")) blindspot.put("activeFps", 15)
        // Display target: "head_unit" (default — 15.6" center screen, layerStack 0,
        // shipping behaviour) or "cluster" (driver gauge screen via OEM projection).
        if (!blindspot.has("target")) blindspot.put("target", "head_unit")
        if (!blindspot.has("rearFov")) blindspot.put("rearFov", 1.66)
        if (!blindspot.has("sideFov")) blindspot.put("sideFov", 1.98)
        if (!blindspot.has("yaw")) blindspot.put("yaw", 1.23)
        if (!blindspot.has("roll")) blindspot.put("roll", 0.25)
        if (!blindspot.has("pitch")) blindspot.put("pitch", -0.275)
        if (!blindspot.has("feather")) blindspot.put("feather", 0.38)
        // Additional opaque stitch-tuning scalars; defaults below = no change.
        if (!blindspot.has("projExp")) blindspot.put("projExp", 1.0)
        if (!blindspot.has("rearRoll")) blindspot.put("rearRoll", 0.0)
        if (!blindspot.has("rearPitch")) blindspot.put("rearPitch", 0.0)
        // Camera merge mode for the view: "both" (default — rear+side stitch),
        // "side" (side camera only), or "rear" (rear camera only). The single-camera
        // modes show one full-FOV feed instead of the merged panorama.
        if (!blindspot.has("mergeMode")) blindspot.put("mergeMode", "both")
        // On-screen card rotation. Either a fixed quarter turn (int 0/90/180/270) or
        // the string "auto". Applied by the SurfaceControl layer, and only honoured
        // for the single-camera merge modes (side/rear) — the merged panorama always
        // renders upright. 0 = shipping. In "auto" the daemon orients to direction of
        // travel: it holds "rotationBase" moving forward and flips 180° in reverse.
        if (!blindspot.has("rotation")) blindspot.put("rotation", 0)
        // Base quarter turn used as the forward-gear orientation when rotation="auto".
        if (!blindspot.has("rotationBase")) blindspot.put("rotationBase", 0)

        // Telemetry Overlay defaults.
        //
        // Schema:
        //   enabled            (legacy) — pano fallback when panoEnabled absent
        //   panoEnabled        explicit pano gate; if absent, falls back to enabled
        //   oemDashcamEnabled  explicit OEM Dashcam gate; default false (the OEM
        //                      front sensor doesn't need a stamp by default —
        //                      separate opt-in keeps pano clean while OEM is on)
        //
        // Resolver lives at isTelemetryOverlayEnabledFor(flow). Don't read
        // panoEnabled / oemDashcamEnabled directly from callers — the legacy
        // fallback is part of the contract.
        val telemetryOverlay = config.optJSONObject("telemetryOverlay") ?: JSONObject().also {
            config.put("telemetryOverlay", it)
        }
        if (!telemetryOverlay.has("enabled")) telemetryOverlay.put("enabled", false)

        // OEM Dashcam toggles (separate from camera.* because they're feature
        // gates, not HAL config). disableNativeDvr is sticky-applied on every
        // daemon boot if true (handles OTA / factory reset un-doing the
        // pm disable-user). bitrateBudget is the soft cap for the combined
        // pano+OEM bitrate when both pipelines run; UI uses it to size the
        // sliders. 10 Mbps matches the Adreno 610 H.264 ceiling under encoder
        // isolation.
        val oemDashcam = config.optJSONObject("oemDashcam") ?: JSONObject().also {
            config.put("oemDashcam", it)
        }
        if (!oemDashcam.has("enabled")) oemDashcam.put("enabled", false)
        // Note: legacy accOffMode key is intentionally NOT seeded here.
        // migrateOemDashcamModes nulls it on upgrades; fresh installs simply
        // don't have it. The modern schema is recordingMode + surveillanceMode.
        if (!oemDashcam.has("disableNativeDvr")) oemDashcam.put("disableNativeDvr", false)
        // Surveillance integration: when enabled, OEM dashcam pipeline records
        // dvr_*.mp4 clips in parallel with pano event_*.mp4 on every motion
        // trigger. Reuses the existing surveillance gating (SafeLocation,
        // schedule, per-camera enable) — no duplicate config.
        val oemSurv = oemDashcam.optJSONObject("surveillance") ?: JSONObject().also {
            oemDashcam.put("surveillance", it)
        }
        if (!oemSurv.has("enabled")) oemSurv.put("enabled", false)
        if (!oemDashcam.has("bitrateBudget"))    oemDashcam.put("bitrateBudget", 10_000_000)
        // Per-pipeline quality slot. Pano reads recording.recordingQuality;
        // OEM reads this. Without a separate slot, both pipelines pulled
        // from the same key and the budget-cap math double-counted pano's
        // tier (MAX→2 Mbps clamp regression). STANDARD default keeps OEM
        // clips well under the budget without user tuning.
        if (!oemDashcam.has("recordingQuality")) oemDashcam.put("recordingQuality", "STANDARD")
        if (!oemDashcam.has("codec")) oemDashcam.put("codec", "H264")
        if (!oemDashcam.has("fps")) oemDashcam.put("fps", 30)
        // ---- OEM parked-idle low-power throttle (default OFF = byte-identical) ----
        // When parked + smart surveillance + vehicle OFF, the OEM dashcam is kept
        // warm but only records event clips (triggered by the pano detector). While
        // idle we throttle the ENCODE draw-stride to ~idleFps (the camera HAL rate
        // stays >=15 since live setCameraFps is HAL-rejectable and KEY_FRAME_RATE is
        // immutable — so we can snap to a clean full-fps event clip). Master gate
        // defaults FALSE; the drive/continuous fps floor is never touched.
        if (!oemDashcam.has("idleThrottleWhenParked")) oemDashcam.put("idleThrottleWhenParked", false)
        if (!oemDashcam.has("idleFps")) oemDashcam.put("idleFps", 5)
        if (!oemDashcam.has("priorityWhenContended")) {
            // "pano" | "oemDashcam" — whichever pipeline holds the AVMCamera
            // when concurrentAvmSupported=0. Default pano because pano feeds
            // both surveillance and the existing dashcam mode.
            oemDashcam.put("priorityWhenContended", "pano")
        }
        if (!oemDashcam.has("segmentRotateOffsetMs")) {
            // Stagger OEM segment rotation so MediaMuxer.stop() bursts on the
            // two pipelines don't collide. 30s into the pano cycle.
            oemDashcam.put("segmentRotateOffsetMs", 30_000)
        }
        
        // Trip Analytics defaults
        val tripAnalytics = config.optJSONObject("tripAnalytics") ?: JSONObject().also {
            config.put("tripAnalytics", it)
        }
        if (!tripAnalytics.has("enabled")) tripAnalytics.put("enabled", false)

        // Floating status pill segment visibility. Independent of whether the
        // underlying feature (recording / trip analytics) is enabled — these
        // only gate the pill segments so users can hide either without
        // surrendering SYSTEM_ALERT_WINDOW or disabling the feature itself.
        val statusOverlay = config.optJSONObject("statusOverlay") ?: JSONObject().also {
            config.put("statusOverlay", it)
        }
        if (!statusOverlay.has("cameraVisible")) statusOverlay.put("cameraVisible", true)
        if (!statusOverlay.has("tripVisible")) statusOverlay.put("tripVisible", true)
        if (!statusOverlay.has("replayVisible")) statusOverlay.put("replayVisible", true)
        
        // BYD Cloud defaults
        val bydCloud = config.optJSONObject("bydCloud") ?: JSONObject().also {
            config.put("bydCloud", it)
        }
        if (!bydCloud.has("enabled")) bydCloud.put("enabled", false)

        // Cloudflared defaults
        val cloudflared = config.optJSONObject("cloudflared") ?: JSONObject().also {
            config.put("cloudflared", it)
        }
        if (!cloudflared.has("isPaid")) cloudflared.put("isPaid", false)
        if (!cloudflared.has("token")) cloudflared.put("token", "")

        // RoadSense Map (navMap) — routing BYOK credential section. Basemap (OpenFreeMap)
        // needs no key; only the routing provider is bring-your-own-key, stored encrypted
        // via CredentialCipher (see NavMapConfig). Default disabled until the user adds a key.
        val navMap = config.optJSONObject("navMap") ?: JSONObject().also {
            config.put("navMap", it)
        }
        if (!navMap.has("enabled")) navMap.put("enabled", false)
        // Auto-project the map onto the driver cluster on ACC-on. Off by default;
        // the daemon reads this on power-up, the Map tab toggles it.
        if (!navMap.has("autoProjectCluster")) navMap.put("autoProjectCluster", false)
        // Daemon→Activity coordination flag: ClusterMapProjector sets it true on
        // start and false on stop/abort; the launched cluster map Activity polls it
        // and self-finishes when false (the OEM projection close never destroys the
        // fission display, so onDisplayRemoved can't be relied on). Default false =
        // no map projected.
        if (!navMap.has("clusterMapActive")) navMap.put("clusterMapActive", false)

        // Projection (native ProjectionFragment — cast an app onto the driver cluster).
        // autoStartOnAcc casts autoStartPackage onto the cluster on ACC-on, read by the
        // daemon in AccMonitor.notifyAccEdge exactly like navMap.autoProjectCluster does for
        // the map. Mutually exclusive with navMap.autoProjectCluster (single cluster takeover
        // surface); each toggle clears the sibling, and AccMonitor tiebreaks map-wins. The
        // package lives here (daemon-readable, uid 2000) rather than the fragment's app-UID
        // SharedPreferences (box geometry) because the daemon needs it at power-up. Off by default.
        val projection = config.optJSONObject("projection") ?: JSONObject().also {
            config.put("projection", it)
        }
        if (!projection.has("autoStartOnAcc")) projection.put("autoStartOnAcc", false)
        if (!projection.has("autoStartPackage")) projection.put("autoStartPackage", "")

        // Vehicle appearance defaults — selected 3D model and body paint color.
        // Stored unified so AVN and remote (phone-over-tunnel) clients show the
        // same vehicle. modelId must match an entry in models/manifest.json; the
        // bundled default 'seal' is always available offline.
        val storedVehicle = config.optJSONObject("vehicle")
        val hadPersistedModel = storedVehicle?.has("modelId") == true
        val vehicle = storedVehicle ?: JSONObject().also {
            config.put("vehicle", it)
        }
        // Keep Seal as the offline 3D-renderer fallback, but do not present it
        // to camera/SOH logic as a selected physical model on fresh installs.
        // Existing configs predate provenance, so preserve their prior model
        // behavior with the legacy source.
        if (!vehicle.has("modelId")) vehicle.put("modelId", "seal")
        if (!vehicle.has("modelSource")) {
            vehicle.put(
                "modelSource",
                if (hadPersistedModel) {
                    VehicleModelSelection.SOURCE_LEGACY
                } else {
                    VehicleModelSelection.SOURCE_UNSET
                }
            )
        }
        if (!vehicle.has("color")) vehicle.put("color", "#E8E8EC")  // Aurora White

        // Geocoding (place-name tagging) defaults — opt-in, fully offline by
        // default so the upgrade is non-surprising for existing users.
        // Per-flow split (recording / surveillance) so dashcam and sentry
        // clips can be tagged independently.
        //
        // Migration: if a pre-split config has top-level enabled / allowOnline
        // / customNominatimBase / nominatimCooldownUntilMs fields, fold them
        // into the new shape. The fold is one-shot and idempotent — once the
        // sub-objects exist applyDefaults is a no-op. Without this, users who
        // had the feature enabled under the old schema would silently lose
        // the toggle on first run after upgrade.
        val geocoding = config.optJSONObject("geocoding") ?: JSONObject().also {
            config.put("geocoding", it)
        }

        // Capture legacy values BEFORE constructing nested defaults so the
        // sub-objects pick up the old settings instead of defaulting to off.
        val legacyEnabled = geocoding.has("enabled") && geocoding.optBoolean("enabled", false)
        val legacyOnline  = geocoding.has("allowOnline") && geocoding.optBoolean("allowOnline", false)
        val legacyCustomUrl = geocoding.optString("customNominatimBase", "")
        val legacyCooldown = geocoding.optLong("nominatimCooldownUntilMs", 0L)

        val recordingGeo = geocoding.optJSONObject("recording") ?: JSONObject().also {
            geocoding.put("recording", it)
        }
        if (!recordingGeo.has("enabled")) recordingGeo.put("enabled", legacyEnabled)
        if (!recordingGeo.has("allowOnline")) recordingGeo.put("allowOnline", legacyOnline)

        val surveillanceGeo = geocoding.optJSONObject("surveillance") ?: JSONObject().also {
            geocoding.put("surveillance", it)
        }
        if (!surveillanceGeo.has("enabled")) surveillanceGeo.put("enabled", legacyEnabled)
        if (!surveillanceGeo.has("allowOnline")) surveillanceGeo.put("allowOnline", legacyOnline)

        val advancedGeo = geocoding.optJSONObject("advanced") ?: JSONObject().also {
            geocoding.put("advanced", it)
        }
        if (!advancedGeo.has("customNominatimBase")) {
            advancedGeo.put("customNominatimBase", legacyCustomUrl)
        }
        if (!advancedGeo.has("nominatimCooldownUntilMs")) {
            advancedGeo.put("nominatimCooldownUntilMs", legacyCooldown)
        }

        // Strip the legacy top-level keys after migration so future writers
        // don't accidentally re-introduce stale values.
        if (geocoding.has("enabled")) geocoding.remove("enabled")
        if (geocoding.has("allowOnline")) geocoding.remove("allowOnline")
        if (geocoding.has("customNominatimBase")) geocoding.remove("customNominatimBase")
        if (geocoding.has("nominatimCooldownUntilMs")) geocoding.remove("nominatimCooldownUntilMs")
    }
    
    /**
     * Load config from file (with caching).
     */
    @JvmStatic
    fun loadConfig(): JSONObject {
        val configFile = File(CONFIG_PATH)
        
        // Check if file changed since last load
        // Cheap fast-path: serve the cache unless the file changed. Freshness is
        // now a COMPOSITE (mtime, size) key (see isCacheFresh): ext4 mtime is
        // second-granular, so a peer-UID write committing in the SAME wall-clock
        // second used to yield equal mtime and serve a one-write-stale snapshot
        // to bare-loadConfig getters (getSurveillance/getRecording/…). Pairing
        // mtime with file length catches that same-second cross-UID rewrite
        // (a real section write almost always changes the byte count) while
        // still short-circuiting genuinely-unchanged files for hot poll loops
        // (e.g. RoadSenseOverlayService). Correctness-critical reads still
        // forceReload() first, and every WRITE path re-reads via
        // loadConfigFresh() under the file lock — so no write loses data.
        // Snapshot the volatile field ONCE into a local: this fast-path runs
        // OUTSIDE synchronized(this), and a concurrent writer (forceReload /
        // loadConfigFresh) nulls cachedConfig under the monitor. A check-then-`!!`
        // on the field directly is a TOCTOU — the null-write can land between the
        // null-check and the `!!` deref, throwing NPE. The local read is atomic
        // (field is @Volatile) so `cached` cannot become null after the check.
        val cached = cachedConfig
        if (cached != null && configFile.exists()) {
            if (isCacheFresh(configFile)) {
                return cached
            }
        }
        
        return synchronized(this) {
            try {
                if (configFile.exists()) {
                    val content = configFile.readText()
                    val config = JSONObject(content)
                    // Run schema migration on every load. applyDefaults is
                    // idempotent — it only fills in absent fields and only
                    // strips legacy top-level geocoding keys that have
                    // already been folded into the nested shape. Without
                    // this call, a user with a pre-split flat geocoding
                    // schema on disk would silently lose their toggle —
                    // getGeocoding() would return false because the new
                    // nested keys wouldn't exist.
                    //
                    // Detect "needs migration" cheaply (legacy key at the
                    // top of geocoding, or missing new sections/selection
                    // provenance).
                    val migrationNeeded = run {
                        if (!config.has("cloudflared")) return@run true
                        val vehicle = config.optJSONObject("vehicle") ?: return@run true
                        if (!vehicle.has("modelSource")) return@run true
                        val geo = config.optJSONObject("geocoding") ?: return@run true
                        geo.has("enabled") || geo.has("allowOnline")
                            || geo.has("customNominatimBase")
                            || geo.has("nominatimCooldownUntilMs")
                    }
                    if (migrationNeeded) {
                        applyDefaults(config)
                        // Persist the migrated shape so subsequent loads skip
                        // the migration check. DAEMON-ONLY + cross-process
                        // locked + re-read-under-lock: the previous unconditional
                        // saveConfigInternal here ran under only synchronized(this),
                        // so a peer daemon JVM's section write landing between our
                        // L550 read and this write was silently clobbered with our
                        // stale snapshot. persistMigrationUnderLock re-reads the
                        // CURRENT on-disk bytes under the file lock and re-applies
                        // the (idempotent) migration to those, so no peer section
                        // is lost. App UID skips persisting (keeps the migrated
                        // object in memory); the daemon persists on its next write.
                        persistMigrationUnderLock()
                    }
                    // Parsed cleanly — any earlier corruption is resolved
                    // (e.g. the daemon rewrote a valid config). Re-arm saving.
                    corruptionDetected = false

                    // LOAD-TIME SEQ PROMOTION (app UID only). If a torn live
                    // file was healed by the DAEMON from its own STALE sticky
                    // .bak (the daemon can't read the app-private .bak), the
                    // live file is now valid-but-STALE and recoverFromBackup
                    // never fires. Here the app detects that its private .bak
                    // carries a HIGHER configSeq than the (stale) live config
                    // and promotes it — restoring the user's newer changes the
                    // daemon couldn't see. One-shot: the promote save bumps the
                    // live seq above the .bak, so the next load won't re-promote.
                    // readAppPrivateBackup() returns null on the daemon, so this
                    // is inert there (the daemon is the source of truth it heals
                    // from). Skipped while a write holds the file lock to avoid
                    // promoting over an in-progress update (re-entrancy flag).
                    val appBak = if (!holdingFileLock.get()) readAppPrivateBackup() else null
                    if (appBak != null && seqOf(appBak) > seqOf(config)) {
                        Log.w(TAG, "App-private .bak (seq=${seqOf(appBak)}) newer than live " +
                            "(seq=${seqOf(config)}); promoting — daemon likely healed from a stale .bak")
                        cachedConfig = config            // provisional, until promote save
                        stampFreshness(configFile.lastModified())
                        // Promote UNDER the cross-process lock and RE-CHECK the
                        // on-disk seq after acquiring it: a peer daemon may have
                        // written a genuinely newer config between our read above
                        // and the lock. Only promote if disk is STILL older than
                        // the .bak — otherwise the peer's newer write wins and we
                        // must not regress it (lost-update guard).
                        val promoted = withConfigFileLock {
                            val liveNow = try {
                                val cf = File(CONFIG_PATH)
                                if (cf.exists() && cf.length() > 0L) JSONObject(cf.readText()) else null
                            } catch (_: Exception) { null }
                            if (liveNow != null && seqOf(liveNow) >= seqOf(appBak)) {
                                false   // peer already caught up / surpassed; don't regress
                            } else {
                                saveConfig(appBak)   // bumps seq past both; mirrors backups
                            }
                        }
                        if (promoted) {
                            // saveConfig set cachedConfig/lastModified to the
                            // promoted object and bumped its seq on disk.
                            return@synchronized cachedConfig ?: appBak
                        }
                        // Not promoted (peer newer, or app-UID non-atomic write
                        // verify failed) — fall through with the live config.
                    }
                    cachedConfig = config
                    stampFreshness(configFile.lastModified())
                    // DEBUG-only: this fires on every genuine reparse (the mtime
                    // early-return above gates unchanged loads), so a 2-3Hz writer
                    // makes it the dominant logcat line. Each Log.d is a synchronous
                    // write; gate it out of release builds.
                    if (com.overdrive.app.BuildConfig.DEBUG) {
                        Log.d(TAG, "Config loaded from $CONFIG_PATH")
                    }
                    config
                } else {
                    Log.w(TAG, "Config file not found, initializing...")
                    init()
                    cachedConfig ?: createDefaultConfig()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config: ${e.message}")
                // The file exists and is non-empty but didn't parse — this is
                // corruption, not a fresh install. Most likely the non-atomic
                // fallback write in saveConfigInternal() was truncated by a
                // process kill (e.g. `pm install -r` during an upgrade).
                //
                // Recovery, in order of preference:
                //   1. Restore from the last-known-good .bak that saveConfig()
                //      mirrors after every successful write — returns the
                //      user's real settings.
                //   2. Second-chance re-read: a concurrent NON-atomic write by
                //      another process (app-UID fallback path) can leave the
                //      file momentarily unparseable. Re-read once before
                //      declaring it dead so a transient race doesn't latch.
                //   3. Genuinely unparseable AND no usable .bak. The on-disk
                //      bytes are unrecoverable, so the corruption latch would
                //      only PERMANENTLY brick saving: saveConfig() refuses
                //      while latched, but the ONLY thing that clears the latch
                //      is a clean load, which needs a clean write the latch
                //      blocks — a deadlock (the "saves don't stick, reverts to
                //      original" report). Preserve the corrupt bytes to .bad,
                //      then — IF we can atomic-write (daemon UID 2000) — reset
                //      the live file to defaults so saving works again. The app
                //      UID leaves the repair to the daemon (its writes route
                //      there anyway) to avoid a non-atomic re-truncation race,
                //      and latches meanwhile to block a defaults-clobber.
                recoverFromBackup(configFile)?.let { return@synchronized it }

                // (2) Transient-corruption second read.
                try {
                    if (configFile.exists() && configFile.length() > 0L) {
                        val retry = JSONObject(configFile.readText())
                        applyDefaults(retry)
                        cachedConfig = retry
                        stampFreshness(configFile.lastModified())
                        corruptionDetected = false
                        Log.w(TAG, "Config parsed on second read; treated as " +
                            "transient corruption (no latch)")
                        return@synchronized retry
                    }
                } catch (_: Exception) {
                    // Still unparseable — genuinely dead. Fall through to (3).
                }

                // (3) Preserve corrupt bytes for forensics / manual recovery.
                try {
                    if (configFile.exists() && configFile.length() > 0L) {
                        val badFile = File(configFile.parentFile, configFile.name + ".bad")
                        if (configFile.copyTo(badFile, overwrite = true).exists()) {
                            badFile.setReadable(true, false)
                            Log.e(TAG, "Corrupt config preserved to ${badFile.path}")
                        }
                    }
                } catch (backupErr: Exception) {
                    Log.w(TAG, "Failed to back up corrupt config: ${backupErr.message}")
                }

                val defaults = createDefaultConfig()
                if (android.os.Process.myUid() == SHELL_DAEMON_UID) {
                    // We own the sticky-dir file and can atomic-rename: repair
                    // it so the feature isn't permanently bricked. The user's
                    // settings were already unrecoverable (corrupt + no .bak),
                    // so this loses nothing recoverable — it only restores the
                    // ability to save. Clear the latch since the file is now
                    // clean. Hold the cross-process lock so the repair rename
                    // doesn't interleave with a peer daemon's write.
                    //
                    // TOCTOU guard: a PEER daemon JVM may have repaired the file
                    // (its own recovery, or a real write) in the window between
                    // our parse-failure above and acquiring the lock here. So
                    // RE-READ on-disk UNDER the lock first: if it now parses,
                    // ADOPT the peer's bytes instead of clobbering them with
                    // defaults (which would also REGRESS configSeq). Only write
                    // defaults when the file is still genuinely unparseable, and
                    // compute the seq INSIDE the lock so it can't regress below a
                    // peer write that landed first.
                    val repaired = withConfigFileLock {
                        val cf = File(CONFIG_PATH)
                        val peerGood = try {
                            if (cf.exists() && cf.length() > 0L) JSONObject(cf.readText()) else null
                        } catch (_: Exception) { null }
                        if (peerGood != null) {
                            // A peer already fixed it — adopt, don't overwrite.
                            cachedConfig = peerGood
                            stampFreshness(cf.lastModified())
                            corruptionDetected = false
                            Log.w(TAG, "Corrupt config was repaired by a peer (seq=${seqOf(peerGood)}); adopting")
                            peerGood
                        } else {
                            defaults.put(SEQ_KEY, nextConfigSeq(defaults))
                            if (saveConfigInternal(defaults)) {
                                cachedConfig = defaults
                                stampFreshness(cf.lastModified())
                                corruptionDetected = false
                                // Seed the sticky .bak from the repaired defaults
                                // so the very next write isn't operating without
                                // a recovery copy (the no-.bak window that made
                                // this branch reachable).
                                writeBackupCopy(defaults)
                                Log.e(TAG, "Unrecoverable config (corrupt, no .bak); " +
                                    "reset to defaults so saving works again. Corrupt " +
                                    "bytes preserved at .bad")
                                defaults
                            } else {
                                null
                            }
                        }
                    }
                    if (repaired != null) return@synchronized repaired
                    Log.e(TAG, "Daemon repair-write of defaults failed; latching")
                }
                // App UID (or daemon repair failed): latch to block a
                // defaults-clobber and return defaults transiently. The daemon
                // repairs the live file on its next load (the very next
                // app-forwarded write triggers a daemon forceReload), after
                // which a clean load clears the latch everywhere. Don't cache
                // the defaults — a stale mtime check could otherwise keep
                // returning them after the daemon repairs the file.
                corruptionDetected = true
                cachedConfig ?: defaults
            }
        }
    }

    /**
     * Best-effort recovery of a corrupt live config from the last-known-good
     * backups written by saveConfig(). Returns the recovered config (and
     * promotes it back to the live path + clears corruptionDetected) on
     * success, or null if there's no usable backup. Caller holds the monitor.
     *
     * SEQ-AWARE, DOUBLE-SOURCE recovery: considers BOTH the sticky .bak
     * (daemon-maintained, may be STALE across a daemon-down session) AND the
     * app-private .bak (app-maintained, current for app-side writes). It
     * restores whichever VALID candidate has the highest [SEQ_KEY], so a stale
     * sticky .bak can NEVER silently revert newer user changes that the
     * app-private copy still holds (the historical "settings reverted after
     * OTA" symptom). Ties / legacy seq=0 fall back to the sticky .bak.
     */
    private fun recoverFromBackup(configFile: File): JSONObject? {
        val bakFile = File(configFile.parentFile, configFile.name + ".bak")
        val stickyBak: JSONObject? = try {
            if (bakFile.exists() && bakFile.length() > 0L) JSONObject(bakFile.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Sticky .bak at ${bakFile.path} unusable: ${e.message}"); null
        }
        val appBak: JSONObject? = readAppPrivateBackup()

        // Pick the highest-seq valid candidate. Prefer the sticky .bak on a tie
        // (seq equal, incl. both legacy 0) so behaviour matches the historical
        // single-source path when seqs are uninformative.
        val recovered: JSONObject? = when {
            stickyBak != null && appBak != null ->
                if (seqOf(appBak) > seqOf(stickyBak)) appBak else stickyBak
            stickyBak != null -> stickyBak
            appBak != null -> appBak
            else -> null
        }
        if (recovered == null) return null
        val src = if (recovered === appBak) APP_PRIVATE_BAK_PATH else bakFile.path

        return try {
            // Promote the good bytes back to the live path so peer processes
            // stop reading corruption. Recovering from a CORRUPT live file, so
            // we deliberately overwrite with the chosen .bak content (no fresh
            // re-read — the on-disk bytes ARE the corruption we're fixing).
            // Persist for BOTH UIDs under the lock: the daemon does an atomic
            // rename; the app does the non-atomic direct write onto the existing
            // world-RW file (no worse than the corruption it replaces).
            val repaired = withConfigFileLock { saveConfigInternal(recovered) }
            cachedConfig = recovered
            corruptionDetected = false
            // Only trust the fast-path mtime gate if the DISK was actually
            // repaired; otherwise leave lastModified at 0 so the next load
            // re-reads (and re-recovers) rather than serving cache over still-
            // corrupt bytes.
            if (repaired) {
                stampFreshness(configFile.lastModified())
                Log.w(TAG, "Recovered + repaired config from $src (seq=${seqOf(recovered)}) after corruption")
            } else {
                stampFreshness(0)
                Log.w(TAG, "Recovered config in-memory from $src (seq=${seqOf(recovered)}); disk repair deferred")
            }
            recovered
        } catch (e: Exception) {
            Log.w(TAG, "Recovery from $src failed: ${e.message}")
            null
        }
    }
    
    /**
     * Save entire config to file.
     */
    @JvmStatic
    @JvmOverloads
    fun saveConfig(config: JSONObject, force: Boolean = false): Boolean {
        // Corruption guard: if the last load found a non-empty-but-unparseable
        // file on disk and we couldn't recover from .bak, `config` here was
        // built from createDefaultConfig() (loadConfig returned defaults, then
        // updateSection merged into them). Persisting it would clobber the
        // user's real — and still on-disk, still recoverable — settings with
        // factory defaults. Refuse, and let a later clean load (e.g. once the
        // daemon rewrites a valid file) clear the latch. This is the fix for
        // the v23.x→v24.1 "all settings lost after upgrade" report.
        //
        // EXCEPTION — force=true: a whole-config RESTORE (ConfigBackupService.
        // applyBundle) passes an already-validated, COMPLETE user config, not a
        // defaults-merge. The latch's rationale (don't overwrite recoverable
        // settings with defaults) does NOT apply — and blocking here defeats the
        // entire point of the restore feature, which exists precisely to recover
        // FROM config corruption (a corrupt on-disk file leaves the latch set,
        // and .bak recovery having failed is exactly when the user reaches for a
        // backup). So force writes past the latch and, on success, clears it —
        // the bytes we just wrote ARE the new valid on-disk truth.
        if (corruptionDetected && !force) {
            Log.e(TAG, "saveConfig blocked: corruption latch set; refusing to " +
                "overwrite live config with defaults until a clean load clears it")
            return false
        }
        if (corruptionDetected && force) {
            Log.w(TAG, "saveConfig(force): writing a validated whole-config restore " +
                "past the corruption latch (restore is authoritative user data, not defaults)")
        }
        config.put("lastModified", System.currentTimeMillis())
        // Bump the monotonic write sequence so recovery / load-time promotion can
        // ALWAYS identify the newest copy independent of (second-granular,
        // possibly clock-skewed) mtime. Take max(disk, mem)+1 so a write never
        // regresses the seq even if `config` was built from a stale snapshot.
        config.put(SEQ_KEY, nextConfigSeq(config))
        val success = saveConfigInternal(config)
        if (success) {
            // A forced restore just wrote a complete, valid config over a
            // (previously) corrupt file — the on-disk truth is now clean, so
            // release the latch. Without this, subsequent normal saveConfig()
            // calls in the same process would still be blocked until a reload.
            if (force && corruptionDetected) {
                corruptionDetected = false
                Log.i(TAG, "saveConfig(force): restore succeeded — corruption latch cleared")
            }
            cachedConfig = config
            // Track the file's actual mtime, NOT wall-clock — the cache
            // freshness check at loadConfig() compares fs mtime against
            // this value to detect cross-process writes. If we stored
            // System.currentTimeMillis() here, the saved mtime would
            // (almost always) be greater than the file's mtime, so the
            // fileModified <= lastModified check would never trip and
            // a cross-UID write would never invalidate the cache.
            stampFreshness(File(CONFIG_PATH).lastModified())
            // Mirror a last-known-good copy. loadConfig() restores from this
            // when the live file is found corrupt, so a truncated write (e.g.
            // process killed mid non-atomic fallback during `pm install`)
            // self-heals instead of degrading to defaults. Best-effort: a
            // failure here doesn't fail the save.
            writeBackupCopy(config)
            // SECOND last-known-good in the app's OWN private dir. The sticky
            // .bak above goes STALE across a daemon-down session (the app UID
            // can't atomic-rename in the sticky dir, so writeBackupCopy skips),
            // which used to make recoverFromBackup silently restore an OLD
            // snapshot. The app CAN atomic-rename in its private dir, so this
            // copy stays current for app-side writes. recoverFromBackup picks
            // whichever .bak has the higher configSeq, so neither source can
            // revert the other. Best-effort; only the app process can write it.
            writeAppPrivateBackup(config)
            notifyListeners("all", config)
        }
        return success
    }

    /**
     * Mirror the current good config to a sibling .bak (best-effort). Written
     * after every successful saveConfig so loadConfig() can self-heal a corrupt
     * live file. Failures are swallowed — the .bak is a safety net, never a
     * gate on the primary save succeeding.
     */
    private fun writeBackupCopy(config: JSONObject) {
        try {
            val configFile = File(CONFIG_PATH)
            val bakFile = File(configFile.parentFile, configFile.name + ".bak")
            val bakTmp = File(configFile.parentFile, configFile.name + ".bak.tmp")
            FileWriter(bakTmp).use { it.write(config.toString(2)) }
            bakTmp.setReadable(true, false)
            bakTmp.setWritable(true, false)
            if (!bakTmp.renameTo(bakFile)) {
                // tmp-create/rename can fail for the app UID on sticky
                // /data/local/tmp. Do NOT fall back to a non-atomic direct
                // write of the .bak: that would TRUNCATE the existing
                // last-known-good copy, and a pm-install kill mid-write could
                // leave BOTH primary and .bak torn — defeating the self-heal
                // this backup exists for. A stale-but-VALID .bak is strictly
                // better than a freshly-truncated one. Skip the update; the
                // daemon (UID 2000, can atomic-rename) refreshes the .bak on
                // its next write.
                try { bakTmp.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backup copy skipped: ${e.message}")
        }
    }

    /**
     * Read the monotonic write sequence from a config (0 if absent/legacy).
     * CLAMPED to non-negative: the config file is world-RW (0666), so a foreign
     * writer could inject a negative or garbage configSeq. A negative seq would
     * make an older .bak (positive seq) out-rank a newer live config in
     * recovery/promotion — the exact "revert to older snapshot" the seq exists
     * to prevent. Floor at 0 so a tampered value degrades to "legacy/unknown"
     * (a no-op tie) rather than a monotonicity break.
     */
    private fun seqOf(config: JSONObject?): Long =
        (config?.optLong(SEQ_KEY, 0L) ?: 0L).coerceAtLeast(0L)

    /**
     * Compute the next [SEQ_KEY] value: strictly greater than BOTH the value in
     * the object being written AND the value currently on disk, so a write built
     * from a stale in-memory snapshot can never regress the sequence (which would
     * let a later load/recover wrongly prefer the older copy). Read under the
     * caller's lock (saveConfig runs inside updateSection's withConfigFileLock,
     * and ConfigBackupService's runUnderConfigLock).
     *
     * SATURATING add: if a foreign-injected seq is at Long.MAX_VALUE, `+1` would
     * wrap to Long.MIN_VALUE (negative) and break ordering. Saturate at MAX_VALUE
     * instead — the sequence then stops advancing (a benign monotonic plateau)
     * rather than wrapping negative. seqOf already floors inputs at 0.
     */
    private fun nextConfigSeq(config: JSONObject): Long {
        val inMem = seqOf(config)
        val onDisk = try {
            val cf = File(CONFIG_PATH)
            if (cf.exists() && cf.length() > 0L) seqOf(JSONObject(cf.readText())) else 0L
        } catch (_: Exception) { 0L }
        val base = maxOf(inMem, onDisk)
        return if (base >= Long.MAX_VALUE - 1L) Long.MAX_VALUE else base + 1L
    }

    /**
     * Mirror the good config to an APP-PRIVATE .bak the app process can ALWAYS
     * refresh atomically (its own data dir, where it can create a sibling and
     * rename — unlike sticky /data/local/tmp). This is the copy that stays
     * CURRENT across a daemon-down OTA window; recoverFromBackup picks the
     * higher-seq source between this and the sticky .bak.
     *
     * Daemon-skip: the daemon (UID 2000) cannot write into the app's 0700 dir
     * and doesn't need to — it keeps the sticky .bak current. App-UID only.
     * Best-effort; a failure never fails the save.
     */
    private fun writeAppPrivateBackup(config: JSONObject) {
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return
        try {
            val dir = File(APP_PRIVATE_DIR)
            if (!dir.exists()) dir.mkdirs()
            val bak = File(APP_PRIVATE_BAK_PATH)
            val tmp = File(APP_PRIVATE_BAK_PATH + ".tmp")
            FileWriter(tmp).use { it.write(config.toString(2)) }
            if (!tmp.renameTo(bak)) {
                // App-private dir: rename should succeed (same fs, app-owned).
                // If it somehow fails, drop the tmp rather than truncate the bak.
                try { tmp.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.d(TAG, "App-private backup skipped: ${e.message}")
        }
    }

    /**
     * Read the app-private .bak if present and parseable, else null. Used as a
     * second recovery source (typically NEWER than the sticky .bak across a
     * daemon-down session). App-UID only — the daemon can't read the 0700 dir.
     */
    private fun readAppPrivateBackup(): JSONObject? {
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return null
        return try {
            val bak = File(APP_PRIVATE_BAK_PATH)
            if (!bak.exists() || bak.length() == 0L) null
            else JSONObject(bak.readText())
        } catch (_: Exception) { null }
    }
    
    private fun saveConfigInternal(config: JSONObject): Boolean {
        val configFile = File(CONFIG_PATH)
        configFile.parentFile?.mkdirs()
        val payload = config.toString(2)

        // Atomic write: write to a sibling .tmp file, then rename. The rename
        // is a single inode swap on the filesystem, so power loss either
        // leaves the old file intact or fully promotes the new one — never
        // a half-written corrupt config that would wipe user settings.
        //
        // The catch matters: when the app UID (10xxx) writes here, it
        // can't always create new files in /data/local/tmp/ (the dir is
        // typically owned by shell:shell with the sticky bit). The tmp
        // create throws FileNotFoundException/EACCES. Without this catch,
        // every app-side write would fail, the cache would never be
        // updated, and `loadConfig` would re-enter `init()` →
        // `migrateFromLegacy()` on every subsequent call — producing the
        // ANR storm in the Connect-and-Test flow.
        // Per-PROCESS-unique tmp name (.tmp.<pid>): the rename onto CONFIG_PATH
        // is atomic, but the sibling tmp itself must not be shared — two
        // writers (e.g. two daemon JVMs both running migrateFromLegacy on first
        // boot) using a fixed "${name}.tmp" could interleave write/rename on the
        // SAME tmp inode and corrupt each other. A pid suffix gives each writer
        // its own staging file; the locked write paths already serialize, this
        // just hardens any residual unlocked caller.
        val tmpFile = File(configFile.parentFile, configFile.name + ".tmp." + android.os.Process.myPid())
        try {
            FileWriter(tmpFile).use { it.write(payload) }
            tmpFile.setReadable(true, false)
            tmpFile.setWritable(true, false)
            if (tmpFile.renameTo(configFile)) {
                Log.i(TAG, "Config saved to $CONFIG_PATH (atomic)")
                return true
            }
            Log.w(TAG, "Atomic rename failed; falling back to direct write")
        } catch (e: Exception) {
            Log.w(TAG, "Tmp-write path unavailable (${e.message}); falling back to direct write")
        }

        // Fallback: write directly to the existing world-RW file. The
        // daemon (UID 2000) creates it on first boot with 0666, so the
        // app UID can open it for writing even though it can't create
        // new files in /data/local/tmp/. We lose atomicity here — a
        // crash mid-write corrupts the file — but for the cross-UID
        // case it's the only path that works, and corruption is
        // recoverable on next boot via the legacy-migration fallback.
        return try {
            if (!configFile.exists()) {
                Log.e(TAG, "Config file missing and tmp-create denied; cannot save")
                false
            } else {
                FileWriter(configFile).use { it.write(payload) }
                configFile.setReadable(true, false)
                configFile.setWritable(true, false)
                try { tmpFile.delete() } catch (_: Exception) {}
                // The direct write is NON-atomic — verify it landed intact
                // before reporting success. If a concurrent reader or a partial
                // flush left unparseable bytes, return false so the caller
                // doesn't treat a torn write as durable (the .bak + corruption
                // guard then recover on next load). Re-reading a few KB is cheap
                // and only happens on the rare app-UID daemon-down fallback.
                val verified = try {
                    JSONObject(configFile.readText()); true
                } catch (ve: Exception) {
                    Log.e(TAG, "Direct write produced unparseable config: ${ve.message}")
                    false
                }
                if (verified) Log.i(TAG, "Config saved to $CONFIG_PATH (direct)")
                verified
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config: ${e.message}")
            false
        }
    }
    
    // ==================== SECTION GETTERS ====================
    
    /**
     * Get surveillance config section.
     */
    @JvmStatic
    fun getSurveillance(): JSONObject {
        return loadConfig().optJSONObject("surveillance") ?: JSONObject()
    }
    
    /**
     * Get the surveillance schedule from config.
     * Returns a SurveillanceSchedule loaded from the surveillance section.
     */
    @JvmStatic
    fun getSurveillanceSchedule(): com.overdrive.app.surveillance.SurveillanceSchedule {
        val schedule = com.overdrive.app.surveillance.SurveillanceSchedule()
        schedule.loadFromJson(getSurveillance())
        return schedule
    }
    
    /**
     * Get recording config section.
     */
    @JvmStatic
    fun getRecording(): JSONObject {
        return loadConfig().optJSONObject("recording") ?: JSONObject()
    }
    
    /**
     * Get streaming config section.
     */
    @JvmStatic
    fun getStreaming(): JSONObject {
        return loadConfig().optJSONObject("streaming") ?: JSONObject()
    }

    /** Blind Spot overlay config section: {enabled, rearFov, sideFov, yaw, roll,
     *  pitch, feather}. mtime-gated loadConfig; callers needing cross-UID
     *  freshness (app reading a daemon/web write) should forceReload() first. */
    @JvmStatic
    fun getBlindSpot(): JSONObject {
        return loadConfig().optJSONObject("blindspot") ?: JSONObject()
    }

    /** True when the blind-spot feature is enabled. */
    @JvmStatic
    fun isBlindSpotEnabled(): Boolean = getBlindSpot().optBoolean("enabled", false)

    /** Global camera fps while the camera is kept warm ONLY for blind-spot AND
     *  the blind-spot view is HIDDEN (turn signal off). BS renders to a
     *  SurfaceControl layer with no encoder, so the shared camera HAL fps is its
     *  only cost/quality lever; keeping it low here burns almost no GPU/encode
     *  while keeping the rails warm for an instant reveal. Default 1 fps. */
    @JvmStatic
    fun getBlindSpotIdleFps(): Int =
        getBlindSpot().optInt("idleFps", 1).coerceIn(1, 15)

    /** Global camera fps while the camera is kept warm ONLY for blind-spot AND
     *  the blind-spot view is SHOWN (turn signal on). Ramps the shared camera up
     *  so the blind-spot view is smooth. Default 15 fps. */
    @JvmStatic
    fun getBlindSpotActiveFps(): Int =
        getBlindSpot().optInt("activeFps", 15).coerceIn(1, 30)

    /** Master gate for the parked-idle surveillance camera throttle. Default
     *  FALSE ⇒ every deployed unit is byte-identical until explicit opt-in. */
    @JvmStatic
    fun isSurveillanceIdleThrottle(): Boolean =
        loadConfig().optJSONObject("camera")?.optBoolean("surveillanceIdleThrottle", false) ?: false

    /** Shared camera HAL fps while ARMED surveillance sits idle (no active
     *  motion). Ramps back to the surveillance fps on first-motion. Default 5. */
    @JvmStatic
    fun getSurveillanceIdleFps(): Int =
        loadConfig().optJSONObject("camera")?.optInt("surveillanceIdleFps", 5)?.coerceIn(1, 15) ?: 5

    /** Master gate for the OEM dashcam parked-idle encode throttle. Default FALSE. */
    @JvmStatic
    fun isOemIdleThrottleWhenParked(): Boolean =
        getOemDashcam().optBoolean("idleThrottleWhenParked", false)

    /** OEM dashcam effective encode fps while parked-idle keep-warm. The camera
     *  HAL rate is unchanged (>=15); only the encoder draw-stride is throttled.
     *  Default 5. */
    @JvmStatic
    fun getOemIdleFps(): Int =
        getOemDashcam().optInt("idleFps", 5).coerceIn(1, 15)

    /** Blind-spot display target: "head_unit" (default) or "cluster". */
    @JvmStatic
    fun getBlindSpotTarget(): String =
        getBlindSpot().optString("target", "head_unit")

    @JvmStatic
    fun isBlindSpotCluster(): Boolean = "cluster" == getBlindSpotTarget()

    /** Persist the blind-spot display target. Single-key merge — never clobbers
     *  the per-target geometry/geometryCluster siblings. */
    @JvmStatic
    fun setBlindSpotTarget(target: String): Boolean =
        updateValues("blindspot", mapOf(
            "target" to (if (target == "cluster") "cluster" else "head_unit")))

    // ── Camera-view (on-demand native camera view, shares the BS lane) ──────────
    /** The camview section {enabled, mode, target, geometry, geometryCluster, autoHideSec}. */
    @JvmStatic
    fun getCamView(): JSONObject {
        return loadConfig().optJSONObject("camview") ?: JSONObject()
    }

    /** True when an on-demand camera view is currently requested/shown. */
    @JvmStatic
    fun isCamViewEnabled(): Boolean = getCamView().optBoolean("enabled", false)

    /** Requested camera view mode: 0=all-4 mosaic, 1=front, 2=right, 3=rear, 4=left.
     *  Clamped to that range; anything else falls back to 0 (mosaic). */
    @JvmStatic
    fun getCamViewMode(): Int = getCamView().optInt("mode", 0).let { if (it in 0..4) it else 0 }

    /** Camera-view display target: "head_unit" (default) or "cluster". */
    @JvmStatic
    fun getCamViewTarget(): String = getCamView().optString("target", "head_unit")

    @JvmStatic
    fun isCamViewCluster(): Boolean = "cluster" == getCamViewTarget()

    /** Auto-hide timeout in seconds (0 = stay until explicitly hidden). */
    @JvmStatic
    fun getCamViewAutoHideSec(): Int = getCamView().optInt("autoHideSec", 0).coerceIn(0, 600)

    /** Persist camview keys with a shallow single-key merge (never clobbers the
     *  per-target geometry/geometryCluster siblings). */
    @JvmStatic
    fun setCamViewValues(values: Map<String, Any>): Boolean = updateValues("camview", values)

    /** Physical-key mapping section: {enabled, allowAdvanced, bindings:[
     *  {keycode:int, pressType:"single|double|long", action:{kind, variables}}]}.
     *  Manual replay is stored per binding as
     *  {kind:"manualClip", beforeSeconds:0..60, afterSeconds:0..60}; the two
     *  values must total 1..60 seconds.
     *  Consumed by KeepAliveAccessibilityService.onKeyEvent → KeyMapDispatcher
     *  (app UID) which fires the mapped action through the daemon; callers needing
     *  cross-UID freshness (the a11y service in the app process reading a
     *  web/daemon write) should forceReload() first. mtime-gated loadConfig
     *  otherwise. */
    @JvmStatic
    fun getKeymap(): JSONObject {
        return loadConfig().optJSONObject("keymap") ?: JSONObject()
    }

    /** Master switch — key interception is a no-op (all keys pass through)
     *  unless this is true. Defaults false so a fresh install never swallows
     *  hardware buttons until the user opts in. */
    @JvmStatic
    fun isKeymapEnabled(): Boolean = getKeymap().optBoolean("enabled", false)

    /** Gate for the advanced escape hatch (raw-CAN / shell bindings). Curated
     *  actions run regardless; advanced payloads are refused at dispatch unless
     *  this is true. Defaults false. */
    @JvmStatic
    fun isKeymapAdvancedAllowed(): Boolean = getKeymap().optBoolean("allowAdvanced", false)

    /** Single-vs-double disambiguation window in ms (see KeyMapDispatcher). A hardware
     *  double-tap on a steering-wheel/dash button is slower than a touchscreen one, and
     *  varies by user and button feel, so this is user-tunable. Clamped to a sane
     *  250..1500ms band: below 250 a real double can't land in time; above 1500 a single
     *  tap on a key that also has a double mapped would feel unbearably laggy. Defaults
     *  450ms (a comfortable hardware double-tap measured from the first press). */
    @JvmStatic
    fun getKeymapDoubleTapWindowMs(): Long =
        getKeymap().optLong("doubleTapWindowMs", 450L).coerceIn(250L, 1500L)

    /** The automation config section: {allowShell}. Distinct from the automations
     *  LIST (which Automations.java persists in its own file) — this holds
     *  automation-wide settings toggles. */
    @JvmStatic
    fun getAutomation(): JSONObject = loadConfig().optJSONObject("automation") ?: JSONObject()

    /** Gate for the automation ShellAction — DISTINCT from [isKeymapAdvancedAllowed].
     *  Automations fire autonomously on vehicle events (not a deliberate button
     *  press), so arming shell there is a separate, stricter opt-in: enabling the
     *  key-mapping advanced hatch must NOT silently also allow unattended shell in
     *  automations. Lives in the automation section (its toggle is on the
     *  Automations page). Re-checked at fire time in ShellAction.trigger; callers
     *  in the daemon should forceReload() for cross-UID freshness. Defaults false. */
    @JvmStatic
    fun isAutomationShellAllowed(): Boolean = getAutomation().optBoolean("allowShell", false)

    /** Persist the automation-shell gate. Single-key merge on the automation
     *  section (updateValues), off the main looper. */
    @JvmStatic
    fun setAutomationShellAllowed(allow: Boolean): Boolean =
        updateValues("automation", mapOf("allowShell" to allow))

    /** Whether the user has explicitly turned WiFi OFF via an automation / key-mapping
     *  radio action. The WiFi keep-alive watchdog (AccSentryDaemon.ensureWifiEnabled,
     *  SentryDaemon.enableWifi, ServiceLauncher.ensureWifiEnabled) checks this BEFORE
     *  running `svc wifi enable`, so a deliberate "WiFi off" rule is not immediately
     *  auto-re-enabled. Defaults FALSE — with no such rule the keep-alive behaves
     *  normally (keeps the head unit connected for streaming / cloud). Fail-open on any
     *  read error (returns false → keep-alive resumes) so a transient config glitch can
     *  never strand WiFi off. Lives in a dedicated "radio" section. */
    @JvmStatic
    fun isWifiKeepAliveSuppressed(): Boolean =
        try { (loadConfig().optJSONObject("radio")?.optBoolean("wifiUserOff", false)) ?: false }
        catch (t: Throwable) { false }

    /** Persist the WiFi-off suppression flag. Set true when a radio action turns WiFi
     *  off (so keep-alive stands down), cleared to false when WiFi is turned back on.
     *  Single-key merge on the "radio" section, off the main looper (updateValues
     *  routes an app-process write to the daemon). */
    @JvmStatic
    fun setWifiKeepAliveSuppressed(suppressed: Boolean): Boolean =
        updateValues("radio", mapOf("wifiUserOff" to suppressed))

    /** The dataUsage config section: {enabled}. Tracks per-day WiFi/mobile bytes
     *  consumed by Overdrive (app UID + UID-2000 daemons/tunnels) for the
     *  performance page's Data graph. */
    @JvmStatic
    fun getDataUsage(): JSONObject = loadConfig().optJSONObject("dataUsage") ?: JSONObject()

    /** Master switch for data-usage tracking. Defaults FALSE (opt-in): the
     *  sampler thread is only armed on the enable edge, so a disabled feature
     *  reads no /proc/net stats, writes no H2 rows, and schedules no wakeups —
     *  true zero-overhead-when-off, mirroring the keymap FileObserver contract.
     *  Daemon callers should forceReload() first for cross-UID freshness (the
     *  web toggle is written by the app UID, read by the UID-2000 sampler). */
    @JvmStatic
    fun isDataUsageEnabled(): Boolean = getDataUsage().optBoolean("enabled", false)

    /** Persist the data-usage master switch. Single-key merge on the dataUsage
     *  section (updateValues routes an app-process write to the daemon), off the
     *  main looper. */
    @JvmStatic
    fun setDataUsageEnabled(enabled: Boolean): Boolean =
        updateValues("dataUsage", mapOf("enabled" to enabled))

    /** The list of key bindings. Empty array when unset. Never null. */
    @JvmStatic
    fun getKeymapBindings(): org.json.JSONArray =
        getKeymap().optJSONArray("bindings") ?: org.json.JSONArray()

    /** Replace the whole keymap section (enabled/allowAdvanced/bindings). Callers
     *  MUST invoke this off the main looper — updateSection round-trips to the
     *  daemon over localhost. */
    @JvmStatic
    fun setKeymap(keymap: JSONObject): Boolean = updateSection("keymap", keymap)

    /**
     * Recording-side dewarp strength (Fitzgibbon division model).
     *
     * Range: 0..100. 0 = off (passthrough — shader stays bit-exact to the
     * legacy mosaic). 100 = maximum rectification.
     *
     * Single shared key used by BOTH the recording (ACC-on dashcam) and
     * surveillance (ACC-off) flows. The UI exposes a slider in each section
     * but they both read/write this key — flipping it in one place applies
     * to the other automatically.
     *
     * Defaults to 0 if absent. Clamped on read so a corrupt config value
     * cannot push the shader out of its safe range.
     */
    @JvmStatic
    fun getRectifyStrength(): Int {
        val raw = getRecording().optInt("rectifyStrength", 0)
        return raw.coerceIn(0, 100)
    }

    /** Set the shared recording/surveillance dewarp strength (0..100). */
    @JvmStatic
    fun setRectifyStrength(strength: Int): Boolean {
        return updateValues("recording", mapOf(
            "rectifyStrength" to strength.coerceIn(0, 100)
        ))
    }

    /**
     * Shared clip segment length in minutes for BOTH the ACC-on dashcam and
     * ACC-off / OEM surveillance flows. The UI exposes the control in each
     * section but both read/write this single key, so changing it in one
     * place applies to the other automatically (mirrors rectifyStrength).
     *
     * Clamped to [MIN_SEGMENT_DURATION_MINUTES..MAX_SEGMENT_DURATION_MINUTES]
     * on read so a corrupt config value can never disable segment rotation.
     */
    @JvmStatic
    fun getSegmentDurationMinutes(): Int {
        val raw = getRecording().optInt("segmentDurationMinutes",
            com.overdrive.app.util.Constants.SEGMENT_DURATION_MINUTES)
        return raw.coerceIn(
            com.overdrive.app.util.Constants.MIN_SEGMENT_DURATION_MINUTES,
            com.overdrive.app.util.Constants.MAX_SEGMENT_DURATION_MINUTES)
    }

    /** Set the shared clip segment length in minutes (clamped to MIN..MAX). */
    @JvmStatic
    fun setSegmentDurationMinutes(minutes: Int): Boolean {
        return updateValues("recording", mapOf(
            "segmentDurationMinutes" to minutes.coerceIn(
                com.overdrive.app.util.Constants.MIN_SEGMENT_DURATION_MINUTES,
                com.overdrive.app.util.Constants.MAX_SEGMENT_DURATION_MINUTES)
        ))
    }
    
    /**
     * Get telegram config section.
     */
    @JvmStatic
    fun getTelegram(): JSONObject {
        return loadConfig().optJSONObject("telegram") ?: JSONObject()
    }
    
    /**
     * Get proximity guard config section.
     */
    @JvmStatic
    fun getProximityGuard(): JSONObject {
        return loadConfig().optJSONObject("proximityGuard") ?: JSONObject()
    }
    
    /**
     * Get telemetry overlay config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getTelemetryOverlay(): JSONObject {
        return loadConfig().optJSONObject("telemetryOverlay") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    // ==================== SECTION SETTERS ====================
    
    /**
     * Update surveillance config section.
     */
    @JvmStatic
    fun setSurveillance(surveillance: JSONObject): Boolean {
        return updateSection("surveillance", surveillance)
    }
    
    /**
     * Update recording config section.
     */
    @JvmStatic
    fun setRecording(recording: JSONObject): Boolean {
        return updateSection("recording", recording)
    }
    
    /**
     * Update streaming config section.
     */
    @JvmStatic
    fun setStreaming(streaming: JSONObject): Boolean {
        return updateSection("streaming", streaming)
    }
    
    /**
     * Update telegram config section.
     */
    @JvmStatic
    fun setTelegram(telegram: JSONObject): Boolean {
        return updateSection("telegram", telegram)
    }
    
    /**
     * Update proximity guard config section.
     */
    @JvmStatic
    fun setProximityGuard(proximityGuard: JSONObject): Boolean {
        return updateSection("proximityGuard", proximityGuard)
    }
    
    /**
     * Update telemetry overlay config section.
     */
    @JvmStatic
    fun setTelemetryOverlay(telemetryOverlay: JSONObject): Boolean {
        return updateSection("telemetryOverlay", telemetryOverlay)
    }

    /**
     * Resolve whether the telemetry overlay should burn-in for a given
     * recording flow ("pano" or "oemDashcam"). The legacy `enabled` key acts
     * as the pano fallback so pre-split installs keep their toggle. OEM
     * dashcam defaults to off — separate opt-in.
     */
    @JvmStatic
    fun isTelemetryOverlayEnabledFor(flow: String): Boolean {
        val tel = getTelemetryOverlay()
        return when (flow) {
            "oemDashcam" -> tel.optBoolean("oemDashcamEnabled", false)
            else /* "pano" */ -> {
                if (tel.has("panoEnabled")) tel.optBoolean("panoEnabled", false)
                else tel.optBoolean("enabled", false)
            }
        }
    }

    // ==================== OEM DASHCAM ====================

    /**
     * Get oemDashcam feature-gate section (separate from camera.* which is HAL
     * config). Read fields: disableNativeDvr, bitrateBudget,
     * priorityWhenContended, segmentRotateOffsetMs.
     */
    @JvmStatic
    fun getOemDashcam(): JSONObject {
        return loadConfig().optJSONObject("oemDashcam") ?: JSONObject().apply {
            put("disableNativeDvr", false)
            put("bitrateBudget", 10_000_000)
            put("priorityWhenContended", "pano")
            put("segmentRotateOffsetMs", 30_000)
            // Two independent modes — one per page:
            //   recordingMode      governs OEM behaviour during pano DASHCAM
            //                      recording (cam_*.mp4 flow). Off | Continuous
            //                      | Smart. Smart = follow whatever the pano
            //                      Recording Mode is doing (Continuous /
            //                      Drive Mode / Proximity Guard).
            //   surveillanceMode   governs OEM behaviour during pano
            //                      SURVEILLANCE (event_*.mp4 flow). Off |
            //                      Continuous | Smart. Smart = follow pano
            //                      surveillance motion detection.
            put("recordingMode", "off")
            put("surveillanceMode", "off")
        }
    }

    @JvmStatic
    fun setOemDashcam(oemDashcam: JSONObject): Boolean {
        return updateSection("oemDashcam", oemDashcam)
    }

    /**
     * True iff either OEM Dashcam mode is non-Off. Pipeline lifecycle is
     * OR-gated. Streaming alone does NOT activate recording — that's gated
     * separately by the daemon-side stream router, mirroring how pano
     * keeps its recording and stream encoders independent.
     */
    @JvmStatic
    fun isAnyOemDashcamTriggerEnabled(): Boolean {
        val oem = getOemDashcam()
        // Once either new key is present, it is the authoritative answer.
        // Legacy `enabled` / `surveillance.enabled` are read-only mirrors
        // post-migration and must NEVER override an explicit Off pick.
        if (oem.has("recordingMode") || oem.has("surveillanceMode")) {
            val rec = oem.optString("recordingMode", "off").lowercase()
            val surv = oem.optString("surveillanceMode", "off").lowercase()
            return rec != "off" || surv != "off"
        }
        // Pre-migration legacy schemas only.
        if (oem.optBoolean("enabled", false)) return true
        val legacySurv = oem.optJSONObject("surveillance")
        if (legacySurv != null && legacySurv.optBoolean("enabled", false)) return true
        return false
    }

    @JvmStatic
    fun getOemRecordingMode(): String {
        val oem = getOemDashcam()
        if (oem.has("recordingMode")) return oem.optString("recordingMode", "off").lowercase()
        // Legacy: enabled=true → smart (matched pano mode). enabled=false → off.
        return if (oem.optBoolean("enabled", false)) "smart" else "off"
    }

    @JvmStatic
    fun getOemSurveillanceMode(): String {
        val oem = getOemDashcam()
        if (oem.has("surveillanceMode")) return oem.optString("surveillanceMode", "off").lowercase()
        // Legacy: surveillance.enabled=true → smart (follow pano motion).
        val legacy = oem.optJSONObject("surveillance")
        return if (legacy != null && legacy.optBoolean("enabled", false)) "smart" else "off"
    }

    /**
     * Migrate legacy {@code enabled / surveillance.enabled / accOffMode} →
     * {@code recordingMode / surveillanceMode}. Idempotent — once one of the
     * new keys is present we treat the migration as done.
     */
    @JvmStatic
    fun migrateOemDashcamModes(): Boolean {
        val root = loadConfig()
        val oem = root.optJSONObject("oemDashcam") ?: return false
        if (oem.has("recordingMode") || oem.has("surveillanceMode")) return false
        val delta = JSONObject()
        val legacyEnabled = oem.optBoolean("enabled", false)
        val legacyMode = oem.optString("accOffMode", "off")
        val legacySurv = oem.optJSONObject("surveillance")
        val survOn = legacySurv?.optBoolean("enabled", false) == true
        // Pre-migration semantics: enabled=true recorded during the ACC ON
        // (driving) phase regardless of accOffMode; accOffMode then governed
        // what happened at ACC OFF (off=tear down, smart=event-trigger,
        // continuous=keep recording). Surveillance-side independently captures
        // the parked-window intent from accOffMode + surveillance.enabled.
        //
        // FIX (dvr_ clips with OEM recording "disabled"): map recording-side to
        // "smart" — NOT "continuous". Two reasons: (1) "continuous" silently
        // records dvr_*.mp4 the ENTIRE ACC-ON drive, which surprised users who
        // only ever had the OEM feed enabled for VIEWING; "smart" mirrors the
        // pano dashcam (records only while the user's main recording is active),
        // the least-surprising faithful mapping. (2) It now AGREES with
        // getOemRecordingMode()'s own pre-migration fallback (legacy enabled=true
        // -> "smart", :1060). The old "continuous" mapping made the persisted
        // migrated value disagree with the on-the-fly getter for the same
        // legacy config — a latent split-brain. On a vehicle whose OEM camera id
        // is unset the recording-mode picker is hidden entirely (recording.js
        // gates it on oemDashcamCameraId>=0), so a "continuous" migration was
        // also UN-resettable from the UI: the user saw no toggle yet got dvr_
        // for every drive. "smart" + the camera-id guard in the resolver close
        // that hole.
        if (legacyEnabled) {
            delta.put("recordingMode", "smart")
            // accOffMode=continuous → user wanted parked-window recording too.
            // accOffMode=smart      → user wanted parked-window event clips.
            // accOffMode=off/unset  → user wanted clean ACC OFF teardown,
            //                          unless surveillance.enabled overrides.
            val survFromAccOff = when (legacyMode) {
                "continuous" -> "continuous"
                "smart" -> "smart"
                else -> if (survOn) "smart" else "off"
            }
            delta.put("surveillanceMode", survFromAccOff)
        } else {
            delta.put("recordingMode", "off")
            delta.put("surveillanceMode", if (survOn) "smart" else "off")
        }
        // R8-A #19: copy pano's codec/quality/fps into the oemDashcam
        // section so applyRecordingConfigFromUcm doesn't keep falling
        // back to pano's keys. The fallback was the pre-migration design
        // but now contradicts the doc comment that says OEM "deliberately
        // does NOT read pano's recording.* keys". Migrate once at upgrade
        // time; subsequent picks land directly into oemDashcam via
        // QualitySettingsApiHandler.
        val rec = root.optJSONObject("recording")
        if (rec != null) {
            if (!oem.has("codec") && rec.has("codec")) {
                delta.put("codec", rec.optString("codec"))
            }
            if (!oem.has("recordingQuality") && rec.has("recordingQuality")) {
                delta.put("recordingQuality", rec.optString("recordingQuality"))
            }
            if (!oem.has("fps") && rec.has("fps")) {
                delta.put("fps", rec.optInt("fps"))
            }
        }
        // Null out legacy keys so isAnyOemDashcamTriggerEnabled (and any
        // other reader that hasn't been switched to the new accessors)
        // can't resurrect a stale enabled=true from the pre-migration
        // schema. The nested surveillance.enabled stays as a one-way
        // mirror of surveillanceMode for readers that still consult it
        // (SurveillanceEngineGpu) — but we explicitly write its new
        // value so it can't disagree.
        delta.put("enabled", JSONObject.NULL)
        delta.put("accOffMode", JSONObject.NULL)
        // Mirror the resolved surveillanceMode (post-migration) into the
        // legacy nested boolean so readers that still consult it (e.g.
        // SurveillanceEngineGpu) can't disagree with the new accessor.
        val resolvedSurvMode = delta.optString("surveillanceMode", "off")
        // Clone existing surveillance object (if any) and only mutate `enabled`,
        // matching the POST handler's per-key merge semantics. Without the clone,
        // any future sub-key under `oem.surveillance` would be silently dropped
        // during migration.
        val existingSurv = oem.optJSONObject("surveillance")
        val sOut = if (existingSurv != null) JSONObject(existingSurv.toString()) else JSONObject()
        sOut.put("enabled", "off" != resolvedSurvMode)
        delta.put("surveillance", sOut)
        return updateSection("oemDashcam", delta)
    }

    /**
     * Resolve the effective OEM Dashcam AVMCamera id.
     *
     * Symmetric to pano's default: pano defaults to id=1 (see
     * {@code PanoramicCameraGpu.PHYSICAL_CAMERA_ID = 1}), so OEM defaults
     * to id=0. The two stay opposite by construction. Three rules in order:
     *
     *  1. **Manual override is honored verbatim.** When the user picked a
     *     specific id in the camera-mapping dialog (
     *     {@code oemDashcamManualOverride=true}), this returns that id —
     *     including -1 if they chose "Off". Manual is the authoritative answer.
     *  2. **Auto-infer from pano** when {@code oemDashcamManualOverride=false}:
     *     - pano=0 → OEM=1 (Tang-style, e.g. user manually set pano to 0)
     *     - pano=1 → OEM=0 (Seal/Han, the typical case)
     *  3. **Default to 0** otherwise (pano unprobed, or pano is some other
     *     id we don't have an XOR rule for). Symmetric to pano's id=1
     *     default — out-of-the-box install on Seal/Han works without
     *     forcing the user through the camera-mapping dialog. Tang users
     *     who manually set pano to 0 get OEM=1 via rule 2; if they ALSO
     *     don't set pano manually (pano stays at default 1, which is
     *     wrong for Tang anyway), the user has to fix the pano side first
     *     — no different from the pre-OEM situation.
     *
     * Safety net: even if this returns 0 on a vehicle where id=0 happens to
     * be the pano sensor, {@code OemDashcamPipeline.validateHalDimsOrReject}
     * checks BmmCameraInfo's declared dims and refuses to open anything with
     * an aspect ≥ 2.0 (panoramic strip). The applyLifecycle catch then rolls
     * UCM back to {@code enabled=false} with a {@code lastStartError} the UI
     * surfaces.
     *
     * Caller must ensure the camera section is fresh (forceReload from app UID).
     */
    @JvmStatic
    fun resolveOemDashcamId(): Int {
        val camera = loadConfig().optJSONObject("camera") ?: return 0
        if (camera.optBoolean("oemDashcamManualOverride", false)) {
            return camera.optInt("oemDashcamCameraId", -1)
        }
        val panoId = if (camera.optBoolean("manualOverride", false)) {
            camera.optInt("manualCameraId", -1)
        } else {
            camera.optInt("probedCameraId", -1)
        }
        return when (panoId) {
            0 -> 1                  // Tang-style: pano=0 → OEM=1
            1 -> 0                  // Seal/Han: pano=1 → OEM=0
            else -> 0               // Default symmetric to pano's id=1 default
        }
    }
    
    /**
     * Get trip analytics config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getTripAnalytics(): JSONObject {
        return loadConfig().optJSONObject("tripAnalytics") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    /**
     * Update trip analytics config section.
     */
    @JvmStatic
    fun setTripAnalytics(tripAnalytics: JSONObject): Boolean {
        return updateSection("tripAnalytics", tripAnalytics)
    }

    /**
     * Resolve the active update channel ("alpha" | "braveheart").
     *
     * Source of truth is the runtime-selectable "updates" section. When the
     * section (or its channel field) is absent — every install that pre-dates
     * this setting, including all current users mid-migration — we fall back
     * to [com.overdrive.app.BuildConfig.UPDATE_CHANNEL]. That build-time seed
     * is "alpha", so an un-toggled install resolves to the archive channel,
     * matching the historical single-tag behavior with ZERO config write.
     *
     * Callers that may run in the daemon process (UID 2000) while the web
     * toggle was written by the app (UID 10xxx) — or vice-versa — MUST
     * [forceReload] before reading, since the in-memory config is per-UID.
     * AppUpdater.resolveChannel() does exactly that.
     */
    @JvmStatic
    fun getUpdateChannel(): String {
        val ch = loadConfig().optJSONObject("updates")?.optString("channel", "")
        return if (!ch.isNullOrEmpty()) ch else com.overdrive.app.BuildConfig.UPDATE_CHANNEL
    }

    /**
     * Persist the user-selected update channel.
     *
     * Full-JSON rewrite via [updateSection] (which auto-routes app-UID writes
     * to the daemon over IPC for atomicity) — callers MUST invoke this OFF the
     * main looper (it can block on the IPC round-trip).
     */
    @JvmStatic
    fun setUpdateChannel(channel: String): Boolean {
        return updateSection("updates", JSONObject().put("channel", channel))
    }

    /**
     * Get status-overlay (floating pill) visibility section.
     * Each segment defaults to visible=true so installs that pre-date this
     * setting see no behavior change.
     */
    @JvmStatic
    fun getStatusOverlay(): JSONObject {
        return loadConfig().optJSONObject("statusOverlay") ?: JSONObject().apply {
            put("cameraVisible", true)
            put("tripVisible", true)
        }
    }

    /**
     * Update status-overlay (floating pill) visibility section.
     */
    @JvmStatic
    fun setStatusOverlay(statusOverlay: JSONObject): Boolean {
        return updateSection("statusOverlay", statusOverlay)
    }
    
    /**
     * Get BYD Cloud config section.
     * Defaults to enabled=false if section doesn't exist.
     */
    @JvmStatic
    fun getBydCloud(): JSONObject {
        return loadConfig().optJSONObject("bydCloud") ?: JSONObject().apply {
            put("enabled", false)
        }
    }
    
    /**
     * Update BYD Cloud config section.
     */
    @JvmStatic
    fun setBydCloud(bydCloud: JSONObject): Boolean {
        return updateSection("bydCloud", bydCloud)
    }

    /**
     * Get Cloudflared config section.
     */
    @JvmStatic
    fun getCloudflared(): JSONObject {
        return loadConfig().optJSONObject("cloudflared") ?: JSONObject().apply {
            put("isPaid", false)
            put("token", "")
        }
    }

    /**
     * Update Cloudflared config section.
     */
    @JvmStatic
    fun setCloudflared(cloudflared: JSONObject): Boolean {
        return updateSection("cloudflared", cloudflared)
    }

    /**
     * Get vehicle appearance config section (selected 3D model + body color +
     * drive-side layout). `driveSide` is "lhd" or "rhd" and decides which
     * physical front door each BYD HAL door-area code maps to in
     * notifications. Default "rhd" because the field-tested L↔R swap in
     * DoorEventNotifier was calibrated against RHD Sealion/Atto/Seal trims.
     */
    @JvmStatic
    fun getVehicle(): JSONObject {
        val stored = loadConfig().optJSONObject("vehicle")
        if (stored != null) {
            // Backfill driveSide on configs written before this field existed
            // so call sites can read it unconditionally without a default.
            if (!stored.has("driveSide")) stored.put("driveSide", "rhd")
            if (!stored.has("modelSource")) {
                stored.put(
                    "modelSource",
                    VehicleModelSelection.normalizeSource(
                        null, stored.optString("modelId", "")
                    )
                )
            }
            return stored
        }
        return JSONObject().apply {
            put("modelId", "seal")
            put("modelSource", VehicleModelSelection.SOURCE_UNSET)
            put("color", "#E8E8EC")
            put("driveSide", "rhd")
        }
    }

    /**
     * Physical vehicle model selected by the user or a future verified
     * detector. Returns null when modelId is only the renderer fallback.
     */
    @JvmStatic
    fun getSelectedVehicleModelId(): String? {
        val vehicle = getVehicle()
        return VehicleModelSelection.resolvedModelId(
            vehicle.optString("modelId", ""),
            vehicle.optString("modelSource", "")
        )
    }

    /**
     * Update vehicle appearance config section.
     */
    @JvmStatic
    fun setVehicle(vehicle: JSONObject): Boolean {
        return updateSection("vehicle", vehicle)
    }

    /**
     * Web-shell appearance preference (theme picker shipped in the WebView
     * pages). Stored separately from the Android-shell theme so a
     * Telegram-bot user accessing the tunnel can pick their own preference
     * without touching the Android side. Default: "dark".
     *
     * Schema:
     *   { "theme": "dark" | "light" | "auto",
     *     "locale": "en" | "de" | … | "auto" }
     *
     * `locale` is stored here (not in LocaleManager) so the web-side
     * language picker doesn't cross-pollinate the Android app's locale.
     * Survives tunnel-URL changes (each new zrok session is a fresh
     * origin, so localStorage alone is not enough). Default: "auto"
     * (the runtime falls back to navigator.language).
     */
    @JvmStatic
    fun getAppearance(): JSONObject {
        return loadConfig().optJSONObject("appearance") ?: JSONObject().apply {
            put("theme", "dark")
            put("locale", "auto")
        }
    }

    @JvmStatic
    fun setAppearance(appearance: JSONObject): Boolean {
        return updateSection("appearance", appearance)
    }

    /**
     * Geocoding (place-name tagging) section. Per-flow split so dashcam
     * and surveillance recordings can be tagged independently.
     *
     * Schema:
     *   recording: { enabled (bool, default false),
     *                allowOnline (bool, default false) }
     *   surveillance: { enabled (bool, default false),
     *                   allowOnline (bool, default false) }
     *   advanced: { customNominatimBase (string, default ""),
     *               nominatimCooldownUntilMs (long, default 0) }
     *
     * Why per-flow: a user driving a road-trip wants dashcam clips tagged
     * with the cities they passed through, but the same user parking at
     * home overnight may NOT want sentry clips tagged with their home
     * address (especially if shared via Telegram pushes). Per-flow toggles
     * give that distinction; a shared "advanced" sub-section keeps
     * power-user knobs (custom URL + cooldown state) singletons.
     *
     * The resolver picks {@code recording.allowOnline} for normal/proximity
     * recordings and {@code surveillance.allowOnline} for sentry events.
     * Cache + rate limiter are process-shared (one rate budget, one cache).
     */
    @JvmStatic
    fun getGeocoding(): JSONObject {
        return loadConfig().optJSONObject("geocoding") ?: JSONObject().apply {
            put("recording", JSONObject().apply {
                put("enabled", false)
                put("allowOnline", false)
            })
            put("surveillance", JSONObject().apply {
                put("enabled", false)
                put("allowOnline", false)
            })
            put("advanced", JSONObject().apply {
                put("customNominatimBase", "")
                put("nominatimCooldownUntilMs", 0L)
            })
        }
    }

    @JvmStatic
    fun setGeocoding(geocoding: JSONObject): Boolean {
        return updateSection("geocoding", geocoding)
    }

    /**
     * Convenience: query whether a given flow ("recording" or "surveillance")
     * is enabled. Falls back to {@code false} on any read failure so the
     * recorder's hot path is fail-closed.
     */
    @JvmStatic
    fun isGeocodingEnabledForFlow(flow: String): Boolean {
        try {
            val geo = loadConfig().optJSONObject("geocoding") ?: return false
            val sec = geo.optJSONObject(flow) ?: return false
            return sec.optBoolean("enabled", false)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * Companion to [isGeocodingEnabledForFlow] for the online tier gate.
     */
    @JvmStatic
    fun isGeocodingOnlineAllowedForFlow(flow: String): Boolean {
        try {
            val geo = loadConfig().optJSONObject("geocoding") ?: return false
            val sec = geo.optJSONObject(flow) ?: return false
            return sec.optBoolean("allowOnline", false)
        } catch (t: Throwable) {
            return false
        }
    }

    /**
     * Native-shell preferences. Today this carries `locale` for the Android
     * UI's language picker — kept separate from `appearance.locale` (which
     * is the WebView-only locale) so a tunnel-side picker doesn't change
     * the in-car native shell's language and vice versa.
     *
     * Schema: { "locale": "<bcp47>" | "auto" }
     *
     * The legacy file at /data/local/tmp/.overdrive/locale was unreliable
     * because the app UID can't `mkdir` under /data/local/tmp/, so writes
     * from the picker silently failed and the language reverted on next
     * cold start.
     */
    @JvmStatic
    fun getNativeShell(): JSONObject {
        return loadConfig().optJSONObject("nativeShell") ?: JSONObject()
    }

    @JvmStatic
    fun setNativeShell(nativeShell: JSONObject): Boolean {
        return updateSection("nativeShell", nativeShell)
    }


    /**
     * If we're NOT the shell daemon, forward this write to the daemon over the
     * localhost IPC socket so it lands via the daemon's atomic tmp+rename path
     * instead of the app process's truncation-prone in-place fallback.
     *
     * Returns:
     *   - true/false  — the daemon applied (or rejected) the write; this is the
     *                   authoritative result and the caller should NOT also
     *                   write locally. On true we forceReload() so the next
     *                   app-side read re-parses the daemon-written bytes.
     *   - null        — routing did not apply (we ARE the daemon, or the daemon
     *                   is unreachable). The caller falls through to the local
     *                   write (guarded by corruptionDetected + .bak self-heal).
     *
     * The socket I/O runs OUTSIDE the updateSection/updateValues monitor (the
     * caller invokes this before entering synchronized(this)) so a slow or
     * stalled localhost round-trip never blocks app-process readers, all of
     * which funnel through loadConfig()'s synchronized(this).
     *
     * `command` is "UPDATE_SECTION" or "UPDATE_VALUES"; `payloadKey` is "data"
     * or "values" respectively. Callers MUST already be off the UI looper
     * (updateSection/updateValues are documented off-looper-only), so the
     * blocking socket call inherits that contract.
     */
    private fun routeWriteIfApp(
        command: String,
        section: String,
        payloadKey: String,
        payload: JSONObject
    ): Boolean? {
        // Shell daemon (UID 2000) writes locally — it can atomic-rename.
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return null

        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(
                java.net.InetSocketAddress(InetAddress.getByName(DAEMON_IPC_HOST), DAEMON_IPC_PORT),
                IPC_CONNECT_TIMEOUT_MS
            )
            socket.soTimeout = IPC_READ_TIMEOUT_MS
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val req = JSONObject()
                .put("command", command)
                .put("section", section)
                .put(payloadKey, payload)
            writer.println(req.toString())
            val line = reader.readLine()
                ?: run {
                    // We SENT the request (connect + write succeeded) but the
                    // daemon died / the socket dropped before we read the ack.
                    // The daemon may well have APPLIED the write atomically. Do
                    // NOT blindly fall through to a redundant local non-atomic
                    // re-write (that re-truncates the file to write bytes that
                    // are likely already durable — reopening the tear window for
                    // a write that already succeeded). Instead re-read from disk
                    // and check whether our delta already landed; if so, report
                    // success and skip the local write. Only fall back (null)
                    // when the delta is genuinely absent.
                    return if (deltaPresentOnDisk(section, payload)) {
                        Log.w(TAG, "IPC $command got no ack but delta is on disk; treating as applied")
                        // Same as the ok-branch: the daemon applied it, so keep
                        // the app-private .bak current with the on-disk bytes.
                        writeAppPrivateBackup(forceReload())
                        true
                    } else {
                        Log.w(TAG, "IPC $command got no response and delta absent; falling back to local write")
                        null
                    }
                }
            val ok = JSONObject(line).optBoolean("success", false)
            if (ok) {
                // The daemon rewrote the file atomically in its own process.
                // Drop our stale cache so the next read re-parses its bytes
                // (honors the cross-UID forceReload-before-read invariant).
                val fresh = forceReload()
                // Keep the APP-PRIVATE .bak current with this daemon-applied
                // write. CRITICAL: this is the COMMON path (daemon up), and it
                // used to leave the app-private copy frozen at the last
                // daemon-DOWN write / migration seed — so the "double-source"
                // recovery was single-source in practice. Refresh it from the
                // bytes the daemon just wrote (which carry the daemon-bumped
                // configSeq) so the app-private .bak tracks live+sticky and can
                // genuinely back-stop a torn/stale sticky .bak during an OTA.
                // writeAppPrivateBackup is app-UID-only (daemon-skip) and atomic
                // in the app's own dir; best-effort, never fails the write.
                writeAppPrivateBackup(fresh)
            } else {
                Log.w(TAG, "IPC $command for '$section' returned success=false")
            }
            ok
        } catch (e: Exception) {
            // Daemon unreachable (down during the pm-install update window,
            // mid-restart, or not yet started). Fall through to the local
            // write. Logged at WARN so a chronically-down daemon — which
            // reopens the truncation window — is observable, not silent.
            Log.w(TAG, "IPC $command unreachable (${e.message}); using local write fallback")
            null
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Best-effort check: are ALL keys of [payload] already present with the
     * expected values inside [section] of the on-disk config? Used by
     * [routeWriteIfApp] when the daemon ack was lost — if the daemon already
     * applied the write atomically we can skip the redundant (truncation-prone)
     * local re-write. Reads fresh from disk (the daemon write, if any, just
     * landed). Conservative: any mismatch / read failure returns false so the
     * caller falls back to the local write rather than dropping a real change.
     */
    private fun deltaPresentOnDisk(section: String, payload: JSONObject): Boolean {
        return try {
            val cf = File(CONFIG_PATH)
            if (!cf.exists() || cf.length() == 0L) return false
            val onDisk = JSONObject(cf.readText())
            val sec = onDisk.optJSONObject(section) ?: return false
            val keys = payload.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (!sec.has(k)) return false
                // Compare by string form — JSONObject lacks deep value equals,
                // and the wire payload round-trips through JSON anyway.
                if (sec.get(k).toString() != payload.get(k).toString()) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Update a specific section of the config.
     */
    @JvmStatic
    fun updateSection(section: String, data: JSONObject): Boolean {
        // App-process writes reroute to the daemon for atomic durability; a
        // non-null result is authoritative (no local write). See routeWriteIfApp.
        routeWriteIfApp("UPDATE_SECTION", section, "data", data)?.let { return it }
        // Cross-process lock + fresh re-read so a peer daemon JVM's just-
        // committed section isn't dropped by a stale-snapshot merge.
        // INVARIANT (audit Jun 2026): config-change listeners run INSIDE this OS
        // file lock (here + saveConfig's notifyListeners("all")), so they MUST be
        // non-blocking — a listener that does synchronous I/O would stall every
        // peer daemon's config write (they block acquiring the FileLock). Current
        // listeners comply (RoadSenseController hops to a short thread;
        // GpuSurveillancePipeline.rectifyConfigListener is a fast in-memory
        // setter). Keep new listeners non-blocking or dispatch them off-thread.
        return withConfigFileLock {
            val config = loadConfigFresh()
            // Merge into existing section to preserve keys not present in data
            // (e.g. surveillanceEnabled is set separately from detection params)
            val existing = config.optJSONObject(section) ?: JSONObject()
            val keys = data.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                existing.put(key, data.get(key))
            }
            config.put(section, existing)
            val success = saveConfig(config)
            if (success) {
                notifyListeners(section, existing)
            }
            success
        }
    }

    /**
     * Update individual values within a section.
     */
    @JvmStatic
    fun updateValues(section: String, values: Map<String, Any>): Boolean {
        // Reroute to the daemon when not shell UID. Serialize the values map
        // into a JSONObject for the wire; the daemon applies it via updateValues.
        val valuesJson = JSONObject()
        values.forEach { (key, value) -> valuesJson.put(key, value) }
        routeWriteIfApp("UPDATE_VALUES", section, "values", valuesJson)?.let { return it }
        return withConfigFileLock {
            val config = loadConfigFresh()
            val sectionObj = config.optJSONObject(section) ?: JSONObject()

            values.forEach { (key, value) ->
                sectionObj.put(key, value)
            }

            config.put(section, sectionObj)
            val success = saveConfig(config)
            if (success) {
                notifyListeners(section, sectionObj)
            }
            success
        }
    }
    
    // ==================== CONVENIENCE METHODS ====================
    
    /**
     * Get a specific surveillance value.
     */
    @JvmStatic
    fun getSurveillanceValue(key: String, default: Any): Any {
        return getSurveillance().opt(key) ?: default
    }
    
    /**
     * Get a specific recording value.
     */
    @JvmStatic
    fun getRecordingValue(key: String, default: Any): Any {
        return getRecording().opt(key) ?: default
    }
    
    /**
     * Get a specific proximity guard value.
     */
    @JvmStatic
    fun getProximityGuardValue(key: String, default: Any): Any {
        return getProximityGuard().opt(key) ?: default
    }
    
    /**
     * Check if surveillance is enabled in config (user preference for ACC OFF auto-start).
     */
    @JvmStatic
    fun isSurveillanceEnabled(): Boolean {
        return getSurveillance().optBoolean("surveillanceEnabled", false)
    }
    
    /**
     * Set surveillance enabled state in config.
     */
    @JvmStatic
    fun setSurveillanceEnabled(enabled: Boolean): Boolean {
        return updateValues("surveillance", mapOf("surveillanceEnabled" to enabled))
    }

    /**
     * Surveillance arm mode: "lock" (arm on door-lock, disarm on unlock, with a
     * 60s fallback when lock state is unreadable) or "power" (arm immediately on
     * ACC-off, disarm on ACC-on). Any unrecognized value falls back to "lock".
     * Read by CameraDaemon's ACC-off dispatch and door-lock gate.
     */
    @JvmStatic
    fun getSurveillanceArmMode(): String {
        val mode = getSurveillance().optString("armMode", "lock")
        return if (mode == "power") "power" else "lock"
    }

    /**
     * True iff the user chose the "onOnly" operating mode — i.e. ALL behaviour that
     * runs after the vehicle is turned OFF must be disabled (keep-awake, post-OFF
     * surveillance, revival alarms) so the head unit can sleep. Read by every post-OFF
     * choke point across the daemons and the app process.
     *
     * Reads the SAME mtime-gated [loadConfig] cache that the other surveillance
     * accessors (isSurveillanceEnabled / getSurveillanceArmMode) and the daemon-side
     * isKeepUsbPowerOnAccOff() use, so a Settings toggle applies on the next config
     * re-read / ACC cycle without a daemon restart.
     *
     * FAIL-OPEN to false (full "onAndOff" behaviour) on any error, absence, or a
     * non-string value — a transient read glitch must NEVER silently disable
     * keep-awake or surveillance for an on-and-off user.
     */
    @JvmStatic
    fun isVehicleOnOnlyMode(): Boolean =
        try { getSurveillance().optString("operatingMode", "onAndOff") == "onOnly" }
        catch (t: Throwable) { false }

    // ==================== LISTENERS ====================
    
    @JvmStatic
    fun addListener(listener: ConfigChangeListener) {
        listeners.add(listener)
    }
    
    @JvmStatic
    fun removeListener(listener: ConfigChangeListener) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(section: String, config: JSONObject) {
        listeners.forEach { listener ->
            try {
                listener.onConfigChanged(section, config)
            } catch (e: Exception) {
                Log.e(TAG, "Listener error: ${e.message}")
            }
        }
    }
    
    // ==================== UTILITY ====================
    
    private fun createDefaultConfig(): JSONObject {
        val config = JSONObject()
        config.put("surveillance", JSONObject())
        config.put("recording", JSONObject())
        config.put("streaming", JSONObject())
        config.put("telegram", JSONObject())
        config.put("camera", JSONObject())
        config.put("proximityGuard", JSONObject())
        config.put("telemetryOverlay", JSONObject())
        config.put("tripAnalytics", JSONObject())
        config.put("oemDashcam", JSONObject())
        config.put("bydCloud", JSONObject())
        config.put("geocoding", JSONObject())
        config.put("version", 1)
        config.put("lastModified", System.currentTimeMillis())
        applyDefaults(config)
        return config
    }
    
    /**
     * Force reload from disk (bypasses cache).
     */
    @JvmStatic
    fun forceReload(): JSONObject {
        synchronized(this) {
            cachedConfig = null
            stampFreshness(0)
            return loadConfig()
        }
    }

    // Sibling advisory-lock file for CROSS-PROCESS serialization of the config
    // read-modify-write. synchronized(this) only excludes threads within ONE
    // JVM; multiple UID-2000 daemon JVMs (CameraDaemon, AccSentryDaemon, the
    // cluster projector, etc.) each run their own UnifiedConfigManager and
    // would otherwise interleave loadConfig→merge→atomic-rename, dropping a
    // peer's just-committed section (stale-snapshot lost update). An OS flock
    // held across the whole critical section makes those writers mutually
    // exclude. World-RW so any daemon UID can acquire it.
    private const val LOCK_PATH = "$CONFIG_PATH.lock"

    /**
     * Choose the file to take the OS advisory lock on. Preference order:
     *  1. The dedicated .lock file when it EXISTS — it has a STABLE inode (never
     *     renamed), so every process locking it mutually excludes. This is the
     *     normal path once any daemon has run init()/provisionLockFile.
     *  2. Daemon UID with .lock absent → still target .lock (RandomAccessFile
     *     creates it; the daemon owns the sticky dir).
     *  3. App UID with .lock absent (can't create siblings in sticky dir) →
     *     fall back to locking the EXISTING world-RW config file. This yields a
     *     REAL cross-process lock instead of the old silent monitor-only
     *     degradation. CONFIG_PATH's inode CAN be swapped by a daemon's atomic
     *     rename, which would break exclusion — but a missing .lock implies no
     *     daemon has initialized (it provisions .lock first), so there is no
     *     concurrent daemon rename to race in this window. If even CONFIG_PATH
     *     is absent, return .lock so RandomAccessFile throws and we degrade to
     *     monitor-only exactly as before (no regression).
     */
    private fun lockTargetFor(): File {
        val lockFile = File(LOCK_PATH)
        if (lockFile.exists()) return lockFile
        if (android.os.Process.myUid() == SHELL_DAEMON_UID) return lockFile
        val cf = File(CONFIG_PATH)
        return if (cf.exists()) cf else lockFile
    }

    /**
     * Run [body] while holding BOTH the in-JVM monitor AND an exclusive OS
     * advisory lock on [LOCK_PATH], so the enclosed read-modify-write is
     * atomic across processes. Best-effort: if the lock file can't be created
     * or locked (e.g. the app UID can't create it in the sticky dir), we fall
     * back to monitor-only — no worse than before this fix, and the daemon
     * (which owns the file and does the real writes) can always lock.
     */
    // RE-ENTRANCY guard: java.nio FileLock is per-JVM for the whole file, so a
    // second channel.lock() from the same JVM while one is held throws
    // OverlappingFileLockException. loadConfig()'s internal self-heal persists
    // can run while updateSection already holds the lock (updateSection →
    // loadConfigFresh → loadConfig → migrate → saveConfigInternal), so nesting
    // is real. This flag makes withConfigFileLock re-entrant per thread: the
    // outermost holder owns the OS lock; inner calls just run body().
    private val holdingFileLock = ThreadLocal.withInitial { false }

    private fun <T> withConfigFileLock(body: () -> T): T {
        synchronized(this) {
            if (holdingFileLock.get()) {
                // Already held by this thread higher in the stack — the OS lock
                // is in force; just run.
                return body()
            }
            var raf: RandomAccessFile? = null
            var channel: FileChannel? = null
            var lock: FileLock? = null
            try {
                // Prefer the dedicated .lock file. But during the daemon-down
                // OTA window the app UID can't CREATE it in sticky
                // /data/local/tmp (and the killed daemon may not have
                // re-provisioned it yet), so RandomAccessFile(lf,"rw") on a
                // missing .lock throws → we'd silently degrade to monitor-only
                // (no cross-process exclusion) exactly when a respawning daemon
                // might also be writing → lost update / tear. Fall back to
                // locking the EXISTING world-RW config file itself: the app can
                // always OPEN it (0666) even though it can't create siblings, so
                // it gets a REAL cross-process lock against the daemon (whose
                // writes acquire the .lock — see note below) without needing to
                // create anything. Both writers must agree on the lock target
                // for exclusion to hold; see lockTargetFor().
                val lf = lockTargetFor()
                raf = RandomAccessFile(lf, "rw")
                channel = raf.channel
                // Blocking exclusive lock — contention is rare (a few daemons,
                // sub-ms writes) and a brief block is preferable to a dropped
                // write. lock() releases on channel/raf close in finally.
                lock = channel.lock()
                try { lf.setReadable(true, false); lf.setWritable(true, false) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "Config file lock unavailable (${e.message}); monitor-only write")
            }
            holdingFileLock.set(true)
            try {
                return body()
            } finally {
                holdingFileLock.set(false)
                try { lock?.release() } catch (_: Exception) {}
                try { channel?.close() } catch (_: Exception) {}
                try { raf?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Persist a config mutated by loadConfig()'s self-heal/migration paths,
     * cross-process-safely. Gated to the daemon (UID 2000): only it can
     * reliably atomic-rename, and routing all real persists through the daemon
     * keeps a single locked writer. A non-daemon (app UID) caller skips the
     * write and keeps the migrated object in memory — the daemon re-persists it
     * on its next locked write, so nothing is lost on disk. Re-entrant: if the
     * caller already holds the file lock (the updateSection path) this nests
     * harmlessly.
     */
    private fun persistSelfHeal(config: JSONObject) {
        if (android.os.Process.myUid() != SHELL_DAEMON_UID) return
        withConfigFileLock { saveConfigInternal(config) }
    }

    /**
     * Persist the geocoding-schema migration WITHOUT clobbering a peer's
     * concurrently-written section. Daemon-only. Acquires the cross-process
     * lock, RE-READS the current on-disk bytes (which may now carry a peer's
     * new section that wasn't in the snapshot the caller migrated), re-applies
     * the idempotent applyDefaults migration to THOSE bytes, and writes the
     * result. App UID skips (no atomic write available); the daemon migrates +
     * persists on its next load/write. Best-effort — failure just defers the
     * migration to a later load.
     */
    private fun persistMigrationUnderLock() {
        if (android.os.Process.myUid() != SHELL_DAEMON_UID) return
        withConfigFileLock {
            try {
                val cf = File(CONFIG_PATH)
                if (!cf.exists()) return@withConfigFileLock
                val fresh = JSONObject(cf.readText())
                applyDefaults(fresh)            // idempotent; folds legacy geocoding keys
                if (saveConfigInternal(fresh)) {
                    cachedConfig = fresh
                    stampFreshness(cf.lastModified())
                    Log.i(TAG, "Migrated legacy geocoding schema to nested form (locked re-read)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Locked migration persist failed: ${e.message}")
            }
        }
    }

    /**
     * Force a fresh re-parse of the on-disk config, bypassing the cache + the
     * 1-second-mtime freshness gate in loadConfig. Used by updateSection/
     * updateValues BEFORE merging so a peer process's same-second write isn't
     * missed (the mtime gate is second-granular on ext4, so two writes in one
     * second look unchanged). Caller holds the monitor (+ ideally the file lock).
     */
    private fun loadConfigFresh(): JSONObject {
        cachedConfig = null
        stampFreshness(0)
        return loadConfig()
    }
    
    /**
     * Get the config file path (for debugging).
     */
    @JvmStatic
    fun getConfigPath(): String = CONFIG_PATH
    
    /**
     * Check if config file exists.
     */
    @JvmStatic
    fun configExists(): Boolean = File(CONFIG_PATH).exists()
    
    /**
     * Get last modified timestamp.
     */
    @JvmStatic
    fun getLastModified(): Long {
        return File(CONFIG_PATH).let { if (it.exists()) it.lastModified() else 0L }
    }
}
