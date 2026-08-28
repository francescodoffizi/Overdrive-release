# Changelog

Tutte le modifiche e gli sviluppi in corso vengono tracciati in questo file e versionati in corrispondenza delle release ufficiali o dei Version Bump.

## [In corso / Unreleased]

### Aggiunte e Correzioni (Added & Fixed)
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
