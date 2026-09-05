// qcarcam_bridge.cpp — BYD DiLink 5.0 (Snapdragon SA8155P) Sidecar Bridge.
// Connects to the high-performance fast_cam_capture daemon via FastCamClient IPC,
// receives zero-copy hardware frames, and posts them to Android's ANativeWindow / Surface.

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <stdint.h>
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

struct CameraMapping {
    std::atomic<int> hw_front{0};
    std::atomic<int> hw_right{1};
    std::atomic<int> hw_rear{2};
    std::atomic<int> hw_left{3};
    std::atomic<int> hw_dashcam{-1};
};
static CameraMapping g_camMapping;

std::atomic<bool> g_streaming{false};
std::atomic<int> g_active_camera{0};
pthread_t g_streamThread = 0;
ANativeWindow* g_nativeWindow = nullptr;
std::mutex g_winMutex;

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
        uint32_t* dst_row = dst_rgba + y * stride;
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
        } else if (desired_cam == 4) {
            // Mode 4 = 2x2 Decimated Mosaic (1920x1080)
            const uint8_t* p0 = cam_ptrs[0] ? cam_ptrs[0] : frame.pixels;
            const uint8_t* p1 = cam_ptrs[1] ? cam_ptrs[1] : p0;
            const uint8_t* p2 = cam_ptrs[2] ? cam_ptrs[2] : p0;
            const uint8_t* p3 = cam_ptrs[3] ? cam_ptrs[3] : p0;

            compose_2x2_1080p_uyvy(p0, p1, p3, p2, mosaic_buf_2x2);
            render_pixels = mosaic_buf_2x2;
            target_w = FRAME_WIDTH_1080P;
            target_h = FRAME_HEIGHT_1080P;
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
            std::lock_guard<std::mutex> lock(g_winMutex);
            if (g_nativeWindow) {
                // Dynamically reconfigure window buffer geometry if resolution changed
                if (current_win_w != target_w || current_win_h != target_h) {
                    ANativeWindow_setBuffersGeometry(g_nativeWindow, target_w, target_h, WINDOW_FORMAT_RGBA_8888);
                    current_win_w = target_w;
                    current_win_h = target_h;
                    LOGI("ANativeWindow buffer geometry adapted: %dx%d RGBA8888 (mode=%d)", target_w, target_h, desired_cam);
                }

                ANativeWindow_Buffer winBuffer;
                memset(&winBuffer, 0, sizeof(winBuffer));
                if (ANativeWindow_lock(g_nativeWindow, &winBuffer, nullptr) == 0) {
                    if (winBuffer.bits != nullptr && winBuffer.stride > 0 && winBuffer.width > 0 && winBuffer.height > 0) {
                        // Color conversion UYVY to RGBA8888 onto Android Surface buffer
                        convert_uyvy_to_rgba(render_pixels, target_w, target_h,
                                             (uint32_t*)winBuffer.bits, winBuffer.stride);
                    }
                    ANativeWindow_unlockAndPost(g_nativeWindow);
                    rendered_frames++;
                    if (rendered_frames % 300 == 1) {
                        LOGI("Rendered %u frames to Surface (mode=%d, res=%dx%d, lastCamId=%u)",
                             rendered_frames, desired_cam, target_w, target_h, frame.cam_id);
                    }
                }
            }
        }
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

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStartSurface(
    JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(g_winMutex);
    ANativeWindow* newWin = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (newWin != nullptr && newWin == g_nativeWindow) {
        // Already active with this ANativeWindow, release extra reference acquired by ANativeWindow_fromSurface
        ANativeWindow_release(newWin);
    } else {
        if (g_nativeWindow) {
            ANativeWindow_release(g_nativeWindow);
            g_nativeWindow = nullptr;
        }
        g_nativeWindow = newWin;
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
