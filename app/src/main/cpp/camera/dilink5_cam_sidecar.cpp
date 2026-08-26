// dilink5_cam_sidecar.cpp — Standalone native camera daemon for BYD DiLink 5.0 (Snapdragon SA8155P).
// Executes in Android's default linker namespace, giving direct access to Qualcomm's
// Automotive Imaging Subsystem (/vendor/lib64/libais_client.so) and streaming 30 FPS frames
// to OverDrive via high-performance abstract UNIX domain socket (\0dilink5_cam).

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
#include <atomic>

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define RAW_FRAME_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 2) // UYVY 4:2:2 (4,992,000 bytes)
#define MAGIC_HEADER 0x44494C35 // 'DIL5'
#define SOCKET_NAME "dilink5_cam"

struct FrameHeader {
    uint32_t magic;      // 0x44494C35
    uint32_t width;      // 1920
    uint32_t height;     // 1300
    uint32_t format;     // 1 = UYVY
    uint32_t data_size;  // 4992000
    uint64_t timestamp;  // ms
};

typedef int (*test_util_init_fn)(void** pp_ctxt, void* p_params);
typedef int (*test_util_parse_xml_fn)(const char* xml_file, void* p_inputs, unsigned int max_inputs);
typedef int (*test_util_init_window_fn)(void* ctxt, void** pp_window);
typedef int (*test_util_dump_window_buffer_fn)(void* ctxt, void* window, unsigned int idx, const char* filename);
typedef int (*test_util_deinit_fn)(void* ctxt);

typedef int (*qcarcam_init_fn)(void*);
typedef int (*qcarcam_uninit_fn)();

static std::atomic<bool> g_running{true};
static int g_client_fd = -1;

void signal_handler(int sig) {
    printf("[Sidecar] Signal %d received, shutting down...\n", sig);
    g_running.store(false);
}

int main(int argc, char** argv) {
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    printf("====================================================\n");
    printf("  DiLink 5 Native Camera Sidecar (Snapdragon 8155)  \n");
    printf("====================================================\n");

    void* h_ais = dlopen("/vendor/lib64/libais_client.so", RTLD_NOW);
    void* h_test = dlopen("/vendor/lib64/libais_test_util_proprietary.so", RTLD_NOW);

    if (!h_ais || !h_test) {
        printf("[-] Failed to load AIS libraries: %s\n", dlerror());
        return 1;
    }
    printf("[+] AIS libraries loaded successfully.\n");

    qcarcam_init_fn fn_q_init = (qcarcam_init_fn)dlsym(h_ais, "qcarcam_initialize");
    test_util_init_fn fn_init = (test_util_init_fn)dlsym(h_test, "_Z14test_util_initPP16test_util_ctxt_tP23test_util_ctxt_params_t");
    test_util_parse_xml_fn fn_parse_xml = (test_util_parse_xml_fn)dlsym(h_test, "_Z31test_util_parse_xml_config_filePKcP21test_util_xml_input_tj");
    test_util_init_window_fn fn_init_window = (test_util_init_window_fn)dlsym(h_test, "_Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t");
    test_util_dump_window_buffer_fn fn_dump = (test_util_dump_window_buffer_fn)dlsym(h_test, "_Z28test_util_dump_window_bufferP16test_util_ctxt_tP18test_util_window_tjPKc");
    test_util_deinit_fn fn_deinit = (test_util_deinit_fn)dlsym(h_test, "_Z16test_util_deinitP16test_util_ctxt_t");

    if (fn_q_init) fn_q_init(NULL);

    void* ctxt = NULL;
    char ctxt_params[256];
    memset(ctxt_params, 0, sizeof(ctxt_params));
    if (fn_init) fn_init(&ctxt, ctxt_params);

    if (!ctxt) {
        printf("[-] test_util_init failed!\n");
        return 1;
    }

    char xml_inputs[2048];
    memset(xml_inputs, 0, sizeof(xml_inputs));
    if (fn_parse_xml) fn_parse_xml("/vendor/bin/1cam.xml", xml_inputs, 1);

    void* window = NULL;
    if (fn_init_window) fn_init_window(ctxt, &window);
    printf("[+] Hardware camera stream initialized (window=%p).\n", window);

    // Setup abstract UNIX domain socket server
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        printf("[-] Socket creation failed: %s\n", strerror(errno));
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, SOCKET_NAME, sizeof(addr.sun_path) - 2);
    socklen_t addr_len = sizeof(addr.sun_family) + 1 + strlen(SOCKET_NAME);

    if (bind(server_fd, (struct sockaddr*)&addr, addr_len) < 0) {
        printf("[-] Socket bind failed: %s\n", strerror(errno));
        return 1;
    }

    listen(server_fd, 2);
    printf("[+] Sidecar IPC server listening on abstract socket @%s\n", SOCKET_NAME);

    const char* tmp_dump = "/data/local/tmp/sidecar_feed.raw";
    uint8_t* raw_buffer = (uint8_t*)malloc(RAW_FRAME_SIZE);

    while (g_running.load()) {
        struct sockaddr_un client_addr;
        socklen_t client_len = sizeof(client_addr);
        printf("[+] Waiting for OverDrive client connection...\n");
        int client = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client < 0) {
            if (!g_running.load()) break;
            continue;
        }

        printf("[+] OverDrive client connected!\n");
        g_client_fd = client;

        while (g_running.load() && g_client_fd >= 0) {
            if (ctxt && window && fn_dump) {
                if (fn_dump(ctxt, window, 0, tmp_dump) == 0) {
                    FILE* f = fopen(tmp_dump, "rb");
                    if (f) {
                        size_t read_bytes = fread(raw_buffer, 1, RAW_FRAME_SIZE, f);
                        fclose(f);

                        if (read_bytes == RAW_FRAME_SIZE) {
                            FrameHeader header;
                            header.magic = MAGIC_HEADER;
                            header.width = FRAME_WIDTH;
                            header.height = FRAME_HEIGHT;
                            header.format = 1;
                            header.data_size = RAW_FRAME_SIZE;
                            header.timestamp = 0;

                            // Send header
                            if (send(client, &header, sizeof(header), MSG_NOSIGNAL) <= 0) {
                                printf("[-] Client disconnected.\n");
                                break;
                            }
                            // Send payload
                            if (send(client, raw_buffer, RAW_FRAME_SIZE, MSG_NOSIGNAL) <= 0) {
                                printf("[-] Client disconnected.\n");
                                break;
                            }
                        }
                    }
                }
            }
            usleep(33333); // 30.0 FPS
        }

        close(client);
        g_client_fd = -1;
    }

    free(raw_buffer);
    close(server_fd);
    if (ctxt && fn_deinit) fn_deinit(ctxt);

    printf("[+] Sidecar exited cleanly.\n");
    return 0;
}
