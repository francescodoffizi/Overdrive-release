// qcarcam_bridge.cpp — BYD DiLink 5.0 (Snapdragon SA8155P) Sidecar Bridge.
// Connects to the high-performance fast_cam_capture daemon via FastCamClient IPC,
// receives zero-copy hardware frames, and posts them to Android's ANativeWindow / Surface.

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <stdint.h>
#include <inttypes.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <atomic>
#include <mutex>
#include "fast_cam_bridge.h"
#include "fast_cam_ipc.h"

#define TAG "QCarCamBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define SENSOR_WIDTH 1920
#define SENSOR_HEIGHT 1300

#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1080

namespace {

// Extension function signatures for EGL and AHardwareBuffer
typedef EGLClientBuffer (EGLAPIENTRYP PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)(const struct AHardwareBuffer* buffer);
typedef EGLImageKHR (EGLAPIENTRYP PFNEGLCREATEIMAGEKHRPROC)(EGLDisplay dpy, EGLContext ctx, EGLenum target, EGLClientBuffer buffer, const EGLint* attrib_list);
typedef EGLBoolean (EGLAPIENTRYP PFNEGLDESTROYIMAGEKHRPROC)(EGLDisplay dpy, EGLImageKHR image);
typedef void (GL_APIENTRYP PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)(GLenum target, GLeglImageOES image);

typedef int (*PFN_AHARDWAREBUFFER_ALLOCATE)(const AHardwareBuffer_Desc* desc, AHardwareBuffer** outBuffer);
typedef int (*PFN_AHARDWAREBUFFER_LOCK)(AHardwareBuffer* buffer, uint64_t usage, int32_t fence, const ARect* rect, void** outVirtualAddress);
typedef int (*PFN_AHARDWAREBUFFER_UNLOCK)(AHardwareBuffer* buffer, int32_t* fence);
typedef void (*PFN_AHARDWAREBUFFER_ACQUIRE)(AHardwareBuffer* buffer);
typedef void (*PFN_AHARDWAREBUFFER_RELEASE)(AHardwareBuffer* buffer);

struct ExtFns {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
    PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;
    PFN_AHARDWAREBUFFER_ALLOCATE ahbAllocate = nullptr;
    PFN_AHARDWAREBUFFER_LOCK ahbLock = nullptr;
    PFN_AHARDWAREBUFFER_UNLOCK ahbUnlock = nullptr;
    PFN_AHARDWAREBUFFER_ACQUIRE ahbAcquire = nullptr;
    PFN_AHARDWAREBUFFER_RELEASE ahbRelease = nullptr;
    bool ok = false;
};

static ExtFns g_ext;
static std::once_flag g_extOnce;

static void resolveExtensions() {
    std::call_once(g_extOnce, []() {
        g_ext.eglGetNativeClientBufferANDROID = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress("eglGetNativeClientBufferANDROID");
        g_ext.eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
        g_ext.eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
        g_ext.glEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) eglGetProcAddress("glEGLImageTargetTexture2DOES");

        void* libnw = dlopen("libnativewindow.so", RTLD_NOW);
        if (libnw) {
            g_ext.ahbAllocate = (PFN_AHARDWAREBUFFER_ALLOCATE) dlsym(libnw, "AHardwareBuffer_allocate");
            g_ext.ahbLock = (PFN_AHARDWAREBUFFER_LOCK) dlsym(libnw, "AHardwareBuffer_lock");
            g_ext.ahbUnlock = (PFN_AHARDWAREBUFFER_UNLOCK) dlsym(libnw, "AHardwareBuffer_unlock");
            g_ext.ahbAcquire = (PFN_AHARDWAREBUFFER_ACQUIRE) dlsym(libnw, "AHardwareBuffer_acquire");
            g_ext.ahbRelease = (PFN_AHARDWAREBUFFER_RELEASE) dlsym(libnw, "AHardwareBuffer_release");
        }
        if (!g_ext.ahbAllocate) g_ext.ahbAllocate = (PFN_AHARDWAREBUFFER_ALLOCATE) dlsym(RTLD_DEFAULT, "AHardwareBuffer_allocate");
        if (!g_ext.ahbLock) g_ext.ahbLock = (PFN_AHARDWAREBUFFER_LOCK) dlsym(RTLD_DEFAULT, "AHardwareBuffer_lock");
        if (!g_ext.ahbUnlock) g_ext.ahbUnlock = (PFN_AHARDWAREBUFFER_UNLOCK) dlsym(RTLD_DEFAULT, "AHardwareBuffer_unlock");
        if (!g_ext.ahbAcquire) g_ext.ahbAcquire = (PFN_AHARDWAREBUFFER_ACQUIRE) dlsym(RTLD_DEFAULT, "AHardwareBuffer_acquire");
        if (!g_ext.ahbRelease) g_ext.ahbRelease = (PFN_AHARDWAREBUFFER_RELEASE) dlsym(RTLD_DEFAULT, "AHardwareBuffer_release");

        g_ext.ok = g_ext.eglGetNativeClientBufferANDROID && g_ext.eglCreateImageKHR &&
                   g_ext.eglDestroyImageKHR && g_ext.glEGLImageTargetTexture2DOES &&
                   g_ext.ahbAllocate && g_ext.ahbLock && g_ext.ahbUnlock && g_ext.ahbRelease;
        LOGI("Zero-copy EGL/AHardwareBuffer extensions resolved: ok=%d", g_ext.ok);
    });
}

struct CameraMapping {
    std::atomic<int> hw_front{0};
    std::atomic<int> hw_right{1};
    std::atomic<int> hw_rear{2};
    std::atomic<int> hw_left{3};
    std::atomic<int> hw_dashcam{-1};
};
static CameraMapping g_camMapping;

static JavaVM* g_jvm = nullptr;
static jclass g_backendClass = nullptr;
static jmethodID g_onFrameAvailableMethod = nullptr;
static std::atomic<uint64_t> g_lastFrameTimestampNs{0};

static inline uint64_t getCurrentNanoTime() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((uint64_t)ts.tv_sec * 1000000000ULL) + (uint64_t)ts.tv_nsec;
}

std::atomic<bool> g_streaming{false};
std::atomic<int> g_active_camera{0};
pthread_t g_streamThread = 0;

// HardwareBuffer ping-pong pool for zero-copy GL texture binding (bypasses SurfaceFlinger and HWC)
static AHardwareBuffer* g_hwBuffers[2] = {nullptr, nullptr};
static EGLImageKHR g_eglImages[2] = {EGL_NO_IMAGE_KHR, EGL_NO_IMAGE_KHR};
static AHardwareBuffer* g_cachedHwBufForImage[2] = {nullptr, nullptr};
static uint32_t g_eglImageEpoch[2] = {0, 0};
static std::atomic<uint32_t> g_hwBufferEpoch{1};
static EGLDisplay g_cachedEglDisplay = EGL_NO_DISPLAY;
static int g_bufWidth = 0;
static int g_bufHeight = 0;
static std::atomic<int> g_frontIdx{-1};
static std::atomic<bool> g_hasNewFrame{false};
static std::mutex g_bufMutex;

#include <arm_neon.h>

// High-performance ARM NEON SIMD UYVY to RGBA8888 conversion (Full-range BT.601)
// Processes 16 pixels (32 bytes UYVY -> 64 bytes RGBA) per SIMD cycle in <3ms per frame with zero overflow.
void convert_uyvy_to_rgba(const uint8_t* __restrict__ uyvy, int width, int height, uint32_t* __restrict__ dst_rgba, int dst_stride) {
    int stride = (dst_stride > 0 ? dst_stride : width);
    const int16x8_t c_128 = vdupq_n_s16(128);
    const uint8x8_t c_255 = vdup_n_u8(255);
    const int32x4_t c_512_32 = vdupq_n_s32(512);

    for (int y = 0; y < height; ++y) {
        const uint8_t* src_row = uyvy + y * width * 2;
        // Invert Y so OpenGL / shader coordinate conventions match upright image
        uint32_t* dst_row = dst_rgba + (height - 1 - y) * stride;
        int x = 0;

        // Vectorized loop: 16 pixels per iteration
        for (; x <= width - 16; x += 16) {
            uint8x8x4_t uyvy_8 = vld4_u8(src_row);
            src_row += 32;

            // U and V in signed 16-bit
            int16x8_t u_16 = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[0])), c_128);
            int16x8_t v_16 = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[2])), c_128);

            // Promote to 32-bit for exact multiplication with zero overflow
            int32x4_t v_lo_32 = vmovl_s16(vget_low_s16(v_16));
            int32x4_t v_hi_32 = vmovl_s16(vget_high_s16(v_16));

            int32x4_t u_lo_32 = vmovl_s16(vget_low_s16(u_16));
            int32x4_t u_hi_32 = vmovl_s16(vget_high_s16(u_16));

            // R delta = (1436 * V + 512) >> 10
            int16x4_t r_delta_lo = vshrn_n_s32(vaddq_s32(vmulq_n_s32(v_lo_32, 1436), c_512_32), 10);
            int16x4_t r_delta_hi = vshrn_n_s32(vaddq_s32(vmulq_n_s32(v_hi_32, 1436), c_512_32), 10);
            int16x8_t r_delta = vcombine_s16(r_delta_lo, r_delta_hi);

            // G delta = (352 * U + 731 * V + 512) >> 10
            int16x4_t g_delta_lo = vshrn_n_s32(vaddq_s32(vaddq_s32(vmulq_n_s32(u_lo_32, 352), vmulq_n_s32(v_lo_32, 731)), c_512_32), 10);
            int16x4_t g_delta_hi = vshrn_n_s32(vaddq_s32(vaddq_s32(vmulq_n_s32(u_hi_32, 352), vmulq_n_s32(v_hi_32, 731)), c_512_32), 10);
            int16x8_t g_delta = vcombine_s16(g_delta_lo, g_delta_hi);

            // B delta = (1815 * U + 512) >> 10
            int16x4_t b_delta_lo = vshrn_n_s32(vaddq_s32(vmulq_n_s32(u_lo_32, 1815), c_512_32), 10);
            int16x4_t b_delta_hi = vshrn_n_s32(vaddq_s32(vmulq_n_s32(u_hi_32, 1815), c_512_32), 10);
            int16x8_t b_delta = vcombine_s16(b_delta_lo, b_delta_hi);

            // EVEN pixels (Y0)
            int16x8_t y0_16 = vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[1]));
            uint8x8_t r0_8 = vqmovun_s16(vaddq_s16(y0_16, r_delta));
            uint8x8_t g0_8 = vqmovun_s16(vsubq_s16(y0_16, g_delta));
            uint8x8_t b0_8 = vqmovun_s16(vaddq_s16(y0_16, b_delta));

            // ODD pixels (Y1)
            int16x8_t y1_16 = vreinterpretq_s16_u16(vmovl_u8(uyvy_8.val[3]));
            uint8x8_t r1_8 = vqmovun_s16(vaddq_s16(y1_16, r_delta));
            uint8x8_t g1_8 = vqmovun_s16(vsubq_s16(y1_16, g_delta));
            uint8x8_t b1_8 = vqmovun_s16(vaddq_s16(y1_16, b_delta));

            // Interleave even and odd channels:
            uint8x8x2_t r_zip = vzip_u8(r0_8, r1_8);
            uint8x8x2_t g_zip = vzip_u8(g0_8, g1_8);
            uint8x8x2_t b_zip = vzip_u8(b0_8, b1_8);

            // First 8 pixels
            uint8x8x4_t rgba_lo;
            rgba_lo.val[0] = r_zip.val[0];
            rgba_lo.val[1] = g_zip.val[0];
            rgba_lo.val[2] = b_zip.val[0];
            rgba_lo.val[3] = c_255;
            vst4_u8((uint8_t*)(dst_row + x), rgba_lo);

            // Next 8 pixels
            uint8x8x4_t rgba_hi;
            rgba_hi.val[0] = r_zip.val[1];
            rgba_hi.val[1] = g_zip.val[1];
            rgba_hi.val[2] = b_zip.val[1];
            rgba_hi.val[3] = c_255;
            vst4_u8((uint8_t*)(dst_row + x + 8), rgba_hi);
        }

        // Remainder scalar loop
        for (; x < width; x += 2) {
            int u  = (int)src_row[0] - 128;
            int y0 = (int)src_row[1];
            int v  = (int)src_row[2] - 128;
            int y1 = (int)src_row[3];
            src_row += 4;

            int r0 = y0 + ((1436 * v + 512) >> 10);
            int g0 = y0 - ((352 * u + 731 * v + 512) >> 10);
            int b0 = y0 + ((1815 * u + 512) >> 10);

            int r1 = y1 + ((1436 * v + 512) >> 10);
            int g1 = y1 - ((352 * u + 731 * v + 512) >> 10);
            int b1 = y1 + ((1815 * u + 512) >> 10);

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

#define FRAME_WIDTH_1080P 1920
#define FRAME_HEIGHT_1080P 1080
#define FRAME_WIDTH_4K 3840
#define FRAME_HEIGHT_4K 2160

// Fast 2x2 grid compositor in UYVY (4 cameras decimated into 1920x1080 16:9 canvas)
// Center crops vertical dimension from 1300 to 1080 (110 rows top/bottom),
// and decimates 2:1 horizontally (1920 -> 960) and vertically (1080 -> 540).
static void compose_2x2_1080p_uyvy(
    const uint8_t* cam0, const uint8_t* cam1,
    const uint8_t* cam3, const uint8_t* cam2,
    uint8_t* __restrict__ out_grid
) {
    const int crop_y_start = 110;
    const int in_stride_bytes = 1920 * 2; // 3840
    const int out_stride_bytes = 1920 * 2; // 3840

    // Top half: Cam 0 (Front, Left) and Cam 1 (Right, Right) -> 540 rows
    for (int y = 0; y < 540; ++y) {
        int src_y = crop_y_start + (y * 2);
        const uint32_t* src0 = (const uint32_t*)(cam0 + src_y * in_stride_bytes);
        const uint32_t* src1 = (const uint32_t*)(cam1 + src_y * in_stride_bytes);
        uint32_t* dst_row = (uint32_t*)(out_grid + y * out_stride_bytes);

        for (int x = 0; x < 480; ++x) {
            dst_row[x] = src0[x * 2];
            dst_row[480 + x] = src1[x * 2];
        }
    }

    // Bottom half: Cam 3 (Left, Left) and Cam 2 (Rear, Right) -> 540 rows
    for (int y = 0; y < 540; ++y) {
        int src_y = crop_y_start + (y * 2);
        const uint32_t* src3 = (const uint32_t*)(cam3 + src_y * in_stride_bytes);
        const uint32_t* src2 = (const uint32_t*)(cam2 + src_y * in_stride_bytes);
        uint32_t* dst_row = (uint32_t*)(out_grid + (540 + y) * out_stride_bytes);

        for (int x = 0; x < 480; ++x) {
            dst_row[x] = src3[x * 2];
            dst_row[480 + x] = src2[x * 2];
        }
    }
}

// 4K Ultra-HD Native Grid in UYVY (3840x2160 standard 16:9 canvas)
static void compose_4k_2160p_uyvy(
    const uint8_t* cam0, const uint8_t* cam1,
    const uint8_t* cam3, const uint8_t* cam2,
    uint8_t* __restrict__ out_grid
) {
    const int crop_y_start = 110;
    const int in_stride_bytes = 1920 * 2; // 3840
    const int out_stride_bytes = 3840 * 2; // 7680

    for (int y = 0; y < 1080; ++y) {
        int src_y = crop_y_start + y;
        const uint8_t* src0 = cam0 + src_y * in_stride_bytes;
        const uint8_t* src1 = cam1 + src_y * in_stride_bytes;
        uint8_t* dst_row = out_grid + y * out_stride_bytes;
        memcpy(dst_row, src0, in_stride_bytes);
        memcpy(dst_row + in_stride_bytes, src1, in_stride_bytes);
    }

    for (int y = 0; y < 1080; ++y) {
        int src_y = crop_y_start + y;
        const uint8_t* src3 = cam3 + src_y * in_stride_bytes;
        const uint8_t* src2 = cam2 + src_y * in_stride_bytes;
        uint8_t* dst_row = out_grid + (1080 + y) * out_stride_bytes;
        memcpy(dst_row, src3, in_stride_bytes);
        memcpy(dst_row + in_stride_bytes, src2, in_stride_bytes);
    }
}

void* streamClientLoop(void* arg) {
    LOGI("FastCamClient thread started.");

    JNIEnv* jniEnv = nullptr;
    bool jniAttached = false;
    if (g_jvm) {
        jint res = g_jvm->AttachCurrentThread(&jniEnv, nullptr);
        if (res == JNI_OK && jniEnv) {
            jniAttached = true;
        } else {
            LOGW("streamClientLoop: failed to attach to JVM (err=%d)", res);
        }
    }

    FastCamClient client;

    // Connect to the abstract domain socket @fast_cam.sock (SELinux-safe)
    while (g_streaming.load() && !client.connect("@fast_cam.sock")) {
        // Fallback check to filesystem socket if abstract is not yet up
        if (client.connect("/data/local/tmp/fast_cam.sock")) {
            break;
        }
        usleep(300000); // Retry connection every 300ms
    }

    if (!g_streaming.load()) {
        client.disconnect();
        if (jniAttached && g_jvm) {
            g_jvm->DetachCurrentThread();
        }
        LOGI("FastCamClient thread terminated before stream start.");
        return nullptr;
    }

    LOGI("FastCamClient connected successfully to fast_cam IPC stream (@fast_cam.sock)!");

    FastCamFrame frame;
    const uint8_t* cam_ptrs[FAST_CAM_MAX_CAMS] = { nullptr };
    static uint8_t mosaic_buf_2x2[FRAME_WIDTH_1080P * FRAME_HEIGHT_1080P * 2];
    std::unique_ptr<uint8_t[]> mosaic_buf_4k;
    int current_win_w = 0;
    int current_win_h = 0;
    uint32_t rendered_frames = 0;

    while (g_streaming.load()) {
        if (!client.isConnected()) {
            {
                std::lock_guard<std::mutex> lock(g_bufMutex);
                g_frontIdx.store(-1);
                g_hasNewFrame.store(false);
            }
            LOGW("FastCamClient disconnected from @fast_cam.sock. Reconnecting...");
            while (g_streaming.load() && !client.connect("@fast_cam.sock")) {
                if (client.connect("/data/local/tmp/fast_cam.sock")) {
                    break;
                }
                usleep(300000); // Retry connection every 300ms
            }
            if (!g_streaming.load()) break;
            LOGI("FastCamClient reconnected successfully to fast_cam IPC stream (@fast_cam.sock)!");
        }

        // Wait for next available hardware frame (timeout 100ms)
        if (!client.waitForFrame(&frame, 100)) {
            continue;
        }

        // Map incoming frame.cam_id to canonical semantic slot:
        // Slot 0 = Front, Slot 1 = Right, Slot 2 = Rear, Slot 3 = Left, Slot 6 = Internal Dashcam
        int slot = -1;
        int hw_f  = g_camMapping.hw_front.load();
        int hw_r  = g_camMapping.hw_right.load();
        int hw_re = g_camMapping.hw_rear.load();
        int hw_l  = g_camMapping.hw_left.load();
        int hw_dc = g_camMapping.hw_dashcam.load();

        if (frame.cam_id == (uint32_t)hw_f)        slot = 0;
        else if (frame.cam_id == (uint32_t)hw_r)   slot = 1;
        else if (frame.cam_id == (uint32_t)hw_re)  slot = 2;
        else if (frame.cam_id == (uint32_t)hw_l)   slot = 3;
        else if (hw_dc >= 0 && frame.cam_id == (uint32_t)hw_dc) slot = 6;
        else if (frame.cam_id < FAST_CAM_MAX_CAMS) slot = (int)frame.cam_id;

        if (slot >= 0 && slot < FAST_CAM_MAX_CAMS && frame.pixels) {
            cam_ptrs[slot] = frame.pixels;
        }

        int desired_cam = g_active_camera.load();
        const uint8_t* render_pixels = nullptr;
        int target_w = FRAME_WIDTH_1080P;
        int target_h = FRAME_HEIGHT_1080P;

        if (desired_cam == 5) {
            // Mode 5 = 4K Ultra-HD Native Grid (3840x2160, 100% native sensor pixels 16:9)
            // Anchor on Cam 0 (slot == 0, Front) arriving at 30 FPS to avoid 120 FPS CPU overload
            if (slot == 0 || rendered_frames == 0) {
                if (!mosaic_buf_4k) {
                    mosaic_buf_4k = std::make_unique<uint8_t[]>(FRAME_WIDTH_4K * FRAME_HEIGHT_4K * 2);
                }
                const uint8_t* p0 = cam_ptrs[0] ? cam_ptrs[0] : frame.pixels;
                const uint8_t* p1 = cam_ptrs[1] ? cam_ptrs[1] : p0;
                const uint8_t* p2 = cam_ptrs[2] ? cam_ptrs[2] : p0;
                const uint8_t* p3 = cam_ptrs[3] ? cam_ptrs[3] : p0;

                compose_4k_2160p_uyvy(p0, p1, p3, p2, mosaic_buf_4k.get());
                render_pixels = mosaic_buf_4k.get();
                target_w = FRAME_WIDTH_4K;
                target_h = FRAME_HEIGHT_4K;
            }
        } else if (desired_cam == 4) {
            // Mode 4 = 2x2 Decimated Mosaic (1920x1080)
            // Anchor on Cam 0 (slot == 0, Front) arriving at 30 FPS to avoid 120 FPS CPU overload
            if (slot == 0 || rendered_frames == 0) {
                const uint8_t* p0 = cam_ptrs[0] ? cam_ptrs[0] : frame.pixels;
                const uint8_t* p1 = cam_ptrs[1] ? cam_ptrs[1] : p0;
                const uint8_t* p2 = cam_ptrs[2] ? cam_ptrs[2] : p0;
                const uint8_t* p3 = cam_ptrs[3] ? cam_ptrs[3] : p0;

                compose_2x2_1080p_uyvy(p0, p1, p3, p2, mosaic_buf_2x2);
                render_pixels = mosaic_buf_2x2;
                target_w = FRAME_WIDTH_1080P;
                target_h = FRAME_HEIGHT_1080P;
            }
        } else if (desired_cam >= 0 && desired_cam < FAST_CAM_MAX_CAMS) {
            // Specific single camera channel requested (0..3 surround or 6 internal dashcam)
            if (slot == desired_cam) {
                // Vertical center-crop 1920x1300 to 1920x1080 (110 rows margin top/bottom)
                render_pixels = frame.pixels + (110 * 1920 * 2);
            }
            target_w = FRAME_WIDTH_1080P;
            target_h = FRAME_HEIGHT_1080P;
        } else {
            // Desired cam < 0: render whatever frame arrives with 16:9 center crop
            render_pixels = frame.pixels + (110 * 1920 * 2);
            target_w = FRAME_WIDTH_1080P;
            target_h = FRAME_HEIGHT_1080P;
        }

        if (render_pixels) {
            resolveExtensions();
            if (g_ext.ok) {
                std::lock_guard<std::mutex> lock(g_bufMutex);
                // Reallocate ping-pong AHardwareBuffers if resolution changed or not yet created
                if (!g_hwBuffers[0] || g_bufWidth != target_w || g_bufHeight != target_h) {
                    if (g_hwBuffers[0]) { g_ext.ahbRelease(g_hwBuffers[0]); g_hwBuffers[0] = nullptr; }
                    if (g_hwBuffers[1]) { g_ext.ahbRelease(g_hwBuffers[1]); g_hwBuffers[1] = nullptr; }

                    AHardwareBuffer_Desc desc = {};
                    desc.width = target_w;
                    desc.height = target_h;
                    desc.layers = 1;
                    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
                    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;

                    g_ext.ahbAllocate(&desc, &g_hwBuffers[0]);
                    g_ext.ahbAllocate(&desc, &g_hwBuffers[1]);
                    g_bufWidth = target_w;
                    g_bufHeight = target_h;
                    g_hwBufferEpoch.fetch_add(1);
                    g_frontIdx.store(-1);
                    LOGI("Allocated ping-pong AHardwareBuffers for GL zero-copy: %dx%d RGBA (epoch=%u)",
                         target_w, target_h, g_hwBufferEpoch.load());
                }

                if (g_hwBuffers[0] && g_hwBuffers[1]) {
                    int curFront = g_frontIdx.load();
                    int writeIdx = (curFront == 0) ? 1 : 0;
                    void* vaddr = nullptr;
                    if (g_ext.ahbLock(g_hwBuffers[writeIdx], AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &vaddr) == 0 && vaddr) {
                        convert_uyvy_to_rgba(render_pixels, target_w, target_h, (uint32_t*)vaddr, target_w);
                        g_ext.ahbUnlock(g_hwBuffers[writeIdx], nullptr);
                        uint64_t curTsNs = (frame.timestamp_ns > 0) ? frame.timestamp_ns : getCurrentNanoTime();
                        g_lastFrameTimestampNs.store(curTsNs);
                        g_frontIdx.store(writeIdx);
                        g_hasNewFrame.store(true);
                        rendered_frames++;
                        if (rendered_frames % 300 == 1) {
                            LOGI("HardwareBuffer zero-copy frame ready (%u frames, mode=%d, %dx%d, ts=%" PRIu64 ")",
                                 rendered_frames, desired_cam, target_w, target_h, curTsNs);
                        }
                        if (jniAttached && jniEnv && g_backendClass && g_onFrameAvailableMethod) {
                            jniEnv->CallStaticVoidMethod(g_backendClass, g_onFrameAvailableMethod, (jlong)curTsNs);
                            if (jniEnv->ExceptionCheck()) {
                                jniEnv->ExceptionClear();
                            }
                        }
                    }
                }
            }
        }
    }

    if (jniAttached && g_jvm) {
        g_jvm->DetachCurrentThread();
    }

    client.disconnect();
    LOGI("FastCamClient thread terminated.");
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

// Bypasses Surface/ANativeWindow completely to avoid Qualcomm SA8155P hwcomposer crashes
JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStartSurface(
    JNIEnv* env, jobject thiz, jobject surface) {
    // Delegated directly to nativeStart: GL rendering now reads via nativeBindLatestFrame
    return Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStart(env, thiz, 1);
}

// Binds the latest available AHardwareBuffer directly into the calling thread's EGL GL_TEXTURE_EXTERNAL_OES
JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeBindLatestFrame(
    JNIEnv* env, jclass clazz, jint textureId) {
    if (!g_hasNewFrame.load()) {
        return JNI_FALSE;
    }
    resolveExtensions();
    if (!g_ext.ok) {
        return JNI_FALSE;
    }

    std::unique_lock<std::mutex> lock(g_bufMutex);
    int idx = g_frontIdx.load();
    if (idx < 0 || idx > 1 || !g_hwBuffers[idx]) {
        return JNI_FALSE;
    }

    AHardwareBuffer* buf = g_hwBuffers[idx];
    if (g_ext.ahbAcquire) {
        g_ext.ahbAcquire(buf);
    }

    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) {
        if (g_ext.ahbRelease) {
            g_ext.ahbRelease(buf);
        }
        return JNI_FALSE;
    }

    uint32_t currentEpoch = g_hwBufferEpoch.load();
    // Reuse pre-created EGLImageKHR per ping-pong buffer slot to eliminate Adreno GSL pool 0 exhaustion
    if (g_eglImages[idx] == EGL_NO_IMAGE_KHR || g_eglImageEpoch[idx] != currentEpoch || g_cachedHwBufForImage[idx] != buf || g_cachedEglDisplay != dpy) {
        if (g_eglImages[idx] != EGL_NO_IMAGE_KHR && g_cachedEglDisplay != EGL_NO_DISPLAY && g_ext.eglDestroyImageKHR) {
            g_ext.eglDestroyImageKHR(g_cachedEglDisplay, g_eglImages[idx]);
            g_eglImages[idx] = EGL_NO_IMAGE_KHR;
        }

        EGLClientBuffer clientBuf = g_ext.eglGetNativeClientBufferANDROID(buf);
        if (!clientBuf) {
            if (g_ext.ahbRelease) {
                g_ext.ahbRelease(buf);
            }
            return JNI_FALSE;
        }

        static const EGLint kAttrs[] = {
            EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
            EGL_NONE
        };
        EGLImageKHR image = g_ext.eglCreateImageKHR(
            dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuf, kAttrs);
        if (image == EGL_NO_IMAGE_KHR) {
            if (g_ext.ahbRelease) {
                g_ext.ahbRelease(buf);
            }
            return JNI_FALSE;
        }

        g_eglImages[idx] = image;
        g_eglImageEpoch[idx] = currentEpoch;
        g_cachedHwBufForImage[idx] = buf;
        g_cachedEglDisplay = dpy;
    }

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(textureId));
    g_ext.glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, g_eglImages[idx]);

    g_hasNewFrame.store(false);
    if (g_ext.ahbRelease) {
        g_ext.ahbRelease(buf);
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
    std::lock_guard<std::mutex> lock(g_bufMutex);
    resolveExtensions();
    if (g_cachedEglDisplay != EGL_NO_DISPLAY && g_ext.eglDestroyImageKHR) {
        if (g_eglImages[0] != EGL_NO_IMAGE_KHR) {
            g_ext.eglDestroyImageKHR(g_cachedEglDisplay, g_eglImages[0]);
            g_eglImages[0] = EGL_NO_IMAGE_KHR;
        }
        if (g_eglImages[1] != EGL_NO_IMAGE_KHR) {
            g_ext.eglDestroyImageKHR(g_cachedEglDisplay, g_eglImages[1]);
            g_eglImages[1] = EGL_NO_IMAGE_KHR;
        }
        g_cachedEglDisplay = EGL_NO_DISPLAY;
    }
    g_eglImageEpoch[0] = 0;
    g_eglImageEpoch[1] = 0;
    g_cachedHwBufForImage[0] = nullptr;
    g_cachedHwBufForImage[1] = nullptr;
    if (g_ext.ahbRelease) {
        if (g_hwBuffers[0]) { g_ext.ahbRelease(g_hwBuffers[0]); g_hwBuffers[0] = nullptr; }
        if (g_hwBuffers[1]) { g_ext.ahbRelease(g_hwBuffers[1]); g_hwBuffers[1] = nullptr; }
    }
    g_frontIdx.store(-1);
    g_hasNewFrame.store(false);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeSetActiveCamera(
    JNIEnv* env, jclass clazz, jint camIdx) {
    g_active_camera.store(camIdx);
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeSetCameraMapping(
    JNIEnv* env, jclass clazz, jint front, jint right, jint rear, jint left, jint dashcam) {
    g_camMapping.hw_front.store(front);
    g_camMapping.hw_right.store(right);
    g_camMapping.hw_rear.store(rear);
    g_camMapping.hw_left.store(left);
    g_camMapping.hw_dashcam.store(dashcam);
    LOGI("Hardware camera mapping set: Front=%d, Right=%d, Rear=%d, Left=%d, Dashcam=%d",
         front, right, rear, left, dashcam);
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeRelease(
    JNIEnv* env, jobject thiz, jlong handle) {
    Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStop(env, thiz, handle);
}

JNIEXPORT jlong JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeGetLatestFrameTimestamp(
    JNIEnv* env, jclass clazz) {
    return (jlong)g_lastFrameTimestampNs.load();
}

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localClass = env->FindClass("com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend");
    if (localClass) {
        g_backendClass = (jclass)env->NewGlobalRef(localClass);
        g_onFrameAvailableMethod = env->GetStaticMethodID(g_backendClass, "onNativeFrameAvailable", "(J)V");
        env->DeleteLocalRef(localClass);
        LOGI("JNI_OnLoad: DiLink5QCarCamBackend cached, onNativeFrameAvailableMethod=%p", g_onFrameAvailableMethod);
    } else {
        LOGE("JNI_OnLoad: Failed to find DiLink5QCarCamBackend class");
    }
    return JNI_VERSION_1_6;
}

void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (g_backendClass) {
            env->DeleteGlobalRef(g_backendClass);
            g_backendClass = nullptr;
        }
    }
    g_jvm = nullptr;
}

} // extern "C"
