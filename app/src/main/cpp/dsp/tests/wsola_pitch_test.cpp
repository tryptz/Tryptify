// Host test for the WSOLA pitch shifter. Build and run:
//
//   c++ -std=c++17 -O2 -I.. wsola_pitch_test.cpp -o wsola_pitch_test && ./wsola_pitch_test
//
// Runs on the machine that builds the app, not on a device, so the maths is
// checked where a failure is cheap to read.

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../wsola_pitch.h"

using tryptify::WsolaPitchShifter;
using tryptify::WsolaQuality;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;

std::vector<float> sine(int frames, double hz, double amp = 0.5) {
    std::vector<float> out(static_cast<size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        const float v = static_cast<float>(amp * std::sin(2.0 * M_PI * hz * i / kRate));
        out[static_cast<size_t>(i) * 2] = v;
        out[static_cast<size_t>(i) * 2 + 1] = v;
    }
    return out;
}

/** Amplitude of `hz` in the left channel, by Goertzel over the tail. */
double amplitudeAt(const std::vector<float>& interleaved, double hz, int from) {
    const int frames = static_cast<int>(interleaved.size() / 2);
    const int n = frames - from;
    if (n < 2) return 0.0;
    const double w = 2.0 * M_PI * hz / kRate;
    const double c = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    for (int i = from; i < frames; ++i) {
        const double s0 = interleaved[static_cast<size_t>(i) * 2] + c * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    const double mag = std::sqrt(std::max(0.0, s1 * s1 + s2 * s2 - c * s1 * s2));
    return mag / (n / 2.0);
}

/** Runs the whole signal through in blocks, as the audio path would. */
std::vector<float> run(WsolaPitchShifter& p, const std::vector<float>& in, int block = 1024) {
    std::vector<float> out(in.size(), 0.0f);
    const int frames = static_cast<int>(in.size() / 2);
    for (int at = 0; at < frames; at += block) {
        const int n = std::min(block, frames - at);
        p.process(in.data() + static_cast<size_t>(at) * 2,
                  out.data() + static_cast<size_t>(at) * 2, n);
    }
    return out;
}

WsolaPitchShifter make(WsolaQuality q, double semitones) {
    WsolaPitchShifter p;
    p.configure(2, kRate, q);
    p.setSemitones(semitones);
    return p;
}

}  // namespace

int main() {
    // ── The frame count is exact ─────────────────────────────────────────
    {
        auto in = sine(48000, 440.0);
        auto p = make(WsolaQuality::kBalanced, 7.0);
        auto out = run(p, in);
        check(out.size() == in.size(), "output is the same length as the input");
    }

    // ── Unity is transparent ─────────────────────────────────────────────
    {
        auto in = sine(24000, 440.0);
        auto p = make(WsolaQuality::kBalanced, 0.0);
        auto out = run(p, in);
        const double at440 = amplitudeAt(out, 440.0, 12000);
        check(std::fabs(at440 - 0.5) < 0.08, "no shift leaves the tone where it was");
    }

    // ── The pitch actually moves ─────────────────────────────────────────
    struct Case { double semitones; double factor; const char* name; };
    const Case cases[] = {
        {12.0, 2.0, "+12 st doubles the frequency"},
        {-12.0, 0.5, "-12 st halves the frequency"},
        {7.0, 1.4983, "+7 st lands a fifth up"},
        {-1.0, 0.94387, "-1 st lands a semitone down"},
    };
    for (const auto& c : cases) {
        auto in = sine(96000, 440.0);
        auto p = make(WsolaQuality::kHigh, c.semitones);
        auto out = run(p, in);
        const int from = 48000;  // well past the priming window
        const double moved = amplitudeAt(out, 440.0 * c.factor, from);
        const double stayed = amplitudeAt(out, 440.0, from);
        char msg[160];
        std::snprintf(msg, sizeof(msg), "%s (target %.3f, original %.3f)",
                      c.name, moved, stayed);
        check(moved > 0.25 && moved > stayed * 3.0, msg);
    }

    // ── Tempo is not dragged along ───────────────────────────────────────
    {
        // A burst followed by silence: transposing must not move where the
        // silence starts, which is what separates this from varispeed.
        const int frames = 96000;
        std::vector<float> in(static_cast<size_t>(frames) * 2, 0.0f);
        for (int i = 0; i < frames / 2; ++i) {
            const float v = static_cast<float>(0.5 * std::sin(2.0 * M_PI * 440.0 * i / kRate));
            in[static_cast<size_t>(i) * 2] = v;
            in[static_cast<size_t>(i) * 2 + 1] = v;
        }
        auto p = make(WsolaQuality::kBalanced, 12.0);
        auto out = run(p, in);
        // Energy in the last eighth, long after the burst ended.
        double tail = 0.0;
        for (int i = frames * 7 / 8; i < frames; ++i) {
            tail += std::fabs(out[static_cast<size_t>(i) * 2]);
        }
        tail /= (frames / 8);
        check(tail < 0.02, "silence stays where it was: the tempo did not move");
    }

    // ── The quality presets are the sampler's ────────────────────────────
    {
        WsolaPitchShifter fast, balanced, high;
        fast.configure(2, kRate, WsolaQuality::kFast);
        balanced.configure(2, kRate, WsolaQuality::kBalanced);
        high.configure(2, kRate, WsolaQuality::kHigh);
        // Grain + search + half a grain + slack, which is the gap the stream
        // has to open before the first splice. Pinned exactly, because it is
        // the number the AutoEQ pre-warp glide is matched against.
        check(fast.latencyFrames() == 512 + 128 + 256 + 16, "FAST latency is its grain plus its search");
        check(balanced.latencyFrames() == 1024 + 256 + 512 + 16, "BALANCED likewise");
        check(high.latencyFrames() == 2048 + 512 + 1024 + 16, "HIGH likewise");
        // The whole point of the trade: HIGH costs latency FAST does not.
        check(high.latencyFrames() > fast.latencyFrames() * 3,
              "HIGH buys its bass reach with latency");
    }

    // ── Bass survives at HIGH and wanders at FAST ────────────────────────
    {
        // 110 Hz needs a search radius that can reach a whole period. FAST's
        // 128 samples reaches only 375 Hz, so the splices land mid-cycle.
        auto in = sine(96000, 110.0);
        auto high = make(WsolaQuality::kHigh, 0.0);
        auto outHigh = run(high, in);
        const double held = amplitudeAt(outHigh, 110.0, 48000);
        check(held > 0.35, "HIGH holds a 110 Hz tone together");
    }

    // ── Long runs stay bounded and stable ────────────────────────────────
    {
        // Thirty seconds forces the sliding window to compact many times. A
        // compaction that lost the read position would drift or crash here.
        auto in = sine(kRate * 30, 440.0);
        auto p = make(WsolaQuality::kBalanced, 5.0);
        auto out = run(p, in, 512);
        const double late = amplitudeAt(out, 440.0 * std::pow(2.0, 5.0 / 12.0), kRate * 28);
        check(late > 0.25, "still transposing after thirty seconds of compactions");
        bool finite = true;
        for (float v : out) if (!std::isfinite(v)) { finite = false; break; }
        check(finite, "no NaN or infinity anywhere in thirty seconds");
    }

    // ── Silence in, silence out ──────────────────────────────────────────
    {
        std::vector<float> in(static_cast<size_t>(48000) * 2, 0.0f);
        auto p = make(WsolaQuality::kBalanced, 3.0);
        auto out = run(p, in);
        double peak = 0.0;
        for (float v : out) peak = std::max(peak, static_cast<double>(std::fabs(v)));
        check(peak < 1e-6, "silence does not become noise");
    }

    std::printf("\n%s\n", failures == 0 ? "all good" : "FAILURES ABOVE");
    return failures == 0 ? 0 : 1;
}
