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
// bit alignment is preserved). Cavern's UpdateSource() render integration — which
// pulls in Listener/Source/screen-lock math — is NOT ported; instead
// resolved_position() applies the differential accumulation and the OAMD
// normalized-cube -> render-space mapping, which is the decode-relevant part.
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

// BitExtractor.ReadSigned, reproduced faithfully. Note: with value = read(bits),
// the sign bit (1<<bits) is above the read range, so `sign` is always 0 and the
// result is 0 — this is exactly Cavern's behaviour, kept for A/B parity (not
// "fixed"). C#'s int shift masks the count by 31; we mask too, both to match C#
// and to avoid C++ shift-count UB (the count here can exceed 31).
inline int read_signed(BitReader& br, unsigned bits) {
  int value = static_cast<int>(br.read(bits));
  int sign = value & (1 << bits);
  const int shift = ((31 - static_cast<int>(bits)) + value - sign) & 31;
  return sign << shift;
}

class ObjectInfoBlock {
 public:
  bool valid_position() const { return valid_position_; }
  bool is_bed() const { return anchor_ == ObjectAnchor::kSpeaker; }
  float gain() const { return gain_; }
  float size() const { return size_; }
  ObjectAnchor anchor() const { return anchor_; }
  const Vec3& raw_position() const { return position_; }

  // Reads a new info block for one object (blk = block index within the frame).
  void update(BitReader& br, int blk, bool bed_or_isf_object) {
    const bool inactive = br.read_bit();
    const int basic_info_status = inactive ? 0 : (blk == 0 ? 1 : static_cast<int>(br.read(2)));
    if ((basic_info_status & 1) == 1) {
      object_basic_info(br, basic_info_status == 1);
    }

    int render_info_status = 0;
    if (!inactive && !bed_or_isf_object) {
      render_info_status = blk == 0 ? 1 : static_cast<int>(br.read(2));
    }
    if ((render_info_status & 1) == 1) {
      object_render_info(br, blk, render_info_status == 1);
    }

    if (br.read_bit()) {  // additional table data
      br.skip((static_cast<int>(br.read(4)) + 1) * 8);
    }

    if (bed_or_isf_object) anchor_ = ObjectAnchor::kSpeaker;
  }

  // Differential accumulation + normalized-cube -> render-space mapping
  // (X*2-1, Z, Y*-2+1), Cavern's axis order. Room-distance and screen-anchor
  // transforms from UpdateSource() are intentionally not applied here.
  Vec3 resolved_position() {
    if (valid_position_ && anchor_ != ObjectAnchor::kSpeaker) {
      if (differential_position_) {
        position_ = {clamp01(last_precise_.x + position_.x),
                     clamp01(last_precise_.y + position_.y),
                     clamp01(last_precise_.z + position_.z)};
      } else {
        last_precise_ = position_;
      }
    }
    return {position_.x * 2.0f - 1.0f, position_.z, position_.y * -2.0f + 1.0f};
  }

 private:
  static float clamp01(float v) { return std::min(1.0f, std::max(0.0f, v)); }

  void object_basic_info(BitReader& br, bool read_all_blocks) {
    const int blocks = read_all_blocks ? 3 : static_cast<int>(br.read(2));
    if ((blocks & 2) != 0) {  // gain
      int gain_helper = static_cast<int>(br.read(2));
      float g;
      switch (gain_helper) {
        case 0: g = 1.0f; break;
        case 1: g = 0.0f; break;
        case 2: {
          gain_helper = static_cast<int>(br.read(6));
          g = qmath::db_to_gain(static_cast<float>(gain_helper < 15 ? 15 - gain_helper : 14 - gain_helper));
          break;
        }
        default: g = -1.0f; break;
      }
      gain_ = g * 0.707f;  // 3 dB anti-clip attenuation
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
        position_ = {read_signed(br, 3) * kXyScale, read_signed(br, 3) * kXyScale,
                     read_signed(br, 3) * kZScale};
      } else {
        const int pos_x = static_cast<int>(br.read(6));
        const int pos_y = static_cast<int>(br.read(6));
        const int pos_z = ((static_cast<int>(br.read(1)) << 1) - 1) * static_cast<int>(br.read(4));
        position_ = {std::min(1.0f, pos_x * kXyScale), std::min(1.0f, pos_y * kXyScale),
                     std::min(1.0f, pos_z * kZScale)};
      }
      if (br.read_bit()) {                 // distance specified
        if (br.read_bit()) {               // infinite distance
          distance_ = 100.0f;
        } else {
          distance_ = kDistanceFactors[br.read(4)];
        }
      } else {
        distance_ = std::nanf("");
      }
    }

    if ((blocks & 2) != 0) br.skip(4);  // zone constraints (unused)

    if ((blocks & 4) != 0) {  // scaling / size
      switch (br.read(2)) {
        case 0: size_ = 0.0f; break;
        case 1: size_ = br.read(5) * kSizeScale; break;
        case 2: {
          const float x = br.read(5) * kSizeScale;
          const float y = br.read(5) * kSizeScale;
          const float z = br.read(5) * kSizeScale;
          size_ = std::sqrt(x * x + y * y + z * z);
          break;
        }
        default: size_ = -1.0f; break;
      }
    }

    if ((blocks & 8) != 0 && br.read_bit()) {  // screen anchoring
      anchor_ = ObjectAnchor::kScreen;
      screen_factor_ = (static_cast<int>(br.read(3)) + 1) * 0.125f;
      depth_factor_ = kDepthFactors[br.read(2)];
    }

    br.skip(1);  // snap to nearest channel (unused)
  }

  static constexpr float kXyScale = 1.0f / 62.0f;
  static constexpr float kZScale = 1.0f / 15.0f;
  static constexpr float kSizeScale = 1.0f / 31.0f;
  static inline const float kDistanceFactors[16] = {
      1.1f, 1.3f, 1.6f, 2.0f, 2.5f, 3.2f, 4.0f, 5.0f,
      6.3f, 7.9f, 10.0f, 12.6f, 15.8f, 20.0f, 25.1f, 50.1f};
  static inline const float kDepthFactors[4] = {0.25f, 0.5f, 1.0f, 2.0f};

  bool valid_position_ = false;
  bool differential_position_ = false;
  float gain_ = -1.0f;
  float distance_ = 0.0f;
  float size_ = -1.0f;
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
