#pragma once
// Remaps vertex input locations in SPIR-V vertex shaders to fit within the host GPU's
// maxVertexInputAttributes limit.
//
// On PowerVR DXT (Google Tensor G5) and other mobile GPUs, maxVertexInputAttributes is 16.
// ReXGlue's GTA IV vertex shaders assign usage-based locations in the range 0..39 (e.g.
// color = 17, blend indices = 18, blend weights = 19). Although no shader uses more than
// 8 attributes at once, locations >= 16 cause pipeline creation rejection on PowerVR.
//
// This remapper compacts active input locations into contiguous indices [0..N-1] while
// preserving original_location so vertex declarations map correctly to vertex buffers.

#include <cstdint>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace rex::graphics::gta4_native {

inline uint32_t RemapSpirvVertexInputLocations(
    std::vector<uint32_t>& words,
    const std::unordered_map<uint32_t, uint32_t>& old_to_new_loc) {
  constexpr uint16_t kOpDecorate = 71;
  constexpr uint16_t kOpVariable = 59;
  constexpr uint32_t kDecorationLocation = 30;
  constexpr uint32_t kStorageClassInput = 1;

  if (words.size() < 5 || words[0] != 0x07230203u || old_to_new_loc.empty()) return 0;

  // 1. Collect all variable IDs declared with StorageClass::Input.
  std::unordered_set<uint32_t> input_variable_ids;
  for (size_t i = 5; i < words.size();) {
    const uint32_t count = words[i] >> 16;
    if (!count || i + count > words.size()) break;
    const uint16_t opcode = uint16_t(words[i] & 0xFFFF);
    if (opcode == kOpVariable && count >= 4 && words[i + 3] == kStorageClassInput) {
      input_variable_ids.insert(words[i + 2]);
    }
    i += count;
  }

  if (input_variable_ids.empty()) return 0;

  // 2. Rewrite OpDecorate %var Location <old_loc> -> <new_loc> for input variables only.
  uint32_t remapped_count = 0;
  for (size_t i = 5; i < words.size();) {
    const uint32_t count = words[i] >> 16;
    if (!count || i + count > words.size()) break;
    const uint16_t opcode = uint16_t(words[i] & 0xFFFF);
    if (opcode == kOpDecorate && count >= 4 &&
        words[i + 2] == kDecorationLocation &&
        input_variable_ids.count(words[i + 1])) {
      auto it = old_to_new_loc.find(words[i + 3]);
      if (it != old_to_new_loc.end()) {
        words[i + 3] = it->second;
        ++remapped_count;
      }
    }
    i += count;
  }

  return remapped_count;
}

}  // namespace rex::graphics::gta4_native
