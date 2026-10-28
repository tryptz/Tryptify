// SPDX-License-Identifier: GPL-3.0-or-later
//
// ReplayGain's stage (replaygain/replay_gain.h), alone and in front of the
// rest of the DSP chain, in the order MixBusProcessor runs it for stereo:
// ReplayGain, then the mixer (DspEngine: buses, snapins, master), then the
// Oxford inflator, then the Oxford compressor.
//
// The stage itself: exact at unity, the gain otherwise, no wrap-around, the
// glide and back to exact. Then what the chain does with a song ReplayGain has
// raised to full scale: the chain left at its defaults stays at or under it,
// a gain change glides through every effect without a click, and every
// snapin, at its defaults, makes a finite signal of it. Where an effect takes
// a raised song past full scale on purpose (makeup gain, an unclipped
// inflator) the level is printed, not failed: that is the effect's job, and
// what keeps it from clipping is the next stage's.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <vector>

#include "../dsp_engine.h"
#include "../oxford/oxford_dsp.h"
#include "../replaygain/replay_gain.h"

using trypt::dsp::CompressorProcessor;
using trypt::dsp::InflatorProcessor;
using trypt::dsp::ReplayGainStage;

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) failures++;
}

constexpr int kRate = 48000;
constexpr int kBlock = 512;
constexpr double kPi = 3.14159265358979323846;

double dbfs(float peak) { return peak > 0.0f ? 20.0 * std::log10(peak) : -999.0; }

/** A stage at [gain], at the start of a stream. Built in place: the stage holds an atomic, and does not copy. */
struct Stage : ReplayGainStage {
    explicit Stage(float gain, int channels = 2, int rate = kRate) {
        prepare(rate, channels);
        setGain(gain);
        reset();
    }
};

std::vector<int16_t> run16(ReplayGainStage& s, std::vector<int16_t> in, int channels = 2) {
    std::vector<int16_t> out(in.size());
    s.processInt16(in.data(), out.data(), static_cast<int>(in.size()) / channels);
    return out;
}

std::vector<float> runF(ReplayGainStage& s, std::vector<float> in, int channels = 2) {
    std::vector<float> out(in.size());
    s.processFloat(in.data(), out.data(), static_cast<int>(in.size()) / channels);
    return out;
}

// ── The stage ────────────────────────────────────────────────────────────

void theStage() {
    std::printf("-- the stage\n");
    {
        Stage s(1.0f);
        const std::vector<int16_t> in{-32768, -1, 0, 1, 12345, 32767};
        check(run16(s, in) == in, "at unity 16-bit samples come through bit for bit");
    }
    {
        Stage s(1.0f);
        const std::vector<float> in{-1.0f, -0.000123f, 0.0f, 0.5f, 1.0f, 1.7f};
        check(runF(s, in) == in, "at unity float samples come through bit for bit, overs included");
    }
    {
        Stage s(0.5f);
        check(run16(s, {1000, -1000, 20000, -20000}) == std::vector<int16_t>({500, -500, 10000, -10000}),
              "a new stream starts at its gain, with no fade in");
    }
    {
        Stage s(2.0f);
        check(run16(s, {0, 0, 0, 0}) == std::vector<int16_t>({0, 0, 0, 0}), "silence stays silent at any gain");
    }
    {
        Stage s(2.0f);
        check(run16(s, {30000, -30000, 100, -100}) == std::vector<int16_t>({32767, -32768, 200, -200}),
              "a boost near full scale stops at full scale rather than wrapping");
    }
    {
        Stage s(2.0f);
        check(runF(s, {0.75f, -0.75f}) == std::vector<float>({1.5f, -1.5f}),
              "float is not clipped here, headroom is the later stages' to keep");
    }
    {
        // Rounds half up, as Java's Math.round: the Kotlin stage's results, exactly.
        Stage s(0.5f);
        check(run16(s, {3, -3, 5, -5}) == std::vector<int16_t>({2, -1, 3, -2}),
              "16-bit rounds half up, as the Kotlin stage did");
    }
    {
        Stage s(1.0f);
        s.setGain(0.5f);
        // 50 ms at 48 kHz is 2400 frames; a little over, in small buffers as a sink sends them.
        std::vector<int16_t> block(64, 10000), last;
        for (int i = 0; i < 100; i++) last = run16(s, block);
        check(std::all_of(last.begin(), last.end(), [](int16_t v) { return v == 5000; }), "a change glides to its goal");
        s.setGain(1.0f);
        for (int i = 0; i < 100; i++) run16(s, block);
        const std::vector<int16_t> in{-7, 3, 32000, -32000};
        check(s.current() == 1.0f && run16(s, in) == in, "and back at unity the output is exact again");
    }
    {
        Stage s(1.0f);
        s.setGain(0.5f);
        const auto out = run16(s, std::vector<int16_t>(64, 10000));
        check(out.front() > 9900 && out.back() > 5000 && out.back() < 10000, "the glide is gradual, not a step");
    }
    {
        // 50 ms at 44.1 kHz is 2205 frames: on the goal then, give or take the
        // rounding of 2205 small steps, and exactly on it.
        Stage s(1.0f, 1, 44100);
        s.setGain(0.5f);
        int landed = -1;
        for (int i = 0; i < 3000 && landed < 0; i++) {
            float x = 1.0f;
            s.processFloat(&x, &x, 1);
            if (x == 0.5f) landed = i + 1;
        }
        std::printf("      on the goal after %d frames\n", landed);
        check(landed >= 2204 && landed <= 2206, "the glide takes 50 ms at any rate and lands on its goal");
    }
    {
        Stage s(1.0f);
        s.setGain(NAN);
        const float a = s.gain();
        s.setGain(-2.0f);
        const float b = s.gain();
        s.setGain(INFINITY);
        check(a == 1.0f && b == 1.0f && s.gain() == 1.0f, "nonsense gains are taken as unity");
    }
    {
        Stage a(0.7f), b(0.7f);
        std::vector<float> in(256);
        for (size_t i = 0; i < in.size(); i++) in[i] = std::sin(0.01f * static_cast<float>(i));
        const auto apart = runF(a, in);
        b.processFloat(in.data(), in.data(), 128);
        check(in == apart, "in place gives what a separate output buffer does");
    }
    {
        Stage s(0.25f, 6);
        const auto out = runF(s, {1, 2, 3, 4, 5, 6, -1, -2, -3, -4, -5, -6}, 6);
        check(out == std::vector<float>({0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, -0.25f, -0.5f, -0.75f, -1.0f, -1.25f, -1.5f}),
              "every channel of a wide stream gets the same gain");
    }
}

// ── In front of the chain ────────────────────────────────────────────────

/** A song peaking at [peak]: two tones, so the shaper and the detector have something to work with. */
float song(long n, int channel, float peak) {
    const double t = static_cast<double>(n) / kRate;
    const double f = channel == 0 ? 220.0 : 330.0;
    return static_cast<float>(peak * (0.7 * std::sin(2 * kPi * f * t) + 0.3 * std::sin(2 * kPi * 3.1 * f * t)));
}

/** Large (the Oxford effects carry 16 channels of filters): made on the heap. */
struct Chain {
    ReplayGainStage rg;
    DspEngine engine{kRate, kBlock};
    InflatorProcessor inflator;
    CompressorProcessor compressor;

    explicit Chain(float gain) {
        rg.prepare(kRate, 2);
        rg.setGain(gain);
        rg.reset();
        engine.setBusInputEnabled(0, true);
        inflator.prepare(kRate, 2);
        compressor.prepare(kRate, 2);
        // As the app starts them: both off until switched on.
        inflator.params.bypass = true;
        compressor.params.bypass = true;
    }

    struct Result {
        float peak = 0.0f;
        bool finite = true;
        bool clippedFlag = false;
        float maxStep = 0.0f;   // largest sample-to-sample jump, left channel
        std::vector<float> left;
    };

    /**
     * [blocks] blocks of the song peaking at [peak], through the chain in
     * MixBusProcessor's order. [onBlock] may change a setting before a block.
     */
    template <typename OnBlock>
    Result run(int blocks, float peak, OnBlock onBlock, int measureFrom = 0) {
        Result r;
        std::vector<float> inter(kBlock * 2), l(kBlock), rr(kBlock);
        float* planes[2] = {l.data(), rr.data()};
        long n = 0;
        float prev = 0.0f;
        bool havePrev = false;
        for (int b = 0; b < blocks; b++) {
            onBlock(*this, b);
            for (int i = 0; i < kBlock; i++) {
                inter[2 * i] = song(n + i, 0, peak);
                inter[2 * i + 1] = song(n + i, 1, peak);
            }
            rg.processFloat(inter.data(), inter.data(), kBlock);
            for (int i = 0; i < kBlock; i++) {
                l[i] = inter[2 * i];
                rr[i] = inter[2 * i + 1];
            }
            engine.process(l.data(), rr.data(), kBlock);
            inflator.process(planes, kBlock);
            compressor.process(planes, kBlock);
            r.clippedFlag = engine.getAndResetClipped() || r.clippedFlag;
            for (int i = 0; i < kBlock; i++) {
                r.finite = r.finite && std::isfinite(l[i]) && std::isfinite(rr[i]);
                if (b >= measureFrom) {
                    r.peak = std::max({r.peak, std::fabs(l[i]), std::fabs(rr[i])});
                    if (havePrev) r.maxStep = std::max(r.maxStep, std::fabs(l[i] - prev));
                    prev = l[i];
                    havePrev = true;
                    r.left.push_back(l[i]);
                }
            }
            n += kBlock;
        }
        return r;
    }

    Result run(int blocks, float peak, int measureFrom = 0) {
        return run(blocks, peak, [](Chain&, int) {}, measureFrom);
    }
};

constexpr int kBlocks = 200;    // a little over two seconds
constexpr int kSettled = 40;    // past every effect's attack and the FIR's latency
constexpr float kQuiet = 0.5f;  // a song peaking at -6 dBFS
constexpr float kRaise = 2.0f;  // +6 dB: as far as ReplayGain will take it, to exactly full scale

void inFrontOfTheChain() {
    std::printf("-- in front of the chain (ReplayGain, mixer, inflator, compressor)\n");
    {
        auto unityOwned = std::make_unique<Chain>(1.0f);
        Chain& unity = *unityOwned;
        auto plainOwned = std::make_unique<Chain>(1.0f);
        Chain& plain = *plainOwned;
        const auto a = unity.run(kBlocks, kQuiet);
        // The same song with no ReplayGain stage at all.
        Chain::Result b;
        {
            std::vector<float> l(kBlock), r(kBlock);
            float* planes[2] = {l.data(), r.data()};
            long n = 0;
            for (int blk = 0; blk < kBlocks; blk++) {
                for (int i = 0; i < kBlock; i++) {
                    l[i] = song(n + i, 0, kQuiet);
                    r[i] = song(n + i, 1, kQuiet);
                }
                plain.engine.process(l.data(), r.data(), kBlock);
                plain.inflator.process(planes, kBlock);
                plain.compressor.process(planes, kBlock);
                b.left.insert(b.left.end(), l.begin(), l.end());
                n += kBlock;
            }
        }
        check(a.left == b.left, "at unity ReplayGain leaves the whole chain's output bit for bit as without it");
    }
    {
        auto cOwned = std::make_unique<Chain>(kRaise);
        Chain& c = *cOwned;
        const auto r = c.run(kBlocks, kQuiet, kSettled);
        std::printf("      raised to %.2f dBFS through the chain at its defaults\n", dbfs(r.peak));
        check(r.finite && r.peak <= 1.0f + 1e-6f && !r.clippedFlag,
              "a song raised to full scale stays at or under it through the chain at its defaults");
    }
    {
        auto cOwned = std::make_unique<Chain>(kRaise);
        Chain& c = *cOwned;
        c.inflator.params.bypass = false;   // on, at full effect, clipping at 0 dB as it starts
        const auto r = c.run(kBlocks, kQuiet, kSettled);
        std::printf("      inflator on: %.2f dBFS\n", dbfs(r.peak));
        check(r.finite && r.peak <= 1.0f + 1e-6f, "the inflator, clipping at 0 dB, keeps a raised song at full scale");
    }
    {
        auto cOwned = std::make_unique<Chain>(kRaise);
        Chain& c = *cOwned;
        c.inflator.params.bypass = false;
        c.inflator.params.clipZeroDb = false;  // the Tape glue preset's setting
        c.inflator.params.inputDb = 6.0f;
        const auto r = c.run(kBlocks, kQuiet, kSettled);
        std::printf("note  inflator unclipped, +6 dB in: %.2f dBFS after a raised song\n", dbfs(r.peak));
        check(r.finite, "an unclipped inflator after a raised song gives a finite signal");
    }
    {
        auto cOwned = std::make_unique<Chain>(kRaise);
        Chain& c = *cOwned;
        c.compressor.params.bypass = false;
        c.compressor.params.thresholdDb = -18.0f;
        c.compressor.params.ratio = 2.0f;
        c.compressor.params.makeupDb = 4.0f;   // as the built-in presets set it
        const auto r = c.run(kBlocks, kQuiet, kSettled);
        std::printf("note  compressor, +4 dB makeup: %.2f dBFS after a raised song\n", dbfs(r.peak));
        check(r.finite, "the compressor's makeup after a raised song gives a finite signal");
    }
    {
        auto cOwned = std::make_unique<Chain>(kRaise);
        Chain& c = *cOwned;
        c.engine.addPlugin(MASTER_BUS, 0, static_cast<int>(SnapinType::LIMITER));
        const auto r = c.run(kBlocks, kQuiet, kSettled);
        std::printf("      master limiter: %.2f dBFS\n", dbfs(r.peak));
        check(r.finite && r.peak <= 1.0f + 1e-4f, "a limiter on the master keeps a raised song at full scale");
    }
    {
        // A gain change mid-song (the setting moved, or the next song's gain
        // gliding in) through the inflator and compressor both on: the jump
        // between samples stays that of the music, no click.
        auto steadyOwned = std::make_unique<Chain>(kRaise);
        Chain& steady = *steadyOwned;
        steady.inflator.params.bypass = false;
        steady.compressor.params.bypass = false;
        steady.compressor.params.thresholdDb = -18.0f;
        const auto ref = steady.run(kBlocks, kQuiet, kSettled);

        auto movingOwned = std::make_unique<Chain>(0.5f);
        Chain& moving = *movingOwned;
        moving.inflator.params.bypass = false;
        moving.compressor.params.bypass = false;
        moving.compressor.params.thresholdDb = -18.0f;
        const auto glide = moving.run(kBlocks, kQuiet, [](Chain& c, int b) {
            if (b == 100) c.rg.setGain(kRaise);
        }, kSettled);
        // The control: the same change as a jump, which is a click.
        auto jumpingOwned = std::make_unique<Chain>(0.5f);
        Chain& jumping = *jumpingOwned;
        jumping.inflator.params.bypass = false;
        jumping.compressor.params.bypass = false;
        jumping.compressor.params.thresholdDb = -18.0f;
        const auto jump = jumping.run(kBlocks, kQuiet, [](Chain& c, int b) {
            if (b == 100) { c.rg.setGain(kRaise); c.rg.reset(); }
        }, kSettled);
        std::printf("      largest step: %.4f gliding, %.4f steady, %.4f as a jump\n", glide.maxStep, ref.maxStep, jump.maxStep);
        check(jump.maxStep > ref.maxStep * 1.5f, "the click check sees a jump in gain");
        check(glide.finite && glide.maxStep <= ref.maxStep * 1.5f,
              "a gain change glides through the inflator and compressor without a click");
    }
}

// ── Every snapin ─────────────────────────────────────────────────────────

const char* const kNames[] = {
    "Gain", "Stereo", "Filter", "EQ 3-band", "Compressor", "Limiter",
    "Gate", "Dynamics", "Compactor", "Transient shaper", "Distortion", "Shaper",
    "Chorus", "Ensemble", "Flanger", "Phaser", "Delay", "Reverb",
    "Bitcrush", "Comb filter", "Channel mixer", "Formant filter",
    "Frequency shifter", "Haas", "Ladder filter", "Nonlinear filter",
    "Phase distortion", "Pitch shifter", "Resonator", "Reverser",
    "Ring mod", "Tape stop", "Trance gate",
    "EQ 10-band", "Disperser", "Misstortion",
};
static_assert(sizeof(kNames) / sizeof(kNames[0]) == static_cast<int>(SnapinType::COUNT), "a name for every snapin");

void everySnapin() {
    std::printf("-- every snapin, at its defaults, after a song raised to full scale\n");
    int over = 0;
    bool allFinite = true;
    bool unityTransparentWhenBypassed = true;
    for (int t = 0; t < static_cast<int>(SnapinType::COUNT); t++) {
        auto cOwned = std::make_unique<Chain>(kRaise);
        Chain& c = *cOwned;
        c.engine.addPlugin(0, 0, t);
        const auto r = c.run(kBlocks, kQuiet, kSettled);
        allFinite = allFinite && r.finite;
        if (r.peak > 1.0f + 1e-6f) over++;
        std::printf("      %-18s %7.2f dBFS%s\n", kNames[t], dbfs(r.peak), r.peak > 1.0f + 1e-6f ? "  over full scale" : "");

        // Bypassed, the snapin is out of the way: ReplayGain's raised song comes through as with no snapin.
        auto withOwned = std::make_unique<Chain>(kRaise);
        Chain& with = *withOwned;
        auto withoutOwned = std::make_unique<Chain>(kRaise);
        Chain& without = *withoutOwned;
        with.engine.addPlugin(0, 0, t);
        with.engine.setPluginBypassed(0, 0, true);
        const auto a = with.run(60, kQuiet), b = without.run(60, kQuiet);
        if (a.left != b.left) {
            unityTransparentWhenBypassed = false;
            std::printf("      %s bypassed changes the signal\n", kNames[t]);
        }
    }
    std::printf("note  %d of %d snapins take a raised song past full scale at their defaults\n",
                over, static_cast<int>(SnapinType::COUNT));
    check(allFinite, "every snapin makes a finite signal of a song ReplayGain raised to full scale");
    check(unityTransparentWhenBypassed, "a bypassed snapin leaves ReplayGain's output untouched");
}

}  // namespace

int main() {
    theStage();
    inFrontOfTheChain();
    everySnapin();
    if (failures == 0) std::printf("all checks passed\n");
    return failures == 0 ? 0 : 1;
}
