// Atmos object reconstruction engine (plan Option B2, phase P1).
//
// Given the decoded E-AC-3 core bed PCM (from Media3's FfmpegAudioRenderer) and
// a raw E-AC-3 frame's Atmos side-data, produces the per-object PCM by driving
// the ported JOC upmix: parse EMDF -> JOC/OAMD, QMF-analyze the bed, apply the
// JOC mixing matrices per 64-sample timeslot, inverse-QMF to object samples.
// The HRTF render (phase P2) consumes these objects + their OAMD positions.
//
// Non-real-time-allocating after warmup: buffers are sized on the first frame /
// on a channel-or-object-count change and then reused, matching the DSP engine's
// approach. Not thread-safe; one instance per stream.
#ifndef TF_ATMOS_OBJECT_ENGINE_H
#define TF_ATMOS_OBJECT_ENGINE_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "bit_reader.h"
#include "cavern/extensible_metadata_decoder.h"
#include "cavern/joint_object_coding.h"
#include "cavern/joint_object_coding_applier.h"

namespace tf {
namespace atmos {

class ObjectEngine {
 public:
  static constexpr int kTimeslot = cavern::JointObjectCodingApplier::kSubbands;  // 64

  // Parses a raw E-AC-3 frame for Atmos side-data and, if it carries JOC,
  // upmixes the bed PCM into objects. `bed` is [bed_channels][frame_samples]
  // (deinterleaved float, the decoder's channel order). Returns the object count
  // (>= 1) or -1 if the frame has no JOC. Object PCM is then object_channel(i).
  int upmix_frame(const uint8_t* frame, size_t frame_size, const float* const* bed,
                  int bed_channels, int frame_samples) {
    BitReader reader(frame, frame_size);
    emdf_.decode(reader);
    if (!emdf_.has_objects()) return -1;
    return upmix(emdf_.joc(), bed, bed_channels, frame_samples);
  }

  // Core upmix from an already-decoded JOC (kept separate so it is host-testable
  // without synthesizing a full EMDF frame). `frame_samples` must be a multiple
  // of kTimeslot (an E-AC-3 frame is 1536 = 24 * 64).
  int upmix(cavern::JointObjectCoding& joc, const float* const* bed,
            int bed_channels, int frame_samples) {
    const int channels = joc.channel_count();
    const int objects = joc.object_count();
    if (channels <= 0 || objects <= 0 || frame_samples <= 0) return -1;
    if (frame_samples % kTimeslot != 0) return -1;
    const int usable_channels = channels < bed_channels ? channels : bed_channels;
    const int timeslots = frame_samples / kTimeslot;

    ensure_capacity(joc, channels, objects, frame_samples);
    joc.get_mixing_matrices(frame_samples);

    for (int ts = 0; ts < timeslots; ++ts) {
      const int base = ts * kTimeslot;
      for (int ch = 0; ch < channels; ++ch) {
        float* dst = ts_input_[ch].data();
        if (ch < usable_channels) {
          const float* src = bed[ch] + base;
          for (int i = 0; i < kTimeslot; ++i) dst[i] = src[i];
        } else {
          for (int i = 0; i < kTimeslot; ++i) dst[i] = 0.0f;  // missing bed channel
        }
      }
      const std::vector<std::vector<float>>& out = applier_->apply(ts_input_, joc, ts);
      for (int obj = 0; obj < objects; ++obj) {
        float* dst = object_pcm_[obj].data() + base;
        const float* src = out[obj].data();
        for (int i = 0; i < kTimeslot; ++i) dst[i] = src[i];
      }
    }
    object_count_ = objects;
    return objects;
  }

  int object_count() const { return object_count_; }
  int frame_samples() const { return frame_samples_; }
  // Object PCM for the last upmix (frame_samples() long). Null if out of range.
  const float* object_channel(int obj) const {
    return (obj >= 0 && obj < object_count_) ? object_pcm_[obj].data() : nullptr;
  }
  // The decoded OAMD frame (object positions), for the HRTF render (P2).
  const cavern::ObjectAudioMetadata& oamd() const { return emdf_.oamd(); }

 private:
  void ensure_capacity(const cavern::JointObjectCoding& joc, int channels,
                       int objects, int frame_samples) {
    if (applier_ == nullptr || channels != applier_channels_ ||
        objects != applier_objects_) {
      applier_ = std::make_unique<cavern::JointObjectCodingApplier>(joc);
      applier_channels_ = channels;
      applier_objects_ = objects;
      ts_input_.assign(channels, std::vector<float>(kTimeslot, 0.0f));
    }
    if (frame_samples != frame_samples_ ||
        static_cast<int>(object_pcm_.size()) != objects) {
      object_pcm_.assign(objects, std::vector<float>(frame_samples, 0.0f));
      frame_samples_ = frame_samples;
    }
  }

  cavern::ExtensibleMetadataDecoder emdf_;
  std::unique_ptr<cavern::JointObjectCodingApplier> applier_;
  int applier_channels_ = -1;
  int applier_objects_ = -1;
  int object_count_ = 0;
  int frame_samples_ = -1;
  std::vector<std::vector<float>> object_pcm_;  // [object][frame_samples]
  std::vector<std::vector<float>> ts_input_;    // [channel][kTimeslot] scratch
};

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_OBJECT_ENGINE_H
