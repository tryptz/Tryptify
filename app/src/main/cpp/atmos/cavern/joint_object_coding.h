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

  // The interpolated mixing-matrix row for object/timeslot/channel:
  // kSubbands floats. Valid after get_mixing_matrices(frame_size), for the
  // frame it was called for. The row is worked out on demand: where it is an
  // interpolation it is written to `scratch` (kSubbands floats) and that is
  // returned; where it is a stored row it is returned as is. Either way the
  // values are the ones Cavern's precomputed per-timeslot matrices held.
  const float* mixing_row(int obj, int ts, int ch, float* scratch) const {
    if (!object_active_[obj]) {
      for (int sb = 0; sb < kSubbands; ++sb) scratch[sb] = 0.0f;
      return scratch;
    }
    const int timeslots = timeslots_;
    const float* from = from_matrix_[obj][ch].data();
    if (data_points_[obj] == 1) {
      // One data point, steep: the frame's starting matrix up to the offset,
      // then the decoded one (by Cavern's index, kept as it is).
      if (steep_slope_[obj]) {
        if (ts < timeslot_offsets_[obj][0]) return from;
        return mix_matrix_[obj][ts < timeslot_offsets_[obj][1] ? 1 : 0][ch].data();
      }
      // Smooth: a ramp from the starting matrix to the decoded one.
      const int* pb_mapping = joc_tables::pb_mapping(bands_index_[obj]);
      const float* mix = mix_matrix_[obj][0][ch].data();
      const float lerp = static_cast<float>(ts + 1) / timeslots;
      for (int sb = 0; sb < kSubbands; ++sb) {
        scratch[sb] = from[sb] + (mix[pb_mapping[sb]] - from[sb]) * lerp;
      }
      return scratch;
    }
    // Two data points, steep: the starting matrix up to the offset, then the
    // first data point.
    if (steep_slope_[obj]) {
      return ts + 1 < timeslot_offsets_[obj][0] ? from : mix_matrix_[obj][0][ch].data();
    }
    // Smooth: the first half ramps to the first data point, the second from
    // it to the second.
    const int ts_2 = timeslots >> 1;
    const int next = ts + 1;
    if (next <= ts_2) {
      const float lerp = static_cast<float>(next) / ts_2;
      const float* to = mix_matrix_[obj][0][ch].data();
      for (int sb = 0; sb < kSubbands; ++sb) scratch[sb] = from[sb] + (to[sb] - from[sb]) * lerp;
    } else {
      const int* pb_mapping = joc_tables::pb_mapping(bands_index_[obj]);
      const float lerp = static_cast<float>(next - ts_2) / (timeslots - ts_2);
      const float* cf = mix_matrix_[obj][0][ch].data();
      const float* ct = mix_matrix_[obj][1][ch].data();
      for (int sb = 0; sb < kSubbands; ++sb) {
        const int pb = pb_mapping[sb];
        scratch[sb] = cf[pb] + (ct[pb] - cf[pb]) * lerp;
      }
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
    for (int obj = 0; obj < object_count_; ++obj) {
      prepare_object(obj);
    }
  }

 private:
  // ---- Decode ------------------------------------------------------------

  void decode_header(BitReader& br) {
    const int downmix_config = static_cast<int>(br.read(3));
    if (downmix_config > 4) { valid_ = false; return; }  // joc_dmx_config_idx
    channel_count_ = (downmix_config == 0 || downmix_config == 3) ? 5 : 7;
    object_count_ = static_cast<int>(br.read(6)) + 1;
    update_cache();
    if (br.read(3) != 0) { valid_ = false; return; }  // joc_ext_config_idx
  }

  void decode_info(BitReader& br) {
    const int gain_power = static_cast<int>(br.read(3));
    gain_ = 1.0f + (br.read(5) / 32.0f) * std::pow(2.0f, static_cast<float>(gain_power - 4));
    br.skip(10);  // sequence counter
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

  void decode_coarse(int obj, int quantized_center, float gain_step) {
    const float center = quantized_center * gain_step;
    const float max = center * 2.0f;
    const int bands = bands_[obj];
    for (int dp = 0; dp < data_points_[obj]; ++dp) {
      for (int ch = 0; ch < channel_count_; ++ch) {
        decode_coarse_channel(joc_matrix_[obj][dp][ch].data(),
                              mix_matrix_[obj][dp][ch].data(), center, gain_step, max, bands);
      }
    }
  }

  static void decode_coarse_channel(const int* source, float* dest, float center,
                                    float gain_step, float max, int bands) {
    dest[0] = std::fmod(center + source[0] * gain_step, max);
    int i = 0, s = 0, b = bands;
    while (--b != 0) {
      const float next = std::fmod(dest[i] + source[++s] * gain_step, max);
      dest[i] -= center;
      dest[++i] = next;
    }
    dest[i] -= center;
  }

  void dequantize(int obj, int center, float gain_step) {
    for (int dp = 0; dp < data_points_[obj]; ++dp) {
      for (int ch = 0; ch < channel_count_; ++ch) {
        float* chan = mix_matrix_[obj][dp][ch].data();
        for (int sb = 0; sb < bands_[obj]; ++sb) {
          chan[sb] = (chan[sb] - center) * gain_step;
        }
      }
    }
  }

  // Sparse decode (b_joc_sparse). Ported for parity but NOT called: upstream
  // keeps it commented out ("revert 0 to gainStep when the standard
  // documentation is fixed") and dequantizes with gainStep 0 instead, which is
  // reproduced in prepare_object below.
  void decode_sparse(int obj, int center) {
    had_sparse_ = true;
    const int max = center * 2;
    const int offset = quantization_table_[obj] * 50 + 50;
    const int bands = bands_[obj];
    for (int dp = 0; dp < data_points_[obj]; ++dp) {
      for (int pb = 0; pb < bands; ++pb) {
        int channel;
        if (pb == 0) {
          channel = joc_channel_[obj][dp][0];
        } else {
          channel = (joc_channel_[obj][dp][pb - 1] + joc_channel_[obj][dp][pb]) % channel_count_;
        }
        for (int ch = 0; ch < channel_count_; ++ch) {
          float* ch_matrix = mix_matrix_[obj][dp][ch].data();
          if (ch == channel) {
            if (pb == 0) {
              ch_matrix[pb] = static_cast<float>((offset + joc_vector_[obj][dp][pb]) % max);
            } else {
              ch_matrix[pb] = std::fmod(ch_matrix[pb - 1] + joc_vector_[obj][dp][pb], static_cast<float>(max));
            }
          } else {
            ch_matrix[pb] = static_cast<float>(offset);
          }
        }
      }
    }
  }

  // ---- Matrix interpolation (per object) ---------------------------------

  void prepare_object(int obj) {
    const int center_value = quantization_table_[obj] * 48 + 48;
    // An inactive object's rows are all zero, and its history is left alone.
    if (!object_active_[obj]) return;
    const float gain_step = 0.2f - quantization_table_[obj] * 0.1f;
    if (sparse_coded_[obj]) {
      dequantize(obj, center_value, 0.0f);  // upstream: gainStep 0 (sparse WIP)
    } else {
      decode_coarse(obj, center_value, gain_step);
    }

    // This frame interpolates from the matrix the last frame ended on; carry
    // the current one forward as history for the next frame.
    const int* pb_mapping = joc_tables::pb_mapping(bands_index_[obj]);
    for (int ch = 0; ch < channel_count_; ++ch) {
      float* from = from_matrix_[obj][ch].data();
      float* prev = prev_matrix_[obj][ch].data();
      const float* mix_src = mix_matrix_[obj][data_points_[obj] - 1][ch].data();
      for (int sb = 0; sb < kSubbands; ++sb) {
        from[sb] = prev[sb];
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
