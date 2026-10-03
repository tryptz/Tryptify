// The process-wide loudness meter behind LoudnessNative.kt.
//
// One meter, like the analyzer tap that feeds it. The audio thread owns it:
// push() configures, resets and measures, and publishes each reading through
// atomics. The UI thread only reads those atomics or raises the reset flag,
// so the two never touch the meter at the same time and nothing takes a lock
// (docs/agent-playbook.md, Realtime Audio).

#include <jni.h>
#include <atomic>

#include "loudness_meter.h"

namespace {

tryptify::LoudnessMeter gMeter;

std::atomic<bool> gResetRequested{false};
std::atomic<float> gMomentary{tryptify::LoudnessMeter::kNone};
std::atomic<float> gShortTerm{tryptify::LoudnessMeter::kNone};
std::atomic<float> gIntegrated{tryptify::LoudnessMeter::kNone};
std::atomic<float> gRange{tryptify::LoudnessMeter::kNone};
std::atomic<float> gTruePeak{tryptify::LoudnessMeter::kNone};

void publish(const tryptify::LoudnessMeter::Reading& r) {
    gMomentary.store(r.momentary, std::memory_order_relaxed);
    gShortTerm.store(r.shortTerm, std::memory_order_relaxed);
    gIntegrated.store(r.integrated, std::memory_order_relaxed);
    gRange.store(r.range, std::memory_order_relaxed);
    gTruePeak.store(r.truePeak, std::memory_order_relaxed);
}

}  // namespace

extern "C" {

// Audio thread. [interleaved] holds [frames] frames of [channels] floats.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_eq_LoudnessNative_nativePush(
        JNIEnv* env, jclass, jfloatArray interleaved, jint frames, jint channels, jint sampleRate) {
    if (frames <= 0 || channels <= 0) return;
    if (channels > tryptify::LoudnessMeter::kMaxChannels) return;
    if (env->GetArrayLength(interleaved) < frames * channels) return;
    gMeter.configure(sampleRate, channels);
    if (gResetRequested.exchange(false, std::memory_order_acq_rel)) gMeter.reset();
    auto* p = static_cast<float*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
    if (!p) return;
    gMeter.process(p, frames);
    env->ReleasePrimitiveArrayCritical(interleaved, p, JNI_ABORT);
    publish(gMeter.reading());
}

// UI thread: [out] gets momentary, short-term, integrated, range, true peak.
// LoudnessMeter::kNone in a slot means there is no reading for it yet.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_eq_LoudnessNative_nativeRead(JNIEnv* env, jclass, jfloatArray out) {
    if (env->GetArrayLength(out) < 5) return;
    const jfloat values[5] = {
        gMomentary.load(std::memory_order_relaxed),
        gShortTerm.load(std::memory_order_relaxed),
        gIntegrated.load(std::memory_order_relaxed),
        gRange.load(std::memory_order_relaxed),
        gTruePeak.load(std::memory_order_relaxed),
    };
    env->SetFloatArrayRegion(out, 0, 5, values);
}

// Any thread: start the measurement again at the next buffer. The published
// values are cleared now, so a readout does not show the last track's
// integrated loudness for the length of one buffer.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_eq_LoudnessNative_nativeRequestReset(JNIEnv*, jclass) {
    gResetRequested.store(true, std::memory_order_release);
    publish(tryptify::LoudnessMeter::Reading{});
}

}  // extern "C"
