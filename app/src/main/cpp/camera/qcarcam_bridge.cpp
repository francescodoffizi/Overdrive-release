// qcarcam_bridge.cpp — BYD DiLink 5.0 (Snapdragon SA8155P) Sidecar Bridge.
// Connects to the native dilink5_cam_sidecar daemon via high-speed abstract UNIX socket (@dilink5_cam),
// receives hardware frames, and posts them to Android's ANativeWindow / Surface.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <atomic>
#include <mutex>

#define TAG "QCarCamBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SOCKET_NAME "dilink5_cam"
#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define MAX_RAW_FRAME_SIZE (FRAME_WIDTH * FRAME_HEIGHT * 2)
#define MAGIC_HEADER 0x44494C35

struct FrameHeader {
    uint32_t magic;
    uint32_t width;
    uint32_t height;
    uint32_t format; // 1 = UYVY, 2 = NV12
    uint32_t data_size;
    uint64_t timestamp;
};

namespace {

std::atomic<bool> g_streaming{false};
pthread_t g_streamThread = 0;
ANativeWindow* g_nativeWindow = nullptr;
std::mutex g_winMutex;

// Convert NV12 to RGBA8888 for Surface posting
void convert_uyvy_to_rgba(const uint8_t* uyvy, int width, int height, uint32_t* dst_rgba, int dst_stride) {
    for (int y = 0; y < height; ++y) {
        const uint8_t* src_row = uyvy + y * width * 2;
        uint32_t* dst_row = dst_rgba + y * (dst_stride > 0 ? dst_stride : width);
        for (int x = 0; x < width; x += 2) {
            uint8_t u  = src_row[0];
            uint8_t y0 = src_row[1];
            uint8_t v  = src_row[2];
            uint8_t y1 = src_row[3];
            src_row += 4;

            int c0 = (int)y0 - 16;
            int c1 = (int)y1 - 16;
            int d = (int)u - 128;
            int e = (int)v - 128;

            int r0 = (298 * c0 + 409 * e + 128) >> 8;
            int g0 = (298 * c0 - 100 * d - 208 * e + 128) >> 8;
            int b0 = (298 * c0 + 516 * d + 128) >> 8;

            int r1 = (298 * c1 + 409 * e + 128) >> 8;
            int g1 = (298 * c1 - 100 * d - 208 * e + 128) >> 8;
            int b1 = (298 * c1 + 516 * d + 128) >> 8;

            r0 = r0 < 0 ? 0 : (r0 > 255 ? 255 : r0);
            g0 = g0 < 0 ? 0 : (g0 > 255 ? 255 : g0);
            b0 = b0 < 0 ? 0 : (b0 > 255 ? 255 : b0);

            r1 = r1 < 0 ? 0 : (r1 > 255 ? 255 : r1);
            g1 = g1 < 0 ? 0 : (g1 > 255 ? 255 : g1);
            b1 = b1 < 0 ? 0 : (b1 > 255 ? 255 : b1);

            dst_row[x]     = (uint32_t)(0xFF000000 | (b0 << 16) | (g0 << 8) | r0);
            dst_row[x + 1] = (uint32_t)(0xFF000000 | (b1 << 16) | (g1 << 8) | r1);
        }
    }
}

void convert_nv12_to_rgba(const uint8_t* nv12, int width, int height, uint32_t* dst_rgba, int dst_stride) {
    const uint8_t* y_plane = nv12;
    const uint8_t* uv_plane = nv12 + (width * height);

    for (int y = 0; y < height; y++) {
        uint32_t* row = dst_rgba + y * dst_stride;
        for (int x = 0; x < width; x++) {
            int y_val = y_plane[y * width + x];
            int uv_idx = (y / 2) * width + (x & ~1);
            float u = (float)uv_plane[uv_idx] - 128.0f;
            float v = (float)uv_plane[uv_idx + 1] - 128.0f;

            int r = (int)(y_val + 1.402f * v);
            int g = (int)(y_val - 0.344136f * u - 0.714136f * v);
            int b = (int)(y_val + 1.772f * u);

            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);

            row[x] = (uint32_t)(0xFF000000 | (b << 16) | (g << 8) | r);
        }
    }
}

ssize_t read_all(int fd, void* buf, size_t count) {
    size_t total = 0;
    uint8_t* ptr = (uint8_t*)buf;
    while (total < count) {
        ssize_t r = recv(fd, ptr + total, count - total, 0);
        if (r <= 0) return -1;
        total += r;
    }
    return total;
}

void* streamClientLoop(void* arg) {
    LOGI("DiLink 5 UNIX Socket Client thread started.");

    uint8_t* frameBuffer = (uint8_t*)malloc(MAX_RAW_FRAME_SIZE);

    while (g_streaming.load()) {
        int sock = socket(AF_UNIX, SOCK_STREAM, 0);
        if (sock < 0) {
            usleep(500000);
            continue;
        }

        struct sockaddr_un serv_addr;
        memset(&serv_addr, 0, sizeof(serv_addr));
        serv_addr.sun_family = AF_UNIX;
        serv_addr.sun_path[0] = '\0';
        memcpy(serv_addr.sun_path + 1, SOCKET_NAME, strlen(SOCKET_NAME));
        socklen_t addr_len = sizeof(sa_family_t) + strlen(SOCKET_NAME) + 1;

        if (connect(sock, (struct sockaddr*)&serv_addr, addr_len) < 0) {
            close(sock);
            usleep(300000); // retry connect
            continue;
        }

        LOGI("Connected to DiLink 5 Camera Sidecar at abstract socket @%s", SOCKET_NAME);

        while (g_streaming.load()) {
            FrameHeader header;
            if (read_all(sock, &header, sizeof(header)) != sizeof(header)) {
                LOGW("Sidecar disconnected (header read failed)");
                break;
            }

            if (header.magic != MAGIC_HEADER || header.data_size > MAX_RAW_FRAME_SIZE) {
                LOGE("Invalid frame magic: 0x%08X", header.magic);
                break;
            }

            if (read_all(sock, frameBuffer, header.data_size) != (ssize_t)header.data_size) {
                LOGW("Sidecar disconnected (payload read failed)");
                break;
            }

            std::lock_guard<std::mutex> lock(g_winMutex);
            if (g_nativeWindow) {
                ANativeWindow_Buffer winBuffer;
                if (ANativeWindow_lock(g_nativeWindow, &winBuffer, nullptr) == 0) {
                    if (header.format == 1) {
                        convert_uyvy_to_rgba(frameBuffer, header.width, header.height, (uint32_t*)winBuffer.bits, winBuffer.stride);
                    } else {
                        convert_nv12_to_rgba(frameBuffer, header.width, header.height, (uint32_t*)winBuffer.bits, winBuffer.stride);
                    }
                    ANativeWindow_unlockAndPost(g_nativeWindow);
                }
            }
        }

        close(sock);
    }

    free(frameBuffer);
    LOGI("DiLink 5 UNIX Socket Client thread terminated.");
    return nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeIsSupported(
    JNIEnv* env, jclass clazz) {
    return (access("/vendor/lib64/libais_client.so", F_OK) == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeInit(
    JNIEnv* env, jobject thiz, jint inputId) {
    return 1;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStart(
    JNIEnv* env, jobject thiz, jlong handle) {
    g_streaming.store(true);
    if (g_streamThread == 0) {
        pthread_create(&g_streamThread, nullptr, streamClientLoop, nullptr);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStartSurface(
    JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(g_winMutex);
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
    if (surface) {
        g_nativeWindow = ANativeWindow_fromSurface(env, surface);
        if (g_nativeWindow) {
            ANativeWindow_setBuffersGeometry(g_nativeWindow, FRAME_WIDTH, FRAME_HEIGHT, WINDOW_FORMAT_RGBA_8888);
            LOGI("ANativeWindow configured: %dx%d RGBA8888", FRAME_WIDTH, FRAME_HEIGHT);
        }
    }
    g_streaming.store(true);
    if (g_streamThread == 0) {
        pthread_create(&g_streamThread, nullptr, streamClientLoop, nullptr);
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStop(
    JNIEnv* env, jobject thiz, jlong handle) {
    g_streaming.store(false);
    if (g_streamThread != 0) {
        pthread_join(g_streamThread, nullptr);
        g_streamThread = 0;
    }
    std::lock_guard<std::mutex> lock(g_winMutex);
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeRelease(
    JNIEnv* env, jobject thiz, jlong handle) {
    g_streaming.store(false);
    if (g_streamThread != 0) {
        pthread_join(g_streamThread, nullptr);
        g_streamThread = 0;
    }
    std::lock_guard<std::mutex> lock(g_winMutex);
    if (g_nativeWindow) {
        ANativeWindow_release(g_nativeWindow);
        g_nativeWindow = nullptr;
    }
}

} // extern "C"
