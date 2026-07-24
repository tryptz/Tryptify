// Host test for motion smoothing in the measured-HRIR binaural renderer.
//
//   c++ -std=c++17 -I.. hrtf_motion_test.cpp -o motion && ./motion
//
// A source sweeping across azimuth used to swap its interpolated HRIR pair
// once per 32 ms frame with no transition — every frame boundary was a filter
// discontinuity (audible as zipper noise on fast pans). The renderer now
// dual-convolves against the previous and current IR with a raised-cosine
// crossfade and ramps the ITD per sample, so a boundary step must not stand
// out from the ordinary in-frame sample steps.
//
// Probe: a DC (constant 1.0) source, so the output is the direction's DC gain
// and every output step is pure direction-change artifact. Per-frame filter
// swapping concentrates each frame's gain change into the ~32 samples the ITD
// ring smears it over (measured max step 0.0052); the crossfaded renderer
// spreads the same change across the whole 1536-sample frame (~25x smaller
// steps). The metric is simply the largest sample-to-sample step after warmup.
#include <cmath>
#include <cstdio>
#include <vector>

#include "render/hrir_renderer.h"

int main() {
  using tf::atmos::render::BinauralRenderer;
  constexpr int kFrame = 1536;
  constexpr int kFrames = 30;
  constexpr float kPi = 3.14159265358979f;

  BinauralRenderer r;
  r.configure(48000, 4);
  r.set_params(1.0f, true);

  std::vector<float> src(kFrame, 1.0f), out(2 * kFrame);
  std::vector<float> left;  // concatenated left-ear output
  left.reserve(kFrames * kFrame);

  for (int f = 0; f < kFrames; ++f) {
    // Sweep front (0) to hard right (pi/2) across the run: ~3 deg per frame,
    // enough that consecutive frames land on different interpolation weights.
    const float az = (kPi / 2.0f) * static_cast<float>(f) / (kFrames - 1);
    const float* obj[1] = {src.data()};
    const float el = 0.0f;
    r.render(obj, &az, &el, 1, nullptr, nullptr, nullptr, 0, kFrame, out.data());
    for (int i = 0; i < kFrame; ++i) left.push_back(out[2 * i]);
  }

  // Skip the first two frames (initial fill of convolution/delay history).
  float max_step = 0.0f;
  for (int f = 2; f < kFrames; ++f) {
    for (int i = 0; i < kFrame; ++i) {
      const int t = f * kFrame + i;
      const float step = std::fabs(left[t] - left[t - 1]);
      if (step > max_step) max_step = step;
    }
  }

  std::printf("=== HRIR motion smoothing ===\n");
  std::printf("max output step during sweep: %.6f\n", max_step);
  // Per-frame filter swapping measures 0.0052 here; the crossfade must spread
  // the same direction change across the frame (predicted ~0.0002).
  const bool pass = max_step < 0.001f;
  std::printf("=== %s ===\n", pass ? "PASS" : "FAIL");
  return pass ? 0 : 1;
}
