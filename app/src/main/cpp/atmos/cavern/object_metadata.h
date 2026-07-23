// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Decoders/EnhancedAC3/ObjectAudioElementMetadata.cs
// and ObjectAudioMetadata.cs — the OAMD framing that drives the per-object
// ObjectInfoBlock decode: program (bed) assignment, element metadata, update
// timing/ramps.
//
// Scope: bitstream DECODE reproduced exactly (bit alignment preserved). The
// UpdateSources()/UpdateSource() render integration (Listener/Source/Vector3.Lerp)
// is NOT ported; decoded state is exposed via accessors for a future renderer.
// The EMDF `VariableBits` helpers are reproduced verbatim (Cavern's extension).
#ifndef TF_ATMOS_CAVERN_OBJECT_METADATA_H
#define TF_ATMOS_CAVERN_OBJECT_METADATA_H

#include <cstdint>
#include <vector>

#include "../bit_reader.h"
#include "object_info_block.h"

namespace tf {
namespace atmos {
namespace cavern {

// Minimal ReferenceChannel identifiers used by the OAMD bed-channel mapping.
enum class ReferenceChannel {
  kFrontLeft, kFrontRight, kFrontCenter, kScreenLFE, kSideLeft, kSideRight,
  kRearLeft, kRearRight, kTopFrontLeft, kTopFrontRight, kTopSideLeft, kTopSideRight,
  kTopRearLeft, kTopRearRight, kWideLeft, kWideRight
};

// ExtensibleMetadataExtensions.VariableBits — reproduced verbatim.
inline int variable_bits(BitReader& br, unsigned bits) {
  int value = 0;
  bool read_more;
  do {
    value += static_cast<int>(br.read(bits));
    read_more = br.read_bit();
    if (read_more) value = (value + 1) << bits;
  } while (read_more);
  return value;
}

// VariableBits with a length limit (the `limit` takes 1 less than the actual
// length — reproduced from Cavern including that quirk).
inline int variable_bits(BitReader& br, unsigned bits, int limit) {
  int value = 0;
  bool read_more;
  do {
    value += static_cast<int>(br.read(bits));
    read_more = br.read_bit();
    if (read_more) value = (value + 1) << bits;
  } while (read_more && limit-- != 0);
  return value;
}

// BitExtractor.ReadBits — n flags, filled in reverse (result[n-1] read first).
inline std::vector<char> read_bits(BitReader& br, int n) {
  std::vector<char> result(n, 0);
  for (int i = n; i-- > 0;) result[i] = br.read_bit() ? 1 : 0;
  return result;
}

// ---- OAElementMD -----------------------------------------------------------

class OAElementMD {
 public:
  short min_offset() const { return block_offset_factor_.empty() ? 0 : block_offset_factor_[0]; }
  int object_count() const { return static_cast<int>(info_blocks_.size()); }
  int block_count() const { return static_cast<int>(ramp_duration_.size()); }
  const ObjectInfoBlock& info_block(int obj, int blk) const { return info_blocks_[obj][blk]; }
  ObjectInfoBlock& info_block(int obj, int blk) { return info_blocks_[obj][blk]; }

  void read(BitReader& br, bool alternate_object_present, int object_count, int bed_or_isf_objects) {
    const int element_index = static_cast<int>(br.read(4));
    const size_t p0 = br.position();
    const int vb = variable_bits(br, 4, 4);
    const size_t end_pos = p0 + static_cast<size_t>(vb) + 1;
    br.skip(alternate_object_present ? 5 : 1);
    if (element_index == kObjectElementIndex) {
      object_element(br, object_count, bed_or_isf_objects);
    } else {  // other elements are unused by encoders
      block_offset_factor_.assign(1, static_cast<short>(-1 - element_index));
    }
    br.set_position(end_pos);  // padding
  }

 private:
  void object_element(BitReader& br, int object_count, int bed_or_isf_objects) {
    md_update_info(br);
    if (!br.read_bit()) br.skip(5);  // reserved

    const int blocks = static_cast<int>(ramp_duration_.size());
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
      default: valid_ = false; return;  // mdOffset unsupported
    }
    const int count = static_cast<int>(br.read(3)) + 1;
    block_offset_factor_.assign(count, 0);
    ramp_duration_.assign(count, 0);
    for (int blk = 0; blk < count; ++blk) block_update_info(br, blk);
  }

  void block_update_info(BitReader& br, int blk) {
    block_offset_factor_[blk] = static_cast<short>(br.read(6) + sample_offset_);
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
      32, 64, 128, 256, 320, 480, 1000, 1001, 1024, 1600, 1601, 1602, 1920, 2000, 2002, 2048};

  bool valid_ = true;
  uint8_t sample_offset_ = 0;
  std::vector<short> block_offset_factor_;
  std::vector<short> ramp_duration_;
  std::vector<std::vector<ObjectInfoBlock>> info_blocks_;  // [object][block]
};

// ---- ObjectAudioMetadata ---------------------------------------------------

class ObjectAudioMetadata {
 public:
  bool valid() const { return valid_; }
  int beds() const { return beds_; }
  int object_count() const { return object_count_; }
  int element_count() const { return static_cast<int>(elements_.size()); }
  const OAElementMD& element(int i) const { return elements_[i]; }

  void decode(BitReader& br, int offset) {
    valid_ = true;
    offset_ = offset;
    int version = static_cast<int>(br.read(2));
    if (version == 3) version += static_cast<int>(br.read(3));
    if (version != 0) { valid_ = false; return; }  // OAver unsupported

    object_count_ = static_cast<int>(br.read(5)) + 1;
    if (object_count_ == 32) object_count_ += static_cast<int>(br.read(7));

    program_assignment(br);
    const bool alternate_object_present = br.read_bit();
    int element_count = static_cast<int>(br.read(4));
    if (element_count == 15) element_count += static_cast<int>(br.read(5));

    int bed_or_isf_objects = beds_;
    if (isf_in_use_) bed_or_isf_objects += kIsfObjectCount[isf_index_];

    elements_.resize(element_count);
    for (int i = 0; i < element_count; ++i) {
      elements_[i].read(br, alternate_object_present, object_count_, bed_or_isf_objects);
    }
  }

  // Which decoded object is the LFE, or -1 if none (OAMD GetLFEPosition).
  int lfe_position() const {
    int beds = 0;
    for (const auto& bed : bed_assignment_) {
      for (int i = 0; i < static_cast<int>(NonStandardBedChannel::kMax); ++i) {
        if (bed[i]) {
          if (i == static_cast<int>(NonStandardBedChannel::kLowFrequencyEffects)) return beds;
          ++beds;
        }
      }
    }
    return -1;
  }

 private:
  void program_assignment(BitReader& br) {
    if (br.read_bit()) {  // dynamic object-only program
      if (br.read_bit()) {  // LFE present
        bed_assignment_.assign(1, std::vector<char>(static_cast<int>(NonStandardBedChannel::kMax), 0));
        bed_assignment_[0][static_cast<int>(NonStandardBedChannel::kLowFrequencyEffects)] = 1;
      } else {
        bed_assignment_.clear();
      }
    } else {
      const int content_description = static_cast<int>(br.read(4));

      if ((content_description & 1) != 0) {  // bed objects (speaker-anchored)
        br.skip(1);  // distributable
        const int beds = br.read_bit() ? static_cast<int>(br.read(3)) + 2 : 1;
        bed_assignment_.assign(beds, std::vector<char>(static_cast<int>(NonStandardBedChannel::kMax), 0));
        for (int bed = 0; bed < beds; ++bed) {
          if (br.read_bit()) {  // LFE only
            bed_assignment_[bed][static_cast<int>(NonStandardBedChannel::kLowFrequencyEffects)] = 1;
          } else if (br.read_bit()) {  // standard bed assignment
            std::vector<char> standard = read_bits(br, 10);
            for (int i = 0; i < 10; ++i) {
              for (int j = 0; j < static_cast<int>(kStandardBedChannels[i].size()); ++j) {
                bed_assignment_[bed][kStandardBedChannels[i][j]] = standard[i];
              }
            }
          } else {
            bed_assignment_[bed] = read_bits(br, static_cast<int>(NonStandardBedChannel::kMax));
          }
        }
      }

      if ((isf_in_use_ = (content_description & 2) != 0)) {  // ISF
        isf_index_ = static_cast<uint8_t>(br.read(3));
        if (isf_index_ >= static_cast<int>(sizeof(kIsfObjectCount) / sizeof(kIsfObjectCount[0]))) {
          valid_ = false;
          return;
        }
      }

      if ((content_description & 4) != 0) {  // room/screen-anchored objects
        if (br.read(5) == 31) br.skip(7);
      }

      if ((content_description & 8) != 0) {  // reserved
        br.skip((static_cast<int>(br.read(4)) + 1) * 8);
      }
    }

    beds_ = 0;
    for (const auto& bed : bed_assignment_) {
      for (int i = 0; i < static_cast<int>(NonStandardBedChannel::kMax); ++i) {
        if (bed[i]) ++beds_;
      }
    }
  }

  static inline const uint8_t kIsfObjectCount[6] = {4, 8, 10, 14, 15, 30};

  // Which bedChannels bits each standard-layout bit sets.
  static inline const std::vector<std::vector<int>> kStandardBedChannels = {
      {0, 1}, {2}, {3}, {4, 5}, {6, 7}, {8, 9}, {10, 11}, {12, 13}, {14, 15}, {16}};

  bool valid_ = false;
  int beds_ = 0;
  int object_count_ = 0;
  int offset_ = 0;
  bool isf_in_use_ = false;
  uint8_t isf_index_ = 0;
  std::vector<std::vector<char>> bed_assignment_;  // [bed][NonStandardBedChannel]
  std::vector<OAElementMD> elements_;
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_OBJECT_METADATA_H
