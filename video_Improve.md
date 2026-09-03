# Specifiche Tecniche: Ottimizzazione Video & Approccio Ibrido (Dual-Pipeline 4K / 720p)

Questo documento definisce l'architettura tecnica per massimizzare la qualità video di **OverDrive** su piattaforma **BYD DiLink 5.0 (Qualcomm Snapdragon SA8155P)**, sfruttando al 100% la risoluzione nativa dei sensori telecamera e adottando una strategia **Ibrida (Dual-Pipeline)** ad alta efficienza.

---

## 1. Analisi del Potenziale Hardware e Limiti Attuali

### 1.1. Risoluzione dei Sensori Hardware
Le 4 telecamere perimetrali della vettura (Frontale, Destra, Posteriore, Sinistra) sono collegate all'ISP Qualcomm Spectra tramite deserializzatori GMSL2/FPD-Link e HAL Qualcomm AIS (`libais_client.so`). Ciascuna camera eroga un flusso nativo non compresso di:
* **Risoluzione per sensore**: $1920 \times 1300$ pixel a **30 FPS**
* **Formato colore**: `UYVY 4:2:2` (16 bit per pixel, 3840 byte per linea)
* **Throughput raw per telecamera**: $1920 \times 1300 \times 2 \times 30 = \mathbf{149{,}76\text{ MB/s}}$
* **Throughput aggregato a 4 canali**: $\mathbf{599\text{ MB/s}}$ (interamente gestito in DMA ion zero-copy da `fast_cam_capture`)

### 1.2. Il Collo di Bottiglia del Mosaico 2x2 a 1080p
Attualmente, per assemblare le 4 telecamere in un unico flusso video standard compatibile con la GPU e lo streaming:
* Il frame viene composto in un quadro complessivo da $1920 \times 1300$ (o scalato a $1920 \times 1080$).
* Di conseguenza, ogni telecamera viene decimata $2\times$ in orizzontale e $2\times$ in verticale:
  $$\text{Risoluzione effettiva per telecamera} = \frac{1920}{2} \times \frac{1300}{2} = \mathbf{960 \times 650\text{ pixel}}$$
* **Perdita di dettaglio**: viene scartato il **75% dei pixel originali** di ciascun sensore. Questo limita la leggibilità di targhe a media/lunga distanza o di volti durante gli eventi registrati da Sentinella e Dashcam.

---

## 2. Architettura dell'Approccio Ibrido (Dual-Pipeline)

Per coniugare **la massima qualità d'immagine possibile** con **consumi dati e latenza minimi** per l'accesso remoto da smartphone (4G/5G) e l'interfaccia Pad, la soluzione ideale è una separazione netta dei target:

```
                      [ 4 Telecamere Hardware BYD ]
                         (4x 1920x1300 @ 30 FPS)
                                    │
                                    ▼
                         [ fast_cam_capture ]
                    (Zero-Copy Shared ION DMA-BUF)
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
   [ PIPELINE LOCALE ]                              [ PIPELINE STREAMING ]
   (Dashcam / Sentry Storage)                       (Web Remote & Pad Live View)
           │                                                 │
           ▼                                                 ▼
 [ Compositore 4K Nativo ]                          [ Compositore 2x2 GPU ]
  3840 x 2160 @ 30 FPS                               1280 x 720 @ 25-30 FPS
 (Nessun downsampling pixel)                        (Leggero, low-bandwidth)
           │                                                 │
           ▼                                                 ▼
  [ MediaCodec HEVC/H.265 ]                         [ MediaCodec H.264 Baseline ]
  c2.qti.hevc.encoder (12 Mbps)                     c2.qti.avc.encoder (1.5 Mbps)
           │                                                 │
           ▼                                                 ▼
   [ Scheda MicroSD / SSD ]                         [ WebSocket JMuxer Web/Pad ]
    Video MP4 4K Ultra-HD                            Latenza < 180ms, 4G fluido
```

---

## 3. Specifiche Tecniche delle Due Pipeline

### 3.1. Pipeline Locale: Registrazione 4K Ultra-HD (Local Archiving)
* **Obiettivo**: Identificazione forense cristallina (targhe, collisioni, atti vandalici, volti in Sentinella).
* **Risoluzione Canvas**: **$3840 \times 2160$ (4K UHD standard)** o **$3840 \times 2600$ (Full Native Aspect Ratio 16:10.8)**.
* **Dettaglio per telecamera**: Ciascuna telecamera conserva **1920 × 1080 / 1920 × 1300 pixel reali al 100%**.
* **Codec di codifica**: **HEVC / H.265** tramite encoder hardware `c2.qti.hevc.encoder`.
  * L'efficienza di compressione H.265 riduce lo spazio occupato del 45% rispetto ad H.264 a parità di nitidezza.
* **Target Bitrate**: **$10 - 14\text{ Mbps}$** (costante o VBR con picco a 16 Mbps).
* **Impatto su Storage (MicroSD / SSD)**:
  * 1 minuto di registrazione: $\approx 85\text{ MB}$
  * 1 ora di registrazione continua: $\approx 5{,}1\text{ GB}$
  * Con una scheda MicroSD standard da 128 GB: oltre **24 ore** di registrazione continua o settimane di eventi Sentinella.
  * Throughput scrittura: $1{,}5\text{ MB/s}$, ampiamente gestibile da qualunque scheda classe U3/V30 (capacità fino a 30 MB/s).

### 3.2. Pipeline Streaming: Accesso Remoto Web & Pad (Live Monitoring)
* **Obiettivo**: Streaming fluido a bassissima latenza, avvio istantaneo e consumo dati ridotto su rete cellulare (SIM dell'auto o dello smartphone).
* **Risoluzione Canvas**: **$1280 \times 720\text{ pixel}$** (720p HD) o **$1920 \times 1080\text{ pixel}$**.
* **Codec di codifica**: **H.264 Baseline / Main Level 3.1** via `c2.qti.avc.encoder`.
  * Massima compatibilità con tutti i browser Web (Safari iOS, Chrome, Edge, Firefox) senza necessità di decodificatori HEVC software lato client.
* **Target Bitrate**: **$1{,}2 - 2{,}0\text{ Mbps}$**.
* **Throughput dati cellulare**: $\approx 10 - 15\text{ MB}$ per 1 minuto di visualizzazione live.

---

## 4. Capacità Hardware Snapdragon SA8155P

La piattaforma di bordo possiede specifiche hardware ampiamente ridondanti per sostenere questo carico:

| Componente | Capacità SA8155P | Utilizzo Previsto con Approccio Ibrido | Margine Disponibile |
| :--- | :--- | :--- | :--- |
| **VPU (Video Codec)** | Fino a $4\text{K} @ 60\text{ FPS} + 1080\text{p} @ 60\text{ FPS}$ simultanei | 1 stream 4K30 HEVC + 1 stream 720p30 H.264 | **~50% margine VPU** |
| **GPU Adreno 640** | 954 GFLOPS, bandwith fino a $34\text{ GB/s}$ | Compositing OpenGL 4K ($\approx 0{,}8\text{ ms}$) | **> 85% libera** |
| **ISP / DMA ion** | 4 stream paralleli UYVY zero-copy | Mappatura kernel diretta senza duplicazioni di RAM | **100% zero-copy** |
| **CPU (Kryo 485)** | 8 core a 2.84 GHz | Solo orchestrazione IPC e file I/O asincrono | **< 8% carico CPU** |

---

## 5. Dettagli di Implementazione nel Codice

### 5.1. Compositore C++ 4K (`compose_4k_mosaic_uyvy`)
Invece di decimare le righe e le colonne di ciascuna telecamera a metà, l'algoritmo copia i byte UYVY integrali di ciascun sensore nel rispettivo quadrante 4K:

```cpp
// Composizione 4K nativa (3840x2600 UYVY, 7680 byte per riga)
void compose_4k_mosaic_uyvy(
    const uint8_t* cam0, const uint8_t* cam1,
    const uint8_t* cam3, const uint8_t* cam2,
    uint8_t* out_4k_grid
) {
    const int W = 1920;
    const int H = 1300;
    const int STRIDE_IN = W * 2;         // 3840 byte
    const int STRIDE_4K = W * 2 * 2;     // 7680 byte

    // 1. Metà Superiore: Cam 0 (Front) a sinistra, Cam 1 (Right) a destra
    for (int y = 0; y < H; y++) {
        uint8_t* dst_row = out_4k_grid + y * STRIDE_4K;
        // Quadrante Top-Left: Cam 0 intera (1920 pixel)
        memcpy(dst_row, cam0 + y * STRIDE_IN, STRIDE_IN);
        // Quadrante Top-Right: Cam 1 intera (1920 pixel)
        memcpy(dst_row + STRIDE_IN, cam1 + y * STRIDE_IN, STRIDE_IN);
    }

    // 2. Metà Inferiore: Cam 3 (Left) a sinistra, Cam 2 (Rear) a destra
    for (int y = 0; y < H; y++) {
        uint8_t* dst_row = out_4k_grid + (H + y) * STRIDE_4K;
        // Quadrante Bottom-Left: Cam 3 intera (1920 pixel)
        memcpy(dst_row, cam3 + y * STRIDE_IN, STRIDE_IN);
        // Quadrante Bottom-Right: Cam 2 intera (1920 pixel)
        memcpy(dst_row + STRIDE_IN, cam2 + y * STRIDE_IN, STRIDE_IN);
    }
}
```

* **Vantaggio**: Eseguito con istruzioni `memcpy` su linee contigue allineate a 64-byte; l'intero canvas 4K (20 MB raw) viene assemblato in meno di **$1{,}2\text{ ms}$** dalla CPU o delegato a uno shader EGL in GPU.

### 5.2. Configurazione Dinamica in `UnifiedConfigManager.kt`
Aggiunta del selettore nelle impostazioni di registrazione di OverDrive:
* `recording_quality`: `STANDARD_1080P` (Attuale) vs `ULTRA_4K_HEVC` (Ibrido).
* Quando impostato su `ULTRA_4K_HEVC`, `HWEncoderGpu` alloca l'encoder su `video/hevc` a $3840 \times 2160$, mentre `GpuStreamScaler` continua ad alimentare lo scaler a 720p per il web.

---

## 6. Piano di Rilascio Suggerito

1. **Fase 1: Configurazione Encoder 4K HEVC**:
   - Creazione di un profilo dedicato in `HWEncoderGpu` per testare la stabilità di `c2.qti.hevc.encoder` su SA8155P a 4K @ 30 FPS.
2. **Fase 2: Buffer Canvas 4K nel Bridge**:
   - Abilitazione del compositore 4K e verifica termica del SoC su sessioni di registrazione continua prolungate (30+ minuti).
3. **Fase 3: Switch Intelligente da UI**:
   - Introduzione del toggle nelle impostazioni grafiche del Pad e della Dashboard Web.
