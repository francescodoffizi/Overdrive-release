// dilink5_cam_sidecar.cpp — Dedicated Native QCarCam C++ Hardware Streamer for DiLink 5.0 (Snapdragon SA8155P).
// Runs in the default native root linker namespace to directly call /vendor/lib64/libais_client.so
// Replaces vendor qcarcam_test, eliminates multi-camera diagnostic overhead, and streams 30 FPS.

#include "qcarcam.h"
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <signal.h>
#include <time.h>
#include <atomic>
#include <chrono>

#define FRAME_WIDTH  1920
#define FRAME_HEIGHT 1300
#define NUM_BUFFERS  4
#define FRAME_SIZE   (FRAME_WIDTH * FRAME_HEIGHT * 2) // UYVY 16-bit
#define MAGIC_HEADER 0x44494C35
#define SOCKET_NAME  "dilink5_cam"
#define MAX_CLIENTS  8

struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format; // 1 = UYVY
    uint32_t data_size;
    uint64_t timestamp;
};

// Function pointer definitions for libais_client.so
typedef qcarcam_ret_t (*pfn_qcarcam_initialize)(qcarcam_init_t* p_init_params);
typedef qcarcam_ret_t (*pfn_qcarcam_uninitialize)(void);
typedef qcarcam_hndl_t (*pfn_qcarcam_open)(qcarcam_input_t input_id);
typedef qcarcam_ret_t (*pfn_qcarcam_close)(qcarcam_hndl_t hndl);
typedef qcarcam_ret_t (*pfn_qcarcam_s_param)(qcarcam_hndl_t hndl, qcarcam_param_t param, const qcarcam_param_value_t* p_value);
typedef qcarcam_ret_t (*pfn_qcarcam_s_buffers)(qcarcam_hndl_t hndl, qcarcam_buffers_t* p_buffers);
typedef qcarcam_ret_t (*pfn_qcarcam_start)(qcarcam_hndl_t hndl);
typedef qcarcam_ret_t (*pfn_qcarcam_stop)(qcarcam_hndl_t hndl);
typedef qcarcam_ret_t (*pfn_qcarcam_get_frame)(qcarcam_hndl_t hndl, qcarcam_frame_info_t* p_frame_info, uint64_t timeout, uint32_t flags);
typedef qcarcam_ret_t (*pfn_qcarcam_release_frame)(qcarcam_hndl_t hndl, uint32_t idx);

static pfn_qcarcam_initialize   g_qcarcam_initialize = nullptr;
static pfn_qcarcam_uninitialize g_qcarcam_uninitialize = nullptr;
static pfn_qcarcam_open         g_qcarcam_open = nullptr;
static pfn_qcarcam_close        g_qcarcam_close = nullptr;
static pfn_qcarcam_s_param      g_qcarcam_s_param = nullptr;
static pfn_qcarcam_s_buffers    g_qcarcam_s_buffers = nullptr;
static pfn_qcarcam_start        g_qcarcam_start = nullptr;
static pfn_qcarcam_stop         g_qcarcam_stop = nullptr;
static pfn_qcarcam_get_frame    g_qcarcam_get_frame = nullptr;
static pfn_qcarcam_release_frame g_qcarcam_release_frame = nullptr;

static std::atomic<bool> g_running{true};
static qcarcam_hndl_t g_camera_handles[4] = { nullptr, nullptr, nullptr, nullptr };
static void* g_buffer_ptrs[4][NUM_BUFFERS];
static qcarcam_buffer_t g_buffers[4][NUM_BUFFERS];

static int g_clients[MAX_CLIENTS];
static int g_client_cam[MAX_CLIENTS];
static pthread_mutex_t g_client_mutex = PTHREAD_MUTEX_INITIALIZER;

static inline uint64_t get_time_ns() {
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

typedef int (*pfn_test_util_init)(void** pp_ctxt, void* p_params);
typedef int (*pfn_test_util_init_window)(void* ctxt, void** pp_window);
typedef int (*pfn_test_util_init_window_buffers)(void* ctxt, void* window, qcarcam_buffers_t* p_buffers);
typedef int (*pfn_test_util_get_buf_ptr)(void* window, void* p_buf_ptr);

static pfn_test_util_init                g_test_util_init = nullptr;
static pfn_test_util_init_window         g_test_util_init_window = nullptr;
static pfn_test_util_init_window_buffers g_test_util_init_window_buffers = nullptr;
static pfn_test_util_get_buf_ptr         g_test_util_get_buf_ptr = nullptr;
static void* g_test_util_ctxt = nullptr;
static void* g_test_util_window[4] = { nullptr, nullptr, nullptr, nullptr };

static bool load_qcarcam_symbols() {
    void* lib = dlopen("/vendor/lib64/libais_client.so", RTLD_NOW);
    if (!lib) lib = dlopen("libais_client.so", RTLD_NOW);
    if (!lib) {
        fprintf(stderr, "[-] dlopen(libais_client.so) failed: %s\n", dlerror());
        return false;
    }

    void* util_lib = dlopen("/vendor/lib64/libais_test_util_proprietary.so", RTLD_NOW);
    if (!util_lib) util_lib = dlopen("libais_test_util_proprietary.so", RTLD_NOW);

    if (util_lib) {
        g_test_util_init = (pfn_test_util_init)dlsym(util_lib, "_Z14test_util_initPP16test_util_ctxt_tP23test_util_ctxt_params_t");
        g_test_util_init_window = (pfn_test_util_init_window)dlsym(util_lib, "_Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t");
        g_test_util_init_window_buffers = (pfn_test_util_init_window_buffers)dlsym(util_lib, "_Z29test_util_init_window_buffersP16test_util_ctxt_tP18test_util_window_tP17qcarcam_buffers_t");
        g_test_util_get_buf_ptr = (pfn_test_util_get_buf_ptr)dlsym(util_lib, "_Z21test_util_get_buf_ptrP18test_util_window_tP19test_util_buf_ptr_t");
    }

    g_qcarcam_initialize   = (pfn_qcarcam_initialize)dlsym(lib, "qcarcam_initialize");
    g_qcarcam_uninitialize = (pfn_qcarcam_uninitialize)dlsym(lib, "qcarcam_uninitialize");
    g_qcarcam_open         = (pfn_qcarcam_open)dlsym(lib, "qcarcam_open");
    g_qcarcam_close        = (pfn_qcarcam_close)dlsym(lib, "qcarcam_close");
    g_qcarcam_s_param      = (pfn_qcarcam_s_param)dlsym(lib, "qcarcam_s_param");
    g_qcarcam_s_buffers    = (pfn_qcarcam_s_buffers)dlsym(lib, "qcarcam_s_buffers");
    g_qcarcam_start        = (pfn_qcarcam_start)dlsym(lib, "qcarcam_start");
    g_qcarcam_stop         = (pfn_qcarcam_stop)dlsym(lib, "qcarcam_stop");
    g_qcarcam_get_frame    = (pfn_qcarcam_get_frame)dlsym(lib, "qcarcam_get_frame");
    g_qcarcam_release_frame = (pfn_qcarcam_release_frame)dlsym(lib, "qcarcam_release_frame");

    if (!g_qcarcam_initialize || !g_qcarcam_open || !g_qcarcam_s_buffers || !g_qcarcam_start || !g_qcarcam_get_frame || !g_qcarcam_release_frame) {
        fprintf(stderr, "[-] Missing essential QCarCam symbols in libais_client.so\n");
        return false;
    }

    printf("[+] Successfully loaded Qualcomm QCarCam API & Test Util\n");
    return true;
}

static bool init_camera(int cam_id) {
    if (g_camera_handles[cam_id] != nullptr) return true;

    printf("[+] Initializing QCarCam hardware stream for Camera %d...\n", cam_id);
    qcarcam_hndl_t hndl = g_qcarcam_open(cam_id);
    if (!hndl) {
        fprintf(stderr, "[-] qcarcam_open failed for Camera %d\n", cam_id);
        return false;
    }

    qcarcam_param_value_t param_val;
    param_val.uint_value = QCARCAM_FMT_UYVY_8;
    g_qcarcam_s_param(hndl, QCARCAM_PARAM_COLOR_FMT, &param_val);

    param_val.res_value.width = FRAME_WIDTH;
    param_val.res_value.height = FRAME_HEIGHT;
    param_val.res_value.fps = 30.0f;
    g_qcarcam_s_param(hndl, QCARCAM_PARAM_RESOLUTION, &param_val);

    qcarcam_buffers_t bufs;
    memset(&bufs, 0, sizeof(bufs));
    bufs.color_fmt = QCARCAM_FMT_UYVY_8;
    bufs.num_buffers = NUM_BUFFERS;
    bufs.buffers = g_buffers[cam_id];

    if (g_test_util_init_window_buffers && g_test_util_init_window && g_test_util_init) {
        if (!g_test_util_ctxt) {
            g_test_util_init(&g_test_util_ctxt, nullptr);
        }
        if (!g_test_util_window[cam_id]) {
            g_test_util_init_window(g_test_util_ctxt, &g_test_util_window[cam_id]);
        }
        g_test_util_init_window_buffers(g_test_util_ctxt, g_test_util_window[cam_id], &bufs);
        printf("[+] Allocated hardware ION buffers via test_util\n");
    } else {
        for (int i = 0; i < NUM_BUFFERS; ++i) {
            if (!g_buffer_ptrs[cam_id][i]) {
                posix_memalign(&g_buffer_ptrs[cam_id][i], 4096, FRAME_SIZE);
                memset(g_buffer_ptrs[cam_id][i], 0, FRAME_SIZE);
            }
            g_buffers[cam_id][i].num_planes = 1;
            g_buffers[cam_id][i].planes[0].width = FRAME_WIDTH;
            g_buffers[cam_id][i].planes[0].height = FRAME_HEIGHT;
            g_buffers[cam_id][i].planes[0].stride = FRAME_WIDTH * 2;
            g_buffers[cam_id][i].planes[0].size = FRAME_SIZE;
            g_buffers[cam_id][i].planes[0].p_buf = g_buffer_ptrs[cam_id][i];
        }
    }

    qcarcam_ret_t ret = g_qcarcam_s_buffers(hndl, &bufs);
    if (ret != QCARCAM_RET_OK) {
        fprintf(stderr, "[-] qcarcam_s_buffers failed on Camera %d: %d\n", cam_id, ret);
        g_qcarcam_close(hndl);
        return false;
    }

    ret = g_qcarcam_start(hndl);
    if (ret != QCARCAM_RET_OK) {
        fprintf(stderr, "[-] qcarcam_start failed on Camera %d: %d\n", cam_id, ret);
        g_qcarcam_close(hndl);
        return false;
    }

    g_camera_handles[cam_id] = hndl;
    printf("[+] Camera %d successfully streaming at 30 FPS (1920x1300 UYVY)\n", cam_id);
    return true;
}

static void* camera_capture_thread(void* arg) {
    int cam_id = (int)(intptr_t)arg;
    qcarcam_hndl_t hndl = g_camera_handles[cam_id];
    if (!hndl) return nullptr;

    printf("[+] Capture thread active for Camera %d\n", cam_id);
    int frame_count = 0;
    auto t_start = std::chrono::steady_clock::now();

    while (g_running.load()) {
        qcarcam_frame_info_t frame_info;
        memset(&frame_info, 0, sizeof(frame_info));

        qcarcam_ret_t ret = g_qcarcam_get_frame(hndl, &frame_info, 100000000ULL, 0);
        if (ret != QCARCAM_RET_OK) continue;

        if (frame_info.idx >= 0 && frame_info.idx < NUM_BUFFERS) {
            void* p_buf = g_buffers[cam_id][frame_info.idx].planes[0].p_buf;
            if (!p_buf && g_test_util_get_buf_ptr && g_test_util_window[cam_id]) {
                struct {
                    uint32_t buf_idx;
                    void* p_buf;
                } buf_req;
                buf_req.buf_idx = frame_info.idx;
                buf_req.p_buf = nullptr;
                g_test_util_get_buf_ptr(g_test_util_window[cam_id], &buf_req);
                p_buf = buf_req.p_buf;
            }
            if (!p_buf) {
                p_buf = g_buffer_ptrs[cam_id][frame_info.idx];
            }

            if (p_buf) {
                FrameHeader hdr;
                hdr.magic = MAGIC_HEADER;
                hdr.width = FRAME_WIDTH;
                hdr.height = FRAME_HEIGHT;
                hdr.format = 1; // UYVY
                hdr.data_size = FRAME_SIZE;
                hdr.timestamp = get_time_ns();

                pthread_mutex_lock(&g_client_mutex);
                for (int i = 0; i < MAX_CLIENTS; i++) {
                    int client = g_clients[i];
                    if (client >= 0 && g_client_cam[i] == cam_id) {
                        if (!write_all(client, &hdr, sizeof(hdr)) || !write_all(client, p_buf, FRAME_SIZE)) {
                            close(client);
                            g_clients[i] = -1;
                        }
                    }
                }
                pthread_mutex_unlock(&g_client_mutex);
            }
        }

        g_qcarcam_release_frame(hndl, frame_info.idx);

        frame_count++;
        if (frame_count % 90 == 0) {
            auto now = std::chrono::steady_clock::now();
            double elapsed = std::chrono::duration<double>(now - t_start).count();
            double fps = 90.0 / elapsed;
            printf("[Sidecar] Camera %d: %.2f FPS (Hardware Direct Stream)\n", cam_id, fps);
            t_start = now;
        }
    }

    return nullptr;
}

void signal_handler(int sig) {
    printf("[Sidecar] Signal %d received, exiting cleanly...\n", sig);
    g_running.store(false);
}

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    printf("====================================================\n");
    printf("  DiLink 5 Qualcomm QCarCam Hardware Sidecar (SA8155P)\n");
    printf("====================================================\n");

    for (int i = 0; i < MAX_CLIENTS; i++) {
        g_clients[i] = -1;
        g_client_cam[i] = 0;
    }

    if (!load_qcarcam_symbols()) {
        return 1;
    }

    qcarcam_init_t init_params;
    memset(&init_params, 0, sizeof(init_params));
    g_qcarcam_initialize(&init_params);

    // Initialize Camera 0 (Front) by default
    init_camera(0);

    pthread_t thread0;
    pthread_create(&thread0, nullptr, camera_capture_thread, (void*)(intptr_t)0);

    // Create UNIX socket for OverDrive
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCKET_NAME, strlen(SOCKET_NAME));
    socklen_t addr_len = sizeof(sa_family_t) + strlen(SOCKET_NAME) + 1;

    bind(server_fd, (struct sockaddr*)&addr, addr_len);
    listen(server_fd, 4);

    printf("[+] Listening on abstract socket @%s for OverDrive...\n", SOCKET_NAME);

    while (g_running.load()) {
        struct sockaddr_un client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client < 0) {
            if (!g_running.load()) break;
            continue;
        }

        pthread_mutex_lock(&g_client_mutex);
        int slot = -1;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i] < 0) {
                slot = i;
                g_clients[i] = client;
                g_client_cam[i] = 0;
                break;
            }
        }
        pthread_mutex_unlock(&g_client_mutex);

        if (slot >= 0) {
            printf("[+] OverDrive connected in slot %d\n", slot);
        } else {
            close(client);
        }
    }

    for (int c = 0; c < 4; c++) {
        if (g_camera_handles[c]) {
            g_qcarcam_stop(g_camera_handles[c]);
            g_qcarcam_close(g_camera_handles[c]);
        }
    }
    g_qcarcam_uninitialize();
    close(server_fd);

    printf("[+] QCarCam Hardware Sidecar exited cleanly.\n");
    return 0;
}
