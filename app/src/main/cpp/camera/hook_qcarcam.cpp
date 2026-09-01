#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <time.h>
#include <stdint.h>
#include <atomic>

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define UYVY_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 2)
#define MAGIC_HEADER 0x44494C35
#define MAX_CLIENTS 8
#define MAX_CAMERAS 4
#define QCARCAM_MAX_NUM_PLANES 3
#define QCARCAM_MAX_NUM_BUFFERS 12

// Shared memory transport (replaces raw 4.77 MB socket writes)
#define SHM_PATH        "/data/local/tmp/dilink5_shm"
#define SHM_NUM_SLOTS   5          // slots 0-3: single cams, slot 4: mosaic
#define SHM_SLOT_SIZE   UYVY_SIZE  // 4,992,000 bytes per slot
#define SHM_TOTAL_SIZE  ((size_t)SHM_NUM_SLOTS * SHM_SLOT_SIZE)  // ~24 MB
#define SHM_NOTIF_MAGIC 0x44494C36U

// 8-byte notification sent over socket instead of the full frame payload
struct ShmNotif {
    uint32_t magic;    // SHM_NOTIF_MAGIC
    uint8_t  cam_idx;  // 0-3 single cam, 4 = mosaic
    uint8_t  buf_slot; // same as cam_idx (1:1 mapping)
    uint16_t seq;      // wrapping frame counter
};

// Legacy header kept for fallback path (when shm init fails)
struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t data_size;
    uint64_t timestamp;
};

// =========================================================================
// qcarcam_frame_info_t layout (from qcarcam_types.h)
// =========================================================================
struct qcarcam_frame_info_t {
    int      idx;
    uint32_t seq_no;
    uint64_t timestamp;
    uint64_t timestamp_system;
    uint64_t sof_qtimestamp;
    uint32_t field_type;
    uint32_t flags;
};

// =========================================================================
// qcarcam_plane_t and buffer tracking (from qcarcam_types.h)
// =========================================================================
struct qcarcam_plane_t {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t size;
    void*    p_buf;   // virtual address of this plane
};

struct qcarcam_buffer_t {
    qcarcam_plane_t planes[QCARCAM_MAX_NUM_PLANES];
    uint32_t        num_planes;
    uint32_t        flags;
};

struct qcarcam_buffers_t {
    uint32_t          color_fmt; // qcarcam_color_fmt_t (enum = 4 bytes)
    uint32_t          flags;
    qcarcam_buffer_t* buffers;   // pointer to buffer array (8 bytes on aarch64)
    uint32_t          num_buffers;
    uint32_t          padding;
};

// Per-camera buffer tracking: ION fd and mmap'd vaddr for up to 12 buffers
struct CamBufferInfo {
    int      buf_ion_fd[QCARCAM_MAX_NUM_BUFFERS]; // ION fd from plane[0].p_buf
    void*    buf_vaddr[QCARCAM_MAX_NUM_BUFFERS];  // mmap'd virtual address
    size_t   buf_size[QCARCAM_MAX_NUM_BUFFERS];   // size from plane[0].size
    uint32_t num_buffers;
    uint32_t color_fmt;
};

static void* g_windows[MAX_CAMERAS]  = { NULL, NULL, NULL, NULL };
static void* g_handles[MAX_CAMERAS]{nullptr, nullptr, nullptr, nullptr};
static CamBufferInfo g_cam_buffers[MAX_CAMERAS];
static std::atomic<int>      g_latest_buf_idx[MAX_CAMERAS]{-1, -1, -1, -1};
static std::atomic<uint64_t> g_frame_counter[MAX_CAMERAS]{0, 0, 0, 0};
static int g_num_windows = 0;

typedef int (*release_frame_fn)(void* hndl, uint32_t idx);
static release_frame_fn real_release_frame = NULL;

static int g_server_fd = -1;
static int g_clients[MAX_CLIENTS];
static int g_client_cam[MAX_CLIENTS]; // 0=Front, 1=Right, 2=Rear, 3=Left, 4=Mosaic (2x2)
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
static std::atomic<bool> g_running{true};

static uint8_t g_mosaic_buf[UYVY_SIZE];

// Shared memory transport
static uint8_t*  g_shm_base = nullptr;
static int       g_shm_fd   = -1;
static uint16_t  g_shm_seq  = 0;

static inline uint64_t get_monotonic_time_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static bool write_all(int fd, const void* buf, size_t count) {
    size_t total = 0;
    const uint8_t* ptr = (const uint8_t*)buf;
    while (total < count) {
        ssize_t w = send(fd, ptr + total, count - total, MSG_NOSIGNAL);
        if (w <= 0) return false;
        total += w;
    }
    return true;
}

// =========================================================================
// Helper: get camera virtual address for buf_idx
// Tries the g_cam_buffers table first (populated by our s_buffers hook).
// Falls back to the legacy window-struct pointer arithmetic for -display mode.
// =========================================================================
static void* get_cam_vaddr(int cam_idx, int buf_idx) {
    if (cam_idx < 0 || cam_idx >= MAX_CAMERAS) return NULL;

    // Fast path: direct buffer table (populated once by qcarcam_s_buffers at startup).
    // Read without mutex: g_cam_buffers is written only during s_buffers init, before
    // any frames arrive, so it is stable by the time dma_streamer_thread calls us.
    // Taking g_mutex here would deadlock because dma_streamer_thread already holds it.
    const CamBufferInfo& ci = g_cam_buffers[cam_idx];
    if (ci.num_buffers > 0 && buf_idx >= 0 && buf_idx < (int)ci.num_buffers) {
        return ci.buf_vaddr[buf_idx];
    }

    // Fallback: legacy window struct traversal (display mode)
    if (cam_idx >= g_num_windows) return NULL;
    void* win = g_windows[cam_idx];
    if (!win) return NULL;
    uint8_t* p_win = (uint8_t*)win;
    uint8_t* p_desc_base = (uint8_t*)(*(uint64_t*)(p_win + 0x78));
    if (!p_desc_base) return NULL;
    uint8_t* desc = p_desc_base + (buf_idx * 56);
    return *(void**)(desc + 0x10);
}

// High-speed 64-bit packed 2x2 grid compositor in UYVY (4 cameras combined into 1920x1300 at 30 FPS)
static void compose_2x2_mosaic(const uint8_t* cam0, const uint8_t* cam1, const uint8_t* cam2, const uint8_t* cam3) {
    const int W = FRAME_WIDTH;  // 1920
    const int H = FRAME_HEIGHT; // 1300
    const int HALF_W = W / 2;   // 960
    const int HALF_H = H / 2;   // 650
    const uint32_t UYVY_BLACK = 0x00800080; // U=128, Y0=0, V=128, Y1=0 (Pure neutral black)

    // Top half: Cam 0 (Front) on Left, Cam 1 (Right) on Right
    for (int y = 0; y < HALF_H; y++) {
        const uint64_t* src0_64 = cam0 ? (const uint64_t*)(cam0 + (y * 2) * W * 2) : NULL;
        const uint64_t* src1_64 = cam1 ? (const uint64_t*)(cam1 + (y * 2) * W * 2) : NULL;
        uint32_t* dst_left_32 = (uint32_t*)(g_mosaic_buf + y * W * 2);
        uint32_t* dst_right_32 = dst_left_32 + (HALF_W / 2);

        // Top-Left: Cam 0
        if (src0_64) {
            for (int x = 0; x < HALF_W / 2; x++) {
                uint64_t s = src0_64[x]; // 8 bytes (4 pixels) -> downsampled to 4 bytes (2 pixels)
                dst_left_32[x] = (uint32_t)(s & 0x00FFFFFFULL) | (uint32_t)((s >> 16) & 0xFF000000ULL);
            }
        } else {
            for (int x = 0; x < HALF_W / 2; x++) dst_left_32[x] = UYVY_BLACK;
        }

        // Top-Right: Cam 1
        if (src1_64) {
            for (int x = 0; x < HALF_W / 2; x++) {
                uint64_t s = src1_64[x];
                dst_right_32[x] = (uint32_t)(s & 0x00FFFFFFULL) | (uint32_t)((s >> 16) & 0xFF000000ULL);
            }
        } else {
            for (int x = 0; x < HALF_W / 2; x++) dst_right_32[x] = UYVY_BLACK;
        }
    }

    // Bottom half: Cam 3 (Left) on Left, Cam 2 (Rear) on Right
    for (int y = 0; y < HALF_H; y++) {
        const uint64_t* src3_64 = cam3 ? (const uint64_t*)(cam3 + (y * 2) * W * 2) : NULL;
        const uint64_t* src2_64 = cam2 ? (const uint64_t*)(cam2 + (y * 2) * W * 2) : NULL;
        uint32_t* dst_left_32 = (uint32_t*)(g_mosaic_buf + (y + HALF_H) * W * 2);
        uint32_t* dst_right_32 = dst_left_32 + (HALF_W / 2);

        // Bottom-Left: Cam 3
        if (src3_64) {
            for (int x = 0; x < HALF_W / 2; x++) {
                uint64_t s = src3_64[x];
                dst_left_32[x] = (uint32_t)(s & 0x00FFFFFFULL) | (uint32_t)((s >> 16) & 0xFF000000ULL);
            }
        } else {
            for (int x = 0; x < HALF_W / 2; x++) dst_left_32[x] = UYVY_BLACK;
        }

        // Bottom-Right: Cam 2
        if (src2_64) {
            for (int x = 0; x < HALF_W / 2; x++) {
                uint64_t s = src2_64[x];
                dst_right_32[x] = (uint32_t)(s & 0x00FFFFFFULL) | (uint32_t)((s >> 16) & 0xFF000000ULL);
            }
        } else {
            for (int x = 0; x < HALF_W / 2; x++) dst_right_32[x] = UYVY_BLACK;
        }
    }
}

static void* dma_streamer_thread(void* arg) {
    printf("[Hook] DMA streamer thread started (30 FPS target)\n");
    const uint64_t target_frame_period_ns = 33333333ULL; // 30.0 FPS
    uint64_t last_fps_log_ns = get_monotonic_time_ns();
    int frames_sent_count = 0;

    while (g_running.load()) {
        uint64_t frame_start_ns = get_monotonic_time_ns();

        pthread_mutex_lock(&g_mutex);

        // Check if any client is active
        int active_clients = 0;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i] >= 0) active_clients++;
        }

        if (active_clients > 0) {
            bool mosaic_built = false;

            for (int i = 0; i < MAX_CLIENTS; i++) {
                int cfd = g_clients[i];
                if (cfd < 0) continue;

                // Check for client commands (non-blocking)
                uint8_t cmd = 0xFF;
                ssize_t cr = recv(cfd, &cmd, 1, MSG_DONTWAIT);
                if (cr == 1 && cmd <= 4) {
                    g_client_cam[i] = cmd;
                    printf("[Hook] Client slot [%d] switched to Mode [%u]\n", i, cmd);
                }

                int target_mode = g_client_cam[i];
                void* send_payload = NULL;

                if (target_mode == 4) {
                    // 2x2 Mosaic: compose using the latest available buffer for each camera
                    if (!mosaic_built) {
                        const uint8_t* c0 = (const uint8_t*)get_cam_vaddr(0, g_latest_buf_idx[0].load(std::memory_order_relaxed));
                        const uint8_t* c1 = (const uint8_t*)get_cam_vaddr(1, g_latest_buf_idx[1].load(std::memory_order_relaxed));
                        const uint8_t* c2 = (const uint8_t*)get_cam_vaddr(2, g_latest_buf_idx[2].load(std::memory_order_relaxed));
                        const uint8_t* c3 = (const uint8_t*)get_cam_vaddr(3, g_latest_buf_idx[3].load(std::memory_order_relaxed));
                        compose_2x2_mosaic(c0, c1, c2, c3);
                        mosaic_built = true;
                    }
                    send_payload = g_mosaic_buf;
                } else {
                    // Single camera
                    int buf_idx = g_latest_buf_idx[target_mode].load(std::memory_order_relaxed);
                    send_payload = get_cam_vaddr(target_mode, buf_idx);
                }

                if (send_payload) {
                    if (g_shm_base) {
                        // Shared memory path: write frame, fence, send 8-byte notification
                        int slot = (target_mode <= 3) ? target_mode : 4;
                        memcpy(g_shm_base + (size_t)slot * SHM_SLOT_SIZE, send_payload, SHM_SLOT_SIZE);
                        __sync_synchronize();

                        ShmNotif notif;
                        notif.magic    = SHM_NOTIF_MAGIC;
                        notif.cam_idx  = (uint8_t)target_mode;
                        notif.buf_slot = (uint8_t)slot;
                        notif.seq      = g_shm_seq++;

                        if (!write_all(cfd, &notif, sizeof(notif))) {
                            close(cfd);
                            g_clients[i] = -1;
                            printf("[Hook] Client [%d] closed (shm notif write error)\n", i);
                        } else {
                            frames_sent_count++;
                        }
                    } else {
                        // Legacy fallback: send full raw frame over socket
                        FrameHeader header;
                        header.magic     = MAGIC_HEADER;
                        header.width     = FRAME_WIDTH;
                        header.height    = FRAME_HEIGHT;
                        header.format    = 1;
                        header.data_size = UYVY_SIZE;
                        header.timestamp = frame_start_ns;

                        if (!write_all(cfd, &header, sizeof(header)) ||
                            !write_all(cfd, send_payload, header.data_size)) {
                            close(cfd);
                            g_clients[i] = -1;
                            printf("[Hook] Client [%d] closed due to socket write error/timeout\n", i);
                        } else {
                            frames_sent_count++;
                        }
                    }
                }
            }
        }
        
        pthread_mutex_unlock(&g_mutex);

        // Real-time FPS logger
        if (frame_start_ns - last_fps_log_ns >= 3000000000ULL) {
            double secs = (double)(frame_start_ns - last_fps_log_ns) / 1000000000.0;
            double fps = (double)frames_sent_count / secs;
            printf("[Hook] Emitting Real-Time Stream: %.1f FPS (sent %d frames in %.1fs, cam0_frames=%llu, cam1_frames=%llu)\n",
                   fps, frames_sent_count, secs,
                   (unsigned long long)g_frame_counter[0].load(std::memory_order_relaxed),
                   (unsigned long long)g_frame_counter[1].load(std::memory_order_relaxed));
            frames_sent_count = 0;
            last_fps_log_ns = frame_start_ns;
        }

        // Precise sleep to maintain stable 30.0 FPS
        uint64_t elapsed_ns = get_monotonic_time_ns() - frame_start_ns;
        if (elapsed_ns < target_frame_period_ns) {
            uint64_t sleep_ns = target_frame_period_ns - elapsed_ns;
            struct timespec req = { 0, (long)sleep_ns };
            nanosleep(&req, NULL);
        }
    }

    return NULL;
}

static void* socket_server_thread(void* arg) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        g_clients[i] = -1;
        g_client_cam[i] = 4; // Default: 2x2 Mosaic
    }

    g_server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_server_fd < 0) return NULL;

    int buf_size = 8 * 1024 * 1024; // 8 MB socket buffer
    setsockopt(g_server_fd, SOL_SOCKET, SO_SNDBUF, &buf_size, sizeof(buf_size));
    setsockopt(g_server_fd, SOL_SOCKET, SO_RCVBUF, &buf_size, sizeof(buf_size));

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, "dilink5_cam", strlen("dilink5_cam"));
    socklen_t len = sizeof(sa_family_t) + strlen("dilink5_cam") + 1;

    if (bind(g_server_fd, (struct sockaddr*)&addr, len) < 0) {
        close(g_server_fd);
        return NULL;
    }

    if (listen(g_server_fd, 8) < 0) {
        close(g_server_fd);
        return NULL;
    }
    printf("[Hook] 4-Camera Multi-client server listening on @dilink5_cam\n");

    while (g_running.load()) {
        int client = accept(g_server_fd, NULL, NULL);
        if (client >= 0) {
            // Configure client socket with 8MB buffer and non-blocking timeout
            int c_buf_size = 8 * 1024 * 1024;
            setsockopt(client, SOL_SOCKET, SO_SNDBUF, &c_buf_size, sizeof(c_buf_size));
            setsockopt(client, SOL_SOCKET, SO_RCVBUF, &c_buf_size, sizeof(c_buf_size));

            struct timeval tv = { 0, 50000 }; // 50ms send timeout max
            setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

            pthread_mutex_lock(&g_mutex);
            bool added = false;
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (g_clients[i] < 0) {
                    g_clients[i] = client;
                    g_client_cam[i] = 4; // Default: 2x2 Mosaic
                    printf("[Hook] Client connected in slot [%d] (fd=%d, buf=8MB)\n", i, client);
                    added = true;
                    break;
                }
            }
            if (!added) {
                close(client);
            }
            pthread_mutex_unlock(&g_mutex);
        }
    }
    return NULL;
}

__attribute__((constructor))
void hook_init() {
    printf("[Hook] libhook_qcarcam.so 4-Camera driver initialized!\n");
    memset(g_cam_buffers, 0, sizeof(g_cam_buffers));

    // Create shared memory frame store (~24 MB file-backed mmap)
    g_shm_fd = open(SHM_PATH, O_CREAT | O_RDWR, 0666);
    if (g_shm_fd >= 0 && ftruncate(g_shm_fd, (off_t)SHM_TOTAL_SIZE) == 0) {
        void* m = mmap(NULL, SHM_TOTAL_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, g_shm_fd, 0);
        if (m != MAP_FAILED) {
            g_shm_base = (uint8_t*)m;
            printf("[Hook] Shared memory ready: %s (%zu MB)\n", SHM_PATH, SHM_TOTAL_SIZE >> 20);
        } else {
            printf("[Hook] WARN: shm mmap failed, falling back to legacy socket mode\n");
            close(g_shm_fd); g_shm_fd = -1;
        }
    } else {
        printf("[Hook] WARN: shm open/truncate failed, falling back to legacy socket mode\n");
        if (g_shm_fd >= 0) { close(g_shm_fd); g_shm_fd = -1; }
    }

    // This hook is always loaded via LD_PRELOAD into qcarcam_test — always spawn threads.
    printf("[Hook] Spawning streamer and socket threads.\n");
    pthread_t th_server, th_stream;
    pthread_create(&th_server, NULL, socket_server_thread, NULL);
    pthread_detach(th_server);
    pthread_create(&th_stream, NULL, dma_streamer_thread, NULL);
    pthread_detach(th_stream);
}

typedef int (*init_window_fn)(void* ctxt, void** pp_window);
static init_window_fn real_init_window = NULL;

extern "C" int _Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t(void* ctxt, void** pp_window) {
    if (!real_init_window) {
        real_init_window = (init_window_fn)dlsym(RTLD_NEXT, "_Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t");
    }
    int res = real_init_window ? real_init_window(ctxt, pp_window) : 0;
    if (pp_window && *pp_window) {
        pthread_mutex_lock(&g_mutex);
        if (g_num_windows < MAX_CAMERAS) {
            g_windows[g_num_windows] = *pp_window;
            printf("[Hook] Captured Camera [%d] test_util window pointer: %p\n", g_num_windows, *pp_window);
        }
        pthread_mutex_unlock(&g_mutex);
    }
    return res;
}

// Bypass display posting in qcarcam_test to eliminate 100% of GPU/SurfaceFlinger rendering overhead.
extern "C" int _Z28test_util_post_window_bufferP16test_util_ctxt_tP18test_util_window_tjPNSt3__14listIjNS3_9allocatorIjEEEE15qcarcam_field_t(
    void* ctxt, void* window, uint32_t buf_idx, void* p_list, int field_t) {

    int cam_idx = -1;
    pthread_mutex_lock(&g_mutex);
    for (int c = 0; c < MAX_CAMERAS; c++) {
        if (g_windows[c] == window) {
            cam_idx = c;
            break;
        }
    }
    pthread_mutex_unlock(&g_mutex);

    // We do NOT store g_latest_buf_idx here anymore. We do it in get_frame.
    // We also completely skip inserting into p_list to avoid libc++ ABI issues.

    return 0; // Skip display blitting completely!
}

// =========================================================================
// Hook qcarcam_open: capture handle → cam_idx mapping
// =========================================================================
typedef void* (*open_fn)(uint32_t);
static open_fn real_open = NULL;
extern "C" void* qcarcam_open(uint32_t input_id) {
    if (!real_open) real_open = (open_fn)dlsym(RTLD_NEXT, "qcarcam_open");
    void* res = real_open ? real_open(input_id) : NULL;
    printf("[Hook] qcarcam_open(input_id=%u) -> %p\n", input_id, res);
    if (input_id < MAX_CAMERAS) {
        pthread_mutex_lock(&g_mutex);
        g_handles[input_id] = res;
        pthread_mutex_unlock(&g_mutex);
    }
    return res;
}

// =========================================================================
// Hook qcarcam_s_param: log frame-rate and other params
// =========================================================================
typedef int (*s_param_fn)(void*, uint32_t, const void*);
static s_param_fn real_s_param = NULL;
extern "C" int qcarcam_s_param(void* hndl, uint32_t param, const void* p_val) {
    if (!real_s_param) real_s_param = (s_param_fn)dlsym(RTLD_NEXT, "qcarcam_s_param");
    uint32_t val0 = p_val ? *(const uint32_t*)p_val : 0;
    uint32_t val1 = p_val ? *((const uint32_t*)p_val + 1) : 0;
    uint32_t val2 = p_val ? *((const uint32_t*)p_val + 2) : 0;
    int res = real_s_param ? real_s_param(hndl, param, p_val) : -1;
    printf("[Hook] qcarcam_s_param(hndl=%p, param=%u, val=[0x%x, 0x%x, 0x%x]) -> %d\n", hndl, param, val0, val1, val2, res);
    return res;
}

// =========================================================================
// Hook qcarcam_start: pass-through, no param override.
// Forcing QCARCAM_PARAM_FRAME_RATE (param 7) via s_param after start returns
// error 20 on this AIS version and triggers an async session reset ~10s later.
// =========================================================================
typedef int (*start_fn)(void*);
static start_fn real_start = NULL;
extern "C" int qcarcam_start(void* hndl) {
    if (!real_start) real_start = (start_fn)dlsym(RTLD_NEXT, "qcarcam_start");
    int res = real_start ? real_start(hndl) : -1;
    printf("[Hook] qcarcam_start(hndl=%p) -> %d\n", hndl, res);
    return res;
}

// =========================================================================
// Hook qcarcam_s_buffers: capture virtual buffer addresses per camera
// This is the KEY hook for -noDisplay mode: we record plane[0].p_buf for
// each buffer so that qcarcam_get_frame can look them up by frame_info.idx.
// =========================================================================
typedef int (*s_buffers_fn)(void*, const void*);
static s_buffers_fn real_s_buffers = NULL;
extern "C" int qcarcam_s_buffers(void* hndl, const void* p_bufs) {
    if (!real_s_buffers) real_s_buffers = (s_buffers_fn)dlsym(RTLD_NEXT, "qcarcam_s_buffers");

    // Call real implementation first so buffers get registered with ISP
    int res = real_s_buffers ? real_s_buffers(hndl, p_bufs) : -1;

    if (p_bufs) {
        const qcarcam_buffers_t* bufs = (const qcarcam_buffers_t*)p_bufs;
        uint32_t num = bufs->num_buffers;
        uint32_t fmt = bufs->color_fmt;
        qcarcam_buffer_t* buf_arr = bufs->buffers;

        printf("[Hook] qcarcam_s_buffers(hndl=%p, color_fmt=0x%x, num_buffers=%u, buffers=%p) -> %d\n",
               hndl, fmt, num, buf_arr, res);

        // Find cam_idx for this handle
        int cam_idx = -1;
        pthread_mutex_lock(&g_mutex);
        for (int c = 0; c < MAX_CAMERAS; c++) {
            if (g_handles[c] == hndl) {
                cam_idx = c;
                break;
            }
        }

        if (cam_idx >= 0 && buf_arr && num > 0 && num <= QCARCAM_MAX_NUM_BUFFERS) {
            CamBufferInfo& ci = g_cam_buffers[cam_idx];
            ci.num_buffers = num;
            ci.color_fmt   = fmt;
            for (uint32_t b = 0; b < num; b++) {
                // p_buf is an ION fd (small integer), not a virtual address
                int ion_fd = (int)(intptr_t)buf_arr[b].planes[0].p_buf;
                size_t sz  = buf_arr[b].planes[0].size;
                ci.buf_ion_fd[b] = ion_fd;
                ci.buf_size[b]   = sz;
                // mmap the ION buffer into our address space
                void* va = (sz > 0 && ion_fd > 2) ?
                    mmap(NULL, sz, PROT_READ, MAP_SHARED, ion_fd, 0) : NULL;
                if (va == MAP_FAILED) va = NULL;
                ci.buf_vaddr[b] = va;
                printf("[Hook]   cam[%d] buf[%u] ion_fd=%d vaddr=%p stride=%u size=%zu\n",
                       cam_idx, b, ion_fd, va,
                       buf_arr[b].planes[0].stride, sz);
            }
        }
        pthread_mutex_unlock(&g_mutex);
    }

    return res;
}

// =========================================================================
// Hook qcarcam_release_frame: pass-through, but we expose the real fn for
// internal use by qcarcam_get_frame to release buffers back to the ISP.
// =========================================================================
typedef int (*release_frame_hook_fn)(void* hndl, uint32_t idx);
static release_frame_hook_fn real_release_frame_hook = NULL;

extern "C" int qcarcam_release_frame(void* hndl, uint32_t idx) {
    if (!real_release_frame_hook) {
        real_release_frame_hook = (release_frame_hook_fn)dlsym(RTLD_NEXT, "qcarcam_release_frame");
    }
    return real_release_frame_hook ? real_release_frame_hook(hndl, idx) : -1;
}

// =========================================================================
// Hook qcarcam_get_frame: the MAIN acquisition path in -noDisplay mode.
// After a successful frame:
//   1. Update g_latest_buf_idx so the DMA streamer picks up the frame.
//   2. Immediately release the previous frame buffer back to the ISP so the
//      hardware can continue writing new frames (avoids ring buffer starvation).
// =========================================================================
typedef int (*get_frame_fn)(void* hndl, void* p_info, uint64_t timeout, uint32_t flags);
static get_frame_fn real_get_frame = NULL;

extern "C" int qcarcam_get_frame(void* hndl, void* p_info, uint64_t timeout, uint32_t flags) {
    if (!real_get_frame) {
        real_get_frame = (get_frame_fn)dlsym(RTLD_NEXT, "qcarcam_get_frame");
    }
    int ret = real_get_frame ? real_get_frame(hndl, p_info, timeout, flags) : -1;

    // QCARCAM_RET_OK == 0
    if (ret == 0 && p_info) {
        qcarcam_frame_info_t* fi = (qcarcam_frame_info_t*)p_info;
        int new_buf_idx = fi->idx;

        // Find cam_idx for this handle (lock-free scan)
        int cam_idx = -1;
        for (int c = 0; c < MAX_CAMERAS; c++) {
            if (g_handles[c] == hndl) {
                cam_idx = c;
                break;
            }
        }

        if (cam_idx >= 0 && new_buf_idx >= 0) {
            // Atomically publish the new buffer index for the DMA streamer to read.
            // NOTE: We do NOT call qcarcam_release_frame here directly, because it can
            // cause a futex deadlock with qcarcam_test's internal locking or re-entrancy issues.
            // Instead, the dma_streamer_thread will call release_frame on the PREVIOUS
            // buffer once it sees a new buffer index published here.
            g_latest_buf_idx[cam_idx].store(new_buf_idx, std::memory_order_release);
            g_frame_counter[cam_idx].fetch_add(1, std::memory_order_relaxed);
        }
    }

    return ret;
}

