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

// ── speed and pitch ─────────────────────────────────────────────────────

/**
 * A steady tone, which is what makes the two claims separable: a DC burst can
 * show that a hit got longer but has no pitch to check, and the whole point of
 * time stretching is that only one of the two moves.
 */
std::shared_ptr<SampleData> toneSample(int frames, double hz) {
    auto data = std::make_shared<SampleData>();
    data->frames = frames;
    data->sampleRate = kRate;
    data->gain = 1.0f;
    data->left.resize(static_cast<size_t>(frames));
    for (int i = 0; i < frames; ++i) {
        data->left[static_cast<size_t>(i)] =
            0.5f * static_cast<float>(std::sin(2.0 * M_PI * hz * i / kRate));
    }
    return data;
}

struct Hit {
    std::vector<float> left;
    int length = 0;      // samples from the first non-silent frame to the last
    double hz = 0.0;
};

/** Triggers one channel by hand and measures what came out. */
Hit triggerAndMeasure(PatternEngine& engine, int frames) {
    std::vector<float> l(static_cast<size_t>(frames), 0.0f);
    std::vector<float> r(static_cast<size_t>(frames), 0.0f);
    post(engine, kCmdTrigger, 0, 0, 0, 1.0f);

    int done = 0;
    while (done < frames) {
        const int n = std::min(512, frames - done);
        engine.render(l.data() + done, r.data() + done, n, true);
        done += n;
    }

    Hit hit;
    hit.left = l;
    int first = -1, last = -1;
    for (int i = 0; i < frames; ++i) {
        if (std::fabs(l[static_cast<size_t>(i)]) > 1e-5f) {
            if (first < 0) first = i;
            last = i;
        }
    }
    hit.length = (first < 0) ? 0 : (last - first + 1);

    // Rising zero crossings over the sustained middle, avoiding both ramps.
    if (hit.length > 4000) {
        const int from = first + hit.length / 4;
        const int to = first + (hit.length * 3) / 4;
        int crossings = 0;
        for (int i = from + 1; i < to; ++i) {
            if (l[static_cast<size_t>(i - 1)] <= 0.0f && l[static_cast<size_t>(i)] > 0.0f) ++crossings;
        }
        hit.hz = crossings * static_cast<double>(kRate) / (to - from);
    }
    return hit;
}

PatternState onePitchedChannel() {
    PatternState p;
    p.lengthSteps = 16;
    p.channelCount = 1;
    p.channels[0].sampleSlot = 0;
    p.channels[0].volume = 1.0f;
    p.channels[0].attackMs = 1.0f;
    p.channels[0].releaseMs = 2.0f;
    return p;
}

/**
 * The claim the whole stretcher exists for: speed moves duration and leaves
 * pitch alone, pitch moves pitch and leaves duration alone. A test that only
 * checked one of the two would pass just as happily for plain resampling.
 */
void testSpeedAndPitchAreIndependent() {
    std::printf("speed and pitch are independent\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, toneSample(96000, 440.0));
    engine.uploadPattern(0, onePitchedChannel());

    const Hit unity = triggerAndMeasure(engine, 300000);
    CHECK(unity.length > 90000);
    CHECK_NEAR(unity.hz, 440.0, 12.0);

    // Half speed: twice as long, same note.
    post(engine, kCmdSetChannelParam, 0, 0, kParamSpeed, 0.5f);
    const Hit slow = triggerAndMeasure(engine, 300000);
    const double lengthRatio = static_cast<double>(slow.length) / unity.length;
    std::printf("    half speed: %.3fx length, %.1f Hz\n", lengthRatio, slow.hz);
    CHECK_NEAR(lengthRatio, 2.0, 0.06);
    CHECK_NEAR(slow.hz, 440.0, 440.0 * 0.04);

    // Double speed: half as long, still the same note.
    post(engine, kCmdSetChannelParam, 0, 0, kParamSpeed, 2.0f);
    const Hit fast = triggerAndMeasure(engine, 300000);
    const double fastRatio = static_cast<double>(fast.length) / unity.length;
    std::printf("    double speed: %.3fx length, %.1f Hz\n", fastRatio, fast.hz);
    CHECK_NEAR(fastRatio, 0.5, 0.04);
    CHECK_NEAR(fast.hz, 440.0, 440.0 * 0.04);

    // An octave up at normal speed: same length, twice the frequency.
    post(engine, kCmdSetChannelParam, 0, 0, kParamSpeed, 1.0f);
    post(engine, kCmdSetChannelParam, 0, 0, kParamPitch, 12.0f);
    const Hit high = triggerAndMeasure(engine, 300000);
    const double highRatio = static_cast<double>(high.length) / unity.length;
    std::printf("    +12 st: %.3fx length, %.1f Hz\n", highRatio, high.hz);
    CHECK_NEAR(highRatio, 1.0, 0.06);
    CHECK_NEAR(high.hz, 880.0, 880.0 * 0.04);

    // An octave down, same again.
    post(engine, kCmdSetChannelParam, 0, 0, kParamPitch, -12.0f);
    const Hit low = triggerAndMeasure(engine, 300000);
    std::printf("    -12 st: %.3fx length, %.1f Hz\n",
                static_cast<double>(low.length) / unity.length, low.hz);
    CHECK_NEAR(static_cast<double>(low.length) / unity.length, 1.0, 0.06);
    CHECK_NEAR(low.hz, 220.0, 220.0 * 0.05);
}

/** Linked is tape: one knob, both effects. */
void testLinkedModeIsVarispeed() {
    std::printf("linked mode is varispeed\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, toneSample(96000, 440.0));
    engine.uploadPattern(0, onePitchedChannel());

    const Hit unity = triggerAndMeasure(engine, 300000);

    post(engine, kCmdSetChannelParam, 0, 0, kParamLinked, 1.0f);
    post(engine, kCmdSetChannelParam, 0, 0, kParamSpeed, 2.0f);
    const Hit tape = triggerAndMeasure(engine, 300000);

    const double ratio = static_cast<double>(tape.length) / unity.length;
    std::printf("    linked 2x: %.3fx length, %.1f Hz\n", ratio, tape.hz);
    // Half the length *and* an octave up — the two move together, which is the
    // difference between this mode and the unlinked one above.
    CHECK_NEAR(ratio, 0.5, 0.03);
    CHECK_NEAR(tape.hz, 880.0, 880.0 * 0.04);
}

/**
 * When pitch and speed ask for the same factor the stretch ratio is exactly 1,
 * and the voice should read the sample directly rather than running a
 * stretcher to reproduce its input.
 *
 * Proved by sample-identical output against tape mode, which takes the bypass
 * unconditionally. Anything else — a stretcher that runs and happens to sound
 * close — would differ in the low bits.
 */
void testUnityStretchTakesTheBypass() {
    std::printf("unity stretch bypasses\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, toneSample(48000, 440.0));
    engine.uploadPattern(0, onePitchedChannel());

    // Unlinked, +12 st and 2x speed: P == S, so P / S == 1.
    post(engine, kCmdSetChannelParam, 0, 0, kParamPitch, 12.0f);
    post(engine, kCmdSetChannelParam, 0, 0, kParamSpeed, 2.0f);
    const Hit unlinked = triggerAndMeasure(engine, 60000);

    // Linked, +12 st at 1x speed: the same varispeed factor by the other road.
    PatternEngine tapeEngine(kRate);
    tapeEngine.samples().put(0, toneSample(48000, 440.0));
    tapeEngine.uploadPattern(0, onePitchedChannel());
    post(tapeEngine, kCmdSetChannelParam, 0, 0, kParamLinked, 1.0f);
    post(tapeEngine, kCmdSetChannelParam, 0, 0, kParamPitch, 12.0f);
    const Hit tape = triggerAndMeasure(tapeEngine, 60000);

    CHECK(unlinked.length > 20000);
    CHECK_EQ(unlinked.length, tape.length);
    int mismatches = 0;
    for (size_t i = 0; i < unlinked.left.size(); ++i) {
        if (unlinked.left[i] != tape.left[i]) ++mismatches;
    }
    CHECK_EQ(mismatches, 0);
}

/**
 * A sample shorter than one grain has nothing for WSOLA to work with. It must
 * still make a sound — silence would be the worst possible answer for a short
 * percussive hit, which is most of what a sampler plays.
 */
void testShortSampleFallsBackRatherThanVanishing() {
    std::printf("short sample fallback\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, burst(600));   // 12.5 ms, well under any grain
    engine.uploadPattern(0, onePitchedChannel());

    post(engine, kCmdSetStretchQuality, kStretchHigh);   // 2048-sample grain
    post(engine, kCmdSetChannelParam, 0, 0, kParamSpeed, 0.5f);
    const Hit hit = triggerAndMeasure(engine, 48000);

    float peak = 0.0f;
    for (float v : hit.left) peak = std::fmax(peak, std::fabs(v));
    std::printf("    600-frame sample at 0.5x: %d samples, peak %.3f\n", hit.length, peak);
    CHECK(hit.length > 100);
    CHECK(peak > 0.3f);
}

/** Reverse cannot be stretched, so it takes speed as varispeed instead. */
void testReverseUsesVarispeed() {
    std::printf("reverse uses varispeed\n");
    PatternEngine engine(kRate);
    engine.samples().put(0, toneSample(48000, 440.0));

    PatternState p = onePitchedChannel();
    p.channels[0].reverse = 1;
    engine.uploadPattern(0, p);

    const Hit unity = triggerAndMeasure(engine, 200000);
    CHECK(unity.length > 40000);

    post(engine, kCmdSetChannelParam, 0, 0, kParamSpeed, 2.0f);
    const Hit fast = triggerAndMeasure(engine, 200000);
    const double ratio = static_cast<double>(fast.length) / unity.length;
    std::printf("    reverse 2x: %.3fx length, %.1f Hz\n", ratio, fast.hz);
    // Half the length and an octave up: the speed knob still does something,
    // it just does the tape thing.
    CHECK_NEAR(ratio, 0.5, 0.03);
    CHECK_NEAR(fast.hz, 880.0, 880.0 * 0.05);
}

/** A speed lock pins one step; the rest keep following the channel. */
void testPerStepSpeedLock() {
    std::printf("per-step speed lock\n");
    // The byte mapping has to round-trip, or a lock saved from the UI would
    // come back as a different tempo.
    // The octave boundaries have to be exact codes, not nearby ones. Speed
    // does not move pitch, so an inexact half-time lock shows up purely as
    // timing drift against the loop it plays under.
    CHECK_EQ(stepSpeedToCode(0.25f), 1);
    CHECK_EQ(stepSpeedToCode(0.5f), 64);
    CHECK_EQ(stepSpeedToCode(1.0f), 127);
    CHECK_EQ(stepSpeedToCode(2.0f), 190);
    CHECK_EQ(stepSpeedToCode(4.0f), 253);
    CHECK_NEAR(stepSpeedFromCode(1), 0.25, 1e-6);
    CHECK_NEAR(stepSpeedFromCode(64), 0.5, 1e-6);
    CHECK_NEAR(stepSpeedFromCode(127), 1.0, 1e-6);
    CHECK_NEAR(stepSpeedFromCode(190), 2.0, 1e-6);
    CHECK_NEAR(stepSpeedFromCode(253), 4.0, 1e-5);
    for (float s : {0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f}) {
        const float back = stepSpeedFromCode(stepSpeedToCode(s));
        CHECK_NEAR(back, s, s * 0.006);
    }
    // Code 0 is "no lock", not the bottom of the range.
    CHECK_NEAR(stepSpeedFromCode(0), 1.0, 1e-6);

    PatternEngine engine(kRate);
    engine.samples().put(0, toneSample(96000, 440.0));

    PatternState p = onePitchedChannel();
    p.lengthSteps = 4;
    p.stepsPerBeat = 4;
    for (int s = 0; s < 4; ++s) {
        p.channels[0].steps[s].enabled = 1;
        p.channels[0].steps[s].velocity = 127;
    }
    // Step 2 alone plays at half speed.
    p.channels[0].steps[2].locks = kLockSpeed;
    p.channels[0].steps[2].speed = stepSpeedToCode(0.5f);
    engine.uploadPattern(0, p);

    PatternState back;
    CHECK(engine.readPattern(0, back));
    CHECK_EQ(back.channels[0].steps[2].locks & kLockSpeed, kLockSpeed);
    CHECK_NEAR(stepSpeedFromCode(back.channels[0].steps[2].speed), 0.5, 0.01);
    CHECK_EQ(back.channels[0].steps[0].locks & kLockSpeed, 0);
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
    testSpeedAndPitchAreIndependent();
    testLinkedModeIsVarispeed();
    testUnityStretchTakesTheBypass();
    testShortSampleFallsBackRatherThanVanishing();
    testReverseUsesVarispeed();
    testPerStepSpeedLock();

    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
