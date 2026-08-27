# Changelog

Tutte le modifiche e gli sviluppi in corso vengono tracciati in questo file e versionati in corrispondenza delle release ufficiali o dei Version Bump.

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
