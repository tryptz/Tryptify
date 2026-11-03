// Host test for render/partitioned_fir.h, the spatial map's equalizer.
//
//   c++ -std=c++17 -I.. partitioned_fir_test.cpp -o fir && ./fir
//
// The partitioned convolution must be the direct-form FIR it replaced, to
// float rounding, whatever the call sizes: whole blocks, calls that end and
// start inside a block, single samples, a filter swapped mid-block, a reset.
// An impulse must come back as the filter itself, from the same sample: no
// latency.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "render/partitioned_fir.h"

namespace {

using tf::atmos::render::StereoPartitionedFir;
constexpr int kTaps = 512;

int failures = 0;
void check(bool ok, const char* what) {
  std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
  if (!ok) ++failures;
}

uint32_t seed = 11;
float rnd() {
  seed = seed * 1664525u + 1013904223u;
  return static_cast<float>((seed >> 8) & 0xFFFF) / 32768.0f - 1.0f;
}

// A decaying random filter, like the equalizer's minimum-phase FIR.
std::vector<float> filter() {
  std::vector<float> h(kTaps);
  for (int k = 0; k < kTaps; ++k) h[k] = rnd() * std::exp(-k / 90.0f);
  return h;
}

// The direct form, summed in double, over interleaved stereo history.
struct Direct {
  std::vector<float> h;
  std::vector<float> x[2];
  float process(int ch, float in) {
    x[ch].push_back(in);
    const int n = static_cast<int>(x[ch].size());
    double y = 0.0;
    for (int k = 0; k < kTaps && k < n; ++k) y += static_cast<double>(h[k]) * x[ch][n - 1 - k];
    return static_cast<float>(y);
  }
  void reset() { x[0].clear(); x[1].clear(); }
};

}  // namespace

int main() {
  static StereoPartitionedFir<kTaps> fir;
  Direct direct;
  direct.h = filter();
  fir.set_filter(direct.h.data());

  const int sizes[] = {128, 512, 77, 1, 1, 300, 1024, 129, 127, 3, 256, 1000};
  std::vector<float> io(2 * 1024);
  double max = 0.0, peak = 0.0;
  for (int call = 0; call < 120; ++call) {
    const int n = sizes[call % 12];
    if (call == 40 || call == 77) {
      // A new filter mid-stream, landing mid-block for one of them.
      direct.h = filter();
      fir.set_filter(direct.h.data());
    }
    if (call == 95) {
      direct.reset();
      fir.reset();
    }
    for (int i = 0; i < 2 * n; ++i) io[i] = 0.5f * rnd();
    std::vector<float> want(2 * n);
    for (int i = 0; i < n; ++i) {
      want[2 * i] = direct.process(0, io[2 * i]);
      want[2 * i + 1] = direct.process(1, io[2 * i + 1]);
    }
    fir.process(io.data(), n);
    for (int i = 0; i < 2 * n; ++i) {
      max = std::fmax(max, std::fabs(static_cast<double>(io[i]) - want[i]));
      peak = std::fmax(peak, std::fabs(static_cast<double>(want[i])));
    }
  }
  std::printf("partitioned vs direct form: max diff %.3g, peak %.3g, ratio %.3g\n", max, peak, max / peak);
  // Float rounding; measured 4.5e-7 of peak.
  check(max / peak < 2e-6, "matches the direct-form FIR to float rounding at ragged call sizes");

  // Impulse on the left only, in calls of 100: the left must give back the
  // filter tap for tap from the impulse's own sample, the right silence.
  fir.reset();
  std::vector<float> h = filter();
  fir.set_filter(h.data());
  double err = 0.0, cross = 0.0;
  int at = 0;
  for (int call = 0; call < 8; ++call) {
    for (int i = 0; i < 2 * 100; ++i) io[i] = 0.0f;
    if (call == 0) io[2 * 37] = 1.0f;
    fir.process(io.data(), 100);
    for (int i = 0; i < 100; ++i, ++at) {
      const int k = at - 37;
      const float expect = (k >= 0 && k < kTaps) ? h[k] : 0.0f;
      err = std::fmax(err, std::fabs(io[2 * i] - expect));
      cross = std::fmax(cross, std::fabs(io[2 * i + 1]));
    }
  }
  std::printf("impulse response error %.3g, other channel %.3g\n", err, cross);
  check(err < 1e-6, "an impulse returns the filter from its own sample: no latency");
  check(cross < 1e-6, "the channels stay apart");

  std::printf(failures == 0 ? "=== PASS ===\n" : "=== FAIL ===\n");
  return failures == 0 ? 0 : 1;
}
