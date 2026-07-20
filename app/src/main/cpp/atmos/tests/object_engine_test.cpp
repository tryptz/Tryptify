// Host test for the Atmos ObjectEngine (plan P1): bed PCM + JOC -> object PCM.
//
//   c++ -std=c++17 -I.. object_engine_test.cpp -o oe && ./oe
//
// Drives the engine's per-frame 24-timeslot upmix loop with a valid (matrix-
// coded, 5-channel, 1-object) JOC built the same way as cavern_joc_test, plus a
// non-Atmos frame. Like the JOC test, this proves the plumbing (sizing, reuse,
// finiteness) — full numeric correctness needs reference Atmos content.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "object_engine.h"

namespace {
int g_fail = 0, g_checks = 0;
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
#define CHECK(c) check((c), #c, __LINE__)

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

using namespace tf::atmos;

// A valid JOC bitstream: 5 channels, 1 object, 1 band, coarse zero matrix, gain 1.
void write_joc(BitWriter& bw) {
  bw.put(3, 0);   // downmix_config 0 -> 5 channels
  bw.put(6, 0);   // object_count-1 = 0 -> 1 object
  bw.put(3, 0);   // joc_ext_config_idx = 0
  bw.put(3, 4);   // gain_power 4
  bw.put(5, 0);   // gain mantissa 0 -> gain 1.0
  bw.put(10, 0);  // sequence counter
  bw.put_bit(1);  // object 0 active
  bw.put(3, 0);   // bands_index 0 -> 1 band
  bw.put_bit(0);  // not sparse
  bw.put(1, 0);   // quant table 0 (coarse)
  bw.put_bit(0);  // not steep
  bw.put(1, 0);   // data_points = 1
  for (int i = 0; i < 5; ++i) bw.put_bit(0);  // 5 channels x 1 band, symbol 0
}

void test_upmix_smoke() {
  std::printf("ObjectEngine: bed + JOC -> objects (24-timeslot frame loop)\n");
  BitWriter bw;
  write_joc(bw);
  BitReader br(bw.bytes.data(), bw.bytes.size());
  cavern::JointObjectCoding joc;
  joc.decode(br);
  CHECK(joc.valid() && joc.channel_count() == 5 && joc.object_count() == 1);

  const int samples = 1536;
  std::vector<std::vector<float>> bed(5, std::vector<float>(samples));
  for (int ch = 0; ch < 5; ++ch)
    for (int i = 0; i < samples; ++i)
      bed[ch][i] = 0.25f * std::sin((i + ch * 7) * 0.05f);  // non-zero source
  std::vector<const float*> bed_ptrs(5);
  for (int ch = 0; ch < 5; ++ch) bed_ptrs[ch] = bed[ch].data();

  ObjectEngine engine;
  const int n = engine.upmix(joc, bed_ptrs.data(), 5, samples);
  CHECK(n == 1);
  CHECK(engine.object_count() == 1);
  CHECK(engine.frame_samples() == samples);

  const float* obj0 = engine.object_channel(0);
  CHECK(obj0 != nullptr);
  bool finite = true;
  for (int i = 0; i < samples; ++i) if (!std::isfinite(obj0[i])) finite = false;
  CHECK(finite);
  CHECK(engine.object_channel(1) == nullptr);  // out of range

  // Reuse path: a second upmix must reconfigure cleanly and stay valid.
  const int n2 = engine.upmix(joc, bed_ptrs.data(), 5, samples);
  CHECK(n2 == 1 && engine.object_channel(0) != nullptr);

  // Reject a non-multiple-of-64 frame size.
  CHECK(engine.upmix(joc, bed_ptrs.data(), 5, 1000) == -1);
}

void test_no_joc_frame() {
  std::printf("ObjectEngine: non-Atmos frame -> -1\n");
  std::vector<uint8_t> frame(64, 0xAB);  // no EMDF syncword
  std::vector<float> ch(1536, 0.1f);
  const float* bed[1] = {ch.data()};
  ObjectEngine engine;
  CHECK(engine.upmix_frame(frame.data(), frame.size(), bed, 1, 1536) == -1);
}

}  // namespace

int main() {
  std::printf("=== Atmos ObjectEngine tests ===\n");
  test_upmix_smoke();
  test_no_joc_frame();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}
