# PowerVR Setup, Troubleshooting, and Performance Optimization Guide

This document is the definitive technical reference for running and optimizing **Liberty Recompiled (GTA IV Android)** on **Imagination PowerVR D-Series** GPUs (such as the **Google Tensor G5** on the **Google Pixel 10 Pro XL**). 

If you or a future developer / AI assistant need to revisit this repository, rebuild the code, or troubleshoot a regression, this document outlines every step taken, every bug resolved, and the exact performance tuning parameters.

> **Verified against `15e63c7` and subsequent on-device profiling.**
> `gta4_native_fs_relaxed_precision` is **off** (prevents shadow acne and banding).
> Upstream CPU pinning uses `android_pin_hot_threads=true`.
> When in doubt, `os/android/app/src/main/assets/default_args.txt` is the shipped truth.

---

## 1. What Was Done to Get the Game Running

### A. The Core Hardware Bottleneck: `maxVertexInputAttributes = 16`
* **Symptom**: On launch, the game produced a black screen or crashed during Vulkan pipeline compilation (`VK_ERROR_INITIALIZATION_FAILED`), with error `vertex-input-location-limit`.
* **Root Cause**:
  - Desktop GPUs (NVIDIA/AMD) and Qualcomm Adreno support 32 to 64 vertex input attributes (`VkPhysicalDeviceLimits::maxVertexInputAttributes`).
  - Mobile PowerVR DXT GPUs strictly enforce **16 attributes** (valid indices `0 .. 15`).
  - Stock GTA IV ReXGlue shaders statically map vertex inputs to sparse semantic indices up to **31**:
    - `Usage::Position` -> Location `0`
    - `Usage::Normal` -> Location `1`
    - `Usage::Tangent` -> Location `2`
    - `Usage::Binormal` -> Location `3`
    - `Usage::Color` -> Location `17` (exceeds 15!)
    - `Usage::BlendIndices` -> Location `18` (exceeds 15!)
    - `Usage::BlendWeight` -> Location `19` (exceeds 15!)
    - `Usage::TexCoord (indices 4..15)` -> Locations `20 .. 31` (exceeds 15!)
  - Even though no individual draw call ever used more than 6–8 active attributes simultaneously, any pipeline with vertex color or skeletal skinning failed pipeline creation immediately.

### B. The Code Solution: In-Memory SPIR-V Compaction
Instead of recompiling or refactoring hundreds of shaders, we implemented a zero-overhead runtime bytecode remapper:
1. **`glue/rexglue-sdk-main/src/graphics/gta4_native/native_spirv_vertex_remap.h`**:
   - Intercepts vertex shader SPIR-V bytecode before `vkCreateShaderModule`.
   - Inspects `OpVariable` declarations with `StorageClassInput`.
   - Extracts active attribute locations (`OpDecorate %var Location <loc>`).
   - If `max_location >= 16`, compacts active locations into a dense sequence `0, 1, ..., N-1` ($N \le 16$).
   - Rewrites the `OpDecorate` bytecode in-place.
2. **Original Location Tracking**:
   - `struct NativeVertexInput` in `graphics_system.h` stores both `original_location` and `remapped_location`.
   - `geometry.h` and `graphics_system.cpp` match vertex buffer stream declarations against `original_location`, but assign pipeline attributes to `remapped_location`.

### C. Build Pipeline Overhaul
1. **Modern DXC Compiler**:
   - Upgraded DXC shader compiler on Ubuntu Linux runners to official modern Microsoft release (`DirectXShaderCompiler v1.9.2609`).
   - Installed `spirv-tools` and `glslang-tools` for shader override validation.
2. **Binary Stripping & Profiler Decoupling**:
   - Disabled Tracy profiler (`-DREXGLUE_ENABLE_TRACY=OFF`) to remove hard dependency on `libTracyClient.so`.
   - Stripped binary with `llvm-strip --strip-unneeded` (reduced size from ~80MB to 29.5MB).
3. **Automated CI/CD**:
   - Created [`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml) which automatically compiles the native library, injects it into the APK, aligns with `zipalign -p 4`, and signs with APK Signature Scheme v2+v3 via `apksigner`.

### D. Device Runtime Configuration
1. **Driver Setting (`driver.txt`)**:
   - PowerVR cannot use the bundled Adreno Mesa Turnip driver.
   - The file `/sdcard/Android/data/com.libertyrecomp/files/driver.txt` **must contain `system`**:
     ```bash
     echo "system" > /sdcard/Android/data/com.libertyrecomp/files/driver.txt
     ```
2. **Storage Permissions (Android 11–17 Scoped Storage)**:
   - When copying or restoring files into `/sdcard/Android/data/com.libertyrecomp/files/`, permissions must allow the app UID to read/write:
     ```bash
     adb shell chmod -R 777 /sdcard/Android/data/com.libertyrecomp/files/
     ```

---

## 2. Performance Optimization Guide

Mobile GPUs like the PowerVR DXT use **Tile-Based Deferred Rendering (TBDR)**. The GPU thrives when render passes stay entirely in on-chip USC memory. When shadow maps, reflections, and heavy resolutions force repeated DRAM flushes, frame rates drop significantly.

### Architectural & Code-Level Optimizations (Zero Visual Compromise)

1. **CPU Thread Pinning (`android_pin_hot_threads`)**:
   - Modern ARM SoCs like the Google Tensor G5 feature a tri-cluster architecture ($1\times$ Cortex-X4 @ 3.78 GHz, $5\times$ Cortex-A725 @ 3.05 GHz, $2\times$ Cortex-A520 @ 2.24 GHz).
   - On-device A/B measurement showed dynamic thread re-ranking differences fall within thermal measurement noise, so the stable upstream `android_pin_hot_threads=true` is used by default. Experimental dynamic ranking remains available via `--gta4_thread_pinning=1..4`.

2. **Pipeline Slack & Decoupled Frame Pacing (`android_frame_wait_hook.cpp`)**:
   - The title engine originally stalls in `sub_82A46D70` whenever more than 2 frames are pending. On Android's multi-threaded command bridge, this 2-frame clamp causes GPU starvation bubbles.
   - Setting `gta4_extra_frames_in_flight = 1` and `gta4_native_max_queued_frames = 3` gives the CPU pipeline headroom to stay 1 frame ahead without stalling on the GPU.

3. **PowerVR FP16 Math — Currently Disabled (`graphics_system.cpp`)**:
   - PowerVR DXT USC clusters do have dual-rate FP16 / FP32 ALUs, but `gta4_native_fs_relaxed_precision=true` was found to cause shadow acne and banding in GTA IV's shadow and lightmap passes (commit `15e63c7`). It is off by default and should stay off until the affected shaders are identified and excluded from the relaxed path.
   - Measure before re-enabling: it is not a free 2x on this title.

4. **Skipping Redundant Layout Barriers (`graphics_system.cpp`)**:
   - Enabling `gta4_native_skip_attachment_barriers = true` avoids emitting redundant `vkCmdPipelineBarrier` transitions when attachment images are already in optimal layout, keeping render passes inside on-chip tile memory.

5. **Diagnostic Profiling Stripping (`graphics_system.cpp`)**:
   - Disabled `gta4_profile_native_detailed_gpu` and `gta4_profile_native_detailed_cpu` by default, eliminating ~512 `vkCmdWriteTimestamp(BOTTOM_OF_PIPE)` driver queries per frame.

---

### Uncompromised Visual Preset (`args.txt`)

Create or edit `/sdcard/Android/data/com.libertyrecomp/files/args.txt` with the following configuration:

```ini
# Logging & Diagnostics (Clean production run)
--log_level=warn
--diagnostics=false
--gta4_profile_native_detailed_gpu=false
--gta4_profile_native_detailed_cpu=false
--gta4_profile_native_memory_deep=false

# Full Graphics Fidelity (Uncompromised Visuals)
--gta4_output_height_cap=720
--gta4_native_upscaler=native
--gta4_draw_distance_scale=1.0
--gta4_dynamic_draw_distance=false
--gta4_shadow_distance_scale=1.0
--gta4_native_shadow_interval=1
--gta4_native_shadow_min_indices=0
--gta4_reflection_capture_distance=original
--gta4_native_environment_reflection_interval=1
--gta4_native_water_reflection_interval=1
--gta4_traffic_density_scale=0.40
--gta4_parked_car_density_scale=0.40
--gta4_ped_density_scale=0.40
--gta4_scenario_ped_density_scale=0.40

# PowerVR DXT & Vulkan Architecture Optimizations (Zero visual loss)
--gta4_native_fs_relaxed_precision=false
--gta4_native_skip_attachment_barriers=true
--gta4_native_ieee_mul=2
--gta4_native_max_queued_frames=3
--gta4_extra_frames_in_flight=1
--gta4_frame_wait_sleep=true
--gta4_fps_guard=0
--gta4_frame_limit=30
--gta4_present_mode=vsync
--android_pin_hot_threads=true
--show_fps=true
```

### In-Game Display Settings Recommendation
From the in-game Pause Menu -> **Display**:
* **Resolution**: 1280x720 (720p) with native display upscaling (matches original Xbox 360 target).
* **Shadow Quality**: Medium or High. The preset above uses shadow interval 1 (minimum quality loss); the shipped `default_args.txt` uses interval 2 with `shadow_distance_scale=0.85` instead and holds 30 FPS more reliably.
* **Reflections**: Medium.
* **VSync**: ON (or lock to 30 FPS via `--gta4_fps_guard=30`).

---

## 3. Key Files Reference

| File | Purpose |
| :--- | :--- |
| `glue/rexglue-sdk-main/src/graphics/gta4_native/native_spirv_vertex_remap.h` | In-memory SPIR-V vertex input compaction engine for PowerVR. |
| `glue/rexglue-sdk-main/src/graphics/gta4_native/graphics_system.cpp` | Vulkan pipeline creation, draw call batching, and attribute binding. |
| `glue/rexglue-sdk-main/src/graphics/gta4_native/geometry.h` | Vertex declaration and stream location mapping. |
| `os/android/app/src/main/assets/default_args.txt` | Default command-line arguments bundled into the APK. |
| `.github/workflows/build-apk.yml` | Patches a prebuilt base APK (code-verified by SHA-256), injects the freshly built `classes.dex`/manifest/`default_args.txt` and the PowerVR `librexgpu-gta4-native.so`, then signs with the release keystore from secrets. |
| `.github/workflows/build-native.yml` | GitHub Actions workflow to build the standalone `librexgpu-gta4-native.so`. |
