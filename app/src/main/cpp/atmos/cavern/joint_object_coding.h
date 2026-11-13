// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Decoders/EnhancedAC3/JointObjectCoding.cs,
// JointObjectCodingCache.cs and JointObjectCodingDecoder.cs — the JOC frame
// decode (header/info/data + Huffman), dequantization and per-timeslot mixing-
// matrix interpolation.
//
// Deviations from upstream, all behaviour-preserving:
//  - Cavern's ThreadPool fan-out over objects is a sequential loop here.
//  - Exceptions (UnsupportedFeatureException) become a valid() flag, since the
//    NDK build is -fno-exceptions.
//  - The reader is tf::atmos::BitReader (bit-identical to Cavern's BitExtractor:
//    MSB-first; ReadBitInt == read(1), ReadBit == read_bit()!=0, Skip == skip).
#ifndef TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_H
#define TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_H

#include <cmath>
#include <vector>

#include "../bit_reader.h"
#include "joc_tables.h"
#include "quadrature_mirror_filterbank.h"

namespace tf {
namespace atmos {
namespace cavern {

class JointObjectCoding {
 public:
  static constexpr int kSubbands = QuadratureMirrorFilterBank::kSubbands;  // 64
  static constexpr int kMaxObjects = 64;
  static constexpr int kMaxChannels = 7;   // inputMatrix.Length
  static constexpr int kMaxBands = 23;     // joc_num_bands[^1]
  static constexpr int kMaxDataPoints = 2;
  static constexpr int kMaxTimeslots = 1536 / kSubbands;  // 24

  bool valid() const { return valid_; }
  int channel_count() const { return channel_count_; }
  int object_count() const { return object_count_; }
  float gain() const { return gain_; }
  bool object_active(int obj) const { return object_active_[obj] != 0; }

  // Decodes a JOC frame from an EMDF payload.
  void decode(BitReader& br) {
    valid_ = true;
    decode_header(br);
    if (!valid_) return;
    decode_info(br);
    decode_data(br);
  }

  // Whether the downmix the objects are rebuilt from has its surround pair
  // turned 90 degrees (joc_dmx_config_idx 3 and 4, Table 47: "with 90-degree
  // phase shift"): the applier turns it back before mixing.
  bool surround_phase_shift() const { return downmix_config_ >= 3; }
  int downmix_config() const { return downmix_config_; }
  // joc_seq_count of the last payload: 0 on a stream's first frame and on the
  // first after a splice.
  int sequence() const { return sequence_; }

  // Forgets the matrix the next frame would ramp from (a seek, a flush): the
  // next payload starts on its own matrix instead of sliding in from one that
  // belongs to another part of the stream.
  void reset_history() { history_ = false; }

  // The interpolated mixing-matrix row for object/timeslot/channel:
  // kSubbands floats, written to `scratch` or returned as stored. Valid after
  // get_mixing_matrices(frame_size), for the frame it was called for.
  //
  // A decoded matrix is in parameter bands and a row is in QMF subbands, so
  // every use of one goes through the band mapping. (The steep branches and
  // the first half of the two-point ramp used to hand out a band-indexed row
  // as it was: with 12 bands, everything above 4.5 kHz of a steep object went
  // silent after the switch.)
  const float* mixing_row(int obj, int ts, int ch, float* scratch) const {
    if (!object_active_[obj]) {
      for (int sb = 0; sb < kSubbands; ++sb) scratch[sb] = 0.0f;
      return scratch;
    }
    const int timeslots = timeslots_;
    const float* from = from_matrix_[obj][ch].data();
    const int* pb_mapping = joc_tables::pb_mapping(bands_index_[obj]);
    const float* first = mix_matrix_[obj][0][ch].data();
    auto expand = [&](const float* bands) {
      for (int sb = 0; sb < kSubbands; ++sb) scratch[sb] = bands[pb_mapping[sb]];
      return scratch;
    };
    auto ramp_to = [&](const float* bands, float lerp) {
      for (int sb = 0; sb < kSubbands; ++sb) {
        scratch[sb] = from[sb] + (bands[pb_mapping[sb]] - from[sb]) * lerp;
      }
      return scratch;
    };
    if (data_points_[obj] == 1) {
      // Steep: the frame's starting matrix up to the offset, then the decoded
      // one. Smooth: a ramp from the one to the other across the frame.
      if (steep_slope_[obj]) return ts < timeslot_offsets_[obj][0] ? from : expand(first);
      return ramp_to(first, static_cast<float>(ts + 1) / timeslots);
    }
    const float* second = mix_matrix_[obj][1][ch].data();
    if (steep_slope_[obj]) {
      // Each data point takes over at its own offset.
      if (ts < timeslot_offsets_[obj][0]) return from;
      return expand(ts < timeslot_offsets_[obj][1] ? first : second);
    }
    // Smooth: the first half ramps to the first data point, the second from
    // it to the second.
    const int ts_2 = timeslots >> 1;
    const int next = ts + 1;
    if (next <= ts_2) return ramp_to(first, static_cast<float>(next) / ts_2);
    const float lerp = static_cast<float>(next - ts_2) / (timeslots - ts_2);
    for (int sb = 0; sb < kSubbands; ++sb) {
      const int pb = pb_mapping[sb];
      scratch[sb] = first[pb] + (second[pb] - first[pb]) * lerp;
    }
    return scratch;
  }

  // Prepares every object's mixing matrices for a frame (Cavern's
  // GetMixingMatrices, sequential). frame_size = samples in the QMF window.
  //
  // Deviation from Cavern (performance, same values): Cavern fills a matrix
  // per object x timeslot x channel here, which at 16 objects and 7 channels
  // is 688 KB written and read back every frame. This only dequantizes and
  // keeps the matrix the frame starts from; mixing_row() works out each row
  // when the applier asks for it, with the same arithmetic in the same order.
  // (The table also took a steep slope's offset, up to 32, as a timeslot
  // count and wrote past its 24 rows; rows on demand have no such end.)
  void get_mixing_matrices(int frame_size) {
    timeslots_ = frame_size / kSubbands;
    // A payload read since the last call is what can start afresh; a frame
    // held from the last payload goes on from where that one ended.
    const bool splice = fresh_ && sequence_ == 0;
    const bool restart = fresh_ && !history_;
    for (int obj = 0; obj < object_count_; ++obj) {
      prepare_object(obj, restart, splice);
    }
    if (fresh_) history_ = true;
    fresh_ = false;
  }

 private:
  // ---- Decode ------------------------------------------------------------

  void decode_header(BitReader& br) {
    const int downmix_config = static_cast<int>(br.read(3));
    if (downmix_config > 4) { valid_ = false; return; }  // joc_dmx_config_idx
    downmix_config_ = downmix_config;
    channel_count_ = (downmix_config == 0 || downmix_config == 3) ? 5 : 7;
    object_count_ = static_cast<int>(br.read(6)) + 1;
    update_cache();
    if (br.read(3) != 0) { valid_ = false; return; }  // joc_ext_config_idx
  }

  void decode_info(BitReader& br) {
    const int gain_power = static_cast<int>(br.read(3));
    gain_ = 1.0f + (br.read(5) / 32.0f) * std::pow(2.0f, static_cast<float>(gain_power - 4));
    sequence_ = static_cast<int>(br.read(10));  // joc_seq_count
    fresh_ = true;
    for (int obj = 0; obj < object_count_; ++obj) {
      object_active_[obj] = br.read_bit() ? 1 : 0;
      if (object_active_[obj]) {
        bands_index_[obj] = static_cast<int>(br.read(3));
        bands_[obj] = joc_tables::kJocNumBands[bands_index_[obj]];
        sparse_coded_[obj] = br.read_bit() ? 1 : 0;
        quantization_table_[obj] = static_cast<int>(br.read(1));  // ReadBitInt
        steep_slope_[obj] = br.read_bit() ? 1 : 0;
        data_points_[obj] = static_cast<int>(br.read(1)) + 1;
        if (steep_slope_[obj]) {
          for (int dp = 0; dp < data_points_[obj]; ++dp) {
            timeslot_offsets_[obj][dp] = static_cast<int>(br.read(5)) + 1;
          }
        }
      }
    }
  }

  void decode_data(BitReader& br) {
    for (int obj = 0; obj < object_count_; ++obj) {
      if (!object_active_[obj]) continue;
      if (sparse_coded_[obj]) {
        const auto* channel_table = joc_tables::get_huff_code_table(channel_count_, HuffmanType::IDX);
        const auto* vec_table = joc_tables::get_huff_code_table(quantization_table_[obj], HuffmanType::VEC);
        for (int dp = 0; dp < data_points_[obj]; ++dp) {
          joc_channel_[obj][dp][0] = static_cast<int>(br.read(3));
          for (int pb = 1; pb < bands_[obj]; ++pb) {
            joc_channel_[obj][dp][pb] = huffman_decode(channel_table, br);
          }
          for (int pb = 0; pb < bands_[obj]; ++pb) {
            joc_vector_[obj][dp][pb] = huffman_decode(vec_table, br);
          }
        }
      } else {
        const auto* code_table = joc_tables::get_huff_code_table(quantization_table_[obj], HuffmanType::MTX);
        for (int dp = 0; dp < data_points_[obj]; ++dp) {
          for (int ch = 0; ch < channel_count_; ++ch) {
            for (int pb = 0; pb < bands_[obj]; ++pb) {
              joc_matrix_[obj][dp][ch][pb] = huffman_decode(code_table, br);
            }
          }
        }
      }
    }
  }

  static int huffman_decode(const int (*code_table)[2], BitReader& br) {
    int node = 0;
    do {
      node = code_table[node][br.read_bit() ? 1 : 0];
    } while (node > 0);
    return ~node;
  }

  // ---- Dequantization ----------------------------------------------------
  //
  // A coefficient is an index on a ring of `levels` (96 coarse, 192 fine),
  // each band's coded as its step from the band before, and the ring's centre
  // is zero gain. The ring is walked in whole numbers: in floats (as Cavern
  // has it) a sum that should land exactly on the modulus can fall just short
  // of it, and the lowest coefficient, -9.6, came out as +9.6.

  // Dense: every channel has its own chain along the bands, from the centre.
  void decode_dense(int obj, int center, float gain_step) {
    const int levels = center * 2;
    const int bands = bands_[obj];
    for (int dp = 0; dp < data_points_[obj]; ++dp) {
      for (int ch = 0; ch < channel_count_; ++ch) {
        const int* source = joc_matrix_[obj][dp][ch].data();
        float* dest = mix_matrix_[obj][dp][ch].data();
        int index = center;
        for (int pb = 0; pb < bands; ++pb) {
          index = (index + source[pb]) % levels;
          dest[pb] = static_cast<float>(index - center) * gain_step;
        }
      }
    }
  }

  // Sparse (b_joc_sparse): one channel a band carries the object. Which one is
  // a chain of its own along the bands; the coefficient's chain runs on
  // unbroken when the channel changes, from 50 (coarse) or 100 (fine). Every
  // other channel is at the centre: zero. As Cavern's DecodeSparse now is.
  // (It used to be left undecoded, and a sparse object played as silence.)
  void decode_sparse(int obj, int center, float gain_step) {
    had_sparse_ = true;
    const int levels = center * 2;
    const int bands = bands_[obj];
    for (int dp = 0; dp < data_points_[obj]; ++dp) {
      int channel = joc_channel_[obj][dp][0] % channel_count_;
      int value = quantization_table_[obj] * 50 + 50;
      for (int pb = 0; pb < bands; ++pb) {
        if (pb != 0) channel = (channel + joc_channel_[obj][dp][pb]) % channel_count_;
        value = (value + joc_vector_[obj][dp][pb]) % levels;
        for (int ch = 0; ch < channel_count_; ++ch) {
          mix_matrix_[obj][dp][ch][pb] =
              ch == channel ? static_cast<float>(value - center) * gain_step : 0.0f;
        }
      }
    }
  }

  // ---- Matrix interpolation (per object) ---------------------------------

  void prepare_object(int obj, bool restart, bool splice) {
    const int center_value = quantization_table_[obj] * 48 + 48;
    // An inactive object's rows are all zero, and its history is left alone.
    if (!object_active_[obj]) return;
    // Clause 6.6.4's steps: 820/4096 coarse, 820/8192 fine (0.2 and 0.1, near
    // enough, which is what Cavern uses).
    const float gain_step = quantization_table_[obj] ? 820.0f / 8192.0f : 820.0f / 4096.0f;
    if (sparse_coded_[obj]) {
      decode_sparse(obj, center_value, gain_step);
    } else {
      decode_dense(obj, center_value, gain_step);
    }

    // This frame interpolates from the matrix the last frame ended on; carry
    // the current one forward as history for the next frame. A stream's first
    // frame and the first after a splice start from nothing (clause 6.6.5);
    // the first after a seek starts on its own matrix.
    const int* pb_mapping = joc_tables::pb_mapping(bands_index_[obj]);
    const int first_point = 0, last_point = data_points_[obj] - 1;
    for (int ch = 0; ch < channel_count_; ++ch) {
      float* from = from_matrix_[obj][ch].data();
      float* prev = prev_matrix_[obj][ch].data();
      const float* start = mix_matrix_[obj][first_point][ch].data();
      const float* mix_src = mix_matrix_[obj][last_point][ch].data();
      for (int sb = 0; sb < kSubbands; ++sb) {
        from[sb] = splice ? 0.0f : (restart ? start[pb_mapping[sb]] : prev[sb]);
        prev[sb] = mix_src[pb_mapping[sb]];
      }
    }
  }

  // ---- Cache -------------------------------------------------------------

  void update_cache() {
    if (static_cast<int>(object_active_.size()) == object_count_ && matrix_channels_ == channel_count_) {
      return;
    }
    matrix_channels_ = channel_count_;

    object_active_.assign(object_count_, 0);
    bands_index_.assign(object_count_, 0);
    bands_.assign(object_count_, 0);
    sparse_coded_.assign(object_count_, 0);
    quantization_table_.assign(object_count_, 0);
    steep_slope_.assign(object_count_, 0);
    data_points_.assign(object_count_, 0);
    timeslot_offsets_.assign(object_count_, std::vector<int>(kMaxDataPoints, 0));

    joc_channel_.assign(object_count_, {});
    joc_vector_.assign(object_count_, {});
    joc_matrix_.assign(object_count_, {});
    mix_matrix_.assign(object_count_, {});
    from_matrix_.assign(object_count_, std::vector<std::vector<float>>(
                                           channel_count_, std::vector<float>(kSubbands, 0.0f)));
    if (static_cast<int>(prev_matrix_.size()) < kMaxObjects) {
      prev_matrix_.assign(kMaxObjects, std::vector<std::vector<float>>(
                                           kMaxChannels, std::vector<float>(kSubbands, 0.0f)));
    }

    for (int obj = 0; obj < object_count_; ++obj) {
      joc_channel_[obj].assign(kMaxDataPoints, std::vector<int>(kMaxBands, 0));
      joc_vector_[obj].assign(kMaxDataPoints, std::vector<int>(kMaxBands, 0));
      joc_matrix_[obj].assign(kMaxDataPoints,
                              std::vector<std::vector<int>>(channel_count_, std::vector<int>(kMaxBands, 0)));
      mix_matrix_[obj].assign(kMaxDataPoints,
                              std::vector<std::vector<float>>(channel_count_, std::vector<float>(kSubbands, 0.0f)));
    }
  }

  // The test that holds mixing_row() to Cavern's precomputed matrices
  // (cavern_joc_test) reads the decoded state through this.
  friend struct JointObjectCodingProbe;

  // ---- State -------------------------------------------------------------

  bool valid_ = false;
  bool had_sparse_ = false;
  int downmix_config_ = 0;
  int sequence_ = 0;
  bool fresh_ = false;    // a payload was decoded since the last get_mixing_matrices()
  bool history_ = false;  // prev_matrix_ is the matrix the last frame ended on
  int channel_count_ = 0;
  int object_count_ = 0;
  int matrix_channels_ = -1;
  float gain_ = 1.0f;

  std::vector<char> object_active_, sparse_coded_, steep_slope_;
  std::vector<int> bands_index_, bands_, quantization_table_, data_points_;
  std::vector<std::vector<int>> timeslot_offsets_;                       // [obj][dp]
  std::vector<std::vector<std::vector<int>>> joc_channel_, joc_vector_;  // [obj][dp][band]
  std::vector<std::vector<std::vector<std::vector<int>>>> joc_matrix_;   // [obj][dp][ch][band]
  std::vector<std::vector<std::vector<std::vector<float>>>> mix_matrix_;      // [obj][dp][ch][sb]
  std::vector<std::vector<std::vector<float>>> from_matrix_;  // [obj][ch][sb], this frame's start
  std::vector<std::vector<std::vector<float>>> prev_matrix_;  // [obj][ch][sb], the next frame's
  int timeslots_ = kMaxTimeslots;  // of the frame get_mixing_matrices() was called for
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_H
