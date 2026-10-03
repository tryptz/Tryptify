// Places the channels of a multichannel bed around the listener and folds them
// to stereo: the audio side of the mixer's spatial map.
//
// Each channel carries a direction (azimuth, elevation) and a gain the user
// set by dragging it on the map. Two ways to fold:
//
//  - Binaural: every channel but the LFE is convolved with the measured HRIR
//    pair for its direction (BinauralRenderer, the same renderer the Atmos
//    bed-HRTF mode uses), so on headphones it is heard where it was put —
//    behind, beside, above. The LFE is non-directional bass and goes to both
//    ears equally, as the Atmos path does.
//
//    Then diffuse-field equalized (hrir_dfe.h). The raw KEMAR responses carry
//    what every direction shares — the measuring speaker's bass roll-off and
//    the dummy ear canal's resonance, -15 dB at 100 Hz against +10 dB at
//    2.5 kHz — and heard raw that is thin and honky ("tinny"). The equalizer
//    divides that shared part out, leaving the differences between directions,
//    which are what place a sound. It runs on the rendered output, so the bass
//    below the crossover is split off here first and goes round both the
//    HRIRs and the equalizer, undirected, as bass has no direction.
//
//    Then equalized to a headphone target (setTarget: one of AutoEQ's curves,
//    Diffuse Field, Harman and the rest, levelled at 1 kHz): the HRIRs'
//    average divided out and the target multiplied in, so the render averaged
//    over every direction is that target. One minimum-phase FIR, designed on
//    the audio thread into buffers allocated by configure(), only when the
//    target changes; with no target set it is flat.
//  - Pan: an equal-power stereo pan from the direction's left/right component
//    (sin of the azimuth). For speakers, and for rates the 48 kHz HRIR table
//    does not suit. Front and back fold to the same side; stereo cannot say
//    more than that.
//
// Threading: setPlacement/setMode come from the UI thread, process from the
// audio thread. The placement travels through a seqlock over atomics — the
// audio thread takes a consistent copy at the start of a block or keeps the
// last one, and never waits. Gains ramp across a block and the renderer
// crossfades direction changes, so a dot dragged across the map glides.
//
// Header-only; no allocation after configure(); -fno-exceptions safe.
#ifndef TF_ATMOS_RENDER_CHANNEL_PLACER_H
#define TF_ATMOS_RENDER_CHANNEL_PLACER_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstring>
#include <vector>

#include "hrir_dfe.h"
#include "hrir_renderer.h"

namespace tf {
namespace atmos {
namespace render {

class ChannelPlacer {
 public:
  static constexpr int kMaxChannels = 16;
  // The renderer and the scratch run in pieces of this many frames.
  static constexpr int kChunk = 512;
  // A target is this many dB values on a log grid from 20 Hz to 20 kHz.
  static constexpr int kTargetPoints = 64;
  static double targetFreq(int i) { return 20.0 * std::pow(1000.0, i / double(kTargetPoints - 1)); }

  // Allocates everything. [lfe_index] is the LFE channel, or -1 for none.
  void configure(int sample_rate, int channels, int lfe_index) {
    channels_ = channels < 1 ? 1 : (channels > kMaxChannels ? kMaxChannels : channels);
    lfe_ = (lfe_index >= 0 && lfe_index < channels_) ? lfe_index : -1;
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    renderer_.configure(sample_rate, kMaxChannels);
    for (int ear = 0; ear < 2; ++ear) dfe_[ear].assign(static_cast<size_t>(kDfeTaps - 1 + kChunk), 0.0f);
    // The equalizer starts as the plain diffuse-field one; a target is folded
    // in by design_eq(). Its scratch, and the diffuse-field EQ's own log
    // magnitude, are made here once.
    for (int b = 0; b < 2; ++b) eq_[b].assign(kDfe, kDfe + kDfeTaps);
    active_eq_ = 0;
    fft_.assign(kDesignN, std::complex<double>(0.0, 0.0));
    dfe_log_.assign(kDesignN / 2 + 1, 0.0);
    for (int t = 0; t < kDfeTaps; ++t) fft_[t] = kDfe[t];
    fft(fft_, false);
    for (int k = 0; k <= kDesignN / 2; ++k) dfe_log_[k] = std::log(std::max(1e-9, std::abs(fft_[k])));
    applied_target_seq_ = ~0u;
    low_.assign(static_cast<size_t>(kChunk), 0.0f);
    for (auto& s : split_) s = Split{};
    scratch_.assign(static_cast<size_t>(kMaxChannels) * kChunk, 0.0f);
    rendered_.assign(static_cast<size_t>(2) * kChunk, 0.0f);
    for (int c = 0; c < kMaxChannels; ++c) {
      bufs_[c] = &scratch_[static_cast<size_t>(c) * kChunk];
      ptrs_[c] = bufs_[c];
      cur_.az[c] = 0.0f;
      cur_.el[c] = 0.0f;
      cur_.gain[c] = 1.0f;
      applied_gain_[c] = -1.0f;  // no ramp from nothing on the first block
    }
    // The renderer sizes its input window on first use; do that here rather
    // than on the audio thread.
    renderer_.render(nullptr, nullptr, nullptr, 0, ptrs_, cur_.az, cur_.el, 0, kChunk,
                     rendered_.data());
    last_seq_ = ~0u;
    if (seq_.load(std::memory_order_acquire) == 0) {
      // Nothing placed yet: every channel ahead at unity rather than silent.
      for (int c = 0; c < kMaxChannels; ++c) in_gain_[c].store(1.0f, std::memory_order_relaxed);
    }
    apply_mode();
  }

  // UI thread. Radians; azimuth 0 ahead, negative to the left; gain linear.
  void setPlacement(const float* az, const float* el, const float* gain, int count) {
    if (count > kMaxChannels) count = kMaxChannels;
    const unsigned s = seq_.load(std::memory_order_relaxed);
    seq_.store(s + 1, std::memory_order_release);  // odd: being written
    std::atomic_thread_fence(std::memory_order_release);
    for (int c = 0; c < count; ++c) {
      in_az_[c].store(finite_or(az[c], 0.0f), std::memory_order_relaxed);
      in_el_[c].store(finite_or(el[c], 0.0f), std::memory_order_relaxed);
      const float g = finite_or(gain[c], 1.0f);
      in_gain_[c].store(g < 0.0f ? 0.0f : (g > 8.0f ? 8.0f : g), std::memory_order_relaxed);
    }
    for (int c = count; c < kMaxChannels; ++c) {
      in_az_[c].store(0.0f, std::memory_order_relaxed);
      in_el_[c].store(0.0f, std::memory_order_relaxed);
      in_gain_[c].store(1.0f, std::memory_order_relaxed);
    }
    seq_.store(s + 2, std::memory_order_release);   // even: consistent
  }

  // Any thread. [db] holds kTargetPoints dB values at targetFreq(i): the
  // headphone target the render is equalized to. All zero is flat.
  void setTarget(const float* db, int count) {
    if (count > kTargetPoints) count = kTargetPoints;
    const unsigned s = target_seq_.load(std::memory_order_relaxed);
    target_seq_.store(s + 1, std::memory_order_release);
    std::atomic_thread_fence(std::memory_order_release);
    for (int i = 0; i < kTargetPoints; ++i) {
      const float v = i < count ? finite_or(db[i], 0.0f) : 0.0f;
      in_target_[i].store(v < -24.0f ? -24.0f : (v > 24.0f ? 24.0f : v), std::memory_order_relaxed);
    }
    target_seq_.store(s + 2, std::memory_order_release);
  }

  // UI thread. [binaural] false folds by pan.
  void setMode(bool binaural, float strength, bool height, bool bass_management, int crossover_hz) {
    binaural_.store(binaural, std::memory_order_relaxed);
    strength_.store(strength, std::memory_order_relaxed);
    height_.store(height, std::memory_order_relaxed);
    bass_.store(bass_management, std::memory_order_relaxed);
    crossover_.store(crossover_hz, std::memory_order_relaxed);
    mode_dirty_.store(true, std::memory_order_release);
  }

  // Audio thread: clears the convolution history (seek, flush).
  void reset() {
    renderer_.reset_history();
    for (int ear = 0; ear < 2; ++ear) std::fill(dfe_[ear].begin(), dfe_[ear].end(), 0.0f);
    for (auto& s : split_) s = Split{};
    for (int c = 0; c < kMaxChannels; ++c) applied_gain_[c] = -1.0f;
  }

  int channels() const { return channels_; }

  // Audio thread. [in][c] holds channel c's [n] samples; [out] gets 2*n
  // interleaved stereo floats.
  void process(const float* const* in, int n, float* out) {
    if (mode_dirty_.exchange(false, std::memory_order_acquire)) apply_mode();
    take_placement();
    take_target();
    const bool binaural = binaural_.load(std::memory_order_relaxed);
    for (int done = 0; done < n; done += kChunk) {
      const int m = (n - done) < kChunk ? (n - done) : kChunk;
      if (binaural) {
        processBinaural(in, done, m, out + 2 * done);
      } else {
        processPan(in, done, m, out + 2 * done);
      }
      // Ramps finish inside the first piece; later ones hold the target.
      for (int c = 0; c < channels_; ++c) applied_gain_[c] = cur_.gain[c];
    }
  }

 private:
  struct Placement {
    float az[kMaxChannels];
    float el[kMaxChannels];
    float gain[kMaxChannels];
  };

  static float finite_or(float v, float fallback) {
    // Bit test: this library builds with -ffast-math, under which
    // std::isfinite may fold to true.
    uint32_t b;
    std::memcpy(&b, &v, sizeof b);
    return (b & 0x7F800000u) == 0x7F800000u ? fallback : v;
  }

  void take_placement() {
    const unsigned s1 = seq_.load(std::memory_order_acquire);
    if (s1 == last_seq_ || (s1 & 1u)) return;  // unchanged, or mid-write: keep the last
    Placement p;
    for (int c = 0; c < kMaxChannels; ++c) {
      p.az[c] = in_az_[c].load(std::memory_order_relaxed);
      p.el[c] = in_el_[c].load(std::memory_order_relaxed);
      p.gain[c] = in_gain_[c].load(std::memory_order_relaxed);
    }
    std::atomic_thread_fence(std::memory_order_acquire);
    if (seq_.load(std::memory_order_relaxed) != s1) return;  // torn: next block
    cur_ = p;
    last_seq_ = s1;
  }

  void apply_mode() {
    renderer_.set_params(strength_.load(std::memory_order_relaxed),
                         height_.load(std::memory_order_relaxed));
    // The bass is split here instead (see processBinaural), so the renderer
    // takes only what is above the crossover.
    renderer_.set_bass_management(false, 80);
    bass_on_ = bass_.load(std::memory_order_relaxed);
    const int xo = crossover_.load(std::memory_order_relaxed);
    const float w0 = 2.0f * kPi * static_cast<float>(xo > 0 ? xo : 80) / static_cast<float>(sample_rate_);
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) * 0.70710678f;  // Q = 1/sqrt(2): two make an LR4
    const float a0 = 1.0f + alpha;
    lp_[0] = (0.5f * (1.0f - cw)) / a0;
    lp_[1] = (1.0f - cw) / a0;
    lp_[2] = lp_[0];
    hp_[0] = (0.5f * (1.0f + cw)) / a0;
    hp_[1] = -(1.0f + cw) / a0;
    hp_[2] = hp_[0];
    lp_[3] = hp_[3] = (-2.0f * cw) / a0;
    lp_[4] = hp_[4] = (1.0f - alpha) / a0;
  }

  static float biquad(const float* c, float* z, float x) {
    const float y = c[0] * x + z[0];
    z[0] = c[1] * x - c[3] * y + z[1];
    z[1] = c[2] * x - c[4] * y;
    return y;
  }

  // A new target, if one was set: the equalizer redesigned once, here on the
  // audio thread, into the buffer not in use, then switched to.
  void take_target() {
    const unsigned s1 = target_seq_.load(std::memory_order_acquire);
    if (s1 == applied_target_seq_ || (s1 & 1u)) return;
    float db[kTargetPoints];
    for (int i = 0; i < kTargetPoints; ++i) db[i] = in_target_[i].load(std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_acquire);
    if (target_seq_.load(std::memory_order_relaxed) != s1) return;
    applied_target_seq_ = s1;
    design_eq(db);
  }

  // The diffuse-field EQ's magnitude times the target's, as one
  // minimum-phase FIR of kDfeTaps (real cepstrum), faded at its end.
  void design_eq(const float* db) {
    const int half = kDesignN / 2;
    const double ln10_20 = std::log(10.0) / 20.0;
    for (int k = 0; k <= half; ++k) {
      // The target at this bin, log-interpolated on its grid, held flat past
      // either end. The table is at 48 kHz, as the HRIRs are.
      const double f = k * 48000.0 / kDesignN;
      double t;
      if (f <= targetFreq(0)) {
        t = db[0];
      } else if (f >= targetFreq(kTargetPoints - 1)) {
        t = db[kTargetPoints - 1];
      } else {
        const double x = std::log(f / 20.0) / std::log(1000.0) * (kTargetPoints - 1);
        const int i = static_cast<int>(x);
        const double w = x - i;
        t = db[i] * (1.0 - w) + db[i + 1] * w;
      }
      fft_[k] = dfe_log_[k] + t * ln10_20;
      if (k > 0 && k < half) fft_[kDesignN - k] = fft_[k];
    }
    fft(fft_, true);  // real cepstrum
    for (int n = 1; n < half; ++n) { fft_[n] *= 2.0; fft_[kDesignN - n] = 0.0; }
    fft(fft_, false);
    for (auto& x : fft_) x = std::exp(x);
    fft(fft_, true);
    std::vector<float>& next = eq_[1 - active_eq_];
    const int fade = kDfeTaps / 8;
    for (int n = 0; n < kDfeTaps; ++n) {
      double w = 1.0;
      if (n >= kDfeTaps - fade) w = 0.5 + 0.5 * std::cos(kPi * (n - (kDfeTaps - fade)) / fade);
      next[static_cast<size_t>(n)] = static_cast<float>(fft_[n].real() * w);
    }
    active_eq_ = 1 - active_eq_;
  }

  // In place, radix 2; [a] is kDesignN long.
  static void fft(std::vector<std::complex<double>>& a, bool inverse) {
    const int n = static_cast<int>(a.size());
    for (int i = 1, j = 0; i < n; ++i) {
      int bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) std::swap(a[i], a[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
      const double ang = 2.0 * 3.14159265358979323846 / len * (inverse ? 1 : -1);
      const std::complex<double> wl(std::cos(ang), std::sin(ang));
      for (int i = 0; i < n; i += len) {
        std::complex<double> w(1.0, 0.0);
        for (int k = 0; k < len / 2; ++k) {
          const std::complex<double> u = a[i + k], v = a[i + k + len / 2] * w;
          a[i + k] = u + v;
          a[i + k + len / 2] = u - v;
          w *= wl;
        }
      }
    }
    if (inverse) for (auto& x : a) x /= static_cast<double>(n);
  }

  // The equalizer over [m] interleaved stereo frames, in place.
  void equalize(float* out, int m) {
    const float* h = eq_[active_eq_].data();
    const int hist = kDfeTaps - 1;
    for (int ear = 0; ear < 2; ++ear) {
      float* x = dfe_[ear].data();
      for (int i = 0; i < m; ++i) x[hist + i] = out[2 * i + ear];
      for (int i = 0; i < m; ++i) {
        const float* w = x + hist + i;  // w[0] newest, w[-k] k samples ago
        float y = 0.0f;
        for (int k = 0; k < kDfeTaps; ++k) y += h[k] * w[-k];
        out[2 * i + ear] = y;
      }
      // Keep the last hist samples for the next piece.
      std::memmove(x, x + m, static_cast<size_t>(hist) * sizeof(float));
    }
  }

  // The gain for sample [i] of an [m]-sample piece: a linear ramp from what
  // the last block used to the target, so a drag does not click.
  float ramp(int c, int i, int m) const {
    const float to = cur_.gain[c];
    const float from = applied_gain_[c] < 0.0f ? to : applied_gain_[c];
    return from + (to - from) * (static_cast<float>(i + 1) / static_cast<float>(m));
  }

  void processBinaural(const float* const* in, int offset, int m, float* out) {
    int k = 0;
    float az[kMaxChannels], el[kMaxChannels];
    float* low = low_.data();
    for (int i = 0; i < m; ++i) low[i] = 0.0f;
    for (int c = 0; c < channels_; ++c) {
      if (c == lfe_) continue;
      float* dst = bufs_[k];
      const float* src = in[c] + offset;
      if (bass_on_) {
        // LR4: both halves filtered, so they sum back allpass-flat. The low
        // half joins every other channel's, undirected.
        Split& sp = split_[c];
        for (int i = 0; i < m; ++i) {
          const float x = src[i] * ramp(c, i, m);
          low[i] += biquad(lp_, sp.z + 2, biquad(lp_, sp.z, x));
          dst[i] = biquad(hp_, sp.z + 6, biquad(hp_, sp.z + 4, x));
        }
      } else {
        for (int i = 0; i < m; ++i) dst[i] = src[i] * ramp(c, i, m);
      }
      az[k] = cur_.az[c];
      el[k] = cur_.el[c];
      ++k;
    }
    renderer_.render(nullptr, nullptr, nullptr, 0, ptrs_, az, el, k, m, out);
    equalize(out, m);
    for (int i = 0; i < m; ++i) {
      out[2 * i] += low[i];
      out[2 * i + 1] += low[i];
    }
    if (lfe_ >= 0) {
      const float* lp = in[lfe_] + offset;
      for (int i = 0; i < m; ++i) {
        const float v = lp[i] * ramp(lfe_, i, m);
        out[2 * i] += v;
        out[2 * i + 1] += v;
      }
    }
  }

  void processPan(const float* const* in, int offset, int m, float* out) {
    for (int i = 0; i < 2 * m; ++i) out[i] = 0.0f;
    for (int c = 0; c < channels_; ++c) {
      float gl = 0.70710678f, gr = 0.70710678f;  // the LFE: both sides
      if (c != lfe_) {
        // Equal-power: centre -3 dB each side, hard left all left.
        float x = std::sin(cur_.az[c]);
        x = x < -1.0f ? -1.0f : (x > 1.0f ? 1.0f : x);
        const float theta = (x + 1.0f) * 0.25f * kPi;
        gl = std::cos(theta);
        gr = std::sin(theta);
      } else {
        gl = gr = 1.0f;
      }
      const float* src = in[c] + offset;
      for (int i = 0; i < m; ++i) {
        const float v = src[i] * ramp(c, i, m);
        out[2 * i] += v * gl;
        out[2 * i + 1] += v * gr;
      }
    }
  }

  BinauralRenderer renderer_;
  int sample_rate_ = 48000;
  // The diffuse-field equalizer's input history and this piece, per ear.
  std::vector<float> dfe_[2];
  // The bass split: per channel, two LR4 stages each way.
  struct Split { float z[8] = {}; };
  Split split_[kMaxChannels];
  std::vector<float> low_;
  float lp_[5] = {}, hp_[5] = {};
  bool bass_on_ = true;
  // The equalizer in use and the one a new target is designed into.
  static constexpr int kDesignN = 4096;
  std::vector<float> eq_[2];
  int active_eq_ = 0;
  std::vector<std::complex<double>> fft_;
  std::vector<double> dfe_log_;
  std::atomic<unsigned> target_seq_{0};
  std::atomic<float> in_target_[kTargetPoints] = {};
  unsigned applied_target_seq_ = ~0u;
  int channels_ = 2;
  int lfe_ = -1;
  std::vector<float> scratch_, rendered_;
  float* bufs_[kMaxChannels] = {};
  const float* ptrs_[kMaxChannels] = {};

  // Written by the UI thread.
  std::atomic<unsigned> seq_{0};
  std::atomic<float> in_az_[kMaxChannels] = {};
  std::atomic<float> in_el_[kMaxChannels] = {};
  std::atomic<float> in_gain_[kMaxChannels] = {};
  std::atomic<bool> binaural_{true};
  std::atomic<float> strength_{1.0f};
  std::atomic<bool> height_{true};
  std::atomic<bool> bass_{true};
  std::atomic<int> crossover_{80};
  std::atomic<bool> mode_dirty_{true};

  // Audio thread only.
  Placement cur_{};
  float applied_gain_[kMaxChannels] = {};
  unsigned last_seq_ = ~0u;
};

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_CHANNEL_PLACER_H
