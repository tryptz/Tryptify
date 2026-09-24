// Host test for the multichannel mixer (MultiLaneEngine) and the refactored
// DspEngine it steps. Build and run with the other host tests:
//
//   ./run_host_tests.sh
//
// Runs on the machine that builds the app, not on a device, so the maths is
// checked where a failure is cheap to read.

#include <cmath>
#include <cstdio>
#include <vector>

#include "../dsp_engine.h"
#include "../multilane_engine.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;
constexpr int kBlock = 512;
constexpr int kBlocks = 120;

float tone(long n, double hz, double amp) {
    return static_cast<float>(amp * std::sin(2.0 * M_PI * hz * static_cast<double>(n) / kRate));
}

// 7.1.4 as ChannelLayout.lanes(12) gives it: FL/FR, FC, LFE, BL/BR, SL/SR,
// TFL/TFR, TBL/TBR.
const int k714First[] = {0, 2, 3, 4, 6, 8, 10};
const int k714Second[] = {1, -1, -1, 5, 7, 9, 11};
constexpr int k714Lanes = 7;
constexpr int k714Channels = 12;

// A chain like a user's: a bus with an image effect and a modulation effect,
// a compressor and a limiter on the master.
template <typename Target>
void buildChain(Target&& apply) {
    apply([](DspEngine& e) {
        e.setBusInputEnabled(0, true);
        e.addPlugin(0, 0, static_cast<int>(SnapinType::HAAS));
        e.addPlugin(0, 1, static_cast<int>(SnapinType::CHORUS));
        e.addPlugin(4, 0, static_cast<int>(SnapinType::COMPRESSOR));
        e.setParameter(4, 0, 0, -30.0f);  // threshold
        e.addPlugin(4, 1, static_cast<int>(SnapinType::LIMITER));
    });
}

/** Planar channels, each [kBlock] long, and the pointers to hand in. */
struct Planar {
    std::vector<std::vector<float>> ch;
    std::vector<float*> ptr;
    explicit Planar(int n) : ch(n, std::vector<float>(kBlock)), ptr(n) {
        for (int c = 0; c < n; c++) ptr[c] = ch[c].data();
    }
};

void stereoIsUnchanged() {
    DspEngine plain(kRate, kBlock);
    MultiLaneEngine lanes(kRate, kBlock);
    buildChain([&](auto f) { f(plain); });
    buildChain([&](auto f) { lanes.forEach(f); });

    Planar p(2);
    std::vector<float> l(kBlock), r(kBlock);
    bool identical = true;
    long n = 0;
    for (int b = 0; b < kBlocks; b++) {
        for (int i = 0; i < kBlock; i++, n++) {
            l[i] = p.ch[0][i] = tone(n, 220, 0.6);
            r[i] = p.ch[1][i] = tone(n, 330, 0.5);
        }
        plain.process(l.data(), r.data(), kBlock);
        lanes.processPlanar(p.ptr.data(), 2, kBlock);
        for (int i = 0; i < kBlock; i++) {
            identical = identical && l[i] == p.ch[0][i] && r[i] == p.ch[1][i];
        }
    }
    check(identical, "one lane is bit-identical to the plain stereo engine");
}

void everyPairLaneRunsTheSameChain() {
    // The chain is built before the lanes, so the extra lanes can only have
    // it through the state clone: this is what proves the clone is whole.
    MultiLaneEngine lanes(kRate, kBlock);
    buildChain([&](auto f) { lanes.forEach(f); });
    lanes.configureLanes(k714First, k714Second, k714Lanes);
    check(lanes.laneCount() == k714Lanes, "7.1.4 gets seven lanes");

    Planar p(k714Channels);
    bool pairsMatch = true;
    long n = 0;
    for (int b = 0; b < kBlocks; b++) {
        for (int i = 0; i < kBlock; i++, n++) {
            for (int c = 0; c < k714Channels; c++) {
                // Every pair carries the same left/right; centre and LFE quiet.
                const bool right = (c % 2 == 1);
                p.ch[c][i] = (c == 2 || c == 3) ? 0.0f : tone(n, right ? 330 : 220, 0.4);
            }
        }
        lanes.processPlanar(p.ptr.data(), k714Channels, kBlock);
        for (int c = 4; c < k714Channels; c++) {
            for (int i = 0; i < kBlock; i++) {
                pairsMatch = pairsMatch && p.ch[c][i] == p.ch[c % 2][i];
            }
        }
    }
    check(pairsMatch, "every pair lane matches the front pair sample for sample");
}

/** Mean |out| / mean |in| over the second half of a run: the applied gain. */
double gainOf(const std::vector<double>& in, const std::vector<double>& out) {
    double a = 0, b = 0;
    for (size_t i = in.size() / 2; i < in.size(); i++) { a += in[i]; b += out[i]; }
    return b / a;
}

/**
 * A loud centre and quiet fronts through a compressor with no image
 * effects in the way. Returns the front and centre gain.
 */
void compressorGains(bool onMaster, double& front, double& centre) {
    MultiLaneEngine lanes(kRate, kBlock);
    lanes.forEach([&](DspEngine& e) {
        e.setBusInputEnabled(0, true);
        const int bus = onMaster ? 4 : 0;
        e.addPlugin(bus, 0, static_cast<int>(SnapinType::COMPRESSOR));
        e.setParameter(bus, 0, 0, -30.0f);
    });
    lanes.configureLanes(k714First, k714Second, k714Lanes);

    Planar p(k714Channels);
    std::vector<double> frontIn, frontOut, centreIn, centreOut;
    long n = 0;
    for (int b = 0; b < kBlocks; b++) {
        double fi = 0, ci = 0;
        for (int i = 0; i < kBlock; i++, n++) {
            for (int c = 0; c < k714Channels; c++) p.ch[c][i] = tone(n, 440, 0.01);
            p.ch[2][i] = tone(n, 220, 0.8);  // loud centre
            fi += std::fabs(p.ch[0][i]);
            ci += std::fabs(p.ch[2][i]);
        }
        lanes.processPlanar(p.ptr.data(), k714Channels, kBlock);
        double fo = 0, co = 0;
        for (int i = 0; i < kBlock; i++) {
            fo += std::fabs(p.ch[0][i]);
            co += std::fabs(p.ch[2][i]);
        }
        frontIn.push_back(fi); frontOut.push_back(fo);
        centreIn.push_back(ci); centreOut.push_back(co);
    }
    front = gainOf(frontIn, frontOut);
    centre = gainOf(centreIn, centreOut);
}

void masterDynamicsAreLinked() {
    double front, centre;
    compressorGains(true, front, centre);
    std::printf("      master: front gain %.4f, centre gain %.4f\n", front, centre);
    check(centre < 0.5, "a loud centre is compressed");
    check(std::fabs(20 * std::log10(front / centre)) < 0.5,
          "master compressor turns the quiet fronts down by the centre's amount");
}

void busDynamicsArePerLane() {
    double front, centre;
    compressorGains(false, front, centre);
    std::printf("      bus:    front gain %.4f, centre gain %.4f\n", front, centre);
    check(centre < 0.5, "a loud centre is compressed on its own lane");
    check(20 * std::log10(front / centre) > 6.0,
          "bus compressor leaves the quiet fronts alone");
}

void monoLanesSkipImageEffects() {
    // Haas on the bus. On the centre lane it is skipped, so the centre comes
    // out exactly as it would through the same chain with no Haas at all.
    auto run = [](bool withHaas) {
        MultiLaneEngine lanes(kRate, kBlock);
        lanes.forEach([&](DspEngine& e) {
            e.setBusInputEnabled(0, true);
            if (withHaas) e.addPlugin(0, 0, static_cast<int>(SnapinType::HAAS));
            e.addPlugin(0, withHaas ? 1 : 0, static_cast<int>(SnapinType::GAIN));
        });
        lanes.configureLanes(k714First, k714Second, k714Lanes);
        Planar p(k714Channels);
        std::vector<float> centre;
        long n = 0;
        for (int b = 0; b < 20; b++) {
            for (int i = 0; i < kBlock; i++, n++) {
                for (int c = 0; c < k714Channels; c++) p.ch[c][i] = tone(n, 1000 + 50 * c, 0.3);
            }
            lanes.processPlanar(p.ptr.data(), k714Channels, kBlock);
            centre.insert(centre.end(), p.ch[2].begin(), p.ch[2].end());
        }
        return centre;
    };
    check(run(true) == run(false), "Haas is skipped on the centre lane");
}

}  // namespace

int main() {
    stereoIsUnchanged();
    everyPairLaneRunsTheSameChain();
    masterDynamicsAreLinked();
    busDynamicsArePerLane();
    monoLanesSkipImageEffects();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
