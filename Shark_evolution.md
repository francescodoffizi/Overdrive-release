# Evoluzione BYD Shark & Piattaforma Multi-Camera DiLink 5.0
## Supporto Mappatura Hardware Dinamica (N Canali & Internal Dashcam)

## 1. Executive Summary & Diagnosi del Problema

Sulla piattaforma **BYD DiLink 5.0 (Qualcomm Snapdragon SA8155P)**, il demone di cattura nativo ad alte prestazioni `fast_cam_capture` e il bridge C++ JNI `qcarcam_bridge.cpp` sono stati inizialmente dimensionati su una configurazione fissa a 4 canali perimetrali (**0, 1, 2, 3**), tipica della **BYD Sealion 7**:
* **0**: Telecamera Frontale (Front)
* **1**: Telecamera Destra (Right)
* **2**: Telecamera Posteriore (Rear)
* **3**: Telecamera Sinistra (Left)

### Requisiti Emersi
1. **Piattaforma BYD Shark (Pickup DMO / SA8155P)**: I deserializzatori GMSL2 e l'ISP Qualcomm Spectra espongono un mapping differente per le 4 perimetrali:
   * **Canale 8**: Front
   * **Canale 9**: Right
   * **Canale 5**: Rear
   * **Canale 4**: Left
2. **Supporto Canale Aggiuntivo: Internal Dashcam / Cabin Camera**: Molti modelli BYD integrano una telecamera interna (nello specchietto o DVR frontale dedicato, es. canale 6 o 7).
3. **Numero Flessibile di Canali ($N$-Canali, da 1 a 8)**: Il sistema non deve essere vincolato rigidamente a 4 canali, ma poter gestire configurazioni a 1, 4, 5 o più telecamere in base all'allestimento hardware del veicolo.

**Diagnosi di intervento**: **Occorre modificare ENTRAMBI (Bridge/Daemon + App/Bridge JNI).**
* Il demone attuale vincola `--all` a 4 ID fissi `[0, 1, 2, 3]`.
* Il bridge JNI scarta qualsiasi frame con `frame.cam_id >= 4`.
* Tuttavia, l'architettura a passaggio di file descriptor SCM_RIGHTS e streaming a pacchetti è già naturalmente predisposta per scalare a $N$ canali arbitrari con modifiche minime e pulite.

---

## 2. Architettura Dinamica a $N$ Canali (Fino a 8 Telecamere)

### 2.1. Dimensionamento Hardware e IPC (`fast_cam_ipc.h`)

Lo Snapdragon SA8155P integra 3 ISP Qualcomm Spectra 390 che gestiscono fino a **8 stream raw a 2 Megapixel** simultanei.
Nel file header del protocollo IPC:

```c
#pragma once
#include <stdint.h>

#define FAST_CAM_IPC_SOCKET_PATH "/data/local/tmp/fast_cam.sock"
#define FAST_CAM_MAX_CAMS        8   // Esteso da 4 a 8 per supportare 4 surround + Dashcam interna + ausiliarie
#define FAST_CAM_BUFS_PER_CAM    5
#define FAST_CAM_MAX_TOTAL_BUFS  (FAST_CAM_MAX_CAMS * FAST_CAM_BUFS_PER_CAM) // 40 buffer DMA totali
#define FAST_CAM_MAGIC           0x4643414D // 'FCAM'
```

* **Consumo Memoria ION DMA**: 5 buffer da ~5 MB per canale $\times$ 5 telecamere = ~125 MB di RAM kernel, assolutamente trascurabile sui 12-16 GB di RAM del SA8155P.
* **Passaggio FD SCM_RIGHTS**: Fino a 40 FD scambiati in una singola chiamata `sendmsg`/`recvmsg` all'avvio.

---

## 3. Dettaglio Tecnico delle Modifiche Necessarie

### 3.1. Modifiche a `fast_cam_capture` (`main.c`)

Il parser degli argomenti CLI deve accettare una lista arbitraria e variabile di ID telecamera:

```bash
# Sealion 7 standard (4 surround):
fast_cam_capture --cams 0,1,2,3 --time 0

# Shark standard (4 surround):
fast_cam_capture --cams 8,9,5,4 --time 0

# Shark con Internal Dashcam (4 surround + 1 DVR/Cabin):
fast_cam_capture --cams 8,9,5,4,6 --time 0
```

### 2.1. Modifiche a `fast_cam_capture` (Sorgente Demone Nativo)

Nel file sorgente del demone (in `frame_grabber_light/fast_cam_capture/src/main.c`):

#### A. Aggiunta dell'opzione CLI `--cams <list>` o `--mapping <list>`
Attualmente il parser CLI gestisce solo:
* `--all` (forza `num_cams = 4` con ID fissi 0, 1, 2, 3)
* `--cam <id>` (apre una singola telecamera con `num_cams = 1`)

Occorre estendere il parser CLI per supportare:
```bash
/data/local/tmp/fast_cam_capture --cams 8,9,5,4 --time 0
```
oppure:
```bash
/data/local/tmp/fast_cam_capture --all --cams 8,9,5,4 --time 0
```

#### B. Implementazione in `main.c`
```c
int cam_ids[FAST_CAM_MAX_CAMS] = {0, 1, 2, 3}; // Default per Sealion 7 e modelli standard
int num_cams = 0;

for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "--cams") == 0 && i + 1 < argc) {
        char buf[64];
        strncpy(buf, argv[++i], sizeof(buf) - 1);
        num_cams = 0;
        char* token = strtok(buf, ",");
        while (token && num_cams < FAST_CAM_MAX_CAMS) {
            cam_ids[num_cams++] = atoi(token);
            token = strtok(NULL, ",");
        }
    } else if (strcmp(argv[i], "--all") == 0) {
        if (num_cams == 0) {
            num_cams = 4;
            // Se --cams non è stato specificato, usa default {0, 1, 2, 3}
            cam_ids[0] = 0; cam_ids[1] = 1; cam_ids[2] = 2; cam_ids[3] = 3;
        }
    }
}
```

Nel loop di inizializzazione hardware:
```c
for (int i = 0; i < num_cams; i++) {
    int hw_id = cam_ids[i];
    g_cam_handles[i] = qcarcam_open(hw_id);
    if (!g_cam_handles[i]) {
        fprintf(stderr, "[-] Failed to open hardware camera %d (slot %d)\n", hw_id, i);
        continue;
    }
    // Salva hw_id nello stream info della risposta handshake IPC
    handshake.streams[i].cam_id = hw_id;
}
```

---

### 2.2. Modifiche a `qcarcam_bridge.cpp` (NDK / C++ JNI)

Attualmente `qcarcam_bridge.cpp` utilizza direttamente `frame.cam_id` come indice nell'array `cam_ptrs[4]`:

```cpp
// CODICE ATTUALE:
if (frame.cam_id < 4 && frame.pixels) {
    cam_ptrs[frame.cam_id] = frame.pixels;
}
```
Se `frame.cam_id` è 8, la condizione è falsa e il puntatore non viene salvato.

#### Soluzione: Tabella di Mapping Logico / Indice di Slot Dinamico ($N$ Canali)
Invece di assumere indici numerici fissi 0..3:
1. Supportare le 4 posizioni canoniche perimetrali (**0=Front, 1=Right, 2=Rear, 3=Left**) + **Canale 6=Internal Dashcam**:
```cpp
struct CameraMapping {
    int hw_front   = 0;
    int hw_right   = 1;
    int hw_rear    = 2;
    int hw_left    = 3;
    int hw_dashcam = -1; // -1 se non presente
};
static CameraMapping g_camMapping;

// Funzione JNI per impostare il mapping da Java
extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeSetCameraMapping(
    JNIEnv* env, jclass clazz, jint front, jint right, jint rear, jint left, jint dashcam) {
    g_camMapping.hw_front   = front;
    g_camMapping.hw_right   = right;
    g_camMapping.hw_rear    = rear;
    g_camMapping.hw_left    = left;
    g_camMapping.hw_dashcam = dashcam;
    LOGI("Hardware camera mapping set: Front=%d, Right=%d, Rear=%d, Left=%d, Dashcam=%d",
         front, right, rear, left, dashcam);
}
```

2. Nel loop di streaming `streamClientLoop`:
```cpp
// Mappatura semantica dello slot per ciascun frame in arrivo
int slot = -1;
if (frame.cam_id == (uint32_t)g_camMapping.hw_front)        slot = 0; // Front
else if (frame.cam_id == (uint32_t)g_camMapping.hw_right)   slot = 1; // Right
else if (frame.cam_id == (uint32_t)g_camMapping.hw_rear)    slot = 2; // Rear
else if (frame.cam_id == (uint32_t)g_camMapping.hw_left)    slot = 3; // Left
else if (frame.cam_id == (uint32_t)g_camMapping.hw_dashcam) slot = 6; // Internal Dashcam

if (slot >= 0 && slot < FAST_CAM_MAX_CAMS && frame.pixels) {
    cam_ptrs[slot] = frame.pixels;
}

// 1. Mosaico 2x2 Decimato (desired_cam == 4) o Mosaico 4K Nativo (desired_cam == 5)
//    Continuano a usare i 4 slot perimetrali canonici p0, p1, p3, p2!
if (desired_cam == 4) {
    FastCamClient::compose2x2(cam_ptrs[0], cam_ptrs[1], cam_ptrs[3], cam_ptrs[2], mosaic_buf_2x2);
    render_pixels = mosaic_buf_2x2;
} else if (desired_cam == 5) {
    FastCamClient::compose4K(cam_ptrs[0], cam_ptrs[1], cam_ptrs[3], cam_ptrs[2], mosaic_buf_4k.get());
    render_pixels = mosaic_buf_4k.get();
}
// 2. Selezione Singola Telecamera (0=Front, 1=Right, 2=Rear, 3=Left, 6=Internal Dashcam)
else if (desired_cam >= 0 && desired_cam < FAST_CAM_MAX_CAMS) {
    if (slot == desired_cam) {
        render_pixels = frame.pixels;
    }
}
```

In questo modo:
* Il compositore perimetrale (2x2 e 4K) rimane perfetto e non influenzato.
* La dashcam interna è selezionabile sia per la visualizzazione a pieno schermo sul Pad/Web, sia per essere instradata a un encoder di registrazione dedicato (ad es. per salvare separatamente i video interni o abilitare il monitoraggio dell'abitacolo in Sentinella).

---

### 3.3. Modifiche ad Android Java / Kotlin (`DiLink5QCarCamBackend.java`)

In `DiLink5QCarCamBackend.java`:
```java
public static String getCameraMappingArgs() {
    String model = android.os.Build.MODEL != null ? android.os.Build.MODEL.toLowerCase() : "";
    String product = android.os.Build.PRODUCT != null ? android.os.Build.PRODUCT.toLowerCase() : "";
    
    // Rilevamento BYD Shark (Pickup DMO)
    if (model.contains("shark") || product.contains("shark")) {
        // Es. canali 8 (Front), 9 (Right), 5 (Rear), 4 (Left) + eventuale dashcam interna (es. 6)
        int internalDashcamId = getInternalDashcamHwId(); // da impostazioni o probe
        logger.info("Detected BYD Shark platform: configuring camera mapping 8,9,5,4 (dashcam=" + internalDashcamId + ")");
        nativeSetCameraMapping(8, 9, 5, 4, internalDashcamId);
        return (internalDashcamId >= 0) ? "--cams 8,9,5,4," + internalDashcamId : "--cams 8,9,5,4";
    }
    
    // Default (Sealion 7, Song, Han DiLink 5.0)
    int internalDashcamId = getInternalDashcamHwId();
    nativeSetCameraMapping(0, 1, 2, 3, internalDashcamId);
    return (internalDashcamId >= 0) ? "--cams 0,1,2,3," + internalDashcamId : "--cams 0,1,2,3";
}
```

---

## 4. Matrice Comparativa Mapping Veicoli DiLink 5.0

| Ruolo Semantico | Posizione Veicolo | ID Standard (es. Sealion 7) | ID BYD Shark (DMO) | Note |
| :--- | :--- | :--- | :--- | :--- |
| **0** | **Anteriore (Front)** | `0` | `8` | 1920x1300 @ 30 FPS |
| **1** | **Laterale Destra (Right)** | `1` | `9` | 1920x1300 @ 30 FPS |
| **2** | **Posteriore (Rear)** | `2` | `5` | 1920x1300 @ 30 FPS |
| **3** | **Laterale Sinistra (Left)** | `3` | `4` | 1920x1300 @ 30 FPS |
| **4** | **Mosaico 2x2 Decimato** | Canali 0, 1, 2, 3 compositi | Canali 8, 9, 5, 4 compositi | 1920x1300 per Pad / Web Live |
| **5** | **4K Ultra-HD Nativo** | Canali 0, 1, 2, 3 nativi | Canali 8, 9, 5, 4 nativi | 3840x2600 per Dashcam/Sentinella |
| **6** | **Internal Dashcam / Cabin** | Es. `4` o `6` | Es. `6` o `7` | Flusso autonomo o PiP |

---

## 4. Piano di Rilascio & Azioni Future

1. **Step 1 - Aggiornamento sorgente `fast_cam_capture`**: Aggiungere il supporto `--cams <csv>` nel sorgente C di `main.c` ed effettuare la cross-compilazione NDK per `aarch64-linux-android` (API 30).
2. **Step 2 - Aggiornamento `overdrive_fast_cam_release.tar.gz`**: Rigenerare l'archivio contenente il nuovo binario compilato.
3. **Step 3 - Adattamento JNI `qcarcam_bridge.cpp` & `DiLink5QCarCamBackend.java`**: Aggiungere la mappatura slot dinamica e il rilevamento modello/property `persist.overdrive.camera_mapping`.
4. **Step 4 - Validazione su Veicolo Shark**: Test di cattura con `adb shell /data/local/tmp/fast_cam_capture --cams 8,9,5,4 --time 5` e visualizzazione live nel Pad UI di OverDrive.
