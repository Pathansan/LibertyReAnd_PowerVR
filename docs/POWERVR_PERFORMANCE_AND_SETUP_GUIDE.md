# PowerVR Setup, Troubleshooting, and Performance Optimization Guide

This document is the definitive technical reference for running and optimizing **Liberty Recompiled (GTA IV Android)** on **Imagination PowerVR D-Series** GPUs (such as the **Google Tensor G5** on the **Google Pixel 10 Pro XL**). 

If you or a future developer / AI assistant need to revisit this repository, rebuild the code, or troubleshoot a regression, this document outlines every step taken, every bug resolved, and the exact performance tuning parameters.

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

### Recommended `args.txt` Preset for PowerVR / Tensor G5

Create or edit `/sdcard/Android/data/com.libertyrecomp/files/args.txt` with the following configuration:

```ini
# Logging & Diagnostics (Disabling verbose logging saves substantial CPU overhead)
--log_level=warn
--diagnostics=false

# Frame Pacing & Latency
--gta4_fps_guard=30
--gta4_native_max_queued_frames=2

# Shadow Map Optimizations
# Redraw sun shadow maps every 2 frames instead of every frame
--gta4_native_shadow_interval=2
# Skip shadow casting for tiny objects (bottles, litter, small poles)
--gta4_native_shadow_min_indices=64
--gta4_shadow_distance_scale=0.85

# Reflection Optimizations
# Update vehicle cubemap reflection every 2 frames
--gta4_native_environment_reflection_interval=2
# Update water reflection every 2 frames
--gta4_native_water_reflection_interval=2
--gta4_reflection_capture_distance=near

# World & Traffic Density (Reduces CPU AI, physics, and draw calls)
--gta4_dynamic_draw_distance=true
--gta4_dynamic_draw_distance_min=0.75
--gta4_traffic_density_scale=0.35
--gta4_parked_car_density_scale=0.35
--gta4_ped_density_scale=0.35
--gta4_scenario_ped_density_scale=0.35
```

### In-Game Display Settings Recommendation
From the in-game Pause Menu -> **Display**:
* **Resolution**: 1280x720 (720p) with native display upscaling (matches original Xbox 360 target).
* **Shadow Quality**: Medium or High (with shadow interval 2 above, performance remains smooth).
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
| `.github/workflows/build-apk.yml` | GitHub Actions workflow to build, package, and sign the release APK. |
| `.github/workflows/build-native.yml` | GitHub Actions workflow to build the standalone `librexgpu-gta4-native.so`. |
