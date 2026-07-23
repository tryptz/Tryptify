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
    mix_real_.assign(kSubbands, 0.0f);
    mix_imag_.assign(kSubbands, 0.0f);
  }

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
    for (int obj = 0; obj < objects; ++obj) {
      process_object(joc, obj, timeslot, channels, gain);
    }
    return object_out_;
  }

 private:
  void process_object(const JointObjectCoding& joc, int obj, int timeslot, int channels, float gain) {
    const float* m0 = joc.interpolated(obj, timeslot, 0);
    qmath::multiply_and_set(result_real_[0].data(), m0, mix_real_.data(), kSubbands);
    qmath::multiply_and_set(result_imag_[0].data(), m0, mix_imag_.data(), kSubbands);
    for (int ch = 1; ch < channels; ++ch) {
      const float* mc = joc.interpolated(obj, timeslot, ch);
      qmath::multiply_and_add(result_real_[ch].data(), mc, mix_real_.data(), kSubbands);
      qmath::multiply_and_add(result_imag_[ch].data(), mc, mix_imag_.data(), kSubbands);
    }
    converters_[obj].process_inverse(mix_real_.data(), mix_imag_.data(), object_out_[obj].data());
    if (gain != 1.0f) {
      for (int sb = 0; sb < kSubbands; ++sb) object_out_[obj][sb] *= gain;
    }
  }

  std::vector<QuadratureMirrorFilterBank> converters_;
  std::vector<std::vector<float>> result_real_, result_imag_;  // [channel][subband]
  std::vector<std::vector<float>> object_out_;                 // [object][subband]
  std::vector<float> mix_real_, mix_imag_;                     // scratch [subband]
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_APPLIER_H
