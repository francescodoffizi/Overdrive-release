# Changelog

Tutte le modifiche e gli sviluppi in corso vengono tracciati in questo file e versionati in corrispondenza delle release ufficiali o dei Version Bump.

## [Unreleased]

### Aggiunte (Added)
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
  - **Bridge Nativo C++ (`qcarcam_bridge.cpp`)**: Accesso dinamico e zero-copy all'HAL Qualcomm AIS (`/vendor/lib64/libais_client.so`) per lo streaming diretto dei flussi camera 1920x1300 @ 30 FPS.
  - **Backend Driver Java (`DiLink5QCarCamBackend.java`)**: Driver integrato nel ciclo di vita delle telecamere di OverDrive per Dashcam, Sentry e pipeline EGL/MediaCodec.
  - **Coordinatore AIDL TS AVM (`TsAvmCoordinator.java`)**: Gestione e risveglio del sottosistema Surround View 360° senza conflitti tramite `com.ts.avm.AvmAndroidService`.
  - **Profilo Hardware (`CameraProfiles.java`)**: Aggiunto profilo dedicato `dilink5_sealion7` con risoluzione nativa 1920x1300 e auto-rilevamento intelligente.
  - **Fallback Sicuro (`PanoramicCameraGpu.java`)**: Gestione automatica della presenza o assenza di `android.hardware.AVMCamera` garantendo retrocompatibilità al 100% con DiLink 3 e 4.
