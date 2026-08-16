// Host-compilable tests for the time stretcher.
//
//   c++ -std=c++17 -I.. wsola_test.cpp -o wsola_test && ./wsola_test
//
// What these have to prove is the one claim the feature rests on: that
// changing how long a sample takes to play does not change what it sounds
// like. So the assertions are about *length* and *frequency* separately —
// stretching must move the first and leave the second alone. Anything that
// only checked length would pass just as happily for plain resampling, which
// is exactly the thing this exists not to be.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include "../wsola.h"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool cond, const char* expr, const char* file, int line) {
    ++g_checks;
    if (!cond) {
        ++g_failures;
        std::printf("  FAIL %s:%d  %s\n", file, line, expr);
    }
}

void check_near(double a, double b, double tol, const char* expr, const char* file, int line) {
    ++g_checks;
    if (std::fabs(a - b) > tol) {
        ++g_failures;
        std::printf("  FAIL %s:%d  %s  (%.6f vs %.6f)\n", file, line, expr, a, b);
    }
}

#define CHECK(c) check((c), #c, __FILE__, __LINE__)
#define CHECK_NEAR(a, b, tol) check_near((a), (b), (tol), #a " ~= " #b, __FILE__, __LINE__)

using tryptify::Wsola;

constexpr int kRate = 48000;

std::vector<float> tone(int frames, double hz, float amplitude = 0.5f) {
    std::vector<float> out(static_cast<size_t>(frames));
    for (int i = 0; i < frames; ++i) {
        out[static_cast<size_t>(i)] =
            amplitude * static_cast<float>(std::sin(2.0 * M_PI * hz * i / kRate));
    }
    return out;
}

/**
 * Dominant frequency, by counting rising zero crossings over the middle of the
 * signal. Cruder than an FFT and entirely sufficient: the question is only
 * whether a 440 Hz tone came out at 440 Hz or at 880 Hz.
 */
double dominantHz(const std::vector<float>& signal, int from, int to) {
    int crossings = 0;
    for (int i = from + 1; i < to && i < static_cast<int>(signal.size()); ++i) {
        if (signal[static_cast<size_t>(i - 1)] <= 0.0f && signal[static_cast<size_t>(i)] > 0.0f) {
            ++crossings;
        }
    }
    const double seconds = static_cast<double>(to - from) / kRate;
    return seconds > 0.0 ? crossings / seconds : 0.0;
}

double peak(const std::vector<float>& signal, int from, int to) {
    double best = 0.0;
    for (int i = from; i < to && i < static_cast<int>(signal.size()); ++i) {
        best = std::fmax(best, std::fabs(signal[static_cast<size_t>(i)]));
    }
    return best;
}

/** Runs the stretcher to exhaustion and returns everything it produced. */
std::vector<float> stretch(const std::vector<float>& src, double ratio, int window = 1024) {
    Wsola wsola;
    wsola.configure(window, window / Wsola::DEFAULT_SEARCH_DIVISOR);
    wsola.setRatio(ratio);
    wsola.reset(0.0);

    std::vector<float> out;
    float block[512];
    while (true) {
        const int produced = wsola.produce(src.data(), static_cast<int>(src.size()), block, 512);
        if (produced <= 0) break;
        out.insert(out.end(), block, block + produced);
        if (out.size() > src.size() * 32) break;  // runaway guard
    }
    return out;
}

// ── length ──────────────────────────────────────────────────────────────

void testRatioChangesLength() {
    std::printf("length follows the ratio\n");
    const auto source = tone(48000, 440.0);

    // The output should be about ratio x the input. The tolerance is wide
    // because the last partial grain is dropped rather than half-written.
    for (double ratio : {0.5, 0.75, 1.5, 2.0}) {
        const auto out = stretch(source, ratio);
        const double expected = source.size() * ratio;
        const double error = std::fabs(static_cast<double>(out.size()) - expected) / expected;
        CHECK(error < 0.05);
        if (error >= 0.05) {
            std::printf("    ratio %.2f produced %zu, expected ~%.0f\n",
                        ratio, out.size(), expected);
        }
    }
}

void testUnityIsPerceptuallyTransparent() {
    std::printf("unity ratio\n");
    // Deliberately NOT a sample-for-sample comparison. At ratio 1 the
    // similarity search is still free to splice a whole period away, which is
    // correct behaviour — the waveform is identical to the ear and offset in
    // time by one cycle. Asserting bit-identity here would be asserting that
    // the search is switched off.
    //
    // Sample-identity at unity is a property of the *engine*, which bypasses
    // the stretcher entirely when nothing is being stretched. That is where it
    // is worth testing, and it is: see the engine's own suite.
    const auto source = tone(24000, 440.0, 0.5f);
    const auto out = stretch(source, 1.0);

    CHECK(out.size() > source.size() * 0.9);
    if (out.size() <= 8000) return;
    const double hz = dominantHz(out, 4000, static_cast<int>(out.size()) - 4000);
    CHECK_NEAR(hz, 440.0, 440.0 * 0.03);
    const double level = peak(out, 4000, static_cast<int>(out.size()) - 4000);
    CHECK_NEAR(level, 0.5, 0.06);
}

// ── pitch ───────────────────────────────────────────────────────────────

void testPitchSurvivesStretching() {
    std::printf("pitch is preserved\n");
    // The entire point. Resampling would move these in lockstep with the
    // length; WSOLA must not.
    const auto source = tone(48000, 440.0);

    for (double ratio : {0.5, 1.0, 2.0}) {
        const auto out = stretch(source, ratio);
        CHECK(out.size() > 8000);
        if (out.size() <= 8000) continue;
        const int from = 4000;
        const int to = static_cast<int>(out.size()) - 4000;
        const double hz = dominantHz(out, from, to);
        // Within 3% of the original: zero-crossing counting is approximate and
        // the splices add a little jitter, but a factor-of-two error — which
        // is what resampling would give — is nowhere near this tolerance.
        CHECK_NEAR(hz, 440.0, 440.0 * 0.03);
        if (std::fabs(hz - 440.0) > 440.0 * 0.03) {
            std::printf("    ratio %.2f gave %.1f Hz\n", ratio, hz);
        }
    }
}

void testLowAndHighTonesBothSurvive() {
    std::printf("pitch across the range\n");
    // A 2048-sample window so the radius reaches 512 — one full period of a
    // 94 Hz tone. With the 1024 default the 110 Hz case measures ~8% flat,
    // which is the documented floor rather than a bug, and is exactly why the
    // engine configures 2048.
    for (double hz : {110.0, 440.0, 1760.0}) {
        const auto out = stretch(tone(48000, hz), 2.0, 2048);
        if (out.size() <= 12000) {
            CHECK(false);
            continue;
        }
        const double measured = dominantHz(out, 6000, static_cast<int>(out.size()) - 6000);
        CHECK_NEAR(measured, hz, hz * 0.05);
        if (std::fabs(measured - hz) > hz * 0.05) {
            std::printf("    %.0f Hz came out as %.1f Hz\n", hz, measured);
        }
    }
}

// ── level ───────────────────────────────────────────────────────────────

void testLevelIsPreserved() {
    std::printf("level is preserved\n");
    // Two Hann windows at 50% overlap sum to one, so the overlap-add should
    // be unity gain. If this drifts, every stretched sample comes out quieter
    // or louder than the one next to it in the pattern.
    const auto source = tone(48000, 440.0, 0.5f);
    for (double ratio : {0.5, 1.0, 2.0}) {
        const auto out = stretch(source, ratio);
        if (out.size() <= 8000) {
            CHECK(false);
            continue;
        }
        const double level = peak(out, 4000, static_cast<int>(out.size()) - 4000);
        CHECK_NEAR(level, 0.5, 0.1);
        if (std::fabs(level - 0.5) > 0.1) {
            std::printf("    ratio %.2f peaked at %.3f\n", ratio, level);
        }
    }
}

void testSilenceStaysSilent() {
    std::printf("silence\n");
    const std::vector<float> quiet(24000, 0.0f);
    const auto out = stretch(quiet, 2.0);
    for (float v : out) {
        CHECK(std::fabs(v) < 1e-6f);
        if (std::fabs(v) >= 1e-6f) break;
    }
}

// ── boundaries ──────────────────────────────────────────────────────────

void testShortSourceTerminatesRatherThanSpinning() {
    std::printf("short source\n");
    // Anything shorter than a window cannot produce a grain. The important
    // property is that produce() reports zero rather than looping forever
    // looking for one.
    const auto tiny = tone(300, 440.0);
    Wsola wsola;
    wsola.configure(1024, 128);
    wsola.setRatio(2.0);
    wsola.reset(0.0);
    float block[256];
    CHECK(wsola.produce(tiny.data(), static_cast<int>(tiny.size()), block, 256) == 0);
}

void testRatioIsClamped() {
    std::printf("ratio clamping\n");
    Wsola wsola;
    wsola.configure(1024, 128);
    wsola.setRatio(0.0);
    CHECK(wsola.ratio() > 0.0);
    wsola.setRatio(-4.0);
    CHECK(wsola.ratio() > 0.0);
    wsola.setRatio(1e9);
    CHECK(wsola.ratio() <= 20.0);
}

void testResetRewinds() {
    std::printf("reset\n");
    const auto source = tone(48000, 440.0);
    Wsola wsola;
    wsola.configure(1024, 128);
    wsola.setRatio(1.5);
    wsola.reset(0.0);

    float block[512];
    wsola.produce(source.data(), static_cast<int>(source.size()), block, 512);
    const double advanced = wsola.sourcePosition();
    CHECK(advanced > 0.0);

    wsola.reset(0.0);
    CHECK_NEAR(wsola.sourcePosition(), 0.0, 1e-9);

    // And resetting to a position starts there, which is what a sample with a
    // trimmed start needs.
    wsola.reset(1000.0);
    CHECK_NEAR(wsola.sourcePosition(), 1000.0, 1e-9);
}

void testSearchImprovesOnFixedSplices() {
    std::printf("similarity search\n");
    // With the search disabled the splices land wherever they fall and the
    // partials cancel; with it on they line up. Comparing the two is the only
    // direct evidence that the "WS" in WSOLA is doing anything.
    const auto source = tone(48000, 440.0, 0.5f);

    Wsola naive;
    naive.configure(1024, 0);
    naive.setRatio(1.7);
    naive.reset(0.0);

    Wsola aligned;
    aligned.configure(1024, 128);
    aligned.setRatio(1.7);
    aligned.reset(0.0);

    std::vector<float> naiveOut, alignedOut;
    float block[512];
    for (int i = 0; i < 60; ++i) {
        int n = naive.produce(source.data(), static_cast<int>(source.size()), block, 512);
        if (n <= 0) break;
        naiveOut.insert(naiveOut.end(), block, block + n);
    }
    for (int i = 0; i < 60; ++i) {
        int n = aligned.produce(source.data(), static_cast<int>(source.size()), block, 512);
        if (n <= 0) break;
        alignedOut.insert(alignedOut.end(), block, block + n);
    }

    CHECK(naiveOut.size() > 8000);
    CHECK(alignedOut.size() > 8000);
    if (naiveOut.size() > 8000 && alignedOut.size() > 8000) {
        // Frequency accuracy, not peak level. A mid-cycle splice adds or eats
        // part of a period, so the measured pitch drifts; peak level barely
        // moves for a pure tone and would pass either way, which makes it
        // useless as evidence.
        const double naiveHz =
            dominantHz(naiveOut, 4000, static_cast<int>(naiveOut.size()) - 4000);
        const double alignedHz =
            dominantHz(alignedOut, 4000, static_cast<int>(alignedOut.size()) - 4000);
        const double naiveError = std::fabs(naiveHz - 440.0);
        const double alignedError = std::fabs(alignedHz - 440.0);
        std::printf("    naive %.1f Hz (err %.1f), aligned %.1f Hz (err %.1f)\n",
                    naiveHz, naiveError, alignedHz, alignedError);
        CHECK(alignedError <= naiveError);
        // And the aligned one has to be genuinely accurate, not merely better
        // than something broken.
        CHECK(alignedError < 440.0 * 0.03);
    }
}

// ── stereo ──────────────────────────────────────────────────────────────

/** Deterministic white-ish noise. Sharp autocorrelation, so lags are readable. */
std::vector<float> noise(int frames, uint32_t seed, float amplitude = 0.4f) {
    std::vector<float> out(static_cast<size_t>(frames));
    uint32_t s = seed | 1u;
    for (int i = 0; i < frames; ++i) {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        const float unit = static_cast<float>(s & 0xFFFFFFu) / 8388608.0f - 1.0f;
        out[static_cast<size_t>(i)] = unit * amplitude;
    }
    return out;
}

/**
 * The lag, in samples, at which [b] best matches [a] over a window.
 *
 * This is the measurement the whole stereo claim rests on: a constant lag
 * between the channels is a stable image position, and a lag that moves
 * between windows is an image that swims.
 */
int bestLag(const std::vector<float>& a, const std::vector<float>& b,
            int from, int span, int maxLag) {
    double best = -1e30;
    int bestK = 0;
    for (int k = -maxLag; k <= maxLag; ++k) {
        double sum = 0.0;
        for (int i = from; i < from + span; ++i) {
            const int j = i + k;
            if (j < 0 || j >= static_cast<int>(b.size()) || i >= static_cast<int>(a.size())) continue;
            sum += static_cast<double>(a[static_cast<size_t>(i)]) *
                   static_cast<double>(b[static_cast<size_t>(j)]);
        }
        if (sum > best) {
            best = sum;
            bestK = k;
        }
    }
    return bestK;
}

struct StereoOut {
    std::vector<float> l, r;
};

StereoOut stretchStereo(const std::vector<float>& srcL, const std::vector<float>& srcR,
                        double ratio, int window = 1024) {
    Wsola wsola;
    wsola.configure(window, window / Wsola::DEFAULT_SEARCH_DIVISOR);
    wsola.setRatio(ratio);
    wsola.reset(0.0);

    StereoOut out;
    float blockL[512], blockR[512];
    const int len = static_cast<int>(std::min(srcL.size(), srcR.size()));
    while (true) {
        const int produced = wsola.produce(srcL.data(), srcR.data(), len, blockL, blockR, 512);
        if (produced <= 0) break;
        out.l.insert(out.l.end(), blockL, blockL + produced);
        out.r.insert(out.r.end(), blockR, blockR + produced);
        if (out.l.size() > srcL.size() * 32) break;
    }
    return out;
}

/**
 * The image must not swim.
 *
 * A fixed inter-channel delay is a fixed position in the stereo field. Both
 * channels are spliced at the same offset, so that delay has to survive
 * stretching exactly — not approximately, exactly, because the offset is an
 * integer number of samples applied to both.
 *
 * The control is the failure mode this design exists to avoid: two independent
 * stretchers, one per channel, each free to pick its own splice. If that also
 * held the image, linking the offsets would be pointless.
 */
void testStereoImageSurvivesStretching() {
    std::printf("stereo image is held\n");
    constexpr int kDelay = 20;
    constexpr int kFrames = 48000;

    // A correlated component that carries the image, plus enough uncorrelated
    // material in the right channel that two independent searches genuinely
    // diverge — otherwise the control would pass by coincidence.
    const std::vector<float> a = noise(kFrames, 0x1234567u);
    const std::vector<float> b = noise(kFrames, 0x89ABCDEu);
    std::vector<float> left = a;
    std::vector<float> right(static_cast<size_t>(kFrames), 0.0f);
    for (int i = kDelay; i < kFrames; ++i) {
        right[static_cast<size_t>(i)] =
            0.75f * a[static_cast<size_t>(i - kDelay)] + 0.35f * b[static_cast<size_t>(i)];
    }

    CHECK(bestLag(left, right, 4000, 8000, 60) == kDelay);  // the source itself

    const StereoOut linked = stretchStereo(left, right, 1.7);
    CHECK(linked.l.size() > 40000);
    CHECK(linked.l.size() == linked.r.size());

    int linkedDrift = 0;
    for (int w = 0; w < 3; ++w) {
        const int from = 6000 + w * 9000;
        const int lag = bestLag(linked.l, linked.r, from, 8000, 60);
        linkedDrift = std::max(linkedDrift, std::abs(lag - kDelay));
    }

    // The control: same material, one stretcher per channel.
    const std::vector<float> soloL = stretch(left, 1.7);
    const std::vector<float> soloR = stretch(right, 1.7);
    int soloDrift = 0;
    for (int w = 0; w < 3; ++w) {
        const int from = 6000 + w * 9000;
        const int lag = bestLag(soloL, soloR, from, 8000, 60);
        soloDrift = std::max(soloDrift, std::abs(lag - kDelay));
    }

    std::printf("    linked drift %d samples, independent drift %d samples\n",
                linkedDrift, soloDrift);
    CHECK(linkedDrift == 0);
    CHECK(soloDrift > linkedDrift);
}

/** A silent side must not break the splice search on the other one. */
void testHardPannedSourceKeepsItsPitch() {
    std::printf("hard-panned source\n");
    const std::vector<float> left = tone(48000, 440.0);
    const std::vector<float> right(48000, 0.0f);

    const StereoOut out = stretchStereo(left, right, 2.0, 2048);
    CHECK(out.l.size() > 80000);

    const double hz = dominantHz(out.l, 8000, static_cast<int>(out.l.size()) - 8000);
    CHECK_NEAR(hz, 440.0, 440.0 * 0.03);

    // Silence in, silence out — the mid-signal search must not leak L into R.
    CHECK(peak(out.r, 0, static_cast<int>(out.r.size())) < 1e-6);
}

/** A mono source read through the stereo path is the same on both sides. */
void testMonoSourceIsCentred() {
    std::printf("mono source\n");
    const std::vector<float> src = tone(24000, 440.0);

    Wsola wsola;
    wsola.configure(1024, 1024 / Wsola::DEFAULT_SEARCH_DIVISOR);
    wsola.setRatio(1.5);
    wsola.reset(0.0);

    int pulled = 0;
    float l = 0.0f, r = 0.0f;
    while (wsola.pull(src.data(), nullptr, static_cast<int>(src.size()), l, r)) {
        CHECK(l == r);
        if (++pulled > 60000) break;
    }
    CHECK(pulled > 30000);
}

/**
 * The voice reads the stretcher one frame at a time; the tests read it in
 * blocks. They have to be the same algorithm, or every property proved above
 * is proved about code the sampler does not run.
 */
void testPullMatchesProduce() {
    std::printf("pull matches produce\n");
    const std::vector<float> src = tone(24000, 330.0);
    const std::vector<float> bulk = stretch(src, 1.4);

    Wsola wsola;
    wsola.configure(1024, 1024 / Wsola::DEFAULT_SEARCH_DIVISOR);
    wsola.setRatio(1.4);
    wsola.reset(0.0);

    size_t i = 0;
    float l = 0.0f, r = 0.0f;
    while (wsola.pull(src.data(), nullptr, static_cast<int>(src.size()), l, r)) {
        if (i >= bulk.size()) break;
        if (l != bulk[i]) {
            std::printf("    diverged at %zu: %.9f vs %.9f\n", i, l, bulk[i]);
            CHECK(false);
            return;
        }
        ++i;
    }
    CHECK(i == bulk.size());
}

/** Every legal window is an exact Hann, whatever size the quality mode asks for. */
void testWindowSizesAreRoundedToPowersOfTwo() {
    std::printf("window sizing\n");
    Wsola wsola;
    const int requested[] = {100, 128, 300, 512, 1000, 1024, 2047, 2048, 9000};
    const int expected[] = {128, 128, 256, 512, 512, 1024, 1024, 2048, 2048};
    for (int i = 0; i < 9; ++i) {
        wsola.configure(requested[i], 64);
        CHECK(wsola.window() == expected[i]);
    }
    // The radius can never exceed half the window, or a grain could be spliced
    // from beyond the next one's territory.
    wsola.configure(256, 10000);
    CHECK(wsola.searchRadius() == 128);
}

}  // namespace

int main() {
    std::printf("wsola tests\n");
    testRatioChangesLength();
    testUnityIsPerceptuallyTransparent();
    testPitchSurvivesStretching();
    testLowAndHighTonesBothSurvive();
    testLevelIsPreserved();
    testSilenceStaysSilent();
    testShortSourceTerminatesRatherThanSpinning();
    testRatioIsClamped();
    testResetRewinds();
    testSearchImprovesOnFixedSplices();
    testStereoImageSurvivesStretching();
    testHardPannedSourceKeepsItsPitch();
    testMonoSourceIsCentred();
    testPullMatchesProduce();
    testWindowSizesAreRoundedToPowersOfTwo();

    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
