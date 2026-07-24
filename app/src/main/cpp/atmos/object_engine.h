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
    // JOC/OAMD are transmitted sparsely — only ~22% of E-AC-3 frames carry a JOC
    // payload — and are defined to hold until the next update. The EMDF decoder
    // overwrites joc_/oamd_ only when a payload is actually present, so a frame
    // without one still has the previous frame's matrices and positions, and
    // re-running the interpolation below holds them steady (prev_matrix_ already
    // equals the decoded endpoint, so the lerp collapses to a constant).
    //
    // Returning -1 for those frames instead — as this did — makes the caller
    // fall back to its stereo downmix, so the output alternates between the
    // binaural render and a plain fold-down every 32 ms. That is audible as
    // chopping, and it is what "micro-stutter" turned out to be.
    if (emdf_.has_objects()) {
      held_frames_ = 0;
    } else if (held_frames_ >= kMaxHeldFrames) {
      // Never saw a JOC payload, or the stream stopped carrying one (a track
      // change into non-Atmos content). Stop rendering rather than hold stale
      // metadata forever — this expiry is what keeps the hold self-limiting
      // without needing a reset call plumbed through the pipeline and JNI.
      return -1;
    } else {
      ++held_frames_;
    }
    return upmix(emdf_.joc(), bed, bed_channels, frame_samples);
  }

  // One more upmix from the held JOC/OAMD, ignoring the hold expiry. The
  // pipeline uses it to render the fade-out half of a render→downmix seam
  // after upmix_frame() has already returned -1 for the same frame — without
  // it the hold's expiry would end in a hard cut instead of a crossfade.
  int upmix_held(const float* const* bed, int bed_channels, int frame_samples) {
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

  // OAMD gain of object `obj` from the frame's last info block, or a negative
  // value meaning "hold the previous gain" (OAMD's gain_helper==3 sentinel, or
  // no object element). Decoded gains include Cavern's 3 dB anti-clip
  // attenuation. The caller keeps the last applied gain per object and ramps.
  float object_gain(int obj) {
    cavern::ObjectAudioMetadata& o = emdf_.oamd();
    for (int e = 0; e < o.element_count(); ++e) {
      cavern::OAElementMD& el = o.element(e);
      if (el.is_object_element() && obj >= 0 && obj < el.object_count()) {
        const int last = el.block_count() - 1;
        if (last < 0) return -1.0f;
        return el.info_block(obj, last).gain();
      }
    }
    return -1.0f;
  }

  // Applies a per-object gain to the reconstructed PCM in place, ramped
  // linearly from `g0` to `g1` across the frame (no zipper on gain changes).
  // Called by the pipeline between upmix and render.
  void scale_object(int obj, float g0, float g1) {
    if (obj < 0 || obj >= object_count_ || frame_samples_ <= 0) return;
    if (g0 == 1.0f && g1 == 1.0f) return;
    float* p = object_pcm_[obj].data();
    const float step = (g1 - g0) / static_cast<float>(frame_samples_);
    float g = g0;
    for (int i = 0; i < frame_samples_; ++i) {
      p[i] *= g;
      g += step;
    }
  }

  // Object index of the LFE, or -1 if none. Bed objects occupy the first bed
  // slots in object order, and the LFE's bed-order index is therefore its object
  // index. The LFE is non-directional bass and must NOT be HRIR-spatialized —
  // the renderer sums it to both ears instead of placing it at a point.
  int lfe_object_index() const { return emdf_.oamd().get_lfe_position(); }

  // Render-space position of object `obj` from the last-parsed OAMD (x = left..
  // right, y = down..up, z = back..front). Returns the origin (center) if the
  // frame carried no object element for it. Consumed by the HRTF render (P2).
  //
  // A frame carries up to 8 info blocks of intra-frame motion; this returns
  // the LAST block's endpoint — the frame's target position — which the
  // renderer's motion crossfade then glides toward. Reading block 0 (as this
  // originally did) lagged the mastered path by a whole frame. Every block is
  // resolved in order because resolved_position() accumulates differential
  // updates per block slot.
  Vec3 object_position(int obj) {
    cavern::ObjectAudioMetadata& o = emdf_.oamd();
    for (int e = 0; e < o.element_count(); ++e) {
      cavern::OAElementMD& el = o.element(e);
      if (el.is_object_element() && obj >= 0 && obj < el.object_count()) {
        Vec3 pos{};
        const int blocks = el.block_count();
        for (int b = 0; b < blocks; ++b) pos = el.info_block(obj, b).resolved_position();
        return pos;
      }
    }
    return Vec3{};
  }

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

  // How long the last JOC/OAMD update stays valid when later frames carry none.
  // Real gaps are ~4-5 frames at the observed ~22% payload density; 32 frames
  // (~1 s at 1536 samples / 48 kHz) covers those with a wide margin while still
  // expiring promptly once a stream really stops carrying object metadata.
  static constexpr int kMaxHeldFrames = 32;

  cavern::ExtensibleMetadataDecoder emdf_;
  // Frames rendered since the last JOC payload; starts expired so a stream that
  // never carries JOC is never rendered.
  int held_frames_ = kMaxHeldFrames;
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
