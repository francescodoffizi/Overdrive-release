# Changelog

Tutte le modifiche e gli sviluppi in corso vengono tracciati in questo file e versionati in corrispondenza delle release ufficiali o dei Version Bump.

## [In corso / Unreleased]

- **Risoluzione Rilevamento Stato ACC su DiLink 5.0 (Sealion 7) & Sicurezza Screen Deterrent in Guida**:
  - `AccMonitor.java`: Risolto il bug di parsing su `dumpsys car_service PowerMode` che matchava la stringa statica `All items` (contenente sempre i termini `Standby`/`Sleep`/`4=`), causando un perenne falso positivo `accOn=false / sentryMode=true` durante la marcia. Il comando ora isola la riga specifica `current` (riconoscendo correttamente `Pre StartUp`, `StartUp`, `DisPlay on`).
  - **Fail-Safe Telemetrico di Marcia**: Aggiunto in `AccMonitor.java` il controllo proattivo su velocità veicolo (`speed.kmh > 0`) e marcia inserita (`gearMode` in D/R/N/M/S) per forzare immediatamente lo stato `accOn=true / inSentryMode=false`.
  - **Protezione Anti-Blocco Schermo (Screen Deterrent)**:
    - `ScreenDeterrent.java`: Integrato il controllo `isVehicleActive()` in `onMotionDetected()`, `fire()` e `shouldStop()` per impedire l'attivazione o interrompere all'istante l'overlay se il veicolo è in marcia o attivo.
    - `DeterrentActivity.java`: Aggiunto il controllo in `onCreate()` e `shouldFinishNow()` per auto-chiudere immediatamente l'Activity a tutto schermo qualora il veicolo sia acceso o in movimento, ripristinando all'istante l'uso del touchscreen del pad.

- **Modalità Sentinella Autonoma (`armMode: power`) & Periodo di Grazia (15s)**:
  - **Sganciamento Totale da Dipendenze Cloud / Stato Serrature**: Configurato il default di `armMode` su `"power"` in `UnifiedConfigManager.kt` e nella configurazione on-device. Su DiLink 5.0 (Snapdragon SA8155P), l'armamento della Sentinella avviene direttamente e localmente sul segnale hardware `dumpsys car_service` `PowerMode STANDBY/SLEEP`, senza dipendere dalla ricezione del blocco porte via Cloud API/MQTT (che in garage interrati o con segnale 4G debole impediva l'attivazione).
  - **Periodo di Grazia (15 secondi)**: Introdotto in `CameraDaemon.java` un timer di grazia (`PowerArmGraceThread`) di 15 secondi dopo lo spegnimento/standby del veicolo (`ACC OFF`). Questo consente a conducente e passeggeri di scendere e chiudere le porte prima che la Sentinella armi i deterrenti visivi e la rilevazione di movimento basata su AI.
  - **Risoluzione Falsi Heartbeat `ACC ON` in `AccSentryDaemon.java`**: Corretta `readPowerLevel()` verificando preventivamente `AccMonitor.isAccOn()`. Su DiLink 5.0 l'HAL legacy restituiva costantemente `POWER_LEVEL_ON (2)`, annullando prematuramente lo standby di `CameraDaemon`.
  - **Risoluzione Dinamica APK nel Watchdog (`start_acc_sentry.sh` / `DaemonLauncher.kt`)**: Lo script di watchdog della sentinella risolve dinamicamente il percorso di `base.apk` tramite `pm path com.overdrive.app` a ogni avvio/respawn, evitando crash-loop in caso di aggiornamenti APK.
- **Integrazione Telemetria Hardware Diretta & Iniezione Runtime SDK DiLink 5.0 (Sealion 7)**:
  - **Architettura Compile-Only Stubs**: Estratti tutti gli stub fittizi `android.hardware.bydauto.*` da `app/src/main/java` verso il modulo `stubs-bydauto` compilato in un JAR `compileOnly`. Questo elimina completamente il problema dell'oscuramento delle classi OEM reali nel DEX dell'APK finale.
  - **Iniezione Runtime DEX (`Dilink5SdkInjector.java`)**: Implementata l'iniezione dinamica del DEX di sistema `/system/app/BydDataCollect/BydDataCollect.apk` in tutti i ClassLoader pertinenti (`PathClassLoader`, `ContextClassLoader`, `SystemClassLoader`).
  - **Bridge di Telemetria in Processo App & Sincronizzazione Multi-Path (`DaemonKeepaliveService` / `BydDataCollector`)**:
    - Su Android 11 (DiLink 5), l'accesso all'HAL richiede il binding del servizio di sistema `com.ts.appservice.caradapter.CarAdapterService`. Questo binding viene eseguito con successo nel processo applicativo registrato `com.overdrive.app`.
    - `BydDataCollector` viene inizializzato in `DaemonKeepaliveService.kt`, effettua il polling continuo dei 24 driver HAL (pressione TPMS pneumatici, contachilometri, tensione 12V, ecc.) e scrive atomicamente lo snapshot nei percorsi condivisi (`/storage/emulated/0/Android/data/com.overdrive.app/files/byd_telemetry_snap.json` e `/data/local/tmp/`).
    - Il daemon `byd_cam_daemon` (UID 2000) legge e unisce all'istante lo snapshot telemetrico restituendolo attraverso `/api/vehicle/state` alla UI web, Home Assistant, MQTT e ABRP.
  - **Inizializzazione Main Looper**: Chiamata esplicita a `Looper.prepareMainLooper()` in `CameraDaemon.main` per garantire che i singleton HAL che istanziano `Handler(Looper.getMainLooper())` (come `BYDAutoOtaDevice`, `BYDAutoSpeedDevice`, `BYDAutoAcDevice`) abbiano sempre una `MessageQueue` valida.
  - **Adattamento Builder Telemetria (`BydDataCollector`)**: Corretti i richiami per l'aggiornamento dinamico di pressione pneumatici (conversione bar/kPa), velocità motori (`rearMotorSpeed`), potenza istantanea (`enginePowerKw`) ed energia residua (`remainKwh`).

- **Risoluzione Aggiornamento Stato Batteria (SoC %) & Autonomia a Veicolo Spento / in Ricarica**:
  - `BydDataCollector.java`: Introdotto il tracciamento puntuale del successo di lettura HAL nel ciclo di polling (`socHalSucceeded`, `rangeHalSucceeded`, `fuelHalSucceeded`).
  - Risolto il problema del congelamento del SoC (es. bloccato al 67% durante la ricarica notturna): a veicolo spento / in sosta (ACC OFF) o quando l'HAL locale DiLink 5 restituisce `0.0` (unpopulated), i fallback e il merge dei dati da BYD Cloud/MQTT (`cs.socPercent`, `cs.elecRangeKm`, `cs.fuelPercent`, `cs.fuelRangeKm`) vengono ora applicati tempestivamente invece di essere bloccati dallo snapshot ereditato da `toBuilder()`.
  - `BydCloudDataMergeSocTest.java`: Aggiunta suite di test unitari a copertura del merge di SoC, autonomia elettrica e carburante (PHEV) in sosta e in marcia.
- **Risoluzione Risoluzione Video & Aspect Ratio su DiLink 5.0 (Full HD 1080p / 16:9 Standard)**:
  - `CameraProfile.java` & `CameraProfiles.java`: Introdotto il supporto per dimensioni encoder personalizzate. Impostata la risoluzione encoder di `dilink5_sealion7` a **1920×1080 @ 30 FPS** nativa (eliminando il calcolo legacy 4-strip che causava l'anomalo 960×2600 e lo stiramento verticale 2.7:1).
  - `GpuSurveillancePipeline.java`: Il costruttore e il supervisore della pipeline acquisiscono direttamente la risoluzione encoder risolta dal profilo (1920×1080 su DiLink 5).
  - `GpuMosaicRecorder.java`: Configurato il vertex/fragment shader OpenGL per eseguire il crop centrato 16:9 del sensore raw 1920×1300 verso canvas 1920×1080, preservando le proporzioni naturali dei sensori fisheye senza deformazioni.
- **Motore di Streaming DMA a 30.0 FPS Hardware & Eliminazione Tearing DMA (`hook_qcarcam.cpp`)**:
  - Sostituita l'intercettazione instabile di `clock_gettime` con un thread di streaming dedicato a 30.00 FPS con temporizzazione nanometrica su `CLOCK_MONOTONIC`.
  - Risolti i cali di framerate (~3.7 FPS) e il tearing orizzontale, garantendo un flusso video fluido a 30.0 FPS stabili verso MediaCodec e client di streaming.
- **Keep-Alive & Risveglio Sottosistema AVM per Modalità Sentinella su DiLink 5.0**:
  - `DiLink5QCarCamBackend.java` & `TsAvmCoordinator.java`: Integrato il binding e l'avvio preventivo del servizio di sistema `com.ts.avm.AvmAndroidService` (`startAvm()`) all'apertura del backend di cattura e all'armamento della Sentinella ad auto spenta, mantenendo alimentati i sensori fisici QCarCam.
- **Persistenza ADB (Wireless 5555 & USB) & Sblocco Alimentazione Periferiche su DiLink 5.0**:
  - `DiLink5PowerDiagnostics`: Aggiunta l'impostazione automatica persistente delle property di sistema (`persist.adb.tcp.port 5555`, `service.adb.tcp.port 5555`, `persist.sys.usb.config mtp,adb` e `adb_enabled 1`) per evitare la disattivazione del debug ADB al riavvio/sleep.
  - `AccSentryDaemon`: Rimosso il vincolo esclusivo DiLink 4 su `BYDAutoSpecialDevice`, abilitando il mantenimento dell'alimentazione dei rail USB / modem anche su DiLink 5.0 (Snapdragon SA8155P).
  - `RecordingModeManager`: Esteso il keep-alive delle telecamere e del backend nativo a basso livello ad auto spenta (ACC OFF) per DiLink 5.0.
- **Supporto Audio & AVAS su DiLink 5.0 / Android Automotive**:
  - `AvasController`: Aggiunto fallback con risoluzione binder VHAL (`CarPropertyBridge`) quando `getSystemService("auto")` non è registrato a livello di sistema operativo, abilitando la corretta esecuzione dei pattern sonori e prevenendo l'errore ingannevole di servizio non disponibile.
- **Stato Veicolo & Telemetria Istantanea (/api/vehicle/state)**:
  - `VehicleControlApiHandler`: Implementata la sintesi iniziale e il fallback dinamico da Cloud/VHAL in `handleGetState()` quando il collector locale non ha ancora completato il primo polling hardware, evitando risposte di errore "Dati veicolo non disponibili" al caricamento della dashboard.
- **Rilevamento Stato di Ricarica (Charging Status)**:
  - `BydDataCollector`: Se il cloud conferma lo stato di ricarica attiva (`cs.getChargingStateAsSdk() == 1`) con cavo collegato (`gunState >= 2`), promuove `b.chargingState` a `CHARGING (1)` e notifica `ChargingDetector` anche se l'hardware DiLink 5 locale restituisce `READY(0)` o `UNAVAILABLE`.
  - Popolato il tempo stimato residuo (`chargingRestTimeHours` / `chargingRestTimeMinutes`) dal cloud snapshot quando non fornito dall'hardware.
  - Corretta la localizzazione italiana in `it.json`: `"state_plugged": "Collegato"` (anziché la traduzione letterale `"Collegato in"`).
- **Gating Hotspot DVR Telecamere**:
  - `UnifiedConfigManager.resolveOemDashcamId()`: Eliminata l'assegnazione automatica di un canale DVR (`0/1`) quando il veicolo non ha una dashcam OEM installata/configurata (tipico dei veicoli per il mercato europeo), evitando la comparsa ingannevole dell'hotspot DVR sul selettore telecamere.
- **Rilevamento ACC OFF & Armamento Sentinella su DiLink 5.0**:
  - `AccMonitor`: Integrato il controllo dello stato di blocco porte (veicolo chiuso/bloccato) e display spento su DiLink 5.0 per determinare in modo affidabile lo stato di veicolo parcheggiato e consentire l'armamento della Sentinella.
- **Modello Veicolo `sealion7` (BYD Sealion 7)**:
  - Aggiunto `sealion7` nel catalogo `manifest.json` dei modelli 3D con batteria LFP Blade da 82.5 kWh nominali.
- **Integrazione Telemetria & VHAL DiLink 5.0**:
  - `CarPropertyBridge`: Aggiunta risoluzione diretta binder tramite `ServiceManager` per interrogare i servizi VHAL Android Automotive nativi (`car_service`).
  - `BydDataCollector`: Gestito il valore grezzo `0.0` di `StatisticDevice` come non popolato per permettere il fallback tempestivo su cloud/VHAL.
  - Esteso `mergeCloudData`: Merge automatico in assenza di segnale hardware per finestrini (LF, RF, LR, RR, tettuccio), portellone/bagagliaio e serrature.
  - `VehicleControlApiHandler`: Overlay a strati (SDK -> VHAL -> Cloud Snapshot) per restituire lo stato veritiero di batteria (SOC), autonomia, finestrini, serrature e climatizzazione nell'interfaccia utente.

## [50.0] - 2026-08-27

### Aggiunte (Added)
- **Supporto Completo BYD DiLink 5.0 (Snapdragon SA8155P / Sealion 7)**:
  - **Driver 4-Telecamere Hardware AIS/QCarCam**: Inizializzazione contemporanea dei 4 canali fisici (Anteriore, Destra, Posteriore, Sinistra) a risoluzione 1920x1300 @ 30 FPS.
  - **Compositore Griglia 2x2 Nativo C++**: Vista "Tutte le telecamere" con griglia 2x2 fluida e senza artefatti di slicing.
  - **Commutazione Istantanea Telecamere Hardware**: Selezione diretta dal diagramma dell'auto tra Anteriore, Destra, Posteriore e Sinistra con orientamento verticale naturale corretto.
  - **Architettura Multi-Client Socket (`hook_qcarcam.cpp`)**: Supporto fino a 8 client concorrenti con sincronizzazione a byte stream senza collisioni.
- **Nuovo modulo autonomo `dilink-probe` (DiLink 5 Camera Dumper & Diagnostic Probe)**:
  - App APK standalone per raccogliere informazioni hardware, kernel, HAL e APK su infotainment BYD DiLink 5.0 (Sealion 7 / Snapdragon 8155).
  - Dump automatico di proprietà di sistema (`getprop`), `dumpsys` (`media.camera`, `evs`, `ServiceManager`), e HAL (`lshal`, EVS 1.0/1.1, QCarCam).
  - Ispezione nodi V4L2 `/dev/video*`, `/dev/media*` e configurazioni XML vendor (`/vendor/etc/qcarcam`, `/vendor/etc/camera`, `/vendor/etc/evs`).
  - Classloader & Reflection probe per identificare i punti di ingresso Java/AIDL/HIDL disponibili su DiLink 5.
  - Test interattivo Android Camera2 API (`CameraManager.getCameraIdList()`, apertura `CameraDevice`, `ImageReader` continuo e calcolo FPS in tempo reale a schermo).
  - Test interattivo e client AIDL per il servizio di sistema `com.ts.avm.AvmAndroidService` (`getAvmStatus()`, `startAvm()`, `stopAvm()`, `IAvmServiceListener`).
  - Acquisizione ed estrazione diretta con successo del fotogramma hardware raw (1920x1300 @ 30 FPS YUV422) tramite Qualcomm QCarCam / AIS Client nativo (`libais_client.so`), convalidando la fattibilità al 100% della registrazione video Dashcam e Sentry Mode su DiLink 5.
  - Estrazione automatica e backup degli APK OEM di fabbrica (`com.byd.avm`, `com.ts.avm`, `com.byd.cameramanager`, ecc.) e relative librerie native `.so`.
  - Salvataggio automatico del dump ZIP sia su memoria interna (`/sdcard/Download/`) sia su tutte le chiavette e schede SD USB rimovibili connesse.
- **Supporto Nativo BYD DiLink 5.0 (Snapdragon SA8155P / Sealion 7) in OverDrive**:
  - **Sidecar Nativo (`dilink5_cam_sidecar`)**: Demone binario C++ standalone che bypassa le restrizioni del linker namespace Android (`classloader-namespace`) e trasmette i fotogrammi a 30 FPS su socket UNIX astratto `@dilink5_cam`.
  - **Bridge Nativo C++ (`qcarcam_bridge.cpp`)**: Riceve i frame dal sidecar e li inietta direttamente nella `ANativeWindow` / `SurfaceTexture` di Android per la pipeline OpenGL ES e MediaCodec.
  - **Backend Driver Java (`DiLink5QCarCamBackend.java`)**: Driver integrato nel ciclo di vita delle telecamere di OverDrive per Dashcam, Sentry e pipeline EGL/MediaCodec.
  - **Coordinatore AIDL TS AVM (`TsAvmCoordinator.java`)**: Gestione e risveglio del sottosistema Surround View 360° senza conflitti tramite `com.ts.avm.AvmAndroidService`.
    - **Profilo Hardware (`CameraProfiles.java`)**: Aggiunto profilo dedicato `dilink5_sealion7` con risoluzione nativa 1920x1300 e auto-rilevamento intelligente.
    - **Pipeline EGL & Dashcam Sicura (`EGLCore.java`, `OemDashcamPipeline.java`, `PanoramicCameraGpu.java`, `GpuStreamScaler.java`)**: Aggiunto fallback automatico GLES2 per contesti headless EGL, layout 1:1 passthrough a schermo intero su DiLink 5, supporto risoluzione nativa 1920x1300 e flusso H.264 attivo su WebSocket porta 8887.
    - **Iniezione Hook Hardware Zero-Copy (`hook_qcarcam.cpp`, `qcarcam_bridge.cpp`)**: Intercettazione diretta dei puntatori DMA a 30.0 FPS dalla pipeline nativa Qualcomm AIS (`/vendor/bin/qcarcam_test`) tramite disassembly degli offset a 64-bit (`0x7764`), con conversione UYVY->RGBA (correzione packing Little-Endian canali Rosso/Verde/Blu) e instradamento in tempo reale sul socket astratto `@dilink5_cam`.
    - **Supervisore Automatico Hardware (`DiLink5QCarCamBackend.java`)**: Gestione autonoma del ciclo di vita del processo di cattura hardware `qcarcam_test` con `libhook_qcarcam.so` e `1cam.xml` inclusi come asset e libreria condivisa nativa, con auto-restart trasparente e zero dipendenza da sessioni ADB manuali.
    - **Diagnostica Energetica & Sentry Keep-Alive (`DiLink5PowerDiagnostics.java`)**: Modulo di monitoraggio continuo e tracciamento su file flash (`/sdcard/Overdrive/sentry_power_test.log`), con acquisizione preventiva di `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`, policy `WIFI_SLEEP_POLICY_NEVER` e whitelist Doze per verificare la persistenza di CPU, Wi-Fi e telecamere durante lo spegnimento del veicolo (ACC OFF).
