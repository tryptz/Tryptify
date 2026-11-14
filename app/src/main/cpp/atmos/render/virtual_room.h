// A virtual 15.1.12 loudspeaker room over headphones: the binaural stage of
// the headphone Atmos render.
//
// The speaker renderer (../speaker_renderer.h) pans objects and beds onto the
// room's speakers exactly as it does for real ones, so size, divergence, zones
// and channel lock all apply. This turns those speaker feeds into two ears:
//
//   speaker feeds --+-- early reflections (delayed copies into other speakers)
//                   +-- HRIR convolution, one fixed pair of filters a speaker
//                   |     -> diffuse-field equalizer
//                   +-- LFE: 120 Hz low-pass, +10 dB, to both ears
//                   +-- a shared late tail (feedback delay network)
//                   -> headroom trim -> look-ahead limiter -> out
//
// The speakers never move, so each one's filter pair is made once, with its
// interaural delay inside it (a windowed-sinc fractional delay; the moving
// renderer applies that delay afterwards, by linear interpolation). That is
// what lets the convolutions be summed as spectra: every speaker's block is
// transformed once, multiplied by its two filters into one spectrum an ear,
// and only those two come back to time. 27 speakers cost 27 forward FFTs and
// 2 inverse ones a block, and a speaker that has been silent costs nothing.
//
// Convolution is overlap-save in blocks of kBlock (512-point FFTs). A call
// brings whole blocks (an E-AC-3 frame is six), and the last short one of an
// odd-sized call is zero-padded: every output depends on the input up to it.
// The stage's delay is kLatency samples, the same for every input, the LFE
// and the tail included.
//
// Realtime contract: configure() and set_hrir*() allocate and run off the
// audio thread; render() and reset_history() allocate nothing. Filters are
// double-buffered and published atomically, as in hrir_renderer.h.
// Header-only: the standard library, the baked HRIR table and ../fft.h.
#ifndef TF_ATMOS_RENDER_VIRTUAL_ROOM_H
#define TF_ATMOS_RENDER_VIRTUAL_ROOM_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <complex>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#include "../fft.h"
#include "hrir_dfe.h"
#include "hrir_table.h"
#include "partitioned_fir.h"

namespace tf {
namespace atmos {
namespace render {

// One speaker of the room. Degrees; azimuth 0 ahead, positive to the right.
struct RoomSpeaker {
  float azimuth = 0.0f;
  float elevation = 0.0f;
  bool lfe = false;
};

class VirtualRoom {
 public:
  static constexpr int kBlock = 256;
  static constexpr int kFft = 2 * kBlock;
  static constexpr int kBins = kFft / 2 + 1;
  static constexpr int kHist = kFft - kBlock;
  // The fractional-delay filter each speaker's interaural delay goes through:
  // kSincTaps taps centred kSincDelay samples in.
  static constexpr int kSincTaps = 16;
  static constexpr int kSincDelay = 7;
  static constexpr int kMaxItd = 62;  // samples, as hrir_renderer.h clamps it
  static constexpr int kIrLen = kHrirTaps + kMaxItd + kSincTaps;
  static_assert(kIrLen <= kFft - kBlock + 1, "a speaker's filter must fit the overlap");
  // The limiter's look-ahead: its gain is the smallest needed over kLook
  // samples, averaged over kLook more, so it is down before the peak is out.
  static constexpr int kLook = 33;
  static constexpr int kLookahead = kLook - 1;
  static constexpr int kLatency = kSincDelay + kLookahead;
  static constexpr int kMaxSpeakers = 32;
  static constexpr int kTypicalFrame = 1536;

  // The LFE channel plays 10 dB above a main channel (its in-band gain).
  static constexpr float kLfeGain = 3.16227766f;
  static constexpr float kLfeCutoffHz = 120.0f;
  // Fixed headroom ahead of the limiter, and where the limiter holds peaks.
  static constexpr float kTrim = 0.70794578f;    // -3 dB
  static constexpr float kCeiling = 0.94406088f; // -0.5 dBFS

  // Allocates everything and builds the filters from the baked HRIR set.
  // `speakers` is the room, in the order of the feeds render() is given.
  void configure(int sample_rate, const std::vector<RoomSpeaker>& speakers) {
    sample_rate_ = sample_rate > 0 ? sample_rate : 48000;
    speakers_ = speakers;
    if (speakers_.size() > static_cast<size_t>(kMaxSpeakers)) speakers_.resize(kMaxSpeakers);
    count_ = static_cast<int>(speakers_.size());
    lfe_ = -1;
    for (int s = 0; s < count_; ++s) if (speakers_[s].lfe) lfe_ = s;

    hist_.assign(static_cast<size_t>(count_) * kHist, 0.0f);
    in_.assign(static_cast<size_t>(count_) * kBlock, 0.0f);
    quiet_.assign(count_, kHist);
    for (int b = 0; b < 2; ++b) {
      sets_[b].spec.assign(static_cast<size_t>(count_) * 2 * kBins, fft::Complex(0.0f, 0.0f));
      sets_[b].dfe.assign(kDfe, kDfe + kDfeTaps);
    }
    pan_l_.assign(count_, 0.0f);
    pan_r_.assign(count_, 0.0f);
    for (int s = 0; s < count_; ++s) {
      // The dry band's place: an equal-power pan from the left/right component.
      const float az = speakers_[s].azimuth * kRad, el = speakers_[s].elevation * kRad;
      const float theta = (std::sin(az) * std::cos(el) + 1.0f) * (kPiF / 4.0f);
      pan_l_[s] = std::cos(theta);
      pan_r_[s] = std::sin(theta);
    }
    configure_reflections();
    configure_tail();
    set_bass_management(bass_, crossover_hz_);
    design_lowpass(kLfeCutoffHz, lfe_c_);
    design_highpass(kTailLowCutHz, tail_hp_c_);
    reserve(kTypicalFrame);
    reset_history();

    build(sets_[0], &kHrirData[0][0][0][0], &kHrirDelay[0][0][0], kDfe);
    active_.store(next_version(active_.load(std::memory_order_acquire)),
                  std::memory_order_seq_cst);
    applied_ = ~0u;
  }

  int speaker_count() const { return count_; }

  // strength: 1 is the full HRIR render, 0 the speakers panned left and right
  // with no ear filtering, time-aligned so anything between is a clean blend.
  // room: 0 is no room at all (no reflections, no tail), 1 a live one.
  void set_params(float strength, float room) {
    strength_ = clamp01(strength);
    room_ = clamp01(room);
  }

  // Below the crossover the feeds go round the ear filters, summed and sent to
  // both ears alike (a Linkwitz-Riley 4th-order split, as in hrir_renderer.h).
  void set_bass_management(bool enabled, int crossover_hz) {
    bass_ = enabled;
    crossover_hz_ = crossover_hz > 0 ? crossover_hz : 80;
    design_lowpass(static_cast<float>(crossover_hz_), lp_c_);
    design_highpass(static_cast<float>(crossover_hz_), hp_c_);
  }

  // Replaces the HRIR set with a loaded one, laid out as the baked table
  // (see BinauralRenderer::set_runtime_hrir), and works out its own
  // diffuse-field equalizer. Loader thread only, one call at a time.
  void set_hrir(const std::vector<float>& data, const std::vector<float>& delay) {
    const size_t points = static_cast<size_t>(kHrirElCount) * kHrirAzCount * 2;
    if (count_ == 0 || data.size() < points * kHrirTaps || delay.size() < points) return;
    std::vector<float> dfe(kDfeTaps, 0.0f);
    design_dfe(data.data(), dfe.data());
    publish(data.data(), delay.data(), dfe.data());
  }

  // Back to the baked set and its baked equalizer.
  void set_hrir_baked() {
    if (count_ == 0) return;
    publish(&kHrirData[0][0][0][0], &kHrirDelay[0][0][0], kDfe);
  }

  // Clears every history (convolution, delays, the room, the limiter) without
  // reallocating: a seek or a flush.
  void reset_history() {
    std::fill(hist_.begin(), hist_.end(), 0.0f);
    std::fill(quiet_.begin(), quiet_.end(), kHist);
    dfe_fir_.reset();
    for (auto& d : group_delay_) std::fill(d.begin(), d.end(), 0.0f);
    group_pos_ = 0;
    std::memset(group_lp_, 0, sizeof(group_lp_));
    for (auto& l : lines_) std::fill(l.buf.begin(), l.buf.end(), 0.0f);
    for (auto& l : lines_) { l.pos = 0; l.damp = 0.0f; }
    std::fill(predelay_.begin(), predelay_.end(), 0.0f);
    predelay_pos_ = 0;
    std::memset(z_, 0, sizeof(z_));
    std::memset(shelf_z_, 0, sizeof(shelf_z_));
    std::memset(align_, 0, sizeof(align_));
    align_pos_ = 0;
    std::memset(look_l_, 0, sizeof(look_l_));
    std::memset(look_r_, 0, sizeof(look_r_));
    for (float& v : need_) v = 1.0f;
    for (float& v : held_) v = 1.0f;
    look_pos_ = 0;
    release_gain_ = 1.0f;
  }

  // `feeds` is `n` frames of speaker_count() interleaved speaker feeds; `out`
  // gets `n` frames of interleaved stereo, overwritten.
  void render(const float* feeds, int n, float* out) {
    if (count_ == 0 || n <= 0) { for (int i = 0; i < 2 * n; ++i) out[i] = 0.0f; return; }
    renders_.fetch_add(1, std::memory_order_seq_cst);
    const uint32_t active = active_.load(std::memory_order_seq_cst);
    const FilterSet& set = sets_[active & 1u];
    if (active != applied_) {
      dfe_fir_.set_filter(set.dfe.data());
      applied_ = active;
    }
    reserve(n);
    const float room = room_;
    const bool dry_on = strength_ < 0.999f;

    for (int b = 0; b < n; b += kBlock) {
      const int len = n - b < kBlock ? n - b : kBlock;
      take_block(feeds + static_cast<size_t>(b) * count_, len, b, dry_on);
      if (room > 0.0f) add_reflections(len, room);
      convolve_block(set, len, wet_.data() + 2 * b);
    }
    dfe_fir_.process(wet_.data(), n);
    for (int i = 0; i < n; ++i) {
      wet_[2 * i] = biquad(set.shelf, shelf_z_, wet_[2 * i]);
      wet_[2 * i + 1] = biquad(set.shelf, shelf_z_ + 2, wet_[2 * i + 1]);
    }
    // Rung out is silent: the shelf's memory never lingers as denormals.
    for (float& z : shelf_z_) if (std::fabs(z) < 1e-25f) z = 0.0f;
    finish(n, room, dry_on, out);
    renders_.fetch_add(1, std::memory_order_release);
  }

  // ── What the tests check the render against ──────────────────────────────

  // Speaker `s`'s filter for `ear` (0 left, 1 right) from the baked set, as
  // render() uses it: kIrLen taps, the interaural delay and kSincDelay inside.
  void baked_filter(int s, int ear, float* ir) const {
    float hl[kHrirTaps], hr[kHrirTaps], dl, dr;
    lookup(&kHrirData[0][0][0][0], &kHrirDelay[0][0][0], speakers_[s].azimuth,
           speakers_[s].elevation, hl, hr, dl, dr);
    delayed_filter(ear == 0 ? hl : hr, ear == 0 ? dl : dr, ir);
  }

  // ── What a set needs put right ───────────────────────────────────────────
  // Three corrections, each worked out from the HRIR set itself so that a
  // loaded set gets its own. They allocate: loader thread, never render().

  // The diffuse-field equalizer for an HRIR set laid out as the baked table:
  // the inverse of the set's power over every direction, third-octave
  // smoothed and limited about the midrange. kDfeTaps taps, minimum phase.
  // The same steps as tools/bake_dfe.cpp, which made the baked equalizer.
  static void design_dfe(const float* data, float* dfe) {
    const int n = kDesignN, half = n / 2;
    std::vector<std::complex<double>> x(n);
    std::vector<double> power(half + 1, 0.0);
    double wsum = 0.0;
    for (int e = 0; e < kHrirElCount; ++e) {
      const double w = std::cos((kHrirElMin + e * kHrirElStep) * kPiD / 180.0);
      for (int a = 0; a < kHrirAzCount; ++a) {
        for (int ear = 0; ear < 2; ++ear) {
          const float* h = data + ((static_cast<size_t>(e) * kHrirAzCount + a) * 2 + ear) * kHrirTaps;
          for (int t = 0; t < n; ++t) x[t] = t < kHrirTaps ? h[t] : 0.0;
          design_fft(x, false);
          for (int k = 0; k <= half; ++k) power[k] += w * std::norm(x[k]);
          wsum += w;
        }
      }
    }
    for (double& p : power) p /= wsum;
    std::vector<double> gain_db(half + 1, 0.0);
    smooth_power(power, 1.0 / 6.0, gain_db);
    for (int k = 1; k <= half; ++k) gain_db[k] = -10.0 * std::log10(std::max(gain_db[k], 1e-12)) - 3.0103;
    gain_db[0] = gain_db[1];
    double ref = 0.0;
    int nref = 0;
    for (int k = 0; k <= half; ++k) {
      const double f = k * 48000.0 / n;
      if (f >= 500.0 && f <= 4000.0) { ref += gain_db[k]; ++nref; }
    }
    ref /= nref > 0 ? nref : 1;
    for (int k = 0; k <= half; ++k) {
      const double f = k * 48000.0 / n;
      const double t = std::max(0.0, std::min(1.0, (f - 10000.0) / 6000.0));
      const double max_boost = 15.0 + (6.0 - 15.0) * t;
      gain_db[k] = ref + std::max(-15.0, std::min(max_boost, gain_db[k] - ref));
    }
    min_phase_fir(gain_db, kDfeTaps, kDfeTaps / 8, dfe);
  }

  // The tone the equalizer leaves wrong, as a short filter to convolve with
  // it: the stage ahead (front_stage_gain) and the top octave
  // (top_end_gain). kToneTaps taps, minimum phase, never over kTopBoostDb.
  // `data` and `delay` as set_hrir() takes them.
  static constexpr int kToneTaps = 128;
  static constexpr double kStageAmount = 0.7;
  static constexpr double kStageBoostDb = 6.0;
  static constexpr double kStageCutDb = 3.0;
  static constexpr double kTopBoostDb = 12.0;
  static void design_tone_correction(const float* data, const float* delay, const float* dfe,
                                     float* out) {
    std::vector<double> gain_db(kDesignN / 2 + 1, 0.0);
    front_stage_gain(data, delay, dfe, gain_db);
    top_end_gain(data, dfe, gain_db);
    for (double& g : gain_db) g = std::min(g, kTopBoostDb);
    min_phase_fir(gain_db, kToneTaps, kToneTaps / 4, out);
  }

  // The low end a measured set lacks, as a low shelf {b0, b1, b2, a1, a2}:
  // the loudspeaker it was measured with had little (the baked KEMAR set is
  // 5 dB down at 60 Hz, 3 at 120), a head does nothing to sound that long,
  // and the equalizer is too short to put it all back. As much as the set,
  // through its equalizer, is quieter at 40-70 Hz than at 300-630 Hz.
  // Nothing for a set whose low end is level, never over kMaxShelfDb, never
  // a cut.
  static void design_bass_shelf(const float* data, const float* dfe, float sample_rate,
                                float* c) {
    double lo = 0.0, ref = 0.0;
    for (double hz : {40.0, 50.0, 60.0, 70.0}) lo += diffuse_level_db(data, dfe, hz) / 4.0;
    for (double hz : {300.0, 400.0, 500.0, 630.0}) ref += diffuse_level_db(data, dfe, hz) / 4.0;
    const double gain_db = std::min(ref - lo, kMaxShelfDb);
    if (gain_db < 0.25) {  // level already
      c[0] = 1.0f; c[1] = c[2] = c[3] = c[4] = 0.0f;
      return;
    }
    const double a = std::pow(10.0, gain_db / 40.0);
    const double w0 = 2.0 * kPiD * kShelfHz / sample_rate;
    const double cw = std::cos(w0), al = std::sin(w0) * 0.70710678118654752;
    const double ra = 2.0 * std::sqrt(a) * al;
    const double a0 = (a + 1.0) + (a - 1.0) * cw + ra;
    c[0] = static_cast<float>(a * ((a + 1.0) - (a - 1.0) * cw + ra) / a0);
    c[1] = static_cast<float>(2.0 * a * ((a - 1.0) - (a + 1.0) * cw) / a0);
    c[2] = static_cast<float>(a * ((a + 1.0) - (a - 1.0) * cw - ra) / a0);
    c[3] = static_cast<float>(-2.0 * ((a - 1.0) + (a + 1.0) * cw) / a0);
    c[4] = static_cast<float>(((a + 1.0) + (a - 1.0) * cw - ra) / a0);
  }

  // What render() uses for the set in use: the equalizer with the tone
  // correction folded in (kDfeTaps taps), and the low shelf after it.
  void active_equalizer(float* out) const {
    const FilterSet& set = sets_[active_.load(std::memory_order_acquire) & 1u];
    std::copy(set.dfe.begin(), set.dfe.begin() + kDfeTaps, out);
  }
  void bass_shelf(float* c) const {
    const FilterSet& set = sets_[active_.load(std::memory_order_acquire) & 1u];
    std::copy(set.shelf, set.shelf + 5, c);
  }

 private:
  static constexpr float kPiF = 3.14159265358979323846f;
  static constexpr double kPiD = 3.14159265358979323846;
  static constexpr float kRad = kPiF / 180.0f;
  static constexpr int kDesignN = 4096;
  static constexpr double kShelfHz = 120.0;
  static constexpr double kMaxShelfDb = 9.0;

  // The room. Four groups of speakers (front and back, left and right) each
  // send four early reflections out of other speakers, so they arrive
  // through those speakers' ear filters; kRefGain is at room = 1.
  static constexpr int kGroups = 4;
  static constexpr int kRefsPerGroup = 4;
  static constexpr int kGroupDelay = 2048;  // ring, a power of two
  static constexpr float kWallCutHz = 6000.0f;
  // The late tail: eight delay lines through a Householder matrix.
  static constexpr int kLines = 8;
  static constexpr float kTailSeconds = 0.32f;  // RT60
  static constexpr float kTailLowCutHz = 150.0f;
  static constexpr float kTailDamp = 0.30f;
  static constexpr float kTailGain = 0.30f;     // at room = 1; see virtual_room_test
  static constexpr int kPredelay = 576;         // 12 ms at 48 kHz
  static constexpr float kLimiterReleaseSeconds = 0.15f;

  struct FilterSet {
    std::vector<fft::Complex> spec;  // [speaker][ear][bin], scaled by 1/kFft
    std::vector<float> dfe;          // kDfeTaps
    float shelf[5] = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f};  // the low end the set lacks
  };
  struct Reflection { int delay; float gain; int speaker; };
  struct Line { std::vector<float> buf; int pos = 0; float gain = 0.0f; float damp = 0.0f; };

  static float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }
  static uint32_t next_version(uint32_t cur) { return ((cur >> 1) + 1u) << 1; }

  void reserve(int n) {
    if (static_cast<int>(wet_.size()) >= 2 * n) return;
    wet_.assign(static_cast<size_t>(2) * n, 0.0f);
    dry_.assign(static_cast<size_t>(2) * n, 0.0f);
    mono_.assign(n, 0.0f);
    lfe_in_.assign(n, 0.0f);
  }

  // ── Filters ──────────────────────────────────────────────────────────────

  // Builds into the set not in use and switches to it. A render that read the
  // old choice may still be in that set: wait for it, as hrir_renderer.h does.
  void publish(const float* data, const float* delay, const float* dfe) {
    const uint32_t cur = active_.load(std::memory_order_acquire);
    const uint32_t slot = (cur & 1u) ^ 1u;
    const uint32_t running = renders_.load(std::memory_order_seq_cst);
    if (running & 1u) {
      while (renders_.load(std::memory_order_acquire) == running) std::this_thread::yield();
    }
    build(sets_[slot], data, delay, dfe);
    active_.store(next_version(cur) | slot, std::memory_order_seq_cst);
  }

  void build(FilterSet& set, const float* data, const float* delay, const float* dfe) {
    fft::RealFft fft(kFft);
    float hl[kHrirTaps], hr[kHrirTaps], dl, dr;
    float ir[kFft];
    const float scale = 1.0f / kFft;
    for (int s = 0; s < count_; ++s) {
      fft::Complex* spec = &set.spec[static_cast<size_t>(s) * 2 * kBins];
      if (speakers_[s].lfe) {
        for (int k = 0; k < 2 * kBins; ++k) spec[k] = fft::Complex(0.0f, 0.0f);
        continue;
      }
      lookup(data, delay, speakers_[s].azimuth, speakers_[s].elevation, hl, hr, dl, dr);
      for (int ear = 0; ear < 2; ++ear) {
        delayed_filter(ear == 0 ? hl : hr, ear == 0 ? dl : dr, ir);
        for (int k = 0; k < kIrLen; ++k) ir[k] *= scale;
        for (int k = kIrLen; k < kFft; ++k) ir[k] = 0.0f;
        fft.forward(ir, spec + ear * kBins);
      }
    }
    // The equalizer, with the tone correction folded into it.
    float tone[kToneTaps];
    design_tone_correction(data, delay, dfe, tone);
    for (int n = 0; n < kDfeTaps; ++n) {
      double acc = 0.0;
      for (int k = 0; k < kToneTaps && k <= n; ++k) acc += static_cast<double>(tone[k]) * dfe[n - k];
      set.dfe[n] = static_cast<float>(acc);
    }
    design_bass_shelf(data, dfe, static_cast<float>(sample_rate_), set.shelf);
  }

  // ── Set design: the pieces ───────────────────────────────────────────────

  // The set's level at `hz` over every direction, through `dfe`, in dB.
  static double diffuse_level_db(const float* data, const float* dfe, double hz) {
    const double w = 2.0 * kPiD * hz / 48000.0;  // the table's own rate
    double power = 0.0, wsum = 0.0;
    for (int e = 0; e < kHrirElCount; ++e) {
      const double we = std::cos((kHrirElMin + e * kHrirElStep) * kPiD / 180.0);
      for (int a = 0; a < kHrirAzCount * 2; ++a) {
        const float* h = data + (static_cast<size_t>(e) * kHrirAzCount * 2 + a) * kHrirTaps;
        power += we * tone_power(h, kHrirTaps, w);
        wsum += we;
      }
    }
    return 10.0 * std::log10(std::max(power / wsum * tone_power(dfe, kDfeTaps, w), 1e-12));
  }
  static double tone_power(const float* h, int n, double w) {
    double re = 0.0, im = 0.0;
    for (int t = 0; t < n; ++t) {
      re += h[t] * std::cos(w * t);
      im -= h[t] * std::sin(w * t);
    }
    return re * re + im * im;
  }

  // `power` (bins 0..half) averaged over `octaves` either side of each bin.
  static void smooth_power(const std::vector<double>& power, double octaves,
                           std::vector<double>& out) {
    const int half = static_cast<int>(power.size()) - 1;
    std::vector<double> prefix(half + 2, 0.0);
    for (int k = 0; k <= half; ++k) prefix[k + 1] = prefix[k] + power[k];
    const double lo_r = std::pow(2.0, -octaves), hi_r = std::pow(2.0, octaves);
    out[0] = power[0];
    for (int k = 1; k <= half; ++k) {
      int lo = static_cast<int>(std::ceil(k * lo_r)), hi = static_cast<int>(std::floor(k * hi_r));
      if (lo < 1) lo = 1;
      if (hi > half) hi = half;
      out[k] = hi >= lo ? (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1) : power[k];
    }
  }

  // The minimum-phase filter with the magnitude `gain_db` (bins 0..half of
  // kDesignN), by the real cepstrum: `taps` of it, the last `fade` faded out.
  static void min_phase_fir(const std::vector<double>& gain_db, int taps, int fade, float* out) {
    const int n = kDesignN, half = n / 2;
    std::vector<std::complex<double>> x(n);
    for (int k = 0; k < n; ++k) x[k] = gain_db[k <= half ? k : n - k] / 20.0 * std::log(10.0);
    design_fft(x, true);
    for (int k = 1; k < half; ++k) { x[k] *= 2.0; x[n - k] = 0.0; }
    design_fft(x, false);
    for (auto& v : x) v = std::exp(v);
    design_fft(x, true);
    for (int t = 0; t < taps; ++t) {
      double w = 1.0;
      if (t >= taps - fade) w = 0.5 + 0.5 * std::cos(kPiD * (t - (taps - fade)) / fade);
      out[t] = static_cast<float>(x[t].real() * w);
    }
  }

  // What two speakers ahead do to a mix, added to `gain_db`. Most of a music
  // mix is ahead of the listener: the centre, sounds panned hard to one of
  // the front pair, and above all sounds panned between the pair, which
  // reach each ear from both speakers a fraction of a millisecond apart and
  // partly cancel there (about 2 kHz, and again from 5 to 10 kHz). A room's
  // walls fill those dips in; through bare ear filters the stage sounds
  // hollow, and the diffuse-field equalizer, an average over every
  // direction, does not see it. The correction: the tone of those three
  // cases through the equalizer, each against its own 250-700 Hz and
  // half-octave smoothed; kStageAmount of the inverse, between -kStageCutDb
  // and +kStageBoostDb, from 500 Hz up to where top_end_gain() takes over.
  static void front_stage_gain(const float* data, const float* delay, const float* dfe,
                               std::vector<double>& gain_db) {
    const int n = kDesignN, half = n / 2;
    float hl[kHrirTaps], hr[kHrirTaps], dl, dr, ir[kIrLen];
    std::vector<std::complex<double>> centre(n), near_side(n), far_side(n), eq(n);
    auto left_ear = [&](float az, std::vector<std::complex<double>>& x) {
      lookup(data, delay, az, 0.0f, hl, hr, dl, dr);
      delayed_filter(hl, dl, ir);
      for (int t = 0; t < n; ++t) x[t] = t < kIrLen ? ir[t] : 0.0;
      design_fft(x, false);
    };
    left_ear(0.0f, centre);
    left_ear(-30.0f, near_side);
    left_ear(30.0f, far_side);
    for (int t = 0; t < n; ++t) eq[t] = t < kDfeTaps ? dfe[t] : 0.0;
    design_fft(eq, false);

    std::vector<double> tone(half + 1, 0.0), power(half + 1), smooth(half + 1);
    auto add = [&](double weight, const std::vector<std::complex<double>>& a,
                   const std::vector<std::complex<double>>* b) {
      double ref = 0.0;
      int nref = 0;
      for (int k = 0; k <= half; ++k) {
        power[k] = std::norm((b ? a[k] + (*b)[k] : a[k]) * eq[k]);
        const double f = k * 48000.0 / n;
        if (f >= 250.0 && f <= 700.0) { ref += power[k]; ++nref; }
      }
      ref = std::max(ref / (nref > 0 ? nref : 1), 1e-12);
      smooth_power(power, 0.25, smooth);
      for (int k = 1; k <= half; ++k) tone[k] += weight * 10.0 * std::log10(std::max(smooth[k], 1e-12) / ref);
    };
    add(0.25, centre, nullptr);       // the centre speaker
    add(0.50, near_side, &far_side);  // between the pair
    add(0.25, near_side, nullptr);    // hard to one side, at the near ear
    for (int k = 1; k <= half; ++k) {
      const double f = k * 48000.0 / n;
      const double g = std::max(-kStageCutDb, std::min(kStageBoostDb, -kStageAmount * tone[k]));
      const double in = std::max(0.0, std::min(1.0, (f - 500.0) / 400.0));
      const double out = std::max(0.0, std::min(1.0, (kTopFromHz + 1000.0 - f) / 2000.0));
      gain_db[k] += g * in * out;
    }
  }

  // The top octave a measured set lacks, added to `gain_db`. The baked KEMAR
  // set (a small loudspeaker, 44.1 kHz) is, over every direction and through
  // its equalizer, 5 dB down at 15 kHz, 9 at 16 and 14 at 18, and the
  // equalizer stops correcting there, so nothing above 15 kHz came through.
  // As much as the set is under its own 8-13 kHz at each kHz from 14
  // to 20, a line between those, held to 20.5 kHz and gone by 22 (there is
  // nothing above to bring back). Never a cut, never over kTopBoostDb.
  static constexpr double kTopFromHz = 13000.0;
  static constexpr int kTopProbes = 7;
  static void top_end_gain(const float* data, const float* dfe, std::vector<double>& gain_db) {
    double ref = 0.0;
    for (double hz : {8000.0, 10000.0, 12000.0, 13000.0}) ref += diffuse_level_db(data, dfe, hz) / 4.0;
    double lack[kTopProbes + 1] = {0.0};  // at kTopFromHz, then each kHz above it
    for (int i = 1; i <= kTopProbes; ++i) {
      const double d = ref - diffuse_level_db(data, dfe, kTopFromHz + 1000.0 * i);
      lack[i] = std::max(0.0, std::min(kTopBoostDb, d));
    }
    const int half = static_cast<int>(gain_db.size()) - 1;
    for (int k = 1; k <= half; ++k) {
      const double f = k * 48000.0 / kDesignN;
      if (f <= kTopFromHz) continue;
      const double at = std::min((f - kTopFromHz) / 1000.0, static_cast<double>(kTopProbes));
      const int i = at < kTopProbes ? static_cast<int>(at) : kTopProbes - 1;
      const double g = lack[i] + (lack[i + 1] - lack[i]) * (at - i);
      gain_db[k] += g * std::max(0.0, std::min(1.0, (22000.0 - f) / 1500.0));
    }
  }


  // The HRIR pair for a direction, bilinear across the grid, and the two
  // onset delays: BinauralRenderer::lookup_hrir, for a set given by pointer.
  static void lookup(const float* data, const float* delay, float az_deg, float el_deg,
                     float* hl, float* hr, float& dl, float& dr) {
    az_deg = std::fmod(az_deg, 360.0f);
    if (az_deg < 0.0f) az_deg += 360.0f;
    const float af = az_deg / kHrirAzStep;
    const int a0 = static_cast<int>(af) % kHrirAzCount;
    const float fa = af - std::floor(af);
    const int a1 = (a0 + 1) % kHrirAzCount;
    const float el_max = kHrirElMin + (kHrirElCount - 1) * kHrirElStep;
    if (el_deg < kHrirElMin) el_deg = kHrirElMin;
    if (el_deg > el_max) el_deg = el_max;
    const float ef = (el_deg - kHrirElMin) / kHrirElStep;
    int e0 = static_cast<int>(ef);
    if (e0 > kHrirElCount - 1) e0 = kHrirElCount - 1;
    const int e1 = e0 + 1 < kHrirElCount ? e0 + 1 : e0;
    const float fe = ef - static_cast<float>(e0);
    const float w[4] = {(1.0f - fa) * (1.0f - fe), fa * (1.0f - fe), (1.0f - fa) * fe, fa * fe};
    const int es[4] = {e0, e0, e1, e1}, as[4] = {a0, a1, a0, a1};
    for (int k = 0; k < kHrirTaps; ++k) hl[k] = hr[k] = 0.0f;
    dl = dr = 0.0f;
    for (int c = 0; c < 4; ++c) {
      const size_t at = (static_cast<size_t>(es[c]) * kHrirAzCount + as[c]) * 2;
      const float* l = data + at * kHrirTaps;
      const float* r = l + kHrirTaps;
      for (int k = 0; k < kHrirTaps; ++k) { hl[k] += w[c] * l[k]; hr[k] += w[c] * r[k]; }
      dl += w[c] * delay[at];
      dr += w[c] * delay[at + 1];
    }
  }

  // `h` (kHrirTaps, onset at tap 0) delayed by `delay` + kSincDelay samples
  // into `ir` (kIrLen): convolved with a Hann-windowed sinc centred on the
  // fraction, at unity gain at DC.
  static void delayed_filter(const float* h, float delay, float* ir) {
    if (!(delay > 0.0f)) delay = 0.0f;
    if (delay > kMaxItd) delay = static_cast<float>(kMaxItd);
    const int whole = static_cast<int>(delay);
    const double frac = static_cast<double>(delay) - whole;
    double taps[kSincTaps], sum = 0.0;
    for (int j = 0; j < kSincTaps; ++j) {
      const double x = j - kSincDelay - frac;
      const double sinc = std::fabs(x) < 1e-9 ? 1.0 : std::sin(kPiD * x) / (kPiD * x);
      const double span = kSincTaps / 2.0;
      const double win = std::fabs(x) >= span ? 0.0 : 0.5 + 0.5 * std::cos(kPiD * x / span);
      taps[j] = sinc * win;
      sum += taps[j];
    }
    for (int k = 0; k < kIrLen; ++k) ir[k] = 0.0f;
    for (int j = 0; j < kSincTaps; ++j) {
      const float t = static_cast<float>(taps[j] / sum);
      float* dst = ir + whole + j;
      for (int k = 0; k < kHrirTaps; ++k) dst[k] += t * h[k];
    }
  }

  static void design_fft(std::vector<std::complex<double>>& a, bool inverse) {
    const int n = static_cast<int>(a.size());
    for (int i = 1, j = 0; i < n; ++i) {
      int bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) std::swap(a[i], a[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
      const double ang = 2.0 * kPiD / len * (inverse ? 1 : -1);
      const std::complex<double> wl(std::cos(ang), std::sin(ang));
      for (int i = 0; i < n; i += len) {
        std::complex<double> w(1.0);
        for (int k = 0; k < len / 2; ++k) {
          const std::complex<double> u = a[i + k], v = a[i + k + len / 2] * w;
          a[i + k] = u + v;
          a[i + k + len / 2] = u - v;
          w *= wl;
        }
      }
    }
    if (inverse) for (auto& x : a) x /= n;
  }

  // Butterworth biquads {b0, b1, b2, a1, a2}; two in a row are an LR4 band.
  void design_lowpass(float hz, float* c) const {
    const float w0 = 2.0f * kPiF * hz / static_cast<float>(sample_rate_);
    const float cw = std::cos(w0), alpha = std::sin(w0) * 0.70710678f, a0 = 1.0f + alpha;
    c[0] = 0.5f * (1.0f - cw) / a0;
    c[1] = (1.0f - cw) / a0;
    c[2] = c[0];
    c[3] = -2.0f * cw / a0;
    c[4] = (1.0f - alpha) / a0;
  }
  void design_highpass(float hz, float* c) const {
    const float w0 = 2.0f * kPiF * hz / static_cast<float>(sample_rate_);
    const float cw = std::cos(w0), alpha = std::sin(w0) * 0.70710678f, a0 = 1.0f + alpha;
    c[0] = 0.5f * (1.0f + cw) / a0;
    c[1] = -(1.0f + cw) / a0;
    c[2] = c[0];
    c[3] = -2.0f * cw / a0;
    c[4] = (1.0f - alpha) / a0;
  }
  static float biquad(const float* c, float* z, float x) {
    const float y = c[0] * x + z[0];
    z[0] = c[1] * x - c[3] * y + z[1];
    z[1] = c[2] * x - c[4] * y;
    return y;
  }

  // ── The room ─────────────────────────────────────────────────────────────

  int nearest_speaker(float az, float el) const {
    int best = -1;
    float best_dot = -2.0f;
    const float a = az * kRad, e = el * kRad;
    const float x = std::cos(e) * std::sin(a), y = std::cos(e) * std::cos(a), z = std::sin(e);
    for (int s = 0; s < count_; ++s) {
      if (speakers_[s].lfe) continue;
      const float sa = speakers_[s].azimuth * kRad, se = speakers_[s].elevation * kRad;
      const float d = x * std::cos(se) * std::sin(sa) + y * std::cos(se) * std::cos(sa) + z * std::sin(se);
      if (d > best_dot) { best_dot = d; best = s; }
    }
    return best;
  }

  // Which group a speaker's sound belongs to, as two weights (a speaker dead
  // ahead or behind is in both sides' groups equally).
  void configure_reflections() {
    group_w_.assign(static_cast<size_t>(count_) * kGroups, 0.0f);
    for (int s = 0; s < count_; ++s) {
      if (speakers_[s].lfe) continue;
      float az = std::fmod(speakers_[s].azimuth, 360.0f);
      if (az > 180.0f) az -= 360.0f;
      if (az < -180.0f) az += 360.0f;
      const int back = std::fabs(az) >= 100.0f ? 2 : 0;
      const bool mid = std::fabs(az) < 1.0f || std::fabs(az) > 179.0f;
      float* w = &group_w_[static_cast<size_t>(s) * kGroups];
      if (mid) { w[back] = 0.70710678f; w[back + 1] = 0.70710678f; }
      else w[back + (az > 0.0f ? 1 : 0)] = 1.0f;
    }
    // A small treated room, a couple of metres to each wall: the near side
    // wall first, the ceiling, the far side wall, then the wall behind (for
    // the front groups) or ahead (for the back ones). Milliseconds, gain at
    // room = 1, and where it comes from for the LEFT group; the right group
    // mirrors it.
    struct Shape { float ms; float gain; float az; float el; };
    static const Shape front[kRefsPerGroup] = {
        {6.1f, 0.355f, -90.0f, 0.0f}, {8.3f, 0.282f, -45.0f, 60.0f},
        {11.3f, 0.251f, 90.0f, 0.0f}, {17.9f, 0.200f, -160.0f, 0.0f}};
    static const Shape back[kRefsPerGroup] = {
        {5.7f, 0.355f, -90.0f, 0.0f}, {8.9f, 0.282f, -135.0f, 60.0f},
        {12.7f, 0.251f, 110.0f, 0.0f}, {16.1f, 0.200f, -30.0f, 0.0f}};
    for (int g = 0; g < kGroups; ++g) {
      const Shape* shape = g < 2 ? front : back;
      const float side = (g & 1) ? -1.0f : 1.0f;  // the right group mirrors
      for (int r = 0; r < kRefsPerGroup; ++r) {
        int delay = static_cast<int>(shape[r].ms * 0.001f * sample_rate_ + 0.5f);
        if (delay < 1) delay = 1;
        if (delay > kGroupDelay - kBlock - 1) delay = kGroupDelay - kBlock - 1;
        refs_[g][r] = {delay, shape[r].gain, nearest_speaker(shape[r].az * side, shape[r].el)};
      }
    }
    for (auto& d : group_delay_) d.assign(kGroupDelay, 0.0f);
    group_pos_ = 0;
    wall_coef_ = 1.0f - std::exp(-2.0f * kPiF * kWallCutHz / static_cast<float>(sample_rate_));
  }

  void configure_tail() {
    // Mutually prime lengths, 15 to 41 ms at 48 kHz, scaled to the rate.
    static const int lengths[kLines] = {743, 907, 1097, 1259, 1433, 1613, 1789, 1951};
    for (int i = 0; i < kLines; ++i) {
      int len = static_cast<int>(lengths[i] * (sample_rate_ / 48000.0) + 0.5);
      if (len < 8) len = 8;
      lines_[i].buf.assign(len, 0.0f);
      lines_[i].pos = 0;
      lines_[i].damp = 0.0f;
      // -60 dB after kTailSeconds of going round this line.
      lines_[i].gain = std::pow(10.0f, -3.0f * len / (kTailSeconds * sample_rate_));
    }
    int pre = static_cast<int>(kPredelay * (sample_rate_ / 48000.0) + 0.5);
    predelay_.assign(pre < 1 ? 1 : pre, 0.0f);
    predelay_pos_ = 0;
    release_coef_ = 1.0f - std::exp(-1.0f / (kLimiterReleaseSeconds * sample_rate_));
  }

  // ── A block ──────────────────────────────────────────────────────────────

  // Deinterleaves one block into in_, and takes what does not go through the
  // ear filters off it: the sum of the speakers, the LFE, and the dry pan.
  void take_block(const float* feeds, int len, int at, bool dry_on) {
    for (int i = 0; i < len; ++i) {
      mono_[at + i] = 0.0f;
      lfe_in_[at + i] = 0.0f;
      dry_[2 * (at + i)] = 0.0f;
      dry_[2 * (at + i) + 1] = 0.0f;
    }
    for (int s = 0; s < count_; ++s) {
      float* dst = &in_[static_cast<size_t>(s) * kBlock];
      const float* src = feeds + s;
      float peak = 0.0f;
      for (int i = 0; i < len; ++i) {
        const float v = src[static_cast<size_t>(i) * count_];
        dst[i] = v;
        const float a = v < 0.0f ? -v : v;
        if (a > peak) peak = a;
      }
      for (int i = len; i < kBlock; ++i) dst[i] = 0.0f;
      loud_[s] = peak > 0.0f;
      if (!loud_[s]) continue;
      if (s == lfe_) {
        for (int i = 0; i < len; ++i) lfe_in_[at + i] = dst[i];
        continue;
      }
      for (int i = 0; i < len; ++i) mono_[at + i] += dst[i];
      if (dry_on) {
        const float gl = pan_l_[s], gr = pan_r_[s];
        for (int i = 0; i < len; ++i) {
          dry_[2 * (at + i)] += gl * dst[i];
          dry_[2 * (at + i) + 1] += gr * dst[i];
        }
      }
    }
  }

  // Each group's sound, off the walls a little later and out of other speakers.
  void add_reflections(int len, float room) {
    float group[kGroups][kBlock];
    for (int g = 0; g < kGroups; ++g) for (int i = 0; i < len; ++i) group[g][i] = 0.0f;
    for (int s = 0; s < count_; ++s) {
      if (!loud_[s] || s == lfe_) continue;
      const float* w = &group_w_[static_cast<size_t>(s) * kGroups];
      const float* src = &in_[static_cast<size_t>(s) * kBlock];
      for (int g = 0; g < kGroups; ++g) {
        if (w[g] == 0.0f) continue;
        for (int i = 0; i < len; ++i) group[g][i] += w[g] * src[i];
      }
    }
    // Into the rings (walls take some treble off), then out at each delay.
    for (int g = 0; g < kGroups; ++g) {
      float lp = group_lp_[g];
      float* ring = group_delay_[g].data();
      for (int i = 0; i < len; ++i) {
        lp += wall_coef_ * (group[g][i] - lp);
        ring[(group_pos_ + i) & (kGroupDelay - 1)] = lp;
      }
      group_lp_[g] = lp;
      for (int r = 0; r < kRefsPerGroup; ++r) {
        const Reflection& ref = refs_[g][r];
        if (ref.speaker < 0) continue;
        const float gain = ref.gain * room;
        float* dst = &in_[static_cast<size_t>(ref.speaker) * kBlock];
        bool any = false;
        for (int i = 0; i < len; ++i) {
          const float v = ring[(group_pos_ + i - ref.delay) & (kGroupDelay - 1)];
          if (v != 0.0f) { dst[i] += gain * v; any = true; }
        }
        if (any) loud_[ref.speaker] = true;
      }
    }
    group_pos_ = (group_pos_ + len) & (kGroupDelay - 1);
  }

  // The block's speakers through their filters, summed as spectra, back to
  // time once an ear: `len` frames of interleaved stereo into `wet`.
  void convolve_block(const FilterSet& set, int len, float* wet) {
    for (int k = 0; k < kBins; ++k) acc_l_[k] = acc_r_[k] = fft::Complex(0.0f, 0.0f);
    bool any = false;
    for (int s = 0; s < count_; ++s) {
      if (s == lfe_) continue;
      float* hist = &hist_[static_cast<size_t>(s) * kHist];
      const float* in = &in_[static_cast<size_t>(s) * kBlock];
      // A speaker silent for a whole window adds nothing: skip its transform.
      if (!loud_[s]) {
        if (quiet_[s] >= kHist) continue;
        quiet_[s] += len;
      } else {
        quiet_[s] = 0;
      }
      std::memcpy(win_, hist, sizeof(float) * kHist);
      std::memcpy(win_ + kHist, in, sizeof(float) * kBlock);  // zero past len
      fft_.forward(win_, xs_);
      const fft::Complex* hl = &set.spec[static_cast<size_t>(s) * 2 * kBins];
      const fft::Complex* hr = hl + kBins;
      for (int k = 0; k < kBins; ++k) {
        acc_l_[k] += fft::mul(xs_[k], hl[k]);
        acc_r_[k] += fft::mul(xs_[k], hr[k]);
      }
      any = true;
      // The window slides by the frames that came in.
      if (len == kHist) {
        std::memcpy(hist, in, sizeof(float) * kHist);
      } else {
        std::memmove(hist, hist + len, sizeof(float) * (kHist - len));
        std::memcpy(hist + kHist - len, in, sizeof(float) * len);
      }
    }
    if (!any) {
      for (int i = 0; i < 2 * len; ++i) wet[i] = 0.0f;
      return;
    }
    fft_.inverse(acc_l_, yl_);
    fft_.inverse(acc_r_, yr_);
    for (int i = 0; i < len; ++i) {
      wet[2 * i] = yl_[kHist + i];
      wet[2 * i + 1] = yr_[kHist + i];
    }
  }

  // Everything after the ear filters, a sample at a time.
  void finish(int n, float room, bool dry_on, float* out) {
    const float wet_g = strength_, dry_g = 1.0f - strength_;
    const float tail_g = kTailGain * room;
    const bool tail_on = room > 0.0f;
    for (int i = 0; i < n; ++i) {
      // What went round the ear filters waits kSincDelay for what went through.
      float* slot = align_[align_pos_];
      const float mono = slot[0], lfe = slot[1], dl = slot[2], dr = slot[3];
      slot[0] = mono_[i];
      slot[1] = lfe_in_[i];
      slot[2] = dry_on ? dry_[2 * i] : 0.0f;
      slot[3] = dry_on ? dry_[2 * i + 1] : 0.0f;
      if (++align_pos_ == kSincDelay) align_pos_ = 0;

      float l = wet_g * wet_[2 * i] + dry_g * dl;
      float r = wet_g * wet_[2 * i + 1] + dry_g * dr;
      if (bass_) {
        l = biquad(hp_c_, z_ + 2, biquad(hp_c_, z_ + 0, l));
        r = biquad(hp_c_, z_ + 6, biquad(hp_c_, z_ + 4, r));
        const float low = biquad(lp_c_, z_ + 10, biquad(lp_c_, z_ + 8, mono));
        l += low;
        r += low;
      }
      const float sub = kLfeGain * biquad(lfe_c_, z_ + 14, biquad(lfe_c_, z_ + 12, lfe));
      l += sub;
      r += sub;
      if (tail_on) {
        float tl, tr;
        tail(biquad(tail_hp_c_, z_ + 16, mono), tl, tr);
        l += tail_g * tl;
        r += tail_g * tr;
      }
      limit(l * kTrim, r * kTrim, out[2 * i], out[2 * i + 1]);
    }
  }

  // One sample of the late tail: in after the predelay, out decorrelated.
  void tail(float in, float& l, float& r) {
    const float fed = predelay_[predelay_pos_];
    predelay_[predelay_pos_] = in;
    if (++predelay_pos_ == static_cast<int>(predelay_.size())) predelay_pos_ = 0;
    float read[kLines], sum = 0.0f;
    for (int i = 0; i < kLines; ++i) {
      Line& line = lines_[i];
      // Each trip round loses level, and treble faster than bass.
      line.damp += (1.0f - kTailDamp) * (line.buf[line.pos] - line.damp);
      read[i] = line.damp * line.gain;
      sum += read[i];
    }
    sum *= 2.0f / kLines;
    l = r = 0.0f;
    for (int i = 0; i < kLines; ++i) {
      Line& line = lines_[i];
      const float sign = (i & 2) ? -1.0f : 1.0f;
      line.buf[line.pos] = read[i] - sum + sign * fed;
      if (++line.pos == static_cast<int>(line.buf.size())) line.pos = 0;
      // Each ear hears its own four lines.
      if (i & 1) r += sign * read[i];
      else l += sign * read[i];
    }
    l *= 0.5f;
    r *= 0.5f;
  }

  // Look-ahead peak limiter, both ears on one gain. The gain a sample needs
  // is held at its smallest over kLook samples, let back up slowly, and
  // averaged over kLook, so the output never passes kCeiling and the gain
  // never steps.
  void limit(float l, float r, float& out_l, float& out_r) {
    const float al = l < 0.0f ? -l : l, ar = r < 0.0f ? -r : r;
    const float peak = al > ar ? al : ar;
    const int p = look_pos_;
    need_[p] = peak > kCeiling ? kCeiling / peak : 1.0f;
    float low = 1.0f;
    for (int k = 0; k < kLook; ++k) if (need_[k] < low) low = need_[k];
    release_gain_ += release_coef_ * (1.0f - release_gain_);
    if (low < release_gain_) release_gain_ = low;
    held_[p] = release_gain_;
    // A running sum would drift; the window is short enough to add up.
    float sum = 0.0f;
    for (int k = 0; k < kLook; ++k) sum += held_[k];
    const float mean = sum * (1.0f / kLook);
    const float gain = mean < 1.0f ? mean : 1.0f;
    look_l_[p] = l;
    look_r_[p] = r;
    // The next slot holds the sample from kLookahead ago: every gain in the
    // average was held down for it.
    const int at = p + 1 == kLook ? 0 : p + 1;
    out_l = look_l_[at] * gain;
    out_r = look_r_[at] * gain;
    look_pos_ = at;
  }

  int sample_rate_ = 48000;
  int count_ = 0;
  int lfe_ = -1;
  std::vector<RoomSpeaker> speakers_;
  float strength_ = 1.0f;
  float room_ = 0.0f;
  bool bass_ = false;
  int crossover_hz_ = 80;

  // Filters: two sets, the published one's slot in bit 0 and a version above.
  FilterSet sets_[2];
  std::atomic<uint32_t> active_{0};
  std::atomic<uint32_t> renders_{0};
  uint32_t applied_ = ~0u;  // the set the equalizer was last given (audio thread)

  // Convolution state and scratch.
  std::vector<float> hist_;   // [speaker][kHist]
  std::vector<float> in_;     // [speaker][kBlock], this block
  std::vector<int> quiet_;    // frames of silence behind each speaker
  bool loud_[kMaxSpeakers] = {};
  fft::RealFft fft_{kFft};
  float win_[kFft] = {};
  float yl_[kFft] = {}, yr_[kFft] = {};
  fft::Complex xs_[kBins], acc_l_[kBins], acc_r_[kBins];
  std::vector<float> wet_, dry_, mono_, lfe_in_;
  std::vector<float> pan_l_, pan_r_;
  StereoPartitionedFir<kDfeTaps> dfe_fir_;

  // The room.
  std::vector<float> group_w_;  // [speaker][group]
  Reflection refs_[kGroups][kRefsPerGroup] = {};
  std::vector<float> group_delay_[kGroups];
  int group_pos_ = 0;
  float group_lp_[kGroups] = {};
  float wall_coef_ = 1.0f;
  Line lines_[kLines];
  std::vector<float> predelay_;
  int predelay_pos_ = 0;

  // Biquad coefficients and their states, two floats each: the stereo
  // high-pass (0-7), the bass (8-11), the LFE (12-15), the tail's low cut (16).
  float lp_c_[5] = {}, hp_c_[5] = {}, lfe_c_[5] = {}, tail_hp_c_[5] = {};
  float z_[18] = {};
  float shelf_z_[4] = {};
  // mono, LFE and the dry pair, kSincDelay samples behind.
  float align_[kSincDelay][4] = {};
  int align_pos_ = 0;

  // The limiter.
  float look_l_[kLook] = {}, look_r_[kLook] = {};
  float need_[kLook], held_[kLook];
  int look_pos_ = 0;
  float release_gain_ = 1.0f;
  float release_coef_ = 0.0f;
};

}  // namespace render
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_VIRTUAL_ROOM_H
