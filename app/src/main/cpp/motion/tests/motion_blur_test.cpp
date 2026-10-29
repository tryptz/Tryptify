// SPDX-License-Identifier: GPL-3.0-or-later
//
// Host tests for the motion blur's tracker and plans.
//
//   cmake -S app/src/main/cpp/motion/tests -B build/motion-tests
//   cmake --build build/motion-tests && ctest --test-dir build/motion-tests
// or directly:
//   c++ -std=c++17 -I.. motion_blur_test.cpp ../motion_blur.cpp

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <initializer_list>
#include <limits>

#include "motion_blur.h"

using namespace tryptify::motion;

static int g_checks = 0;
static int g_failures = 0;

#define CHECK(cond)                                                             \
    do {                                                                        \
        ++g_checks;                                                             \
        if (!(cond)) {                                                          \
            ++g_failures;                                                       \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);         \
        }                                                                       \
    } while (0)

#define CHECK_NEAR(a, b, eps)                                                   \
    do {                                                                        \
        ++g_checks;                                                             \
        const double va = (a);                                                  \
        const double vb = (b);                                                  \
        if (!(std::fabs(va - vb) <= (eps))) {                                   \
            ++g_failures;                                                       \
            std::printf("FAIL %s:%d  %s ~ %s  (%.7g vs %.7g)\n", __FILE__, __LINE__, \
                        #a, #b, va, vb);                                        \
        }                                                                       \
    } while (0)

namespace {

constexpr int64_t kSecond = 1000000000LL;

// Drives a tracker at `hz` for `seconds` at a steady `pxPerSec`, from `start`.
float runSteady(Tracker& t, double hz, double seconds, double pxPerSec, int64_t start = 0) {
    const int frames = static_cast<int>(seconds * hz);
    float v = 0.0f;
    for (int i = 0; i <= frames; ++i) {
        const double s = i / hz;
        v = t.update(start + static_cast<int64_t>(s * kSecond), static_cast<float>(s * pxPerSec), 1e6f);
    }
    return v;
}

// The coverage right behind the sprite: every copy overlaps there.
double edgeCoverage(const Trail& trail) { return 1.0 - std::pow(1.0 - trail.alpha, trail.taps); }

void steadySpeedIsMeasuredAtAnyRefreshRate() {
    for (double hz : {60.0, 90.0, 120.0, 144.0}) {
        Tracker t;
        const float v = runSteady(t, hz, 0.5, 1800.0);
        CHECK_NEAR(v, 1800.0, 1.0);
        CHECK_NEAR(t.frameSeconds(), 1.0 / hz, 1e-4);
    }
    // Signed with the axis: a scroll the other way reads negative.
    Tracker back;
    CHECK_NEAR(runSteady(back, 60.0, 0.5, -900.0), -900.0, 1.0);
}

void firstFrameIsAtRest() {
    Tracker t;
    CHECK(t.update(0, 500.0f, 1e6f) == 0.0f);
    CHECK(t.update(0, 900.0f, 1e6f) == 0.0f);  // the same frame drawn twice
}

void oneLateFrameDoesNotDoubleTheSmear() {
    Tracker t;
    runSteady(t, 120.0, 0.5, 1200.0);
    // One frame arrives late, covering two frames' distance: the speed is
    // unchanged, so the smear must stay put.
    const int64_t last = static_cast<int64_t>(0.5 * kSecond);
    const float v = t.update(last + 2 * kSecond / 120, 0.5f * 1200.0f + 2.0f * 1200.0f / 120.0f, 1e6f);
    CHECK_NEAR(v, 1200.0, 1.0);
    // ...and a frame that jitters early and short reads close to the truth.
    Tracker j;
    runSteady(j, 60.0, 0.5, 1200.0);
    const float jittered = j.update(static_cast<int64_t>((0.5 + 0.012) * kSecond), 600.0f + 1200.0f * 0.012f, 1e6f);
    CHECK_NEAR(jittered, 1200.0, 30.0);
}

void aJumpIsNotASpeed() {
    Tracker t;
    runSteady(t, 60.0, 0.5, 600.0);
    // A seek: 4000 px in one frame against a 1000 px jump threshold.
    const int64_t at = static_cast<int64_t>((0.5 + 1.0 / 60.0) * kSecond);
    CHECK(t.update(at, 4300.0f, 1000.0f) == 0.0f);
    // It starts again from the new place: the next steady frames build up
    // from rest, never from the jump.
    const float next = t.update(at + kSecond / 60, 4310.0f, 1000.0f);
    CHECK(next > 0.0f && next < 600.0f);
}

void aPauseStartsFromRest() {
    Tracker t;
    runSteady(t, 60.0, 0.5, 600.0);
    // The window was hidden for two seconds and the music moved on.
    CHECK(t.update(3 * kSecond, 1200.0f, 1e6f) == 0.0f);
    // A clock that went back is a restart too.
    CHECK(t.update(kSecond, 1200.0f, 1e6f) == 0.0f);
}

void stoppingFadesQuickly() {
    Tracker t;
    runSteady(t, 60.0, 0.5, 1500.0);
    // Held still for 150 ms: what is left of the speed draws no trail, even
    // at the longest shutter.
    float v = 0.0f;
    for (int i = 1; i <= 9; ++i) v = t.update(static_cast<int64_t>((0.5 + i / 60.0) * kSecond), 750.0f, 1e6f);
    CHECK(std::fabs(v) < 1500.0f * 0.02f);
    const auto& q = qualityFor(kQualityLevels - 1);
    CHECK(planTrail(q, v, exposureSeconds(q, 1.0f / 30.0f), 140.0f, 1).taps == 0);
}

void notANumberIsIgnored() {
    Tracker t;
    runSteady(t, 60.0, 0.25, 600.0);
    const float before = t.velocity();
    CHECK(t.update(kSecond / 2, std::numeric_limits<float>::quiet_NaN(), 1e6f) == before);
    CHECK(t.update(kSecond / 2, std::numeric_limits<float>::infinity(), 1e6f) == before);
}

void offDrawsNothing() {
    const auto& off = qualityFor(0);
    const Trail trail = planTrail(off, 3000.0f, exposureSeconds(off, 1.0f / 60.0f), 120.0f, 1);
    CHECK(trail.taps == 0);
    CHECK(blurSigma(off, 3000.0f, exposureSeconds(off, 1.0f / 60.0f)) == 0.0f);
}

void slowMotionCostsNothing() {
    for (int level = 1; level < kQualityLevels; ++level) {
        const auto& q = qualityFor(level);
        const float exposure = exposureSeconds(q, 1.0f / 60.0f);
        // A pixel of smear, at most: nothing extra is drawn.
        const float slow = 1.0f / exposure;
        CHECK(planTrail(q, slow, exposure, 120.0f, 1).taps == 0);
        CHECK(blurSigma(q, slow, exposure) == 0.0f);
        CHECK(planTrail(q, 0.0f, exposure, 120.0f, 1).taps == 0);
    }
}

void qualityChangesGrainNotBrightness() {
    int lastTaps = 0;
    for (int level = 1; level < kQualityLevels; ++level) {
        const auto& q = qualityFor(level);
        const Trail trail = planTrail(q, 2400.0f, exposureSeconds(q, 1.0f / 60.0f), 140.0f, 1);
        CHECK(trail.taps >= 1);
        CHECK(trail.taps >= lastTaps);  // smoother, never coarser, as the level rises
        lastTaps = trail.taps;
        CHECK_NEAR(edgeCoverage(trail), q.edgeCoverage, 1e-4);
        CHECK_NEAR(trail.lengthPx, trail.taps * trail.spacingPx, 1e-3);
        // Gaps as fine as the level asks, unless its copies ran out first.
        if (trail.taps < q.maxTaps) CHECK(trail.spacingPx <= q.tapSpacingPx + 1e-3f);
    }
    // Every level starts the trail equally opaque.
    for (int level = 2; level < kQualityLevels; ++level) {
        CHECK_NEAR(qualityFor(level).edgeCoverage, qualityFor(1).edgeCoverage, 0.0);
    }
}

void aFastScrollIsAStreakNotASecondNote() {
    for (int level = 1; level < kQualityLevels; ++level) {
        const auto& q = qualityFor(level);
        const Trail trail = planTrail(q, 1e6f, exposureSeconds(q, 1.0f / 30.0f), 100.0f, 1);
        CHECK(trail.lengthPx <= q.maxTrailOfSprite * 100.0f + 1e-3f);
        CHECK(trail.taps <= q.maxTaps && trail.taps <= kMaxTaps);
    }
}

void aDenseFrameSharesTheBudget() {
    const auto& q = qualityFor(4);
    const float exposure = exposureSeconds(q, 1.0f / 60.0f);
    const Trail one = planTrail(q, 3000.0f, exposure, 140.0f, 1);
    const Trail many = planTrail(q, 3000.0f, exposure, 140.0f, 200);
    CHECK(many.taps * 200 <= q.frameTapBudget);
    CHECK(many.taps < one.taps);
    // Still the same length and the same opacity, only coarser.
    CHECK_NEAR(many.lengthPx, one.lengthPx, 1e-3);
    CHECK_NEAR(edgeCoverage(many), edgeCoverage(one), 1e-4);
    // Past the budget, nothing at all rather than a slower frame.
    CHECK(planTrail(q, 3000.0f, exposure, 140.0f, q.frameTapBudget + 1).taps == 0);
}

void exposureFollowsTheFrame() {
    const auto& q = qualityFor(2);
    CHECK_NEAR(exposureSeconds(q, 1.0f / 60.0f), 0.5 / 60.0, 1e-7);
    CHECK_NEAR(exposureSeconds(q, 1.0f / 120.0f), 0.5 / 120.0, 1e-7);
    // A stall is not a long exposure.
    CHECK_NEAR(exposureSeconds(q, 1.0f), 0.5 * Tracker::kMaxFrameSeconds, 1e-7);
    CHECK(exposureSeconds(q, std::numeric_limits<float>::quiet_NaN()) == 0.0f);
}

void sigmaIsABoxFilterInQuarterSteps() {
    const auto& q = qualityFor(4);
    const float exposure = exposureSeconds(q, 1.0f / 30.0f);
    const float sigma = blurSigma(q, 400.0f, exposure);
    const double expected = 400.0 * exposure / std::sqrt(12.0);
    CHECK(sigma <= expected && sigma > expected - 0.25);
    CHECK_NEAR(std::fmod(sigma, 0.25f), 0.0, 1e-6);
    CHECK(blurSigma(q, 1e6f, exposure) == q.maxSigmaPx);
    CHECK(blurSigma(q, -400.0f, exposure) == sigma);  // either way the same
    CHECK(blurSigma(q, std::numeric_limits<float>::quiet_NaN(), exposure) == 0.0f);
}

void badInputsDrawNothing() {
    const auto& q = qualityFor(3);
    const float e = exposureSeconds(q, 1.0f / 60.0f);
    CHECK(planTrail(q, 3000.0f, e, 0.0f, 1).taps == 0);
    CHECK(planTrail(q, 3000.0f, e, 120.0f, 0).taps == 0);
    CHECK(planTrail(q, std::numeric_limits<float>::infinity(), e, 120.0f, 1).taps == 0);
    CHECK(planTrail(q, 3000.0f, -1.0f, 120.0f, 1).taps == 0);
    CHECK(qualityFor(-3).maxTaps == 0);
    CHECK(qualityFor(99).maxTaps == qualityFor(kQualityLevels - 1).maxTaps);
}

}  // namespace

int main() {
    steadySpeedIsMeasuredAtAnyRefreshRate();
    firstFrameIsAtRest();
    oneLateFrameDoesNotDoubleTheSmear();
    aJumpIsNotASpeed();
    aPauseStartsFromRest();
    stoppingFadesQuickly();
    notANumberIsIgnored();
    offDrawsNothing();
    slowMotionCostsNothing();
    qualityChangesGrainNotBrightness();
    aFastScrollIsAStreakNotASecondNote();
    aDenseFrameSharesTheBudget();
    exposureFollowsTheFrame();
    sigmaIsABoxFilterInQuarterSteps();
    badInputsDrawNothing();
    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
