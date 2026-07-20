// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Decoders/EnhancedAC3/ObjectAudioMetadata.cs and
// ObjectAudioElementMetadata.cs. This is the OAMD *frame* layer that sits above
// the per-object ObjectInfoBlock decode: it reads the object count, the program
// / bed-channel assignment, and the element metadata that owns the info blocks.
//
// Scope: the bitstream DECODE is reproduced exactly (every Read/Skip/ReadBit and
// the reverse-filled ReadBits, so bit alignment and bed-channel mapping match
// Cavern bit-for-bit). Cavern's UpdateSources() render integration — which pulls
// in Listener/Source/Vector3 interpolation — is NOT ported, matching
// object_info_block.h; the decoded data model (object count, beds, per-object
// info blocks) is exposed for the renderer/bed-panner to consume.
#ifndef TF_ATMOS_CAVERN_OBJECT_AUDIO_METADATA_H
#define TF_ATMOS_CAVERN_OBJECT_AUDIO_METADATA_H

#include <array>
#include <cstdint>
#include <vector>

#include "../bit_reader.h"
#include "object_info_block.h"  // ObjectInfoBlock, NonStandardBedChannel
#include "reference_channel.h"  // ReferenceChannel

namespace tf {
namespace atmos {
namespace cavern {

// Faithful port of BitExtractor.ReadBits(int): the C# method fills the array
// from the TOP index downward, so out[bits-1] is the FIRST bit read and out[0]
// is the LAST. This reversal is load-bearing for the bed-channel mapping below —
// do not "simplify" it to an ascending fill.
inline void read_bits_reversed(BitReader& br, bool* out, int bits) {
  while (bits-- > 0) out[bits] = br.read_bit() != 0u;
}

// One object-audio element: the per-object, per-block ObjectInfoBlock grid plus
// its block-offset/ramp timing. Non-object elements are recorded only by their
// negative min_offset() sentinel (Cavern: -1 - elementIndex).
class OAElementMD {
 public:
  // Timecode of the first update; a negative value flags a non-object element.
  short min_offset() const {
    return block_offset_factor_.empty() ? 0 : block_offset_factor_[0];
  }
  bool is_object_element() const { return is_object_element_; }
  int block_count() const { return static_cast<int>(ramp_duration_.size()); }
  int object_count() const { return static_cast<int>(info_blocks_.size()); }
  bool valid() const { return valid_; }

  // Per-object (dim 0), per-info-block (dim 1) decode results. Non-const so the
  // consumer can call ObjectInfoBlock::resolved_position() (which accumulates
  // differential state); const overload for read-only queries.
  ObjectInfoBlock& info_block(int obj, int blk) { return info_blocks_[obj][blk]; }
  const ObjectInfoBlock& info_block(int obj, int blk) const {
    return info_blocks_[obj][blk];
  }

  // Decodes an element. Returns false on an unsupported feature (mirrors Cavern
  // throwing UnsupportedFeatureException — here it degrades to a valid() flag).
  bool read(BitReader& br, bool alternate_object_present, int object_count,
            int bed_or_isf_objects) {
    valid_ = true;
    const int element_index = static_cast<int>(br.read(4));
    // Evaluate the position getter BEFORE the variable-bits read (C# guarantees
    // left-to-right; C++ does not, so capture it explicitly).
    const size_t pos_before = br.position();
    const uint32_t size_bits = br.read_variable_bits(4, 4);
    const size_t end_pos = pos_before + size_bits + 1;
    br.skip(alternate_object_present ? 5 : 1);
    if (element_index == kObjectElementIndex) {
      is_object_element_ = true;
      object_element(br, object_count, bed_or_isf_objects);
    } else {  // Other element types are unused by encoders.
      is_object_element_ = false;
      block_offset_factor_.assign(1, static_cast<short>(-1 - element_index));
    }
    br.seek(end_pos);  // Skip any padding to the element boundary.
    return valid_;
  }

 private:
  void object_element(BitReader& br, int object_count, int bed_or_isf_objects) {
    md_update_info(br);
    if (!valid_) return;
    if (!br.read_bit()) br.skip(5);  // Reserved.

    const int blocks = static_cast<int>(ramp_duration_.size());
    // Reuse the info-block grid across frames when the shape is unchanged, so
    // per-object differential-position state survives (Cavern's realloc guard).
    if (static_cast<int>(info_blocks_.size()) != object_count ||
        (object_count > 0 && static_cast<int>(info_blocks_[0].size()) != blocks)) {
      info_blocks_.assign(object_count, std::vector<ObjectInfoBlock>(blocks));
    }

    for (int obj = 0; obj < object_count; ++obj) {
      for (int blk = 0; blk < blocks; ++blk) {
        info_blocks_[obj][blk].update(br, blk, obj < bed_or_isf_objects);
      }
    }
  }

  void md_update_info(BitReader& br) {
    switch (br.read(2)) {
      case 0: sample_offset_ = 0; break;
      case 1: sample_offset_ = kSampleOffsetIndex[br.read(2)]; break;
      case 2: sample_offset_ = static_cast<uint8_t>(br.read(5)); break;
      default: valid_ = false; return;  // UnsupportedFeatureException("mdOffset")
    }
    const int count = static_cast<int>(br.read(3)) + 1;
    block_offset_factor_.assign(count, 0);
    ramp_duration_.assign(count, 0);
    for (int blk = 0; blk < count; ++blk) block_update_info(br, blk);
  }

  void block_update_info(BitReader& br, int blk) {
    block_offset_factor_[blk] =
        static_cast<short>(br.read(6) + sample_offset_);
    const int ramp_code = static_cast<int>(br.read(2));
    if (ramp_code == 3) {
      if (br.read_bit()) {
        ramp_duration_[blk] = kRampDurationIndex[br.read(4)];
      } else {
        ramp_duration_[blk] = static_cast<short>(br.read(11));
      }
    } else {
      ramp_duration_[blk] = kRampDurations[ramp_code];
    }
  }

  static constexpr int kObjectElementIndex = 1;
  static inline const uint8_t kSampleOffsetIndex[4] = {8, 16, 18, 24};
  static inline const short kRampDurations[3] = {0, 512, 1536};
  static inline const short kRampDurationIndex[16] = {
      32, 64, 128, 256, 320, 480, 1000, 1001,
      1024, 1600, 1601, 1602, 1920, 2000, 2002, 2048};

  bool valid_ = true;
  bool is_object_element_ = false;
  uint8_t sample_offset_ = 0;
  std::vector<short> block_offset_factor_;
  std::vector<short> ramp_duration_;
  std::vector<std::vector<ObjectInfoBlock>> info_blocks_;  // [object][block]
};

// A decoded OAMD frame from an EMDF payload: object count, program/bed
// assignment, and the object-audio elements.
class ObjectAudioMetadata {
 public:
  int object_count() const { return object_count_; }  // Includes beds.
  int beds() const { return beds_; }                  // Static bed channels.
  int element_count() const { return static_cast<int>(elements_.size()); }
  const OAElementMD& element(int i) const { return elements_[i]; }
  OAElementMD& element(int i) { return elements_[i]; }
  bool isf_in_use() const { return isf_in_use_; }
  bool valid() const { return valid_; }

  // Decodes a OAMD frame. `offset` is the EMDF payload's sample offset.
  void decode(BitReader& br, int offset) {
    valid_ = true;
    offset_ = offset;
    int version = static_cast<int>(br.read(2));
    if (version == 3) version += static_cast<int>(br.read(3));
    if (version != 0) {  // UnsupportedFeatureException("OAver")
      valid_ = false;
      return;
    }

    object_count_ = static_cast<int>(br.read(5)) + 1;
    if (object_count_ == 32) object_count_ += static_cast<int>(br.read(7));

    program_assignment(br);
    if (!valid_) return;

    const bool alternate_object_present = br.read_bit();
    int element_count = static_cast<int>(br.read(4));
    if (element_count == 15) element_count += static_cast<int>(br.read(5));

    int bed_or_isf_objects = beds_;
    if (isf_in_use_) bed_or_isf_objects += kIsfObjectCount[isf_index_];

    if (static_cast<int>(elements_.size()) != element_count) {
      elements_.resize(element_count);
    }
    for (int i = 0; i < element_count; ++i) {
      if (!elements_[i].read(br, alternate_object_present, object_count_,
                             bed_or_isf_objects)) {
        valid_ = false;
      }
    }
  }

  // The bed "objects" that are really static channels, in bed order.
  std::vector<ReferenceChannel> get_static_channels() const {
    std::vector<ReferenceChannel> result(beds_ > 0 ? beds_ : 0);
    int last = 0;
    for (const auto& assignment : bed_assignment_) {
      for (int j = 0; j < kMaxBed; ++j) {
        if (assignment[j]) {
          result[last] = kBedChannels[j];
          if (++last == beds_) return result;
        }
      }
    }
    return result;
  }

  // Which object index is the LFE channel, or -1 if not present.
  int get_lfe_position() const {
    int beds = 0;
    for (const auto& assignment : bed_assignment_) {
      for (int i = 0; i < kMaxBed; ++i) {
        if (assignment[i]) {
          if (i == static_cast<int>(NonStandardBedChannel::kLowFrequencyEffects)) {
            return beds;
          }
          ++beds;
        }
      }
    }
    return -1;
  }

 private:
  static constexpr int kMaxBed = static_cast<int>(NonStandardBedChannel::kMax);
  using BedRow = std::array<bool, static_cast<size_t>(kMaxBed)>;

  void program_assignment(BitReader& br) {
    if (br.read_bit()) {           // Dynamic object-only program.
      if (br.read_bit()) {         // LFE present.
        bed_assignment_.assign(1, BedRow{});
        bed_assignment_[0][static_cast<int>(
            NonStandardBedChannel::kLowFrequencyEffects)] = true;
      } else {
        bed_assignment_.clear();
      }
    } else {
      const int content_description = static_cast<int>(br.read(4));

      // Object(s) with speaker-anchored coordinates (bed objects).
      if ((content_description & 1) != 0) {
        br.skip(1);  // Distributable flag — Cavern distributes anyway.
        const int beds = br.read_bit() ? static_cast<int>(br.read(3)) + 2 : 1;
        bed_assignment_.assign(beds, BedRow{});
        for (int bed = 0; bed < beds; ++bed) {
          if (br.read_bit()) {  // LFE only.
            bed_assignment_[bed][static_cast<int>(
                NonStandardBedChannel::kLowFrequencyEffects)] = true;
          } else if (br.read_bit()) {  // Standard bed assignment.
            bool standard[kStandardBeds];
            read_bits_reversed(br, standard, kStandardBeds);
            for (int i = 0; i < kStandardBeds; ++i) {
              for (int j = 0; j < kStandardBedChannelsLen[i]; ++j) {
                bed_assignment_[bed][kStandardBedChannels[i][j]] = standard[i];
              }
            }
          } else {  // Non-standard: one bit per channel.
            read_bits_reversed(br, bed_assignment_[bed].data(), kMaxBed);
          }
        }
      }

      // Intermediate spatial format (ISF).
      if ((isf_in_use_ = (content_description & 2) != 0)) {
        isf_index_ = static_cast<uint8_t>(br.read(3));
        if (isf_index_ >= kIsfLayouts) {  // UnsupportedFeatureException("ISF")
          valid_ = false;
          return;
        }
      }

      // Object(s) with room-/screen-anchored coordinates (redundant count).
      if ((content_description & 4) != 0) {
        if (br.read(5) == 31) br.skip(7);
      }

      // Reserved.
      if ((content_description & 8) != 0) {
        br.skip((static_cast<size_t>(br.read(4)) + 1) * 8);
      }
    }

    beds_ = 0;
    for (const auto& assignment : bed_assignment_) {
      for (int i = 0; i < kMaxBed; ++i) {
        if (assignment[i]) ++beds_;
      }
    }
  }

  static constexpr int kStandardBeds = 10;
  static constexpr int kIsfLayouts = 6;
  static inline const uint8_t kIsfObjectCount[kIsfLayouts] = {4, 8, 10, 14, 15, 30};

  // Which bed-channel slot each bit of bed_assignment_ means.
  static inline const ReferenceChannel kBedChannels[kMaxBed] = {
      ReferenceChannel::kFrontLeft,    ReferenceChannel::kFrontRight,
      ReferenceChannel::kFrontCenter,  ReferenceChannel::kScreenLFE,
      ReferenceChannel::kSideLeft,     ReferenceChannel::kSideRight,
      ReferenceChannel::kRearLeft,     ReferenceChannel::kRearRight,
      ReferenceChannel::kTopFrontLeft, ReferenceChannel::kTopFrontRight,
      ReferenceChannel::kTopSideLeft,  ReferenceChannel::kTopSideRight,
      ReferenceChannel::kTopRearLeft,  ReferenceChannel::kTopRearRight,
      ReferenceChannel::kWideLeft,     ReferenceChannel::kWideRight,
      ReferenceChannel::kScreenLFE};

  // Which kBedChannels slots each bit of a standard layout sets.
  static inline const uint8_t kStandardBedChannels[kStandardBeds][2] = {
      {0, 1}, {2, 0}, {3, 0}, {4, 5}, {6, 7},
      {8, 9}, {10, 11}, {12, 13}, {14, 15}, {16, 0}};
  static inline const uint8_t kStandardBedChannelsLen[kStandardBeds] = {
      2, 1, 1, 2, 2, 2, 2, 2, 2, 1};

  bool valid_ = true;
  int object_count_ = 0;
  int beds_ = 0;
  bool isf_in_use_ = false;
  uint8_t isf_index_ = 0;
  int offset_ = 0;
  std::vector<BedRow> bed_assignment_;  // [bed instance][channel bit]
  std::vector<OAElementMD> elements_;
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_OBJECT_AUDIO_METADATA_H
