// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Decoders/EnhancedAC3/ObjectInfoBlock.cs and the OAMD
// enums (ObjectAudioMetadataEnums.cs). This is the per-object OAMD decode: the
// spatial-position update (absolute or differential), gain, size and anchor.
//
// Scope: the bitstream DECODE is reproduced exactly (every Read/Skip/ReadBit, so
// bit alignment is preserved). On top of Cavern, every field the syntax carries
// is now KEPT instead of skipped — the coded standard-precision position, zone
// constraints, elevation enable, per-axis size, screen anchoring and snap — and
// the update/reuse status of each block is recorded, so ObjectAudioMetadata can
// resolve each object's state per ETSI TS 103 420 (clauses 5.2 and 5.6.1). The
// resolution itself (hold/default rules, differential coding against the
// previous block, distance and screen transforms) lives in
// object_audio_metadata.h; resolved_position() below is the original per-slot
// Cavern shortcut, kept for the host tests that pin it.
#ifndef TF_ATMOS_CAVERN_OBJECT_INFO_BLOCK_H
#define TF_ATMOS_CAVERN_OBJECT_INFO_BLOCK_H

#include <algorithm>
#include <cmath>

#include "../bit_reader.h"
#include "../vbap.h"  // tf::atmos::Vec3
#include "qmath.h"

namespace tf {
namespace atmos {
namespace cavern {

// OAMD bed-channel slots (NonStandardBedChannel).
enum class NonStandardBedChannel {
  kFrontLeft = 0, kFrontRight = 1, kCenter = 2, kLowFrequencyEffects = 3,
  kSurroundLeft = 4, kSurroundRight = 5, kRearLeft = 6, kRearRight = 7,
  kTopFrontLeft = 8, kTopFrontRight = 9, kTopSurroundLeft = 10, kTopSurroundRight = 11,
  kTopRearLeft = 12, kTopRearRight = 13, kWideLeft = 14, kWideRight = 15,
  kLowFrequencyEffects2 = 16, kMax = 17
};

enum class ObjectAnchor { kRoom, kScreen, kSpeaker };

// A two's-complement signed integer of `bits` width — how TS 103 420 clauses
// 5.6.1.1.12-14 define diff_pos3D_X/Y/Z_bits ("interpreted as a signed
// integer"), so a 3-bit delta spans -4..+3 position steps.
//
// Cavern's BitExtractor.ReadSigned computes `sign << (31 - bits) + value - sign`,
// which C# parses as `sign << ((31 - bits) + value - sign)` with a sign mask
// (1 << bits) that sits ABOVE the read range, so it always returns 0 — every
// differential position update decoded as "no movement", freezing objects at
// their last absolute position between absolute updates. The previous port kept
// that for A/B parity; the spec is unambiguous, so this decodes it properly.
inline int read_signed(BitReader& br, unsigned bits) {
  const int value = static_cast<int>(br.read(bits));
  const int sign_bit = 1 << (bits - 1);
  return (value & sign_bit) ? value - (1 << bits) : value;
}

class ObjectInfoBlock {
 public:
  // --- status of this metadata update block (TS 103 420 clause 5.6.4) --------
  // Status codes: 0 = default, 1 = full update, 2 = full reuse, 3 = mixed.
  bool inactive() const { return inactive_; }
  int basic_status() const { return basic_status_; }
  int render_status() const { return render_status_; }
  // Which fields this block signalled. Gain: object_gain_idx was present.
  bool has_gain() const { return has_gain_; }
  // The object_gain_idx value (0 = 0 dB, 1 = -inf, 2 = coded, 3 = previous
  // object's gain in this block) — idx 3 is resolved against the other objects.
  int gain_idx() const { return gain_idx_; }
  bool has_position() const { return valid_position_; }
  bool has_zone() const { return has_zone_; }
  bool has_size() const { return has_size_; }
  bool has_screen() const { return has_screen_; }

  // --- signalled values (only meaningful when the matching has_*() is true) --
  bool valid_position() const { return valid_position_; }
  bool differential() const { return differential_position_; }
  // Standard-precision coded position: absolute X,Y in 0..63, signed Z in
  // -15..15; or, for a differential block, the signed deltas.
  int pos_x_bits() const { return pos_bits_[0]; }
  int pos_y_bits() const { return pos_bits_[1]; }
  int pos_z_bits() const { return pos_bits_[2]; }
  // Distance factor (Table 15), +inf for b_object_at_infinity, NaN if unsignalled.
  float distance() const { return distance_; }
  int zone_constraints_idx() const { return zone_idx_; }
  bool enable_elevation() const { return enable_elevation_; }
  float width() const { return width_; }
  float depth() const { return depth_; }
  float height() const { return height_; }
  bool screen_ref() const { return anchor_ == ObjectAnchor::kScreen; }
  float screen_factor() const { return screen_factor_; }
  float depth_factor() const { return depth_factor_; }
  bool snap() const { return snap_; }

  // Set by the extended_object_element (clauses 5.5.13-15, 5.6.6), which
  // arrives after the object element in the same OAMD frame.
  void set_ext_precision(int x, int y, int z) { ext_prec_[0] = x; ext_prec_[1] = y; ext_prec_[2] = z; }
  int ext_prec(int axis) const { return ext_prec_[axis]; }
  void set_divergence(float d) { divergence_ = d; has_divergence_ = true; }
  bool has_divergence() const { return has_divergence_; }
  float divergence() const { return divergence_; }

  // --- original Cavern-shaped accessors ---------------------------------------
  bool is_bed() const { return anchor_ == ObjectAnchor::kSpeaker; }
  float gain() const { return gain_; }
  float size() const { return size_; }
  ObjectAnchor anchor() const { return anchor_; }
  const Vec3& raw_position() const { return position_; }

  // Reads a new info block for one object (blk = block index within the frame).
  void update(BitReader& br, int blk, bool bed_or_isf_object) {
    has_gain_ = has_zone_ = has_size_ = has_screen_ = has_divergence_ = false;
    valid_position_ = false;
    ext_prec_[0] = ext_prec_[1] = ext_prec_[2] = 0;
    inactive_ = br.read_bit();
    basic_status_ = inactive_ ? 0 : (blk == 0 ? 1 : static_cast<int>(br.read(2)));
    if ((basic_status_ & 1) == 1) {
      object_basic_info(br, basic_status_ == 1);
    }
    render_status_ = 0;
    if (!inactive_ && !bed_or_isf_object) {
      render_status_ = blk == 0 ? 1 : static_cast<int>(br.read(2));
    }
    if ((render_status_ & 1) == 1) {
      object_render_info(br, blk, render_status_ == 1);
    }
    if (br.read_bit()) {  // additional table data
      br.skip((static_cast<int>(br.read(4)) + 1) * 8);
    }
    if (bed_or_isf_object) anchor_ = ObjectAnchor::kSpeaker;
  }

  // Cavern's per-slot shortcut: differential accumulation against this same
  // block slot + the normalized-cube -> render-space map (X*2-1, Z, Y*-2+1).
  // NOT spec-exact (the spec's reference is the previous block, and it holds
  // unsignalled fields) — ObjectAudioMetadata::resolve() is what renders use.
  Vec3 resolved_position() {
    if (valid_position_ && anchor_ != ObjectAnchor::kSpeaker) {
      if (differential_position_) {
        position_ = {clamp(last_precise_.x + position_.x, 0.0f, 1.0f),
                     clamp(last_precise_.y + position_.y, 0.0f, 1.0f),
                     clamp(last_precise_.z + position_.z, -1.0f, 1.0f)};
      } else {
        last_precise_ = position_;
      }
    }
    return {position_.x * 2.0f - 1.0f, position_.z, position_.y * -2.0f + 1.0f};
  }

 private:
  static float clamp(float v, float lo, float hi) { return std::min(hi, std::max(lo, v)); }

  void object_basic_info(BitReader& br, bool read_all_blocks) {
    const int blocks = read_all_blocks ? 3 : static_cast<int>(br.read(2));
    if ((blocks & 2) != 0) {  // gain
      has_gain_ = true;
      gain_idx_ = static_cast<int>(br.read(2));
      float g;
      switch (gain_idx_) {
        case 0: g = 1.0f; break;
        case 1: g = 0.0f; break;
        case 2: {
          const int bits = static_cast<int>(br.read(6));
          g = qmath::db_to_gain(static_cast<float>(bits < 15 ? 15 - bits : 14 - bits));
          break;
        }
        default: g = -1.0f; break;  // previous object's gain — resolved by the frame
      }
      gain_ = g >= 0.0f ? g * 0.707f : g;  // 3 dB anti-clip attenuation
    }
    if ((blocks & 1) != 0 && !br.read_bit()) {  // priority (unused)
      br.skip(5);
    }
  }

  void object_render_info(BitReader& br, int blk, bool read_all_blocks) {
    const int blocks = read_all_blocks ? 15 : static_cast<int>(br.read(4));
    valid_position_ = (blocks & 1) != 0;
    if (valid_position_) {
      differential_position_ = blk != 0 && br.read_bit();
      if (differential_position_) {
        pos_bits_[0] = read_signed(br, 3);
        pos_bits_[1] = read_signed(br, 3);
        pos_bits_[2] = read_signed(br, 3);
        position_ = {pos_bits_[0] * kXyScale, pos_bits_[1] * kXyScale, pos_bits_[2] * kZScale};
      } else {
        pos_bits_[0] = static_cast<int>(br.read(6));
        pos_bits_[1] = static_cast<int>(br.read(6));
        const int sign = (static_cast<int>(br.read(1)) << 1) - 1;
        pos_bits_[2] = sign * static_cast<int>(br.read(4));
        position_ = {std::min(1.0f, pos_bits_[0] * kXyScale), std::min(1.0f, pos_bits_[1] * kXyScale),
                     std::min(1.0f, pos_bits_[2] * kZScale)};
      }
      if (br.read_bit()) {                 // distance specified
        if (br.read_bit()) {               // infinite distance
          distance_ = INFINITY;
        } else {
          distance_ = kDistanceFactors[br.read(4)];
        }
      } else {
        distance_ = std::nanf("");
      }
    }
    if ((blocks & 2) != 0) {  // zone constraints (clause 5.6.1.6)
      has_zone_ = true;
      zone_idx_ = static_cast<int>(br.read(3));
      enable_elevation_ = br.read_bit();
    }
    if ((blocks & 4) != 0) {  // scaling / size (clause 5.6.1.2)
      has_size_ = true;
      switch (br.read(2)) {
        case 0: width_ = depth_ = height_ = size_ = 0.0f; break;
        case 1: width_ = depth_ = height_ = size_ = br.read(5) * kSizeScale; break;
        case 2: {
          width_ = br.read(5) * kSizeScale;
          depth_ = br.read(5) * kSizeScale;
          height_ = br.read(5) * kSizeScale;
          size_ = std::sqrt(width_ * width_ + depth_ * depth_ + height_ * height_);
          break;
        }
        default: has_size_ = false; size_ = -1.0f; break;  // reserved: keep the last size
      }
    }
    if ((blocks & 8) != 0) {  // screen anchoring (clause 5.6.1.1.18-20)
      has_screen_ = true;
      if (br.read_bit()) {
        anchor_ = ObjectAnchor::kScreen;
        screen_factor_ = (static_cast<int>(br.read(3)) + 1) * 0.125f;
        depth_factor_ = kDepthFactors[br.read(2)];
      } else {
        anchor_ = ObjectAnchor::kRoom;
        screen_factor_ = 0.0f;
      }
    }
    snap_ = br.read_bit();  // channel lock (clause 5.2.5)
  }

  static constexpr float kXyScale = 1.0f / 62.0f;
  static constexpr float kZScale = 1.0f / 15.0f;
  static constexpr float kSizeScale = 1.0f / 31.0f;
  static inline const float kDistanceFactors[16] = {
      1.1f, 1.3f, 1.6f, 2.0f, 2.5f, 3.2f, 4.0f, 5.0f,
      6.3f, 7.9f, 10.0f, 12.6f, 15.8f, 20.0f, 25.1f, 50.1f};
  static inline const float kDepthFactors[4] = {0.25f, 0.5f, 1.0f, 2.0f};

  bool inactive_ = false;
  int basic_status_ = 0;
  int render_status_ = 0;
  bool has_gain_ = false;
  int gain_idx_ = 0;
  bool has_zone_ = false;
  bool has_size_ = false;
  bool has_screen_ = false;
  bool has_divergence_ = false;
  bool valid_position_ = false;
  bool differential_position_ = false;
  int pos_bits_[3] = {0, 0, 0};
  int ext_prec_[3] = {0, 0, 0};
  float gain_ = -1.0f;
  float distance_ = 0.0f;
  float size_ = -1.0f;
  float width_ = 0.0f, depth_ = 0.0f, height_ = 0.0f;
  int zone_idx_ = 0;
  bool enable_elevation_ = true;
  bool snap_ = false;
  float divergence_ = 0.0f;
  float depth_factor_ = 0.0f;
  float screen_factor_ = 0.0f;
  ObjectAnchor anchor_ = ObjectAnchor::kRoom;
  Vec3 position_{};
  Vec3 last_precise_{};
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_OBJECT_INFO_BLOCK_H
