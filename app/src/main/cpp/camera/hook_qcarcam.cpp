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

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define UYVY_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 2)
#define MAGIC_HEADER 0x44494C35
#define MAX_CLIENTS 8

struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t data_size;
    uint64_t timestamp;
};

static void* g_window = NULL;
static int g_server_fd = -1;
static int g_clients[MAX_CLIENTS];
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

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

static void* socket_server_thread(void* arg) {
    for (int i = 0; i < MAX_CLIENTS; i++) g_clients[i] = -1;

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
    printf("[Hook] Multi-client server listening on @dilink5_cam (max %d clients)\n", MAX_CLIENTS);

    while (1) {
        int client = accept(g_server_fd, NULL, NULL);
        if (client >= 0) {
            pthread_mutex_lock(&g_mutex);
            bool added = false;
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (g_clients[i] < 0) {
                    g_clients[i] = client;
                    printf("[Hook] Client connected in slot [%d] (fd=%d)\n", i, client);
                    added = true;
                    break;
                }
            }
            if (!added) {
                printf("[Hook] Max clients reached, dropping fd=%d\n", client);
                close(client);
            }
            pthread_mutex_unlock(&g_mutex);
        }
    }
    return NULL;
}

__attribute__((constructor))
void hook_init() {
    printf("[Hook] libhook_qcarcam.so multi-client initialized!\n");
    pthread_t th;
    pthread_create(&th, NULL, socket_server_thread, NULL);
    pthread_detach(th);
}

typedef int (*init_window_fn)(void* ctxt, void** pp_window);
static init_window_fn real_init_window = NULL;

extern "C" int _Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t(void* ctxt, void** pp_window) {
    if (!real_init_window) {
        real_init_window = (init_window_fn)dlsym(RTLD_NEXT, "_Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t");
    }
    int res = real_init_window ? real_init_window(ctxt, pp_window) : 0;
    if (pp_window && *pp_window) {
        g_window = *pp_window;
        printf("[Hook] Captured test_util window pointer: %p\n", g_window);
    }
    return res;
}

typedef int (*clock_gettime_fn)(clockid_t, struct timespec*);
static clock_gettime_fn real_clock_gettime = NULL;

extern "C" int clock_gettime(clockid_t clk_id, struct timespec *tp) {
    if (!real_clock_gettime) {
        real_clock_gettime = (clock_gettime_fn)dlsym(RTLD_NEXT, "clock_gettime");
    }
    int res = real_clock_gettime ? real_clock_gettime(clk_id, tp) : -1;

    if (g_window) {
        uint8_t* p_win = (uint8_t*)g_window;
        uint8_t* p_desc_base = (uint8_t*)(*(uint64_t*)(p_win + 0x78));
        if (p_desc_base) {
            static uint32_t s_idx = 0;
            int buf_idx = s_idx % 5;
            uint8_t* desc = p_desc_base + (buf_idx * 56);
            void* vaddr = *(void**)(desc + 0x10);
            if (vaddr) {
                s_idx++;
                FrameHeader header;
                header.magic = MAGIC_HEADER;
                header.width = FRAME_WIDTH;
                header.height = FRAME_HEIGHT;
                header.format = 1; // UYVY
                header.data_size = UYVY_SIZE;
                header.timestamp = 0;

                pthread_mutex_lock(&g_mutex);
                for (int i = 0; i < MAX_CLIENTS; i++) {
                    int cfd = g_clients[i];
                    if (cfd >= 0) {
                        if (!write_all(cfd, &header, sizeof(header)) ||
                            !write_all(cfd, vaddr, header.data_size)) {
                            close(cfd);
                            g_clients[i] = -1;
                        }
                    }
                }
                pthread_mutex_unlock(&g_mutex);
            }
        }
    }
    return res;
}
