// Host test for the mixer's exactness. A flat mix hands back the samples it
// was given bit for bit; a fader or pan moved and moved back lands there
// again, at every rate; the glide on the way is the one it always was; and
// the flag that tells MixBusProcessor a block came through untouched is
// never set on one that did not. Build and run with the other host tests:
//
//   ./run_host_tests.sh
//
// Built without -ffast-math: the glide is compared against the same float
// arithmetic done here, step for step.

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

#include "../dsp_engine.h"
#include "../multilane_engine.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kBlock = 512;
constexpr int kRates[] = {44100, 48000, 96000, 192000, 384000};

uint32_t seed = 1;

// Noise in (-0.9, 0.9), never exactly zero: a -0.0 may come back as +0.0
// (the flag's one allowance), and these tests compare bits.
float noise() {
    seed = seed * 1664525u + 1013904223u;
    const float u = (static_cast<float>(seed >> 8) + 0.5f) / 16777216.0f;  // (0, 1)
    return 1.8f * u - 0.9f;
}

bool sameBits(const float* a, const float* b, int n) {
    return std::memcmp(a, b, sizeof(float) * static_cast<size_t>(n)) == 0;
}

/** One block of noise through [e]: whether it came out bit for bit, and what the flag said. */
struct Block {
    bool exact;
    bool flagged;
};

Block runBlock(DspEngine& e, int n = kBlock) {
    std::vector<float> inL(n), inR(n), l(n), r(n);
    for (int i = 0; i < n; i++) { inL[i] = noise(); inR[i] = noise(); }
    l = inL;
    r = inR;
    e.process(l.data(), r.data(), n);
    return {sameBits(l.data(), inL.data(), n) && sameBits(r.data(), inR.data(), n), e.lastProcessUnchanged()};
}

/** [blocks] blocks; whether every one came out bit for bit and was flagged so. */
bool runExact(DspEngine& e, int blocks) {
    bool all = true;
    for (int b = 0; b < blocks; b++) {
        const Block k = runBlock(e);
        all = all && k.exact && k.flagged;
    }
    return all;
}

void runFor(DspEngine& e, int rate, double seconds) {
    const int blocks = static_cast<int>(seconds * rate / kBlock) + 1;
    for (int b = 0; b < blocks; b++) runBlock(e);
}

void freshEngineIsExact() {
    bool all = true;
    for (int rate : kRates) {
        DspEngine e(rate, 4096);
        all = all && runExact(e, 20);
    }
    check(all, "a fresh engine hands back every sample bit for bit, and says so");
}

void movedAndBackLandsExactly() {
    // A one-pole glide stops short of its target where each step rounds to
    // nothing: 1e-5 off at 48 kHz, further at high rates. It is snapped home
    // now, so a fader or pan moved and moved back is bit-perfect again.
    struct Move { int bus; bool pan; float there; };
    const Move moves[] = {
        {0, false, 3.0f}, {0, false, -3.0f}, {0, false, 12.0f},
        {0, true, 0.3f}, {0, true, -1.0f},
        {MASTER_BUS, false, 3.0f}, {MASTER_BUS, false, -6.0f}, {MASTER_BUS, true, 0.5f},
    };
    bool all = true;
    for (int rate : kRates) {
        for (const Move& m : moves) {
            DspEngine e(rate, 4096);
            if (m.pan) e.setBusPan(m.bus, m.there); else e.setBusGain(m.bus, m.there);
            runFor(e, rate, 0.5);
            const Block moved = runBlock(e);
            if (moved.exact || moved.flagged) all = false;  // it did move
            if (m.pan) e.setBusPan(m.bus, 0.0f); else e.setBusGain(m.bus, 0.0f);
            runFor(e, rate, 0.5);
            if (!runExact(e, 10)) {
                std::printf("      did not land: %d Hz, bus %d, %s %.1f\n", rate, m.bus, m.pan ? "pan" : "gain", m.there);
                all = false;
            }
        }
    }
    check(all, "a fader or pan moved and back is bit for bit again, at every rate");
}

void theGlideIsTheOneItWas() {
    // The glide itself is unchanged: until it is within the snap of its
    // target, every sample is the input times the gain the one-pole gives,
    // step for step as it always did.
    const int rate = 48000;
    DspEngine e(rate, 4096);
    e.setBusGain(0, 3.0f);
    const float target = std::pow(10.0f, 3.0f / 20.0f);
    const float coeff = 1.0f - std::exp(-1.0f / (0.005f * rate));
    float g = 1.0f;
    bool same = true;
    int compared = 0;
    constexpr int n = 64;  // short blocks, so most of the glide is compared
    for (int b = 0; b < 400; b++) {
        std::vector<float> inL(n), inR(n), l(n), r(n);
        for (int i = 0; i < n; i++) { inL[i] = noise(); inR[i] = noise(); }
        l = inL;
        r = inR;
        e.process(l.data(), r.data(), n);
        std::vector<float> wantL(n), wantR(n);
        for (int i = 0; i < n; i++) {
            g += coeff * (target - g);
            wantL[i] = inL[i] * g;
            wantR[i] = inR[i] * g;
        }
        same = same && sameBits(l.data(), wantL.data(), n) && sameBits(r.data(), wantR.data(), n);
        compared += n;
        // After this block the engine may snap to the target.
        if (std::fabs(target - g) <= 1e-4f * target) break;
    }
    std::printf("      compared the first %d samples of the glide\n", compared);
    check(same && compared > 1000, "a fader's glide is sample for sample the one it always was");
}

/** Runs [blocks] blocks: every flagged one must be bit for bit; returns the last flag. */
bool lastFlag(DspEngine& e, int blocks, bool& honest) {
    Block k{true, true};
    for (int b = 0; b < blocks; b++) {
        k = runBlock(e);
        if (k.flagged && !k.exact) honest = false;
    }
    return k.flagged;
}

void theFlagNeverLies() {
    DspEngine e(48000, 4096);
    bool honest = true;
    int wrong = 0;
    auto expect = [&](bool want, const char* what) {
        const bool got = lastFlag(e, 30, honest);
        if (got != want) {
            std::printf("      %s: flag %d, want %d\n", what, got ? 1 : 0, want ? 1 : 0);
            wrong++;
        }
    };
    expect(true, "fresh");
    const int eq = static_cast<int>(SnapinType::EQ_3BAND);
    e.addPlugin(0, 0, eq);
    e.setParameter(0, 0, 1, 6.0f);
    expect(false, "an EQ running on bus 1");
    e.setPluginBypassed(0, 0, true);
    expect(true, "the EQ bypassed");
    e.setPluginOversampling(0, 0, 2);
    expect(false, "the bypassed EQ oversampled, so latent");
    e.removePlugin(0, 0);
    expect(true, "the EQ removed");
    e.addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
    e.setParameter(0, 0, 0, 6.0f);
    expect(false, "a gain of +6 dB");
    e.setPluginDryWet(0, 0, 0.0f);
    expect(true, "the gain at 0 % wet");
    e.removePlugin(0, 0);
    e.setBusInputEnabled(1, true);
    expect(false, "bus 2 taking the input as well");
    e.setBusInputEnabled(1, false);
    expect(true, "bus 2 off again");
    e.setSend(0, MASTER_BUS, 0.5f);
    expect(false, "bus 1 sent to the master at half");
    e.setSend(0, MASTER_BUS, 1.0f);
    expect(true, "and back at unity");
    const int b5 = e.addBus();
    e.setSend(0, b5, 1.0f);
    e.setSend(0, MASTER_BUS, 0.0f);
    expect(false, "bus 1 reaching the master through another bus (untouched, but not known to be)");
    e.setSend(0, MASTER_BUS, 1.0f);
    e.setSend(0, b5, 0.0f);
    expect(true, "the direct route back");
    e.setBusMute(0, true);
    expect(false, "bus 1 muted");
    e.setBusMute(0, false);
    e.setBusSolo(1, true);
    expect(false, "another bus soloed");
    e.setBusSolo(1, false);
    expect(true, "solo off");
    e.addPlugin(MASTER_BUS, 0, static_cast<int>(SnapinType::LIMITER));
    e.setParameter(MASTER_BUS, 0, 1, -12.0f);
    expect(false, "a limiter on the master");
    e.setPluginBypassed(MASTER_BUS, 0, true);
    expect(true, "the limiter bypassed");
    e.addPlugin(0, 0, eq);
    e.setParameter(0, 0, 1, 6.0f);
    e.setMixBypassed(true);
    expect(true, "the mixer bypassed, an EQ on bus 1 skipped");
    e.setMixBypassed(false);
    expect(false, "the mixer back on");
    e.removePlugin(0, 0);
    e.setBusGain(MASTER_BUS, -1.0f);
    expect(false, "the master at -1 dB");
    e.setBusGain(MASTER_BUS, 0.0f);
    expect(true, "the master back at 0 dB");

    // A NaN in: replaced by silence, and that block is not the input.
    std::vector<float> l(kBlock, 0.25f), r(kBlock, 0.25f);
    uint32_t nanBits = 0x7FC00000u;
    std::memcpy(&l[7], &nanBits, sizeof nanBits);
    e.process(l.data(), r.data(), kBlock);
    check(!e.lastProcessUnchanged() && dspAllFinite(l.data(), kBlock), "a NaN in is silenced, and that block is flagged changed");
    expect(true, "the block after it");

    // A block longer than the engine's own, run in pieces: every piece counts.
    DspEngine small(48000, 300);
    Block k = runBlock(small, 1000);
    check(k.exact && k.flagged, "a block run in pieces is flagged untouched when every piece is");
    small.setBusGain(0, -2.0f);
    k = runBlock(small, 1000);
    check(!k.flagged, "and changed when one is");

    check(honest, "the flag is never set on a block that changed");
    check(wrong == 0, "the flag follows the mix through every change above");
}

void lanesAreExactAndSaySo() {
    // 7.1.4, spread one channel group per bus, mono lanes for centre and LFE.
    const int first[] = {0, 2, 3, 4, 6, 8, 10};
    const int second[] = {1, -1, -1, 5, 7, 9, 11};
    MultiLaneEngine m(48000, 1024);
    m.configureLanes(first, second, 7);
    std::vector<std::vector<float>> ch(12, std::vector<float>(kBlock)), in(12, std::vector<float>(kBlock));
    float* p[12];
    for (int c = 0; c < 12; c++) p[c] = ch[c].data();
    auto block = [&]() {
        for (int c = 0; c < 12; c++) {
            for (int i = 0; i < kBlock; i++) in[c][i] = ch[c][i] = noise();
        }
        m.processPlanar(p, 12, kBlock);
        bool same = true;
        for (int c = 0; c < 12; c++) same = same && sameBits(ch[c].data(), in[c].data(), kBlock);
        return Block{same, m.lastProcessUnchanged()};
    };
    bool all = true;
    for (int b = 0; b < 20; b++) {
        const Block k = block();
        all = all && k.exact && k.flagged;
    }
    check(all, "a flat 7.1.4 mix hands back every channel bit for bit, and says so");
    m.forEach([](DspEngine& e) { e.addPlugin(MASTER_BUS, 0, static_cast<int>(SnapinType::COMPRESSOR)); });
    bool honest = true, flagged = false;
    for (int b = 0; b < 20; b++) {
        const Block k = block();
        if (k.flagged && !k.exact) honest = false;
        flagged = flagged || k.flagged;
    }
    check(honest && !flagged, "with a compressor on the master it says changed");
}

void aSilentScopeStaysRight() {
    // A bus that falls silent writes zeros into its scope until the ring is
    // all zeros, then stops; the head stays where it was. Audio coming back
    // must still land at the newest end, behind it nothing but silence.
    DspEngine e(48000, 4096);
    auto feed = [&](float v, int n) {
        std::vector<float> l(n, v), r(n, v);
        e.process(l.data(), r.data(), n);
    };
    std::vector<float> w(WAVE_TAP_SIZE);
    auto wave = [&]() { return e.getBusWaveform(0, w.data(), WAVE_TAP_SIZE); };
    feed(0.5f, 1024);
    feed(0.5f, 1024);
    bool ok = wave() == WAVE_TAP_SIZE;
    for (int i = 0; i < WAVE_TAP_SIZE; i++) ok = ok && w[i] == 0.5f;
    e.setBusMute(0, true);
    feed(0.5f, 512);
    wave();
    for (int i = 0; i < WAVE_TAP_SIZE; i++) ok = ok && w[i] == (i < WAVE_TAP_SIZE - 512 ? 0.5f : 0.0f);
    for (int b = 0; b < 6; b++) feed(0.5f, 700);
    wave();
    for (int i = 0; i < WAVE_TAP_SIZE; i++) ok = ok && w[i] == 0.0f;
    e.setBusMute(0, false);
    feed(0.25f, 100);
    wave();
    for (int i = 0; i < WAVE_TAP_SIZE; i++) ok = ok && w[i] == (i < WAVE_TAP_SIZE - 100 ? 0.0f : 0.25f);
    check(ok, "a bus's scope decays to silence, stops writing there, and picks up again at its newest end");
}

// What dspZeroNonFinite was before it was made branch-free.
void oldZeroNonFinite(float* x, int n) {
    for (int i = 0; i < n; i++) {
        uint32_t b;
        std::memcpy(&b, &x[i], sizeof b);
        if ((b & 0x7F800000u) == 0x7F800000u) x[i] = 0.0f;
    }
}

void theNonFiniteGuardIsAsItWas() {
    // Every kind of bit pattern: the masked guard leaves exactly what the
    // branching one did, and says when it replaced something.
    bool same = true, told = true;
    for (int round = 0; round < 2000; round++) {
        float a[67], b[67];
        bool any = false;
        for (int i = 0; i < 67; i++) {
            seed = seed * 1664525u + 1013904223u;
            uint32_t bits = seed;
            switch (seed % 23) {
                case 0: bits = 0x7F800000u; break;            // +inf
                case 1: bits = 0xFF800000u; break;            // -inf
                case 2: bits = 0x7FC00001u | (seed >> 9); break;  // NaN
                case 3: bits = 0x80000000u; break;            // -0
                case 4: bits &= 0x807FFFFFu; break;           // denormal
                default: bits &= 0xBFFFFFFFu; break;          // finite
            }
            std::memcpy(&a[i], &bits, sizeof bits);
            std::memcpy(&b[i], &bits, sizeof bits);
            any = any || (bits & 0x7F800000u) == 0x7F800000u;
        }
        const int n = 1 + static_cast<int>(seed % 67);
        any = false;
        for (int i = 0; i < n; i++) any = any || !dspIsFinite(a[i]);
        oldZeroNonFinite(a, n);
        const bool replaced = dspZeroNonFinite(b, n);
        same = same && sameBits(a, b, 67);
        told = told && replaced == any;
    }
    check(same, "the branch-free non-finite guard leaves every sample as the old one did");
    check(told, "and reports exactly when it replaced one");
}

}  // namespace

int main() {
    freshEngineIsExact();
    movedAndBackLandsExactly();
    theGlideIsTheOneItWas();
    theFlagNeverLies();
    lanesAreExactAndSaySo();
    aSilentScopeStaysRight();
    theNonFiniteGuardIsAsItWas();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
