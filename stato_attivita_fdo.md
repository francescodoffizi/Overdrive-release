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

### D. Integrazione Modello Sealion 7 & Rilevamento ACC DiLink 5.0
- **Modello Sealion 7 Ufficiale**: Aggiunto nel catalogo `manifest.json` dei modelli con Blade Battery LFP da 82.5 kWh nominali.
- **Rilevamento ACC DiLink 5.0**: Implementato in `AccMonitor.java` tramite polling `sys.accanim.status` e `PowerManager.isInteractive()`.
- **Installazione su Veicolo**: Build e installazione completate con successo via ADB (`10.0.1.45:5555`).

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
9. `app/build.gradle.kts` & `CHANGELOG.md`: Versione `50.0` e storico rilasci.

---

## 🚀 4. Prossimi Passi di Sviluppo
1. **Telemetria CarPropertyManager / VHAL DiLink 5**: Mappare i registri Android Automotive VHAL per la lettura di porte, finestrini, ricarica e pressione pneumatici.
2. **Risveglio Centralina 360° (AVM)**: Testare `TsAvmCoordinator.startAvm()` per verificare se è possibile alimentare anche le telecamere laterali/posteriore ad auto spenta.
