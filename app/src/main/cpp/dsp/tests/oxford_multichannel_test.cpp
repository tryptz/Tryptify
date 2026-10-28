// Host test for the Oxford Inflator and compressor at Atmos widths. Build and
// run with the other host tests:
//
//   ./run_host_tests.sh

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../oxford/oxford_dsp.h"

using trypt::dsp::CompressorProcessor;
using trypt::dsp::FirAlignDelay;
using trypt::dsp::InflatorProcessor;
using trypt::dsp::LinearPhaseFir;
using trypt::dsp::Smoothed;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;
constexpr int kFrames = 512;
constexpr int kBlocks = 80;
constexpr int kChannels = 12;  // 7.1.4

void compressorIsLinkedAcrossTheBed() {
    CompressorProcessor comp;
    comp.prepare(kRate, kChannels);
    comp.params.thresholdDb = -30.0f;
    comp.params.ratio = 8.0f;
    std::vector<std::vector<float>> ch(kChannels, std::vector<float>(kFrames));
    float* planes[kChannels];
    for (int c = 0; c < kChannels; c++) planes[c] = ch[c].data();

    double inQuiet = 0, outQuiet = 0, inLoud = 0, outLoud = 0;
    long n = 0;
    for (int b = 0; b < kBlocks; b++) {
        for (int i = 0; i < kFrames; i++, n++) {
            for (int c = 0; c < kChannels; c++) {
                ch[c][i] = static_cast<float>((c == 2 ? 0.8 : 0.01) * std::sin(2 * M_PI * 440 * n / kRate));
            }
        }
        double iq = 0, il = 0;
        for (int i = 0; i < kFrames; i++) { iq += std::fabs(ch[10][i]); il += std::fabs(ch[2][i]); }
        comp.process(planes, kFrames);
        if (b >= kBlocks / 2) {
            inQuiet += iq; inLoud += il;
            for (int i = 0; i < kFrames; i++) { outQuiet += std::fabs(ch[10][i]); outLoud += std::fabs(ch[2][i]); }
        }
    }
    const double gQuiet = outQuiet / inQuiet, gLoud = outLoud / inLoud;
    std::printf("      top-back-left gain %.4f, centre gain %.4f\n", gQuiet, gLoud);
    check(gLoud < 0.5, "a loud centre is compressed");
    check(std::fabs(20 * std::log10(gQuiet / gLoud)) < 0.05,
          "a quiet height channel is turned down by the same amount");
}

void inflatorTreatsEveryChannelAlike() {
    // Channel c of a 12-channel run must equal a stereo run fed the same pair.
    InflatorProcessor wide, pair;
    wide.prepare(kRate, kChannels);
    pair.prepare(kRate, 2);
    for (auto* p : {&wide, &pair}) {
        p->params.bandSplit = true;
        p->params.curve = 20.0f;
        p->params.inputDb = 3.0f;
    }
    std::vector<std::vector<float>> ch(kChannels, std::vector<float>(kFrames));
    std::vector<float> l(kFrames), r(kFrames);
    float* planes[kChannels];
    for (int c = 0; c < kChannels; c++) planes[c] = ch[c].data();
    float* pairPlanes[2] = {l.data(), r.data()};
    bool same = true;
    long n = 0;
    for (int b = 0; b < kBlocks; b++) {
        for (int i = 0; i < kFrames; i++, n++) {
            const float a = static_cast<float>(0.6 * std::sin(2 * M_PI * 180 * n / kRate));
            const float z = static_cast<float>(0.4 * std::sin(2 * M_PI * 2500 * n / kRate));
            for (int c = 0; c < kChannels; c++) ch[c][i] = (c % 2 == 0) ? a : z;
            l[i] = a; r[i] = z;
        }
        wide.process(planes, kFrames);
        pair.process(pairPlanes, kFrames);
        for (int c = 0; c < kChannels; c++) {
            const auto& ref = (c % 2 == 0) ? l : r;
            for (int i = 0; i < kFrames; i++) same = same && ch[c][i] == ref[i];
        }
    }
    check(same, "every channel of a 7.1.4 bed is inflated exactly as in stereo");
}

bool sameBits(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && std::memcmp(a.data(), b.data(), sizeof(float) * a.size()) == 0;
}

void firFedWithoutOutputIsTheSameFilter() {
    // A filter only pushed into for a while, then run, gives exactly what
    // one run all along does: the history is all it keeps.
    LinearPhaseFir always, pushed;
    always.design(LinearPhaseFir::Type::LowPass, 260.0, kRate);
    pushed.design(LinearPhaseFir::Type::LowPass, 260.0, kRate);
    bool same = true;
    long n = 0;
    for (int i = 0; i < 3000; i++, n++) {
        const float x = static_cast<float>(0.5 * std::sin(0.013 * n) + 0.2 * std::sin(0.71 * n));
        const float a = always.process(x);
        if (i < 1700) {
            pushed.push(x);
        } else {
            const float b = pushed.process(x);
            same = same && std::memcmp(&a, &b, sizeof a) == 0;
        }
    }
    check(same, "an FIR fed with push() and then run matches one run all along, bit for bit");
}

// The Inflator's waveshaper, as in oxford_dsp.h.
float waveshape(float x, float A, float B, float C, float D) {
    const float ax = std::fabs(x);
    const float s = std::copysign(1.0f, x);
    if (ax <= 1.0f) {
        const float x2 = ax * ax;
        const float x3 = x2 * ax;
        const float x4 = x2 * x2;
        return s * (A * ax + B * x2 + C * x3 - D * (x2 - 2.0f * x3 + x4));
    }
    if (ax <= 2.0f) return s * (2.0f * ax - ax * ax);
    return 0.0f;
}

void inflatorWithoutSplitIsAsBefore() {
    // With band split off the FIRs' outputs never reached the sound; the old
    // per-sample loop without them is the reference, step for step.
    const float inputDb = 3.0f, outputDb = -1.5f, effect = 0.8f, curve = 20.0f;
    InflatorProcessor inf;
    inf.prepare(kRate, 2);
    inf.params.inputDb = inputDb;
    inf.params.outputDb = outputDb;
    inf.params.effect = effect;
    inf.params.curve = curve;
    inf.params.bandSplit = false;
    Smoothed pre, post, wetS, curveS;
    pre.configure(kRate, 20.0);
    post.configure(kRate, 20.0);
    wetS.configure(kRate, 20.0);
    curveS.configure(kRate, 50.0);
    pre.snapTo(1.0f);
    post.snapTo(1.0f);
    wetS.snapTo(1.0f);
    curveS.snapTo(0.0f);
    FirAlignDelay delay[2];
    delay[0].reset();
    delay[1].reset();
    std::vector<float> l(kFrames), r(kFrames), wantL(kFrames), wantR(kFrames);
    float* planes[2] = {l.data(), r.data()};
    bool same = true;
    long n = 0;
    for (int b = 0; b < kBlocks; b++) {
        for (int i = 0; i < kFrames; i++, n++) {
            l[i] = wantL[i] = static_cast<float>(0.7 * std::sin(2 * M_PI * 180 * n / kRate));
            r[i] = wantR[i] = static_cast<float>(0.5 * std::sin(2 * M_PI * 2500 * n / kRate));
        }
        inf.process(planes, kFrames);
        pre.setTarget(trypt::dsp::dbToLin(inputDb));
        post.setTarget(trypt::dsp::dbToLin(outputDb));
        wetS.setTarget(std::clamp(effect, 0.0f, 1.0f));
        curveS.setTarget(std::clamp(curve, -50.0f, 50.0f));
        for (int i = 0; i < kFrames; i++) {
            const float g = pre.next();
            const float out = post.next();
            const float wet = wetS.next();
            const float dry = 1.0f - wet;
            const float c = curveS.next();
            const float A = 1.5f + c * 0.01f;
            const float B = -c * 0.02f;
            const float C = -0.5f + c * 0.01f;
            const float D = 0.0625f - c * 0.0025f + c * c * 2.5e-5f;
            float* want[2] = {&wantL[i], &wantR[i]};
            for (int ch = 0; ch < 2; ch++) {
                const float x = std::clamp(*want[ch] * g, -2.0f, 2.0f);
                const float xd = delay[ch].process(x);
                float y = (dry * xd + wet * waveshape(xd, A, B, C, D)) * out;
                y = std::clamp(y, -1.0f, 1.0f);
                *want[ch] = y;
            }
        }
        same = same && sameBits(l, wantL) && sameBits(r, wantR);
    }
    check(same, "the Inflator with band split off is bit for bit what it was");
}

void inflatorSplitTurnsOnSeamlessly() {
    // Off for a while, then on: from then on exactly an Inflator that had
    // band split on all along, so the filters held the same history.
    InflatorProcessor toggled, always;
    for (auto* p : {&toggled, &always}) {
        p->prepare(kRate, 2);
        p->params.curve = -15.0f;
        p->params.inputDb = 2.0f;
    }
    toggled.params.bandSplit = false;
    always.params.bandSplit = true;
    std::vector<float> l(kFrames), r(kFrames), l2(kFrames), r2(kFrames);
    float* a[2] = {l.data(), r.data()};
    float* b2[2] = {l2.data(), r2.data()};
    bool same = true;
    long n = 0;
    for (int b = 0; b < kBlocks; b++) {
        if (b == kBlocks / 2) toggled.params.bandSplit = true;
        for (int i = 0; i < kFrames; i++, n++) {
            l[i] = l2[i] = static_cast<float>(0.6 * std::sin(2 * M_PI * 120 * n / kRate) + 0.2 * std::sin(2 * M_PI * 5000 * n / kRate));
            r[i] = r2[i] = static_cast<float>(0.4 * std::sin(2 * M_PI * 900 * n / kRate));
        }
        toggled.process(a, kFrames);
        always.process(b2, kFrames);
        if (b >= kBlocks / 2) same = same && sameBits(l, l2) && sameBits(r, r2);
    }
    check(same, "band split turned on mid-stream is bit for bit an Inflator that had it on all along");
}

}  // namespace

int main() {
    compressorIsLinkedAcrossTheBed();
    inflatorTreatsEveryChannelAlike();
    firFedWithoutOutputIsTheSameFilter();
    inflatorWithoutSplitIsAsBefore();
    inflatorSplitTurnsOnSeamlessly();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
