// Host test for AtmosPipeline's loudspeaker path (process_frame_speakers).
//
//   c++ -std=c++17 -I.. atmos_speaker_pipeline_test.cpp -o spkpipe && ./spkpipe
//
// The multichannel output: passthrough (-1),
// the bed fallback delayed by exactly the QMF round-trip and routed to the
// matching speaker, the fallback->render seam starting on the fallback signal,
// a JOC frame rendering finite output on every layout, and flush clearing the
// delay line.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "atmos_pipeline.h"
// A number a test compares must be a number: under -ffast-math (the
// *_fastmath build, and the phone's) a comparison with NaN is whatever the
// compiler likes, so a NaN could pass every CHECK_NEAR. This looks at the
// bits behind a barrier the compiler cannot see through (dsp/util/finite.h).
#include "joc_frame.h"
#include "check.h"

namespace {

using namespace tf::atmos;

constexpr int kSamples = 1536;

void test_passthrough() {
  std::printf("speakers: passthrough mode -> -1\n");
  AtmosPipeline pipe; pipe.configure(48000, 16);
  pipe.set_output_layout(static_cast<int>(OutputLayout::k7_1_4));
  pipe.set_params(/*mode=*/0, 0, 1.0f, true, 0.0f, false, 80, 0, false);
  std::vector<uint8_t> frame(64, 0xAB);
  std::vector<float> ch(kSamples, 0.1f);
  const float* bed[1] = {ch.data()};
  std::vector<float> out(12 * kSamples);
  CHECK(pipe.process_frame_speakers(frame.data(), frame.size(), bed, 1, kSamples, out.data()) == -1);
}

void test_fallback_latency_routing() {
  std::printf("speakers: non-Atmos 5.1 bed -> right speakers, delayed by the QMF latency\n");
  AtmosPipeline pipe; pipe.configure(48000, 16);
  const int ch = pipe.set_output_layout(static_cast<int>(OutputLayout::k7_1_4));
  CHECK(ch == 12);
  std::vector<std::vector<float>> bed(6, std::vector<float>(kSamples, 0.0f));
  bed[0][100] = 1.0f;  // FL impulse
  bed[2][200] = 0.5f;  // FC impulse
  std::vector<const float*> ptrs(6);
  for (int c = 0; c < 6; ++c) ptrs[c] = bed[c].data();
  std::vector<uint8_t> frame(64, 0xAB);
  std::vector<float> out(static_cast<size_t>(ch) * kSamples, -1.0f);
  CHECK(pipe.process_frame_speakers(frame.data(), frame.size(), ptrs.data(), 6, kSamples, out.data()) == 1);
  CHECK_NEAR(out[static_cast<size_t>(100 + 577) * ch + 0], 1.0f, 1e-6f);
  CHECK_NEAR(out[static_cast<size_t>(200 + 577) * ch + 2], 0.5f, 1e-6f);
  float other = 0.0f;
  for (int i = 0; i < kSamples; ++i)
    for (int c = 0; c < ch; ++c) {
      const bool expected = (i == 677 && c == 0) || (i == 777 && c == 2);
      if (!expected) other += std::fabs(out[static_cast<size_t>(i) * ch + c]);
    }
  CHECK_NEAR(other, 0.0f, 1e-6f);

  // flush clears the delay line: a silent frame after it stays silent.
  pipe.flush_state();
  for (auto& b : bed) std::fill(b.begin(), b.end(), 0.0f);
  CHECK(pipe.process_frame_speakers(frame.data(), frame.size(), ptrs.data(), 6, kSamples, out.data()) == 1);
  float sum = 0.0f;
  for (float v : out) sum += std::fabs(v);
  CHECK_NEAR(sum, 0.0f, 1e-9f);
}

void test_seam() {
  std::printf("speakers: fallback -> render seam starts on the fallback signal\n");
  AtmosPipeline pipe; pipe.configure(48000, 16);
  const int ch = pipe.set_output_layout(static_cast<int>(OutputLayout::k5_1));
  std::vector<std::vector<float>> bed(5, std::vector<float>(kSamples));
  std::vector<const float*> ptrs(5);
  for (int c = 0; c < 5; ++c) ptrs[c] = bed[c].data();
  for (int c = 0; c < 5; ++c)
    for (int i = 0; i < kSamples; ++i) bed[c][i] = 0.2f * std::sin((i + c) * 0.03f);
  std::vector<uint8_t> plain(64, 0xAB);
  std::vector<float> out(static_cast<size_t>(ch) * kSamples);
  CHECK(pipe.process_frame_speakers(plain.data(), plain.size(), ptrs.data(), 5, kSamples, out.data()) == 1);
  BitWriter fw; write_emdf_joc_frame(fw);
  for (int c = 0; c < 5; ++c)
    for (int i = 0; i < kSamples; ++i) bed[c][i] = 0.2f * std::sin((kSamples + i + c) * 0.03f);
  CHECK(pipe.process_frame_speakers(fw.bytes.data(), fw.bytes.size(), ptrs.data(), 5, kSamples, out.data()) == 1);
  // First sample of the seam frame = FL of the fallback, 577 samples back.
  CHECK_NEAR(out[0], 0.2f * std::sin((2 * kSamples - 577) * 0.03f), 0.02f);
}

void test_joc_every_layout() {
  std::printf("speakers: JOC frame renders finite output on every layout\n");
  for (int id = 1; id <= 8; ++id) {
    AtmosPipeline pipe; pipe.configure(48000, 16);
    const int ch = pipe.set_output_layout(id);
    std::vector<std::vector<float>> bed(5, std::vector<float>(kSamples));
    std::vector<const float*> ptrs(5);
    for (int c = 0; c < 5; ++c) {
      for (int i = 0; i < kSamples; ++i) bed[c][i] = 0.2f * std::sin((i + c) * 0.03f);
      ptrs[c] = bed[c].data();
    }
    BitWriter fw; write_emdf_joc_frame(fw);
    std::vector<float> out(static_cast<size_t>(ch) * kSamples, 0.0f);
    const int rc = pipe.process_frame_speakers(fw.bytes.data(), fw.bytes.size(), ptrs.data(), 5, kSamples, out.data());
    CHECK(rc == 1);
    bool finite = true;
    for (float v : out) if (!dspIsFinite(v)) finite = false;
    CHECK(finite);
  }
}

}  // namespace

int main() {
  std::printf("=== AtmosPipeline speaker path tests ===\n");
  test_passthrough();
  test_fallback_latency_routing();
  test_seam();
  test_joc_every_layout();
  std::printf("=== %d checks, %d failures ===\n", g_checks, g_fail);
  return g_fail == 0 ? 0 : 1;
}
