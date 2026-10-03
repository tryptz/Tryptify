// Host test for the Oxford Inflator and compressor at Atmos widths. Build and
// run with the other host tests:
//
//   ./run_host_tests.sh

#include <cmath>
#include <cstdio>
#include <vector>

#include "../oxford/oxford_dsp.h"

using trypt::dsp::CompressorProcessor;
using trypt::dsp::InflatorProcessor;

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

}  // namespace

int main() {
    compressorIsLinkedAcrossTheBed();
    inflatorTreatsEveryChannelAlike();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
