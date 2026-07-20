// End-to-end native Atmos render pipeline (plan Option B2, P1 -> P2 glue).
//
// Chains the object reconstruction (ObjectEngine) and the binaural render
// (BinauralRenderer): a raw E-AC-3 frame + its decoded core bed PCM go in, and
// binaural stereo comes out. Each object's OAMD render-space position is mapped
// to an azimuth/elevation for the HRTF renderer. This is the single native call
// the Media3 AtmosAudioProcessor (P4) invokes per frame.
#ifndef TF_ATMOS_ATMOS_PIPELINE_H
#define TF_ATMOS_ATMOS_PIPELINE_H

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

  // Renders one E-AC-3 frame's Atmos content to interleaved stereo (`out` holds
  // 2*samples floats). Returns 1 if Atmos was rendered, or -1 if the frame has
  // no objects (the caller should then pass the bed through unchanged). `bed` is
  // [bed_channels][samples] deinterleaved float (the decoder's channel order).
  int process_frame(const uint8_t* frame, size_t frame_size, const float* const* bed,
                    int bed_channels, int samples, float* out) {
    // Passthrough / non-binaural fold-downs are the caller's job (it already
    // has an ITU downmix); returning -1 selects that path.
    if (mode_ == kPassthrough || downmix_ != kBinaural) return -1;
    // Dialogue normalization reads the stream's own dialnorm from the syncframe
    // header, so the gain follows the content rather than a fixed guess.
    dialnorm_gain_ = dialog_norm_ ? dialnorm_linear(frame, frame_size) : 1.0f;
    if (mode_ == kBedHrtf) {
      const int rc = render_bed(bed, bed_channels, samples, out);
      if (rc == 1) apply_post(out, samples);
      return rc;
    }

    const int n = engine_.upmix_frame(frame, frame_size, bed, bed_channels, samples);
    if (n <= 0) return -1;
    const int objects = n < max_objects_ ? n : max_objects_;

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
    apply_post(out, samples);
    return 1;
  }

 private:
  static constexpr int kMaxBedCh = 8;
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

  // Linear gain that aligns the stream's dialogue to the -31 dBFS reference.
  float dialnorm_linear(const uint8_t* frame, size_t frame_size) {
    BitReader br(frame, frame_size);
    cavern::EnhancedAC3Header header;
    if (!header.decode(br)) return 1.0f;
    return std::pow(10.0f, header.dialnorm_gain_db() / 20.0f);
  }

  // Dialogue-normalization gain followed by the profile's DRC compression.
  void apply_post(float* out, int samples) {
    if (dialnorm_gain_ != 1.0f) {
      for (int i = 0; i < 2 * samples; ++i) out[i] *= dialnorm_gain_;
    }
    if (drc_mode_ == 0) return;  // OFF (full range)
    float thresh, ratio;
    switch (drc_mode_) {
      case 1: thresh = 0.50f; ratio = 2.0f; break;   // LIGHT
      case 2: thresh = 0.35f; ratio = 4.0f; break;   // STANDARD
      default: thresh = 0.20f; ratio = 8.0f; break;  // HEAVY (night)
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
  std::vector<const float*> obj_ptrs_, bed_ptrs_;
  std::vector<float> az_, el_, lfe_scratch_;
  // Reused marshaling / deinterleave storage (see frame_scratch etc.).
  std::vector<uint8_t> frame_scratch_;
  std::vector<float> bed_scratch_, stereo_scratch_;
  std::vector<std::vector<float>> planar_;
  std::vector<const float*> planar_ptrs_;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_ATMOS_PIPELINE_H
