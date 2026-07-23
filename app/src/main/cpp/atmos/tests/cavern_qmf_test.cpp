// Host test for the ported Cavern QMF filterbank.
//
//   c++ -std=c++17 -I.. cavern_qmf_test.cpp -o qmf && ./qmf
//
// A complex QMF analysis followed by the matched synthesis must reconstruct the
// input signal (up to a fixed delay and gain). We drive a broadband multi-sine
// through forward→inverse per 64-sample timeslot and check the output is a
// delayed, scaled copy — normalized cross-correlation ≈ 1 at the best lag.
#include <cmath>
#include <cstdio>
#include <vector>

#include "cavern/quadrature_mirror_filterbank.h"

int main() {
  using tf::atmos::cavern::QuadratureMirrorFilterBank;
  constexpr int kSub = QuadratureMirrorFilterBank::kSubbands;  // 64

  const int frames = 900;
  const int n = frames * kSub;

  // Broadband, non-trivial input (three sinusoids below Nyquist).
  std::vector<float> in(n), out(n, 0.0f);
  for (int i = 0; i < n; ++i) {
    in[i] = 0.30f * std::sin(2.0f * 3.14159265f * 0.050f * i) +
            0.20f * std::sin(2.0f * 3.14159265f * 0.130f * i + 1.0f) +
            0.15f * std::sin(2.0f * 3.14159265f * 0.310f * i + 2.0f);
  }

  QuadratureMirrorFilterBank qmf;
  std::vector<float> frameOut(kSub);
  bool allFinite = true;
  for (int f = 0; f < frames; ++f) {
    qmf.process_forward(&in[f * kSub]);
    qmf.process_inverse(qmf.out_real(), qmf.out_imaginary(), frameOut.data());
    for (int i = 0; i < kSub; ++i) {
      const float v = frameOut[i];
      if (!std::isfinite(v)) allFinite = false;
      out[f * kSub + i] = v;
    }
  }

  // Best-lag normalized cross-correlation over the steady-state region.
  const int lo = 3000, hi = n - 3000;
  float bestCorr = 0.0f;
  int bestLag = 0;
  for (int lag = 0; lag <= 1400; ++lag) {
    double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
    int count = 0;
    for (int i = lo; i < hi; ++i) {
      const float x = in[i];
      const float y = out[i + lag];
      sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; ++count;
    }
    const double cov = sxy - sx * sy / count;
    const double vx = sxx - sx * sx / count;
    const double vy = syy - sy * sy / count;
    if (vx > 1e-9 && vy > 1e-9) {
      const float corr = static_cast<float>(cov / std::sqrt(vx * vy));
      if (std::fabs(corr) > std::fabs(bestCorr)) { bestCorr = corr; bestLag = lag; }
    }
  }

  std::printf("=== Cavern QMF reconstruction ===\n");
  std::printf("finite output: %s\n", allFinite ? "yes" : "NO");
  std::printf("best lag: %d samples, correlation: %.5f\n", bestLag, bestCorr);

  const bool pass = allFinite && std::fabs(bestCorr) > 0.99f;
  std::printf("=== %s ===\n", pass ? "PASS" : "FAIL");
  return pass ? 0 : 1;
}
