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
// (bilinearly interpolated across the grid). The ITD lives in the HRIR itself as
// an inter-ear onset difference, so no separate delay line is needed — the
// measured data already encodes it. Input history is carried across frames so
// the convolution has no block-boundary gap.
//
// Same public interface as the old renderer (configure / set_params /
// set_bass_management / render / capacity) so the pipeline and JNI are
// unchanged. Header-only, depends only on <cmath>/<vector> + the baked table,
// and is -fno-exceptions / -fno-rtti safe.
#ifndef TF_ATMOS_RENDER_HRIR_RENDERER_H
#define TF_ATMOS_RENDER_HRIR_RENDERER_H

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
  void render(const float* const* objects, const float* obj_az, const float* obj_el,
              int num_objects, const float* const* bed, const float* bed_az,
              const float* bed_el, int bed_channels, int n, float* out) {
    for (int i = 0; i < 2 * n; ++i) out[i] = 0.0f;
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
  struct Source {
    // The last (kHrirTaps-1) samples of the band that was convolved last frame,
    // so this frame's convolution is continuous at the boundary.
    float tail[kHrirTaps];
    float bass_state = 0.0f;
    void reset() {
      for (float& v : tail) v = 0.0f;
      bass_state = 0.0f;
    }
  };

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
    const float* l00 = kHrirData[e0][a0][0]; const float* r00 = kHrirData[e0][a0][1];
    const float* l10 = kHrirData[e0][a1][0]; const float* r10 = kHrirData[e0][a1][1];
    const float* l01 = kHrirData[e1][a0][0]; const float* r01 = kHrirData[e1][a0][1];
    const float* l11 = kHrirData[e1][a1][0]; const float* r11 = kHrirData[e1][a1][1];
    for (int k = 0; k < kHrirTaps; ++k) {
      hl_[k] = w00 * l00[k] + w10 * l10[k] + w01 * l01[k] + w11 * l11[k];
      hr_[k] = w00 * r00[k] + w10 * r10[k] + w01 * r01[k] + w11 * r11[k];
    }
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
      // Wet HRIR image blended with the dry (mono) band; the managed bass is
      // re-added equally to both ears outside the blend.
      out[2 * i]     += wet * yl + dry * hp + low;
      out[2 * i + 1] += wet * yr + dry * hp + low;
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
};

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_HRIR_RENDERER_H
