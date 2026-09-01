# Resume Optimization e Debugging

Questo documento riassume le attività svolte, i problemi identificati e le prossime azioni necessarie per stabilizzare lo streaming della dashcam sull'interfaccia web di Overdrive.

## Attività Completate

1. **Risoluzione Problemi di Compilazione (Build Fixes)**
   - Corretto l'errore `java.net.SocketException` e i problemi di incompatibilità con Gradle 8.13 configurando esplicitamente l'ambiente su **Java 21**.
   - Specificata la versione corretta di **NDK (26.1.10909125)** nei `build.gradle.kts` dei moduli `:app` e `:dilink-probe`.
   - Compilazione dell'APK (`assembleDebug`) completata con successo.

2. **Diagnostica Problema Streaming Web (Schermata Nera/Connessione infinita)**
   - Abbiamo analizzato lo stato dei processi sul sistema dell'auto (DiLink 5) e i log di sistema.
   - È stato scoperto che il processo di sistema che gestisce le camere è `byd_cam_daemon`.
   - **Causa Radice Trovata:** Il codice nativo dell'app (`hook_qcarcam.cpp`), responsabile dell'intercettazione dei frame e dell'apertura del server di streaming, era configurato per attivarsi **solo** se il processo ospite si chiamava `byd_apa`. Di conseguenza, nel `byd_cam_daemon` l'hook entrava in modalità "passthrough", ignorando la creazione dei thread per l'invio dei frame video. Questo spiega perché l'interfaccia web restava in perenne attesa senza ricevere dati.

3. **Ottimizzazione e Deploy**
   - Modificato `app/src/main/cpp/camera/hook_qcarcam.cpp` per riconoscere anche il processo `byd_cam_daemon` e avviare i thread dello streamer.
   - Compilata la nuova versione dell'applicazione e installato l'aggiornamento sul dispositivo (`app-arm64-v8a-debug.apk`).
   - Eseguito il riavvio forzato del demone Android di Overdrive per estrarre la nuova libreria (`libhook_qcarcam.so`) e posizionarla in `/data/local/tmp`.

## Problematiche Ancora Aperte (Next Steps)

> [!WARNING]
> Affinché le modifiche al codice C++ abbiano effetto, il demone di sistema delle telecamere della vettura deve ricaricare la libreria condivisa.

1. **Riavvio del Demone Camera (`byd_cam_daemon`)**
   Attualmente il processo `byd_cam_daemon` sta ancora girando con la vecchia versione dell'hook iniettata via `LD_PRELOAD`. 
   - **Soluzione:** È necessario "killare" il demone tramite ADB (`adb shell killall byd_cam_daemon` o `adb shell kill -9 <PID>`) per forzare il sistema dell'auto a riavviarlo. Al riavvio, il demone caricherà il nuovo `libhook_qcarcam.so` e i log dovrebbero finalmente mostrare la dicitura: *"[Hook] Detected byd_cam_daemon. Spawning streamer and socket threads."*

2. **Verifica Timeout "Encoder Warmup"**
   Una volta ripristinato il flusso di frame, occorrerà confermare se l'errore noto *"Encoder warmup timeout"* (in `OemDashcamApiHandler.java`) è stato indirettamente risolto dall'arrivo tempestivo dei frame al muxer, oppure se saranno necessari ulteriori aggiustamenti lato Java per stabilizzare l'avvio della registrazione a 30 FPS.

3. **Aggiornamento Changelog**
   Il file `CHANGELOG.md` non è ancora stato aggiornato con questi specifici fix sul driver C++. Questo verrà fatto in concomitanza con la verifica del funzionamento per un futuro Version Bump.
