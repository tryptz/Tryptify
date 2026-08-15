// Host-compilable unit tests for the pattern looper engine.
//
// Same harness style as atmos/tests — C++17 standard library and a handful of
// macros, no gtest — so they run on any host toolchain without the NDK:
//
//   c++ -std=c++17 -I.. pattern_engine_test.cpp ../pattern_engine.cpp \
//       -o pattern_engine_test && ./pattern_engine_test
//
// What these prove is the part that cannot be checked from Kotlin: that steps
// fire at the sample positions the clock says they should, that a queued
// pattern lands on the loop boundary and not before, and that the voice pool
// never runs off the end of a sample buffer.

#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

#include "../pattern_engine.h"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool cond, const char* expr, const char* file, int line) {
    ++g_checks;
    if (!cond) {
        ++g_failures;
        std::printf("  FAIL %s:%d  %s\n", file, line, expr);
    }
}

void check_eq(long long a, long long b, const char* expr, const char* file, int line) {
    ++g_checks;
    if (a != b) {
        ++g_failures;
        std::printf("  FAIL %s:%d  %s  (%lld vs %lld)\n", file, line, expr, a, b);
    }
}

void check_near(double a, double b, double tol, const char* expr, const char* file, int line) {
    ++g_checks;
    if (std::fabs(a - b) > tol) {
        ++g_failures;
        std::printf("  FAIL %s:%d  %s  (%.6f vs %.6f)\n", file, line, expr, a, b);
    }
}

#define CHECK(c) check((c), #c, __FILE__, __LINE__)
#define CHECK_EQ(a, b) check_eq((long long)(a), (long long)(b), #a " == " #b, __FILE__, __LINE__)
#define CHECK_NEAR(a, b, tol) check_near((a), (b), (tol), #a " ~= " #b, __FILE__, __LINE__)

using namespace tryptify;

constexpr int kRate = 48000;

/**
 * A burst of DC, so a hit is a countable rectangle rather than something the
 * envelope can hide. Shorter than a step at the tempos used below, which is
 * what gives every hit a clean silent gap in front of it.
 */
std::shared_ptr<SampleData> burst(int frames = 2400) {
    auto data = std::make_shared<SampleData>();
    data->frames = frames;
    data->sampleRate = kRate;
    data->gain = 1.0f;
    data->left.assign(static_cast<size_t>(frames), 1.0f);
    return data;
}

PatternState fourOnTheFloor(int length = 16, int slot = 0) {
    PatternState p;
    p.lengthSteps = length;
    p.stepsPerBeat = 4;
    p.beatsPerBar = 4;
    p.channelCount = 1;
    p.channels[0].sampleSlot = slot;
    p.channels[0].volume = 1.0f;
    p.channels[0].attackMs = 0.0f;
    p.channels[0].releaseMs = 1.0f;
    for (int s = 0; s < length; s += 4) {
        p.channels[0].steps[s].enabled = 1;
        p.channels[0].steps[s].velocity = 127;
    }
    return p;
}

void post(PatternEngine& engine, int type, int a = 0, int b = 0, int c = 0, float f = 0.0f) {
    Command cmd;
    cmd.type = type;
    cmd.a = a;
    cmd.b = b;
    cmd.c = c;
    cmd.f = f;
    engine.post(cmd);
}

/** Renders [frames] in [block]-sized calls and returns the onset positions. */
std::vector<int> renderAndFindOnsets(PatternEngine& engine, int frames, int block = 256) {
    std::vector<float> left(static_cast<size_t>(frames), 0.0f);
    std::vector<float> right(static_cast<size_t>(frames), 0.0f);
    int done = 0;
    while (done < frames) {
        const int n = std::min(block, frames - done);
        engine.render(left.data() + done, right.data() + done, n, true);
        done += n;
    }
    // The threshold is deliberately near zero rather than at some musical
    // level: the envelope's first sample is already non-zero, so the first
    // frame above it *is* the step boundary, and the assertions below can be
    // exact instead of approximate.
    std::vector<int> onsets;
    bool inHit = false;
    for (int i = 0; i < frames; ++i) {
        const bool loud = std::fabs(left[static_cast<size_t>(i)]) > 1e-6f;
        if (loud && !inHit) onsets.push_back(i);
        inHit = loud;
    }
    return onsets;
}

// ── clock ───────────────────────────────────────────────────────────────

void testClockMath() {
    std::printf("clock math\n");
    // 120 BPM, sixteenths: a beat is 0.5 s = 24000 frames, a step is 6000.
    CHECK_NEAR(framesPerStep(48000.0, 120.0, 4), 6000.0, 1e-9);
    CHECK_NEAR(framesPerStep(44100.0, 120.0, 4), 5512.5, 1e-9);
    // Eighth-note grid at the same tempo is twice as long.
    CHECK_NEAR(framesPerStep(48000.0, 120.0, 2), 12000.0, 1e-9);

    // Swing pushes offbeats late and leaves downbeats alone.
    CHECK_NEAR(swingDelayFrames(0, 1.0, 6000.0), 0.0, 1e-9);
    CHECK_NEAR(swingDelayFrames(1, 1.0, 6000.0), 2000.0, 1e-9);
    CHECK_NEAR(swingDelayFrames(1, 0.5, 6000.0), 1000.0, 1e-9);
    CHECK_NEAR(swingDelayFrames(2, 1.0, 6000.0), 0.0, 1e-9);

    // Full swing: 8000 frames to the offbeat, 4000 back to the downbeat —
    // the classic 2:1 shuffle, and the pair still adds up to two steps.
    CHECK_NEAR(framesToNextStep(0, 1.0, 6000.0), 8000.0, 1e-9);
    CHECK_NEAR(framesToNextStep(1, 1.0, 6000.0), 4000.0, 1e-9);
    CHECK_NEAR(framesToNextStep(0, 0.0, 6000.0) + framesToNextStep(1, 0.0, 6000.0), 12000.0, 1e-9);

    // Wrapping is correct for negative indices, which recording quantization
    // produces whenever a hit lands just before step 0.
    CHECK_EQ(wrapStep(-1, 16), 15);
    CHECK_EQ(wrapStep(-17, 16), 15);
    CHECK_EQ(wrapStep(16, 16), 0);
    CHECK_EQ(wrapStep(5, 12), 5);

    // Quantization snaps to the nearer boundary.
    CHECK_EQ(quantizeToStep(4, 100.0, 6000.0, 1), 4);
    CHECK_EQ(quantizeToStep(4, 5000.0, 6000.0, 1), 5);
    CHECK_EQ(quantizeToStep(4, 3000.0, 6000.0, 1), 5);   // exactly half rounds up
    CHECK_EQ(quantizeToStep(5, 100.0, 6000.0, 4), 4);    // beat quantize
    CHECK_EQ(quantizeToStep(6, 3000.0, 6000.0, 4), 8);
}

// ── looping ─────────────────────────────────────────────────────────────

void testStepTiming() {
    std::printf("step timing\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    CHECK(engine.uploadPattern(0, fourOnTheFloor()));

    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdQueuePattern, 0, 1);
    post(engine, kCmdTransport, kTransportPlay);

    // One bar at 120 BPM = 2 s = 96000 frames, four kicks 24000 apart.
    const auto onsets = renderAndFindOnsets(engine, 96000);
    CHECK_EQ(onsets.size(), 4u);
    if (onsets.size() == 4) {
        CHECK_EQ(onsets[0], 0);
        CHECK_EQ(onsets[1], 24000);
        CHECK_EQ(onsets[2], 48000);
        CHECK_EQ(onsets[3], 72000);
    }
}

void testBlockSizeIndependence() {
    std::printf("block size independence\n");
    // The same pattern must land on the same samples whether the host hands
    // us 64-frame or 4096-frame buffers — otherwise the loop drifts with the
    // output path, which is the failure mode a UI-driven timer has.
    std::vector<int> reference;
    for (int block : {64, 128, 480, 1024, 4096}) {
        PatternEngine engine(kRate);
        engine.samples().put(0, burst());
        engine.uploadPattern(0, fourOnTheFloor());
        post(engine, kCmdSetBpm, 0, 0, 0, 124.0f);
        post(engine, kCmdQueuePattern, 0, 1);
        post(engine, kCmdTransport, kTransportPlay);
        const auto onsets = renderAndFindOnsets(engine, 96000, block);
        if (reference.empty()) {
            reference = onsets;
            CHECK(reference.size() >= 4);
        } else {
            CHECK_EQ(onsets.size(), reference.size());
            const size_t n = std::min(onsets.size(), reference.size());
            for (size_t i = 0; i < n; ++i) CHECK_EQ(onsets[i], reference[i]);
        }
    }
}

void testPatternLengths() {
    std::printf("pattern lengths\n");
    // 4 / 8 / 16 / 32 / 64 and an odd 12, each with a hit on step 0 only:
    // the loop period must be exactly length × step.
    for (int length : {4, 8, 12, 16, 32, 64}) {
        PatternEngine engine(kRate);
        engine.samples().put(0, burst());
        PatternState p;
        p.lengthSteps = length;
        p.channelCount = 1;
        p.channels[0].sampleSlot = 0;
        p.channels[0].releaseMs = 1.0f;
        p.channels[0].steps[0].enabled = 1;
        engine.uploadPattern(0, p);

        post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
        post(engine, kCmdQueuePattern, 0, 1);
        post(engine, kCmdTransport, kTransportPlay);

        const int period = length * 6000;   // 6000 frames per step at 120 BPM
        const auto onsets = renderAndFindOnsets(engine, period * 3);
        CHECK_EQ(onsets.size(), 3u);
        if (onsets.size() == 3) {
            CHECK_EQ(onsets[0], 0);
            CHECK_EQ(onsets[1], period);
            CHECK_EQ(onsets[2], period * 2);
        }
    }
}

void testSwingMovesOffbeatsOnly() {
    std::printf("swing\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    PatternState p;
    p.lengthSteps = 4;
    p.channelCount = 1;
    p.channels[0].sampleSlot = 0;
    p.channels[0].releaseMs = 1.0f;
    for (int s = 0; s < 4; ++s) p.channels[0].steps[s].enabled = 1;
    engine.uploadPattern(0, p);

    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdSetSwing, 0, 0, 0, 1.0f);
    post(engine, kCmdQueuePattern, 0, 1);
    post(engine, kCmdTransport, kTransportPlay);

    const auto onsets = renderAndFindOnsets(engine, 24000);
    CHECK_EQ(onsets.size(), 4u);
    if (onsets.size() == 4) {
        CHECK_EQ(onsets[0], 0);
        CHECK_EQ(onsets[1], 8000);    // offbeat pushed a third of a step late
        CHECK_EQ(onsets[2], 12000);   // downbeat unmoved
        CHECK_EQ(onsets[3], 20000);
    }
}

void testQueuedSwitchWaitsForLoopBoundary() {
    std::printf("queued pattern switch\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());   // pattern 0's sound
    engine.samples().put(1, burst());   // pattern 1's sound

    engine.uploadPattern(0, fourOnTheFloor(16, 0));
    PatternState second;
    second.lengthSteps = 16;
    second.channelCount = 1;
    second.channels[0].sampleSlot = 1;
    second.channels[0].releaseMs = 1.0f;
    for (int s = 0; s < 16; ++s) second.channels[0].steps[s].enabled = 1;  // every step
    engine.uploadPattern(1, second);

    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdQueuePattern, 0, 1);
    post(engine, kCmdTransport, kTransportPlay);

    // Half a bar in, queue pattern 1. It must not take effect until the bar ends.
    std::vector<float> l(97000, 0.0f), r(97000, 0.0f);
    engine.render(l.data(), r.data(), 48000, true);
    CHECK_EQ(engine.currentPattern(), 0);
    post(engine, kCmdQueuePattern, 1, 0);
    engine.render(l.data() + 48000, r.data() + 48000, 24000, true);
    CHECK_EQ(engine.currentPattern(), 0);
    CHECK_EQ(engine.queuedPattern(), 1);

    // Still pattern 0 for the last step of the bar — the switch belongs to the
    // step *after* it, not to any point inside the bar.
    engine.render(l.data() + 72000, r.data() + 72000, 23999, true);
    CHECK_EQ(engine.currentPattern(), 0);
    engine.render(l.data() + 95999, r.data() + 95999, 64, true);
    CHECK_EQ(engine.currentPattern(), 1);
    CHECK_EQ(engine.queuedPattern(), -1);
}

void testImmediateSwitch() {
    std::printf("immediate pattern switch\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    engine.uploadPattern(0, fourOnTheFloor(16, 0));
    engine.uploadPattern(1, fourOnTheFloor(16, 0));

    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);
    std::vector<float> l(48000, 0.0f), r(48000, 0.0f);
    engine.render(l.data(), r.data(), 24000, true);

    post(engine, kCmdQueuePattern, 1, 1);   // b = 1 → immediate
    engine.render(l.data(), r.data(), 64, true);
    CHECK_EQ(engine.currentPattern(), 1);
    CHECK_EQ(engine.queuedPattern(), -1);
}

void testBpmChangeTakesEffectOnNextStep() {
    std::printf("tempo change\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    PatternState p;
    p.lengthSteps = 16;
    p.channelCount = 1;
    p.channels[0].sampleSlot = 0;
    p.channels[0].releaseMs = 1.0f;
    for (int s = 0; s < 16; ++s) p.channels[0].steps[s].enabled = 1;
    engine.uploadPattern(0, p);

    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);

    std::vector<float> l(48000, 0.0f), r(48000, 0.0f);
    engine.render(l.data(), r.data(), 6000, true);   // exactly one step
    post(engine, kCmdSetBpm, 0, 0, 0, 240.0f);       // half the step length
    const auto onsets = renderAndFindOnsets(engine, 12000);
    // At 240 BPM a sixteenth is 3000 frames; the first hit of the new render
    // is the step already due, then they come every 3000.
    CHECK(onsets.size() >= 4);
    if (onsets.size() >= 4) {
        CHECK_EQ(onsets[0], 0);
        CHECK_EQ(onsets[1], 3000);
        CHECK_EQ(onsets[2], 6000);
        CHECK_EQ(onsets[3], 9000);
    }
}

// ── voices ──────────────────────────────────────────────────────────────

void testVelocityScalesLevel() {
    std::printf("velocity\n");
    PatternEngine engine(kRate);
    auto sample = std::make_shared<SampleData>();
    sample->frames = 4800;
    sample->sampleRate = kRate;
    sample->left.assign(4800, 1.0f);   // DC so the level is easy to read
    engine.samples().put(0, sample);

    PatternState p;
    p.lengthSteps = 16;
    p.channelCount = 2;
    for (int c = 0; c < 2; ++c) {
        p.channels[c].sampleSlot = 0;
        p.channels[c].attackMs = 0.0f;
        p.channels[c].releaseMs = 1.0f;
        p.channels[c].steps[0].enabled = 1;
    }
    p.channels[0].steps[0].velocity = 127;
    p.channels[1].steps[0].velocity = 64;
    engine.uploadPattern(0, p);

    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);

    // Channel 1 alone, then channel 0 alone, by muting the other each time.
    std::vector<float> l(2400, 0.0f), r(2400, 0.0f);
    post(engine, kCmdSetChannelParam, 0, 1, kParamMute, 1.0f);
    engine.render(l.data(), r.data(), 2400, true);
    float loud = 0.0f;
    for (int i = 1000; i < 2000; ++i) loud = std::fmax(loud, std::fabs(l[static_cast<size_t>(i)]));

    PatternEngine quiet(kRate);
    quiet.samples().put(0, sample);
    quiet.uploadPattern(0, p);
    post(quiet, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(quiet, kCmdSetChannelParam, 0, 0, kParamMute, 1.0f);
    post(quiet, kCmdTransport, kTransportPlay);
    std::vector<float> l2(2400, 0.0f), r2(2400, 0.0f);
    quiet.render(l2.data(), r2.data(), 2400, true);
    float soft = 0.0f;
    for (int i = 1000; i < 2000; ++i) soft = std::fmax(soft, std::fabs(l2[static_cast<size_t>(i)]));

    CHECK(loud > 0.5f);
    CHECK(soft > 0.1f);
    CHECK_NEAR(soft / loud, 64.0f / 127.0f, 0.05);
}

void testMuteAndSolo() {
    std::printf("mute / solo\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    PatternState p;
    p.lengthSteps = 4;
    p.channelCount = 2;
    for (int c = 0; c < 2; ++c) {
        p.channels[c].sampleSlot = 0;
        p.channels[c].releaseMs = 1.0f;
        p.channels[c].steps[0].enabled = 1;
    }
    engine.uploadPattern(0, p);
    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);

    std::vector<float> l(24000, 0.0f), r(24000, 0.0f);
    engine.render(l.data(), r.data(), 600, true);
    CHECK(engine.channelTriggers(0) == 1);
    CHECK(engine.channelTriggers(1) == 1);

    // Solo channel 0: only it fires from here on.
    post(engine, kCmdSetChannelParam, 0, 0, kParamSolo, 1.0f);
    engine.render(l.data(), r.data(), 24000, true);
    CHECK(engine.channelTriggers(0) > 1);
    CHECK_EQ(engine.channelTriggers(1), 1u);
}

void testVoiceStaysInsideSample() {
    std::printf("voice bounds\n");
    // A 32-frame sample pitched up two octaves at a dense 64-step pattern:
    // if the reader ever ran past the buffer this would be a heap overflow,
    // and the address sanitiser build of this test is the thing that catches it.
    PatternEngine engine(kRate);
    auto sample = std::make_shared<SampleData>();
    sample->frames = 32;
    sample->sampleRate = kRate;
    sample->left.assign(32, 0.5f);
    sample->right.assign(32, -0.5f);
    engine.samples().put(0, sample);

    PatternState p;
    p.lengthSteps = 64;
    p.channelCount = 1;
    p.channels[0].sampleSlot = 0;
    p.channels[0].pitch = 24.0f;
    p.channels[0].sampleStart = 0.9f;
    p.channels[0].sampleEnd = 1.0f;
    for (int s = 0; s < 64; ++s) p.channels[0].steps[s].enabled = 1;
    engine.uploadPattern(0, p);
    post(engine, kCmdSetBpm, 0, 0, 0, 300.0f);
    post(engine, kCmdTransport, kTransportPlay);

    std::vector<float> l(48000, 0.0f), r(48000, 0.0f);
    engine.render(l.data(), r.data(), 48000, true);
    for (float v : l) CHECK(std::fabs(v) <= 2.0f);

    // And in reverse, from the other end.
    post(engine, kCmdSetChannelParam, 0, 0, kParamReverse, 1.0f);
    post(engine, kCmdSetChannelParam, 0, 0, kParamSampleStart, 0.0f);
    engine.render(l.data(), r.data(), 48000, true);
    for (float v : l) CHECK(std::fabs(v) <= 2.0f);
}

void testMixesRatherThanOverwrites() {
    std::printf("additive render\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    engine.uploadPattern(0, fourOnTheFloor(16, 0));
    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);

    // clearFirst = false is the in-chain path: the music is already in the
    // buffer and the pattern has to land on top of it, not replace it. This is
    // the difference between the looper joining the track and muting it.
    std::vector<float> mixed(1024, 0.25f), mixedR(1024, 0.25f);
    engine.render(mixed.data(), mixedR.data(), 1024, false);

    PatternEngine alone(kRate);
    alone.samples().put(0, burst());
    alone.uploadPattern(0, fourOnTheFloor(16, 0));
    post(alone, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(alone, kCmdTransport, kTransportPlay);
    std::vector<float> solo(1024, 0.0f), soloR(1024, 0.0f);
    alone.render(solo.data(), soloR.data(), 1024, true);

    for (size_t i = 0; i < 1024; ++i) {
        CHECK_NEAR(mixed[i], solo[i] + 0.25f, 1e-5);
    }
}

// ── live recording ──────────────────────────────────────────────────────

void testLiveRecordingQuantizes() {
    std::printf("live recording\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    PatternState p;
    p.lengthSteps = 16;
    p.channelCount = 1;
    p.channels[0].sampleSlot = 0;
    engine.uploadPattern(0, p);   // completely empty

    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdSetRecordQuantize, 1);
    post(engine, kCmdTransport, kTransportPlay);
    post(engine, kCmdTransport, kTransportRecordOn);

    std::vector<float> l(96000, 0.0f), r(96000, 0.0f);
    // Land a hit 400 frames after step 4 → snaps back onto step 4.
    engine.render(l.data(), r.data(), 4 * 6000 + 400, true);
    post(engine, kCmdTrigger, 0, 0, 0, 1.0f);
    engine.render(l.data(), r.data(), 64, true);

    PatternState readback;
    CHECK(engine.readPattern(0, readback));
    CHECK_EQ(readback.channels[0].steps[4].enabled, 1);
    CHECK_EQ(readback.channels[0].steps[3].enabled, 0);
    CHECK_EQ(readback.channels[0].steps[5].enabled, 0);
    CHECK(engine.recordEditCount() >= 1u);

    // And a hit late in step 8 snaps forward onto step 9.
    engine.render(l.data(), r.data(), 4 * 6000 - 464 + 5500, true);
    post(engine, kCmdTrigger, 0, 0, 0, 1.0f);
    engine.render(l.data(), r.data(), 64, true);
    CHECK(engine.readPattern(0, readback));
    CHECK_EQ(readback.channels[0].steps[9].enabled, 1);
}

void testCountInSuppressesPlayback() {
    std::printf("count-in\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    engine.uploadPattern(0, fourOnTheFloor(16, 0));
    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdSetCountIn, 1);
    post(engine, kCmdTransport, kTransportPlay);

    std::vector<float> l(96000, 0.0f), r(96000, 0.0f);
    engine.render(l.data(), r.data(), 96000, true);   // exactly one bar of pre-roll
    CHECK_EQ(engine.channelTriggers(0), 0u);
    CHECK_EQ(engine.countInRemaining(), 0);

    engine.render(l.data(), r.data(), 96000, true);   // the bar that plays
    CHECK_EQ(engine.channelTriggers(0), 4u);
}

// ── pattern publication ─────────────────────────────────────────────────

void testUploadWhilePlayingDoesNotTear() {
    std::printf("upload under playback\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst());
    engine.uploadPattern(0, fourOnTheFloor(16, 0));
    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);

    std::vector<float> l(4096, 0.0f), r(4096, 0.0f);
    // Far more uploads than there are spare buffers: the reclaim path has to
    // recycle them, or this runs out and starts returning false forever.
    int accepted = 0;
    for (int i = 0; i < 200; ++i) {
        engine.render(l.data(), r.data(), 1024, true);
        if (engine.uploadPattern(0, fourOnTheFloor(16, 0))) ++accepted;
    }
    CHECK(accepted > 150);
}

void testSampleSwapIsSafeUnderVoices() {
    std::printf("sample swap\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst(48000));
    engine.uploadPattern(0, fourOnTheFloor(16, 0));
    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);

    std::vector<float> l(4096, 0.0f), r(4096, 0.0f);
    engine.render(l.data(), r.data(), 512, true);      // a voice is now sounding
    engine.samples().put(0, burst(48000));           // retires the old buffer
    // Nothing may be freed while that voice still points at it.
    CHECK_EQ(engine.collectGarbage(), 0);
    for (int i = 0; i < 400; ++i) engine.render(l.data(), r.data(), 1024, true);
    CHECK(engine.collectGarbage() >= 0);
}

void testStopSilencesAndRewinds() {
    std::printf("stop\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst(48000));
    engine.uploadPattern(0, fourOnTheFloor(16, 0));
    post(engine, kCmdSetBpm, 0, 0, 0, 120.0f);
    post(engine, kCmdTransport, kTransportPlay);

    std::vector<float> l(96000, 0.0f), r(96000, 0.0f);
    engine.render(l.data(), r.data(), 30000, true);
    CHECK(engine.currentStep() > 0);
    post(engine, kCmdTransport, kTransportStop);
    engine.render(l.data(), r.data(), 4800, true);   // long enough for the tails
    CHECK_EQ(engine.currentStep(), 0);
    CHECK(!engine.playing());

    std::fill(l.begin(), l.end(), 0.0f);
    engine.render(l.data(), r.data(), 48000, true);
    float peak = 0.0f;
    for (float v : l) peak = std::fmax(peak, std::fabs(v));
    CHECK(peak < 1e-4f);
}

}  // namespace

int main() {
    std::printf("pattern engine tests\n");
    testClockMath();
    testStepTiming();
    testBlockSizeIndependence();
    testPatternLengths();
    testSwingMovesOffbeatsOnly();
    testQueuedSwitchWaitsForLoopBoundary();
    testImmediateSwitch();
    testBpmChangeTakesEffectOnNextStep();
    testVelocityScalesLevel();
    testMuteAndSolo();
    testVoiceStaysInsideSample();
    testMixesRatherThanOverwrites();
    testLiveRecordingQuantizes();
    testCountInSuppressesPlayback();
    testUploadWhilePlayingDoesNotTear();
    testSampleSwapIsSafeUnderVoices();
    testStopSilencesAndRewinds();

    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
