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

#include "object_engine.h"
#include "render/structural_hrtf.h"
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
                  bool height_virtualization, float lfe_gain_db) {
    mode_ = mode;
    downmix_ = downmix;
    lfe_gain_ = std::pow(10.0f, lfe_gain_db / 20.0f);
    renderer_.set_params(binaural_strength, height_virtualization);
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
    if (mode_ == kBedHrtf) return render_bed(bed, bed_channels, samples, out);

    const int n = engine_.upmix_frame(frame, frame_size, bed, bed_channels, samples);
    if (n <= 0) return -1;
    const int objects = n < max_objects_ ? n : max_objects_;

    obj_ptrs_.resize(objects);
    az_.resize(objects);
    el_.resize(objects);
    for (int o = 0; o < objects; ++o) {
      obj_ptrs_[o] = engine_.object_channel(o);
      position_to_azel(engine_.object_position(o), az_[o], el_[o]);
    }
    renderer_.render(obj_ptrs_.data(), az_.data(), el_.data(), objects,
                     nullptr, nullptr, nullptr, 0, samples, out);
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

  ObjectEngine engine_;
  render::BinauralRenderer renderer_;
  int max_objects_ = 16;
  int mode_ = kObjectRender;
  int downmix_ = kBinaural;
  float lfe_gain_ = 1.0f;
  std::vector<const float*> obj_ptrs_, bed_ptrs_;
  std::vector<float> az_, el_, lfe_scratch_;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_ATMOS_PIPELINE_H
