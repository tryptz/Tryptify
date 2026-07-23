// Clean-room OAMD (Object Audio Metadata) position decoding — ETSI TS 103 420.
//
// OAMD carries, per object per update, a position in the normalized unit cube
// [0,1]^3 plus a gain. Positions are coded either absolutely (fixed-width
// indices) or differentially (small signed steps from the previous update).
// This header decodes those indices back to normalized coordinates and maps
// them into the renderer's centered listening space, which is what the VBAP /
// HRTF back-ends consume.
//
// The bitstream *framing* (object count, per-block info) is scaffolded in
// oamd_parse.h; this header is the pure coordinate math, which is fully unit
// testable without reference content.
//
// Header-only, only <cmath>/<cstdint>; builds under the NDK and on host.
#ifndef TF_ATMOS_OAMD_H
#define TF_ATMOS_OAMD_H

#include <cmath>
#include <cstdint>

#include "vbap.h"

namespace tf {
namespace atmos {

// Quantization steps from TS 103 420: the X/Y axes use 6-bit (0..63) absolute
// indices spanning [0,1]; the Z axis uses a coarser grid. The differential mode
// codes signed steps scaled by these constants.
constexpr float kOamdXyScale = 1.0f / 62.0f;  // differential X/Y step
constexpr float kOamdZScale = 1.0f / 15.0f;   // differential Z step

// Absolute position: X and Y are 6-bit indices in [0,62] mapped to [0,1]; Z is a
// sign bit plus a 4-bit magnitude in [0,15] mapped to [0,1].
inline float decode_absolute_xy(uint32_t index6) {
  return static_cast<float>(index6) * kOamdXyScale;
}

inline float decode_absolute_z(uint32_t sign, uint32_t mag4) {
  const float m = static_cast<float>(mag4) * kOamdZScale;
  return sign ? -m : m;  // signed height around the listener plane
}

// Differential update: apply a signed 3-bit step (-3..+3, "reuse previous" is
// handled by the caller) to the previous normalized coordinate.
inline float apply_differential(float previous, int step, float scale) {
  return previous + static_cast<float>(step) * scale;
}

// Clamp a decoded normalized coordinate back into the legal unit interval; a
// stream may momentarily overshoot through differential accumulation.
inline float clamp_unit(float v) {
  if (v < 0.0f) return 0.0f;
  if (v > 1.0f) return 1.0f;
  return v;
}

// A normalized OAMD position in the unit cube (0.5,0.5,0.5 == dead center).
struct ObjectPositionNorm {
  float x = 0.5f;  // 0 = left,  1 = right
  float y = 0.5f;  // 0 = front, 1 = back
  float z = 0.5f;  // 0 = floor, 1 = ceiling (bed plane at 0.5 in this convention)
};

// Map the normalized unit-cube position into the renderer's centered space,
// following the plan's convention (X*2-1, Z, Y*-2+1): right-handed, listener at
// the origin, +x right, +y front, +z up, each axis in [-1, 1].
inline Vec3 map_to_render_space(const ObjectPositionNorm& p) {
  return {p.x * 2.0f - 1.0f,   // x: left..right  -> -1..+1
          p.y * -2.0f + 1.0f,  // y: front..back  -> +1..-1
          p.z * 2.0f - 1.0f};  // z: floor..ceil  -> -1..+1
}

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_OAMD_H
