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

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include "hrir_renderer.h"

namespace tf {
namespace atmos {
namespace render {

class ChannelPlacer {
 public:
  static constexpr int kMaxChannels = 16;
  // The renderer and the scratch run in pieces of this many frames.
  static constexpr int kChunk = 512;

  // Allocates everything. [lfe_index] is the LFE channel, or -1 for none.
  void configure(int sample_rate, int channels, int lfe_index) {
    channels_ = channels < 1 ? 1 : (channels > kMaxChannels ? kMaxChannels : channels);
    lfe_ = (lfe_index >= 0 && lfe_index < channels_) ? lfe_index : -1;
    renderer_.configure(sample_rate, kMaxChannels);
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
    for (int c = 0; c < kMaxChannels; ++c) applied_gain_[c] = -1.0f;
  }

  int channels() const { return channels_; }

  // Audio thread. [in][c] holds channel c's [n] samples; [out] gets 2*n
  // interleaved stereo floats.
  void process(const float* const* in, int n, float* out) {
    if (mode_dirty_.exchange(false, std::memory_order_acquire)) apply_mode();
    take_placement();
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
    renderer_.set_bass_management(bass_.load(std::memory_order_relaxed),
                                  crossover_.load(std::memory_order_relaxed));
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
    for (int c = 0; c < channels_; ++c) {
      if (c == lfe_) continue;
      float* dst = bufs_[k];
      const float* src = in[c] + offset;
      for (int i = 0; i < m; ++i) dst[i] = src[i] * ramp(c, i, m);
      az[k] = cur_.az[c];
      el[k] = cur_.el[c];
      ++k;
    }
    renderer_.render(nullptr, nullptr, nullptr, 0, ptrs_, az, el, k, m, out);
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
