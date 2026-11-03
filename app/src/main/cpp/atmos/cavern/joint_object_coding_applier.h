// Ported from Cavern — C# -> C++.
//   Source:  https://github.com/VoidXH/Cavern   (Bence Sgánetz, http://en.sbence.hu)
//   License: Cavern licence (non-commercial, no ads, attribution + source link;
//            public/commercial use requires the creator's permission).
//            See cpp/atmos/cavern/NOTICE.md — these terms apply to this file.
//
// Ported from Cavern.Format/Decoders/EnhancedAC3/JointObjectCodingApplier.cs —
// converts a channel-based audio stream + JOC mixing matrices into object output
// samples in the QMF domain.
//
// Deviations, behaviour-preserving: Cavern's ThreadPool fan-out (per channel for
// the forward transform, per object for the mix + inverse) is sequential here;
// the timeslot index is passed explicitly instead of the internal counter; only
// the scalar QMath path is used. Forward results are copied out so no converter
// output aliases across the timeslot.
//
// Single-threaded on purpose. This used to fan the objects out to a worker
// pool, which woke the workers and made the calling thread (ExoPlayer's
// playback thread) wait for them once per 64-sample timeslot: 750 times a
// second. On the host that was slower than doing the work in place (at 7
// channels to 16 objects, 1.3 ms a frame and 2.2 ms of CPU against 0.86 ms,
// with ~2,800 context switches a second), and a worker parked on a little
// core could hold the audio thread for as long as the scheduler pleased.
// Handing whole objects to the workers once a frame instead measured no
// better than in place with three workers, and 10% faster with one for 15%
// more CPU. Objects are independent, so the output is the same either way,
// and no thread is ever started (the applier is rebuilt on the audio thread
// when the channel or object count changes).
#ifndef TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_APPLIER_H
#define TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_APPLIER_H

#include <algorithm>
#include <vector>

#include "joint_object_coding.h"
#include "qmath.h"
#include "quadrature_mirror_filterbank.h"

namespace tf {
namespace atmos {
namespace cavern {

class JointObjectCodingApplier {
 public:
  static constexpr int kSubbands = QuadratureMirrorFilterBank::kSubbands;  // 64

  explicit JointObjectCodingApplier(const JointObjectCoding& joc) {
    const int channels = joc.channel_count();
    const int objects = joc.object_count();
    // A single converter per index serves both the channel forward transform
    // and the object inverse transform (their delay lines are independent).
    const int converter_count = std::max(channels, objects);
    converters_.resize(converter_count);
    result_real_.assign(channels, std::vector<float>(kSubbands, 0.0f));
    result_imag_.assign(channels, std::vector<float>(kSubbands, 0.0f));
    object_out_.assign(objects, std::vector<float>(kSubbands, 0.0f));
  }

  JointObjectCodingApplier(const JointObjectCodingApplier&) = delete;
  JointObjectCodingApplier& operator=(const JointObjectCodingApplier&) = delete;

  // Produces one timeslot (kSubbands samples) of output per object. `input` is
  // [channel][kSubbands] for this timeslot. Returns [object][kSubbands].
  const std::vector<std::vector<float>>& apply(const std::vector<std::vector<float>>& input,
                                               JointObjectCoding& joc, int timeslot) {
    const int channels = joc.channel_count();
    const int objects = joc.object_count();
    const float gain = joc.gain();

    // Forward transform every input channel, copying the result out.
    for (int ch = 0; ch < channels; ++ch) {
      converters_[ch].process_forward(input[ch].data());
      const float* re = converters_[ch].out_real();
      const float* im = converters_[ch].out_imaginary();
      for (int sb = 0; sb < kSubbands; ++sb) {
        result_real_[ch][sb] = re[sb];
        result_imag_[ch][sb] = im[sb];
      }
    }

    // Mix to objects and inverse transform.
    //
    // Deviation from Cavern (performance, behaviour-preserving): an object the
    // bitstream marks inactive has an all-zero mixing matrix, so it can only
    // produce silence — yet the inverse transform is the most expensive step
    // per object. Skip it and emit silence instead. Note the skipped object's
    // synthesis delay line does not advance, which can give a brief transient
    // when it reactivates; that is a fair trade for real-time headroom.
    for (int obj = 0; obj < objects; ++obj) {
      if (!joc.object_active(obj)) {
        std::fill(object_out_[obj].begin(), object_out_[obj].end(), 0.0f);
        continue;
      }
      process_object(joc, obj, timeslot, channels, gain);
    }
    return object_out_;
  }

 private:
  void process_object(const JointObjectCoding& joc, int obj, int timeslot, int channels, float gain) {
    // Each channel's row of the mixing matrix is worked out here, as it is
    // needed, rather than read from a table of every object x timeslot x
    // channel built ahead of the frame (688 KB a frame at 16 objects).
    const float* m0 = joc.mixing_row(obj, timeslot, 0, row_);
    qmath::multiply_and_set(result_real_[0].data(), m0, mix_real_, kSubbands);
    qmath::multiply_and_set(result_imag_[0].data(), m0, mix_imag_, kSubbands);
    for (int ch = 1; ch < channels; ++ch) {
      const float* mc = joc.mixing_row(obj, timeslot, ch, row_);
      qmath::multiply_and_add(result_real_[ch].data(), mc, mix_real_, kSubbands);
      qmath::multiply_and_add(result_imag_[ch].data(), mc, mix_imag_, kSubbands);
    }
    float* out = object_out_[obj].data();
    converters_[obj].process_inverse(mix_real_, mix_imag_, out);
    if (gain != 1.0f) {
      for (int sb = 0; sb < kSubbands; ++sb) out[sb] *= gain;
    }
  }

  std::vector<QuadratureMirrorFilterBank> converters_;
  std::vector<std::vector<float>> result_real_, result_imag_;  // [channel][subband]
  std::vector<std::vector<float>> object_out_;                 // [object][subband]
  float mix_real_[kSubbands] = {};
  float mix_imag_[kSubbands] = {};
  float row_[kSubbands] = {};  // scratch for an interpolated mixing-matrix row
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_APPLIER_H
