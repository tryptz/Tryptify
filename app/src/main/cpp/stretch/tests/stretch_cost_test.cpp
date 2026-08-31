// Host measurement of the vocoder's cost *shape*, which is what a dropout is
// made of. Accuracy has its own test next door; this one asks the question that
// one never did -- what the block chosen for accuracy costs per call.
//
// Two numbers matter and neither is the mean.
//
//   p99/mean  is burstiness. A phase vocoder does a block's analysis and
//             synthesis when a hop boundary is crossed, so without
//             `splitComputation` one call in every few does all of it and the
//             rest do nothing. An audio deadline cannot absorb that shape, no
//             matter how comfortable the average looks.
//   p99       against the realtime budget for the same number of frames is the
//             headroom, and it has to survive being multiplied by however much
//             slower a phone core is than this host at float FFT work -- call
//             it four to six times -- and then shared with the rest of the
//             chain.
//
// Measured here (x86-64, -O2, best of three runs), 48 kHz stereo, +5 st:
//
//     chunk  block  split |   mean     p99 | p99/mean | p99 as % of budget
//       512   0.35     no |    837    2691 |    3.21x |   25.2%
//       512   0.35    yes |    839    1222 |    1.46x |   11.5%
//       512   0.25    yes |    451     639 |    1.42x |    6.0%
//      1024   0.35     no |   1657    3006 |    1.81x |   14.1%
//      1024   0.25    yes |    905    1175 |    1.30x |    5.5%
//
// The first row is what shipped: a quarter of the realtime budget on a desktop
// core, which on a phone is the whole of it and then some. The block sets the
// mean -- 0.12/0.25/0.35 s cost 2.0%/4.3%/7.8% of budget regardless of chunk
// size -- and splitComputation sets the tail. Neither alone is enough.
//
// Build and run:
//
//   cd app/src/main/cpp/stretch/tests
//   c++ -std=c++17 -O2 -I../../../../../../third_party/signalsmith-stretch \
//       stretch_cost_test.cpp -o stretch_cost_test && ./stretch_cost_test
//
// It prints a table and asserts the two properties the shipping configuration
// depends on. Timing on a shared or throttled machine is noisy, so the
// thresholds are loose on purpose -- this is a guard against the burstiness
// coming back, not a benchmark to tune against.

#include "signalsmith-stretch.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>

namespace {

constexpr double kSampleRate = 48000.0;
constexpr double kIntervalSeconds = 0.03;
// Must track kVocoderBlockSeconds in ../stretch_jni.cpp.
constexpr double kBlockSeconds[3] = {0.12, 0.25, 0.35};

int failures = 0;

void check(bool ok, const char *what) {
    if (!ok) {
        std::printf("  FAIL: %s\n", what);
        ++failures;
    }
}

struct Stats {
    double mean;
    double p50;
    double p99;
};

/** One pass: `seconds` of audio through `chunk`-frame calls, each timed. */
Stats measure(double blockSeconds, bool split, int chunk, int seconds) {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    stretch.configure(2, static_cast<int>(kSampleRate * blockSeconds),
                      static_cast<int>(kSampleRate * kIntervalSeconds), split);
    stretch.setTransposeSemitones(5.0f);

    std::vector<float> left(chunk), right(chunk), outLeft(chunk), outRight(chunk);
    float *in[2] = {left.data(), right.data()};
    float *out[2] = {outLeft.data(), outRight.data()};

    const int calls = static_cast<int>(kSampleRate * seconds) / chunk;
    std::vector<double> micros;
    micros.reserve(calls);
    double phase = 0.0;
    for (int c = 0; c < calls; ++c) {
        for (int i = 0; i < chunk; ++i) {
            phase += 2.0 * M_PI * 220.0 / kSampleRate;
            left[i] = right[i] = 0.5f * static_cast<float>(std::sin(phase));
        }
        const auto t0 = std::chrono::steady_clock::now();
        stretch.process(in, chunk, out, chunk);
        const auto t1 = std::chrono::steady_clock::now();
        // The first tenth is the pipeline filling, not steady state.
        if (c > calls / 10) {
            micros.push_back(std::chrono::duration<double, std::micro>(t1 - t0).count());
        }
    }
    std::sort(micros.begin(), micros.end());
    double sum = 0;
    for (double v : micros) sum += v;
    return {sum / micros.size(), micros[micros.size() / 2],
            micros[static_cast<size_t>(micros.size() * 0.99)]};
}

/** Best of three, so one busy moment on the host cannot invent a spike. */
Stats best(double blockSeconds, bool split, int chunk) {
    Stats r = measure(blockSeconds, split, chunk, 10);
    for (int i = 0; i < 2; ++i) {
        const Stats t = measure(blockSeconds, split, chunk, 10);
        r.mean = std::min(r.mean, t.mean);
        r.p50 = std::min(r.p50, t.p50);
        r.p99 = std::min(r.p99, t.p99);
    }
    return r;
}

}  // namespace

int main() {
    std::printf("chunk  block  split |   mean     p50     p99 | p99/mean | %% of budget\n");
    std::printf("                    |   (us)    (us)    (us) |          |  mean    p99\n");
    std::printf("--------------------------------------------------------------------\n");

    for (int chunk : {512, 1024, 2048}) {
        const double budget = chunk / kSampleRate * 1e6;
        for (double block : kBlockSeconds) {
            for (int split = 0; split < 2; ++split) {
                const Stats s = best(block, split != 0, chunk);
                std::printf("%5d  %5.2f  %5s | %6.1f  %6.1f  %6.1f |  %6.2fx | %5.2f%% %5.2f%%\n",
                            chunk, block, split ? "yes" : "no", s.mean, s.p50, s.p99,
                            s.p99 / s.mean, 100 * s.mean / budget, 100 * s.p99 / budget);
            }
        }
        std::printf("--------------------------------------------------------------------\n");
    }

    // The property splitComputation exists for: without it the median call does
    // almost nothing while the mean is carried by the few that do everything.
    // A 512-frame chunk is a third of a hop, so the effect is unmistakable
    // there -- the median measures under a tenth of the mean.
    const Stats burst = best(kBlockSeconds[2], false, 512);
    check(burst.p50 < burst.mean * 0.5,
          "without splitComputation the cost should be bursty (this is the bug)");

    // With it, every call pays about the same, which is the whole point.
    const Stats flat = best(kBlockSeconds[2], true, 512);
    check(flat.p50 > flat.mean * 0.7 && flat.p50 < flat.mean * 1.4,
          "with splitComputation the median call should sit near the mean");
    check(flat.p99 < burst.p99,
          "splitComputation should cut the tail, not just move it");

    // And the shipping default has to leave room for a phone core and for the
    // rest of the chain. Four times this host's p99 is the pessimistic phone
    // estimate; a third of the budget is the most the pitch stage may claim.
    const Stats shipping = best(kBlockSeconds[1], true, 512);
    const double budget512 = 512 / kSampleRate * 1e6;
    check(4 * shipping.p99 < budget512 / 3,
          "the default (0.25 s, split) should leave headroom on a phone core");

    if (failures == 0) {
        std::printf("\nAll cost-shape checks passed.\n");
        return 0;
    }
    std::printf("\n%d check(s) failed.\n", failures);
    return 1;
}
