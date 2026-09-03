# Pull Request: [DiLink 5.0] Native FastCam 4K Dual-Pipeline & Ultra-HD Hardware Capture

## Summary

This PR replaces the legacy DiLink 5.0 camera capture (`qcarcam_test` XML pipe injection) with a high-performance, zero-copy native daemon (`fast_cam_capture`) and client bridge (`libfast_cam_client.so`), introducing **Dual-Pipeline 4K Ultra-HD capture**:

1. **Dual-Pipeline Video Architecture**:
   - **Local Storage (Dashcam / Sentry)**: Encodes full-resolution 4K Ultra-HD ($3840 \times 2160$ / $3840 \times 2600$) at 30 FPS using Qualcomm hardware HEVC (`c2.qti.hevc.encoder`) at 12 Mbps. Preserves 100% of native sensor pixels across all 4 cameras with zero decimating artifacts.
   - **Remote Web & Pad Live View**: Retains lightweight 720p H.264 streaming (`c2.qti.avc.encoder` at 1.5 Mbps) for responsive viewing on 4G connections.

2. **Zero-Copy Abstract Socket IPC**:
   - Uses abstract UNIX domain socket `@fast_cam.sock`, bypassing Android SELinux filesystem permission restrictions.
   - High-throughput ARM NEON color conversion and 2x2 / 4K composition.

3. **Charging Hardware Interlock**:
   - Enforces `GEAR_P` during vehicle charging sessions, preventing GPS jitter in garages from falsely triggering driving trip recordings.

---

## Precompiled Binaries & Build Instructions

To ensure seamless compilation without requiring proprietary Qualcomm vendor camera SDK headers:

- **Release Archive**: `frame_grabber_light/release/overdrive_fast_cam_release.tar.gz` (SHA-256: `ec116567243135e9d2fcea58386e1483920cb1248f00cda60b2f2bfd81d9e7ba`)
- **Automated Gradle Task**: The Gradle build automatically runs `:app:downloadFastCam` before compiling CMake native targets. It extracts the client library (`libfast_cam_client.so`), capture daemon (`fast_cam_capture`), and bridge header (`fast_cam_bridge.h`) into their proper directories automatically.
- **Manual Build**: Run `./gradlew :app:assembleDebug` as usual.
