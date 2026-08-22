// JNI bridge for signalsmith-stretch: independent pitch shifting, i.e. a
// transposition that does not drag the tempo along with it.
//
// The engine is a phase vocoder, so its pitch accuracy is bounded by how
// finely it can resolve a partial in its analysis block rather than by
// arithmetic. Measured on a 48 kHz stream, worst case over semitones -12..+12
// and fundamentals 82 Hz..3.5 kHz:
//
//     block    bins      latency   worst error
//     0.12 s   8.33 Hz   120 ms    1.348 Hz
//     0.25 s   4.00 Hz   250 ms    0.419 Hz
//     0.35 s   2.86 Hz   350 ms    0.179 Hz     <- what kBlockSeconds picks
//     0.50 s   2.00 Hz   500 ms    0.105 Hz
//
// 0.35 s is the smallest block that holds the error inside half a hertz with
// real margin. Going further buys little and costs latency linearly.
//
// Note the distinction: half-hertz *bin spacing* would need a two-second
// block. Half-hertz *accuracy* needs far less, because the vocoder interpolates
// within a bin — the block only has to be fine enough for that estimate to
// converge.

#include <jni.h>
#include <cstring>
#include <vector>
#include <new>

#include "signalsmith-stretch.h"

namespace {

// Analysis block, as a fraction of the sample rate. See the table above.
constexpr double kBlockSeconds = 0.35;
// Hop between analysis frames. Upstream's own presets use 0.03 s.
constexpr double kIntervalSeconds = 0.03;
// Frames accepted in one process() call. The Kotlin side chunks to this.
constexpr int kMaxBlockFrames = 8192;

struct Engine {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    int channels = 2;
    // Planar scratch. Allocated once, at construction, so process() never does.
    std::vector<float> planarIn;
    std::vector<float> planarOut;
    std::vector<float *> inPtrs;
    std::vector<float *> outPtrs;

    Engine(int ch, int sampleRate) : channels(ch) {
        const int block = static_cast<int>(sampleRate * kBlockSeconds);
        const int interval = static_cast<int>(sampleRate * kIntervalSeconds);
        stretch.configure(ch, block, interval);
        planarIn.assign(static_cast<size_t>(ch) * kMaxBlockFrames, 0.0f);
        planarOut.assign(static_cast<size_t>(ch) * kMaxBlockFrames, 0.0f);
        inPtrs.resize(ch);
        outPtrs.resize(ch);
        for (int c = 0; c < ch; ++c) {
            inPtrs[c] = planarIn.data() + static_cast<size_t>(c) * kMaxBlockFrames;
            outPtrs[c] = planarOut.data() + static_cast<size_t>(c) * kMaxBlockFrames;
        }
    }
};

inline Engine *asEngine(jlong handle) {
    return reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeCreate(
        JNIEnv *, jclass, jint channels, jint sampleRate) {
    if (channels < 1 || channels > 2 || sampleRate <= 0) return 0;
    auto *e = new(std::nothrow) Engine(channels, sampleRate);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(e));
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeDestroy(
        JNIEnv *, jclass, jlong handle) {
    delete asEngine(handle);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeSetSemitones(
        JNIEnv *, jclass, jlong handle, jfloat semitones) {
    if (auto *e = asEngine(handle)) e->stretch.setTransposeSemitones(semitones);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeReset(
        JNIEnv *, jclass, jlong handle) {
    if (auto *e = asEngine(handle)) e->stretch.reset();
}

/** Total round-trip latency in frames: what the transposition change lags by. */
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeLatencyFrames(
        JNIEnv *, jclass, jlong handle) {
    auto *e = asEngine(handle);
    if (!e) return 0;
    return e->stretch.inputLatency() + e->stretch.outputLatency();
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeMaxBlockFrames(
        JNIEnv *, jclass) {
    return kMaxBlockFrames;
}

/**
 * Interleaved float in, interleaved float out, same frame count — a pure
 * transposition with the timeline left alone.
 *
 * Both buffers must be direct: this takes their addresses rather than copying
 * through the JVM heap, so nothing here allocates or blocks.
 */
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeProcess(
        JNIEnv *env, jclass, jlong handle,
        jobject inBuf, jobject outBuf, jint frames) {
    auto *e = asEngine(handle);
    if (!e || frames <= 0 || frames > kMaxBlockFrames) return 0;
    auto *in = static_cast<const float *>(env->GetDirectBufferAddress(inBuf));
    auto *out = static_cast<float *>(env->GetDirectBufferAddress(outBuf));
    if (in == nullptr || out == nullptr) return 0;

    const int ch = e->channels;
    for (int c = 0; c < ch; ++c) {
        float *dst = e->inPtrs[c];
        for (int i = 0; i < frames; ++i) dst[i] = in[i * ch + c];
    }

    e->stretch.process(e->inPtrs.data(), frames, e->outPtrs.data(), frames);

    for (int c = 0; c < ch; ++c) {
        const float *src = e->outPtrs[c];
        for (int i = 0; i < frames; ++i) out[i * ch + c] = src[i];
    }
    return frames;
}

}  // extern "C"
