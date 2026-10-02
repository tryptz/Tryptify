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

constexpr int kRing = 1 << 17;            // frames — ~2.7 s at 48 kHz, room for long bursts
constexpr int kMask = kRing - 1;

float gL[kRing];
float gR[kRing];
std::atomic<uint64_t> gWritten{0};        // frames pushed, ever
std::atomic<int> gSampleRate{48000};

int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return int64_t(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

// ── The read clock (UI thread only) ─────────────────────────────────────
//
// The analyzer is fed ahead of what is heard, and how it is fed depends on the
// stream: some arrive in small steady chunks, others in bursts of hundreds of
// milliseconds with gaps between. Sliding through "the newest chunk" was smooth
// for the first kind and stepped for the second — the same scope looked
// instant on one song and low-fps on the next. So the read position keeps its
// own clock: it advances at exactly the sample rate each frame, trailing the
// newest sample by a lag that adapts to the largest burst seen lately, and is
// only nudged toward that target, never jumped, unless it has fallen out of the
// ring or overtaken the writer.

double gReadPos = -1.0;           // absolute frames; < 0 until first render
int64_t gLastAdvanceNs = 0;
uint64_t gSeenWritten = 0;
double gBurst = 2048.0;           // decaying max of frames that arrived between two frames

constexpr double kMinLag = 1024.0;
constexpr double kMaxLag = kRing / 2.0;

// Advances and returns the end of the visible window. Idempotent within a
// frame: a second call at the same time moves nothing.
double advancePlayhead(uint64_t written, int64_t now, int sr) {
    const double dt = gLastAdvanceNs == 0 ? 0.0 : double(now - gLastAdvanceNs) / 1e9;
    gLastAdvanceNs = now;

    // How bursty is delivery? The jump in `written` since the last frame, as a
    // max that decays with a ~2 s half-life.
    if (written > gSeenWritten) {
        const double jump = double(written - gSeenWritten);
        gBurst = std::fmax(jump, gBurst * std::pow(0.5, dt / 2.0));
        gSeenWritten = written;
    } else if (dt > 0) {
        gBurst *= std::pow(0.5, dt / 2.0);
    }
    double lag = gBurst * 1.25 + 256.0;
    if (lag < kMinLag) lag = kMinLag;
    if (lag > kMaxLag) lag = kMaxLag;
    const double target = double(written) - lag;

    if (gReadPos < 0 || gReadPos > double(written) ||
        double(written) - gReadPos > kRing - 4096 || dt > 0.5) {
        // First frame, overtook the writer, fell out of the ring, or a long
        // stall (pause, seek, backgrounded): place it, don't glide it.
        gReadPos = target;
    } else if (dt > 0) {
        gReadPos += dt * sr;                                  // real-time scroll
        // Gentle pull to the lag. The target itself steps with every burst,
        // so a firm pull turns bursts into a speed wobble; this keeps it ~3%.
        gReadPos += (target - gReadPos) * std::fmin(1.0, dt * 0.3);
        if (gReadPos > double(written)) gReadPos = double(written);
    }
    return gReadPos < 0 ? 0.0 : gReadPos;
}

double playhead(uint64_t written) {
    return advancePlayhead(written, nowNs(), gSampleRate.load(std::memory_order_relaxed));
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
