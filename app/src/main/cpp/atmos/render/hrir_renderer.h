// Measured-HRIR binaural renderer (Atmos plan Option B2, phase P2 — SOFA path).
//
// Replaces the procedural spherical-head model (structural_hrtf.h, deleted) with
// convolution against MEASURED head-related impulse responses. The HRIRs are the
// MIT KEMAR set, baked to a compact 48 kHz az/el grid by libmysofa in
// hrir_table.h (see scratchpad/dump.c). Measured HRIRs carry the pinna spectral
// cues — the notches and peaks that give real front/back and elevation
// disambiguation — that a delay + one-pole shadow cannot reproduce, which is the
// fidelity the procedural model lacked.
//
// Per source, per ear: the frame is convolved with the direction's HRIR
// (bilinearly interpolated across the grid), then delayed by the interpolated
// ITD. The baked table stores each HRIR ONSET-ALIGNED (energy at tap 0) with the
// ITD separated into a delay, because interpolating raw HRIRs whose onsets
// differ comb-filters the result (measured -17.8 dB at 10 kHz between nodes 10
// deg apart — audible as "lofi/thin"). Aligning before interpolation removes the
// comb; the ITD is restored here as a fractional delay. Input history is carried
// across frames so the convolution has no block-boundary gap.
//
// Same public interface as the old renderer (configure / set_params /
// set_bass_management / render / capacity) so the pipeline and JNI are
// unchanged. Header-only, depends only on <cmath>/<vector> + the baked table,
// and is -fno-exceptions / -fno-rtti safe.
#ifndef TF_ATMOS_RENDER_HRIR_RENDERER_H
#define TF_ATMOS_RENDER_HRIR_RENDERER_H

#include <atomic>
#include <cmath>
#include <cstddef>
#include <vector>

#include "hrir_table.h"

namespace tf {
namespace atmos {
namespace render {

constexpr float kPi = 3.14159265358979323846f;

// Renders positioned objects (+ bed channels) to binaural stereo by convolving
// each source with its direction's measured HRIR pair. Persistent per-source
// input history keeps the convolution continuous across frames.
class BinauralRenderer {
 public:
  // `sample_rate` should be 48000 — the table is baked at 48 kHz, which is the
  // native rate of every DD+ / Atmos bed, so no runtime resampling is needed.
  void configure(int sample_rate, int max_sources) {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    sources_.assign(static_cast<size_t>(max_sources < 1 ? 1 : max_sources), Source{});
    for (Source& s : sources_) s.reset();
    hl_.assign(kHrirTaps, 0.0f);
    hr_.assign(kHrirTaps, 0.0f);
  }

  // strength: 0 = dry (mono source to both ears) .. 1 = full HRIR convolution.
  // height_virtualization: when false, elevation is ignored (all sources are
  // looked up at the ear-level ring), matching the profile toggle.
  void set_params(float strength, bool height_virtualization) {
    strength_ = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f : strength);
    height_ = height_virtualization;
  }

  // Below `crossover_hz` the signal bypasses the HRIR and is summed equally to
  // both ears: low frequencies carry almost no interaural cue, and keeping them
  // out of the convolution avoids muddying the bass with the HRIR's colouration.
  void set_bass_management(bool enabled, int crossover_hz) {
    bass_management_ = enabled;
    crossover_hz_ = crossover_hz > 0 ? crossover_hz : 80;
    bass_coeff_ = std::exp(-2.0f * kPi * static_cast<float>(crossover_hz_) /
                           static_cast<float>(sample_rate_));
  }

  // Renders `num_objects` object signals (objects[o], `n` samples each) at
  // (obj_az[o], obj_el[o]) plus `bed_channels` bed signals at (bed_az[b],
  // bed_el[b]) into interleaved stereo `out` (2*n floats). `out` is overwritten.
  // Replaces the HRIR set with a runtime-loaded one (e.g. a user SOFA file),
  // laid out the SAME as the baked table: `data` is
  // kHrirElCount*kHrirAzCount*2*kHrirTaps floats, `delay` is
  // kHrirElCount*kHrirAzCount*2 floats. Double-buffered: the inactive slot is
  // filled, then published atomically, so the audio thread (which only reads the
  // active slot) never races the load. Call from ONE loader thread at a time.
  void set_runtime_hrir(const std::vector<float>& data, const std::vector<float>& delay) {
    const int cur = active_rt_.load(std::memory_order_acquire);
    const int slot = (cur == 0) ? 1 : 0;
    rt_data_[slot] = data;
    rt_delay_[slot] = delay;
    active_rt_.store(slot, std::memory_order_release);
  }

  // Reverts to the baked default HRTF.
  void clear_runtime_hrir() { active_rt_.store(-1, std::memory_order_release); }

  bool has_runtime_hrir() const { return active_rt_.load(std::memory_order_acquire) >= 0; }

  void render(const float* const* objects, const float* obj_az, const float* obj_el,
              int num_objects, const float* const* bed, const float* bed_az,
              const float* bed_el, int bed_channels, int n, float* out) {
    for (int i = 0; i < 2 * n; ++i) out[i] = 0.0f;
    // Select the HRIR source once per frame (not per source). The baked table is
    // contiguous, so &kHrirData[0][0][0][0] and the flat index below address it
    // identically to a runtime vector.
    const int rt = active_rt_.load(std::memory_order_acquire);
    if (rt >= 0) {
      cur_data_ = rt_data_[rt].data();
      cur_delay_ = rt_delay_[rt].data();
    } else {
      cur_data_ = &kHrirData[0][0][0][0];
      cur_delay_ = &kHrirDelay[0][0][0];
    }
    // ext holds (kHrirTaps-1) history samples followed by this frame's n input
    // samples, so the convolution can read across the block boundary. Reused
    // across sources (they are processed sequentially on the audio thread).
    const int hist = kHrirTaps - 1;
    if (static_cast<int>(ext_.size()) < hist + n) ext_.assign(hist + n, 0.0f);

    int slot = 0;
    for (int o = 0; o < num_objects && slot < capacity(); ++o, ++slot)
      mix_source(sources_[slot], objects[o], obj_az[o], obj_el[o], n, out);
    for (int b = 0; b < bed_channels && slot < capacity(); ++b, ++slot)
      mix_source(sources_[slot], bed[b], bed_az[b], bed_el[b], n, out);
  }

  int capacity() const { return static_cast<int>(sources_.size()); }

 private:
  // The ITD is applied as a fractional delay on the convolved output. The baked
  // delays reach ~32 samples; 64 leaves margin for interpolation.
  static constexpr int kDelayRing = 64;

  struct Source {
    // The last (kHrirTaps-1) samples of the band that was convolved last frame,
    // so this frame's convolution is continuous at the boundary.
    float tail[kHrirTaps];
    // Per-ear ring of convolved output, delayed by the interpolated ITD.
    float dl[kDelayRing];
    float dr[kDelayRing];
    int dwrite = 0;
    float bass_state = 0.0f;
    void reset() {
      for (float& v : tail) v = 0.0f;
      for (float& v : dl) v = 0.0f;
      for (float& v : dr) v = 0.0f;
      dwrite = 0;
      bass_state = 0.0f;
    }
  };

  // Fractional read `delay` samples behind the write cursor of a delay ring.
  static float read_delayed(const float* ring, int write, float delay) {
    if (delay < 0.0f) delay = 0.0f;
    if (delay > kDelayRing - 2) delay = kDelayRing - 2;
    const int d = static_cast<int>(delay);
    const float frac = delay - static_cast<float>(d);
    const int i0 = (write - 1 - d + kDelayRing) % kDelayRing;
    const int i1 = (i0 - 1 + kDelayRing) % kDelayRing;
    return ring[i0] * (1.0f - frac) + ring[i1] * frac;
  }

  // Bilinearly interpolates the HRIR pair for (azimuth, elevation) [radians]
  // into hl_/hr_. azimuth 0 = front, +right; elevation 0 = ear level, +up —
  // the same convention baked into the table.
  void lookup_hrir(float azimuth, float elevation) {
    float az_deg = azimuth * (180.0f / kPi);
    az_deg = std::fmod(az_deg, 360.0f);
    if (az_deg < 0.0f) az_deg += 360.0f;
    const float af = az_deg / kHrirAzStep;
    int a0 = static_cast<int>(af) % kHrirAzCount;
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

    const float w00 = (1.0f - fa) * (1.0f - fe), w10 = fa * (1.0f - fe);
    const float w01 = (1.0f - fa) * fe, w11 = fa * fe;
    const float* l00 = ir(e0, a0, 0); const float* r00 = ir(e0, a0, 1);
    const float* l10 = ir(e0, a1, 0); const float* r10 = ir(e0, a1, 1);
    const float* l01 = ir(e1, a0, 0); const float* r01 = ir(e1, a0, 1);
    const float* l11 = ir(e1, a1, 0); const float* r11 = ir(e1, a1, 1);
    for (int k = 0; k < kHrirTaps; ++k) {
      hl_[k] = w00 * l00[k] + w10 * l10[k] + w01 * l01[k] + w11 * l11[k];
      hr_[k] = w00 * r00[k] + w10 * r10[k] + w01 * r01[k] + w11 * r11[k];
    }
    // Interpolate the ITD delay the same way. Aligning the responses to tap 0
    // (baked) is what lets the IR interpolation above avoid comb filtering; the
    // ITD it removed is restored here as a smooth per-ear delay.
    dl_ = w00 * dly(e0, a0, 0) + w10 * dly(e0, a1, 0) +
          w01 * dly(e1, a0, 0) + w11 * dly(e1, a1, 0);
    dr_ = w00 * dly(e0, a0, 1) + w10 * dly(e0, a1, 1) +
          w01 * dly(e1, a0, 1) + w11 * dly(e1, a1, 1);
  }

  // Flat accessors over the active HRIR source (baked constexpr or a runtime
  // vector — both are [el][az][ear][tap] row-major, so the index is identical).
  const float* ir(int e, int a, int ear) const {
    return cur_data_ + ((static_cast<size_t>(e) * kHrirAzCount + a) * 2 + ear) * kHrirTaps;
  }
  float dly(int e, int a, int ear) const {
    return cur_delay_[(static_cast<size_t>(e) * kHrirAzCount + a) * 2 + ear];
  }

  void mix_source(Source& s, const float* in, float az, float el, int n, float* out) {
    lookup_hrir(az, height_ ? el : 0.0f);
    const int T = kHrirTaps, hist = T - 1;
    const float wet = strength_, dry = 1.0f - strength_, a = bass_coeff_;

    // Build the extended input: [history | this frame's convolved band]. The
    // band is the source minus its managed low band (or the whole source).
    for (int i = 0; i < hist; ++i) ext_[i] = s.tail[i];
    for (int i = 0; i < n; ++i) {
      float src = in[i], low = 0.0f, hp = src;
      if (bass_management_) {
        s.bass_state = (1.0f - a) * src + a * s.bass_state;
        low = s.bass_state;
        hp = src - low;
      }
      ext_[hist + i] = hp;

      // y[i] = sum_k h[k] * ext[i + (T-1) - k]  = correlate the reversed IR over
      // the window ending at ext[hist + i].
      const float* m = &ext_[i];  // m[0..T-1]; m[T-1] is the newest sample
      float yl = 0.0f, yr = 0.0f;
      for (int k = 0; k < T; ++k) {
        const float x = m[hist - k];
        yl += hl_[k] * x;
        yr += hr_[k] * x;
      }
      // Apply the ITD: push the convolved output into the per-ear ring and read
      // it back the interpolated delay behind, so each ear's arrival time is
      // correct without the onset delay ever being inside the interpolated IR.
      s.dl[s.dwrite] = yl;
      s.dr[s.dwrite] = yr;
      const float dyl = read_delayed(s.dl, s.dwrite + 1, dl_);
      const float dyr = read_delayed(s.dr, s.dwrite + 1, dr_);
      s.dwrite = (s.dwrite + 1) % kDelayRing;
      // Wet HRIR image blended with the dry (mono) band; the managed bass is
      // re-added equally to both ears outside the blend.
      out[2 * i]     += wet * dyl + dry * hp + low;
      out[2 * i + 1] += wet * dyr + dry * hp + low;
    }
    // Carry the last (T-1) convolved-band samples into the next frame.
    for (int i = 0; i < hist; ++i) s.tail[i] = ext_[n + i];
  }

  int sample_rate_ = 48000;
  float strength_ = 1.0f;
  bool height_ = true;
  bool bass_management_ = false;
  int crossover_hz_ = 80;
  float bass_coeff_ = 0.0f;
  std::vector<Source> sources_;
  std::vector<float> ext_;       // reusable [hist + n] convolution input
  std::vector<float> hl_, hr_;   // interpolated HRIR pair for the current source
  float dl_ = 0.0f, dr_ = 0.0f;  // interpolated ITD delay (samples) per ear

  // Active HRIR source for the current render() call (set once per frame).
  const float* cur_data_ = &kHrirData[0][0][0][0];
  const float* cur_delay_ = &kHrirDelay[0][0][0];
  // Double-buffered runtime HRIR sets (from a loaded SOFA). -1 = use the baked
  // default; 0/1 selects a runtime slot. Only the inactive slot is ever written.
  std::vector<float> rt_data_[2], rt_delay_[2];
  std::atomic<int> active_rt_{-1};
};

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_HRIR_RENDERER_H
