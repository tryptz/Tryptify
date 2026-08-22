// Host test for the pitch shifter's frequency accuracy.
//
// The claim this pins: with the analysis block the JNI bridge configures
// (kBlockSeconds), a transposed tone lands within half a hertz of the exact
// equal-tempered target. That is a property of the block size, not of the
// arithmetic -- a phase vocoder estimates each partial's frequency from its
// analysis block, so the block is what bounds the error. Shrinking it to the
// library's own default costs roughly an order of magnitude of accuracy.
//
// Build and run (as with cpp/atmos/tests):
//
//   cd app/src/main/cpp/stretch/tests
//   c++ -std=c++17 -O2 -I../../../../../../third_party/signalsmith-stretch \
//       stretch_precision_test.cpp -o stretch_precision_test && ./stretch_precision_test

#include "signalsmith-stretch.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

constexpr double kSampleRate = 48000.0;
// Must track kBlockSeconds / kIntervalSeconds in ../stretch_jni.cpp.
constexpr double kBlockSeconds = 0.35;
constexpr double kIntervalSeconds = 0.03;
// What the block above is chosen to guarantee.
constexpr double kToleranceHz = 0.5;

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::printf("  FAIL: %s\n", what);
        ++failures;
    }
}

/**
 * Frequency of a near-pure tone, from interpolated upward zero crossings.
 *
 * Preferred over a Goertzel scan because it is O(n) and does not quantise the
 * answer to a search grid -- the errors being measured here are a fraction of
 * a hertz, which a grid fine enough to resolve makes ruinously slow.
 */
double measureHz(const std::vector<float> &x, int start, int end) {
    double first = -1, last = -1;
    long cycles = 0;
    for (int i = start + 1; i < end; ++i) {
        if (x[i - 1] <= 0.0f && x[i] > 0.0f) {
            double t = (i - 1) + double(-x[i - 1]) / (x[i] - x[i - 1]);
            if (first < 0) {
                first = t;
            } else {
                last = t;
                ++cycles;
            }
        }
    }
    if (cycles < 8 || last < 0) return 0.0;
    return kSampleRate * cycles / (last - first);
}

double shiftedToneHz(double fundamental, double semitones) {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    stretch.configure(1, int(kSampleRate * kBlockSeconds), int(kSampleRate * kIntervalSeconds));
    stretch.setTransposeSemitones(float(semitones));

    const int n = int(kSampleRate * 8);
    std::vector<float> in(n), out(n);
    for (int i = 0; i < n; ++i) {
        in[i] = float(std::sin(2.0 * M_PI * fundamental * i / kSampleRate));
    }
    float *inPtr[1] = {in.data()};
    float *outPtr[1] = {out.data()};
    stretch.process(inPtr, n, outPtr, n);

    const int settled = stretch.inputLatency() + stretch.outputLatency() + int(kSampleRate);
    return measureHz(out, settled, n - 16);
}

}  // namespace

int main() {
    std::printf("pitch accuracy at a %.2f s block (%.2f Hz bins)\n",
                kBlockSeconds, kSampleRate / (kSampleRate * kBlockSeconds));

    const double semitones[] = {-12.0, -7.0, -5.0, 3.0, 7.0, 12.0};
    const double fundamentals[] = {82.4, 110.0, 220.0, 440.0, 880.0, 1760.0};

    double worstHz = 0.0;
    for (double st : semitones) {
        for (double f0 : fundamentals) {
            const double expected = f0 * std::pow(2.0, st / 12.0);
            const double got = shiftedToneHz(f0, st);
            check(got > 0.0, "tone was not measurable");
            const double errHz = std::fabs(got - expected);
            if (errHz > worstHz) worstHz = errHz;
            if (errHz > kToleranceHz) {
                std::printf("  FAIL: %+.0f st from %.1f Hz -> expected %.4f, got %.4f (%.4f Hz off)\n",
                            st, f0, expected, got, errHz);
                ++failures;
            }
        }
    }
    std::printf("  worst error %.4f Hz (tolerance %.2f Hz)\n", worstHz, kToleranceHz);

    // A transposition of zero must be a genuine no-op, not a round trip through
    // the vocoder that happens to come back close.
    const double unity = shiftedToneHz(440.0, 0.0);
    check(std::fabs(unity - 440.0) < 0.01, "zero semitones did not leave the tone alone");

    if (failures == 0) {
        std::printf("stretch_precision_test: OK\n");
        return 0;
    }
    std::printf("stretch_precision_test: %d failure(s)\n", failures);
    return 1;
}
