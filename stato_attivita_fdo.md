# 🧠 MEMORIA DI SESSIONE: OVERDRIVE - BYD SEALION 7 (DiLink 5.0)

## 📌 1. Informazioni Veicolo & Ambiente di Esecuzione
- **Veicolo**: BYD Sealion 7 (2024–2025)
- **Infotainment**: BYD DiLink 5.0, Android 11 Automotive (API 30)
- **Piattaforma Hardware**: Qualcomm Snapdragon SA8155P (`msmnile`), GPU Adreno 640
- **Rete Locale / ADB**: IP `10.0.1.45:5555` (disponibile ad auto accesa / in standby parziale)
- **Connettività Remota**: Tunnel Cloudflare attivo via modem 4G/LTE eSIM integrata (`rmnet_data0`)
- **Branch Git**: `sealion7`
- **Versione Attuale**: `v50.0` (`braveheart-v50.0`, versionCode 66)

---

## 🎯 2. Traguardi Raggiunti & Funzionalità Operative

### A. Acquisizione Hardware 4 Telecamere (Qualcomm QCarCam / AIS)
- **Sensori Hardware**: 4 canali nativi indipendenti a 1920x1300 @ 30 FPS YUV422 (UYVY):
  - `Camera 0`: Anteriore (Front)
  - `Camera 1`: Destra (Right)
  - `Camera 2`: Posteriore (Rear)
  - `Camera 3`: Sinistra (Left)
- **Driver di Cattura Zero-Copy (`hook_qcarcam.cpp`)**:
  - Intercetta la funzione `test_util_init_window` di `/vendor/bin/qcarcam_test` (eseguito con `4cam.xml`).
  - Legge i puntatori di memoria DMA dai descrittori a 64-bit (`p_win + 0x78`, stride 56B, `vaddr` a `+0x10`).
  - Server multi-client (fino a 8 connessioni concorrenti) su socket UNIX astratto `@dilink5_cam`.
  - Resincronizzazione atomica a byte con magic header `0x44494C35` (zero crash o frame drops).

### B. Visualizzazione Web & Compositore Griglia 2x2
- **Compositore Nativo Griglia 2x2**: Quando è selezionata la vista *"Tutte le telecamere"* (Mode 0), `hook_qcarcam.cpp` assembla i 4 flussi in un fotogramma 1920x1300 composito a 30 FPS in C++ (< 1ms).
- **Commutazione Hardware Istantanea**: La selezione dei singoli pulsanti (*Anteriore*, *Destra*, *Posteriore*, *Sinistra*) trasmette un byte di comando sul socket commutando la telecamera a pieno schermo (1920x1300 @ 30 FPS).
- **Orientamento & Calibrazione Colore**:
  - Inversione verticale Y in `convert_uyvy_to_rgba` (`qcarcam_bridge.cpp`) per orientamento dritto naturale.
  - Filtro DiLink 4 RedMask disabilitato su DiLink 5 (colori reali, rosso e blu fedeli).

### D. Integrazione Modello Sealion 7, ACC & Telemetria DiLink 5.0
- **Modello Sealion 7 Ufficiale**: Aggiunto nel catalogo `manifest.json` dei modelli con Blade Battery LFP da 82.5 kWh nominali e agganciato al modello 3D fastback.
- **Rilevamento Standby Hardware & Sentinella Autonoma (`armMode: power`)**:
  - Implementato in `AccMonitor.java` tramite lettura diretta dello stato di alimentazione Android Automotive (`dumpsys car_service` `PowerMode STANDBY/SLEEP`).
  - Modalità Sentinella autonoma e sganciata dal Cloud: armamento immediato all'ingresso in standby senza dipendere dallo stato di blocco porte remoto.
  - Periodo di grazia di 15 secondi (`PowerArmGraceThread`) all'uscita dal veicolo prima di avviare il monitoraggio AI e i deterrenti.
- **Bridge Telemetria Hardware & TPMS Diretto**:
  - `BydDataCollector` opera in `DaemonKeepaliveService` dentro il processo registrato `com.overdrive.app`, agganciandosi con successo a `CarAdapterService` (`com.ts.appservice.caradapter`).
  - Scrittura e lettura persistente dello snapshot telemetrico (`byd_telemetry_snap.json`): TPMS 4 ruote (270-285 kPa / 39.2-41.3 psi), contachilometri (10072 km), SoC batteria (87%), autonomia (420 km) e VIN reale.
- **Risoluzione Stato di Ricarica**: `BydDataCollector` promuove `chargingState` a `CHARGING (1)` quando il cloud conferma la ricarica con cavo inserito anche se l'hardware DiLink 5 restituisce `0 (READY)`.
- **Gating Hotspot DVR Telecamere**: Disabilitato automaticamente su DiLink 5.0 per non mostrare hotspot DVR su veicoli europei privi di dashcam OEM.
### E. Risoluzione Schermo Verde Telecamere, AVAS & Telemetria Istantanea
- **Cattura Hardware 4 Telecamere a 30 FPS**:
  - Risolto il problema della schermata verde modificando il supervisore in `DiLink5QCarCamBackend` per caricare ed eseguire `4cam.xml` (tutti e 4 i sensori contemporaneamente).
  - Verificata la corretta iniezione dei fotogrammi nella pipeline GPU zero-copy con campionamento pixel OES non-zero e streaming H.264 / WebSocket attivo su porta `8887`.
- **Supporto Audio & Toni AVAS DiLink 5.0**:
  - `AvasController`: Integrato fallback su `CarPropertyBridge` quando il binder legacy `auto` non è registrato in Android Automotive, abilitando i pattern sonori senza generare errori.
- **Telemetria e Stato Veicolo (/api/vehicle/state)**:
  - `VehicleControlApiHandler`: Implementata sintesi immediata da Cloud/VHAL in caso di collector avviato di recente, eliminando gli errori "Dati non disponibili".
- **Deploy**: Installato con successo su veicolo via ADB (`192.168.189.60:5555`).

### F. Risoluzione FPS Bassi (30.0 FPS Stabili), Risoluzione 1080p & Sentinella AVM
- **Correzione Risoluzione & Aspect Ratio (Full HD 1080p / 16:9 Standard)**:
  - Eliminata la risoluzione anomala 960x2600 (derivante dalla formula legacy per strip 4-cam `panoWidth / 2` e `panoHeight * 2`).
  - `CameraProfile.java` & `CameraProfiles.java`: Introdotto il supporto per risoluzione encoder esplicita (1920x1080 @ 30 FPS).
  - `GpuMosaicRecorder.java`: Implementato il center-crop 16:9 del sensore raw 1920x1300 verso canvas 1920x1080 nello shader OpenGL, preservando le proporzioni fisheye senza deformazioni.
- **Motore di Streaming Hardware DMA a 30.0 FPS (`hook_qcarcam.cpp`)**:
### G. Integrazione Telemetria Hardware Diretta & Iniezione Runtime SDK DiLink 5.0
- **Architettura Compile-Only Stubs**:
  - Rimossi tutti i file stub mock `android.hardware.bydauto.*` da `app/src/main/java` e riposizionati in un task Gradle `compileOnly` separato. In questo modo il DEX finale dell'APK Overdrive non oscura più le classi OEM reali di sistema.
- **Iniezione Runtime DEX (`Dilink5SdkInjector.java`)**:
  - Implementata l'iniezione dinamica di `/system/app/BydDataCollect/BydDataCollect.apk` in tutti i ClassLoader pertinenti (`PathClassLoader`, `ContextClassLoader`, `SystemClassLoader`).
- **Bridge di Telemetria in Processo App & Sincronizzazione Multi-Path (`DaemonKeepaliveService` / `BydDataCollector`)**:
  - Su Android 11 Automotive (DiLink 5), l'accesso all'HAL BYD richiede il binding del servizio di sistema `com.ts.appservice.caradapter.CarAdapterService`. Questo binding viene eseguito con successo nel processo applicativo registrato `com.overdrive.app`.
  - `BydDataCollector` viene inizializzato ed eseguito in `DaemonKeepaliveService.kt`, interroga i 24 driver HAL locali (pressione TPMS pneumatici, contachilometri, tensione 12V, ecc.) e scrive atomicamente lo snapshot nei percorsi condivisi (`/storage/emulated/0/Android/data/com.overdrive.app/files/byd_telemetry_snap.json` e `/data/local/tmp/`).
  - Il daemon `byd_cam_daemon` (UID 2000) legge e unisce all'istante lo snapshot telemetrico restituendolo attraverso `/api/vehicle/state` alla UI web, Home Assistant, MQTT e ABRP.
- **Verifica Valori Live Rilevati**:
  - Pressione Pneumatici TPMS Hardware: `FL: 285 kPa (41.3 psi)`, `FR: 285 kPa (41.3 psi)`, `RL: 285 kPa (41.3 psi)`, `RR: 285 kPa (41.3 psi)` (`available: true`).
  - Batteria & Autonomia: `SoC: 87%`, `Range: 420 km`, `12V: 13.0V`.
  - Contachilometri & VIN: `10072 km`, `LGXCH4CD2S2151430`.

---

## 🚀 3. Istruzioni Operative per Deploy & Verifica
- **Build APK**:
  ```bash
  JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ./gradlew assembleDebug
  ```
- **Installazione su Sealion 7**:
  ```bash
  adb -s 100.119.76.84:5555 install -r -d app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
  ```
- **Riavvio Daemon**:
  ```bash
  adb -s 100.119.76.84:5555 shell "pkill -f byd_cam_daemon; nohup /data/local/tmp/start_cam_daemon.sh > /dev/null 2>&1 &"
  ```
- **Verifica Telemetria**:
  ```bash
  curl -s http://100.119.76.84:8080/api/vehicle/state | jq .
  ```
  - Azzerati i frame drop, il tearing e i duplicati orizzontali.
- **Wakeup & Sincronizzazione Sentinella ad Auto Spenta (`TsAvmCoordinator.java`)**:
  - Integrata l'attivazione preventivo `startAvm()` di `com.ts.avm.AvmAndroidService` all'apertura del driver di cattura per mantenere accese le telecamere anche ad auto bloccata/spenta.

### G. Risoluzione Aggiornamento Stato Batteria (SoC %) & Autonomia in Ricarica / Sosta
- **Tracciamento Successo HAL Puntuale (`BydDataCollector.java`)**:
  - Aggiunto `StatisticHalResult` che traccia se la lettura locale di SoC, autonomia elettrica e carburante ha avuto successo nel ciclo di polling corrente.
  - A veicolo spento / in ricarica notturna (ACC OFF) o quando `StatisticDevice` restituisce `0.0` unpopulated, i valori aggiornati in tempo reale da BYD Cloud/MQTT (`cs.socPercent`, `cs.elecRangeKm`, `cs.fuelPercent`, `cs.fuelRangeKm`) vengono applicati tempestivamente invece di rimanere bloccati dal valore ereditato da `snapshot.toBuilder()`.
  - Risolto il blocco della percentuale batteria (es. 67%) mentre il veicolo si ricarica.
- **Suite di Test Unitari (`BydCloudDataMergeSocTest.java`)**:
  - Convalidata la logica di fallback e priorità (HAL primario durante la guida, Cloud primario a veicolo spento o con HAL non popolato).

---

## 🛠️ 3. File Chiave del Modulo DiLink 5.0
1. `app/src/main/cpp/camera/hook_qcarcam.cpp`: Hook LD_PRELOAD per qcarcam_test, compositore 2x2 e server multi-client.
2. `app/src/main/cpp/camera/qcarcam_bridge.cpp`: Bridge JNI nativo, ricezione socket, conversione UYVY->RGBA e iniezione in `ANativeWindow`.
3. `app/src/main/java/com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend.java`: Backend Java per il ciclo di vita della cattura.
4. `app/src/main/java/com/overdrive/app/camera/dilink5/TsAvmCoordinator.java`: Coordinatore AIDL per `com.ts.avm.AvmAndroidService`.
5. `app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java`: Mappatura layout 1:1, stream scaler e gestione visuali 0..4.
6. `app/src/main/assets/dilink5/4cam.xml`: File di configurazione 4 canali per Qualcomm QCarCam.
7. `app/src/main/java/com/overdrive/app/monitor/AccMonitor.java`: Rilevamento stato accensione / standby display su DiLink 5.0.
8. `app/src/main/assets/web/shared/models/manifest.json`: Catalogo modelli veicoli con Sealion 7.
9. `app/src/main/java/com/overdrive/app/byd/CarPropertyBridge.java`: Bridge VHAL Android Automotive.
10. `app/src/main/java/com/overdrive/app/byd/BydDataCollector.java`: Raccolta telemetria e merge cloud.
11. `app/build.gradle.kts` & `CHANGELOG.md`: Versione `50.0` e storico rilasci.

---

## 🚀 4. Prossimi Passi di Sviluppo
1. **Risveglio Centralina 360° (AVM)**: Testare `TsAvmCoordinator.startAvm()` per verificare se è possibile alimentare anche le telecamere laterali/posteriore ad auto spenta.
2. **Modello 3D Sealion 7**: Quando disponibile il file `.glb` dedicato, inserirlo negli asset/manifest.
