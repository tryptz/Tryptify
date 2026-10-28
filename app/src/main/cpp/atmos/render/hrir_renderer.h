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
// across frames so the convolution has no block-boundary gap. Direction changes
// are motion-crossfaded: the frame is convolved against both the previous and
// the current IR with a raised-cosine blend, and the ITD ramps per sample, so
// a moving source glides instead of stepping once per frame.
//
// The convolution is overlap-save in blocks of 128 samples (256-point FFTs):
// one forward FFT of the source serves both ears (and both IRs while it
// moves), against the IR pair's spectra, which are worked out once per call
// and only when the direction changed. Each block's input is all in hand when
// render() is called — the frame plus the history carried over — so every
// output sample still depends on exactly the input up to it, as the direct
// form did: no latency is added, and a short last block is zero-padded. It is
// the same convolution to float rounding, for roughly a third of the
// arithmetic (it was 256 multiply-adds a sample per source, 512 while it
// moved).
//
// Same public interface as the old renderer (configure / set_params /
// set_bass_management / render / capacity) so the pipeline and JNI are
// unchanged. Header-only, depends only on <cmath>/<vector>, the baked table
// and the vendored FFT (../fft.h), and is -fno-exceptions / -fno-rtti safe.
#ifndef TF_ATMOS_RENDER_HRIR_RENDERER_H
#define TF_ATMOS_RENDER_HRIR_RENDERER_H

#include <atomic>
#include <cmath>
#include <cstddef>
#include <vector>

#include "../fft.h"
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
    // Sized for an E-AC-3 frame here, off the audio thread; render() grows
    // them only for a longer call.
    ext_.assign(kHrirTaps - 1 + kTypicalFrame, 0.0f);
    low_.assign(kTypicalFrame, 0.0f);
    fade_ct_.assign(kTypicalFrame, 0.0f);
    fade_w_.assign(kTypicalFrame, 0.0f);
    fade_n_ = -1;
    // Recompute the crossover for the (possibly new) sample rate.
    set_bass_management(bass_management_, crossover_hz_);
  }

  // strength: 1 = full HRIR convolution; 0 = ITD-only (the dry band keeps the
  // per-ear arrival-time cue but no spectral shaping). The dry path shares the
  // wet path's fractional ITD delay so blending them is time-aligned — an
  // undelayed dry signal against an ITD-delayed wet one comb-filtered every
  // intermediate strength setting.
  // height_virtualization: when false, elevation is ignored (all sources are
  // looked up at the ear-level ring), matching the profile toggle.
  void set_params(float strength, bool height_virtualization) {
    strength_ = strength < 0.0f ? 0.0f : (strength > 1.0f ? 1.0f : strength);
    height_ = height_virtualization;
  }

  // Below `crossover_hz` the signal bypasses the HRIR and is summed equally to
  // both ears: low frequencies carry almost no interaural cue, and keeping them
  // out of the convolution avoids muddying the bass with the HRIR's colouration.
  // The split is a Linkwitz-Riley 4th-order pair (two cascaded Butterworth
  // biquads per band): LP4 + HP4 sums to an allpass, i.e. magnitude-flat, where
  // the previous one-pole split leaked mids into the diffuse band at 6 dB/oct.
  void set_bass_management(bool enabled, int crossover_hz) {
    bass_management_ = enabled;
    crossover_hz_ = crossover_hz > 0 ? crossover_hz : 80;
    const float w0 = 2.0f * kPi * static_cast<float>(crossover_hz_) /
                     static_cast<float>(sample_rate_);
    const float cw = std::cos(w0);
    const float alpha = std::sin(w0) * 0.70710678f;  // Q = 1/sqrt(2)
    const float a0 = 1.0f + alpha;
    lp_c_[0] = (0.5f * (1.0f - cw)) / a0;
    lp_c_[1] = (1.0f - cw) / a0;
    lp_c_[2] = lp_c_[0];
    hp_c_[0] = (0.5f * (1.0f + cw)) / a0;
    hp_c_[1] = -(1.0f + cw) / a0;
    hp_c_[2] = hp_c_[0];
    lp_c_[3] = hp_c_[3] = (-2.0f * cw) / a0;
    lp_c_[4] = hp_c_[4] = (1.0f - alpha) / a0;
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
    if (static_cast<int>(low_.size()) < n) {
      low_.assign(n, 0.0f);
      fade_ct_.assign(n, 0.0f);
      fade_w_.assign(n, 0.0f);
      fade_n_ = -1;
    }

    int slot = 0;
    for (int o = 0; o < num_objects && slot < capacity(); ++o, ++slot)
      mix_source(sources_[slot], objects[o], obj_az[o], obj_el[o], n, out);
    for (int b = 0; b < bed_channels && slot < capacity(); ++b, ++slot)
      mix_source(sources_[slot], bed[b], bed_az[b], bed_el[b], n, out);
  }

  int capacity() const { return static_cast<int>(sources_.size()); }

  // Zeroes every source's time history (convolution tails, ITD delay rings)
  // without reallocating, so it is safe on the audio thread. Called on
  // seek/flush — otherwise the first frames after a seek would convolve
  // against pre-seek audio.
  void reset_history() {
    for (Source& s : sources_) s.reset();
  }

 private:
  // The ITD is applied as a fractional delay on the convolved output. The baked
  // delays reach ~32 samples; 64 leaves margin for interpolation.
  static constexpr int kDelayRing = 64;
  // Overlap-save: blocks of kBlock outputs, each from a window of kFft input
  // samples (the block and the kHrirTaps - 1 before it) against an IR padded
  // to kFft.
  static constexpr int kBlock = 128;
  static constexpr int kFft = 256;
  static constexpr int kBins = kFft / 2 + 1;
  static_assert(kHrirTaps <= kFft - kBlock + 1, "the IR must fit the overlap");
  // Scratch is sized for an E-AC-3 frame up front.
  static constexpr int kTypicalFrame = 1536;

  struct Source {
    // The last (kHrirTaps-1) samples of the band that was convolved last frame,
    // so this frame's convolution is continuous at the boundary.
    float tail[kHrirTaps];
    // Per-ear ring of convolved output, delayed by the interpolated ITD, and
    // the dry band's input ring (read at the same delays so wet/dry align).
    float dl[kDelayRing];
    float dr[kDelayRing];
    float din[kDelayRing];
    int dwrite = 0;
    // LR4 crossover state: LP biquad 1+2, HP biquad 1+2 (2 floats each).
    float bm[8];
    // Spectra of the IR pair, [slot][ear]: slot `cur` is the last call's IR.
    // For the motion crossfade, when the direction (or the HRIR set) changes,
    // the new IR goes into the other slot and the frame is convolved against
    // both and crossfaded instead of swapping filters at the boundary.
    fft::Complex spec[2][2][kBins];
    int cur = 0;
    float pdl = 0.0f, pdr = 0.0f;
    float paz = 0.0f, pel = 0.0f;
    const float* ptable = nullptr;  // HRIR set the previous IR came from
    bool has_prev = false;
    void reset() {
      for (float& v : tail) v = 0.0f;
      for (float& v : dl) v = 0.0f;
      for (float& v : dr) v = 0.0f;
      for (float& v : din) v = 0.0f;
      for (float& v : bm) v = 0.0f;
      dwrite = 0;
      cur = 0;
      pdl = pdr = paz = pel = 0.0f;
      ptable = nullptr;
      has_prev = false;
    }
  };

  // One biquad step, transposed direct form II. c = {b0,b1,b2,a1,a2}; z = 2
  // floats of state.
  static float biquad(const float* c, float* z, float x) {
    const float y = c[0] * x + z[0];
    z[0] = c[1] * x - c[3] * y + z[1];
    z[1] = c[2] * x - c[4] * y;
    return y;
  }

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

  // The spectra of the current hl_/hr_ into s.spec[slot], scaled by 1/kFft
  // so the unnormalised inverse FFT gives the convolution itself.
  void ir_spectra(Source& s, int slot) {
    const float scale = 1.0f / kFft;
    for (int ear = 0; ear < 2; ++ear) {
      const float* h = ear == 0 ? hl_.data() : hr_.data();
      for (int k = 0; k < kHrirTaps; ++k) win_[k] = h[k] * scale;
      for (int k = kHrirTaps; k < kFft; ++k) win_[k] = 0.0f;
      fft_.forward(win_, s.spec[slot][ear]);
    }
  }

  // The window's spectrum (in xs_) times `ir`, back to time: the block's
  // kBlock outputs are the last kBlock samples of the circular convolution.
  void convolve_block(const fft::Complex* ir, float* dst) {
    for (int k = 0; k < kBins; ++k) zs_[k] = fft::mul(xs_[k], ir[k]);
    fft_.inverse(zs_, yt_);
    for (int t = 0; t < kBlock; ++t) dst[t] = yt_[kFft - kBlock + t];
  }

  void mix_source(Source& s, const float* in, float az, float el, int n, float* out) {
    const float el_eff = height_ ? el : 0.0f;
    lookup_hrir(az, el_eff);
    const int hist = kHrirTaps - 1;
    const float wet = strength_, dry = 1.0f - strength_;

    // Motion crossfade decision. Any real direction change (even sub-degree —
    // it still moves the interpolation weights) or a swapped HRIR set (runtime
    // SOFA load) crossfades; the epsilon only skips work for static sources.
    // Without this the whole direction change lands inside the ~ITD-sized
    // window the delay ring smears it over — zipper steps at the frame rate
    // (measured 0.0052 max step on a DC probe; crossfaded: ~0.0002).
    const bool changed = s.has_prev &&
        (std::fabs(az - s.paz) > 1e-4f || std::fabs(el_eff - s.pel) > 1e-4f ||
         s.ptable != cur_data_);
    if (!s.has_prev) {
      s.pdl = dl_;
      s.pdr = dr_;
    }
    const float pdl0 = s.pdl, pdr0 = s.pdr;
    // The IR spectra: the last call's stay in s.cur for the crossfade when the
    // direction changed; they are reused as they are when the direction is
    // exactly the last one (the same lookup gives the same IR); a move under
    // the crossfade's epsilon replaces them, as the direct form used the new
    // IR at once.
    if (changed) {
      s.cur ^= 1;
      ir_spectra(s, s.cur);
    } else if (!s.has_prev || az != s.paz || el_eff != s.pel) {
      ir_spectra(s, s.cur);
    }
    const fft::Complex* hl = s.spec[s.cur][0];
    const fft::Complex* hr = s.spec[s.cur][1];
    const fft::Complex* pl = s.spec[s.cur ^ 1][0];
    const fft::Complex* pr = s.spec[s.cur ^ 1][1];
    // The crossfade's ramp and raised-cosine weight depend only on the call's
    // length, so they are worked out once per length, not per moving source.
    if (changed && fade_n_ != n) {
      for (int i = 0; i < n; ++i) {
        const float ct = (i + 0.5f) / static_cast<float>(n);
        fade_ct_[i] = ct;
        fade_w_[i] = 0.5f - 0.5f * std::cos(kPi * ct);
      }
      fade_n_ = n;
    }

    // Build the extended input: [history | this frame's convolved band]. The
    // band is the source minus its managed low band (or the whole source).
    for (int i = 0; i < hist; ++i) ext_[i] = s.tail[i];
    float* low = low_.data();
    for (int i = 0; i < n; ++i) {
      float src = in[i], lo = 0.0f, hp = src;
      if (bass_management_) {
        // LR4 split: both bands are filtered (no subtractive shortcut) so
        // LP4 + HP4 sums allpass-flat.
        float lp = biquad(lp_c_, s.bm + 0, src);
        lo = biquad(lp_c_, s.bm + 2, lp);
        float hb = biquad(hp_c_, s.bm + 4, src);
        hp = biquad(hp_c_, s.bm + 6, hb);
      }
      ext_[hist + i] = hp;
      low[i] = lo;
    }

    for (int b = 0; b < n; b += kBlock) {
      const int len = n - b < kBlock ? n - b : kBlock;
      // The window for outputs b .. b + kBlock - 1 is input b - kBlock ..
      // b + kBlock - 1, which is ext_[b - 1 ..]. Its first sample reaches
      // only outputs that are discarded (the history holds kHrirTaps - 1), so
      // it is left zero, as is anything past the end of the frame.
      const int lead = kFft - kBlock - hist;  // 1
      for (int m = 0; m < lead; ++m) win_[m] = 0.0f;
      const int avail = hist + n - b;  // ext_ samples from index b on
      const int take = avail < kFft - lead ? avail : kFft - lead;
      for (int m = 0; m < take; ++m) win_[lead + m] = ext_[b + m];
      for (int m = lead + take; m < kFft; ++m) win_[m] = 0.0f;
      fft_.forward(win_, xs_);
      convolve_block(hl, cl_);
      convolve_block(hr, cr_);
      if (changed) {
        convolve_block(pl, pl_);
        convolve_block(pr, pr_);
      }

      for (int t = 0; t < len; ++t) {
        const int i = b + t;
        const float hp = ext_[hist + i];
        float yl, yr, edl, edr;
        if (changed) {
          // Previous and current IR share the input window; the raised-cosine
          // weight moves the image, and the ITD ramps linearly (a
          // constant-velocity source — the slight Doppler this implies is the
          // physically correct sound).
          const float ct = fade_ct_[i];
          const float w = fade_w_[i];
          yl = pl_[t] + w * (cl_[t] - pl_[t]);
          yr = pr_[t] + w * (cr_[t] - pr_[t]);
          edl = pdl0 + (dl_ - pdl0) * ct;
          edr = pdr0 + (dr_ - pdr0) * ct;
        } else {
          yl = cl_[t];
          yr = cr_[t];
          edl = dl_;
          edr = dr_;
        }
        // Apply the ITD: push the convolved output into the per-ear ring and
        // read it back the interpolated delay behind, so each ear's arrival
        // time is correct without the onset delay ever being inside the
        // interpolated IR. The dry band takes the SAME per-ear delays from its
        // own input ring — time-aligned with the wet image, so any blend of
        // the two is comb-free (and strength 0 degrades gracefully to an
        // ITD-only render).
        s.dl[s.dwrite] = yl;
        s.dr[s.dwrite] = yr;
        s.din[s.dwrite] = hp;
        const float dyl = read_delayed(s.dl, s.dwrite + 1, edl);
        const float dyr = read_delayed(s.dr, s.dwrite + 1, edr);
        const float dryl = read_delayed(s.din, s.dwrite + 1, edl);
        const float dryr = read_delayed(s.din, s.dwrite + 1, edr);
        s.dwrite = (s.dwrite + 1) % kDelayRing;
        // Wet HRIR image blended with the aligned dry band; the managed bass
        // is re-added equally to both ears outside the blend.
        out[2 * i]     += wet * dyl + dry * dryl + low[i];
        out[2 * i + 1] += wet * dyr + dry * dryr + low[i];
      }
    }
    // Carry the last (kHrirTaps-1) convolved-band samples into the next frame,
    // and the current ITD/direction as the next frame's crossfade origin (the
    // IR's spectra are already in s.spec[s.cur]).
    for (int i = 0; i < hist; ++i) s.tail[i] = ext_[n + i];
    s.pdl = dl_;
    s.pdr = dr_;
    s.paz = az;
    s.pel = el_eff;
    s.ptable = cur_data_;
    s.has_prev = true;
  }

  int sample_rate_ = 48000;
  float strength_ = 1.0f;
  bool height_ = true;
  bool bass_management_ = false;
  int crossover_hz_ = 80;
  // LR4 crossover coefficients ({b0,b1,b2,a1,a2} per band); state is per-source.
  float lp_c_[5] = {0, 0, 0, 0, 0};
  float hp_c_[5] = {0, 0, 0, 0, 0};
  std::vector<Source> sources_;
  std::vector<float> ext_;       // reusable [hist + n] convolution input
  std::vector<float> low_;       // reusable [n] managed low band
  // The motion crossfade's ramp and weight per sample, for calls fade_n_ long.
  std::vector<float> fade_ct_, fade_w_;
  int fade_n_ = -1;
  std::vector<float> hl_, hr_;   // interpolated HRIR pair for the current source
  // Overlap-save scratch, reused by every source in turn.
  fft::RealFft fft_{kFft};
  float win_[kFft] = {};
  float yt_[kFft] = {};
  fft::Complex xs_[kBins], zs_[kBins];
  float cl_[kBlock] = {}, cr_[kBlock] = {}, pl_[kBlock] = {}, pr_[kBlock] = {};
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
