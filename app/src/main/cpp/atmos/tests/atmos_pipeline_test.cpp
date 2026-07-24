// Host test for the end-to-end native Atmos pipeline (plan P1->P2 glue).
//
//   c++ -std=c++17 -I.. atmos_pipeline_test.cpp -o pipe && ./pipe
//
// Checks the render-space position -> azimuth/elevation mapping, the
// passthrough mode (-1), the latency-matched fallback downmix (a non-Atmos
// frame emerges as an ITU fold-down delayed by exactly the QMF round-trip),
// the downmix->render seam crossfade, and a full run: an EMDF frame carrying a
// JOC payload + bed PCM -> binaural stereo.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "atmos_pipeline.h"

namespace {
int g_fail = 0, g_checks = 0;
void check(bool c, const char* e, int line) {
  ++g_checks;
  if (!c) { ++g_fail; std::printf("  FAIL %s:%d %s\n", __FILE__, line, e); }
}
void check_near(float a, float b, float t, const char* e, int line) {
  ++g_checks;
  if (std::fabs(a - b) > t) { ++g_fail; std::printf("  FAIL %s:%d %s (%.3f vs %.3f)\n", __FILE__, line, e, a, b); }
}
#define CHECK(c) check((c), #c, __LINE__)
#define CHECK_NEAR(a, b, t) check_near((a), (b), (t), #a " ~= " #b, __LINE__)

struct BitWriter {
  std::vector<uint8_t> bytes;
  size_t count = 0;
  void bit(int b) {
    if ((count & 7u) == 0) bytes.push_back(0);
    if (b & 1) bytes.back() |= (1u << (7u - (count & 7u)));
    ++count;
  }
  void put(unsigned n, uint32_t v) { for (int i = (int)n - 1; i >= 0; --i) bit((v >> i) & 1u); }
};

using namespace tf::atmos;

constexpr float kHalfPiC = 1.57079632679f;

// JOC body: 5 channels, 1 object, coarse zero matrix, gain 1 (as cavern_joc_test).
void write_joc(BitWriter& bw) {
  bw.put(3, 0); bw.put(6, 0); bw.put(3, 0);
  bw.put(3, 4); bw.put(5, 0); bw.put(10, 0);
  bw.bit(1); bw.put(3, 0); bw.bit(0); bw.put(1, 0); bw.bit(0); bw.put(1, 0);
  for (int i = 0; i < 5; ++i) bw.bit(0);
}

// Wrap a JOC body (payload id 14) in an EMDF block behind a garbage prefix.
void write_emdf_joc_frame(BitWriter& bw) {
  BitWriter probe; write_joc(probe);
  const unsigned payload_bytes = (unsigned)((probe.count + 7) / 8);
  bw.put(8, 0x00); bw.put(8, 0x11);      // garbage prefix
  bw.put(8, 0x58); bw.put(8, 0x38);      // EMDF syncword
  bw.put(16, 3 + payload_bytes);         // block length
  bw.put(2, 0); bw.put(3, 0);            // version, key
  bw.put(5, 14);                         // payload id = JOC
  bw.bit(0);                             // has_sample_offset
  bw.bit(0); bw.bit(0); bw.bit(0);       // flags
  bw.bit(1);                             // skip alignment block
  bw.put(8, payload_bytes); bw.bit(0);   // VariableBits(8) = payload size
  const size_t body_start = bw.count;
  write_joc(bw);
  while (bw.count < body_start + payload_bytes * 8) bw.bit(0);
}

void test_azel() {
  std::printf("AtmosPipeline: position -> azimuth/elevation\n");
  float az, el;
  position_to_azel(Vec3{0, 0, 1}, az, el);   CHECK_NEAR(az, 0.0f, 1e-3f); CHECK_NEAR(el, 0.0f, 1e-3f);
  position_to_azel(Vec3{1, 0, 0}, az, el);   CHECK_NEAR(az, kHalfPiC, 1e-3f);
  position_to_azel(Vec3{-1, 0, 0}, az, el);  CHECK_NEAR(az, -kHalfPiC, 1e-3f);
  position_to_azel(Vec3{0, 1, 0}, az, el);   CHECK_NEAR(el, kHalfPiC, 1e-3f);
}

void test_passthrough_mode() {
  std::printf("AtmosPipeline: passthrough mode -> -1\n");
  std::vector<uint8_t> frame(64, 0xAB);
  std::vector<float> ch(1536, 0.1f);
  const float* bed[1] = {ch.data()};
  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_params(/*mode=*/0, /*downmix=*/0, 1.0f, true, 0.0f, false, 80, 0, false);
  std::vector<float> out(2 * 1536, 0.0f);
  CHECK(pipe.process_frame(frame.data(), frame.size(), bed, 1, 1536, out.data()) == -1);
}

void test_downmix_latency() {
  std::printf("AtmosPipeline: non-Atmos frame -> downmix delayed by the QMF latency\n");
  std::vector<uint8_t> frame(64, 0xAB);
  std::vector<float> ch(1536, 0.0f);
  ch[100] = 1.0f;  // impulse
  const float* bed[1] = {ch.data()};
  AtmosPipeline pipe; pipe.configure(48000, 16);
  std::vector<float> out(2 * 1536, -1.0f);
  CHECK(pipe.process_frame(frame.data(), frame.size(), bed, 1, 1536, out.data()) == 1);
  // The impulse must emerge exactly 577 samples late (the measured QMF
  // round-trip that the object-render path incurs), in both ears.
  const int at = 100 + 577;
  CHECK_NEAR(out[2 * at], 1.0f, 1e-6f);
  CHECK_NEAR(out[2 * at + 1], 1.0f, 1e-6f);
  float pre = 0.0f;
  for (int i = 0; i < at; ++i) pre += std::fabs(out[2 * i]) + std::fabs(out[2 * i + 1]);
  CHECK_NEAR(pre, 0.0f, 1e-6f);  // nothing before the delayed impulse
}

void test_seam_continuity() {
  std::printf("AtmosPipeline: downmix -> render seam starts on the downmix signal\n");
  const int samples = 1536;
  std::vector<std::vector<float>> bed(5, std::vector<float>(samples));
  std::vector<const float*> bed_ptrs(5);
  for (int ch = 0; ch < 5; ++ch) bed_ptrs[ch] = bed[ch].data();

  AtmosPipeline pipe; pipe.configure(48000, 16);
  std::vector<float> out(2 * samples, 0.0f);

  // Frame 1: no JOC -> the delayed downmix path.
  for (int ch = 0; ch < 5; ++ch)
    for (int i = 0; i < samples; ++i) bed[ch][i] = 0.2f * std::sin((i + ch) * 0.03f);
  std::vector<uint8_t> plain(64, 0xAB);
  CHECK(pipe.process_frame(plain.data(), plain.size(), bed_ptrs.data(), 5, samples, out.data()) == 1);

  // Frame 2: JOC -> the render path; the switch must crossfade, so the frame's
  // first sample is still ~the downmix continuation (equal-power weight ~1),
  // which for <6 bed channels is bed[0] delayed by the QMF latency.
  BitWriter fw; write_emdf_joc_frame(fw);
  for (int ch = 0; ch < 5; ++ch)
    for (int i = 0; i < samples; ++i) bed[ch][i] = 0.2f * std::sin((samples + i + ch) * 0.03f);
  CHECK(pipe.process_frame(fw.bytes.data(), fw.bytes.size(), bed_ptrs.data(), 5, samples, out.data()) == 1);
  const float expect = 0.2f * std::sin((2 * samples - 577) * 0.03f);  // bed[0], 577 back
  CHECK_NEAR(out[0], expect, 0.02f);
}

void test_end_to_end() {
  std::printf("AtmosPipeline: EMDF(JOC) frame + bed -> binaural stereo\n");
  BitWriter fw; write_emdf_joc_frame(fw);
  const int samples = 1536;
  std::vector<std::vector<float>> bed(5, std::vector<float>(samples));
  for (int ch = 0; ch < 5; ++ch)
    for (int i = 0; i < samples; ++i) bed[ch][i] = 0.2f * std::sin((i + ch) * 0.03f);
  std::vector<const float*> bed_ptrs(5);
  for (int ch = 0; ch < 5; ++ch) bed_ptrs[ch] = bed[ch].data();

  AtmosPipeline pipe; pipe.configure(48000, 16);
  std::vector<float> out(2 * samples, 0.0f);
  const int rc = pipe.process_frame(fw.bytes.data(), fw.bytes.size(), bed_ptrs.data(), 5, samples, out.data());
  CHECK(rc == 1);  // Atmos rendered
  bool finite = true;
  for (float v : out) if (!std::isfinite(v)) finite = false;
  CHECK(finite);
}

}  // namespace

int main() {
  std::printf("=== Atmos pipeline (P1->P2) tests ===\n");
  test_azel();
  test_passthrough_mode();
  test_downmix_latency();
  test_seam_continuity();
  test_end_to_end();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}
