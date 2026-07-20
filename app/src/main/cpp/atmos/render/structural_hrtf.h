// Structural HRTF binaural renderer (Atmos plan Option B2, phase P2).
//
// Renders positioned audio objects (+ the bed channels) to binaural stereo using
// a PROCEDURAL spherical-head HRTF model — no measured HRIR dataset, so it is
// clean-room and carries no third-party data license. Two dominant localization
// cues are modeled per source:
//   * ITD  — Woodworth spherical-head interaural time difference (a fractional
//            delay on the far ear).
//   * ILD  — a per-ear head-shadow: a level term plus a one-pole low-pass whose
//            strength grows as the ear turns away from the source (Brown-Duda
//            style).
// This is real-time-cheap (one delay line + one one-pole per source per ear) and
// gives stable left/right + front/elevation placement. Measured-HRIR convolution
// (pinna spectral cues) is a fidelity upgrade path, not required here.
//
// Header-only, dependency-free (only <cmath>/<vector>), -fno-exceptions safe.
#ifndef TF_ATMOS_RENDER_STRUCTURAL_HRTF_H
#define TF_ATMOS_RENDER_STRUCTURAL_HRTF_H

#include <cmath>
#include <cstddef>
#include <vector>

namespace tf {
namespace atmos {
namespace render {

constexpr float kHeadRadius = 0.0875f;   // m, average human head
constexpr float kSoundSpeed = 343.0f;    // m/s
constexpr float kPi = 3.14159265358979323846f;
constexpr float kHalfPi = kPi * 0.5f;

// Wrap an angle to [-pi, pi].
inline float wrap_pi(float a) {
  while (a > kPi) a -= 2.0f * kPi;
  while (a < -kPi) a += 2.0f * kPi;
  return a;
}

// Woodworth spherical-head ITD (seconds) at a lateral angle |lat| in radians,
// folded so the rear hemisphere mirrors the front.
inline float woodworth_itd(float lat) {
  float a = std::fabs(lat);
  if (a > kHalfPi) a = kPi - a;
  return (kHeadRadius / kSoundSpeed) * (std::sin(a) + a);
}

// Per-source, per-ear rendering coefficients for a static direction.
struct SourceCoeffs {
  float delay_l, delay_r;  // fractional sample delay per ear
  float gain_l, gain_r;    // head-shadow level per ear
  float lp_l, lp_r;        // one-pole coefficient per ear (0 = none .. ~0.9 heavy)
};

// Level from 1.0 (ear faces the source) down to kMinGain (ear faces away).
inline float ear_gain(float incidence) {
  constexpr float kMinGain = 0.35f;
  const float f = 0.5f * (1.0f + std::cos(incidence));  // 1 at 0, 0 at pi
  return kMinGain + (1.0f - kMinGain) * f;
}

// One-pole low-pass coefficient: none when the ear faces the source, heavy when
// it faces away (the head shadows high frequencies at the far ear).
inline float ear_lp(float incidence) {
  constexpr float kMaxLp = 0.85f;
  const float f = 0.5f * (1.0f - std::cos(incidence));  // 0 at 0, 1 at pi
  return kMaxLp * std::sqrt(f);
}

// azimuth: radians, 0 = front, +right. elevation: radians, 0 = ear level, +up.
inline SourceCoeffs compute_coeffs(float azimuth, float elevation, int sample_rate) {
  // Laterality collapses toward 0 as the source rises overhead.
  const float sin_lat = std::sin(azimuth) * std::cos(elevation);
  const float lat = std::asin(sin_lat < -1.0f ? -1.0f : (sin_lat > 1.0f ? 1.0f : sin_lat));
  const float itd_samples = woodworth_itd(lat) * static_cast<float>(sample_rate);

  // Angular distance from the source to each ear's outward normal (±90°).
  const float inc_r = std::fabs(wrap_pi(azimuth - kHalfPi));  // 0 = faces right ear
  const float inc_l = std::fabs(wrap_pi(azimuth + kHalfPi));

  SourceCoeffs c;
  // The ear on the far side of the head is delayed by the ITD.
  if (sin_lat >= 0.0f) {  // source to the right
    c.delay_r = 0.0f;
    c.delay_l = itd_samples;
  } else {
    c.delay_l = 0.0f;
    c.delay_r = itd_samples;
  }
  c.gain_l = ear_gain(inc_l);
  c.gain_r = ear_gain(inc_r);
  c.lp_l = ear_lp(inc_l);
  c.lp_r = ear_lp(inc_r);
  return c;
}

// Binaural renderer with persistent per-source delay/filter state. Sources are
// the objects followed by the bed channels; configure() sizes for the max.
class BinauralRenderer {
 public:
  void configure(int sample_rate, int max_sources) {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    sources_.assign(static_cast<size_t>(max_sources < 1 ? 1 : max_sources), Source{});
    for (Source& s : sources_) s.reset();
  }

  // Renders `num_objects` object signals (objects[o], `n` samples each) at
  // (obj_az[o], obj_el[o]) plus `bed_channels` bed signals at (bed_az[b],
  // bed_el[b]) into interleaved stereo `out` (2*n floats). `out` is overwritten.
  void render(const float* const* objects, const float* obj_az, const float* obj_el,
              int num_objects, const float* const* bed, const float* bed_az,
              const float* bed_el, int bed_channels, int n, float* out) {
    for (int i = 0; i < 2 * n; ++i) out[i] = 0.0f;
    int slot = 0;
    for (int o = 0; o < num_objects && slot < capacity(); ++o, ++slot)
      mix_source(sources_[slot], objects[o], obj_az[o], obj_el[o], n, out);
    for (int b = 0; b < bed_channels && slot < capacity(); ++b, ++slot)
      mix_source(sources_[slot], bed[b], bed_az[b], bed_el[b], n, out);
  }

  int capacity() const { return static_cast<int>(sources_.size()); }

 private:
  static constexpr int kDelayLen = 128;  // > max Woodworth ITD (~32 @ 48k) + margin

  struct Source {
    float ring[kDelayLen];
    int write = 0;
    float lp_state_l = 0.0f, lp_state_r = 0.0f;
    void reset() {
      for (float& v : ring) v = 0.0f;
      write = 0;
      lp_state_l = 0.0f;
      lp_state_r = 0.0f;
    }
  };

  // Fractional read `delay` samples behind the current write cursor.
  static float read_delayed(const Source& s, float delay) {
    if (delay < 0.0f) delay = 0.0f;
    if (delay > kDelayLen - 2) delay = kDelayLen - 2;
    const int d = static_cast<int>(delay);
    const float frac = delay - d;
    const int i0 = (s.write - 1 - d + kDelayLen) % kDelayLen;
    const int i1 = (i0 - 1 + kDelayLen) % kDelayLen;
    return s.ring[i0] * (1.0f - frac) + s.ring[i1] * frac;
  }

  void mix_source(Source& s, const float* in, float az, float el, int n, float* out) {
    const SourceCoeffs c = compute_coeffs(az, el, sample_rate_);
    for (int i = 0; i < n; ++i) {
      s.ring[s.write] = in[i];
      s.write = (s.write + 1) % kDelayLen;
      const float sl = c.gain_l * read_delayed(s, c.delay_l);
      const float sr = c.gain_r * read_delayed(s, c.delay_r);
      s.lp_state_l = (1.0f - c.lp_l) * sl + c.lp_l * s.lp_state_l;
      s.lp_state_r = (1.0f - c.lp_r) * sr + c.lp_r * s.lp_state_r;
      out[2 * i] += s.lp_state_l;
      out[2 * i + 1] += s.lp_state_r;
    }
  }

  int sample_rate_ = 48000;
  std::vector<Source> sources_;
};

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_STRUCTURAL_HRTF_H
