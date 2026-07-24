// End-to-end native Atmos render pipeline (plan Option B2, P1 -> P2 glue).
//
// Chains the object reconstruction (ObjectEngine) and the binaural render
// (BinauralRenderer): a raw E-AC-3 frame + its decoded core bed PCM go in, and
// binaural stereo comes out. Each object's OAMD render-space position is mapped
// to an azimuth/elevation for the HRTF renderer. This is the single native call
// the Media3 AtmosAudioProcessor (P4) invokes per frame.
#ifndef TF_ATMOS_ATMOS_PIPELINE_H
#define TF_ATMOS_ATMOS_PIPELINE_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "cavern/enhanced_ac3.h"  // dialnorm from the syncframe header
#include "object_engine.h"
#include "render/hrir_renderer.h"
#include "render/sofa_loader.h"  // runtime SOFA HRTF (defined only in the JNI lib)
#include "vbap.h"  // Vec3

namespace tf {
namespace atmos {

// Maps a render-space position (x = left..right, y = down..up, z = back..front)
// to azimuth (0 = front, +right) and elevation (0 = ear level, +up), in radians.
inline void position_to_azel(const Vec3& v, float& azimuth, float& elevation) {
  azimuth = std::atan2(v.x, v.z);
  const float horiz = std::sqrt(v.x * v.x + v.z * v.z);
  elevation = std::atan2(v.y, horiz);
}

// Mirrors the Kotlin RendererMode / StereoDownmixMode ordinals so the profile
// can be pushed across JNI as plain ints.
enum RenderMode { kPassthrough = 0, kObjectRender = 1, kBedHrtf = 2 };
enum DownmixMode { kBinaural = 0, kLoRo = 1, kLtRt = 2 };

class AtmosPipeline {
 public:
  void configure(int sample_rate, int max_objects) {
    max_objects_ = max_objects < 1 ? 1 : max_objects;
    // +8 slots so the bed-HRTF path can also render bed channels as sources.
    renderer_.configure(sample_rate, max_objects_ + 8);
    dm_delay_l_.assign(kQmfLatency, 0.0f);
    dm_delay_r_.assign(kQmfLatency, 0.0f);
    dm_idx_ = 0;
    last_path_ = kPathNone;
  }

  // Clears all time history (downmix delay, renderer tails, DRC envelope, path
  // memory) without reallocating — audio-thread-safe. Called on seek/flush;
  // without it the first ~12 ms after a seek would replay pre-seek audio out
  // of the latency-matching delay line.
  void flush_state() {
    std::fill(dm_delay_l_.begin(), dm_delay_l_.end(), 0.0f);
    std::fill(dm_delay_r_.begin(), dm_delay_r_.end(), 0.0f);
    dm_idx_ = 0;
    last_path_ = kPathNone;
    drc_env_ = 0.0f;
    renderer_.reset_history();
  }

  // Applies the user's RendererProfile. `lfe_gain_db` currently affects the
  // bed-HRTF path (in object render the objects carry their own OAMD gains).
  void set_params(int mode, int downmix, float binaural_strength,
                  bool height_virtualization, float lfe_gain_db,
                  bool bass_management, int crossover_hz, int drc_mode,
                  bool dialog_normalization) {
    mode_ = mode;
    downmix_ = downmix;
    lfe_gain_ = std::pow(10.0f, lfe_gain_db / 20.0f);
    drc_mode_ = drc_mode;
    dialog_norm_ = dialog_normalization;
    renderer_.set_params(binaural_strength, height_virtualization);
    renderer_.set_bass_management(bass_management, crossover_hz);
  }

  // Installs a runtime HRTF from a SOFA buffer (off the audio thread), or
  // reverts to the baked default. The load itself is defined in the JNI lib.
  bool load_sofa(const char* data, long size) {
    return load_sofa_into_renderer(renderer_, data, size);
  }
  void clear_sofa() { renderer_.clear_runtime_hrir(); }

  // ── Audio-thread-safe marshaling scratch ─────────────────────────────────
  // The JNI layer runs on the audio thread, so it must not allocate per call.
  // These grow once and are reused; the interleaved entry point deinterleaves
  // into reusable planar storage instead of building vectors every frame.
  std::vector<uint8_t>& frame_scratch(size_t n) {
    if (frame_scratch_.size() < n) frame_scratch_.resize(n);
    return frame_scratch_;
  }
  std::vector<float>& bed_scratch(size_t n) {
    if (bed_scratch_.size() < n) bed_scratch_.resize(n);
    return bed_scratch_;
  }
  std::vector<float>& stereo_scratch(size_t n) {
    if (stereo_scratch_.size() < n) stereo_scratch_.resize(n);
    return stereo_scratch_;
  }

  // Interleaved bed in, interleaved stereo out. Same contract as process_frame.
  int process_frame_interleaved(const uint8_t* frame, size_t frame_size,
                                const float* bed_interleaved, int channels,
                                int samples, float* out_stereo) {
    if (static_cast<int>(planar_.size()) != channels) {
      planar_.assign(static_cast<size_t>(channels), std::vector<float>());
    }
    planar_ptrs_.resize(static_cast<size_t>(channels));
    for (int c = 0; c < channels; ++c) {
      if (static_cast<int>(planar_[c].size()) < samples) planar_[c].resize(samples);
      float* dst = planar_[c].data();
      for (int i = 0; i < samples; ++i) {
        dst[i] = bed_interleaved[static_cast<size_t>(i) * channels + c];
      }
      planar_ptrs_[c] = dst;
    }
    return process_frame(frame, frame_size, planar_ptrs_.data(), channels, samples,
                         out_stereo);
  }

  // Renders one E-AC-3 frame to interleaved stereo (`out` holds 2*samples
  // floats). Returns 1 whenever the binaural render is active: the output is
  // the object render or, for frames without usable JOC, an ITU downmix
  // delayed by the QMF round-trip so both paths are sample-aligned — a path
  // switch is then an equal-power crossfade instead of a cut that also jumps
  // ~12 ms in time. Returns -1 only when the caller's own fold-down should be
  // used (passthrough mode / LoRo / LtRt). `bed` is [bed_channels][samples]
  // deinterleaved float (the decoder's channel order).
  int process_frame(const uint8_t* frame, size_t frame_size, const float* const* bed,
                    int bed_channels, int samples, float* out) {
    // Passthrough / non-binaural fold-downs are the caller's job (it already
    // has an ITU downmix); returning -1 selects that path.
    if (mode_ == kPassthrough || downmix_ != kBinaural) return -1;
    // One BSI parse serves both dialogue normalization (the stream's own
    // dialnorm) and RF-mode DRC (the stream's own compr gain word).
    dialnorm_gain_ = 1.0f;
    frame_has_compr_ = false;
    if (dialog_norm_ || drc_mode_ == 3) parse_bsi(frame, frame_size);
    if (mode_ == kBedHrtf) {
      const int rc = render_bed(bed, bed_channels, samples, out);
      if (rc == 1) apply_post(out, samples);
      return rc;
    }
    if (bed_channels <= 0 || samples <= 0) return -1;

    // The latency-matched downmix runs every frame, rendered or not: it is the
    // fallback signal, and its delay line must stay primed with real audio so
    // a render→downmix switch always has aligned history to fade into.
    if (static_cast<int>(dm_.size()) < 2 * samples) dm_.assign(2 * samples, 0.0f);
    render_downmix(bed, bed_channels, samples, dm_.data());

    const int n = engine_.upmix_frame(frame, frame_size, bed, bed_channels, samples);
    const int want = n > 0 ? kPathRender : kPathDownmix;
    if (want == kPathRender) render_objects(n, samples, out);

    if (last_path_ == kPathNone) last_path_ = want;
    if (want != last_path_) {
      if (want == kPathDownmix) {
        // The engine still holds the last JOC/OAMD past its expiry; render one
        // fade-out frame from it so the switch is a crossfade, not a cut.
        const int m = engine_.upmix_held(bed, bed_channels, samples);
        if (m > 0) {
          render_objects(m, samples, out);
          crossfade(out, dm_.data(), samples, out);
        } else {
          std::copy(dm_.data(), dm_.data() + 2 * samples, out);
        }
      } else {
        crossfade(dm_.data(), out, samples, out);
      }
      last_path_ = want;
    } else if (want == kPathDownmix) {
      std::copy(dm_.data(), dm_.data() + 2 * samples, out);
    }
    apply_post(out, samples);
    return 1;
  }

 private:
  static constexpr int kMaxBedCh = 8;
  // The object render's bed passes through QMF analysis+synthesis, which the
  // fallback downmix does not. MEASURED round-trip: cavern_qmf_test's best-lag
  // cross-correlation reports 577 samples (not the textbook 640-64=576) — the
  // fallback is delayed by the same amount so the paths stay sample-aligned.
  static constexpr int kQmfLatency = 577;
  static constexpr float kHalfPi = 1.57079632679f;
  enum { kPathNone = 0, kPathRender = 1, kPathDownmix = 2 };

  // The object render (JOC objects -> HRIR binaural + diffuse LFE) for the
  // engine's current upmix. `object_count` is the engine's return value.
  void render_objects(int object_count, int samples, float* out) {
    const int objects = object_count < max_objects_ ? object_count : max_objects_;

    // Apply each object's OAMD gain (dialog/ambience trims mastered into the
    // stream) to the reconstructed PCM, ramped from the previously applied
    // value. A negative gain is OAMD's "hold" sentinel — keep the last one.
    if (static_cast<int>(obj_gain_.size()) < objects) obj_gain_.resize(objects, 1.0f);
    for (int o = 0; o < objects; ++o) {
      const float g = engine_.object_gain(o);
      const float g1 = g >= 0.0f ? g : obj_gain_[o];
      engine_.scale_object(o, obj_gain_[o], g1);
      obj_gain_[o] = g1;
    }

    // The LFE is non-directional bass. Spatializing it through the HRIR places
    // it at a point (and, with no OAMD position, at the front-left origin corner
    // — where it was the loudest, most obviously mislocalized object). Pull it
    // out of the HRIR set and sum it to both ears below instead.
    const int lfe = engine_.lfe_object_index();

    obj_ptrs_.clear();
    az_.clear();
    el_.clear();
    for (int o = 0; o < objects; ++o) {
      if (o == lfe) continue;
      float az, el;
      position_to_azel(engine_.object_position(o), az, el);
      obj_ptrs_.push_back(engine_.object_channel(o));
      az_.push_back(az);
      el_.push_back(el);
    }
    const int rendered_objects = static_cast<int>(obj_ptrs_.size());
    renderer_.render(obj_ptrs_.data(), az_.data(), el_.data(), rendered_objects,
                     nullptr, nullptr, nullptr, 0, samples, out);

    // Sum the non-directional LFE equally to both ears (with the profile's LFE
    // trim), after the HRIR render and before post. Bass management in the
    // renderer already folds low frequencies of the spatialized objects to both
    // ears; the LFE is bass by definition, so it just goes straight through.
    if (lfe >= 0) {
      const float* lp = engine_.object_channel(lfe);
      if (lp) {
        for (int i = 0; i < samples; ++i) {
          const float v = lp[i] * lfe_gain_;
          out[2 * i] += v;
          out[2 * i + 1] += v;
        }
      }
    }
  }

  // ITU-style fold-down (FL FR FC LFE SL SR; the LFE is omitted per BS.775),
  // pushed through the kQmfLatency delay line. Matches the Kotlin fallback's
  // coefficients so pipeline-off and pipeline-on fallbacks sound identical.
  void render_downmix(const float* const* bed, int ch, int samples, float* out) {
    for (int i = 0; i < samples; ++i) {
      float l, r;
      if (ch >= 6) {
        const float fc = 0.707f * bed[2][i];
        l = bed[0][i] + fc + 0.707f * bed[4][i];
        r = bed[1][i] + fc + 0.707f * bed[5][i];
      } else {
        l = bed[0][i];
        r = ch > 1 ? bed[1][i] : bed[0][i];
      }
      out[2 * i] = dm_delay_l_[dm_idx_];
      out[2 * i + 1] = dm_delay_r_[dm_idx_];
      dm_delay_l_[dm_idx_] = l;
      dm_delay_r_[dm_idx_] = r;
      if (++dm_idx_ == kQmfLatency) dm_idx_ = 0;
    }
  }

  // Equal-power fade from `from` into `to` across the frame. `out` may alias
  // either input — each index is read before it is written.
  void crossfade(const float* from, const float* to, int samples, float* out) {
    for (int i = 0; i < samples; ++i) {
      const float t = (i + 0.5f) / static_cast<float>(samples);
      const float wf = std::cos(t * kHalfPi);
      const float wt = std::sin(t * kHalfPi);
      out[2 * i] = wf * from[2 * i] + wt * to[2 * i];
      out[2 * i + 1] = wf * from[2 * i + 1] + wt * to[2 * i + 1];
    }
  }

  // Canonical speaker azimuths (radians) for the decoder's channel order
  // FL FR FC LFE SL SR BL BR. Used by the bed-HRTF mode, which spatializes the
  // core bed directly instead of reconstructing objects.
  static constexpr float kBedAzimuth[kMaxBedCh] = {
      -0.5236f, 0.5236f, 0.0f, 0.0f, -1.9199f, 1.9199f, -2.6180f, 2.6180f};

  // Spatializes the decoded bed channels at their speaker positions (no JOC).
  int render_bed(const float* const* bed, int bed_channels, int samples, float* out) {
    const int ch = bed_channels > kMaxBedCh ? kMaxBedCh : bed_channels;
    if (ch <= 0) return -1;
    bed_ptrs_.resize(ch);
    az_.resize(ch);
    el_.resize(ch);
    for (int c = 0; c < ch; ++c) {
      az_[c] = kBedAzimuth[c];
      el_[c] = 0.0f;
      if (c == 3 && lfe_gain_ != 1.0f) {  // LFE trim from the profile
        lfe_scratch_.resize(samples);
        for (int i = 0; i < samples; ++i) lfe_scratch_[i] = bed[c][i] * lfe_gain_;
        bed_ptrs_[c] = lfe_scratch_.data();
      } else {
        bed_ptrs_[c] = bed[c];
      }
    }
    renderer_.render(bed_ptrs_.data(), az_.data(), el_.data(), ch,
                     nullptr, nullptr, nullptr, 0, samples, out);
    return 1;
  }

  // Reads the frame's BSI for dialnorm (dialogue normalization) and compr
  // (the encoder's RF-mode compression gain word).
  void parse_bsi(const uint8_t* frame, size_t frame_size) {
    BitReader br(frame, frame_size);
    cavern::EnhancedAC3Header header;
    if (!header.decode(br)) return;
    if (dialog_norm_) {
      dialnorm_gain_ = std::pow(10.0f, header.dialnorm_gain_db() / 20.0f);
    }
    if (header.has_compr()) {
      frame_compr_ = header.compr_gain();
      frame_has_compr_ = true;
    }
  }

  // Dialogue normalization followed by DRC. The decoded bed already carries
  // Line-mode DRC (FFmpeg applies the per-block dynrng words at its default
  // drc_scale=1.0, and NextLib passes no codec options), so:
  //   OFF      -> nothing added here (the Line mode inherent to the decode).
  //   LIGHT /
  //   STANDARD -> the generic peak compressor as additional leveling.
  //   HEAVY    -> true RF/night mode: the stream's own compr gain word, ramped
  //               across the frame, plus a limiter for overload protection —
  //               falling back to the generic heavy curve for streams that
  //               never carry compr.
  void apply_post(float* out, int samples) {
    if (dialnorm_gain_ != 1.0f) {
      for (int i = 0; i < 2 * samples; ++i) out[i] *= dialnorm_gain_;
    }
    if (drc_mode_ == 0) return;

    float thresh, ratio;
    bool rf = false;
    if (drc_mode_ == 3) {
      if (frame_has_compr_) {
        rf_target_ = frame_compr_;
        rf_seen_ = true;
      }
      rf = rf_seen_;
    }
    if (rf) {
      // The mastered heavy-compression gain, ramped from the last applied
      // value so per-frame word changes never step.
      const float g0 = rf_gain_;
      const float step = (rf_target_ - g0) / static_cast<float>(samples);
      float g = g0;
      for (int i = 0; i < samples; ++i) {
        out[2 * i] *= g;
        out[2 * i + 1] *= g;
        g += step;
      }
      rf_gain_ = rf_target_;
      thresh = 0.95f;   // overload protection only — compr did the real work
      ratio = 20.0f;
    } else {
      switch (drc_mode_) {
        case 1: thresh = 0.50f; ratio = 2.0f; break;   // LIGHT
        case 2: thresh = 0.35f; ratio = 4.0f; break;   // STANDARD
        default: thresh = 0.20f; ratio = 8.0f; break;  // HEAVY without compr
      }
    }
    for (int i = 0; i < samples; ++i) {
      const float l = out[2 * i], r = out[2 * i + 1];
      const float al = l < 0.0f ? -l : l, ar = r < 0.0f ? -r : r;
      const float peak = al > ar ? al : ar;
      const float c = peak > drc_env_ ? kDrcAttack : kDrcRelease;
      drc_env_ = c * drc_env_ + (1.0f - c) * peak;
      float gain = 1.0f;
      if (drc_env_ > thresh) gain = (thresh + (drc_env_ - thresh) / ratio) / drc_env_;
      out[2 * i] = l * gain;
      out[2 * i + 1] = r * gain;
    }
  }

  static constexpr float kDrcAttack = 0.30f;    // fast catch of transients
  static constexpr float kDrcRelease = 0.9995f; // slow recovery (~40 ms @ 48k)

  ObjectEngine engine_;
  render::BinauralRenderer renderer_;
  int max_objects_ = 16;
  int mode_ = kObjectRender;
  int downmix_ = kBinaural;
  float lfe_gain_ = 1.0f;
  int drc_mode_ = 0;
  bool dialog_norm_ = false;
  float dialnorm_gain_ = 1.0f;
  float drc_env_ = 0.0f;
  // RF-mode DRC state: this frame's compr word (if any), the ramp source, and
  // whether the stream has ever carried compr (else HEAVY falls back).
  bool frame_has_compr_ = false;
  float frame_compr_ = 1.0f;
  float rf_gain_ = 1.0f;
  float rf_target_ = 1.0f;
  bool rf_seen_ = false;
  std::vector<const float*> obj_ptrs_, bed_ptrs_;
  std::vector<float> az_, el_, lfe_scratch_;
  // Latency-matched fallback downmix: per-ear delay lines (kQmfLatency long),
  // the delayed stereo scratch, and which path produced the previous frame.
  std::vector<float> dm_delay_l_, dm_delay_r_, dm_;
  int dm_idx_ = 0;
  int last_path_ = kPathNone;
  // Last applied OAMD gain per object (survives seeks — it is stream metadata).
  std::vector<float> obj_gain_;
  // Reused marshaling / deinterleave storage (see frame_scratch etc.).
  std::vector<uint8_t> frame_scratch_;
  std::vector<float> bed_scratch_, stereo_scratch_;
  std::vector<std::vector<float>> planar_;
  std::vector<const float*> planar_ptrs_;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_ATMOS_PIPELINE_H
