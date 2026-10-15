// The process-wide dynamic range meter behind DynamicRangeNative.kt.
//
// One meter, fed by DynamicRangeTapProcessor at the head of the chain. The
// audio thread owns it: push() configures, resets and measures, and publishes
// through atomics. Any other thread only reads those atomics or raises the
// reset flag, so nothing takes a lock (docs/agent-playbook.md, Realtime Audio).

#include <jni.h>
#include <atomic>
#include <cstdint>

#include "dynamic_range_meter.h"

namespace {

tryptify::DynamicRangeMeter gMeter;

std::atomic<bool> gResetRequested{false};
std::atomic<float> gReading{tryptify::DynamicRangeMeter::kNone};
std::atomic<int64_t> gFrames{0};
std::atomic<int> gSampleRate{0};
std::atomic<int> gBlocks{0};

// The reading as it stood when the stream's format last changed under the
// meter. At a gapless join from stereo to 5.1, the next track's first buffer
// arrives, and resets the meter, before the player says the track changed;
// the finished track's reading is kept here for it to read.
std::atomic<float> gPrevReading{tryptify::DynamicRangeMeter::kNone};
std::atomic<float> gPrevSeconds{0.f};
std::atomic<int> gPrevBlocks{0};

void publish() {
    gReading.store(gMeter.reading(), std::memory_order_relaxed);
    gFrames.store(gMeter.frames(), std::memory_order_relaxed);
    gSampleRate.store(gMeter.sampleRate(), std::memory_order_relaxed);
    gBlocks.store(gMeter.blocks(), std::memory_order_relaxed);
}

}  // namespace

extern "C" {

// Audio thread. [interleaved] holds [frames] frames of [channels] floats.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_meter_DynamicRangeNative_nativePush(
        JNIEnv* env, jclass, jfloatArray interleaved, jint frames, jint channels, jint sampleRate) {
    if (frames <= 0 || channels <= 0) return;
    if (channels > tryptify::DynamicRangeMeter::kMaxChannels) return;
    if (env->GetArrayLength(interleaved) < frames * channels) return;
    // A requested reset first: what it discards is no track's to keep.
    if (gResetRequested.exchange(false, std::memory_order_acq_rel)) gMeter.reset();
    if (gMeter.frames() > 0 && gMeter.formatChanges(sampleRate, channels)) {
        const int rate = gMeter.sampleRate();
        gPrevReading.store(gMeter.reading(), std::memory_order_relaxed);
        gPrevSeconds.store(rate > 0 ? float(double(gMeter.frames()) / rate) : 0.f, std::memory_order_relaxed);
        gPrevBlocks.store(gMeter.blocks(), std::memory_order_relaxed);
    }
    gMeter.configure(sampleRate, channels);
    auto* p = static_cast<float*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
    if (!p) return;
    gMeter.process(p, frames);
    env->ReleasePrimitiveArrayCritical(interleaved, p, JNI_ABORT);
    publish();
}

// Any thread: [out] gets the reading (dB, or DynamicRangeMeter::kNone), the
// seconds measured since the reset and the whole blocks in the reading; then
// the same three as they stood at the last format change.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_meter_DynamicRangeNative_nativeRead(JNIEnv* env, jclass, jfloatArray out) {
    if (env->GetArrayLength(out) < 6) return;
    const int rate = gSampleRate.load(std::memory_order_relaxed);
    const int64_t frames = gFrames.load(std::memory_order_relaxed);
    const jfloat values[6] = {
        gReading.load(std::memory_order_relaxed),
        rate > 0 ? jfloat(double(frames) / rate) : 0.f,
        jfloat(gBlocks.load(std::memory_order_relaxed)),
        gPrevReading.load(std::memory_order_relaxed),
        gPrevSeconds.load(std::memory_order_relaxed),
        jfloat(gPrevBlocks.load(std::memory_order_relaxed)),
    };
    env->SetFloatArrayRegion(out, 0, 6, values);
}

// Any thread: start again at the next buffer. Read first: the published
// values are cleared now, so a reading taken after this is the new track's.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_meter_DynamicRangeNative_nativeRequestReset(JNIEnv*, jclass) {
    gResetRequested.store(true, std::memory_order_release);
    gReading.store(tryptify::DynamicRangeMeter::kNone, std::memory_order_relaxed);
    gFrames.store(0, std::memory_order_relaxed);
    gBlocks.store(0, std::memory_order_relaxed);
    gPrevReading.store(tryptify::DynamicRangeMeter::kNone, std::memory_order_relaxed);
    gPrevSeconds.store(0.f, std::memory_order_relaxed);
    gPrevBlocks.store(0, std::memory_order_relaxed);
}

}  // extern "C"
