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
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>
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
    // Per-object mix scratch: objects are processed concurrently, so this must
    // not be shared state (it was a single buffer in the sequential port).
    mix_real_.assign(objects, std::vector<float>(kSubbands, 0.0f));
    mix_imag_.assign(objects, std::vector<float>(kSubbands, 0.0f));
    start_workers();
  }

  ~JointObjectCodingApplier() { stop_workers(); }

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
    // produce silence — yet the inverse transform is the single most expensive
    // step here (~17k MACs per object per timeslot, 76% of the filterbank cost
    // at 16 objects). Skip it and emit silence instead. Note the skipped
    // object's synthesis delay line does not advance, which can give a brief
    // transient when it reactivates; that is a fair trade for real-time headroom.
    // Fan the per-object work out across the worker pool (Cavern parallelizes
    // the same loop). Objects are independent: each owns its converter, its mix
    // scratch and its output row, so no synchronisation is needed inside the
    // job — only the barrier at the end.
    job_joc_ = &joc;
    job_timeslot_ = timeslot;
    job_channels_ = channels;
    job_gain_ = gain;
    job_objects_ = objects;
    next_object_.store(0, std::memory_order_relaxed);

    if (!workers_.empty()) {
      pending_.store(static_cast<int>(workers_.size()), std::memory_order_relaxed);
      {
        std::lock_guard<std::mutex> lock(mutex_);
        ++generation_;
      }
      cv_start_.notify_all();
    }
    drain_jobs();  // the calling (audio) thread pulls its share too
    if (!workers_.empty()) {
      std::unique_lock<std::mutex> lock(mutex_);
      cv_done_.wait(lock, [this] {
        return pending_.load(std::memory_order_acquire) == 0;
      });
    }
    return object_out_;
  }

 private:
  void process_object(const JointObjectCoding& joc, int obj, int timeslot, int channels, float gain) {
    float* mix_re = mix_real_[obj].data();
    float* mix_im = mix_imag_[obj].data();
    const float* m0 = joc.interpolated(obj, timeslot, 0);
    qmath::multiply_and_set(result_real_[0].data(), m0, mix_re, kSubbands);
    qmath::multiply_and_set(result_imag_[0].data(), m0, mix_im, kSubbands);
    for (int ch = 1; ch < channels; ++ch) {
      const float* mc = joc.interpolated(obj, timeslot, ch);
      qmath::multiply_and_add(result_real_[ch].data(), mc, mix_re, kSubbands);
      qmath::multiply_and_add(result_imag_[ch].data(), mc, mix_im, kSubbands);
    }
    converters_[obj].process_inverse(mix_re, mix_im, object_out_[obj].data());
    if (gain != 1.0f) {
      for (int sb = 0; sb < kSubbands; ++sb) object_out_[obj][sb] *= gain;
    }
  }

  // ── Worker pool ──────────────────────────────────────────────────────────
  // Claims objects atomically until the timeslot's work is exhausted. Shared by
  // the workers and the calling thread.
  void drain_jobs() {
    for (;;) {
      const int obj = next_object_.fetch_add(1, std::memory_order_relaxed);
      if (obj >= job_objects_) return;
      if (!job_joc_->object_active(obj)) {
        std::fill(object_out_[obj].begin(), object_out_[obj].end(), 0.0f);
        continue;
      }
      process_object(*job_joc_, obj, job_timeslot_, job_channels_, job_gain_);
    }
  }

  void start_workers() {
    const unsigned hw = std::thread::hardware_concurrency();
    // Leave the calling thread a share; cap the pool so we never oversubscribe
    // a phone's little cores with real-time audio work.
    const int count = hw >= 4 ? 3 : (hw >= 2 ? 1 : 0);
    for (int i = 0; i < count; ++i) {
      workers_.emplace_back([this] { worker_loop(); });
    }
  }

  void worker_loop() {
    int seen = 0;
    for (;;) {
      {
        std::unique_lock<std::mutex> lock(mutex_);
        cv_start_.wait(lock, [this, seen] { return generation_ != seen || !running_; });
        if (!running_) return;
        seen = generation_;
      }
      drain_jobs();
      if (pending_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
        std::lock_guard<std::mutex> lock(mutex_);
        cv_done_.notify_one();
      }
    }
  }

  void stop_workers() {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      running_ = false;
      ++generation_;
    }
    cv_start_.notify_all();
    for (std::thread& t : workers_) {
      if (t.joinable()) t.join();
    }
  }

  std::vector<QuadratureMirrorFilterBank> converters_;
  std::vector<std::vector<float>> result_real_, result_imag_;  // [channel][subband]
  std::vector<std::vector<float>> object_out_;                 // [object][subband]
  std::vector<std::vector<float>> mix_real_, mix_imag_;        // [object][subband]

  // Job description for the current timeslot, published before the workers wake.
  const JointObjectCoding* job_joc_ = nullptr;
  int job_timeslot_ = 0, job_channels_ = 0, job_objects_ = 0;
  float job_gain_ = 1.0f;
  std::atomic<int> next_object_{0};
  std::atomic<int> pending_{0};

  std::vector<std::thread> workers_;
  std::mutex mutex_;
  std::condition_variable cv_start_, cv_done_;
  int generation_ = 0;
  bool running_ = true;
};

}  // namespace cavern
}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_CAVERN_JOINT_OBJECT_CODING_APPLIER_H
