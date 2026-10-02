// Wave Candy scope: a stereo ring the audio thread fills and the UI renders
// from, once per frame, into ready-to-draw line segments.
//
// Why native, and why it was choppy before: the analyzer receives audio in
// chunks of tens of milliseconds, so "the latest N samples" only changed when
// a chunk landed and the scope stepped at that rate whatever the display did.
// Render interpolates the read position between chunks from the clock — the
// window lags one chunk and slides smoothly through it — and reduces the window
// to points peak-preservingly, so a transient between two points still shows.
//
// Audio-thread rules (docs/agent-playbook.md, Realtime Audio): push allocates
// nothing, takes no lock and logs nothing; positions cross threads through
// atomics. A render can race a push by one chunk, which a scope cannot show.

#include <jni.h>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <ctime>

namespace {

constexpr int kRing = 1 << 15;            // frames — ~0.68 s at 48 kHz
constexpr int kMask = kRing - 1;

float gL[kRing];
float gR[kRing];
std::atomic<uint64_t> gWritten{0};        // frames pushed, ever
std::atomic<int64_t> gLastPushNs{0};
std::atomic<int> gLastChunk{0};
std::atomic<int> gSampleRate{48000};

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

// The interpolated end of the visible window, in absolute frames: one chunk
// behind the newest sample, advanced by the time since that chunk arrived.
double playhead(uint64_t written) {
    const int chunk = gLastChunk.load(std::memory_order_relaxed);
    const int sr = gSampleRate.load(std::memory_order_relaxed);
    const double elapsed = double(nowNs() - gLastPushNs.load(std::memory_order_relaxed)) * sr / 1e9;
    double end = double(written) - chunk + std::fmin(std::fmax(elapsed, 0.0), double(chunk));
    if (end > double(written)) end = double(written);
    if (end < 0) end = 0;
    return end;
}

inline float sampleAt(int64_t frame, uint64_t written, int which /*0 L, 1 R, 2 mid*/) {
    if (frame < 0 || uint64_t(frame) >= written || written - uint64_t(frame) > uint64_t(kRing)) return 0.f;
    const int i = int(frame & kMask);
    return which == 0 ? gL[i] : which == 1 ? gR[i] : 0.5f * (gL[i] + gR[i]);
}

// Peak-preserving reduction: of each bucket, the sample furthest from zero.
float bucketPeak(int64_t from, int64_t to, uint64_t written, int which) {
    float best = 0.f;
    for (int64_t f = from; f < to; ++f) {
        const float v = sampleAt(f, written, which);
        if (std::fabs(v) > std::fabs(best)) best = v;
    }
    return best;
}

// Writes (points - 1) segments, 4 floats each, for one channel.
int lineFor(float* out, int points, int64_t start, double perPoint, uint64_t written, int which,
            float width, float baseY, float amp) {
    float px = 0.f, py = 0.f;
    int n = 0;
    for (int j = 0; j < points; ++j) {
        const int64_t a = start + int64_t(j * perPoint);
        const int64_t b = start + int64_t((j + 1) * perPoint);
        float v = bucketPeak(a, b > a ? b : a + 1, written, which);
        if (v > 1.f) v = 1.f; else if (v < -1.f) v = -1.f;
        const float x = width * float(j) / float(points - 1);
        const float y = baseY - v * amp;
        if (j > 0) {
            out[n++] = px; out[n++] = py; out[n++] = x; out[n++] = y;
        }
        px = x; py = y;
    }
    return n;
}

}  // namespace

extern "C" {

// Interleaved stereo [L0 R0 L1 R1 ...], [frames] frames.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_eq_WaveScopeNative_nativePush(
        JNIEnv* env, jclass, jfloatArray interleaved, jint frames, jint sampleRate) {
    if (frames <= 0) return;
    auto* p = static_cast<float*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
    if (!p) return;
    const uint64_t w = gWritten.load(std::memory_order_relaxed);
    for (int i = 0; i < frames; ++i) {
        const int idx = int((w + i) & kMask);
        gL[idx] = p[2 * i];
        gR[idx] = p[2 * i + 1];
    }
    env->ReleasePrimitiveArrayCritical(interleaved, p, JNI_ABORT);
    gSampleRate.store(sampleRate > 0 ? sampleRate : 48000, std::memory_order_relaxed);
    gLastChunk.store(frames, std::memory_order_relaxed);
    gLastPushNs.store(nowNs(), std::memory_order_relaxed);
    gWritten.store(w + frames, std::memory_order_release);
}

// Fills [out] with line segments for the window ending at the interpolated
// playhead; returns how many floats were written. Stereo draws L across the
// top and R across the bottom; mono one summed line through the middle.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_eq_WaveScopeNative_nativeRender(
        JNIEnv* env, jclass, jfloatArray outSegs, jint points, jfloat windowMs, jboolean stereo,
        jfloat width, jfloat height, jfloat gain) {
    const uint64_t written = gWritten.load(std::memory_order_acquire);
    if (written < 2 || points < 2) return 0;
    const jsize cap = env->GetArrayLength(outSegs);
    const int perLine = (points - 1) * 4;
    if (cap < perLine * (stereo ? 2 : 1)) return 0;

    const int sr = gSampleRate.load(std::memory_order_relaxed);
    double win = double(windowMs) * sr / 1000.0;
    if (win < points) win = points;
    if (win > kRing / 2) win = kRing / 2;
    const double end = playhead(written);
    const int64_t start = int64_t(end - win);
    const double perPoint = win / points;

    auto* out = static_cast<float*>(env->GetPrimitiveArrayCritical(outSegs, nullptr));
    if (!out) return 0;
    int n;
    if (stereo) {
        const float amp = height * 0.13f * gain;
        n = lineFor(out, points, start, perPoint, written, 0, width, height * 0.25f, amp);
        n += lineFor(out + n, points, start, perPoint, written, 1, width, height * 0.75f, amp);
    } else {
        n = lineFor(out, points, start, perPoint, written, 2, width, height * 0.5f, height * 0.22f * gain);
    }
    env->ReleasePrimitiveArrayCritical(outSegs, out, 0);
    return n;
}

// RMS of the kick band (one-pole low-pass, ~150 Hz at 48 kHz) over the last
// [frames] frames before the interpolated playhead.
JNIEXPORT jfloat JNICALL
Java_tf_monochrome_android_audio_eq_WaveScopeNative_nativeLowBandRms(JNIEnv*, jclass, jint frames) {
    const uint64_t written = gWritten.load(std::memory_order_acquire);
    if (written < 2 || frames <= 0) return 0.f;
    const int64_t end = int64_t(playhead(written));
    float y = 0.f, sum = 0.f;
    for (int64_t f = end - frames; f < end; ++f) {
        y += (sampleAt(f, written, 2) - y) * 0.02f;
        sum += y * y;
    }
    return std::sqrt(sum / float(frames));
}

}  // extern "C"
