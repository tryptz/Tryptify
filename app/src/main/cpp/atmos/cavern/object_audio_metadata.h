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

#include <algorithm>
#include <array>
#include <cmath>
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

  bool is_extended_element() const { return is_extended_element_; }

  // Decodes an element. Returns false on an unsupported feature (mirrors Cavern
  // throwing UnsupportedFeatureException — here it degrades to a valid() flag).
  // `object_md` is the frame's object element decoded so far (if any):
  // an extended_object_element annotates its info blocks.
  bool read(BitReader& br, bool alternate_object_present, int object_count,
            int bed_or_isf_objects, OAElementMD* object_md = nullptr) {
    valid_ = true;
    is_object_element_ = is_extended_element_ = false;
    const int element_index = static_cast<int>(br.read(4));
    // TS 103 420 clause 5.6.4.3: oa_element_size = oa_element_size_bits + 1 is
    // the length in BYTES of everything that follows the size field
    // (alternate_object_data_id_idx, b_discard_unknown_element, the element and
    // its padding). Cavern ends the element `size + 1` BITS past the position
    // BEFORE the size field, which only works because encoders emit a single
    // element: seeking back there desynchronises any element after the first
    // (trims, the extended object element carrying divergence / ext precision).
    const uint32_t size_bytes = br.read_variable_bits(4, 4) + 1;
    const size_t end_pos = br.position() + static_cast<size_t>(size_bytes) * 8u;
    br.skip(alternate_object_present ? 5 : 1);
    if (element_index == kObjectElementIndex) {
      is_object_element_ = true;
      object_element(br, object_count, bed_or_isf_objects);
    } else if (element_index == kExtendedObjectElementIndex && object_md != nullptr &&
               object_md->is_object_element()) {
      is_extended_element_ = true;
      block_offset_factor_.assign(1, static_cast<short>(-1 - element_index));
      extended_object_element(br, *object_md, bed_or_isf_objects);
    } else {  // Trim elements and reserved types don't affect rendering.
      block_offset_factor_.assign(1, static_cast<short>(-1 - element_index));
    }
    // Skip padding to the element boundary — forward only, so an understated
    // size can never rewind the reader into data it already consumed.
    if (end_pos > br.position()) br.seek(end_pos);
    return valid_;
  }

  // Ramp duration of block `blk` in samples (0 = jump).
  int ramp_duration(int blk) const { return ramp_duration_[blk]; }
  // Sample offset of block `blk` within the frame.
  int block_offset(int blk) const { return block_offset_factor_[blk]; }

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

  // extended_object_element (clauses 5.5.13-15): per-object, per-block
  // divergence and extended-precision position, only for active dynamic objects.
  void extended_object_element(BitReader& br, OAElementMD& obj_el, int bed_or_isf_objects) {
    const int objects = obj_el.object_count();
    const int blocks = obj_el.block_count();
    if (br.read_bit()) {  // b_obj_div_block
      for (int obj = 0; obj < objects; ++obj) {
        for (int blk = 0; blk < blocks; ++blk) {
          ObjectInfoBlock& b = obj_el.info_block(obj, blk);
          if (b.inactive()) { b.set_divergence(0.0f); continue; }
          if (obj < bed_or_isf_objects) continue;  // only DYNAMIC objects carry it
          if (!br.read_bit()) { b.set_divergence(0.0f); continue; }  // b_object_divergence
          const int mode = static_cast<int>(br.read(2));
          if (mode == 0) {
            b.set_divergence(kDivergenceTable[br.read(2)]);
          } else if (mode == 2 || mode == 3) {
            b.set_divergence(kDivergenceCode[br.read(6)]);
          }
          // mode 1 = reuse the previous block's divergence: leave it unset.
        }
      }
    }
    if (br.read_bit()) {  // b_ext_prec_pos_block
      for (int obj = 0; obj < objects; ++obj) {
        for (int blk = 0; blk < blocks; ++blk) {
          ObjectInfoBlock& b = obj_el.info_block(obj, blk);
          if (b.inactive() || obj < bed_or_isf_objects) continue;
          if (!br.read_bit()) continue;  // b_ext_prec_pos
          // ext_prec_pos_presence[]: index 2 = X, 1 = Y, 0 = Z (Table 43),
          // indices numbered by bit significance like every other flag array.
          const int presence = static_cast<int>(br.read(3));
          const int x = (presence & 4) ? static_cast<int>(br.read(2)) : 0;
          const int y = (presence & 2) ? static_cast<int>(br.read(2)) : 0;
          const int z = (presence & 1) ? static_cast<int>(br.read(2)) : 0;
          b.set_ext_precision(x, y, z);
        }
      }
    }
  }

  static constexpr int kObjectElementIndex = 1;
  static constexpr int kExtendedObjectElementIndex = 5;
  // Tables 41 and 42 (object_div_table / object_div_code); code 0 is reserved.
  static inline const float kDivergenceTable[4] = {0.500755f, 0.608529f, 0.704833f, 1.0f};
  static inline const float kDivergenceCode[64] = {
      0.0f,     0.0f,     0.004026f, 0.00716f,  0.012731f, 0.020173f, 0.028485f, 0.04021f,
      0.050582f, 0.063601f, 0.079914f, 0.100299f, 0.125666f, 0.140532f, 0.157027f, 0.175282f,
      0.195417f, 0.217536f, 0.241718f, 0.268002f, 0.296377f, 0.326766f, 0.359017f, 0.392895f,
      0.428081f, 0.464184f, 0.500755f, 0.537316f, 0.573389f, 0.608529f, 0.642346f, 0.674524f,
      0.704833f, 0.733123f, 0.75932f,  0.783416f, 0.805451f, 0.825506f, 0.843686f, 0.860112f,
      0.874914f, 0.888222f, 0.900168f, 0.910875f, 0.920461f, 0.929035f, 0.936698f, 0.943544f,
      0.949656f, 0.955112f, 0.95998f,  0.964322f, 0.968195f, 0.974729f, 0.979923f, 0.98405f,
      0.98733f,  0.989935f, 0.992874f, 0.994955f, 0.996817f, 0.99821f,  0.998993f, 1.0f};
  static inline const uint8_t kSampleOffsetIndex[4] = {8, 16, 18, 24};
  static inline const short kRampDurations[3] = {0, 512, 1536};
  static inline const short kRampDurationIndex[16] = {
      32, 64, 128, 256, 320, 480, 1000, 1001,
      1024, 1600, 1601, 1602, 1920, 2000, 2002, 2048};

  bool valid_ = true;
  bool is_object_element_ = false;
  bool is_extended_element_ = false;
  uint8_t sample_offset_ = 0;
  std::vector<short> block_offset_factor_;
  std::vector<short> ramp_duration_;
  std::vector<std::vector<ObjectInfoBlock>> info_blocks_;  // [object][block]
};

// One object's rendering state after an OAMD frame, resolved per ETSI TS 103 420
// (clauses 5.2 and 5.6.4): each metadata update block is applied in order with
// its default / full-update / full-reuse / mixed rules, so fields a block does
// not signal hold their previous value, differential positions build on the
// PREVIOUS block, and an inactive object is silent. Values are the state at the
// frame's last block — the target the renderers glide toward.
struct ObjectState {
  bool active = false;
  bool is_bed = false;  // speaker-anchored bed object (position = its channel)
  bool is_isf = false;  // intermediate-spatial-format ring object
  ReferenceChannel bed_channel = ReferenceChannel::kFrontCenter;  // when is_bed
  float gain = 0.0f;    // linear, including Cavern's 3 dB anti-clip trim

  // Standard-precision coded position (X,Y in 0..62, Z in -15..15) and the
  // extended-precision refinement of the last block (0..3 fifths of a step).
  int pos_bits[3] = {31, 31, 0};
  int ext_prec[3] = {0, 0, 0};
  float distance = NAN;  // Table 15 factor, +inf at infinity, NaN = inside the room
  bool screen_ref = false;
  float screen_factor = 0.0f;
  float depth_factor = 1.0f;
  float width = 0.0f, depth = 0.0f, height = 0.0f;  // room units, 0..1
  int zone_constraints_idx = 0;                     // Table 20
  bool enable_elevation = true;                     // Table 21
  bool snap = false;                                // channel lock
  float divergence = 0.0f;                          // 0..1, Tables 41/42

  // Final room-anchored position after the distance / screen transforms:
  // x 0..1 left->right, y 0..1 front->back, z -1..1 floor->ceiling (clause 4.2.1).
  Vec3 room{0.5f, 0.5f, 0.0f};
  // The same point in render space (x right, y up, z front) = (2x-1, z, 1-2y),
  // what the binaural renderer consumes.
  Vec3 render{0.0f, 0.0f, 0.0f};
};

// The screen used for screen-anchored objects (clause 4.2.2). A home renderer
// has no screen geometry, so this is Cavern's default (Listener.ScreenSize): 90 %
// of the front wall wide, bottom edge at ear level, 0.972 room units tall.
constexpr float kScreenWidth = 0.9f;
constexpr float kScreenHeight = 0.972f;

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
    OAElementMD* object_element = nullptr;
    for (int i = 0; i < element_count; ++i) {
      if (!elements_[i].read(br, alternate_object_present, object_count_,
                             bed_or_isf_objects, object_element)) {
        valid_ = false;
      }
      if (elements_[i].is_object_element()) object_element = &elements_[i];
    }
    if (valid_) resolve(bed_or_isf_objects);
  }

  // Render-space direction of a bed channel's nominal speaker (see
  // bed_channel_render) — shared with the speaker renderer's bed fallback.
  static Vec3 bed_render_position(ReferenceChannel ch) { return bed_channel_render(ch); }

  // Per-object rendering state after the last decoded frame (see ObjectState).
  int state_count() const { return static_cast<int>(states_.size()); }
  const ObjectState& state(int obj) const { return states_[obj]; }

  // Applies every object element's blocks to the persistent per-object state.
  // Public so tests can drive it; decode() calls it for each valid frame.
  void resolve(int bed_or_isf_objects) {
    if (static_cast<int>(states_.size()) != object_count_) states_.resize(object_count_);
    const std::vector<ReferenceChannel> statics = get_static_channels();
    const int isf_objects = isf_in_use_ ? kIsfObjectCount[isf_index_] : 0;
    for (int obj = 0; obj < object_count_; ++obj) {
      ObjectState& st = states_[obj];
      st.is_bed = obj < beds_;
      st.is_isf = !st.is_bed && obj < bed_or_isf_objects;
      if (st.is_bed && obj < static_cast<int>(statics.size())) st.bed_channel = statics[obj];
    }
    for (auto& el : elements_) {
      if (!el.is_object_element()) continue;
      const int objects = std::min(el.object_count(), object_count_);
      for (int blk = 0; blk < el.block_count(); ++blk) {
        for (int obj = 0; obj < objects; ++obj) {
          apply_block(states_[obj], el.info_block(obj, blk), obj, blk, objects, el);
        }
      }
    }
    for (int obj = 0; obj < object_count_; ++obj) {
      ObjectState& st = states_[obj];
      if (st.is_bed) {
        st.render = bed_channel_render(st.bed_channel);
      } else if (st.is_isf) {
        st.render = isf_render(isf_index_, obj - beds_, isf_objects);
      } else {
        st.room = room_position(st);
        st.render = {st.room.x * 2.0f - 1.0f, st.room.z, 1.0f - 2.0f * st.room.y};
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

  // One metadata update block, applied to an object's running state.
  void apply_block(ObjectState& st, const ObjectInfoBlock& b, int obj, int blk, int objects,
                   const OAElementMD& el) {
    if (b.inactive()) {
      st.active = false;
      st.gain = 0.0f;  // Table 28 default: -inf dB
    } else {
      st.active = true;
      // object_basic_info: 1 full, 3 mixed (only signalled fields), 2 reuse.
      if ((b.basic_status() == 1 || b.basic_status() == 3) && b.has_gain()) {
        if (b.gain_idx() == 3) {
          // Table 18: the gain of the PREVIOUS OBJECT in this same block
          // (0 dB for the first object) — not a hold of this object's gain.
          st.gain = obj > 0 ? states_[obj - 1].gain : 0.707f;
        } else {
          st.gain = b.gain();
        }
      }
    }
    (void)objects;
    (void)el;
    if (st.is_bed || st.is_isf) return;  // speaker/ISF-anchored: no render info
    const int status = b.render_status();
    if (status == 0) {  // Table 29 defaults (also what an inactive object gets)
      st.pos_bits[0] = 31; st.pos_bits[1] = 31; st.pos_bits[2] = 0;
      st.ext_prec[0] = st.ext_prec[1] = st.ext_prec[2] = 0;
      st.distance = NAN;
      st.width = st.depth = st.height = 0.0f;
      st.zone_constraints_idx = 0;
      st.enable_elevation = true;
      st.screen_ref = false;
      st.snap = false;
      st.divergence = 0.0f;
      return;
    }
    if (status == 2) return;  // full reuse
    if (b.has_position()) {
      if (b.differential()) {
        // Clause 5.6.1.1.12-14: relative to the previous block's standard-
        // precision value, clamped to the room (Z spans -1..1).
        st.pos_bits[0] = clampi(st.pos_bits[0] + b.pos_x_bits(), 0, 62);
        st.pos_bits[1] = clampi(st.pos_bits[1] + b.pos_y_bits(), 0, 62);
        st.pos_bits[2] = clampi(st.pos_bits[2] + b.pos_z_bits(), -15, 15);
      } else {
        st.pos_bits[0] = b.pos_x_bits();
        st.pos_bits[1] = b.pos_y_bits();
        st.pos_bits[2] = b.pos_z_bits();
      }
      for (int a = 0; a < 3; ++a) st.ext_prec[a] = b.ext_prec(a);
      st.distance = b.distance();
    }
    if (b.has_zone()) {
      st.zone_constraints_idx = b.zone_constraints_idx();
      st.enable_elevation = b.enable_elevation();
    }
    if (b.has_size()) {
      st.width = b.width();
      st.depth = b.depth();
      st.height = b.height();
    }
    if (b.has_screen()) {
      st.screen_ref = b.screen_ref();
      st.screen_factor = b.screen_factor();
      st.depth_factor = b.depth_factor();
    }
    st.snap = b.snap();  // b_object_snap is sent with every object_render_info
    if (b.has_divergence()) st.divergence = b.divergence();
    (void)blk;
  }

  static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
  static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

  // Coded position -> room coordinates, then the distance (clause 5.2.1.2) or
  // screen (clause 5.2.1.3) transform.
  static Vec3 room_position(const ObjectState& st) {
    Vec3 c{std::fmin(1.0f, st.pos_bits[0] / 62.0f + st.ext_prec[0] / (62.0f * 5.0f)),
           std::fmin(1.0f, st.pos_bits[1] / 62.0f + st.ext_prec[1] / (62.0f * 5.0f)), 0.0f};
    const float z_sign = st.pos_bits[2] < 0 ? -1.0f : 1.0f;
    c.z = clampf(z_sign * (std::abs(st.pos_bits[2]) / 15.0f + st.ext_prec[2] / (15.0f * 5.0f)), -1.0f, 1.0f);

    if (st.screen_ref) {
      // C_R: the screen coordinate mapped into the room; M1 blends screen- vs
      // room-scaled x/z by screen_factor, M2 blends the result back toward C_R
      // by y^depth_factor (zero at the screen plane).
      const Vec3 cr{(0.5f - kScreenWidth / 2.0f) + kScreenWidth * c.x, c.y,
                    kScreenHeight / 2.0f * (c.z + 1.0f)};
      const float m1 = st.screen_factor;
      const float m2 = std::pow(c.y, st.depth_factor);
      const float ix = m1 * c.x + (1.0f - m1) * cr.x;
      const float iz = m1 * c.z + (1.0f - m1) * cr.z;
      return {m2 * ix + (1.0f - m2) * cr.x, c.y, m2 * iz + (1.0f - m2) * cr.z};
    }
    if (!std::isnan(st.distance)) {
      // Outside the room: extend the ray from O = (0.5, 0.5, 0) through C to
      // the room boundary I, then P = df * I + (1 - df) * O.
      const Vec3 o{0.5f, 0.5f, 0.0f};
      const Vec3 d{c.x - o.x, c.y - o.y, c.z - o.z};
      float t = INFINITY;
      if (d.x > 1e-6f) t = std::fmin(t, (1.0f - o.x) / d.x);
      if (d.x < -1e-6f) t = std::fmin(t, (0.0f - o.x) / d.x);
      if (d.y > 1e-6f) t = std::fmin(t, (1.0f - o.y) / d.y);
      if (d.y < -1e-6f) t = std::fmin(t, (0.0f - o.y) / d.y);
      if (d.z > 1e-6f) t = std::fmin(t, (1.0f - o.z) / d.z);
      if (d.z < -1e-6f) t = std::fmin(t, (-1.0f - o.z) / d.z);
      if (std::isfinite(t)) {
        // Infinity only matters as a direction here; 100x the room is plenty.
        const float df = std::isinf(st.distance) ? 100.0f : st.distance;
        const Vec3 i{o.x + t * d.x, o.y + t * d.y, o.z + t * d.z};
        return {df * i.x + (1.0f - df) * o.x, df * i.y + (1.0f - df) * o.y, df * i.z + (1.0f - df) * o.z};
      }
    }
    return c;
  }

  // Render-space direction of a bed channel's nominal speaker (Dolby home
  // angles), so bed objects are heard where their channel is — they carry no
  // coded position of their own (clause 5.2.1.4).
  static Vec3 bed_channel_render(ReferenceChannel ch) {
    float az = 0.0f, el = 0.0f;
    switch (ch) {
      case ReferenceChannel::kFrontLeft: az = -30; break;
      case ReferenceChannel::kFrontRight: az = 30; break;
      case ReferenceChannel::kFrontCenter: az = 0; break;
      case ReferenceChannel::kScreenLFE: az = 0; break;
      case ReferenceChannel::kSideLeft: az = -90; break;
      case ReferenceChannel::kSideRight: az = 90; break;
      case ReferenceChannel::kRearLeft: az = -150; break;
      case ReferenceChannel::kRearRight: az = 150; break;
      case ReferenceChannel::kTopFrontLeft: az = -45; el = 45; break;
      case ReferenceChannel::kTopFrontRight: az = 45; el = 45; break;
      case ReferenceChannel::kTopSideLeft: az = -90; el = 45; break;
      case ReferenceChannel::kTopSideRight: az = 90; el = 45; break;
      case ReferenceChannel::kTopRearLeft: az = -135; el = 45; break;
      case ReferenceChannel::kTopRearRight: az = 135; el = 45; break;
      case ReferenceChannel::kWideLeft: az = -60; break;
      case ReferenceChannel::kWideRight: az = 60; break;
      case ReferenceChannel::kRearCenter: az = 180; break;
      case ReferenceChannel::kTopFrontCenter: az = 0; el = 45; break;
      case ReferenceChannel::kGodsVoice: el = 90; break;
      case ReferenceChannel::kFrontLeftCenter: az = -15; break;
      case ReferenceChannel::kFrontRightCenter: az = 15; break;
    }
    return direction_render(az, el);
  }

  static Vec3 direction_render(float az_deg, float el_deg) {
    const float az = az_deg * 3.14159265f / 180.0f, el = el_deg * 3.14159265f / 180.0f;
    return {std::sin(az) * std::cos(el), std::sin(el), std::cos(az) * std::cos(el)};
  }

  // ISF ("stacked ring") objects arrive in MULZ order — Mid ring, Upper, Lower,
  // Zenith — with the ring sizes of Table 11b. TS 103 420 does not give the ring
  // azimuths, so each ring is spaced evenly from the front, clockwise; this is an
  // approximation, not a spec-exact placement.
  static Vec3 isf_render(int isf_index, int isf_obj, int isf_objects) {
    static const int kRings[kIsfLayouts][4] = {
        {3, 1, 0, 0}, {5, 3, 0, 0}, {7, 3, 0, 0}, {9, 5, 0, 0}, {7, 5, 3, 0}, {15, 9, 5, 1}};
    static const float kRingElevation[4] = {0.0f, 45.0f, -30.0f, 90.0f};
    if (isf_index < 0 || isf_index >= kIsfLayouts || isf_obj < 0 || isf_obj >= isf_objects) return {};
    int first = 0;
    for (int ring = 0; ring < 4; ++ring) {
      const int n = kRings[isf_index][ring];
      if (isf_obj < first + n) {
        const float az = 360.0f * static_cast<float>(isf_obj - first) / static_cast<float>(n);
        return direction_render(az > 180.0f ? az - 360.0f : az, kRingElevation[ring]);
      }
      first += n;
    }
    return {};
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
  std::vector<ObjectState> states_;  // [object], persists across frames
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_OBJECT_AUDIO_METADATA_H
