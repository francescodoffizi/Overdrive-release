# Improvements: Mantenimento ADB/WAKE a Veicolo Spento e Acquisizione Dati Guida

Questo documento analizza e riassume in dettaglio le innovazioni tecniche, i metodi e le architetture sviluppate nei rami post-fork (`sealion7`, `feature/adb-persistence-and-self-healing`, `feature/native-qcarcam-streamer`) rispetto al ramo upstream `origin/main`.

Il documento è focalizzato sui due macro-temi chiave pronti per il porting nel ramo principale del mantainer:
1. **Mantenimento di ADB e WAKE del Pad a veicolo spento (Keep-Awake & Self-Healing)**
2. **Acquisizione dello Stato Veicolo (Acceso/Spento) e Dati di Guida (Drive Mode / Gear)**

---

## 1. Mantenimento di ADB e WAKE del Pad ad Auto Spenta

### Contesto e Problematiche Risolte
Sui veicoli BYD (in particolare con piattaforma **DiLink 5.0 / Android 11 Automotive su Qualcomm SA8155P**, come Sealion 7, Seal, Atto 3 aggiornate):
- All'aggancio dello stato **ACC OFF** o al blocco delle portiere, il sistema operativo Android e il Power Manager nativo inviano il SoC in **Deep Sleep / Suspend-to-RAM (STR)**.
- Le connessioni di rete (Wi-Fi e dati) vengono sospese, la porta ADB TCP (`5555`) viene chiusa o disabilitata per timeout (`adb_allowed_connection_time`), e i demoni in background (`app_process`) vengono congelati o terminati dall'OOM/Power Controller.
- L'approccio classico con `PowerManager.userActivity(long)` sui sistemi recenti risveglia lo schermo fisico, illuminando l'abitacolo (violando la modalità sentinella "stealth").

### Architettura e Soluzioni Implementate

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             PIANO DI PERSISTENZA                            │
├─────────────────────────────────────────────────────────────────────────────┤
│  1. Iniezione Stealth UserActivity (Android 11 SA8155P)                    │
│     PowerManager.userActivity(uptime, 0, NO_CHANGE_LIGHTS)                  │
│                                                                             │
│  2. Gestione Co-ordinata WakeLock + High-Perf WifiLock                      │
│     PowerManager.PARTIAL_WAKE_LOCK + WifiManager.WIFI_MODE_FULL_HIGH_PERF   │
│                                                                             │
│  3. Forzatura Globale Impostazioni ADB & Developer Settings (UID 2000)     │
│     adb_enabled=1, adb_wifi_enabled=1, adb_allowed_connection_time=0        │
│     stay_on_while_plugged_in=7, development_settings_enabled=1              │
│                                                                             │
│  4. Watchdog Resiliente con Risoluzione Dinamica APK                       │
│     Scansione `pm path` per superare i percorsi hash `~~hash/`             │
│     Controllo PID per prevenire istanze duplicate o loop di crash           │
│                                                                             │
│  5. Loopback Recovery & Auto-Guarigione dei Demoni (60s Health Check)      │
│     Ripristino preventivo porta 5555 da BootReceiver e AdbShellExecutor    │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### A. Iniezione Stealth di `userActivity` su Android 11+
In `AccSentryDaemon.java` è stata implementata una tecnica a reflection multilivello per mantenere la CPU attiva senza accendere il pannello:
- **Metodo a 3 argomenti (Android 11 / SA8155P)**:
  ```java
  // Signature: userActivity(long when, int event, int flags)
  // event = 0 (USER_ACTIVITY_EVENT_OTHER)
  // flags = 1 (USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS)
  pmUserActivity3ArgMethod.invoke(pm, SystemClock.uptimeMillis(), 0, 1);
  ```
  Questo resetta il timer di spegnimento dell'AP (Application Processor) mantenendo lo schermo completamente spento.
- **Fallback a 2 argomenti (DiLink 4)**: `userActivity(uptime, noChangeLights = true)`.
- **Fallback legacy a 1 argomento**: Eseguito solo se lo schermo è già acceso, evitando il riaccendersi indesiderato del display durante la sosta.

#### B. Gestione Co-ordinata di `WakeLock` e `WifiLock (WIFI_MODE_FULL_HIGH_PERF)`
Per consentire il funzionamento ininterrotto 24/7 di Cloudflare Tunnel, Telegram Bot Daemon e Web Dashboard ad auto spenta:
- In `AccSentryDaemon.java` e `DaemonKeepaliveService.kt`, all'ingresso in modalità sentinella o parcheggio viene acquisito un `WifiLock` dedicato:
  ```java
  WifiManager wm = (WifiManager) permissiveContext.getSystemService(Context.WIFI_SERVICE);
  wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AccSentry:Wifi");
  wifiLock.setReferenceCounted(false);
  wifiLock.acquire();
  ```
- Questo impedisce al modulo Wi-Fi di entrare in power-save mode o spegnersi quando il veicolo va in sosta.

#### C. Forzatura Persistente dei Parametri Globali ADB (UID 2000 / Shell)
In `AdbShellExecutor.kt`, `BootReceiver.kt` e nel timer periodico a 60s di `AccSentryDaemon.java`:
```kotlin
fun enforceGlobalAdbSettings(context: Context) {
    val cr = context.contentResolver
    Settings.Global.putInt(cr, "adb_enabled", 1)
    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
    // 0 = Nessun timeout di disconnessione automatica su Wi-Fi
    Settings.Global.putInt(cr, "adb_allowed_connection_time", 0)
    Settings.Global.putInt(cr, "development_settings_enabled", 1)
    // 7 = AC (1) | USB (2) | WIRELESS (4) -> resta sveglio sotto alimentazione
    Settings.Global.putInt(cr, "stay_on_while_plugged_in", 7)
}
```
- In caso di porta ADB `5555` chiusa o non in ascolto durante i tentativi di connessione loopback di `AdbShellExecutor`, viene tentata l'auto-guarigione istantanea riapplicando i parametri `Settings.Global`.

#### D. Watchdog Script con Risoluzione Dinamica APK e PID Tracking
Nei demoni indipendenti (`start_telegram.sh`, `start_cam_daemon.sh` generati da `DaemonLauncher.kt`):
- **Problema delle directory con hash su Android 11**: In Android 11 Automotive i percorsi dei pacchetti variano ad ogni aggiornamento (es. `/data/app/~~randomHash/com.overdrive.app-.../base.apk`). I percorsi hardcoded o le letture statiche di `System.getProperty("java.class.path")` causavano il fallimento dell'avvio in `app_process`.
- **Risoluzione Dinamica via Package Manager**:
  ```bash
  APK_PATH=$(pm path com.overdrive.app 2>/dev/null | grep '/base.apk$' | head -n 1 | sed 's/^package://')
  if [ -z "$APK_PATH" ] && [ -f "$FALLBACK_APK_PATH" ]; then APK_PATH="$FALLBACK_APK_PATH"; fi
  CLASSPATH=$APK_PATH app_process /system/bin --nice-name=telegram_bot_daemon com.overdrive.app.daemon.TelegramBotDaemon
  ```
- **Prevenzione Istanze Orfane/Duplicate (PID Tracking)**:
  Viene registrato il PID del watchdog in `/data/local/tmp/telegram_watchdog.pid` e validato tramite `kill -0 $OLD_WPID` prima di eseguire un nuovo spawn.
- **Auto-Healing Periodico (60s)** in `AccSentryDaemon.enforceAdbAndDaemonHealth()`:
  Il demone sentinella verifica con `isProcessRunning(...)` lo stato dei processi compagni (`CameraDaemon`, `TelegramBotDaemon`) e riavvia automaticamente gli script qualora fossero terminati in modo anomalo.

---

## 2. Acquisizione dei Dati di Guida e Stato del Veicolo

### Contesto e Problematiche Risolte
Sulle vetture BYD DiLink 5.0 / Sealion 7:
1. **Rilevamento ACC ON/OFF**: L'interfaccia legacy `android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice.getPowerLevel()` restituisce valori sentinella fittizi (`FAKE_OK = 4`, `INVALID = 255`) o genera eccezioni di sicurezza. Ciò faceva credere all'app che l'auto fosse sempre accesa o sempre spenta.
2. **Crash nel Rilevamento Marcia (Gear/Shift Mode)**: L'iscrizione di un listener ad `android.hardware.bydauto.gearbox.BYDAutoGearboxDevice` causa un crash fatale in `learningEPB()` per mismatch di permessi tra UID 2000 (Shell) e UID di sistema, provocando loop di restart.
3. **Mappatura Invertita / Distorta dei Segnali**:
   - Stato blocco portiere (`doorLockStatus`): Nel framework BYD, l'indice `1` o `2` ha semantiche proprietarie che causavano aperture/chiusure non sincronizzate.
   - Stato ricarica: La rilevazione dello sportellino di ricarica causava falsi positivi di ricarica attiva se lo sportellino era aperto ma il connettore scollegato.

---

### Metodi e Tecniche di Rilevamento Stato Veicolo (Acceso / Spento)

In `AccMonitor.java`, `AccSentryDaemon.java` e `BydDataCollector.java` è stata introdotta una pipeline a probe multi-sorgente con priorità deterministica:

```
                                    ┌───────────────────────────────┐
                                    │    Probe Stato Veicolo (ACC)  │
                                    └───────────────┬───────────────┘
                                                    │
             ┌──────────────────────────────────────┴──────────────────────────────────────┐
             ▼                                      ▼                                      ▼
┌─────────────────────────┐            ┌─────────────────────────┐            ┌─────────────────────────┐
│   1. Dumpsys CarService │            │  2. Display & PowerMgr  │            │  3. Telemetria e CanBus │
├─────────────────────────┤            ├─────────────────────────┤            ├─────────────────────────┤
│ Valutazione PowerMode:  │            │ PowerManager:           │            │ Se Marcia != P (D/R/N/S)│
│ - Standby (4)           │──► ACC OFF │ .isInteractive() == false──► ACC OFF │ oppure Speed >= 3 km/h  │──► ACC ON
│ - Sleep (8)             │            │                         │            │ (Auto in movimento)     │
│ - Str / Suspending (5,9)│            │ Lock Portiere:          │            └─────────────────────────┘
│ - Pre StartUp (1)       │            │ doorLockStatus[0] == 1  │──► ACC OFF
│ - Tod (12)              │            │ (Veicolo Bloccato)      │
│ - Off (0)               │            └─────────────────────────┘
├─────────────────────────┤
│ - StartUp (2)           │──► ACC ON
│ - Display on (10)       │
│ - Degraded (3)          │
└─────────────────────────┘
```

1. **Interrogazione `dumpsys car_service` per i PowerMode DiLink 5.0**:
   - **Stati di Sosta / Standby (ACC = OFF)**:
     - `4 = PowerMode Standby`
     - `8 = PowerMode Sleep`
     - `5 = PowerMode Str` (Suspend-to-RAM)
     - `9 = PowerMode Str Suspending`
     - `1 = PowerMode Pre StartUp` (Fase preliminare prima dell'accensione quadro)
     - `12 = PowerMode Tod` (Time of Day / Sosta)
     - `0 = PowerMode Off`
   - **Stati di Veicolo Attivo (ACC = ON)**:
     - `2 = PowerMode StartUp` (EV Ready / Motore pronto)
     - `10 = PowerMode DisPlay on` (Quadro strumenti e infotainment attivi per marcia)
     - `3 = PowerMode Degraded`
2. **Display Interactive State**: Se `PowerManager.isInteractive() == false`, il pad è in sospensione -> ACC OFF autoritativo.
3. **Stato Blocco Portiere (`doorLockStatus`)**: Se il veicolo risulta bloccato dall'esterno (`doorLockStatus[0] == 1`), viene confermato lo stato ACC OFF con avvio immediato della sentinella.
4. **Override di Movimento e Marcia Attiva**: Se `gearMode != GEAR_P` (es. `GEAR_D`, `GEAR_R`) o `speedKmh >= 3.0f`, il monitor forza `accOn = true`, azzera la modalità sentinella e segnala l'edge di accensione per proteggere la continuità di registrazione dashcam.
5. **Fallback `sys.accanim.status`**: Lettura della system property (`"1"` = OFF, `"0"` = ON).

---

### Metodi di Acquisizione Dati Guida (Drive Mode / Gear / Speed)

Per ovviare ai crash dei listener gearbox su DiLink 5.0, `GearMonitor.java` e `TelemetryDataCollector.java` utilizzano un'architettura **Polling a 5 Hz (200ms) multi-sorgente**:

#### A. Rilevamento Marcia e ShiftMode su Sealion 7 / DiLink 5.0
```java
// 1. Framework TS: CarAdapterManager -> CarBodyManager ("body")
Class<?> camCls = Class.forName("com.ts.lib.caradapter.CarAdapterManager");
Method getInst = camCls.getMethod("getInstance", Context.class);
Object cam = getInst.invoke(null, context);
Method getMgr = camCls.getMethod("getCarAdapterManager", String.class);
Object bodyMgr = getMgr.invoke(cam, "body");
Method m = bodyMgr.getClass().getMethod("getShiftMode");
int shift = ((Number) m.invoke(bodyMgr)).intValue();

// Mappatura Shift Mode:
// 0, 1 -> GEAR_P (Park)
// 2    -> GEAR_R (Reverse)
// 3    -> GEAR_N (Neutral)
// 4    -> GEAR_D (Drive)
// 5    -> GEAR_M (Manual)
// 6    -> GEAR_S (Sport)
```

#### B. Fallback a Catena per Massima Compatibilità
1. **CarBodyManager (`getShiftMode`)**: Principale per Sealion 7 e DiLink 5.0.
2. **CarAdapterManager Cabin Adapter (`getGearboxAutoModeType` / `getGear`)**: Per modelli intermedi.
3. **TelemetryDataCollector Snapshot**: Lettura dalla cache unificata della telemetria a 2 Hz.
4. **BydDeviceHelper Getter Polling (`BYDAutoGearboxDevice.getGearboxAutoModeType`)**: Interrogazione getter pura (senza registrare listener, evitando il crash in `learningEPB`).
5. **CarPropertyBridge & CarService Dumpsys**: Interrogazione delle proprietà hardware HAL:
   - `0x21406407` (`SHIFT_MODE`)
   - `0x21403a06`
   - `0x21403a0a`

#### C. Filtraggio e Validazione Telemetria Accessoria
- **Segnale TPMS (Pressione Pneumatici)**: Filtraggio dei valori grezzi fuori scala (> 500 kPa o `0xFF` / `255`) emessi dai sensori durante l'avvio del bus CAN, memorizzando l'ultimo snapshot valido su disco (`byd_telemetry_snap.json`).
- **Rilevamento Ricarica Fisico**: Utilizzo del segnale `CHARGING_GUN_STATER` hardware ID `0x21403407` per verificare l'effettiva inserzione del connettore di ricarica rispetto al mero stato meccanico dello sportellino.
- **Driving Safety Guard**: Blocco automatico dei comandi remoti e locali pericolosi (apertura bagagliaio, porte, tetto panoramico) se la marcia rilevata è diversa da `GEAR_P` o la velocità è > 0 km/h.
- **Promozione Automatica Dashcam**: In `RecordingModeManager.java`, la transizione della marcia a `D`/`R` o una velocità GPS ≥ 3 km/h promuove istantaneamente la registrazione video a 30 FPS continui.

---

## Riepilogo File e Componenti Coinvolti

| Componente / File | Ambito di Intervento | Descrizione delle Modifiche |
| :--- | :--- | :--- |
| `AccSentryDaemon.java` | Power / ADB / Sentry | Iniezione `userActivity` a 3 argomenti stealth (Android 11), `WifiLock High Perf`, health-check a 60s per ripristino globale ADB e demoni orfani. |
| `AdbShellExecutor.kt` | ADB Persistence | Auto-enforcement delle chiavi globali (`adb_allowed_connection_time=0`, `stay_on_while_plugged_in=7`) su fallimento connessione loopback. |
| `DaemonLauncher.kt` | Watchdog / Processi | Risoluzione dinamica di `base.apk` via `pm path` per strutture `/data/app/~~hash/`, tracking PID in `.pid` contro spawn multipli. |
| `AccMonitor.java` | Stato Veicolo (ACC) | Parsing `dumpsys car_service` PowerMode (Standby, Sleep, Tod, Pre StartUp vs StartUp, Display on), stato interattività display, lock portiere e guardie di movimento. |
| `GearMonitor.java` | Dati Guida (Marcia) | Polling a 5 Hz senza listener crascanti; integrazione `CarAdapterManager.getShiftMode()` (TS Framework) e fallback su `CarPropertyBridge`. |
| `TelemetryDataCollector.java` | Telemetria Guida | Ingestion 2 Hz / 1 Hz per velocità normalizzata km/h, acceleratore, freno, indicatori di direzione e marcia. |
| `BydDataCollector.java` | HAL & Sensoristica | Normalizzazione unità di misura, correzione scala energia PHEV, listener per porte/serrature e persistenza snapshot su disco. |
| `RecordingModeManager.java` | Dashcam Automation | Passaggio automatico a 30 FPS su cambio marcia da P o movimento GPS ≥ 3 km/h. |

---

## Raccomandazioni per il Merge upstream

1. **Nessun impatto regressivo sui modelli DiLink 3.0 / 4.0**:
   Tutte le chiamate su `CarAdapterManager`, `PowerMode` e i metodi di reflection a 3 argomenti sono incapsulate in blocchi `try-catch` con probe condizionale basato su `DiLink5QCarCamBackend.isSupported()`.
2. **Eliminazione dei crash loop UID 2000**:
   La rimozione dell'iscrizione listener asincrona su `BYDAutoGearboxDevice` in favore del polling getter o dell'adattatore TS risolve definitivamente i crash del `HandlerThread` di sistema.
3. **Persistenza e Affidabilità 24/7**:
   L'accoppiata `WifiLock` + `userActivity(..., 0, 1)` + `adb_allowed_connection_time=0` garantisce il funzionamento continuo della modalità sentinella e della dashboard remota senza richiedere riattivazioni manuali via menu segreto.
