// qcarcam_bridge.cpp — Qualcomm AIS / QCarCam native JNI bridge for DiLink 5.0 (Snapdragon SA8155P).
// Directly interfaces with /vendor/lib64/libais_client.so to stream raw camera frames
// (1920x1300 / 1920x1020 YUV) for OverDrive's hardware MediaCodec encoder pipeline.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <mutex>

#define TAG "QCarCamBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

typedef int (*qcarcam_init_fn)(void*);
typedef int (*qcarcam_uninit_fn)();
typedef void* (*qcarcam_open_fn)(int);
typedef int (*qcarcam_start_fn)(void*);
typedef int (*qcarcam_stop_fn)(void*);
typedef int (*qcarcam_close_fn)(void*);
typedef int (*qcarcam_get_frame_fn)(void*, void*, unsigned long long, unsigned int);
typedef int (*qcarcam_release_frame_fn)(void*, void*);

struct QCarCamFns {
    void* handle_lib = nullptr;
    qcarcam_init_fn init = nullptr;
    qcarcam_uninit_fn uninit = nullptr;
    qcarcam_open_fn open = nullptr;
    qcarcam_close_fn close = nullptr;
    qcarcam_start_fn start = nullptr;
    qcarcam_stop_fn stop = nullptr;
    qcarcam_get_frame_fn get_frame = nullptr;
    qcarcam_release_frame_fn release_frame = nullptr;
    bool loaded = false;
};

QCarCamFns g_qcarcam;
std::mutex g_lock;

bool loadQCarCamLibraries() {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_qcarcam.loaded) return true;

    g_qcarcam.handle_lib = dlopen("/vendor/lib64/libais_client.so", RTLD_NOW);
    if (!g_qcarcam.handle_lib) {
        LOGW("Failed to load /vendor/lib64/libais_client.so: %s", dlerror());
        return false;
    }

    g_qcarcam.init = (qcarcam_init_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_initialize");
    g_qcarcam.uninit = (qcarcam_uninit_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_uninitialize");
    g_qcarcam.open = (qcarcam_open_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_open");
    g_qcarcam.close = (qcarcam_close_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_close");
    g_qcarcam.start = (qcarcam_start_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_start");
    g_qcarcam.stop = (qcarcam_stop_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_stop");
    g_qcarcam.get_frame = (qcarcam_get_frame_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_get_frame");
    g_qcarcam.release_frame = (qcarcam_release_frame_fn)dlsym(g_qcarcam.handle_lib, "qcarcam_release_frame");

    if (!g_qcarcam.init || !g_qcarcam.open || !g_qcarcam.start) {
        LOGE("Required QCarCam symbols missing from libais_client.so");
        dlclose(g_qcarcam.handle_lib);
        g_qcarcam.handle_lib = nullptr;
        return false;
    }

    g_qcarcam.loaded = true;
    LOGI("Qualcomm QCarCam / AIS Client library loaded successfully.");
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeIsSupported(
    JNIEnv* env, jclass clazz) {
    return loadQCarCamLibraries() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeInit(
    JNIEnv* env, jobject thiz, jint inputId) {
    if (!loadQCarCamLibraries()) return 0;

    int res = g_qcarcam.init(nullptr);
    LOGI("qcarcam_initialize() returned %d", res);

    void* cam_hndl = g_qcarcam.open(inputId);
    if (!cam_hndl) {
        LOGE("qcarcam_open(%d) failed!", inputId);
        return 0;
    }

    LOGI("qcarcam_open(%d) succeeded, handle=%p", inputId, cam_hndl);
    return reinterpret_cast<jlong>(cam_hndl);
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStart(
    JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle || !g_qcarcam.loaded) return JNI_FALSE;

    void* cam_hndl = reinterpret_cast<void*>(handle);
    int res = g_qcarcam.start(cam_hndl);
    LOGI("qcarcam_start() returned %d", res);
    return (res == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStop(
    JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle || !g_qcarcam.loaded) return JNI_FALSE;

    void* cam_hndl = reinterpret_cast<void*>(handle);
    int res = g_qcarcam.stop(cam_hndl);
    LOGI("qcarcam_stop() returned %d", res);
    return (res == 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeRelease(
    JNIEnv* env, jobject thiz, jlong handle) {
    if (!handle || !g_qcarcam.loaded) return;

    void* cam_hndl = reinterpret_cast<void*>(handle);
    g_qcarcam.close(cam_hndl);
    g_qcarcam.uninit();
    LOGI("qcarcam handle released.");
}

} // extern "C"
