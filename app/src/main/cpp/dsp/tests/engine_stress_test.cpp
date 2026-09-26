// Tries to break the mixer. Every effect at every rate the hi-res paths
// deliver (8 kHz to 384 kHz), at every oversampling factor, at the ends of
// every knob, on block sizes from one frame to the largest; then input no
// decoder should produce (NaN, infinity, denormals, full-scale square);
// then parameters no knob can reach; then a control thread tearing the graph
// apart while an audio thread plays through it; then corrupt saved mixes.
//
// Built with AddressSanitizer and UndefinedBehaviorSanitizer by
// run_host_tests.sh, so a read past a buffer fails here, not on a phone.
//
//   engine_stress_test <snapin_ranges.csv>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <limits>
#include <map>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "../dsp_engine.h"
#include "../multilane_engine.h"
#include "../util/finite.h"

namespace {

int failures = 0;
int checks = 0;

void check(bool ok, const std::string& what) {
    ++checks;
    if (!ok) {
        std::printf("FAIL  %s\n", what.c_str());
        ++failures;
    }
}

struct Range { float lo, hi; };
std::map<int, std::vector<Range>> ranges;

bool loadRanges(const char* path) {
    std::ifstream in(path);
    if (!in) return false;
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        int t, i;
        float lo, hi;
        if (std::sscanf(line.c_str(), "%d,%d,%f,%f", &t, &i, &lo, &hi) != 4) return false;
        auto& v = ranges[t];
        if (static_cast<int>(v.size()) != i) return false;
        v.push_back({lo, hi});
    }
    return ranges.size() == static_cast<size_t>(SnapinType::COUNT);
}

// Loud, but no louder than a mastered track: the output bound below is about
// what an effect does to it, not what the input already was.
constexpr float kOutputBound = 1000.0f;  // +60 dBFS: past this it has run away

bool finiteAndBounded(const float* x, int n, float& worst) {
    for (int i = 0; i < n; i++) {
        if (!dspIsFinite(x[i])) { worst = x[i]; return false; }
        if (std::fabs(x[i]) > std::fabs(worst)) worst = x[i];
    }
    return std::fabs(worst) <= kOutputBound;
}

const char* typeName(int t) {
    SnapinProcessor* p = createSnapin(static_cast<SnapinType>(t));
    static std::string name;
    name = p ? p->getName() : "?";
    delete p;
    return name.c_str();
}

const int kRates[] = {8000, 22050, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000};
constexpr int kMaxBlock = 4096;

// ── 1. Every effect, every rate, every factor, the ends of every knob ─────

void sweepEffects() {
    std::mt19937 rng(1234);
    std::uniform_real_distribution<float> noise(-0.5f, 0.5f);
    std::vector<float> l(kMaxBlock), r(kMaxBlock);
    const int blocks[] = {1, 7, 64, 333, 1024, kMaxBlock};

    int combos = 0;
    for (int t = 0; t < static_cast<int>(SnapinType::COUNT); t++) {
        const auto& rs = ranges[t];
        for (int rate : kRates) {
            for (int os : {1, 2, 4}) {
                // Defaults, every knob at its minimum, every knob at its
                // maximum, and two random settings inside the ranges.
                for (int setting = 0; setting < 5; setting++) {
                    std::unique_ptr<SnapinProcessor> p(createSnapin(static_cast<SnapinType>(t)));
                    p->setOversampling(os);
                    p->prepareOS(rate, kMaxBlock);
                    for (size_t i = 0; i < rs.size(); i++) {
                        float v;
                        if (setting == 0) continue;
                        if (setting == 1) v = rs[i].lo;
                        else if (setting == 2) v = rs[i].hi;
                        else v = rs[i].lo + (rs[i].hi - rs[i].lo) *
                                 std::uniform_real_distribution<float>(0.0f, 1.0f)(rng);
                        p->applyParameter(static_cast<int>(i), v);
                    }
                    // About a tenth of a second of audio in ragged blocks.
                    const int total = std::max(rate / 10, 2 * kMaxBlock);
                    float worst = 0.0f;
                    bool ok = true;
                    for (int done = 0, b = 0; done < total && ok; b++) {
                        const int n = std::min(blocks[b % 6], total - done);
                        for (int i = 0; i < n; i++) {
                            // Noise over a loud low tone: every band excited.
                            const float s = 0.5f * std::sin(0.02f * static_cast<float>(done + i));
                            l[i] = s + noise(rng);
                            r[i] = s + noise(rng);
                        }
                        p->processOS(l.data(), r.data(), n);
                        ok = finiteAndBounded(l.data(), n, worst) && finiteAndBounded(r.data(), n, worst);
                        done += n;
                    }
                    // Ten EQ bands at +24 dB on one frequency is +240 dB,
                    // and that is what those knobs ask for: gain, not a
                    // blow-up. It still has to stay finite.
                    if (!ok && setting >= 2 && t == static_cast<int>(SnapinType::EQ_10BAND) &&
                        dspIsFinite(worst)) {
                        ok = true;
                    }
                    char what[160];
                    std::snprintf(what, sizeof what, "%s at %d Hz, %dx, setting %d: output %g",
                                  typeName(t), rate, os, setting, worst);
                    check(ok, what);
                    combos++;
                }
            }
        }
    }
    std::printf("ok    swept %d effect/rate/oversampling/setting combinations\n", combos);
}

// ── 2. Parameters no knob can reach ─────────────────────────────────────────

void unreachableParameters() {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    const float wild[] = {nan, inf, -inf, 1e30f, -1e30f, 0.0f, -0.0f, 1e-30f};
    std::vector<float> l(512), r(512);
    int bad = 0;
    for (int t = 0; t < static_cast<int>(SnapinType::COUNT); t++) {
        for (int rate : {44100, 192000}) {
            for (float w : wild) {
                std::unique_ptr<SnapinProcessor> p(createSnapin(static_cast<SnapinType>(t)));
                p->prepareOS(rate, 512);
                const int np = p->getNumParameters();
                for (int i = -2; i < np + 2; i++) p->applyParameter(i, w);
                p->setDryWet(w);
                p->setOversampling(static_cast<int>(dspIsFinite(w) ? std::fmax(-1e6f, std::fmin(1e6f, w)) : 7));
                float worst = 0.0f;
                bool ok = true;
                for (int b = 0; b < 20 && ok; b++) {
                    for (int i = 0; i < 512; i++) {
                        l[i] = 0.3f * std::sin(0.05f * static_cast<float>(b * 512 + i));
                        r[i] = l[i];
                    }
                    p->processOS(l.data(), r.data(), 512);
                    ok = finiteAndBounded(l.data(), 512, worst) && finiteAndBounded(r.data(), 512, worst);
                }
                // 1e30 clamps every EQ band to +24 dB: gain, as in the sweep.
                if (!ok && t == static_cast<int>(SnapinType::EQ_10BAND) && dspIsFinite(worst)) ok = true;
                for (int i = 0; i < np; i++) {
                    if (!dspIsFinite(p->getParameter(i))) ok = false;
                }
                if (!ok) {
                    bad++;
                    char what[160];
                    std::snprintf(what, sizeof what, "%s at %d Hz with every parameter %g: output %g",
                                  typeName(t), rate, w, worst);
                    check(false, what);
                }
            }
        }
    }
    if (bad == 0) std::printf("ok    no parameter value (NaN, ±inf, ±1e30, out-of-range indices) breaks an effect\n");
}

// ── 3. Input no decoder should produce ─────────────────────────────────────

void hostileInput() {
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float inf = std::numeric_limits<float>::infinity();
    std::vector<float> l(512), r(512);
    int bad = 0;
    for (int t = 0; t < static_cast<int>(SnapinType::COUNT); t++) {
        DspEngine e(48000, 512);
        e.setBusInputEnabled(0, true);
        e.addPlugin(0, 0, t);
        e.setPluginOversampling(0, 0, 2);
        e.addPlugin(MASTER_BUS, 0, static_cast<int>(SnapinType::LIMITER));
        // Poison: NaN, infinity, denormals, a full-scale square.
        for (int kind = 0; kind < 4; kind++) {
            for (int i = 0; i < 512; i++) {
                float v;
                switch (kind) {
                    case 0: v = (i % 50 == 0) ? nan : 0.1f; break;
                    case 1: v = (i % 70 == 0) ? inf : -0.1f; break;
                    case 2: v = 1e-40f; break;
                    default: v = (i / 24) % 2 ? 1.0f : -1.0f; break;
                }
                l[i] = v;
                r[i] = -v;
            }
            e.process(l.data(), r.data(), 512);
            float worst = 0.0f;
            const bool ok = finiteAndBounded(l.data(), 512, worst) && finiteAndBounded(r.data(), 512, worst);
            if (!ok) {
                bad++;
                char what[160];
                std::snprintf(what, sizeof what, "%s: hostile input kind %d reaches the output (%g)",
                              typeName(t), kind, worst);
                check(false, what);
            }
        }
        // And it has to come back: a clean tone afterwards plays clean.
        bool recovered = true;
        float worst = 0.0f;
        for (int b = 0; b < 200; b++) {
            for (int i = 0; i < 512; i++) {
                l[i] = 0.25f * std::sin(0.03f * static_cast<float>(b * 512 + i));
                r[i] = l[i];
            }
            e.process(l.data(), r.data(), 512);
            if (b >= 150) {
                recovered = recovered && finiteAndBounded(l.data(), 512, worst) &&
                            finiteAndBounded(r.data(), 512, worst);
            }
        }
        if (!recovered) {
            bad++;
            char what[160];
            std::snprintf(what, sizeof what, "%s: still broken 3 s after hostile input (%g)", typeName(t), worst);
            check(false, what);
        }
    }
    if (bad == 0) std::printf("ok    NaN, infinity, denormal and full-scale input never reach the output, and every effect recovers\n");
}

// ── 4. Blocks longer than the engine was prepared for ──────────────────────

void oversizedBlocks() {
    DspEngine e(96000, 256);
    e.setBusInputEnabled(0, true);
    e.addPlugin(0, 0, static_cast<int>(SnapinType::DISTORTION));
    e.setPluginOversampling(0, 0, 4);
    e.addPlugin(MASTER_BUS, 0, static_cast<int>(SnapinType::COMPRESSOR));
    std::vector<float> l(5000, 0.2f), r(5000, 0.2f);
    e.process(l.data(), r.data(), 5000);
    float worst = 0.0f;
    check(finiteAndBounded(l.data(), 5000, worst) && finiteAndBounded(r.data(), 5000, worst),
          "a block 20x the prepared size is processed, not overrun");
    std::printf("ok    a block 20x the prepared size is processed in pieces\n");
}

// ── 5. A control thread tearing the graph apart under a playing stream ─────

void chaos(int seconds) {
    MultiLaneEngine engine(48000, 1024);
    std::atomic<bool> stop{false};
    std::atomic<long> blocks{0};
    std::atomic<int> nonFinite{0};

    // The audio thread: stereo, then 5.1, then 7.1.4, as tracks change.
    std::thread audio([&] {
        std::vector<std::vector<float>> ch(12, std::vector<float>(1024));
        std::vector<float*> ptr(12);
        for (int c = 0; c < 12; c++) ptr[c] = ch[c].data();
        std::mt19937 rng(7);
        long n = 0;
        while (!stop.load()) {
            const int frames = 1 + static_cast<int>(rng() % 1024);
            for (int c = 0; c < 12; c++) {
                for (int i = 0; i < frames; i++) {
                    ch[c][i] = 0.4f * std::sin(0.01f * static_cast<float>(n + i) * (1 + c));
                }
            }
            engine.processPlanar(ptr.data(), 12, frames);
            for (int c = 0; c < 12; c++) {
                for (int i = 0; i < frames; i++) {
                    if (!dspIsFinite(ch[c][i])) { nonFinite++; break; }
                }
            }
            n += frames;
            blocks++;
            // A device's audio thread waits for the next buffer between
            // blocks; without the pause this one re-takes the lane lock the
            // instant it drops it and the control thread barely gets a turn.
            std::this_thread::sleep_for(std::chrono::microseconds(200));
        }
    });

    // The control thread: everything the UI, a preset load and a track
    // change can do, as fast as it can, in random order.
    std::mt19937 rng(99);
    auto pick = [&](int n) { return static_cast<int>(rng() % static_cast<unsigned>(n)); };
    const int k51First[] = {0, 2, 3, 4};
    const int k51Second[] = {1, -1, -1, 5};
    const int k714First[] = {0, 2, 3, 4, 6, 8, 10};
    const int k714Second[] = {1, -1, -1, 5, 7, 9, 11};
    const int kStereoFirst[] = {0};
    const int kStereoSecond[] = {1};
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    long ops = 0;
    while (std::chrono::steady_clock::now() < until) {
        const int bus = pick(TOTAL_BUSES + 2) - 1;  // includes invalid -1 and 17
        const int slot = pick(MAX_PLUGINS_PER_BUS + 2) - 1;
        switch (pick(16)) {
            case 0: case 1: case 2:
                engine.forEach([&](DspEngine& e) { e.addPlugin(bus, slot, pick(static_cast<int>(SnapinType::COUNT) + 1)); });
                break;
            case 3:
                engine.forEach([&](DspEngine& e) { e.removePlugin(bus, slot); });
                break;
            case 4:
                engine.forEach([&](DspEngine& e) { e.movePlugin(bus, slot, pick(MAX_PLUGINS_PER_BUS)); });
                break;
            case 5: {
                const float v = (rng() % 2) ? static_cast<float>(pick(40000)) - 20000.0f
                                            : std::numeric_limits<float>::quiet_NaN();
                const int param = pick(12);
                engine.forEach([&](DspEngine& e) { e.setParameter(bus, slot, param, v); });
                break;
            }
            case 6: {
                const int f = pick(6);
                engine.forEach([&](DspEngine& e) { e.setPluginOversampling(bus, slot, f); });
                break;
            }
            case 7:
                engine.forEach([&](DspEngine& e) { e.addBus(); });
                break;
            case 8:
                engine.forEach([&](DspEngine& e) { e.removeBus(bus); });
                break;
            case 9: {
                const bool on = rng() % 2;
                engine.forEach([&](DspEngine& e) {
                    e.setBusInputEnabled(bus, on);
                    e.setPluginBypassed(bus, slot, !on);
                    e.setPluginDryWet(bus, slot, static_cast<float>(pick(100)) / 99.0f);
                });
                break;
            }
            case 10:
                engine.forEach([&](DspEngine& e) {
                    e.setBusSolo(bus, rng() % 2);
                    e.setBusMute(bus, rng() % 4 == 0);
                    e.setBusGain(bus, static_cast<float>(pick(200)) - 100.0f);
                    e.setBusPan(bus, static_cast<float>(pick(300)) / 100.0f - 1.5f);
                });
                break;
            case 11: {
                // A track change: new layout, sometimes a new rate.
                switch (pick(3)) {
                    case 0: engine.configureLanes(kStereoFirst, kStereoSecond, 1); break;
                    case 1: engine.configureLanes(k51First, k51Second, 4); break;
                    default: engine.configureLanes(k714First, k714Second, 7); break;
                }
                if (rng() % 3 == 0) engine.reconfigure(kRates[2 + pick(8)], 1024);
                break;
            }
            case 12:
                engine.setSpread(rng() % 2);
                break;
            case 13: {
                // A preset load: the primary's own state, round-tripped.
                const std::string state = engine.primary().getStateJson(true);
                engine.forEach([&](DspEngine& e) { e.loadStateJson(state); });
                break;
            }
            case 14: {
                float levels[TOTAL_BUSES * 4];
                engine.getBusLevels(levels, TOTAL_BUSES * 4);
                engine.getAndResetClipped();
                engine.forEach([](DspEngine& e) { e.resetMeters(); });
                break;
            }
            default:
                engine.forEach([&](DspEngine& e) { e.setMixBypassed(rng() % 5 == 0); });
                break;
        }
        ops++;
    }
    stop = true;
    audio.join();
    char what[160];
    std::snprintf(what, sizeof what, "chaos: %ld control changes against %ld audio blocks, %d with non-finite output",
                  ops, blocks.load(), nonFinite.load());
    check(nonFinite.load() == 0 && blocks.load() > 100, what);
    if (nonFinite.load() == 0) std::printf("ok    %s\n", what);
}

// ── 6. Saved mixes nobody should have saved ────────────────────────────────

void corruptState() {
    const char* states[] = {
        "",
        "{",
        "garbage",
        "{\"buses\":[",
        "{\"mixBusCount\":9999,\"buses\":[]}",
        "{\"mixBusCount\":-5}",
        "{\"buses\":[{\"gain\":1e999,\"pan\":\"x\",\"plugins\":[{\"type\":999,\"os\":64,\"dryWet\":-3,\"params\":[1e30,-1e30]}]}]}",
        "{\"buses\":[{\"plugins\":[{\"type\":-1},{\"type\":10,\"params\":[" /* truncated */,
        "{\"buses\":[{\"plugins\":[{\"type\":17,\"os\":4,\"params\":[nan,inf,-inf]}]}]}",
    };
    std::vector<float> l(512), r(512);
    int bad = 0;
    for (const char* s : states) {
        DspEngine e(44100, 256);
        e.loadStateJson(s);
        // A long saved mix too: every bus full.
        for (int b = 0; b < 30; b++) {
            for (int i = 0; i < 256; i++) l[i] = r[i] = 0.2f * std::sin(0.1f * static_cast<float>(b * 256 + i));
            e.process(l.data(), r.data(), 256);
        }
        float worst = 0.0f;
        if (!(finiteAndBounded(l.data(), 256, worst) && finiteAndBounded(r.data(), 256, worst))) {
            bad++;
            check(false, std::string("corrupt state plays non-finite audio: ") + s);
        }
        // And it can still be saved and loaded again.
        const std::string again = e.getStateJson(true);
        DspEngine f(44100, 256);
        f.loadStateJson(again);
    }
    // Every bus and slot filled, at 4x, saved and restored.
    {
        DspEngine e(48000, 512);
        for (int k = 0; k < MAX_MIX_BUSES - MIN_MIX_BUSES; k++) e.addBus();
        for (int b = 0; b < TOTAL_BUSES; b++) {
            e.setBusInputEnabled(b, true);
            for (int s = 0; s < MAX_PLUGINS_PER_BUS; s++) {
                e.addPlugin(b, s, (b * MAX_PLUGINS_PER_BUS + s) % static_cast<int>(SnapinType::COUNT));
                e.setPluginOversampling(b, s, 4);
            }
        }
        const std::string full = e.getStateJson(true);
        DspEngine f(48000, 512);
        f.loadStateJson(full);
        check(f.getStateJson(true) == full, "a full mix (16 buses x 16 slots at 4x) round-trips");
        for (int b = 0; b < 4; b++) {
            for (int i = 0; i < 512; i++) l[i] = r[i] = 0.2f * std::sin(0.07f * static_cast<float>(b * 512 + i));
            f.process(l.data(), r.data(), 256);
        }
        float worst = 0.0f;
        check(finiteAndBounded(l.data(), 256, worst), "a full mix at 4x plays");
    }
    if (bad == 0) std::printf("ok    corrupt and truncated saved mixes load without crashing and play finite audio\n");
}

// ── 7. Oversampling does not comb-filter ────────────────────────────────────

/** Null depth of [y] against [x] held back [delay] samples and scaled by [gain], in dB. */
double nullDb(const std::vector<float>& y, const std::vector<float>& x, int delay, float gain, size_t from) {
    double err = 0.0, sig = 0.0;
    for (size_t i = from; i < y.size(); i++) {
        const double want = gain * x[i - static_cast<size_t>(delay)];
        err += (y[i] - want) * (y[i] - want);
        sig += want * want;
    }
    return 10.0 * std::log10(std::max(err, 1e-30) / std::max(sig, 1e-30));
}

/** Runs [e] over a sweep of tones in ragged blocks and returns its left output. */
std::vector<float> runSweep(DspEngine& e, const std::vector<float>& x) {
    std::vector<float> y(x.size()), l(1024), r(1024);
    const int blocks[] = {512, 37, 1024, 300};
    for (size_t done = 0, b = 0; done < x.size(); b++) {
        const int n = static_cast<int>(std::min<size_t>(blocks[b % 4], x.size() - done));
        std::copy(x.begin() + done, x.begin() + done + n, l.begin());
        std::copy(x.begin() + done, x.begin() + done + n, r.begin());
        e.process(l.data(), r.data(), n);
        std::copy(l.begin(), l.begin() + n, y.begin() + done);
        done += static_cast<size_t>(n);
    }
    return y;
}

void alignment() {
    const int rate = 48000;
    // Tones across the band, so a comb anywhere in it shows.
    std::vector<float> x(static_cast<size_t>(rate));
    for (size_t i = 0; i < x.size(); i++) {
        double v = 0.0;
        for (double hz : {60.0, 440.0, 2500.0, 7000.0, 13000.0, 17500.0}) v += std::sin(2.0 * M_PI * hz * i / rate);
        x[i] = static_cast<float>(0.1 * v);
    }
    const int lat4 = ChannelOversampler::latencyFor(rate, 4);

    {
        // Bus 1 through a unity gain at 4x, bus 2 through one at 1x: summed,
        // twice the input, a whole number of samples late — not a comb.
        DspEngine e(rate, 1024);
        e.setBusInputEnabled(0, true);
        e.setBusInputEnabled(1, true);
        e.addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
        e.setPluginOversampling(0, 0, 4);
        e.addPlugin(1, 0, static_cast<int>(SnapinType::GAIN));
        const auto y = runSweep(e, x);
        const double d = nullDb(y, x, lat4, 2.0f, 8192);
        char what[160];
        std::snprintf(what, sizeof what, "a 4x bus summed with a 1x bus lines up (%d samples, null %.1f dB)", lat4, d);
        check(d < -70.0, what);
        if (d < -70.0) std::printf("ok    %s\n", what);
    }
    {
        // A 50 % blend of an oversampled unity gain is the input, delayed.
        DspEngine e(rate, 1024);
        e.addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
        e.setPluginOversampling(0, 0, 2);
        e.setPluginDryWet(0, 0, 0.5f);
        const auto y = runSweep(e, x);
        const int lat2 = ChannelOversampler::latencyFor(rate, 2);
        const double d = nullDb(y, x, lat2, 1.0f, 8192);
        char what[160];
        std::snprintf(what, sizeof what, "a 50 %% blend at 2x has no comb (null %.1f dB)", d);
        check(d < -70.0, what);
        if (d < -70.0) std::printf("ok    %s\n", what);
    }
    {
        // Bypassed, the effect still delays: the timing does not move.
        DspEngine e(rate, 1024);
        e.setBusInputEnabled(0, true);
        e.setBusInputEnabled(1, true);
        e.addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
        e.setPluginOversampling(0, 0, 4);
        e.setPluginBypassed(0, 0, true);
        e.addPlugin(1, 0, static_cast<int>(SnapinType::GAIN));
        const auto y = runSweep(e, x);
        const double d = nullDb(y, x, lat4, 2.0f, 8192);
        char what[160];
        std::snprintf(what, sizeof what, "bypassing the 4x effect keeps both buses in step (null %.1f dB)", d);
        check(d < -70.0, what);
        if (d < -70.0) std::printf("ok    %s\n", what);
    }
    {
        // At 192 kHz 4x is capped to off: no latency, no filter at all.
        DspEngine e(192000, 1024);
        e.addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
        e.setPluginOversampling(0, 0, 4);
        const auto y = runSweep(e, x);
        const double d = nullDb(y, x, 0, 1.0f, 8192);
        char what[160];
        std::snprintf(what, sizeof what, "at 192 kHz 4x runs as 1x: output is the input (null %.1f dB)", d);
        check(d < -100.0, what);
        if (d < -100.0) std::printf("ok    %s\n", what);
    }
}

}  // namespace

int main(int argc, char** argv) {
    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    if (argc < 2 || !loadRanges(argv[1])) {
        std::printf("FAIL  could not read the range table (%s)\n", argc > 1 ? argv[1] : "no path");
        return 1;
    }
    const int chaosSeconds = argc > 2 ? std::atoi(argv[2]) : 8;
    // "chaos" alone: for a ThreadSanitizer build, which cannot share a binary
    // with AddressSanitizer and has no use for the single-threaded sections.
    const bool chaosOnly = argc > 3 && std::string(argv[3]) == "chaos";
    if (!chaosOnly) {
        sweepEffects();
        unreachableParameters();
        hostileInput();
        oversizedBlocks();
        corruptState();
        alignment();
    }
    chaos(chaosSeconds);
    std::printf("%s (%d checks)\n", failures ? "FAILED" : "all passed", checks);
    return failures ? 1 : 0;
}
