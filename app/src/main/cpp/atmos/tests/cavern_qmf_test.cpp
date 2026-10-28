// Host test for the ported Cavern QMF filterbank.
//
//   c++ -std=c++17 -I.. cavern_qmf_test.cpp -o qmf && ./qmf
//
// A complex QMF analysis followed by the matched synthesis must reconstruct the
// input signal (up to a fixed delay and gain). We drive a broadband multi-sine
// through forward→inverse per 64-sample timeslot and check the output is a
// delayed, scaled copy — normalized cross-correlation ≈ 1 at the best lag.
//
// The modulation runs through an FFT; Cavern computes it with dense 64 x 128
// rotation matrices. Both directions are also held, timeslot by timeslot, to
// that dense transform: in double precision with exact phases (the FFT must
// match it to float rounding), and in float with Cavern's own tables, whose
// phases were rounded in float (the FFT replaced those; this bounds what the
// change did to the output).
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "cavern/quadrature_mirror_filterbank.h"

namespace {

using tf::atmos::cavern::QuadratureMirrorFilterBank;
constexpr int kSub = QuadratureMirrorFilterBank::kSubbands;     // 64
constexpr int kLen = QuadratureMirrorFilterBank::kCoeffLen;      // 640

// Cavern's QMF as it was ported first: memmoved delay lines and the dense
// modulation, in T. In float the rotation tables are built the way Cavern
// builds them (the phase in float); in double, with exact phases.
template <typename T>
struct DenseQmf {
  T fwd_re[kSub][2 * kSub], fwd_im[kSub][2 * kSub];
  T inv_re[kSub][2 * kSub], inv_im[kSub][2 * kSub];
  T stream_f[kLen] = {}, stream_i[2 * kLen] = {};
  T out_re[kSub], out_im[kSub];
  DenseQmf() {
    for (int sb = 0; sb < kSub; ++sb) {
      for (int j = 0; j < 2 * kSub; ++j) {
        if (sizeof(T) == sizeof(float)) {
          const float pi = 3.14159265358979323846f, div = 1.0f / kSub;
          float e = pi * (sb + 0.5f) * (j - 0.5f) * div;
          fwd_re[sb][j] = std::cos(e);
          fwd_im[sb][j] = std::sin(e);
          e = pi * (sb + 0.5f) * (j - 2 * kSub + 0.5f) * div;
          inv_re[sb][j] = std::cos(e) * div;
          inv_im[sb][j] = std::sin(e) * div;
        } else {
          const double pi = 3.14159265358979323846;
          double e = pi * (sb + 0.5) * (j - 0.5) / kSub;
          fwd_re[sb][j] = static_cast<T>(std::cos(e));
          fwd_im[sb][j] = static_cast<T>(std::sin(e));
          e = pi * (sb + 0.5) * (j - 2 * kSub + 0.5) / kSub;
          inv_re[sb][j] = static_cast<T>(std::cos(e) / kSub);
          inv_im[sb][j] = static_cast<T>(std::sin(e) / kSub);
        }
      }
    }
  }
  void forward(const float* in) {
    std::memmove(stream_f + kSub, stream_f, (kLen - kSub) * sizeof(T));
    for (int i = 0; i < kSub; ++i) stream_f[i] = in[kSub - 1 - i];
    T g[2 * kSub];
    for (int j = 0; j < 2 * kSub; ++j) {
      g[j] = stream_f[j] * T(QuadratureMirrorFilterBank::kCoeffs[j]);
      for (int s = 2 * kSub; s < kLen; s += 2 * kSub) {
        g[j] += stream_f[s + j] * T(QuadratureMirrorFilterBank::kCoeffs[s + j]);
      }
    }
    for (int sb = 0; sb < kSub; ++sb) {
      T r = 0, q = 0;
      for (int j = 0; j < 2 * kSub; ++j) { r += fwd_re[sb][j] * g[j]; q += fwd_im[sb][j] * g[j]; }
      out_re[sb] = r;
      out_im[sb] = q;
    }
  }
  void inverse(const float* re, const float* im, T* out) {
    std::memmove(stream_i + 2 * kSub, stream_i, (2 * kLen - 2 * kSub) * sizeof(T));
    for (int j = 0; j < 2 * kSub; ++j) {
      T v = 0;
      for (int sb = 0; sb < kSub; ++sb) v += inv_re[sb][j] * T(re[sb]) - inv_im[sb][j] * T(im[sb]);
      stream_i[j] = v;
    }
    const float* c = QuadratureMirrorFilterBank::kCoeffs;
    for (int t = 0; t < kSub; ++t) {
      T o = 0;
      for (int j = 0; j < kLen / (2 * kSub); ++j) {
        o += stream_i[4 * kSub * j + t] * T(c[2 * kSub * j + t]) +
             stream_i[4 * kSub * j + 3 * kSub + t] * T(c[2 * kSub * j + kSub + t]);
      }
      out[t] = o;
    }
  }
};

struct Diff {
  double max = 0.0, peak = 0.0;
  void add(double ref, double got) {
    max = std::fmax(max, std::fabs(ref - got));
    peak = std::fmax(peak, std::fabs(ref));
  }
  double rel() const { return peak > 0.0 ? max / peak : 0.0; }
};

// Feeds random timeslots to the filterbank and the two dense references;
// returns false if any direction strays past its bound.
bool against_dense() {
  QuadratureMirrorFilterBank qmf_f, qmf_i;
  static DenseQmf<double> exact_f, exact_i;
  static DenseQmf<float> cavern_f, cavern_i;
  Diff fe, fc, ie, ic;  // forward/inverse against exact/Cavern
  uint32_t seed = 1;
  auto rnd = [&seed]() {
    seed = seed * 1664525u + 1013904223u;
    return static_cast<float>((seed >> 8) & 0xFFFF) / 32768.0f - 1.0f;
  };
  float in[kSub], re[kSub], im[kSub], out[kSub];
  double out_e[kSub];
  float out_c[kSub];
  for (int ts = 0; ts < 1500; ++ts) {
    for (int i = 0; i < kSub; ++i) in[i] = 0.5f * rnd();
    qmf_f.process_forward(in);
    exact_f.forward(in);
    cavern_f.forward(in);
    for (int sb = 0; sb < kSub; ++sb) {
      fe.add(exact_f.out_re[sb], qmf_f.out_real()[sb]);
      fe.add(exact_f.out_im[sb], qmf_f.out_imaginary()[sb]);
      fc.add(cavern_f.out_re[sb], qmf_f.out_real()[sb]);
      fc.add(cavern_f.out_im[sb], qmf_f.out_imaginary()[sb]);
    }
    for (int sb = 0; sb < kSub; ++sb) { re[sb] = rnd(); im[sb] = rnd(); }
    qmf_i.process_inverse(re, im, out);
    exact_i.inverse(re, im, out_e);
    cavern_i.inverse(re, im, out_c);
    for (int t = 0; t < kSub; ++t) {
      ie.add(out_e[t], out[t]);
      ic.add(out_c[t], out[t]);
    }
  }
  std::printf("forward vs exact dense: max %.3g of peak; vs Cavern's float tables: %.3g\n", fe.rel(), fc.rel());
  std::printf("inverse vs exact dense: max %.3g of peak; vs Cavern's float tables: %.3g\n", ie.rel(), ic.rel());
  // Float rounding is ~2e-7 of peak here (measured). Cavern's tables are
  // off by up to 2.4e-5 rad in phase, which is what the FFT's output differs
  // from them by (measured 1.2e-5 forward, 6e-6 inverse; 1.7e-5 forward in
  // the full upmix bench).
  return fe.rel() < 1e-6 && ie.rel() < 1e-6 && fc.rel() < 5e-5 && ic.rel() < 5e-5;
}

}  // namespace

int main() {

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

  // The lag is pinned: AtmosPipeline::kQmfLatency delays the fallback downmix
  // by exactly this measured round-trip so both output paths stay aligned. If
  // the filterbank ever changes, this failing says "update kQmfLatency too".
  const bool dense = against_dense();
  const bool pass = allFinite && std::fabs(bestCorr) > 0.99f && bestLag == 577 && dense;
  std::printf("=== %s ===\n", pass ? "PASS" : "FAIL");
  return pass ? 0 : 1;
}
