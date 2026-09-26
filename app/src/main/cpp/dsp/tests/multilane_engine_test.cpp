// Host test for the multichannel mixer (MultiLaneEngine) and the refactored
// DspEngine it steps. Build and run with the other host tests:
//
//   ./run_host_tests.sh
//
// Runs on the machine that builds the app, not on a device, so the maths is
// checked where a failure is cheap to read.

#include <cmath>
#include <cstdio>
#include <string>
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
    lanes.setSpread(false);  // one bus: every lane through bus 1's chain
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
    lanes.setSpread(false);  // one bus: every lane through bus 1's chain
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
        lanes.setSpread(false);  // one bus: every lane through bus 1's chain
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


void busesAddUpToTheCapAndStopThere() {
    DspEngine e(kRate, kBlock);
    check(e.mixBusCount() == 4, "a new mixer has four buses");
    check(MAX_MIX_BUSES == 48, "the cap is 48 mix buses");
    bool indicesRight = true;
    for (int expected = 5; expected <= MAX_MIX_BUSES; expected++) indicesRight = indicesRight && e.addBus() == expected;
    check(indicesRight, "added buses take indices 5 to 48, past the master at 4");
    check(e.mixBusCount() == MAX_MIX_BUSES, "48 mix buses at most");
    check(e.addBus() == -1, "a 49th is refused");
}

void removingABusMovesTheOnesAboveDown() {
    DspEngine e(kRate, kBlock);
    e.addBus();  // 5
    e.addBus();  // 6
    e.setBusGain(6, -6.0f);
    e.setBusInputEnabled(6, true);
    e.addPlugin(6, 0, static_cast<int>(SnapinType::GAIN));
    check(!e.removeBus(3), "buses 1-4 cannot be removed");
    check(!e.removeBus(MASTER_BUS), "the master cannot be removed");
    check(e.removeBus(5), "bus 5 is removed");
    check(e.mixBusCount() == 5, "five mix buses remain");
    const std::string state = e.getStateJson();
    // Bus 6 is now bus 5 (the sixth entry, after the master): gain, input
    // and its plugin all came with it.
    size_t at = 0;
    for (int i = 0; i < 6; i++) at = state.find("{\"gain\":", at + 1);
    const std::string moved = state.substr(at, state.find("]}", at) - at);
    check(moved.find("\"gain\":-6") != std::string::npos &&
          moved.find("\"inputEnabled\":true") != std::string::npos &&
          moved.find("\"type\":0") != std::string::npos,
          "the bus above moved down with its gain, input and plugin");
}

void stateRoundTripsBusCount() {
    DspEngine a(kRate, kBlock);
    for (int i = 0; i < 3; i++) a.addBus();  // 7 mix buses
    a.setBusGain(7, -3.0f);
    DspEngine b(kRate, kBlock);
    b.loadStateJson(a.getStateJson());
    check(b.mixBusCount() == 7, "a saved 7-bus mix loads as 7 buses");
    check(b.getStateJson() == a.getStateJson(), "and saves back identically");

    DspEngine c(kRate, kBlock);
    for (int i = 0; i < 5; i++) c.addBus();
    DspEngine old(kRate, kBlock);  // a four-bus save, as every earlier build wrote
    c.loadStateJson(old.getStateJson());
    check(c.mixBusCount() == 4, "an older five-entry save loads as four buses");
}

void anAddedBusCarriesAudio() {
    auto peak = [](bool useBus5) {
        DspEngine e(kRate, kBlock);
        e.setBusInputEnabled(0, false);
        if (useBus5) {
            const int idx = e.addBus();
            e.setBusInputEnabled(idx, true);
        }
        std::vector<float> l(kBlock), r(kBlock);
        float p = 0;
        long n = 0;
        for (int b = 0; b < 10; b++) {
            for (int i = 0; i < kBlock; i++, n++) l[i] = r[i] = tone(n, 440, 0.5);
            e.process(l.data(), r.data(), kBlock);
            for (int i = 0; i < kBlock; i++) p = std::max(p, std::fabs(l[i]));
        }
        return p;
    };
    check(peak(false) == 0.0f && peak(true) > 0.1f, "an added bus with its input on is heard");
}

}  // namespace

// Fills every channel of a 7.1.4 block with its own tone.
void fill714(Planar& p, long& n) {
    for (int i = 0; i < kBlock; i++, n++) {
        for (int c = 0; c < k714Channels; c++) p.ch[c][i] = tone(n, 110.0 * (c + 1), 0.3);
    }
}

double rms(const std::vector<float>& v) {
    double acc = 0.0;
    for (float x : v) acc += static_cast<double>(x) * x;
    return std::sqrt(acc / static_cast<double>(v.size()));
}

void atmosSpreadsAcrossTheMixer() {
    MultiLaneEngine m(kRate, kBlock);
    m.configureLanes(k714First, k714Second, k714Lanes);
    check(m.primary().mixBusCount() == 7, "7.1.4 grows the mixer to one bus per channel group (7)");
    // Mute bus 2 (index 1), the centre's: only the centre channel goes quiet.
    m.forEach([](DspEngine& e) { e.setBusMute(1, true); });
    Planar p(k714Channels);
    long n = 0;
    for (int b = 0; b < 20; b++) {
        fill714(p, n);
        m.processPlanar(p.ptr.data(), k714Channels, kBlock);
    }
    check(rms(p.ch[2]) < 1e-6, "muting the centre's bus silences the centre channel");
    bool othersAlive = true;
    for (int c = 0; c < k714Channels; c++) {
        if (c != 2) othersAlive = othersAlive && rms(p.ch[c]) > 0.01;
    }
    check(othersAlive, "and every other channel still plays");
    // Top rear pair is lane 6 -> bus 7 -> index 7.
    m.forEach([](DspEngine& e) { e.setBusMute(1, false); e.setBusMute(7, true); });
    for (int b = 0; b < 20; b++) {
        fill714(p, n);
        m.processPlanar(p.ptr.data(), k714Channels, kBlock);
    }
    check(rms(p.ch[10]) < 1e-6 && rms(p.ch[11]) < 1e-6 && rms(p.ch[2]) > 0.01,
          "bus 7 is the top rear pair, and nothing else");
    check(!m.primary().removeBus(7), "a bus a channel group feeds cannot be removed");
}

void grownBusesLeaveWithTheStream() {
    MultiLaneEngine m(kRate, kBlock);
    const std::string before = m.primary().getStateJson();
    m.configureLanes(k714First, k714Second, k714Lanes);
    check(m.primary().getStateJson() == before,
          "the untouched grown buses are not saved");
    check(m.primary().getStateJson(true) != before, "but the live state shows them");
    // Touch bus 6 (a top front pair), leave 5 and 7 alone.
    m.forEach([](DspEngine& e) { e.addPlugin(6, 0, static_cast<int>(SnapinType::GAIN)); });
    const int stereoFirst[] = {0};
    const int stereoSecond[] = {1};
    m.configureLanes(stereoFirst, stereoSecond, 1);
    check(m.primary().mixBusCount() == 6,
          "back to stereo drops untouched bus 7 and keeps the edited bus 6 (and 5 below it)");
    check(m.primary().routedBus() == -1, "and stereo is not routed");
}

void aSavedMixKeepsItsBusesThroughAnAtmosTrack() {
    MultiLaneEngine m(kRate, kBlock);
    m.configureLanes(k714First, k714Second, k714Lanes);
    DspEngine plain(kRate, kBlock);
    plain.setBusGain(0, -2.0f);
    // The app reloads the saved mix whenever the format changes.
    m.forEach([&](DspEngine& e) { e.loadStateJson(plain.getStateJson()); });
    check(m.primary().mixBusCount() == 7, "a four-bus mix loaded mid-Atmos still has a bus per group");
    check(m.primary().getStateJson() == plain.getStateJson(), "and saves as the four-bus mix it is");
    const int stereoFirst[] = {0};
    const int stereoSecond[] = {1};
    m.configureLanes(stereoFirst, stereoSecond, 1);
    check(m.primary().mixBusCount() == 4, "and is four buses again after");
}

void spreadCanBeSwitchedOffAndOnMidStream() {
    MultiLaneEngine m(kRate, kBlock);
    m.configureLanes(k714First, k714Second, k714Lanes);
    m.forEach([](DspEngine& e) { e.setBusMute(0, true); });  // bus 1 = front only
    Planar p(k714Channels);
    long n = 0;
    for (int b = 0; b < 20; b++) { fill714(p, n); m.processPlanar(p.ptr.data(), k714Channels, kBlock); }
    check(rms(p.ch[0]) < 1e-6 && rms(p.ch[2]) > 0.01, "spread: muting bus 1 silences only the front");

    m.setSpread(false);
    check(m.primary().mixBusCount() == 4, "one bus: the grown buses go");
    for (int b = 0; b < 20; b++) { fill714(p, n); m.processPlanar(p.ptr.data(), k714Channels, kBlock); }
    bool allSilent = true;
    for (int c = 0; c < k714Channels; c++) allSilent = allSilent && rms(p.ch[c]) < 1e-6;
    check(allSilent, "one bus: every channel runs through bus 1, so muting it silences all");

    m.setSpread(true);
    check(m.primary().mixBusCount() == 7, "spread again: a bus per group again");
}

void clonedLanesNarrowWithTheFirst() {
    // 7.1.4 builds lanes 1-6 as clones after the mixer grew to seven buses.
    // Going to 5.1 keeps lanes 1-3: they must hand back buses 5-7 as lane 0
    // does, or every later add or remove lands on a different bus per lane.
    MultiLaneEngine m(kRate, kBlock);
    m.configureLanes(k714First, k714Second, k714Lanes);
    const int k51First[] = {0, 2, 3, 4};
    const int k51Second[] = {1, -1, -1, 5};
    m.configureLanes(k51First, k51Second, 4);
    bool same = true;
    m.forEach([&](DspEngine& e) { same = same && e.mixBusCount() == 4; });
    check(same, "after 7.1.4 then 5.1 every lane has the same four buses");
}

int main() {
    stereoIsUnchanged();
    everyPairLaneRunsTheSameChain();
    masterDynamicsAreLinked();
    busDynamicsArePerLane();
    monoLanesSkipImageEffects();
    busesAddUpToTheCapAndStopThere();
    removingABusMovesTheOnesAboveDown();
    stateRoundTripsBusCount();
    anAddedBusCarriesAudio();
    atmosSpreadsAcrossTheMixer();
    grownBusesLeaveWithTheStream();
    aSavedMixKeepsItsBusesThroughAnAtmosTrack();
    spreadCanBeSwitchedOffAndOnMidStream();
    clonedLanesNarrowWithTheFirst();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
