// dilink5_cam_sidecar.cpp — Standalone native camera daemon for BYD DiLink 5.0 (Snapdragon SA8155P).
// Uses Qualcomm's proprietary test_util and AIS subsystem to capture 30 FPS hardware camera frames
// and stream them into OverDrive over high-performance abstract UNIX domain socket (\0dilink5_cam).

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
#define FRAME_HEIGHT 1080
#define NV12_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 3 / 2)
#define MAGIC_HEADER 0x44494C35 // 'DIL5'
#define SOCKET_NAME "dilink5_cam"

struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format;    // 2 = NV12
    uint32_t data_size;
    uint64_t timestamp;
};

typedef int (*test_util_init_fn)(void** pp_ctxt, void* p_params);
typedef int (*test_util_parse_xml_fn)(const char* xml_file, void* p_inputs, unsigned int max_inputs);
typedef int (*test_util_init_window_fn)(void* ctxt, void** pp_window);
typedef int (*test_util_dump_window_buffer_fn)(void* ctxt, void* window, unsigned int idx, const char* filename);
typedef int (*test_util_deinit_fn)(void* ctxt);

typedef int (*qcarcam_init_fn)(void*);

static std::atomic<bool> g_running{true};

void signal_handler(int sig) {
    printf("[Sidecar] Signal %d received, exiting...\n", sig);
    g_running.store(false);
}

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    printf("====================================================\n");
    printf("  DiLink 5 Native Camera Sidecar (Snapdragon SA8155P)\n");
    printf("====================================================\n");

    void* h_test_util = dlopen("/vendor/lib64/libais_test_util_proprietary.so", RTLD_NOW);
    void* h_ais = dlopen("/vendor/lib64/libais_client.so", RTLD_NOW);

    if (!h_test_util || !h_ais) {
        printf("[-] Failed to load AIS libraries: %s\n", dlerror());
        return 1;
    }

    test_util_init_fn fn_init = (test_util_init_fn)dlsym(h_test_util, "_Z14test_util_initPP16test_util_ctxt_tP23test_util_ctxt_params_t");
    test_util_parse_xml_fn fn_parse_xml = (test_util_parse_xml_fn)dlsym(h_test_util, "_Z31test_util_parse_xml_config_filePKcP21test_util_xml_input_tj");
    test_util_init_window_fn fn_init_window = (test_util_init_window_fn)dlsym(h_test_util, "_Z21test_util_init_windowP16test_util_ctxt_tPP18test_util_window_t");
    test_util_dump_window_buffer_fn fn_dump = (test_util_dump_window_buffer_fn)dlsym(h_test_util, "_Z28test_util_dump_window_bufferP16test_util_ctxt_tP18test_util_window_tjPKc");
    test_util_deinit_fn fn_deinit = (test_util_deinit_fn)dlsym(h_test_util, "_Z16test_util_deinitP16test_util_ctxt_t");

    qcarcam_init_fn fn_q_init = (qcarcam_init_fn)dlsym(h_ais, "qcarcam_initialize");
    if (fn_q_init) fn_q_init(NULL);

    void* ctxt = NULL;
    char ctxt_params[256];
    memset(ctxt_params, 0, sizeof(ctxt_params));
    if (fn_init) fn_init(&ctxt, ctxt_params);

    if (!ctxt) {
        printf("[-] Failed to create Qualcomm AIS context.\n");
        return 1;
    }

    char xml_inputs[2048];
    memset(xml_inputs, 0, sizeof(xml_inputs));
    if (fn_parse_xml) {
        fn_parse_xml("/vendor/bin/1cam.xml", xml_inputs, 1);
    }

    void* window = NULL;
    if (fn_init_window) {
        fn_init_window(ctxt, &window);
    }

    // Setup abstract UNIX socket server
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        printf("[-] Socket creation failed: %s\n", strerror(errno));
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCKET_NAME, strlen(SOCKET_NAME));
    socklen_t addr_len = sizeof(sa_family_t) + strlen(SOCKET_NAME) + 1;

    if (bind(server_fd, (struct sockaddr*)&addr, addr_len) < 0) {
        printf("[-] Socket bind failed: %s\n", strerror(errno));
        close(server_fd);
        return 1;
    }

    listen(server_fd, 2);
    printf("[+] DiLink 5 Camera Sidecar listening on abstract socket @%s\n", SOCKET_NAME);

    const char* tmp_frame_path = "/data/local/tmp/dilink5_live_frame.raw";
    uint8_t* frame_buf = (uint8_t*)malloc(NV12_SIZE);
    memset(frame_buf, 0x80, NV12_SIZE);

    while (g_running.load()) {
        struct sockaddr_un client_addr;
        socklen_t client_len = sizeof(client_addr);
        printf("[+] Waiting for OverDrive client connection...\n");
        int client = accept(server_fd, (struct sockaddr*)&client_addr, &client_len);
        if (client < 0) {
            if (!g_running.load()) break;
            continue;
        }

        printf("[+] OverDrive client connected! Starting live stream...\n");

        while (g_running.load()) {
            if (window && fn_dump) {
                fn_dump(ctxt, window, 0, tmp_frame_path);
                FILE* f = fopen(tmp_frame_path, "rb");
                if (f) {
                    size_t read_bytes = fread(frame_buf, 1, NV12_SIZE, f);
                    fclose(f);
                }
            }

            FrameHeader header;
            header.magic = MAGIC_HEADER;
            header.width = FRAME_WIDTH;
            header.height = FRAME_HEIGHT;
            header.format = 2; // NV12
            header.data_size = NV12_SIZE;
            header.timestamp = 0;

            if (send(client, &header, sizeof(header), MSG_NOSIGNAL) <= 0) {
                printf("[-] Client disconnected.\n");
                break;
            }

            if (send(client, frame_buf, NV12_SIZE, MSG_NOSIGNAL) <= 0) {
                printf("[-] Client disconnected.\n");
                break;
            }

            usleep(33333); // ~30 FPS
        }

        close(client);
    }

    free(frame_buf);
    close(server_fd);
    if (ctxt && fn_deinit) fn_deinit(ctxt);
    dlclose(h_ais);
    dlclose(h_test_util);

    return 0;
}
