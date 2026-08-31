// NativeQCarCamStreamer.cpp — Direct C++ Qualcomm QCarCam / AIS Hardware Driver
// Connects directly to /vendor/lib64/libais_client.so (Qualcomm SA8155P Automotive Camera System)
// Eliminates qcarcam_test process overhead, socket IPC latency, and delivers 30 FPS hardware camera feed.

#include "qcarcam.h"
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <arm_neon.h>
#include <atomic>
#include <mutex>
#include <chrono>

#define TAG "NativeQCarCam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define DEFAULT_FRAME_WIDTH  1920
#define DEFAULT_FRAME_HEIGHT 1300
#define NUM_BUFFERS          4

namespace {

// QCarCam Function Pointer Definitions
typedef qcarcam_ret_t (*pfn_qcarcam_initialize)(qcarcam_init_t* p_init_params);
typedef qcarcam_ret_t (*pfn_qcarcam_uninitialize)(void);
typedef qcarcam_ret_t (*pfn_qcarcam_query_inputs)(qcarcam_input_identifier_t* p_inputs, uint32_t size, uint32_t* p_ret_size);
typedef qcarcam_hndl_t (*pfn_qcarcam_open)(qcarcam_input_t input_id);
typedef qcarcam_ret_t (*pfn_qcarcam_close)(qcarcam_hndl_t hndl);
typedef qcarcam_ret_t (*pfn_qcarcam_g_param)(qcarcam_hndl_t hndl, qcarcam_param_t param, qcarcam_param_value_t* p_value);
typedef qcarcam_ret_t (*pfn_qcarcam_s_param)(qcarcam_hndl_t hndl, qcarcam_param_t param, const qcarcam_param_value_t* p_value);
typedef qcarcam_ret_t (*pfn_qcarcam_s_buffers)(qcarcam_hndl_t hndl, qcarcam_buffers_t* p_buffers);
typedef qcarcam_ret_t (*pfn_qcarcam_start)(qcarcam_hndl_t hndl);
typedef qcarcam_ret_t (*pfn_qcarcam_stop)(qcarcam_hndl_t hndl);
typedef qcarcam_ret_t (*pfn_qcarcam_get_frame)(qcarcam_hndl_t hndl, qcarcam_frame_info_t* p_frame_info, uint64_t timeout, uint32_t flags);
typedef qcarcam_ret_t (*pfn_qcarcam_release_frame)(qcarcam_hndl_t hndl, uint32_t idx);

struct QCarCamSymbols {
    void* libHandle = nullptr;
    pfn_qcarcam_initialize   initialize = nullptr;
    pfn_qcarcam_uninitialize uninitialize = nullptr;
    pfn_qcarcam_query_inputs query_inputs = nullptr;
    pfn_qcarcam_open         open = nullptr;
    pfn_qcarcam_close        close = nullptr;
    pfn_qcarcam_g_param      g_param = nullptr;
    pfn_qcarcam_s_param      s_param = nullptr;
    pfn_qcarcam_s_buffers    s_buffers = nullptr;
    pfn_qcarcam_start        start = nullptr;
    pfn_qcarcam_stop         stop = nullptr;
    pfn_qcarcam_get_frame    get_frame = nullptr;
    pfn_qcarcam_release_frame release_frame = nullptr;

    bool load() {
        if (libHandle) return true;

        // 1. Try SPHAL loader first (bypasses Android Bionic classloader-namespace restrictions on vendor HAL libraries)
        void* vndk = dlopen("libvndksupport.so", RTLD_NOW);
        if (vndk) {
            typedef void* (*pfn_android_load_sphal_library)(const char* name, int flag);
            pfn_android_load_sphal_library load_sphal = 
                (pfn_android_load_sphal_library)dlsym(vndk, "android_load_sphal_library");
            if (load_sphal) {
                libHandle = load_sphal("libais_client.so", RTLD_NOW);
                if (!libHandle) {
                    libHandle = load_sphal("/vendor/lib64/libais_client.so", RTLD_NOW);
                }
                if (libHandle) {
                    LOGI("Successfully loaded libais_client.so via android_load_sphal_library");
                }
            }
        }

        // 2. Direct fallback
        if (!libHandle) {
            libHandle = dlopen("/vendor/lib64/libais_client.so", RTLD_NOW);
        }
        if (!libHandle) {
            libHandle = dlopen("libais_client.so", RTLD_NOW);
        }
        if (!libHandle) {
            LOGE("Failed to load libais_client.so: %s", dlerror());
            return false;
        }

        initialize   = (pfn_qcarcam_initialize)dlsym(libHandle, "qcarcam_initialize");
        uninitialize = (pfn_qcarcam_uninitialize)dlsym(libHandle, "qcarcam_uninitialize");
        query_inputs = (pfn_qcarcam_query_inputs)dlsym(libHandle, "qcarcam_query_inputs");
        open         = (pfn_qcarcam_open)dlsym(libHandle, "qcarcam_open");
        close        = (pfn_qcarcam_close)dlsym(libHandle, "qcarcam_close");
        g_param      = (pfn_qcarcam_g_param)dlsym(libHandle, "qcarcam_g_param");
        s_param      = (pfn_qcarcam_s_param)dlsym(libHandle, "qcarcam_s_param");
        s_buffers    = (pfn_qcarcam_s_buffers)dlsym(libHandle, "qcarcam_s_buffers");
        start        = (pfn_qcarcam_start)dlsym(libHandle, "qcarcam_start");
        stop         = (pfn_qcarcam_stop)dlsym(libHandle, "qcarcam_stop");
        get_frame    = (pfn_qcarcam_get_frame)dlsym(libHandle, "qcarcam_get_frame");
        release_frame = (pfn_qcarcam_release_frame)dlsym(libHandle, "qcarcam_release_frame");

        if (!initialize || !open || !s_buffers || !start || !get_frame || !release_frame) {
            LOGE("Missing essential QCarCam API symbols in libais_client.so");
            dlclose(libHandle);
            libHandle = nullptr;
            return false;
        }

        LOGI("Successfully resolved all Qualcomm QCarCam API symbols from libais_client.so");
        return true;
    }

    void unload() {
        if (libHandle) {
            dlclose(libHandle);
            libHandle = nullptr;
        }
    }
};

static QCarCamSymbols g_qcarcam;
static std::mutex g_stateMutex;
static std::atomic<bool> g_isStreaming{false};
static pthread_t g_captureThread = 0;
static qcarcam_hndl_t g_hndl = nullptr;

static ANativeWindow* g_surfaceWindow = nullptr;
static std::mutex g_windowMutex;

static int g_width = DEFAULT_FRAME_WIDTH;
static int g_height = DEFAULT_FRAME_HEIGHT;
static int g_cameraId = 0;

static qcarcam_buffer_t g_buffers[NUM_BUFFERS];
static void* g_bufferPointers[NUM_BUFFERS];

// High-speed ARM NEON SIMD UYVY to RGBA converter
static inline void neon_uyvy_to_rgba(const uint8_t* __restrict__ uyvy, int width, int height, uint32_t* __restrict__ dst_rgba, int dst_stride) {
    int stride = (dst_stride > 0 ? dst_stride : width);
    const int16x8_t c_128 = vdupq_n_s16(128);
    const int32x4_t c_512_32 = vdupq_n_s32(512);

    for (int y = 0; y < height; ++y) {
        const uint8_t* src_row = uyvy + y * width * 2;
        uint32_t* dst_row = dst_rgba + y * stride;
        int x = 0;

        for (; x <= width - 16; x += 16) {
            uint8x8x4_t uyvy_8 = vld4_u8(src_row);
            src_row += 32;

            int16x8_t u_16 = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[0])), c_128);
            int16x8_t v_16 = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[2])), c_128);

            int32x4_t v_lo_32 = vmovl_s16(vget_low_s16(v_16));
            int32x4_t v_hi_32 = vmovl_s16(vget_high_s16(v_16));
            int32x4_t u_lo_32 = vmovl_s16(vget_low_s16(u_16));
            int32x4_t u_hi_32 = vmovl_s16(vget_high_s16(u_16));

            int16x4_t r_delta_lo = vshrn_n_s32(vaddq_s32(vmulq_n_s32(v_lo_32, 1436), c_512_32), 10);
            int16x4_t r_delta_hi = vshrn_n_s32(vaddq_s32(vmulq_n_s32(v_hi_32, 1436), c_512_32), 10);
            int16x8_t r_delta = vcombine_s16(r_delta_lo, r_delta_hi);

            int16x4_t g_delta_lo = vshrn_n_s32(vaddq_s32(vaddq_s32(vmulq_n_s32(u_lo_32, 352), vmulq_n_s32(v_lo_32, 731)), c_512_32), 10);
            int16x4_t g_delta_hi = vshrn_n_s32(vaddq_s32(vaddq_s32(vmulq_n_s32(u_hi_32, 352), vmulq_n_s32(v_hi_32, 731)), c_512_32), 10);
            int16x8_t g_delta = vcombine_s16(g_delta_lo, g_delta_hi);

            int16x4_t b_delta_lo = vshrn_n_s32(vaddq_s32(vmulq_n_s32(u_lo_32, 1815), c_512_32), 10);
            int16x4_t b_delta_hi = vshrn_n_s32(vaddq_s32(vmulq_n_s32(u_hi_32, 1815), c_512_32), 10);
            int16x8_t b_delta = vcombine_s16(b_delta_lo, b_delta_hi);

            int16x8_t y0_16 = vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[1]));
            uint8x8_t r0_8 = vqmovun_s16(vaddq_s16(y0_16, r_delta));
            uint8x8_t g0_8 = vqmovun_s16(vsubq_s16(y0_16, g_delta));
            uint8x8_t b0_8 = vqmovun_s16(vaddq_s16(y0_16, b_delta));

            int16x8_t y1_16 = vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[3]));
            uint8x8_t r1_8 = vqmovun_s16(vaddq_s16(y1_16, r_delta));
            uint8x8_t g1_8 = vqmovun_s16(vsubq_s16(y1_16, g_delta));
            uint8x8_t b1_8 = vqmovun_s16(vaddq_s16(y1_16, b_delta));

            uint8x8x2_t r_zip = vzip_u8(r0_8, r1_8);
            uint8x8x2_t g_zip = vzip_u8(g0_8, g1_8);
            uint8x8x2_t b_zip = vzip_u8(b0_8, b1_8);
            uint8x8_t a_8 = vdup_n_u8(255);

            uint8x8x4_t rgba_lo = { r_zip.val[0], g_zip.val[0], b_zip.val[0], a_8 };
            uint8x8x4_t rgba_hi = { r_zip.val[1], g_zip.val[1], b_zip.val[1], a_8 };

            vst4_u8(reinterpret_cast<uint8_t*>(dst_row), rgba_lo);
            vst4_u8(reinterpret_cast<uint8_t*>(dst_row + 8), rgba_hi);
            dst_row += 16;
        }
    }
}

// Dedicated hardware stream capture loop
static void* captureLoop(void* arg) {
    LOGI("Native QCarCam Direct Stream Thread Started (Cam %d, %dx%d)", g_cameraId, g_width, g_height);

    int frame_count = 0;
    auto t_start = std::chrono::steady_clock::now();

    while (g_isStreaming.load()) {
        qcarcam_frame_info_t frame_info;
        memset(&frame_info, 0, sizeof(frame_info));

        // 100ms timeout per frame
        qcarcam_ret_t ret = g_qcarcam.get_frame(g_hndl, &frame_info, 100000000ULL, 0);
        if (ret != QCARCAM_RET_OK) {
            if (ret != QCARCAM_RET_TIMEOUT) {
                LOGW("qcarcam_get_frame returned status %d", ret);
            }
            continue;
        }

        if (frame_info.idx < 0 || frame_info.idx >= NUM_BUFFERS) {
            LOGW("Invalid buffer index %d received from ISP", frame_info.idx);
            continue;
        }

        const uint8_t* frame_data = static_cast<const uint8_t*>(g_bufferPointers[frame_info.idx]);

        // Render directly to Surface if connected
        {
            std::lock_guard<std::mutex> lock(g_windowMutex);
            if (g_surfaceWindow && frame_data) {
                ANativeWindow_Buffer win_buf;
                if (ANativeWindow_lock(g_surfaceWindow, &win_buf, nullptr) == 0) {
                    neon_uyvy_to_rgba(frame_data, g_width, g_height, static_cast<uint32_t*>(win_buf.bits), win_buf.stride);
                    ANativeWindow_unlockAndPost(g_surfaceWindow);
                }
            }
        }

        // Release frame buffer immediately back to Qualcomm ISP pool
        g_qcarcam.release_frame(g_hndl, frame_info.idx);

        frame_count++;
        if (frame_count % 90 == 0) {
            auto now = std::chrono::steady_clock::now();
            double elapsed = std::chrono::duration<double>(now - t_start).count();
            double fps = 90.0 / elapsed;
            LOGI("Native QCarCam Direct Stream FPS: %.2f FPS (Seq: %u, Buffer: %d)", fps, frame_info.seq_no, frame_info.idx);
            t_start = now;
        }
    }

    LOGI("Native QCarCam Direct Stream Thread Stopped");
    return nullptr;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_NativeQCarCamEngine_nativeIsSupported(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_stateMutex);
    return g_qcarcam.load() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_NativeQCarCamEngine_nativeStart(
    JNIEnv* env, jclass clazz, jobject surface, jint cameraId, jint width, jint height) {

    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (g_isStreaming.load()) {
        LOGW("Native QCarCam stream already running");
        return JNI_TRUE;
    }

    if (!g_qcarcam.load()) {
        LOGE("Failed to load QCarCam API symbols");
        return JNI_FALSE;
    }

    g_cameraId = cameraId;
    g_width = (width > 0) ? width : DEFAULT_FRAME_WIDTH;
    g_height = (height > 0) ? height : DEFAULT_FRAME_HEIGHT;

    // 1. Initialize QCarCam system
    qcarcam_init_t init_params;
    memset(&init_params, 0, sizeof(init_params));
    qcarcam_ret_t ret = g_qcarcam.initialize(&init_params);
    if (ret != QCARCAM_RET_OK && ret != QCARCAM_RET_BADSTATE) {
        LOGE("qcarcam_initialize failed: %d", ret);
        return JNI_FALSE;
    }

    // 2. Open camera input
    g_hndl = g_qcarcam.open(static_cast<qcarcam_input_t>(g_cameraId));
    if (!g_hndl) {
        LOGE("qcarcam_open failed for cameraId %d", g_cameraId);
        g_qcarcam.uninitialize();
        return JNI_FALSE;
    }

    // 3. Set Color Format and Resolution
    qcarcam_param_value_t param_val;
    param_val.uint_value = QCARCAM_FMT_UYVY_8;
    g_qcarcam.s_param(g_hndl, QCARCAM_PARAM_COLOR_FMT, &param_val);

    param_val.res_value.width = g_width;
    param_val.res_value.height = g_height;
    param_val.res_value.fps = 30.0f;
    g_qcarcam.s_param(g_hndl, QCARCAM_PARAM_RESOLUTION, &param_val);

    // 4. Allocate 4-buffer pool (4096-byte aligned for Direct Memory Access)
    size_t frame_bytes = g_width * g_height * 2; // UYVY 16-bit
    memset(g_buffers, 0, sizeof(g_buffers));

    for (int i = 0; i < NUM_BUFFERS; ++i) {
        if (!g_bufferPointers[i]) {
            posix_memalign(&g_bufferPointers[i], 4096, frame_bytes);
            memset(g_bufferPointers[i], 0, frame_bytes);
        }
        g_buffers[i].num_planes = 1;
        g_buffers[i].planes[0].width = g_width;
        g_buffers[i].planes[0].height = g_height;
        g_buffers[i].planes[0].stride = g_width * 2;
        g_buffers[i].planes[0].size = frame_bytes;
        g_buffers[i].planes[0].p_buf = g_bufferPointers[i];
    }

    qcarcam_buffers_t buffers_cfg;
    buffers_cfg.color_fmt = QCARCAM_FMT_UYVY_8;
    buffers_cfg.num_buffers = NUM_BUFFERS;
    buffers_cfg.buffers = g_buffers;
    buffers_cfg.flags = 0;

    ret = g_qcarcam.s_buffers(g_hndl, &buffers_cfg);
    if (ret != QCARCAM_RET_OK) {
        LOGE("qcarcam_s_buffers failed: %d", ret);
        g_qcarcam.close(g_hndl);
        g_hndl = nullptr;
        g_qcarcam.uninitialize();
        return JNI_FALSE;
    }

    // 5. Configure Surface if provided
    {
        std::lock_guard<std::mutex> winLock(g_windowMutex);
        if (g_surfaceWindow) {
            ANativeWindow_release(g_surfaceWindow);
            g_surfaceWindow = nullptr;
        }
        if (surface) {
            g_surfaceWindow = ANativeWindow_fromSurface(env, surface);
            if (g_surfaceWindow) {
                ANativeWindow_setBuffersGeometry(g_surfaceWindow, g_width, g_height, WINDOW_FORMAT_RGBA_8888);
                LOGI("ANativeWindow successfully configured for Native QCarCam: %dx%d RGBA8888", g_width, g_height);
            }
        }
    }

    // 6. Start hardware streaming
    ret = g_qcarcam.start(g_hndl);
    if (ret != QCARCAM_RET_OK) {
        LOGE("qcarcam_start failed: %d", ret);
        g_qcarcam.close(g_hndl);
        g_hndl = nullptr;
        g_qcarcam.uninitialize();
        return JNI_FALSE;
    }

    g_isStreaming.store(true);
    pthread_create(&g_captureThread, nullptr, captureLoop, nullptr);

    LOGI("Native QCarCam direct hardware stream started successfully on camera %d (%dx%d @ 30 FPS)", g_cameraId, g_width, g_height);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_NativeQCarCamEngine_nativeStop(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_stateMutex);

    if (!g_isStreaming.load()) return;

    g_isStreaming.store(false);
    if (g_captureThread != 0) {
        pthread_join(g_captureThread, nullptr);
        g_captureThread = 0;
    }

    if (g_hndl) {
        if (g_qcarcam.stop) g_qcarcam.stop(g_hndl);
        if (g_qcarcam.close) g_qcarcam.close(g_hndl);
        g_hndl = nullptr;
    }

    if (g_qcarcam.uninitialize) {
        g_qcarcam.uninitialize();
    }

    {
        std::lock_guard<std::mutex> winLock(g_windowMutex);
        if (g_surfaceWindow) {
            ANativeWindow_release(g_surfaceWindow);
            g_surfaceWindow = nullptr;
        }
    }

    for (int i = 0; i < NUM_BUFFERS; ++i) {
        if (g_bufferPointers[i]) {
            free(g_bufferPointers[i]);
            g_bufferPointers[i] = nullptr;
        }
    }

    LOGI("Native QCarCam stream stopped and cleaned up");
}

} // extern "C"
