// Host tests for single snapins whose behaviour the FX presets rely on: each
// one pins a defect that was found and fixed, so a preset named "Octave Up"
// or "Power Down" keeps doing what it says.
//
//   ./run_host_tests.sh

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "snapins/dynamics.h"
#include "snapins/pitch_shifter.h"
#include "snapins/resonator.h"
#include "snapins/stereo.h"
#include "snapins/tape_stop.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;
constexpr int kBlock = 256;

/**
 * The dominant frequency of [x]: the loudest bin of a Goertzel scan from
 * 50 Hz to 2 kHz in 1 Hz steps. A spectral peak rather than zero crossings,
 * because a granular shifter sums overlapping grains whose phases drift, and
 * near their cancellations a crossing count picks up spurious crossings.
 */
double frequency(const std::vector<float>& x) {
    double bestHz = 0.0, best = -1.0;
    const double n = static_cast<double>(x.size());
    for (int hz = 50; hz <= 2000; hz++) {
        const double w = 2.0 * M_PI * hz / kRate;
        const double c = 2.0 * std::cos(w);
        double s1 = 0.0, s2 = 0.0;
        for (size_t i = 0; i < x.size(); i++) {
            // Hann window, so a strong neighbour does not leak into the scan.
            const double win = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / (n - 1));
            const double s0 = x[i] * win + c * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        const double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        if (power > best) { best = power; bestHz = hz; }
    }
    return bestHz;
}

/** Runs a sine of [hz] through [p] for [seconds]; returns the left output. */
template <typename P>
std::vector<float> runSine(P& p, double hz, double seconds, long& n) {
    std::vector<float> out;
    std::vector<float> l(kBlock), r(kBlock);
    const int blocks = static_cast<int>(seconds * kRate / kBlock);
    for (int b = 0; b < blocks; b++) {
        for (int i = 0; i < kBlock; i++, n++) {
            l[i] = r[i] = static_cast<float>(0.5 * std::sin(2.0 * M_PI * hz * n / kRate));
        }
        p.process(l.data(), r.data(), kBlock);
        out.insert(out.end(), l.begin(), l.end());
    }
    return out;
}

void pitchShifterGoesTheRightWay() {
    for (float st : {12.0f, 7.0f, -12.0f}) {
        PitchShifterProcessor p;
        p.prepare(kRate, kBlock);
        p.setParameter(PitchShifterProcessor::PITCH, st);
        p.setParameter(PitchShifterProcessor::MIX, 100.0f);
        long n = 0;
        std::vector<float> out = runSine(p, 440.0, 1.0, n);
        std::vector<float> tail(out.begin() + kRate / 2, out.end());
        const double want = 440.0 * std::pow(2.0, st / 12.0);
        const double got = frequency(tail);
        char what[96];
        std::snprintf(what, sizeof what, "pitch %+.0f st: 440 Hz comes out at %.0f Hz (want %.0f)", st, got, want);
        check(std::fabs(got - want) < want * 0.05, what);
    }
}

void tapeStopLowersThePitch() {
    TapeStopProcessor p;
    p.prepare(kRate, kBlock);
    p.setParameter(TapeStopProcessor::STOP_TIME, 1000.0f);
    p.setParameter(TapeStopProcessor::CURVE, 0.0f);
    long n = 0;
    runSine(p, 1000.0, 0.2, n);
    p.setParameter(TapeStopProcessor::PLAY, 0.0f);
    std::vector<float> out = runSine(p, 1000.0, 0.6, n);
    // 0.3–0.5 s into a 1 s linear stop the tape runs at roughly half speed.
    std::vector<float> window(out.begin() + kRate * 3 / 10, out.begin() + kRate / 2);
    const double f = frequency(window);
    char what[96];
    std::snprintf(what, sizeof what, "tape stop: 1 kHz has dropped to %.0f Hz mid-stop", f);
    check(f > 300.0 && f < 850.0, what);
}

void dynamicsKneeIsContinuous() {
    // Upward compression below −40 dB, 2:1, 12 dB knee: the output level must
    // rise smoothly with the input, with no step at the knee's edge.
    double prevOut = -1e9, worstStep = 0.0;
    bool monotonic = true;
    for (int db = -56; db <= -24; db++) {
        DynamicsProcessor p;
        p.prepare(kRate, kBlock);
        p.setParameter(DynamicsProcessor::LOW_THRESHOLD, -40.0f);
        p.setParameter(DynamicsProcessor::LOW_RATIO, 2.0f);
        p.setParameter(DynamicsProcessor::HIGH_THRESHOLD, 0.0f);
        p.setParameter(DynamicsProcessor::KNEE, 12.0f);
        p.setParameter(DynamicsProcessor::ATTACK, 1.0f);
        p.setParameter(DynamicsProcessor::RELEASE, 5.0f);
        const float amp = std::pow(10.0f, db / 20.0f);
        std::vector<float> l(kBlock), r(kBlock);
        double acc = 0.0;
        long count = 0;
        for (int b = 0; b < kRate / 2 / kBlock; b++) {
            for (int i = 0; i < kBlock; i++) {
                l[i] = r[i] = amp * static_cast<float>(std::sin(2.0 * M_PI * 1000.0 * (b * kBlock + i) / kRate));
            }
            p.process(l.data(), r.data(), kBlock);
            if (b > kRate / 4 / kBlock) {
                for (float v : l) { acc += static_cast<double>(v) * v; count++; }
            }
        }
        const double outDb = 10.0 * std::log10(acc / count + 1e-30);
        if (prevOut > -1e8) {
            worstStep = std::max(worstStep, std::fabs(outDb - prevOut));
            if (outDb < prevOut - 0.05) monotonic = false;
        }
        prevOut = outDb;
    }
    char what[96];
    std::snprintf(what, sizeof what, "dynamics: output rises smoothly across the knee (largest 1 dB step: %.2f dB)", worstStep);
    check(monotonic && worstStep < 1.5, what);
}

void stereoIsTransparentAtDefaults() {
    StereoProcessor p;
    p.prepare(kRate, kBlock);
    std::vector<float> l(kBlock), r(kBlock), l0(kBlock), r0(kBlock);
    std::srand(7);
    double worst = 0.0;
    for (int b = 0; b < 20; b++) {
        for (int i = 0; i < kBlock; i++) {
            l0[i] = l[i] = std::rand() / (float)RAND_MAX - 0.5f;
            r0[i] = r[i] = std::rand() / (float)RAND_MAX - 0.5f;
        }
        p.process(l.data(), r.data(), kBlock);
        for (int i = 0; i < kBlock; i++) {
            worst = std::max(worst, (double)std::fabs(l[i] - l0[i]));
            worst = std::max(worst, (double)std::fabs(r[i] - r0[i]));
        }
    }
    check(worst < 1e-5, "stereo: at its defaults the signal passes unchanged");
}

void resonatorStaysBounded() {
    for (int timbre = 0; timbre <= 1; timbre++) {
        ResonatorProcessor p;
        p.prepare(kRate, kBlock);
        p.setParameter(ResonatorProcessor::TIMBRE, static_cast<float>(timbre));
        p.setParameter(ResonatorProcessor::DECAY_PCT, 100.0f);
        p.setParameter(ResonatorProcessor::INTENSITY, 100.0f);
        p.setParameter(ResonatorProcessor::MIX, 100.0f);
        std::vector<float> l(kBlock), r(kBlock);
        std::srand(3);
        double peak = 0.0;
        bool finite = true;
        for (int b = 0; b < 3 * kRate / kBlock; b++) {
            const bool noise = b < kRate / 4 / kBlock;
            for (int i = 0; i < kBlock; i++) {
                l[i] = r[i] = noise ? (std::rand() / (float)RAND_MAX - 0.5f) * 0.5f : 0.0f;
            }
            p.process(l.data(), r.data(), kBlock);
            for (float v : l) {
                if (!std::isfinite(v)) finite = false;
                peak = std::max(peak, (double)std::fabs(v));
            }
        }
        char what[96];
        std::snprintf(what, sizeof what, "resonator (%s, decay 100): output stays bounded (peak %.1f)",
                      timbre ? "square" : "saw", peak);
        check(finite && peak < 200.0, what);
    }
}

}  // namespace

int main() {
    pitchShifterGoesTheRightWay();
    tapeStopLowersThePitch();
    dynamicsKneeIsContinuous();
    stereoIsTransparentAtDefaults();
    resonatorStaysBounded();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
