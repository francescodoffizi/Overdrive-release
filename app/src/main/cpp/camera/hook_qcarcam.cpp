#define _GNU_SOURCE 1
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <pthread.h>
#include <stdint.h>
#include <time.h>

#define SOCKET_NAME "dilink5_cam"
#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define UYVY_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 2)
#define MAGIC_HEADER 0x44494C35

struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format; // 1 = UYVY
    uint32_t data_size;
    uint64_t timestamp;
};

// Disassembled exact 64-bit struct from /vendor/lib64/libais_test_util_proprietary.so (ARM64 address 0x7764)
typedef struct {
    uint32_t buf_idx; // offset 0x00
    uint32_t padding; // offset 0x04
    void*    vaddr;   // offset 0x08 (64-bit DMA virtual address pointer)
    uint32_t size;    // offset 0x10 (total buffer size or stride)
    uint32_t stride;  // offset 0x14 (bytes per line)
} test_util_buf_ptr_t;

typedef void* (*test_util_init_window_fn)(int, int, int, int, int, int, int);
typedef void (*test_util_get_buf_ptr_fn)(void*, test_util_buf_ptr_t*);

static test_util_init_window_fn real_init_window = NULL;
static test_util_get_buf_ptr_fn real_get_buf_ptr = NULL;
static void* g_window = NULL;
static int g_server_fd = -1;
static int g_client_fd = -1;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

static void* socket_server_thread(void* arg) {
    (void)arg;
    g_server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_server_fd < 0) return NULL;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strcpy(addr.sun_path + 1, SOCKET_NAME);
    socklen_t len = sizeof(sa_family_t) + strlen(SOCKET_NAME) + 1;

    if (bind(g_server_fd, (struct sockaddr*)&addr, len) < 0) {
        close(g_server_fd);
        return NULL;
    }

    if (listen(g_server_fd, 4) < 0) {
        close(g_server_fd);
        return NULL;
    }

    while (1) {
        int client = accept(g_server_fd, NULL, NULL);
        if (client >= 0) {
            pthread_mutex_lock(&g_mutex);
            if (g_client_fd >= 0) close(g_client_fd);
            g_client_fd = client;
            pthread_mutex_unlock(&g_mutex);
        }
    }
    return NULL;
}

__attribute__((constructor))
void hook_init() {
    void* handle = dlopen("/vendor/lib64/libais_test_util_proprietary.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle) {
        real_init_window = (test_util_init_window_fn)dlsym(handle, "_Z22test_util_init_windowiiiiiii");
        real_get_buf_ptr = (test_util_get_buf_ptr_fn)dlsym(handle, "_Z21test_util_get_buf_ptrP18test_util_window_tP19test_util_buf_ptr_t");
    }

    pthread_t th;
    pthread_create(&th, NULL, socket_server_thread, NULL);
    pthread_detach(th);
}

extern "C" void* _Z22test_util_init_windowiiiiiii(int a, int b, int c, int d, int e, int f, int g) {
    if (!real_init_window) {
        void* handle = dlopen("/vendor/lib64/libais_test_util_proprietary.so", RTLD_NOW | RTLD_GLOBAL);
        if (handle) {
            real_init_window = (test_util_init_window_fn)dlsym(handle, "_Z22test_util_init_windowiiiiiii");
        }
    }
    void* w = real_init_window ? real_init_window(a, b, c, d, e, f, g) : NULL;
    g_window = w;
    return w;
}

typedef int (*clock_gettime_fn)(clockid_t, struct timespec*);
static clock_gettime_fn real_clock_gettime = NULL;

extern "C" int clock_gettime(clockid_t clk_id, struct timespec *tp) {
    if (!real_clock_gettime) {
        real_clock_gettime = (clock_gettime_fn)dlsym(RTLD_NEXT, "clock_gettime");
    }
    int res = real_clock_gettime ? real_clock_gettime(clk_id, tp) : -1;

    if (g_window && g_client_fd >= 0 && real_get_buf_ptr) {
        static uint32_t s_idx = 0;
        test_util_buf_ptr_t ptr;
        memset(&ptr, 0, sizeof(ptr));
        ptr.buf_idx = s_idx % 5;
        real_get_buf_ptr(g_window, &ptr);
        if (ptr.vaddr) {
            s_idx++;
            FrameHeader header;
            header.magic = MAGIC_HEADER;
            header.width = FRAME_WIDTH;
            header.height = FRAME_HEIGHT;
            header.format = 1; // UYVY
            header.data_size = UYVY_SIZE;
            header.timestamp = 0;

            if (send(g_client_fd, &header, sizeof(header), MSG_NOSIGNAL) <= 0) {
                close(g_client_fd);
                g_client_fd = -1;
            } else if (send(g_client_fd, ptr.vaddr, header.data_size, MSG_NOSIGNAL) <= 0) {
                close(g_client_fd);
                g_client_fd = -1;
            }
        }
    }
    return res;
}
