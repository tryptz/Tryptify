// Host tests for the ported Cavern JOC decoder.
//
//   c++ -std=c++17 -I.. cavern_joc_test.cpp -o joc && ./joc
//
// Validates the pieces that are deterministic without reference Atmos content:
//  - Huffman tables + decode (encode a symbol via the table, decode it back)
//  - dense and sparse dequantization (hand-computed, through real Huffman codes)
//  - steep-slope rows and the surround pair's 90-degree turn
//  - an end-to-end decode -> mixing matrices -> applier smoke run
//  - the on-demand mixing rows against the same rows worked out as a table
//    ahead of the frame, over random payloads in every interpolation mode
//
// Note: full numeric correctness of JOC needs A/B against reference content;
// these tests prove the port is self-consistent and runs, not bit-exactness vs
// a real Dolby stream.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "cavern/joint_object_coding.h"
#include "cavern/joint_object_coding_applier.h"

namespace tf {
namespace atmos {
namespace cavern {

// The frame's mixing matrices as a table, every object x timeslot x channel
// row worked out ahead of the frame the way Cavern's GetMixingMatrices does,
// with every decoded (parameter-band) matrix expanded to subbands. Reads the
// decoder's state; `from` is the matrix the frame starts from (Cavern's prev
// before it is carried forward).
struct JointObjectCodingProbe {
  using Table = std::vector<std::vector<std::vector<float>>>;  // [ts][ch][sb]
  static Table table_rows(const JointObjectCoding& j, int obj, int timeslots) {
    const int channels = j.channel_count_;
    constexpr int kSb = JointObjectCoding::kSubbands;
    Table rows(timeslots, std::vector<std::vector<float>>(channels, std::vector<float>(kSb, 0.0f)));
    if (!j.object_active_[obj]) return rows;
    const auto& prev = j.from_matrix_[obj];
    const auto& mix = j.mix_matrix_[obj];
    const int* pb_mapping = joc_tables::pb_mapping(j.bands_index_[obj]);
    auto copy = [&](int ts, const std::vector<std::vector<float>>& src) {
      for (int ch = 0; ch < channels; ++ch) rows[ts][ch] = std::vector<float>(src[ch].begin(), src[ch].begin() + kSb);
    };
    auto expand = [&](int ts, const std::vector<std::vector<float>>& src) {
      for (int ch = 0; ch < channels; ++ch)
        for (int sb = 0; sb < kSb; ++sb) rows[ts][ch][sb] = src[ch][pb_mapping[sb]];
    };
    if (j.data_points_[obj] == 1) {
      if (j.steep_slope_[obj]) {
        const int split = j.timeslot_offsets_[obj][0];
        for (int ts = 0; ts < split && ts < timeslots; ++ts) copy(ts, prev);
        for (int ts = split; ts < timeslots; ++ts) expand(ts, mix[0]);
      } else {
        for (int ch = 0; ch < channels; ++ch) {
          const float* p = prev[ch].data();
          const float* m = mix[0][ch].data();
          for (int ts = 0; ts < timeslots;) {
            float* interp = rows[ts][ch].data();
            const float lerp = static_cast<float>(++ts) / timeslots;
            for (int sb = 0; sb < kSb; ++sb) interp[sb] = p[sb] + (m[pb_mapping[sb]] - p[sb]) * lerp;
          }
        }
      }
    } else if (j.steep_slope_[obj]) {
      for (int ts = 0; ts < timeslots; ++ts) {
        if (ts < j.timeslot_offsets_[obj][0]) copy(ts, prev);
        else expand(ts, mix[ts < j.timeslot_offsets_[obj][1] ? 0 : 1]);
      }
    } else {
      const int ts_2 = timeslots >> 1;
      for (int ts = 0; ts < timeslots;) {
        const int cur = ts++;
        const bool first_half = ts <= ts_2;
        const float lerp = first_half ? static_cast<float>(ts) / ts_2
                                      : static_cast<float>(ts - ts_2) / (timeslots - ts_2);
        for (int ch = 0; ch < channels; ++ch) {
          float* interp = rows[cur][ch].data();
          if (first_half) {
            const float* cf = prev[ch].data();
            const float* ct = mix[0][ch].data();
            for (int sb = 0; sb < kSb; ++sb) interp[sb] = cf[sb] + (ct[pb_mapping[sb]] - cf[sb]) * lerp;
          } else {
            const float* cf = mix[0][ch].data();
            const float* ct = mix[1][ch].data();
            for (int sb = 0; sb < kSb; ++sb) {
              const int pb = pb_mapping[sb];
              interp[sb] = cf[pb] + (ct[pb] - cf[pb]) * lerp;
            }
          }
        }
      }
    }
    return rows;
  }
  // A decoded coefficient, in its parameter band.
  static float band(const JointObjectCoding& j, int obj, int dp, int ch, int pb) {
    return j.mix_matrix_[obj][dp][ch][pb];
  }
  // Makes object `obj` exactly channel `ch` for the frame in hand.
  static void set_passes(JointObjectCoding& j, int obj, int ch) {
    for (int c = 0; c < j.channel_count_; ++c) {
      const float v = c == ch ? 1.0f : 0.0f;
      for (float& x : j.from_matrix_[obj][c]) x = v;
      for (float& x : j.mix_matrix_[obj][0][c]) x = v;
    }
  }
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

namespace {
int g_fail = 0, g_checks = 0;
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK(c) check((c), #c, __LINE__)
void check_near(float a, float b, float tol, const char* e, int line) {
  ++g_checks;
  if (std::fabs(a - b) > tol) { ++g_fail; std::printf("  FAIL %s:%d %s (%.4f vs %.4f)\n", __FILE__, line, e, a, b); }
}
#define CHECK_NEAR(a, b, t) check_near((a), (b), (t), #a " ~= " #b, __LINE__)

// MSB-first bit writer matching tf::atmos::BitReader.
struct BitWriter {
  std::vector<uint8_t> bytes;
  size_t count = 0;
  void put_bit(int bit) {
    if ((count & 7u) == 0) bytes.push_back(0);
    if (bit & 1) bytes.back() |= (1u << (7u - (count & 7u)));
    ++count;
  }
  void put(unsigned n, uint32_t v) {
    for (int i = (int)n - 1; i >= 0; --i) put_bit((v >> i) & 1u);
  }
};

// Find the root->leaf bit path for a symbol in a JOC Huffman table.
bool huff_path(const int (*table)[2], int symbol, std::vector<int>& bits) {
  // Leaf value for `symbol` is ~symbol (negative); walk/search via DFS.
  struct Frame { int node; std::vector<int> path; };
  std::vector<Frame> stack{{0, {}}};
  const int leaf = ~symbol;
  while (!stack.empty()) {
    Frame f = stack.back();
    stack.pop_back();
    for (int b = 0; b < 2; ++b) {
      int child = table[f.node][b];
      std::vector<int> p = f.path;
      p.push_back(b);
      if (child <= 0) {
        if (child == leaf) { bits = p; return true; }
      } else {
        stack.push_back({child, p});
      }
    }
  }
  return false;
}

using namespace tf::atmos;
using namespace tf::atmos::cavern;

void test_huffman() {
  std::printf("huffman decode\n");
  const auto* coarse = joc_tables::get_huff_code_table(0, HuffmanType::MTX);
  // Symbol 0 must be the single-bit '0' path (root = {-1, 1}).
  std::vector<int> bits;
  CHECK(huff_path(coarse, 0, bits));
  CHECK(bits.size() == 1 && bits[0] == 0);

  // Encode a handful of symbols, decode them back through the real decoder.
  for (int sym : {0, 1, 2, 5, 40, 94}) {
    std::vector<int> path;
    if (!huff_path(coarse, sym, path)) { CHECK(false); continue; }
    BitWriter bw;
    for (int b : path) bw.put_bit(b);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    // Re-run the decoder's node walk.
    int node = 0;
    do { node = coarse[node][br.read_bit() ? 1 : 0]; } while (node > 0);
    CHECK(~node == sym);
  }

  // Fine table (mode 1) round-trips too.
  const auto* fine = joc_tables::get_huff_code_table(1, HuffmanType::MTX);
  for (int sym : {0, 3, 100, 190}) {
    std::vector<int> path;
    if (!huff_path(fine, sym, path)) { CHECK(false); continue; }
    BitWriter bw;
    for (int b : path) bw.put_bit(b);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    int node = 0;
    do { node = fine[node][br.read_bit() ? 1 : 0]; } while (node > 0);
    CHECK(~node == sym);
  }
}

void test_pb_mapping() {
  std::printf("parameter-band mapping\n");
  // bands index 0 has a single band -> every subband maps to band 0.
  const int* m0 = joc_tables::pb_mapping(0);
  for (int sb = 0; sb < 64; ++sb) CHECK(m0[sb] == 0);
  // index 1 boundaries {0,3,14}: sb<3 ->0, 3<=sb<14 ->1, sb>=14 ->2.
  const int* m1 = joc_tables::pb_mapping(1);
  CHECK(m1[0] == 0 && m1[2] == 0 && m1[3] == 1 && m1[13] == 1 && m1[14] == 2 && m1[63] == 2);
}

void put_symbol(BitWriter& bw, const int (*table)[2], int symbol) {
  std::vector<int> bits;
  const bool found = huff_path(table, symbol, bits);
  CHECK(found);
  for (int b : bits) bw.put_bit(b);
}

// One active object's header and info: `config` channels, fine or coarse.
void put_object_header(BitWriter& bw, int config, int bands_index, bool sparse, bool fine, bool steep,
                       int points, int offset0 = 0, int offset1 = 0, int sequence = 1) {
  bw.put(3, config);
  bw.put(6, 0);   // one object
  bw.put(3, 0);   // joc_ext_config_idx
  bw.put(3, 4);   // clip gain 1
  bw.put(5, 0);
  bw.put(10, sequence);
  bw.put_bit(1);  // active
  bw.put(3, bands_index);
  bw.put_bit(sparse);
  bw.put(1, fine ? 1 : 0);
  bw.put_bit(steep);
  bw.put(1, points - 1);
  if (steep) {
    bw.put(5, offset0 - 1);
    if (points == 2) bw.put(5, offset1 - 1);
  }
}

// Coefficients are steps along the bands on a ring of 96 (coarse) or 192
// (fine) levels, the centre being zero gain.
void test_dense_decode() {
  std::printf("dense decode: whole-number ring, steps of 820/4096 and 820/8192\n");
  const float coarse = 820.0f / 4096.0f, fine = 820.0f / 8192.0f;
  {
    BitWriter bw;
    put_object_header(bw, 0, 1, false, false, false, 1);  // 5 channels, 3 bands, coarse
    const auto* table = joc_tables::get_huff_code_table(0, HuffmanType::MTX);
    // Channel 0: +10, then -5 (91 on the ring), then +3. Channel 1: straight
    // to the bottom of the ring, 48 below the centre, and back up one.
    const int symbols[5][3] = {{10, 91, 3}, {48, 1, 0}, {0, 0, 0}, {0, 0, 0}, {95, 95, 2}};
    for (int ch = 0; ch < 5; ++ch) for (int pb = 0; pb < 3; ++pb) put_symbol(bw, table, symbols[ch][pb]);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    JointObjectCoding joc;
    joc.decode(br);
    CHECK(joc.valid());
    joc.get_mixing_matrices(1536);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 0, 0), 10 * coarse, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 0, 1), 5 * coarse, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 0, 2), 8 * coarse, 1e-6f);
    // The lowest level is -9.6, not +9.6 (which float modulo made of it).
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 1, 0), -48 * coarse, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 1, 1), -47 * coarse, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 2, 2), 0.0f, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 4, 0), -1 * coarse, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 4, 2), 0.0f, 1e-6f);
  }
  {
    BitWriter bw;
    put_object_header(bw, 0, 0, false, true, false, 1);  // 1 band, fine
    const auto* table = joc_tables::get_huff_code_table(1, HuffmanType::MTX);
    const int symbols[5] = {96, 191, 7, 0, 100};
    for (int ch = 0; ch < 5; ++ch) put_symbol(bw, table, symbols[ch]);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    JointObjectCoding joc;
    joc.decode(br);
    joc.get_mixing_matrices(1536);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 0, 0), -96 * fine, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 1, 0), -1 * fine, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 2, 0), 7 * fine, 1e-6f);
    CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, 4, 0), -92 * fine, 1e-6f);
  }
}

// Sparse: one channel a band, chosen by a chain of its own; the coefficient's
// chain runs on through a change of channel, from 50.
void test_sparse_decode() {
  std::printf("sparse decode: one channel a band\n");
  const float coarse = 820.0f / 4096.0f;
  BitWriter bw;
  put_object_header(bw, 0, 1, true, false, false, 1);  // 5 channels, 3 bands, sparse
  bw.put(3, 2);  // the first band's channel
  const auto* idx = joc_tables::get_huff_code_table(5, HuffmanType::IDX);
  put_symbol(bw, idx, 1);  // 2 -> 3
  put_symbol(bw, idx, 4);  // 3 -> 2
  const auto* vec = joc_tables::get_huff_code_table(0, HuffmanType::VEC);
  put_symbol(bw, vec, 3);   // 50 -> 53
  put_symbol(bw, vec, 10);  // 63
  put_symbol(bw, vec, 94);  // 61
  BitReader br(bw.bytes.data(), bw.bytes.size());
  JointObjectCoding joc;
  joc.decode(br);
  CHECK(joc.valid());
  joc.get_mixing_matrices(1536);
  const int channel[3] = {2, 3, 2};
  const int level[3] = {53 - 48, 63 - 48, 61 - 48};
  for (int pb = 0; pb < 3; ++pb) {
    for (int ch = 0; ch < 5; ++ch) {
      const float want = ch == channel[pb] ? level[pb] * coarse : 0.0f;
      CHECK_NEAR(JointObjectCodingProbe::band(joc, 0, 0, ch, pb), want, 1e-6f);
    }
  }
  // And it is heard: the last timeslot's row is the decoded matrix.
  float scratch[JointObjectCoding::kSubbands];
  const float* row = joc.mixing_row(0, 23, 2, scratch);
  CHECK_NEAR(row[0], 5 * coarse, 1e-5f);
}

// A steep object switches to its decoded matrix in every subband, not only
// in the first few (as many as it has parameter bands).
void test_steep_rows() {
  std::printf("steep slope: the decoded matrix in every subband\n");
  const float coarse = 820.0f / 4096.0f;
  const auto* table = joc_tables::get_huff_code_table(0, HuffmanType::MTX);
  float scratch[JointObjectCoding::kSubbands];
  {
    BitWriter bw;
    put_object_header(bw, 0, 5, false, false, true, 1, /*offset0=*/7);  // 12 bands
    for (int ch = 0; ch < 5; ++ch) for (int pb = 0; pb < 12; ++pb) put_symbol(bw, table, pb == 0 ? 4 + ch : 1);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    JointObjectCoding joc;
    joc.decode(br);
    CHECK(joc.valid());
    joc.get_mixing_matrices(1536);
    const int* pb_mapping = joc_tables::pb_mapping(5);
    for (int ch = 0; ch < 5; ++ch) {
      // Before the offset: where the frame started (a seek's first frame
      // starts on its own matrix, so both sides are the decoded one here).
      const float* after = joc.mixing_row(0, 7, ch, scratch);
      for (int sb = 0; sb < JointObjectCoding::kSubbands; ++sb) {
        CHECK_NEAR(after[sb], (4 + ch + pb_mapping[sb]) * coarse, 1e-5f);
      }
      CHECK(after[63] != 0.0f);  // the top of the band is there
    }
  }
  {
    // Two data points, each from its own offset.
    BitWriter bw;
    put_object_header(bw, 0, 0, false, false, true, 2, /*offset0=*/4, /*offset1=*/15, /*sequence=*/0);
    for (int dp = 0; dp < 2; ++dp) for (int ch = 0; ch < 5; ++ch) put_symbol(bw, table, dp == 0 ? 10 : 20);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    JointObjectCoding joc;
    joc.decode(br);
    joc.get_mixing_matrices(1536);
    // A stream's first frame (sequence 0) starts from nothing.
    CHECK_NEAR(joc.mixing_row(0, 3, 0, scratch)[30], 0.0f, 1e-6f);
    CHECK_NEAR(joc.mixing_row(0, 4, 0, scratch)[30], 10 * coarse, 1e-5f);
    CHECK_NEAR(joc.mixing_row(0, 14, 0, scratch)[30], 10 * coarse, 1e-5f);
    CHECK_NEAR(joc.mixing_row(0, 15, 0, scratch)[30], 20 * coarse, 1e-5f);
    CHECK_NEAR(joc.mixing_row(0, 23, 0, scratch)[30], 20 * coarse, 1e-5f);
  }
}

// Downmix configurations 3 and 4 carry the surrounds turned 90 degrees; the
// objects are made from them turned back.
void test_surround_phase() {
  std::printf("surround pair turned back 90 degrees for configurations 3 and 4\n");
  const int samples = 1536 * 4;
  const double w = 2.0 * 3.14159265358979 * 1000.0 / 48000.0;
  auto run = [&](int config, int channel, double& in_phase, double& quadrature) {
    BitWriter bw;
    put_object_header(bw, config, 0, false, false, false, 1);
    const auto* table = joc_tables::get_huff_code_table(0, HuffmanType::MTX);
    const int channels = (config == 0 || config == 3) ? 5 : 7;
    for (int ch = 0; ch < channels; ++ch) put_symbol(bw, table, 0);
    BitReader br(bw.bytes.data(), bw.bytes.size());
    JointObjectCoding joc;
    joc.decode(br);
    CHECK(joc.valid());
    CHECK(joc.surround_phase_shift() == (config >= 3));
    JointObjectCodingApplier applier(joc);
    std::vector<std::vector<float>> input(channels, std::vector<float>(64, 0.0f));
    std::vector<float> out(samples);
    for (int ts = 0; ts < samples / 64; ++ts) {
      if (ts % 24 == 0) {
        joc.get_mixing_matrices(1536);
        JointObjectCodingProbe::set_passes(joc, 0, channel);
      }
      for (int i = 0; i < 64; ++i) input[channel][i] = static_cast<float>(std::sin(w * (ts * 64 + i)));
      const auto& o = applier.apply(input, joc, ts % 24);
      for (int i = 0; i < 64; ++i) out[ts * 64 + i] = o[0][i];
    }
    // Against the input 577 samples late (the filter bank's delay), and
    // against that a quarter turn behind.
    in_phase = quadrature = 0.0;
    double power = 0.0;
    for (int n = 2048; n < samples; ++n) {
      in_phase += out[n] * std::sin(w * (n - 577));
      quadrature += out[n] * -std::cos(w * (n - 577));
      power += out[n] * out[n];
    }
    in_phase /= power;
    quadrature /= power;
  };
  double same, turned;
  run(0, 3, same, turned);   // plain 5.x: Ls as it comes
  CHECK_NEAR(static_cast<float>(same), 1.0f, 0.02f);
  CHECK_NEAR(static_cast<float>(turned), 0.0f, 0.02f);
  run(3, 3, same, turned);   // 5.x with the phase shift: Ls a quarter turn back
  CHECK_NEAR(static_cast<float>(same), 0.0f, 0.02f);
  CHECK_NEAR(static_cast<float>(turned), 1.0f, 0.02f);
  run(3, 4, same, turned);   // and Rs
  CHECK_NEAR(static_cast<float>(turned), 1.0f, 0.02f);
  run(3, 0, same, turned);   // the fronts are left alone
  CHECK_NEAR(static_cast<float>(same), 1.0f, 0.02f);
  run(4, 3, same, turned);   // 5.x+2 with the phase shift
  CHECK_NEAR(static_cast<float>(turned), 1.0f, 0.02f);
  run(4, 5, same, turned);   // its height pair is left alone
  CHECK_NEAR(static_cast<float>(same), 1.0f, 0.02f);
}

void test_end_to_end() {
  std::printf("end-to-end decode + apply (1 object, matrix-coded)\n");
  BitWriter bw;
  // Header
  bw.put(3, 0);   // downmix_config 0 -> 5 channels
  bw.put(6, 0);   // object_count-1 = 0 -> 1 object
  bw.put(3, 0);   // joc_ext_config_idx = 0
  // Info
  bw.put(3, 4);   // gain_power 4
  bw.put(5, 0);   // gain mantissa 0 -> Gain = 1.0
  bw.put(10, 0);  // sequence counter
  bw.put_bit(1);  // object 0 active
  bw.put(3, 0);   // bands_index 0 -> 1 band
  bw.put_bit(0);  // not sparse
  bw.put(1, 0);   // quant table 0 (coarse)
  bw.put_bit(0);  // not steep
  bw.put(1, 0);   // data_points = 1
  // Data: 5 channels x 1 band, symbol 0 = single '0' bit each.
  for (int i = 0; i < 5; ++i) bw.put_bit(0);

  BitReader br(bw.bytes.data(), bw.bytes.size());
  JointObjectCoding joc;
  joc.decode(br);
  CHECK(joc.valid());
  CHECK(joc.channel_count() == 5);
  CHECK(joc.object_count() == 1);
  CHECK_NEAR(joc.gain(), 1.0f, 1e-6f);
  CHECK(joc.object_active(0));

  joc.get_mixing_matrices(1536);  // 24 timeslots

  JointObjectCodingApplier applier(joc);
  std::vector<std::vector<float>> input(5, std::vector<float>(64, 0.0f));
  bool finite = true;
  for (int ts = 0; ts < 1536 / 64; ++ts) {
    const auto& out = applier.apply(input, joc, ts);
    for (float v : out[0]) if (!std::isfinite(v)) finite = false;
  }
  CHECK(finite);
  // All-zero source symbols dequantize to a zero matrix, so zero input -> zero out.
  const auto& out = applier.apply(input, joc, 0);
  float energy = 0.0f;
  for (float v : out[0]) energy += v * v;
  CHECK_NEAR(energy, 0.0f, 1e-6f);
}

// A payload with random per-object modes (active or not, bands, quantizer,
// steep or smooth, one or two data points, offsets) and random symbols: any
// bit string walks a Huffman tree to some leaf.
void random_payload(JointObjectCoding& joc, uint32_t& seed, int objects) {
  auto rnd = [&seed](uint32_t n) {
    seed = seed * 1664525u + 1013904223u;
    return (seed >> 8) % n;
  };
  BitWriter bw;
  bw.put(3, rnd(2) ? 1 : 0);  // 7 or 5 channels
  bw.put(6, objects - 1);
  bw.put(3, 0);
  bw.put(3, 4);
  bw.put(5, rnd(32));
  bw.put(10, 1 + rnd(1023));
  for (int o = 0; o < objects; ++o) {
    const bool active = rnd(8) != 0;
    bw.put_bit(active);
    if (!active) continue;
    bw.put(3, rnd(8));
    bw.put_bit(0);  // not sparse
    bw.put(1, rnd(2));
    const bool steep = rnd(3) == 0;
    bw.put_bit(steep);
    const int dp = 1 + rnd(2);
    bw.put(1, dp - 1);
    if (steep) for (int d = 0; d < dp; ++d) bw.put(5, rnd(32));
  }
  for (int i = 0; i < 6000; ++i) bw.put_bit(rnd(2));
  BitReader br(bw.bytes.data(), bw.bytes.size());
  joc.decode(br);
}

void test_rows_match_table() {
  std::printf("mixing rows on demand == the table worked out ahead of the frame\n");
  JointObjectCoding joc;
  uint32_t seed = 7;
  float scratch[JointObjectCoding::kSubbands];
  int compared = 0, mismatched = 0;
  double worst = 0.0;  // largest difference, relative to the value
  for (int frame = 0; frame < 200; ++frame) {
    // A new payload most frames; the rest hold the last one, as the engine
    // does between payloads (the ramp then starts where the last one ended).
    if (frame % 4 != 3) random_payload(joc, seed, frame < 100 ? 16 : 5);
    if (!joc.valid()) continue;
    const int frame_size = frame % 5 == 4 ? 1024 : 1536;
    const int timeslots = frame_size / JointObjectCoding::kSubbands;
    joc.get_mixing_matrices(frame_size);
    for (int obj = 0; obj < joc.object_count(); ++obj) {
      const auto rows = JointObjectCodingProbe::table_rows(joc, obj, timeslots);
      for (int ts = 0; ts < timeslots; ++ts) {
        for (int ch = 0; ch < joc.channel_count(); ++ch) {
          const float* got = joc.mixing_row(obj, ts, ch, scratch);
          const float* want = rows[ts][ch].data();
          ++compared;
          if (std::memcmp(got, want, sizeof(scratch)) != 0) ++mismatched;
          for (int sb = 0; sb < JointObjectCoding::kSubbands; ++sb) {
            const double d = std::fabs(static_cast<double>(got[sb]) - want[sb]);
            worst = std::fmax(worst, d / std::fmax(1.0, std::fabs(static_cast<double>(want[sb]))));
          }
        }
      }
    }
  }
  std::printf("  %d rows compared, %d not bit for bit, worst %.3g\n", compared, mismatched, worst);
  CHECK(compared > 10000);
  // Bit for bit as built here and on arm64. Under -ffast-math a compiler may
  // round the two copies of the same interpolation differently (the armv7
  // build does, by up to 2.4e-7), so the bound is a few ulps, not zero.
  CHECK(worst < 1e-6);
}

}  // namespace

int main() {
  std::printf("=== Cavern JOC tests ===\n");
  test_huffman();
  test_pb_mapping();
  test_dense_decode();
  test_sparse_decode();
  test_steep_rows();
  test_surround_phase();
  test_end_to_end();
  test_rows_match_table();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}
