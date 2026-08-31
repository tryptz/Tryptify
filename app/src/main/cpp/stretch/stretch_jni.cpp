// JNI bridge for signalsmith-stretch: independent pitch shifting, i.e. a
// transposition that does not drag the tempo along with it.
//
// The engine is a phase vocoder, so its pitch accuracy is bounded by how
// finely it can resolve a partial in its analysis block rather than by
// arithmetic. Measured on a 48 kHz stream, worst case over semitones -12..+12
// and fundamentals 82 Hz..3.5 kHz:
//
//     block    bins      latency   worst error   quality
//     0.12 s   8.33 Hz   120 ms    1.384 Hz      Fast
//     0.25 s   4.00 Hz   250 ms    0.419 Hz      Balanced (default)
//     0.35 s   2.86 Hz   350 ms    0.179 Hz      High
//
// tests/stretch_precision_test.cpp measures that column and fails if a block
// misses what its quality claims, because those figures are printed under the
// quality buttons -- see PitchQuality.vocoderErrorHz.
//
// Accuracy was for a long time the only axis this file considered, which is how
// 0.35 s came to be hard-coded for everyone: it is the smallest block holding
// the error inside half a hertz with real margin, and on that question alone it
// is the right answer. tests/stretch_cost_test.cpp asks the other question, and
// there 0.35 s is a quarter of the realtime budget on a desktop core -- which
// on a phone is all of it. Hence a control rather than a constant.
//
// Note the distinction: half-hertz *bin spacing* would need a two-second
// block. Half-hertz *accuracy* needs far less, because the vocoder interpolates
// within a bin — the block only has to be fine enough for that estimate to
// converge.

#include <jni.h>
#include <cstring>
#include <atomic>
#include <vector>
#include <new>

#include "signalsmith-stretch.h"
#include "../dsp/wsola_pitch.h"

namespace {

/**
 * Analysis block per quality, as a fraction of the sample rate. See the table
 * above for what each buys.
 *
 * The table measured accuracy and said nothing about cost, and 0.35 s is
 * nearly three times upstream's own default. A block that size is an FFT of
 * some sixteen thousand samples every thirty-millisecond hop, and on a phone
 * that is what a dropout sounds like. So it is the quality control's business
 * now, for both engines rather than only for WSOLA's grain, and the middle
 * setting is the default: 0.419 Hz worst case is under nine cents at the very
 * bottom of the range these were measured over, which is not a thing anyone
 * will hear, and it is a third less work per hop.
 */
constexpr double kVocoderBlockSeconds[3] = {0.12, 0.25, 0.35};
// Hop between analysis frames. Upstream's own presets use 0.03 s.
constexpr double kIntervalSeconds = 0.03;
// Frames accepted in one process() call. The Kotlin side chunks to this.
constexpr int kMaxBlockFrames = 8192;

/**
 * Which algorithm transposes.
 *
 * Two engines with opposite failure modes rather than one compromise. The
 * vocoder is accurate on sustained tones and smears attacks; WSOLA keeps
 * attacks and goes phasey on sustained polyphony. Kept in one Engine so
 * switching costs no allocation and no reconfigure of the audio path.
 */
enum : int32_t { kEngineVocoder = 0, kEngineWsola = 1 };

inline int clampQuality(int q) { return q < 0 ? 0 : (q > 2 ? 2 : q); }

struct Engine {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    tryptify::WsolaPitchShifter wsola;
    int engine = kEngineVocoder;
    float semitones = 0.0f;
    int quality = 1;
    int channels = 2;
    int sampleRate = 48000;
    /**
     * Held across a vocoder reconfigure, which tears down and rebuilds the
     * FFT and so cannot run while the audio thread is inside process().
     *
     * The audio thread *tries* and never waits: finding it held, it passes the
     * block through unpitched rather than blocking, which is a few
     * milliseconds of unshifted audio during an explicit settings change and
     * the one behaviour a realtime callback is allowed to have here. The
     * writer is a coroutine and may spin.
     */
    std::atomic_flag busy = ATOMIC_FLAG_INIT;
    // Planar scratch. Allocated once, at construction, so process() never does.
    std::vector<float> planarIn;
    std::vector<float> planarOut;
    std::vector<float *> inPtrs;
    std::vector<float *> outPtrs;

    /**
     * Splits the block's work across calls instead of doing it in one burst.
     *
     * Without this the whole of a hop's analysis and synthesis lands in a
     * single process() call and the rest do almost nothing, so the cost per
     * callback is spiky in exactly the way an audio deadline cannot absorb.
     * It costs one interval -- thirty milliseconds -- of extra output latency,
     * which is a good trade against dropping out.
     */
    void configureVocoder() {
        const int block = static_cast<int>(sampleRate * kVocoderBlockSeconds[quality]);
        const int interval = static_cast<int>(sampleRate * kIntervalSeconds);
        stretch.configure(channels, block, interval, /*splitComputation=*/true);
        stretch.setTransposeSemitones(semitones);
    }

    Engine(int ch, int rate) : channels(ch), sampleRate(rate) {
        configureVocoder();
        wsola.configure(ch, sampleRate, tryptify::WsolaQuality::kBalanced);
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
    if (auto *e = asEngine(handle)) {
        e->semitones = semitones;
        e->stretch.setTransposeSemitones(semitones);
        e->wsola.setSemitones(semitones);
    }
}

/**
 * Chooses the algorithm, and for WSOLA its grain size.
 *
 * Both engines are kept configured, so this is two stores rather than a
 * rebuild -- but the one being switched *to* is holding stale history, so it
 * is reset here. Quality changes reconfigure the stretcher, which resets it
 * anyway; that is audible, and the caller is expected not to do it mid-phrase.
 */
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeSetEngine(
        JNIEnv *, jclass, jlong handle, jint engine, jint quality) {
    auto *e = asEngine(handle);
    if (!e) return;
    const int wanted = (engine == kEngineWsola) ? kEngineWsola : kEngineVocoder;
    const int q = clampQuality(quality);
    if (q != e->quality) {
        e->quality = q;
        // Rebuilding the FFT cannot run under the audio thread's feet, so the
        // flag is taken first. This is a coroutine, so spinning is allowed;
        // process() only ever tries.
        while (e->busy.test_and_set(std::memory_order_acquire)) {}
        e->configureVocoder();
        e->busy.clear(std::memory_order_release);
        e->wsola.setQuality(static_cast<tryptify::WsolaQuality>(q));
        e->wsola.setSemitones(e->semitones);
    }
    if (wanted != e->engine) {
        e->engine = wanted;
        if (wanted == kEngineWsola) {
            e->wsola.reset();
            e->wsola.setSemitones(e->semitones);
        } else {
            e->stretch.reset();
            e->stretch.setTransposeSemitones(e->semitones);
        }
    }
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeReset(
        JNIEnv *, jclass, jlong handle) {
    if (auto *e = asEngine(handle)) {
        e->stretch.reset();
        e->wsola.reset();
    }
}

/** Total round-trip latency in frames: what the transposition change lags by. */
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_stretch_StretchNative_nativeLatencyFrames(
        JNIEnv *, jclass, jlong handle) {
    auto *e = asEngine(handle);
    if (!e) return 0;
    if (e->engine == kEngineWsola) return e->wsola.latencyFrames();
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

    // Tried, never waited on. A reconfigure is in flight, so this block goes
    // through unpitched rather than the audio thread blocking on it.
    if (e->busy.test_and_set(std::memory_order_acquire)) {
        std::memcpy(out, in, sizeof(float) * static_cast<size_t>(frames) * e->channels);
        return frames;
    }
    struct Release {
        Engine *e;
        ~Release() { e->busy.clear(std::memory_order_release); }
    } release{e};

    if (e->engine == kEngineWsola) {
        // Already interleaved, and it works in place, so it skips the planar
        // round trip the vocoder needs.
        e->wsola.process(in, out, frames);
        return frames;
    }

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
