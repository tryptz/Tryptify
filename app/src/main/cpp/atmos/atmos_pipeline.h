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

class AtmosPipeline {
 public:
  void configure(int sample_rate, int max_objects) {
    max_objects_ = max_objects < 1 ? 1 : max_objects;
    renderer_.configure(sample_rate, max_objects_);
  }

  // Renders one E-AC-3 frame's Atmos content to interleaved stereo (`out` holds
  // 2*samples floats). Returns 1 if Atmos was rendered, or -1 if the frame has
  // no objects (the caller should then pass the bed through unchanged). `bed` is
  // [bed_channels][samples] deinterleaved float (the decoder's channel order).
  int process_frame(const uint8_t* frame, size_t frame_size, const float* const* bed,
                    int bed_channels, int samples, float* out) {
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
  ObjectEngine engine_;
  render::BinauralRenderer renderer_;
  int max_objects_ = 16;
  std::vector<const float*> obj_ptrs_;
  std::vector<float> az_, el_;
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_ATMOS_PIPELINE_H
