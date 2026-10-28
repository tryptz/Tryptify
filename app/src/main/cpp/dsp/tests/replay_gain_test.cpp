// SPDX-License-Identifier: GPL-3.0-or-later
//
// ReplayGain's stage (replaygain/replay_gain.h), alone and in front of the
// rest of the DSP chain, in the order MixBusProcessor runs it for stereo:
// ReplayGain, then the mixer (DspEngine: buses, snapins, master), then the
// Oxford inflator, then the Oxford compressor.
//
// The stage itself: exact at unity, the gain otherwise, no wrap-around, the
// glide and back to exact, and where a stream starts (a new one, the same one
// after a seek, a blend's seeded tail). Then what the chain does with a song
// ReplayGain has raised to full scale: the chain left at its defaults stays at
// or under it, a gain change glides through every effect without a click, and
// every snapin, at its defaults, makes a finite signal of it. Where an effect takes
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
#include "../util/finite.h"

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
        // A boost starts a stream at unity and glides up (see reset()): past the glide first.
        Stage s(2.0f);
        run16(s, std::vector<int16_t>(2 * 2410, 0));
        check(run16(s, {30000, -30000, 100, -100}) == std::vector<int16_t>({32767, -32768, 200, -200}),
              "a boost near full scale stops at full scale rather than wrapping");
    }
    {
        Stage s(2.0f);
        runF(s, std::vector<float>(2 * 2410, 0.0f));
        check(runF(s, {0.75f, -0.75f}) == std::vector<float>({1.5f, -1.5f}),
              "float is not clipped here, headroom is the later stages' to keep");
    }
    {
        Stage s(2.0f);
        const auto out = runF(s, {0.5f, 0.5f});
        // One glide step past unity on the first frame, no more.
        check(out[0] <= 0.5f * 1.001f && s.current() < 1.001f,
              "a stream with a boost starts at unity, not boosted, and glides up");
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

// ── Where a stream starts ────────────────────────────────────────────────

/** What a flush does before the start itself, as the JNI calls it: the format again, then the song's mode. */
void reflush(ReplayGainStage& s, float gain, int channels = 2) {
    s.prepare(kRate, channels);
    s.setGain(gain);
    s.fixed();
}

/** [frames] stereo frames of [level] through [s]; the quietest sample out. */
float quietestOf(ReplayGainStage& s, int frames, float level, float* first = nullptr) {
    std::vector<float> in(static_cast<size_t>(frames) * 2, level), out(in.size());
    s.processFloat(in.data(), out.data(), frames);
    if (first != nullptr) *first = out[0];
    return *std::min_element(out.begin(), out.end());
}

void startingAStream() {
    std::printf("-- where a stream starts: new, the same again, seeded\n");
    constexpr float kBoost = 1.4f;   // about +2.9 dB
    constexpr float kIn = 0.5f;
    constexpr int kPastGlide = 2410; // 50 ms at 48 kHz is 2400 frames, give or take the rounding of its steps
    {
        // A seek in a raised song: the flush brings no new format, so the
        // level carries on. Every sample after it is at the settled gain.
        Stage s(kBoost);
        quietestOf(s, kPastGlide, kIn);
        const float settled = s.current();
        reflush(s, kBoost);
        s.resume();
        const float quietest = quietestOf(s, 4800, kIn);
        std::printf("      after a seek at %.2f dB: quietest sample %.4f, settled %.4f\n",
                    20.0 * std::log10(settled), quietest, kIn * settled);
        check(settled == kBoost && quietest >= kIn * settled,
              "a seek in a raised song keeps its level: no sample below the settled gain");

        // The control: the same flush started as a new stream dips to unity.
        Stage t(kBoost);
        quietestOf(t, kPastGlide, kIn);
        reflush(t, kBoost);
        t.reset();
        float first = 0.0f;
        quietestOf(t, 1, kIn, &first);
        check(first <= kIn * 1.001f && t.current() < 1.001f,
              "a new stream at a boost still starts at unity, so the next song's start is not boosted");
    }
    {
        // A seek mid-glide changes nothing: the output is the one with no flush at all.
        Stage a(kBoost), b(kBoost);
        std::vector<float> in(2 * 3000), outA(in.size()), outB(in.size());
        for (size_t i = 0; i < in.size(); i++) in[i] = 0.8f * std::sin(0.013f * static_cast<float>(i));
        a.processFloat(in.data(), outA.data(), 3000);
        b.processFloat(in.data(), outB.data(), 1000);
        const float mid = b.current();
        reflush(b, kBoost);
        b.resume();
        b.processFloat(in.data() + 2 * 1000, outB.data() + 2 * 1000, 2000);
        check(mid > 1.0f && mid < kBoost && outA == outB && b.current() == kBoost,
              "a seek mid-glide carries the glide on, sample for sample, and lands on the gain");
    }
    {
        Stage s(1.0f);
        reflush(s, 1.0f);
        s.resume();
        const std::vector<int16_t> in{-32768, -1, 0, 1, 12345, 32767};
        check(run16(s, in) == in, "at unity a seek leaves the samples bit for bit");
    }
    {
        // A blend's tail, seeded with the level the outgoing song plays at: from the first sample.
        ReplayGainStage tail;
        reflush(tail, kBoost);
        tail.startAt(kBoost);
        float first = 0.0f;
        const float quietest = quietestOf(tail, 4800, kIn, &first);
        check(first == kIn * kBoost && quietest == kIn * kBoost,
              "a seeded stream starts at the seed, with no dip and no glide");
    }
    {
        // Seeded mid-glide: from the seed, on to the gain, as usual.
        ReplayGainStage tail;
        reflush(tail, kBoost);
        tail.startAt(1.2f);
        float first = 0.0f;
        quietestOf(tail, 1, kIn, &first);
        const bool fromSeed = first >= kIn * 1.2f && first <= kIn * 1.2f * 1.001f;
        quietestOf(tail, kPastGlide, kIn);
        check(fromSeed && tail.current() == kBoost, "a seed below the gain starts there and glides on to it");
    }
    {
        ReplayGainStage tail;
        reflush(tail, 1.0f);
        tail.startAt(1.0f);
        const std::vector<int16_t> in{-32768, -1, 0, 1, 12345, 32767};
        check(run16(tail, in) == in, "a stream seeded at unity is bit for bit");
    }
    {
        // Nonsense seeds are no seed: the new stream starts by the mode, a boost at unity.
        bool allByMode = true;
        for (float bad : {0.0f, -1.0f, static_cast<float>(NAN), static_cast<float>(INFINITY)}) {
            Stage s(kBoost);
            quietestOf(s, kPastGlide, kIn);
            reflush(s, kBoost);
            s.startAt(bad);
            allByMode = allByMode && s.current() == 1.0f;
        }
        check(allByMode, "a seed that is not a positive finite number starts the stream by the mode");
    }
}

// ── Following a song with no tags ────────────────────────────────────────

/** Stereo music-like test signal: two tones a twelfth apart, at [peak]. */
void fillSong(std::vector<float>& inter, long start, float peak) {
    const int frames = static_cast<int>(inter.size()) / 2;
    for (int i = 0; i < frames; i++) {
        const double t = static_cast<double>(start + i) / kRate;
        inter[2 * i] = static_cast<float>(peak * (0.7 * std::sin(2 * kPi * 220.0 * t) + 0.3 * std::sin(2 * kPi * 660.0 * t)));
        inter[2 * i + 1] = static_cast<float>(peak * (0.7 * std::sin(2 * kPi * 330.0 * t) + 0.3 * std::sin(2 * kPi * 990.0 * t)));
    }
}

/** The song's own integrated loudness, LUFS, over [seconds]. */
float loudnessOf(float peak, double seconds) {
    tryptify::LoudnessMeter m;
    m.configure(kRate, 2);
    std::vector<float> block(kBlock * 2);
    long n = 0;
    for (int b = 0; b < static_cast<int>(seconds * kRate / kBlock); b++, n += kBlock) {
        fillSong(block, n, peak);
        m.process(block.data(), kBlock);
    }
    return m.reading().integrated;
}

double dbOf(float linear) { return 20.0 * std::log10(linear); }

/** [seconds] of the song at [peak] through [s], following song [song] to [target]; the output's loudness. */
struct Followed {
    float lastGain = 1.0f;
    float outLufs = 0.0f;
    float maxStepOut = 0.0f, maxStepIn = 0.0f;
    bool identical = true;
};

Followed follow(ReplayGainStage& s, uint32_t song, float target, float peak, double seconds, long& n) {
    Followed f;
    tryptify::LoudnessMeter out;
    out.configure(kRate, 2);
    std::vector<float> in(kBlock * 2), o(kBlock * 2);
    float prevIn = 0.0f, prevOut = 0.0f;
    bool first = true;
    const int blocks = static_cast<int>(seconds * kRate / kBlock);
    for (int b = 0; b < blocks; b++, n += kBlock) {
        fillSong(in, n, peak);
        s.follow(song, target);
        s.processFloat(in.data(), o.data(), kBlock);
        f.identical = f.identical && in == o;
        // The output's loudness over the last two seconds, after the glide has had its time.
        if (b >= blocks - 2 * kRate / kBlock) out.process(o.data(), kBlock);
        for (int i = 0; i < kBlock; i++) {
            if (!first) {
                f.maxStepIn = std::max(f.maxStepIn, std::fabs(in[2 * i] - prevIn));
                f.maxStepOut = std::max(f.maxStepOut, std::fabs(o[2 * i] - prevOut));
            }
            prevIn = in[2 * i];
            prevOut = o[2 * i];
            first = false;
        }
    }
    f.lastGain = s.current();
    f.outLufs = out.reading().integrated;
    return f;
}

void following() {
    std::printf("-- following a song with no tags\n");
    const float loud = 0.9f;
    const float songLufs = loudnessOf(loud, 5.0);
    std::printf("      the song measures %.2f LUFS\n", songLufs);
    {
        Stage s(1.0f);
        long n = 0;
        const float target = songLufs - 6.0f;
        const auto f = follow(s, 1, target, loud, 6.0, n);
        std::printf("      to %.2f LUFS: %.2f dB, comes out at %.2f LUFS\n", target, dbOf(f.lastGain), f.outLufs);
        check(std::fabs(dbOf(f.lastGain) + 6.0) < 0.3, "a loud song with no tags is turned down by what it measures over the target");
        check(std::fabs(f.outLufs - target) < 0.5f, "and comes out at the target loudness");
        check(f.maxStepOut <= f.maxStepIn * 1.01f, "the gain moves without a click");

        // The next song starts where this one was left, not at full level.
        std::vector<float> in(kBlock * 2), o(kBlock * 2);
        fillSong(in, 0, loud);
        s.follow(2, target);
        s.processFloat(in.data(), o.data(), kBlock);
        check(std::fabs(dbOf(s.current()) + 6.0) < 0.5, "the next song starts at the level the last one was left at");

        // The listener picks another target: the measurement carries on, so it moves at once.
        Stage t(1.0f);
        long m = 0;
        follow(t, 7, target, loud, 5.0, m);
        const auto moved = follow(t, 7, target - 3.0f, loud, 1.0, m);
        std::printf("      target 3 dB lower: %.2f dB after a second\n", dbOf(moved.lastGain));
        check(std::fabs(dbOf(moved.lastGain) + 9.0) < 0.3, "a new target on the same song moves the gain within a second");

        // A seek: a new stream, same song, same level.
        const float before = t.current();
        t.reset();
        check(std::fabs(dbOf(t.current()) - dbOf(before)) < 0.01, "a seek inside a measured song keeps its level");

        // Its tags arrive (or the next song has them): back to a fixed gain, in 50 ms.
        t.setGain(0.25f);
        t.fixed();
        // 50 ms is 2400 frames at 48 kHz, give or take the rounding of its steps.
        std::vector<float> ones(2 * 2410, 0.5f), out(2 * 2410);
        t.processFloat(ones.data(), out.data(), 2410);
        check(t.current() == 0.25f, "a song with tags after a measured one glides to its gain");
    }
    {
        Stage s(1.0f);
        long n = 0;
        const auto f = follow(s, 1, songLufs + 6.0f, loud, 5.0, n);
        check(f.lastGain == 1.0f && f.identical, "a song quieter than the target is not raised, and comes through bit for bit");
    }
    {
        // 16-bit is measured the same as float.
        Stage a(1.0f), b(1.0f);
        const float target = songLufs - 6.0f;
        std::vector<float> f(kBlock * 2), fo(kBlock * 2);
        std::vector<int16_t> i16(kBlock * 2), io(kBlock * 2);
        for (long n = 0; n < 5L * kRate; n += kBlock) {
            fillSong(f, n, loud);
            for (size_t k = 0; k < f.size(); k++) i16[k] = static_cast<int16_t>(std::lround(f[k] * 32767.0f));
            a.follow(1, target);
            b.follow(1, target);
            a.processFloat(f.data(), fo.data(), kBlock);
            b.processInt16(i16.data(), io.data(), kBlock);
        }
        check(std::fabs(dbOf(a.current()) - dbOf(b.current())) < 0.1, "16-bit is measured and followed as float is");
    }
    {
        // Silence has no loudness to go by: left where it is, here a cut.
        Stage s(0.5f);
        std::vector<float> zero(kBlock * 2, 0.0f), o(kBlock * 2);
        for (int b = 0; b < 200; b++) {
            s.follow(1, -14.0f);
            s.processFloat(zero.data(), o.data(), kBlock);
        }
        check(s.current() == 0.5f, "silence leaves a measured song's gain where it is");
    }
    {
        // The call order the player uses (the JNI): the new song's mode, then
        // the stream's start. A tagged song turned down, then a skip to a loud
        // one with no tags: it starts at the level the last was left at.
        auto s = std::make_unique<Stage>(0.35f);  // about -9 dB
        std::vector<float> in(kBlock * 2), o(kBlock * 2);
        fillSong(in, 0, loud);
        s->processFloat(in.data(), o.data(), kBlock);
        s->setGain(1.0f);
        s->follow(5, songLufs - 6.0f);
        s->reset();
        s->processFloat(in.data(), o.data(), kBlock);
        check(std::fabs(s->current() - 0.35f) < 0.01f, "a skip from a tagged song to a measured one starts at the last level");

        // Back the other way: a measured song at unity, then a skip to a
        // tagged one at -12 dB. It starts at its gain, not at the old level.
        auto t = std::make_unique<Stage>(1.0f);
        t->follow(1, songLufs + 6.0f);
        t->processFloat(in.data(), o.data(), kBlock);
        t->setGain(0.25f);
        t->fixed();
        t->reset();
        std::vector<float> one{1.0f, 1.0f}, first(2);
        t->processFloat(one.data(), first.data(), 1);
        check(first[0] == 0.25f, "a skip from a measured song to a tagged one starts at the tagged gain");
    }
    {
        // A gapless boundary comes before the player says what is next: the
        // stage restarts in the old song's mode. Mid-glide, it must not jump.
        auto s = std::make_unique<Stage>(1.0f);
        long n = 0;
        follow(*s, 1, songLufs - 6.0f, loud, 0.7, n);  // the glide is under way
        const float before = s->current();
        s->reset();
        check(before < 0.99f && before > 0.55f && s->current() == before,
              "a gapless boundary in a measured song leaves the level mid-glide where it is");

        // A boost must not run on into the next song's start: past its own peak, it would clip.
        auto b = std::make_unique<Stage>(2.0f);
        std::vector<float> z(2 * 2410, 0.0f), zo(2 * 2410);
        b->processFloat(z.data(), zo.data(), 2410);
        b->reset();
        check(b->current() == 1.0f, "a gapless boundary after a boosted song starts the next at unity");
    }
    {
        // A soft intro after a song left at -6 dB: for its first seconds the
        // gain may come down, never up, so the song proper does not burst in.
        auto s = std::make_unique<Stage>(0.5f);
        long n = 0;
        std::vector<float> in(kBlock * 2), o(kBlock * 2);
        float most = 0.0f;
        for (int b = 0; b < static_cast<int>(4.5 * kRate / kBlock); b++, n += kBlock) {
            fillSong(in, n, 0.02f);
            s->follow(9, songLufs - 6.0f);
            s->processFloat(in.data(), o.data(), kBlock);
            most = std::max(most, s->current());
        }
        check(most <= 0.5f + 1e-6f, "a soft intro does not raise the gain in a song's first seconds");

        // After them, a song quieter than the target rises back to unity, and is bit for bit again.
        for (int b = 0; b < static_cast<int>(4.0 * kRate / kBlock); b++, n += kBlock) {
            fillSong(in, n, 0.02f);
            s->follow(9, songLufs - 6.0f);
            s->processFloat(in.data(), o.data(), kBlock);
        }
        check(s->current() == 1.0f && in == o, "a quiet song rises back to exact unity and comes through bit for bit");
    }
    {
        // A song right at the target: the gain settles once and stays, no flicker around unity.
        auto s = std::make_unique<Stage>(1.0f);
        std::vector<float> in(kBlock * 2), o(kBlock * 2);
        float last = s->current();
        int changes = 0;
        long n = 0;
        for (int b = 0; b < static_cast<int>(60.0 * kRate / kBlock); b++, n += kBlock) {
            fillSong(in, n, loud);
            s->follow(3, songLufs);
            s->processFloat(in.data(), o.data(), kBlock);
            if (s->current() != last && (s->current() == 1.0f || last == 1.0f)) changes++;
            last = s->current();
        }
        std::printf("      at the target for a minute: %d moves to or from unity\n", changes);
        check(changes <= 1, "a song at the target does not flicker around unity");
    }
    {
        // A NaN from upstream must not freeze the measurement for the rest of the song.
        tryptify::LoudnessMeter control, hit;
        control.configure(kRate, 2);
        hit.configure(kRate, 2);
        std::vector<float> block(kBlock * 2);
        long n = 0;
        for (int b = 0; b < static_cast<int>(3.0 * kRate / kBlock); b++, n += kBlock) {
            fillSong(block, n, loud);
            control.process(block.data(), kBlock);
            hit.process(block.data(), kBlock);
        }
        const float before = control.reading().integrated;
        float bad[2] = {NAN, NAN};
        hit.process(bad, 1);
        // 6 dB quieter: inside the relative gate, so the reading has to move.
        for (int b = 0; b < static_cast<int>(10.0 * kRate / kBlock); b++, n += kBlock) {
            fillSong(block, n, loud * 0.5f);
            control.process(block.data(), kBlock);
            hit.process(block.data(), kBlock);
        }
        std::printf("      %.2f LUFS, then after a quieter part %.2f, and %.2f with a NaN before it\n",
                    before, control.reading().integrated, hit.reading().integrated);
        check(before - control.reading().integrated > 1.0f &&
                  std::fabs(control.reading().integrated - hit.reading().integrated) < 0.3f,
              "a NaN upstream does not freeze the loudness reading");
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
                // dspIsFinite, not std::isfinite: built with -ffast-math, as on
                // the phone, the compiler may fold the latter to true.
                r.finite = r.finite && dspIsFinite(l[i]) && dspIsFinite(rr[i]);
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
    startingAStream();
    following();
    inFrontOfTheChain();
    everySnapin();
    if (failures == 0) std::printf("all checks passed\n");
    return failures == 0 ? 0 : 1;
}
