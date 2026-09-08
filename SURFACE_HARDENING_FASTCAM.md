# Architectural & Driver Deep-Dive: Surface & Overlay Hardening for Qualcomm SA8155P with `fast_cam`

**Author**: Antigravity Engineering  
**Target Audience**: Yash Srivastava & Overdrive Core Engineering Team  
**Platform**: Qualcomm Snapdragon Automotive Cockpit Platform (**SA8155P**) / Adreno 640 GPU  
**OS/BSP**: BYD DiLink 5.0 / Android 10 (API 29)  
**Date**: September 2026  

---

## 1. Executive Summary

During testing of the new UI release on the BYD Sealion 7, triggering on-screen overlay views (specifically testing toasts/dialogs via `MessageOverlayService`) caused an instant hard reboot / crash of the infotainment display.

Inspection of the native crash logs and tombstones revealed a fatal `SIGSEGV (SEGV_ACCERR)` inside Qualcomm's proprietary graphics driver stack:
```text
pid: 24013, tid: 24102, name: surfaceflinger  >>> /system/bin/surfaceflinger <<<
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x774f78b7a0
    #00 pc 0000000000021fa0  /vendor/lib64/libadreno_utils.so (validate_resource_memory_layout_metadata+40)
    #01 pc 000000000010c710  /vendor/lib64/egl/libGLESv2_adreno.so
    #02 pc 00000000000f3be8  /vendor/lib64/egl/libGLESv2_adreno.so
    #03 pc 00000000000f2490  /vendor/lib64/egl/libGLESv2_adreno.so
    #04 pc 000000000001859c  /system/lib64/libEGL.so (android::eglCreateImageTmpl)
    #05 pc 0000000000049e38  /system/lib64/libsurfaceflinger.so (android::renderengine::gl::GLESRenderEngine::create)
```

This document explains the physical GPU pipeline conflict between `fast_cam` (`byd_cam_daemon`) and Android `WindowManager` overlays, why this crash happens, and how the **Surface Hardening Architecture** (`SafeOverlayHelper`) permanently eliminates it without sacrificing performance or frame rates.

---

## 2. Background: How `fast_cam` Interacts with the Hardware

The `fast_cam` pipeline (`byd_cam_daemon` running under UID 2000) achieves 30 fps multi-channel camera recording and live streaming by bypassing standard high-overhead Android camera frameworks.

### The Buffer Lifecycle in `fast_cam`:
1. **Direct Camera HAL Ingestion**: Raw camera frames (4 panoramic feeds + 1 DMS/rear feed) are acquired from `/vendor/lib64/hw/camera.qcom.so` / BYD HAL into physical `ION` DMA-BUFs.
2. **UBWC Tiling**: Qualcomm Adreno hardware utilizes **Universal Bandwidth Compression (UBWC)**. These camera buffers are not simple linear memory; they have custom hardware tile formats and dynamic layout metadata allocated in contiguous carveouts.
3. **EGL Texture Binding**: The camera daemon binds these buffers into OpenGL ES textures via `glEGLImageTargetTexture2DOES` (implemented in `HardwareBufferTextureBinder.cpp` using ARM NEON SIMD).
4. **Saturation of GPU Descriptor Pools**: With 4 to 5 camera streams running at 30 fps, the GPU driver's resource memory layout descriptors and SurfaceFlinger's `RenderEngine` contexts are under constant high throughput.

---

## 3. The Anatomy of the Crash

### Step 1: An Overlay Window is Created
In standard Android development, displaying a floating status pill, HUD speed limit, or message toast is done via `WindowManager.addView()`:
```java
WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.MATCH_PARENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    PixelFormat.TRANSLUCENT
);
windowManager.addView(overlayView, lp);
```

### Step 2: The Android View Hierarchy Initializes Hardware Acceleration
Because the application is hardware-accelerated, the Android framework spawns a `ThreadedRenderer` and a `RenderProxy` for this new window. This allocates a hardware `GraphicBuffer` with an alpha channel (`RGBA_8888`) and hands it to SurfaceFlinger.

### Step 3: SurfaceFlinger Involves `GLESRenderEngine`
SurfaceFlinger must composite this translucent layer over existing layers. To do this, `surfaceflinger` creates an EGL Image backing the layer:
```cpp
GLESRenderEngine::create(...) -> android::eglCreateImageTmpl(...)
```

### Step 4: Adreno Driver Metadata Validation Fails
Inside `/vendor/lib64/libadreno_utils.so`, the driver executes `validate_resource_memory_layout_metadata`.
* Because `fast_cam` is actively modifying, locking, and unmapping camera DMA-BUFs with UBWC compression descriptors in the background, the Adreno driver's internal metadata cache experiences a race condition or memory layout pointer corruption.
* The driver attempts to dereference an invalid or unmapped address (`0x774f78b7a0`), triggering a fatal `SEGV_ACCERR` (code 2 = permission/access fault).
* `surfaceflinger` dies immediately.
* On Android, `init` detects the death of `surfaceflinger` and reboots the Zygote/SystemServer, resulting in a black screen and complete cockpit reboot.

---

## 4. Comprehensive Audit: All Surface Touchpoints in Overdrive

Our audit revealed that this risk is not limited to `MessageOverlayService`. There are **5 distinct categories** across the codebase that touch surfaces or create overlays:

| Category | Component / Location | Mechanism | Risk Level with `fast_cam` |
| :--- | :--- | :--- | :--- |
| **WindowManager Overlays** | `MessageOverlayService.java`<br>`StatusOverlayService.java`<br>`RoadSenseOverlayService.kt`<br>`RemoteVoiceService.java`<br>`NavPromptOverlay.kt` | `windowManager.addView()` with `TYPE_APPLICATION_OVERLAY` | **CRITICAL**: If `FLAG_HARDWARE_ACCELERATED` is set on translucent windows, it triggers the Adreno `validate_resource_memory_layout_metadata` SEGV. |
| **Native SurfaceControl** | `BsNativeLayer.java`<br>`ScreenDeterrent.java` | Reflective `SurfaceControl.Builder` allocating raw SurfaceFlinger layers at $z = \text{Integer.MAX\_VALUE}$ | **HIGH if misconfigured**: Must explicitly enforce PixelFormat (`OPAQUE` vs `RGBA_8888`) and handle 0° $\leftrightarrow$ 90° orientation transactions atomically. |
| **OEM Hardware Layer Collision** | BYD 360 Panoramic (`com.byd.panoramic`)<br>Apple CarPlay (`com.ts.carplay`) | Exclusive Hardware Composer (HWC) video plane takeover | **HIGH**: Presenting an overlay during AVM 360 engagement collides with HWC display pipes. |
| **Video Playback Surfaces** | `VideoPlaybackActivity.java`<br>`ZoomableVideoView.kt` | `TextureView` vs `SurfaceView` | **SAFE**: Overdrive correctly uses `TextureView` rather than `SurfaceView`, avoiding hardware punch-through holes in HWC planes. |
| **VirtualDisplay Buffers** | `RemoteDevVirtualDisplay.kt` | `createVirtualDisplay` + `ImageReader` | **MEDIUM**: Buffer queue starvation risk if images are not closed promptly (mitigated via Kotlin `use` blocks). |

---

## 5. The Solution: Unified `SafeOverlayHelper` Architecture

To eliminate this vulnerability permanently without affecting video recording or HUD responsiveness, we implemented a centralized sanitization module: `SafeOverlayHelper.kt`.

### 1. Forced Pure Software Canvas Rendering
Instead of letting the OS initialize an Adreno EGL context for UI overlays, `SafeOverlayHelper` forces the window to render into a CPU-backed Skia surface:
```kotlin
// Strip hardware acceleration flag from WindowManager.LayoutParams
lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED.inv()

// Force software rendering on the root view hierarchy
view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
```
**Why this works**:
* The overlay is drawn by Skia into a regular memory buffer.
* SurfaceFlinger treats it as a simple linear buffer, **completely bypassing `GLESRenderEngine::create` and `validate_resource_memory_layout_metadata`**.
* The GPU never creates EGL image handles for the overlay, completely eliminating the crash trigger.

### 2. Guarding Against OEM Display Pipe Collisions
`SafeOverlayHelper` hooks into `ProjectionStateMonitor.isSafeToDisplay()`:
```kotlin
fun isSafeToDisplay(): Boolean {
    return !ProjectionStateMonitor.isProjectionActive
}
```
If the driver engages reverse gear (bringing up BYD 360 AVM) or CarPlay is active in the foreground, non-critical overlays are gracefully suppressed or deferred, preventing HWC plane collisions.

### 3. Bounded Geometries (`WRAP_CONTENT`)
`MessageOverlayService` previously inflated a full-screen `MATCH_PARENT` $\times$ `MATCH_PARENT` scrim covering the entire 1920×1080 panel. This was changed to bounded layouts (`WRAP_CONTENT`), reducing compositor surface footprint.

### 4. StatusOverlayService Foreground Service Alignment
In addition to the rendering fix, `StatusOverlayService` was failing to enter foreground on Android 10 due to a type mismatch:
* `AndroidManifest.xml` declared `android:foregroundServiceType="specialUse"` (`0x40000000`).
* At runtime on Android 10 (API 29), the service requested `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (`0x00000001`).
* The platform threw:
  `foregroundServiceType 0x00000001 is not a subset of attribute 0x40000000`.
* We updated the manifest to:
  ```xml
  android:foregroundServiceType="specialUse|dataSync"
  ```
  restoring the persistent REC / TRIP / MIC status pill on boot.

---

## 6. Summary for Upstream Merges

When developing or integrating new overlay features (such as blind-spot HUDs, speed alerts, or remote communication widgets):
1. **Never** invoke raw `windowManager.addView(view, lp)` directly.
2. **Always** use `SafeOverlayHelper.safeAddView(windowManager, view, lp)`.
3. **Always** ensure non-video overlays strip `FLAG_HARDWARE_ACCELERATED` and enforce `LAYER_TYPE_SOFTWARE`.
4. Keep `fast_cam` offscreen GL contexts isolated from the WindowManager's window composition pipeline.
