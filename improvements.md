# Improvements: Persistent ADB/WakeLock with Vehicle Off & Driving Telemetry Acquisition

This document details and summarizes the technical innovations, methods, and architectures developed in the post-fork branches (`sealion7`, `feature/adb-persistence-and-self-healing`, `feature/native-qcarcam-streamer`) compared to the upstream `origin/main` branch.

It focuses on two key core themes ready to be ported upstream:
1. **Persistent ADB and Headunit Keep-Awake while vehicle is OFF (Keep-Awake & Self-Healing)**
2. **Vehicle State Detection (ACC ON / OFF) and Driving Data Acquisition (Drive Mode / Gear)**

---

## 1. Persistent ADB & Headunit Keep-Awake with Vehicle Off

### Background & Issues Resolved
On BYD vehicles (specifically those with the **DiLink 5.0 / Android 11 Automotive platform on Qualcomm SA8155P**, such as the Sealion 7, Seal, and updated Atto 3):
- As soon as the vehicle transitions to **ACC OFF** or the doors are locked, the Android OS and native Power Manager put the SoC into **Deep Sleep / Suspend-to-RAM (STR)**.
- Network connections (Wi-Fi and mobile data) are suspended, the ADB TCP port (`5555`) is closed or disabled due to timeout (`adb_allowed_connection_time`), and background daemon processes (`app_process`) are frozen or killed by the OOM/Power Controller.
- The traditional `PowerManager.userActivity(long)` invocation on recent Android systems turns the physical screen on, illuminating the vehicle cabin (violating stealth Sentry mode requirements).

### Architecture & Implemented Solutions

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            PERSISTENCE PIPELINE                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  1. Stealth UserActivity Injection (Android 11 SA8155P)                    │
│     PowerManager.userActivity(uptime, 0, NO_CHANGE_LIGHTS)                  │
│                                                                             │
│  2. Coordinated WakeLock + High-Performance WifiLock Management             │
│     PowerManager.PARTIAL_WAKE_LOCK + WifiManager.WIFI_MODE_FULL_HIGH_PERF   │
│                                                                             │
│  3. Global ADB & Developer Settings Enforcement (UID 2000 / Shell)          │
│     adb_enabled=1, adb_wifi_enabled=1, adb_allowed_connection_time=0        │
│     stay_on_while_plugged_in=7, development_settings_enabled=1              │
│                                                                             │
│  4. Resilient Watchdog with Dynamic APK Path Resolution                     │
│     `pm path` scanning to handle dynamic `~~hash/` package directories      │
│     PID validation to prevent duplicate instances or crash loops            │
│                                                                             │
│  5. Loopback Recovery & Companion Daemon Auto-Healing (60s Health Check)   │
│     Preemptive port 5555 recovery in BootReceiver & AdbShellExecutor        │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### A. Stealth `userActivity` Injection on Android 11+
In `AccSentryDaemon.java`, a multi-level reflection technique was implemented to keep the CPU awake without waking the physical display panel:
- **3-argument method (Android 11 / SA8155P)**:
  ```java
  // Signature: userActivity(long when, int event, int flags)
  // event = 0 (USER_ACTIVITY_EVENT_OTHER)
  // flags = 1 (USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS)
  pmUserActivity3ArgMethod.invoke(pm, SystemClock.uptimeMillis(), 0, 1);
  ```
  This resets the Application Processor (AP) sleep timer while keeping the screen completely dark.
- **2-argument fallback (DiLink 4)**: `userActivity(uptime, noChangeLights = true)`.
- **Legacy 1-argument fallback**: Executed only if the screen is already interactive, preventing unwanted screen wake-ups while parked.

#### B. Coordinated `WakeLock` and `WifiLock (WIFI_MODE_FULL_HIGH_PERF)` Management
To enable uninterrupted 24/7 operation of Cloudflare Tunnel, Telegram Bot Daemon, and the Web Dashboard while the vehicle is parked and off:
- In `AccSentryDaemon.java` and `DaemonKeepaliveService.kt`, a dedicated `WifiLock` is acquired upon entering sentry or parked mode:
  ```java
  WifiManager wm = (WifiManager) permissiveContext.getSystemService(Context.WIFI_SERVICE);
  wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AccSentry:Wifi");
  wifiLock.setReferenceCounted(false);
  wifiLock.acquire();
  ```
- This prevents the Wi-Fi subsystem from entering power-save mode or dropping the interface when the vehicle enters sleep.

#### C. Persistent Global ADB Settings Enforcement (UID 2000 / Shell)
In `AdbShellExecutor.kt`, `BootReceiver.kt`, and the 60-second periodic timer in `AccSentryDaemon.java`:
```kotlin
fun enforceGlobalAdbSettings(context: Context) {
    val cr = context.contentResolver
    Settings.Global.putInt(cr, "adb_enabled", 1)
    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
    // 0 = No automatic Wi-Fi ADB disconnect timeout
    Settings.Global.putInt(cr, "adb_allowed_connection_time", 0)
    Settings.Global.putInt(cr, "development_settings_enabled", 1)
    // 7 = AC (1) | USB (2) | WIRELESS (4) -> keep awake under power
    Settings.Global.putInt(cr, "stay_on_while_plugged_in", 7)
}
```
- If ADB port `5555` is closed or not listening during `AdbShellExecutor` loopback connection attempts, instant auto-healing is triggered by reapplying `Settings.Global` parameters.

#### D. Watchdog Scripts with Dynamic APK Resolution & PID Tracking
For standalone daemons (`start_telegram.sh`, `start_cam_daemon.sh` generated by `DaemonLauncher.kt`):
- **Dynamic directory issue on Android 11**: In Android 11 Automotive, package paths change across updates (e.g. `/data/app/~~randomHash/com.overdrive.app-.../base.apk`). Hardcoded paths or static `System.getProperty("java.class.path")` reads caused `app_process` startup failures.
- **Dynamic Resolution via Package Manager**:
  ```bash
  APK_PATH=$(pm path com.overdrive.app 2>/dev/null | grep '/base.apk$' | head -n 1 | sed 's/^package://')
  if [ -z "$APK_PATH" ] && [ -f "$FALLBACK_APK_PATH" ]; then APK_PATH="$FALLBACK_APK_PATH"; fi
  CLASSPATH=$APK_PATH app_process /system/bin --nice-name=telegram_bot_daemon com.overdrive.app.daemon.TelegramBotDaemon
  ```
- **Orphan / Duplicate Instance Prevention (PID Tracking)**:
  The watchdog script stores its PID in `/data/local/tmp/telegram_watchdog.pid` and validates it with `kill -0 $OLD_WPID` before spawning a new instance.
- **Periodic Auto-Healing (60s)** in `AccSentryDaemon.enforceAdbAndDaemonHealth()`:
  The sentry daemon uses `isProcessRunning(...)` to check the health of companion processes (`CameraDaemon`, `TelegramBotDaemon`) and automatically restarts scripts if they terminate unexpectedly.

---

## 2. Vehicle State Detection & Driving Data Acquisition

### Background & Issues Resolved
On BYD DiLink 5.0 / Sealion 7 vehicles:
1. **ACC ON / OFF Detection**: The legacy `android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice.getPowerLevel()` interface returns dummy sentinel values (`FAKE_OK = 4`, `INVALID = 255`) or throws security exceptions. This caused the application to misidentify the vehicle as permanently ON or permanently OFF.
2. **Gear / Shift Mode Listener Crashes**: Registering a listener on `android.hardware.bydauto.gearbox.BYDAutoGearboxDevice` triggers a fatal crash in `learningEPB()` due to permission mismatches between UID 2000 (Shell) and system UID, creating continuous restart loops.
3. **Inverted / Distorted Signal Mappings**:
   - Door Lock Status (`doorLockStatus`): In the BYD framework, index `1` vs `2` has proprietary semantics that caused inverted lock/unlock state reporting.
   - Charging Status: Detecting the charge port flap generated false-positive active charging states when the flap was open but no charging cable was connected.

---

### Vehicle State Detection Methods (ACC ON / OFF)

In `AccMonitor.java`, `AccSentryDaemon.java`, and `BydDataCollector.java`, a deterministic multi-source probe pipeline was introduced:

```
                                    ┌───────────────────────────────┐
                                    │    Vehicle State Probe (ACC)  │
                                    └───────────────┬───────────────┘
                                                    │
              ┌─────────────────────────────────────┴─────────────────────────────────────┐
              ▼                                     ▼                                     ▼
┌─────────────────────────┐           ┌─────────────────────────┐           ┌─────────────────────────┐
│   1. Dumpsys CarService │           │  2. Display & PowerMgr  │           │  3. Telemetry & CAN Bus │
├─────────────────────────┤           ├─────────────────────────┤           ├─────────────────────────┤
│ PowerMode Evaluation:   │           │ PowerManager:           │           │ If Gear != P (D/R/N/S)  │
│ - Standby (4)           │──► ACC OFF│ .isInteractive()==false ──► ACC OFF │ OR Speed >= 3 km/h      │──► ACC ON
│ - Sleep (8)             │           │                         │           │ (Vehicle in Motion)     │
│ - Str / Suspending (5,9)│           │ Door Lock:              │           └─────────────────────────┘
│ - Pre StartUp (1)       │           │ doorLockStatus[0] == 1  │──► ACC OFF
│ - Tod (12)              │           │ (Vehicle Locked)        │
│ - Off (0)               │           └─────────────────────────┘
├─────────────────────────┤
│ - StartUp (2)           │──► ACC ON
│ - Display on (10)       │
│ - Degraded (3)          │
└─────────────────────────┘
```

1. **`dumpsys car_service` Query for DiLink 5.0 PowerModes**:
   - **Parked / Standby States (ACC = OFF)**:
     - `4 = PowerMode Standby`
     - `8 = PowerMode Sleep`
     - `5 = PowerMode Str` (Suspend-to-RAM)
     - `9 = PowerMode Str Suspending`
     - `1 = PowerMode Pre StartUp` (Pre-ignition state before instrument cluster power-up)
     - `12 = PowerMode Tod` (Time of Day / Parked)
     - `0 = PowerMode Off`
   - **Active Vehicle States (ACC = ON)**:
     - `2 = PowerMode StartUp` (EV Ready / Drivetrain active)
     - `10 = PowerMode DisPlay on` (Instrument cluster and infotainment active for driving)
     - `3 = PowerMode Degraded`
2. **Display Interactive State**: If `PowerManager.isInteractive() == false`, the headunit is suspended -> authoritative ACC OFF.
3. **Door Lock Status (`doorLockStatus`)**: If the vehicle is locked from the outside (`doorLockStatus[0] == 1`), ACC OFF is confirmed and sentry mode starts immediately.
4. **Motion & Active Gear Override**: If `gearMode != GEAR_P` (e.g. `GEAR_D`, `GEAR_R`) or `speedKmh >= 3.0f`, the monitor forces `accOn = true`, clears sentry mode, and signals an ignition edge to guarantee dashcam recording continuity.
5. **System Property Fallback `sys.accanim.status`**: Reads the system property (`"1"` = OFF, `"0"` = ON).

---

### Driving Data Acquisition Methods (Drive Mode / Gear / Speed)

To bypass gearbox listener crashes on DiLink 5.0, `GearMonitor.java` and `TelemetryDataCollector.java` utilize a **5 Hz (200ms) multi-source polling** architecture:

#### A. Gear & ShiftMode Detection on Sealion 7 / DiLink 5.0
```java
// 1. TS Framework: CarAdapterManager -> CarBodyManager ("body")
Class<?> camCls = Class.forName("com.ts.lib.caradapter.CarAdapterManager");
Method getInst = camCls.getMethod("getInstance", Context.class);
Object cam = getInst.invoke(null, context);
Method getMgr = camCls.getMethod("getCarAdapterManager", String.class);
Object bodyMgr = getMgr.invoke(cam, "body");
Method m = bodyMgr.getClass().getMethod("getShiftMode");
int shift = ((Number) m.invoke(bodyMgr)).intValue();

// Shift Mode Mapping:
// 0, 1 -> GEAR_P (Park)
// 2    -> GEAR_R (Reverse)
// 3    -> GEAR_N (Neutral)
// 4    -> GEAR_D (Drive)
// 5    -> GEAR_M (Manual)
// 6    -> GEAR_S (Sport)
```

#### B. Fallback Chain for Maximum Vehicle Compatibility
1. **CarBodyManager (`getShiftMode`)**: Primary method for Sealion 7 and DiLink 5.0.
2. **CarAdapterManager Cabin Adapter (`getGearboxAutoModeType` / `getGear`)**: For intermediate models.
3. **TelemetryDataCollector Snapshot**: Cached reading from the 2 Hz unified telemetry snapshot.
4. **BydDeviceHelper Getter Polling (`BYDAutoGearboxDevice.getGearboxAutoModeType`)**: Direct getter invocation (without registering listeners, preventing `learningEPB` crashes).
5. **CarPropertyBridge & CarService Dumpsys**: HAL property fallback:
   - `0x21406407` (`SHIFT_MODE`)
   - `0x21403a06`
   - `0x21403a0a`

#### C. Filtering & Validation of Ancillary Telemetry
- **TPMS (Tire Pressure Monitoring) Filtering**: Out-of-bounds raw readings (> 500 kPa or `0xFF` / `255`, `4095`, `2047`) emitted during CAN bus startup are sanitized, persisting the last valid snapshot to disk (`byd_telemetry_snap.json`).
- **Physical Charging Detection**: Uses the `CHARGING_GUN_STATER` hardware ID `0x21403407` signal to confirm actual cable insertion rather than relying solely on flap status.
- **Driving Safety Guard**: Automatically blocks dangerous remote and local commands (opening trunk, doors, panoramic sunroof) if the vehicle is not in `GEAR_P` or vehicle speed is > 0 km/h.
- **Automatic Dashcam Promotion**: In `RecordingModeManager.java`, shifting out of Park (`D`/`R`) or detecting GPS speed ≥ 3 km/h immediately promotes video recording to continuous 30 FPS.

---

## Summary of Affected Files & Components

| Component / File | Domain | Description of Changes |
| :--- | :--- | :--- |
| `AccSentryDaemon.java` | Power / ADB / Sentry | 3-argument stealth `userActivity` injection (Android 11), `High Perf WifiLock`, 60s health-check for global ADB restoration and orphan daemon respawn. |
| `AdbShellExecutor.kt` | ADB Persistence | Auto-enforcement of global settings (`adb_allowed_connection_time=0`, `stay_on_while_plugged_in=7`) upon loopback connection failure. |
| `DaemonLauncher.kt` | Watchdog / Processes | Dynamic `base.apk` resolution via `pm path` for `/data/app/~~hash/` directories, PID tracking in `.pid` files to prevent multiple spawns. |
| `AccMonitor.java` | Vehicle State (ACC) | Parsing `dumpsys car_service` PowerModes (Standby, Sleep, Tod, Pre StartUp vs StartUp, Display on), display interactivity state, door locks, and motion safety guards. |
| `GearMonitor.java` | Driving Data (Gear) | 5 Hz polling without crashing listeners; integrated `CarAdapterManager.getShiftMode()` (TS Framework) and `CarPropertyBridge` fallback. |
| `TelemetryDataCollector.java` | Driving Telemetry | 2 Hz / 1 Hz ingestion for normalized speed (km/h), accelerator, brake, turn signals, and gear mode. |
| `BydDataCollector.java` | HAL & Sensors | Unit normalization, PHEV energy scale corrections, door/lock listeners, and disk snapshot persistence. |
| `RecordingModeManager.java` | Dashcam Automation | Automatic promotion to 30 FPS continuous recording when shifting out of Park or when GPS speed ≥ 3 km/h. |

---

## Upstream Merge Recommendations

1. **Zero Regression on DiLink 3.0 / 4.0 Models**:
   All `CarAdapterManager`, `PowerMode`, and 3-argument reflection calls are encapsulated within `try-catch` blocks and guarded by conditional probes such as `DiLink5QCarCamBackend.isSupported()`.
2. **Elimination of UID 2000 Crash Loops**:
   Replacing asynchronous listener registration on `BYDAutoGearboxDevice` with getter polling and the TS framework adapter definitively prevents system `HandlerThread` crashes.
3. **24/7 Persistence & Reliability**:
   The combination of `WifiLock` + `userActivity(..., 0, 1)` + `adb_allowed_connection_time=0` ensures continuous sentry mode and remote dashboard operation without requiring manual reactivation via hidden engineering menus.

