#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <stdint.h>
#include <atomic>

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define UYVY_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 2)
#define MAGIC_HEADER 0x44494C35
#define MAX_CLIENTS 8
#define MAX_CAMERAS 4

struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t data_size;
    uint64_t timestamp;
};

static void* g_windows[MAX_CAMERAS] = { NULL, NULL, NULL, NULL };
static int g_num_windows = 0;

static int g_server_fd = -1;
static int g_clients[MAX_CLIENTS];
static int g_client_cam[MAX_CLIENTS]; // 0=Front, 1=Right, 2=Rear, 3=Left, 4=Mosaic (2x2)
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
static std::atomic<bool> g_running{true};

static uint8_t g_mosaic_buf[UYVY_SIZE];

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

static void* get_cam_vaddr(int cam_idx, int buf_idx) {
    if (cam_idx < 0 || cam_idx >= g_num_windows) return NULL;
    void* win = g_windows[cam_idx];
    if (!win) return NULL;
    uint8_t* p_win = (uint8_t*)win;
    uint8_t* p_desc_base = (uint8_t*)(*(uint64_t*)(p_win + 0x78));
    if (!p_desc_base) return NULL;
    uint8_t* desc = p_desc_base + (buf_idx * 56);
    return *(void**)(desc + 0x10);
}

// 2x2 grid compositor in UYVY (4 cameras combined into 1920x1300 at 30 FPS)
static void compose_2x2_mosaic(const uint8_t* cam0, const uint8_t* cam1, const uint8_t* cam2, const uint8_t* cam3) {
    const int W = FRAME_WIDTH;  // 1920
    const int H = FRAME_HEIGHT; // 1300
    const int HALF_W = W / 2;   // 960
    const int HALF_H = H / 2;   // 650
    const uint32_t UYVY_BLACK = 0x00800080; // U=128, Y0=0, V=128, Y1=0 (Pure neutral black)

    // Top half: Cam 0 (Front) on Left, Cam 1 (Right) on Right
    for (int y = 0; y < HALF_H; y++) {
        const uint8_t* src0 = cam0 ? (cam0 + (y * 2) * W * 2) : NULL;
        const uint8_t* src1 = cam1 ? (cam1 + (y * 2) * W * 2) : NULL;
        uint8_t* dst_row = g_mosaic_buf + y * W * 2;

        // Top-Left: Cam 0
        if (src0) {
            for (int x = 0; x < HALF_W; x += 2) {
                int s = (x * 2) * 2;
                dst_row[x * 2]     = src0[s];
                dst_row[x * 2 + 1] = src0[s + 1];
                dst_row[x * 2 + 2] = src0[s + 2];
                dst_row[x * 2 + 3] = src0[s + 5];
            }
        } else {
            uint32_t* dst32 = (uint32_t*)dst_row;
            for (int x = 0; x < HALF_W / 2; x++) {
                dst32[x] = UYVY_BLACK;
            }
        }

        // Top-Right: Cam 1
        uint8_t* dst_right = dst_row + HALF_W * 2;
        if (src1) {
            for (int x = 0; x < HALF_W; x += 2) {
                int s = (x * 2) * 2;
                dst_right[x * 2]     = src1[s];
                dst_right[x * 2 + 1] = src1[s + 1];
                dst_right[x * 2 + 2] = src1[s + 2];
                dst_right[x * 2 + 3] = src1[s + 5];
            }
        } else {
            uint32_t* dst32 = (uint32_t*)dst_right;
            for (int x = 0; x < HALF_W / 2; x++) {
                dst32[x] = UYVY_BLACK;
            }
        }
    }

    // Bottom half: Cam 3 (Left) on Left, Cam 2 (Rear) on Right
    for (int y = 0; y < HALF_H; y++) {
        const uint8_t* src3 = cam3 ? (cam3 + (y * 2) * W * 2) : NULL;
        const uint8_t* src2 = cam2 ? (cam2 + (y * 2) * W * 2) : NULL;
        uint8_t* dst_row = g_mosaic_buf + (y + HALF_H) * W * 2;

        // Bottom-Left: Cam 3
        if (src3) {
            for (int x = 0; x < HALF_W; x += 2) {
                int s = (x * 2) * 2;
                dst_row[x * 2]     = src3[s];
                dst_row[x * 2 + 1] = src3[s + 1];
                dst_row[x * 2 + 2] = src3[s + 2];
                dst_row[x * 2 + 3] = src3[s + 5];
            }
        } else {
            uint32_t* dst32 = (uint32_t*)dst_row;
            for (int x = 0; x < HALF_W / 2; x++) {
                dst32[x] = UYVY_BLACK;
            }
        }

        // Bottom-Right: Cam 2
        uint8_t* dst_right = dst_row + HALF_W * 2;
        if (src2) {
            for (int x = 0; x < HALF_W; x += 2) {
                int s = (x * 2) * 2;
                dst_right[x * 2]     = src2[s];
                dst_right[x * 2 + 1] = src2[s + 1];
                dst_right[x * 2 + 2] = src2[s + 2];
                dst_right[x * 2 + 3] = src2[s + 5];
            }
        } else {
            uint32_t* dst32 = (uint32_t*)dst_right;
            for (int x = 0; x < HALF_W / 2; x++) {
                dst32[x] = UYVY_BLACK;
            }
        }
    }
}

static void* dma_streamer_thread(void* arg) {
    printf("[Hook] Dedicated 30.0 FPS DMA Streaming engine started.\n");

    const uint64_t target_frame_period_ns = 33333333ULL; // 30.00 FPS (~33.33 ms)
    uint32_t s_idx = 0;

    while (g_running.load()) {
        uint64_t frame_start_ns = get_monotonic_time_ns();

        pthread_mutex_lock(&g_mutex);

        // Check if any client is active
        int active_clients = 0;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i] >= 0) active_clients++;
        }

        if (active_clients > 0 && g_num_windows > 0) {
            s_idx++;
            int buf_idx = s_idx % 5;
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
                    // 2x2 Mosaic
                    if (!mosaic_built) {
                        const uint8_t* c0 = (const uint8_t*)get_cam_vaddr(0, buf_idx);
                        const uint8_t* c1 = (const uint8_t*)get_cam_vaddr(1, buf_idx);
                        const uint8_t* c2 = (const uint8_t*)get_cam_vaddr(2, buf_idx);
                        const uint8_t* c3 = (const uint8_t*)get_cam_vaddr(3, buf_idx);
                        compose_2x2_mosaic(c0, c1, c2, c3);
                        mosaic_built = true;
                    }
                    send_payload = g_mosaic_buf;
                } else {
                    // Single camera
                    send_payload = get_cam_vaddr(target_mode, buf_idx);
                }

                if (send_payload) {
                    FrameHeader header;
                    header.magic = MAGIC_HEADER;
                    header.width = FRAME_WIDTH;
                    header.height = FRAME_HEIGHT;
                    header.format = 1; // UYVY
                    header.data_size = UYVY_SIZE;
                    header.timestamp = frame_start_ns;

                    if (!write_all(cfd, &header, sizeof(header)) ||
                        !write_all(cfd, send_payload, header.data_size)) {
                        close(cfd);
                        g_clients[i] = -1;
                    }
                }
            }
        }

        pthread_mutex_unlock(&g_mutex);

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
            pthread_mutex_lock(&g_mutex);
            bool added = false;
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (g_clients[i] < 0) {
                    g_clients[i] = client;
                    g_client_cam[i] = 4; // Default: 2x2 Mosaic
                    printf("[Hook] Client connected in slot [%d] (fd=%d)\n", i, client);
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
            g_num_windows++;
        }
        pthread_mutex_unlock(&g_mutex);
    }
    return res;
}

// Hook qcarcam_get_frame: enforce proper hardware interrupt wait timeout (33ms = 30 FPS)
// to prevent qcarcam_test worker threads from busy-spinning at 100% CPU.
typedef int (*get_frame_fn)(void* hndl, void* p_info, uint64_t timeout, uint32_t flags);
static get_frame_fn real_get_frame = NULL;

extern "C" int qcarcam_get_frame(void* hndl, void* p_info, uint64_t timeout, uint32_t flags) {
    if (!real_get_frame) {
        real_get_frame = (get_frame_fn)dlsym(RTLD_NEXT, "qcarcam_get_frame");
    }
    if (timeout < 25000000ULL) {
        timeout = 33333333ULL; // 33.3ms (30 FPS)
    }
    return real_get_frame ? real_get_frame(hndl, p_info, timeout, flags) : -1;
}
