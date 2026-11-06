# PowerVR Vulkan Vertex Input Attribute Limit Workaround

## 1. Device Context & Hardware Limit
* **Device**: Google Pixel 10 Pro XL (`mustang`)
* **GPU**: Imagination PowerVR D-Series `DXT-48-1536 MC1` (Driver version 25.3 / API 37)
* **Vulkan Limit**: `VkPhysicalDeviceLimits::maxVertexInputAttributes = 16`
  * Valid vertex attribute locations strictly span `0 .. 15`. Any `location >= 16` fails Vulkan pipeline creation (`VK_ERROR_INITIALIZATION_FAILED` with `vertex-input-location-limit`).

## 2. Root Cause in GTA IV Pipeline
In GTA IV's native Vulkan backend (`glue/rexglue-sdk-main/src/graphics/gta4_native/`):
* Vertex shader input usages are mapped to fixed Vulkan attribute locations via `ConvertVertexUsageToLocation`:
  - `Usage::Position` -> Location `0`
  - `Usage::Normal` -> Location `1`
  - `Usage::Tangent` -> Location `2`
  - `Usage::Binormal` -> Location `3`
  - `Usage::Color` -> Location `17`  (Exceeds 15!)
  - `Usage::BlendIndices` -> Location `18` (Exceeds 15!)
  - `Usage::BlendWeight` -> Location `19` (Exceeds 15!)
  - `Usage::TexCoord (indices 4..15)` -> Location `20 .. 31` (Exceeds 15!)

On desktop GPUs (NVIDIA, AMD) and Qualcomm Adreno GPUs, `maxVertexInputAttributes` is typically 32 or 64, so sparse locations up to 31 succeed without error.
On PowerVR, 100% of pipeline creations using vertex colors, skinning/skeletal animation (blend indices/weights), or secondary texture coordinates failed immediately.

## 3. Solution Architecture
A typical draw call in GTA IV never uses more than 4–8 active attributes simultaneously. The failure occurred only because the location indices were *sparse* and uncompacted.

### A. Dynamic SPIR-V Vertex Input Remapping (`native_spirv_vertex_remap.h`)
* Inspects SPIR-V bytecode of the vertex shader prior to `vkCreateShaderModule`.
* Identifies all active vertex input variables (`OpVariable` with `StorageClassInput`).
* Gathers their decorated locations (`OpDecorate %var Location <loc>`).
* If `max_location >= 16`, sorts active locations and maps them into a dense sequence `0, 1, 2, ..., N-1` (where $N \le 16$).
* Rewrites the `OpDecorate` bytecode instructions in-place to the new dense locations.

### B. Original Location Tracking (`graphics_system.h` & `geometry.h`)
* `struct NativeVertexInput` retains `uint32_t original_location = 0;`.
* When the game runtime declares vertex buffers and streams (`ConvertVertexUsageToLocation(...)` in `geometry.h` and `graphics_system.cpp`), it matches against `shader_input.original_location`, but binds the pipeline attribute `location` to `shader_input.remapped_location`.

### C. Affected Files
1. `glue/rexglue-sdk-main/src/graphics/gta4_native/native_spirv_vertex_remap.h`:
   - Contains SPIR-V parser and bytecode rewriter `RemapSpirvVertexInputLocations`.
2. `glue/rexglue-sdk-main/src/graphics/gta4_native/graphics_system.h`:
   - Adds `original_location` to `NativeVertexInput`.
3. `glue/rexglue-sdk-main/src/graphics/gta4_native/geometry.h`:
   - Checks `value.original_location == ConvertVertexUsageToLocation(...)`.
4. `glue/rexglue-sdk-main/src/graphics/gta4_native/graphics_system.cpp`:
   - Applies remapping in `CreatePipelineLayoutAndStages`, `LoadCustomShader`, and pipeline vertex attribute binding matching.

## 4. Build & Deployment
* Compiled via GitHub Actions (`.github/workflows/build-native.yml`) using Android NDK r29 targeting `arm64-v8a`.
* Replaced `lib/arm64-v8a/librexgpu-gta4-native.so` inside the Liberty Recompiled APK (`com.libertyrecomp`).
* Re-signed using APK Signature Scheme v2/v3 (`sign-apk-py`).
