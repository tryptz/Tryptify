// Host test: the binaural renderer's overlap-save convolution against the
// direct-form convolution it replaced.
//
//   c++ -std=c++17 -I.. hrtf_fft_test.cpp -o hrtf_fft && ./hrtf_fft
//
// DirectRenderer below is the renderer as it was before the FFT: the same
// HRIR lookup, ITD delay, crossfade and bass split, with the convolution done
// a tap at a time. The two are fed the same sources — static and moving, at
// ragged call sizes from 1 to 4096 samples, so short last blocks and
// single-sample calls are covered — and must agree to float rounding. An
// impulse must come out at the same sample in both: the FFT adds no latency.
//
// The bass split stays off here. It is untouched by the FFT, and its 100 Hz
// crossover turns the last-bit differences in how a build fuses its
// multiply-adds into ~4e-4 of peak (seen on arm64, where the two copies of
// the same biquad compile differently); hrtf_polish_test holds the split.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "render/hrir_renderer.h"

namespace {

using namespace tf::atmos::render;

// The pre-FFT renderer, baked table only, objects only.
class DirectRenderer {
 public:
  void configure(int sources) { src_.assign(sources, Source{}); }
  void set_params(float strength, bool height) { strength_ = strength; height_ = height; }
  void set_bass_management(bool on, int hz) {
    bass_ = on;
    const float w0 = 2.0f * kPi * static_cast<float>(hz) / 48000.0f;
    const float cw = std::cos(w0), alpha = std::sin(w0) * 0.70710678f, a0 = 1.0f + alpha;
    lp_[0] = (0.5f * (1.0f - cw)) / a0;
    lp_[1] = (1.0f - cw) / a0;
    lp_[2] = lp_[0];
    hp_[0] = (0.5f * (1.0f + cw)) / a0;
    hp_[1] = -(1.0f + cw) / a0;
    hp_[2] = hp_[0];
    lp_[3] = hp_[3] = (-2.0f * cw) / a0;
    lp_[4] = hp_[4] = (1.0f - alpha) / a0;
  }
  void render(const float* const* objs, const float* az, const float* el, int count, int n, float* out) {
    for (int i = 0; i < 2 * n; ++i) out[i] = 0.0f;
    if (static_cast<int>(ext_.size()) < kHrirTaps - 1 + n) ext_.assign(kHrirTaps - 1 + n, 0.0f);
    for (int o = 0; o < count; ++o) mix(src_[o], objs[o], az[o], el[o], n, out);
  }

 private:
  static constexpr int kRing = 64;
  struct Source {
    float tail[kHrirTaps] = {};
    float dl[kRing] = {}, dr[kRing] = {}, din[kRing] = {};
    int dwrite = 0;
    float bm[8] = {};
    float phl[kHrirTaps] = {}, phr[kHrirTaps] = {};
    float pdl = 0, pdr = 0, paz = 0, pel = 0;
    bool has_prev = false;
  };
  static float biquad(const float* c, float* z, float x) {
    const float y = c[0] * x + z[0];
    z[0] = c[1] * x - c[3] * y + z[1];
    z[1] = c[2] * x - c[4] * y;
    return y;
  }
  static float read_delayed(const float* ring, int write, float delay) {
    if (delay < 0.0f) delay = 0.0f;
    if (delay > kRing - 2) delay = kRing - 2;
    const int d = static_cast<int>(delay);
    const float frac = delay - static_cast<float>(d);
    const int i0 = (write - 1 - d + kRing) % kRing;
    const int i1 = (i0 - 1 + kRing) % kRing;
    return ring[i0] * (1.0f - frac) + ring[i1] * frac;
  }
  void lookup(float azimuth, float elevation) {
    float az_deg = std::fmod(azimuth * (180.0f / kPi), 360.0f);
    if (az_deg < 0.0f) az_deg += 360.0f;
    const float af = az_deg / kHrirAzStep;
    const int a0 = static_cast<int>(af) % kHrirAzCount;
    const float fa = af - std::floor(af);
    const int a1 = (a0 + 1) % kHrirAzCount;
    float el_deg = elevation * (180.0f / kPi);
    const float el_max = kHrirElMin + (kHrirElCount - 1) * kHrirElStep;
    if (el_deg < kHrirElMin) el_deg = kHrirElMin;
    if (el_deg > el_max) el_deg = el_max;
    const float ef = (el_deg - kHrirElMin) / kHrirElStep;
    int e0 = static_cast<int>(ef);
    if (e0 > kHrirElCount - 1) e0 = kHrirElCount - 1;
    const int e1 = e0 + 1 < kHrirElCount ? e0 + 1 : e0;
    const float fe = ef - static_cast<float>(e0);
    const float w00 = (1 - fa) * (1 - fe), w10 = fa * (1 - fe), w01 = (1 - fa) * fe, w11 = fa * fe;
    for (int k = 0; k < kHrirTaps; ++k) {
      hl_[k] = w00 * kHrirData[e0][a0][0][k] + w10 * kHrirData[e0][a1][0][k] +
               w01 * kHrirData[e1][a0][0][k] + w11 * kHrirData[e1][a1][0][k];
      hr_[k] = w00 * kHrirData[e0][a0][1][k] + w10 * kHrirData[e0][a1][1][k] +
               w01 * kHrirData[e1][a0][1][k] + w11 * kHrirData[e1][a1][1][k];
    }
    dl_ = w00 * kHrirDelay[e0][a0][0] + w10 * kHrirDelay[e0][a1][0] + w01 * kHrirDelay[e1][a0][0] +
          w11 * kHrirDelay[e1][a1][0];
    dr_ = w00 * kHrirDelay[e0][a0][1] + w10 * kHrirDelay[e0][a1][1] + w01 * kHrirDelay[e1][a0][1] +
          w11 * kHrirDelay[e1][a1][1];
  }
  void mix(Source& s, const float* in, float az, float el, int n, float* out) {
    const float el_eff = height_ ? el : 0.0f;
    lookup(az, el_eff);
    const int T = kHrirTaps, hist = T - 1;
    const float wet = strength_, dry = 1.0f - strength_;
    const bool changed = s.has_prev && (std::fabs(az - s.paz) > 1e-4f || std::fabs(el_eff - s.pel) > 1e-4f);
    if (!s.has_prev) {
      for (int k = 0; k < T; ++k) { s.phl[k] = hl_[k]; s.phr[k] = hr_[k]; }
      s.pdl = dl_;
      s.pdr = dr_;
    }
    const float pdl0 = s.pdl, pdr0 = s.pdr;
    for (int i = 0; i < hist; ++i) ext_[i] = s.tail[i];
    for (int i = 0; i < n; ++i) {
      float src = in[i], low = 0.0f, hp = src;
      if (bass_) {
        low = biquad(lp_, s.bm + 2, biquad(lp_, s.bm + 0, src));
        hp = biquad(hp_, s.bm + 6, biquad(hp_, s.bm + 4, src));
      }
      ext_[hist + i] = hp;
      const float* m = &ext_[i];
      double yl0 = 0, yr0 = 0, yl1 = 0, yr1 = 0;  // the reference sums in double
      for (int k = 0; k < T; ++k) {
        const double x = m[hist - k];
        yl0 += s.phl[k] * x;
        yr0 += s.phr[k] * x;
        yl1 += hl_[k] * x;
        yr1 += hr_[k] * x;
      }
      float yl, yr, edl, edr;
      if (changed) {
        const float ct = (i + 0.5f) / static_cast<float>(n);
        const float w = 0.5f - 0.5f * std::cos(kPi * ct);
        yl = static_cast<float>(yl0 + w * (yl1 - yl0));
        yr = static_cast<float>(yr0 + w * (yr1 - yr0));
        edl = pdl0 + (dl_ - pdl0) * ct;
        edr = pdr0 + (dr_ - pdr0) * ct;
      } else {
        yl = static_cast<float>(yl1);
        yr = static_cast<float>(yr1);
        edl = dl_;
        edr = dr_;
      }
      s.dl[s.dwrite] = yl;
      s.dr[s.dwrite] = yr;
      s.din[s.dwrite] = hp;
      const float dyl = read_delayed(s.dl, s.dwrite + 1, edl);
      const float dyr = read_delayed(s.dr, s.dwrite + 1, edr);
      const float dryl = read_delayed(s.din, s.dwrite + 1, edl);
      const float dryr = read_delayed(s.din, s.dwrite + 1, edr);
      s.dwrite = (s.dwrite + 1) % kRing;
      out[2 * i] += wet * dyl + dry * dryl + low;
      out[2 * i + 1] += wet * dyr + dry * dryr + low;
    }
    for (int i = 0; i < hist; ++i) s.tail[i] = ext_[n + i];
    for (int k = 0; k < T; ++k) { s.phl[k] = hl_[k]; s.phr[k] = hr_[k]; }
    s.pdl = dl_;
    s.pdr = dr_;
    s.paz = az;
    s.pel = el_eff;
    s.has_prev = true;
  }

  std::vector<Source> src_;
  std::vector<float> ext_;
  float hl_[kHrirTaps], hr_[kHrirTaps], dl_ = 0, dr_ = 0;
  float strength_ = 1.0f;
  bool height_ = true, bass_ = false;
  float lp_[5] = {}, hp_[5] = {};
};

int failures = 0;
void check(bool ok, const char* what) {
  std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
  if (!ok) ++failures;
}

// Max |difference| over the peak, for one configuration.
double compare(float strength, bool height, bool bass) {
  const int sources = 12;
  BinauralRenderer fft;
  fft.configure(48000, sources);
  fft.set_params(strength, height);
  fft.set_bass_management(bass, 100);
  DirectRenderer direct;
  direct.configure(sources);
  direct.set_params(strength, height);
  direct.set_bass_management(bass, 100);

  std::vector<std::vector<float>> sig(sources, std::vector<float>(4096));
  std::vector<const float*> ptr(sources);
  std::vector<float> az(sources), el(sources), a(2 * 4096), b(2 * 4096);
  for (int o = 0; o < sources; ++o) { az[o] = -3.0f + 0.5f * o; el[o] = -0.7f + 0.13f * o; }
  const int sizes[] = {1536, 1536, 1000, 77, 512, 1, 128, 4096, 300, 129, 1536};
  uint32_t seed = 3;
  double max = 0.0, peak = 0.0;
  long t = 0;
  for (int call = 0; call < 44; ++call) {
    const int n = sizes[call % 11];
    for (int o = 0; o < sources; ++o) {
      for (int i = 0; i < n; ++i) {
        seed = seed * 1664525u + 1013904223u;
        const float noise = static_cast<float>((seed >> 8) & 0xFFFF) / 32768.0f - 1.0f;
        sig[o][i] = 0.3f * std::sin((t + i) * 0.002f * (o + 1)) + 0.1f * noise;
      }
      ptr[o] = sig[o].data();
      // A third of the sources sweep for a few calls at a time, one creeps
      // under the crossfade's threshold.
      if ((call / 4) % 2 == 1 && o % 3 == 1) az[o] += 0.07f;
      if (o == 0) az[o] += 5e-5f;
    }
    fft.render(ptr.data(), az.data(), el.data(), sources, nullptr, nullptr, nullptr, 0, n, a.data());
    direct.render(ptr.data(), az.data(), el.data(), sources, n, b.data());
    for (int i = 0; i < 2 * n; ++i) {
      max = std::fmax(max, std::fabs(static_cast<double>(a[i]) - b[i]));
      peak = std::fmax(peak, std::fabs(static_cast<double>(b[i])));
    }
    t += n;
  }
  return peak > 0.0 ? max / peak : 1.0;
}

// The sample an impulse's largest output lands on, per renderer.
void impulse_peaks(int& fft_at, int& direct_at) {
  const int n = 1536;
  std::vector<float> imp(n, 0.0f), a(2 * n), b(2 * n);
  imp[300] = 1.0f;
  const float* p[1] = {imp.data()};
  float az = 1.2f, el = 0.3f;
  BinauralRenderer fft;
  fft.configure(48000, 1);
  fft.render(p, &az, &el, 1, nullptr, nullptr, nullptr, 0, n, a.data());
  DirectRenderer direct;
  direct.configure(1);
  direct.render(p, &az, &el, 1, n, b.data());
  fft_at = direct_at = 0;
  for (int i = 1; i < 2 * n; ++i) {
    if (std::fabs(a[i]) > std::fabs(a[fft_at])) fft_at = i;
    if (std::fabs(b[i]) > std::fabs(b[direct_at])) direct_at = i;
  }
}

}  // namespace

int main() {
  // Float rounding: measured 2.6e-7 to 5e-7 of peak (the FFT's own error
  // against the direct form summed in double).
  const double plain = compare(1.0f, true, false);
  const double blended = compare(0.7f, true, false);
  const double flat = compare(1.0f, false, false);
  std::printf("overlap-save vs direct form, max diff / peak: %.3g, %.3g (strength 0.7), %.3g (no height)\n",
              plain, blended, flat);
  check(plain < 2e-6, "full strength matches the direct form to float rounding");
  check(blended < 2e-6, "blended strength matches too");
  check(flat < 2e-6, "height off matches too");
  int fft_at, direct_at;
  impulse_peaks(fft_at, direct_at);
  std::printf("impulse peak at interleaved sample %d (direct form %d)\n", fft_at, direct_at);
  check(fft_at == direct_at, "no latency added: an impulse lands where it did");
  std::printf(failures == 0 ? "=== PASS ===\n" : "=== FAIL ===\n");
  return failures == 0 ? 0 : 1;
}
