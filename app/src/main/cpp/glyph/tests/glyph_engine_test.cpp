// SPDX-License-Identifier: GPL-3.0-or-later
//
// Host tests for the native Trypt Mania engine.
//
// The numbers are the ones GlyphEngineTest, GlyphChartTest and
// GlyphScrollModeTest assert against the Kotlin twin, so the two stay in
// agreement: a rule changed on one side and not the other fails here or there.
//
//   cmake -S app/src/main/cpp/glyph/tests -B build/glyph-tests
//   cmake --build build/glyph-tests && ctest --test-dir build/glyph-tests
// or directly:
//   c++ -std=c++17 -I.. glyph_engine_test.cpp ../glyph_engine.cpp ../glyph_timing.cpp

#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>

#include "glyph_engine.h"
#include "glyph_timing.h"

using namespace tryptify::glyph;

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

#define CHECK_EQ(a, b)                                                          \
    do {                                                                        \
        ++g_checks;                                                             \
        const auto va = (a);                                                    \
        const auto vb = (b);                                                    \
        if (!(va == vb)) {                                                      \
            ++g_failures;                                                       \
            std::printf("FAIL %s:%d  %s == %s  (%g vs %g)\n", __FILE__, __LINE__, \
                        #a, #b, (double)va, (double)vb);                        \
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

constexpr int L = 0;  // GlyphLane.LEFT
constexpr float kNoEnd = -1.0f;

Note note(float t, int type = kTap, float end = kNoEnd, int lane = L) {
    return Note{t, end < 0.0f ? t : end, lane, type};
}

Timing constant(float bpm, float offset = 0.0f) {
    return Timing(offset, {{0.0f, bpm}}, {});
}

Engine engine(std::vector<Note> notes, Config config = {}) {
    return Engine(std::move(notes), constant(120.0f), Windows{}, config);
}

// ── timing ───────────────────────────────────────────────────────────────

void constantTempoRoundTrips() {
    const Timing t = constant(120.0f);
    CHECK_NEAR(t.beatToSeconds(0.0f), 0.0f, 1e-5);
    CHECK_NEAR(t.beatToSeconds(1.0f), 0.5f, 1e-5);
    CHECK_NEAR(t.beatToSeconds(4.0f), 2.0f, 1e-5);
    CHECK_NEAR(t.secondsToBeat(2.0f), 4.0f, 1e-4);
    for (int beat = 0; beat <= 2000; beat += 7) {
        const float seconds = t.beatToSeconds(static_cast<float>(beat));
        CHECK_NEAR(t.secondsToBeat(seconds), beat, 1e-2);
    }
}

void offsetPlacesBeatZero() {
    const Timing t(0.2f, {{0.0f, 120.0f}}, {});
    CHECK_NEAR(t.beatToSeconds(0.0f), 0.2f, 1e-5);
    CHECK_NEAR(t.beatToSeconds(1.0f), 0.7f, 1e-5);
}

void tempoChange() {
    const Timing t(0.0f, {{0.0f, 120.0f}, {8.0f, 240.0f}}, {});
    CHECK_NEAR(t.beatToSeconds(8.0f), 4.0f, 1e-4);
    CHECK_NEAR(t.beatToSeconds(16.0f), 6.0f, 1e-4);
    CHECK_NEAR(t.bpmAt(7.99f), 120.0f, 1e-3);
    CHECK_NEAR(t.bpmAt(8.0f), 240.0f, 1e-3);
    CHECK_NEAR(t.secondsToBeat(6.0f), 16.0f, 1e-2);
    CHECK_NEAR(t.minBpm(), 120.0f, 0.0);
    CHECK_NEAR(t.maxBpm(), 240.0f, 0.0);
}

void segmentsArriveInAnyOrder() {
    const Timing sorted(0.0f, {{0.0f, 120.0f}, {8.0f, 240.0f}}, {});
    const Timing shuffled(0.0f, {{8.0f, 240.0f}, {0.0f, 120.0f}}, {});
    CHECK_EQ(sorted.beatToSeconds(12.0f), shuffled.beatToSeconds(12.0f));
}

void stopFreezesTheBeat() {
    const Timing t(0.0f, {{0.0f, 120.0f}}, {{4.0f, 1.0f}});
    CHECK_NEAR(t.beatToSeconds(4.0f), 2.0f, 1e-4);
    CHECK_NEAR(t.beatToSeconds(5.0f), 3.5f, 1e-4);
    CHECK_NEAR(t.secondsToBeat(2.5f), 4.0f, 1e-2);
    CHECK_NEAR(t.secondsToBeat(2.99f), 4.0f, 1e-2);
    CHECK_NEAR(t.secondsToBeat(3.5f), 5.0f, 1e-3);
}

void noUsableTempoFallsBackTo120() {
    const Timing empty(0.0f, {}, {});
    CHECK_NEAR(empty.startBpm(), 120.0f, 0.0);
    const Timing negative(0.0f, {{0.0f, -5.0f}, {4.0f, 0.0f}}, {{2.0f, -1.0f}});
    CHECK_NEAR(negative.startBpm(), 120.0f, 0.0);
    CHECK_NEAR(negative.beatToSeconds(4.0f), 2.0f, 1e-5);
}

// ── judgement and score ──────────────────────────────────────────────────

void windowEdgesAreInclusive() {
    const Windows w;
    CHECK_EQ(w.judge(0.0225f), kMarvelous);
    CHECK_EQ(w.judge(0.02251f), kPerfect);
    CHECK_EQ(w.judge(0.045f), kPerfect);
    CHECK_EQ(w.judge(0.0451f), kGreat);
    CHECK_EQ(w.judge(0.090f), kGreat);
    CHECK_EQ(w.judge(0.0901f), kGood);
    CHECK_EQ(w.judge(0.135f), kGood);
    CHECK_EQ(w.judge(0.1351f), kBoo);
    CHECK_EQ(w.judge(0.180f), kBoo);
    CHECK_EQ(w.judge(0.1801f), kNoJudgement);
    CHECK_EQ(w.judge(-0.03f), w.judge(0.03f));
}

void pressJudgesAndCombos() {
    Engine e = engine({note(1.0f)});
    e.advanceTo(0.9f);
    Event ev{};
    CHECK(e.press(L, 1.01f, &ev));
    CHECK_EQ(ev.judgement, kMarvelous);
    CHECK_NEAR(ev.offsetSeconds, 0.01f, 1e-5);
    CHECK_EQ(e.combo(), 1);
    CHECK_EQ(ev.combo, 1);
}

void missIsRecordedOnceAtTheEdgeOfTheWindow() {
    Engine e = engine({note(1.0f)});
    e.advanceTo(1.17f);
    CHECK_EQ(e.score().judged, 0);
    e.advanceTo(1.19f);
    CHECK_EQ(e.score().judged, 1);
    CHECK_EQ(e.score().counts[kMiss], 1);
    e.advanceTo(3.0f);
    CHECK_EQ(e.score().judged, 1);
    Event out[4];
    const int n = e.drainEvents(out, 4);
    CHECK_EQ(n, 1);
    CHECK(std::isnan(out[0].offsetSeconds));
}

void pressIntoEmptySpaceCostsNothing() {
    Engine e = engine({note(5.0f)});
    e.advanceTo(1.0f);
    CHECK(!e.press(3, 1.0f, nullptr));
    CHECK_EQ(e.score().judged, 0);
    CHECK_EQ(e.combo(), 0);
}

void nearestNoteTakesTheTap() {
    Engine e = engine({note(1.00f), note(1.12f)});
    e.advanceTo(1.05f);
    Event ev{};
    CHECK(e.press(L, 1.10f, &ev));
    CHECK_EQ(ev.judgement, kMarvelous);
    CHECK_EQ(ev.noteIndex, 1);
}

void holdTailTakesTheHeadJudgement() {
    Engine e = engine({note(1.0f, kHold, 2.0f)});
    e.advanceTo(0.9f);
    Event ev{};
    CHECK(e.press(L, 1.04f, &ev));
    CHECK_EQ(ev.judgement, kPerfect);
    e.advanceTo(1.5f);
    CHECK_EQ(e.score().judged, 1);
    e.advanceTo(2.01f);
    CHECK_EQ(e.score().judged, 2);
    CHECK_EQ(e.score().counts[kPerfect], 2);
    CHECK_EQ(e.score().holdsDropped, 0);
}

void releasedHoldDropsAfterGrace() {
    Engine e = engine({note(1.0f, kHold, 3.0f)});
    e.advanceTo(0.9f);
    e.press(L, 1.0f, nullptr);
    e.release(L, 1.2f, nullptr);
    // StepMania's hold window: a quarter of a second to come back.
    e.advanceTo(1.4f);
    CHECK_EQ(e.score().judged, 1);
    e.advanceTo(1.5f);
    CHECK_EQ(e.score().judged, 2);
    CHECK_EQ(e.score().holdsDropped, 1);
    CHECK_EQ(e.score().counts[kMiss], 1);
}

void missedHoldHeadTakesItsTail() {
    Engine e = engine({note(1.0f, kHold, 2.0f)});
    e.advanceTo(1.5f);
    CHECK_EQ(e.score().judged, 2);
    CHECK_EQ(e.score().counts[kMiss], 2);
}

void rollDropsWhenTapsStop() {
    Engine e = engine({note(1.0f, kRoll, 3.0f)});
    e.advanceTo(0.9f);
    e.press(L, 1.0f, nullptr);
    e.press(L, 1.2f, nullptr);  // refreshes the roll, judges nothing new
    CHECK_EQ(e.score().judged, 1);
    // StepMania's roll window: half a second between taps.
    e.advanceTo(1.65f);
    CHECK_EQ(e.score().judged, 1);
    e.advanceTo(1.71f);
    CHECK_EQ(e.score().holdsDropped, 1);
}

void mineBreaksComboWithoutAJudgement() {
    Engine e = engine({note(1.0f), note(2.0f, kMine)});
    e.advanceTo(0.9f);
    e.press(L, 1.0f, nullptr);
    e.release(L, 1.05f, nullptr);
    CHECK_EQ(e.combo(), 1);
    e.advanceTo(1.95f);
    Event ev{};
    CHECK(e.press(L, 2.0f, &ev));
    CHECK(ev.isMine);
    CHECK_EQ(e.combo(), 0);
    CHECK_EQ(e.score().minesHit, 1);
    CHECK_EQ(e.score().judged, 1);
}

void judgementCountSkipsMinesAndFakes() {
    Engine e = engine({note(1.0f), note(2.0f, kMine), note(3.0f, kFake), note(4.0f, kHold, 5.0f)});
    CHECK_EQ(e.score().totalJudgements, 3);
}

void liftIsJudgedOnRelease() {
    Engine e = engine({note(1.0f, kLift)});
    e.advanceTo(0.8f);
    CHECK(!e.press(L, 0.85f, nullptr));
    CHECK_EQ(e.score().judged, 0);
    Event ev{};
    CHECK(e.release(L, 1.0f, &ev));
    CHECK_EQ(ev.judgement, kMarvelous);
}

void calibrationShiftsEveryPress() {
    Config c;
    c.audioOffsetSeconds = -0.04f;
    Engine e = engine({note(1.0f)}, c);
    e.advanceTo(0.9f);
    Event ev{};
    CHECK(e.press(L, 1.04f, &ev));
    CHECK_EQ(ev.judgement, kMarvelous);
    CHECK_NEAR(ev.offsetSeconds, 0.0f, 1e-5);
}

void scoreAccuracyAndGradeNumbers() {
    // The derivations GlyphScore.kt makes, pinned on hand-worked runs.
    Engine perfect = engine({note(1.0f), note(2.0f)});
    perfect.advanceTo(0.9f);
    perfect.press(L, 1.0f, nullptr);
    perfect.advanceTo(1.9f);
    perfect.press(L, 2.0f, nullptr);
    ScoreState s = perfect.score();
    CHECK_NEAR(s.finalAccuracy, 1.0f, 1e-5);
    CHECK_EQ(s.score, Engine::kMaxScore);

    // A Great and a Good: 0.70 + 0.40 over two judgements is 0.55.
    Engine mixed = engine({note(1.0f), note(2.0f)});
    mixed.advanceTo(0.9f);
    mixed.press(L, 1.08f, nullptr);  // Great
    mixed.advanceTo(1.9f);
    mixed.press(L, 2.12f, nullptr);  // Good
    s = mixed.score();
    CHECK_NEAR(s.finalAccuracy, 0.55f, 1e-5);
    CHECK_EQ(s.score, 550000);
    CHECK_EQ(s.late, 2);
    CHECK_EQ(s.early, 0);
    CHECK_NEAR(s.meanOffsetSeconds, 0.10f, 1e-5);
    CHECK(s.offsetDeviationSeconds > 0.01f);

    // Live accuracy is over what was judged, final over the chart.
    Engine partial = engine({note(1.0f), note(2.0f), note(3.0f), note(4.0f)});
    partial.advanceTo(0.9f);
    partial.press(L, 1.0f, nullptr);
    s = partial.score();
    CHECK_NEAR(s.accuracy, 1.0f, 1e-5);
    CHECK_NEAR(s.finalAccuracy, 0.25f, 1e-5);
}

void earlyLateDeadZone() {
    Engine e = engine({note(1.0f), note(2.0f)});
    e.advanceTo(0.9f);
    e.press(L, 1.0005f, nullptr);  // inside the dead zone
    e.advanceTo(1.9f);
    e.press(L, 1.97f, nullptr);  // 30 ms early
    const ScoreState s = e.score();
    CHECK_EQ(s.early, 1);
    CHECK_EQ(s.late, 0);
}

void resetPlaysTheChartAgain() {
    Engine e = engine({note(1.0f), note(2.0f)});
    e.advanceTo(0.9f);
    e.press(L, 1.0f, nullptr);
    e.advanceTo(2.5f);
    CHECK_EQ(e.score().judged, 2);
    CHECK(e.isFinished());
    e.reset();
    CHECK_EQ(e.score().judged, 0);
    CHECK_EQ(e.combo(), 0);
    CHECK_EQ(e.score().maxCombo, 0);
    CHECK(!e.isFinished());
    e.advanceTo(0.9f);
    Event ev{};
    CHECK(e.press(L, 1.0f, &ev));
    CHECK_EQ(ev.judgement, kMarvelous);
}

void resetReleasesHeldLanes() {
    Engine e = engine({note(1.0f, kHold, 5.0f)});
    e.advanceTo(0.9f);
    e.press(L, 1.0f, nullptr);
    CHECK(e.isHeld(L));
    CHECK_EQ(e.heldMask(), 1);
    e.reset();
    CHECK(!e.isHeld(L));
    e.advanceTo(0.9f);
    CHECK(e.press(L, 1.0f, nullptr));
}

// The training-loop bug: a pass starting at 10 s used to time out every note
// before 10 s as a miss on its first advance.
void loopWindowSetsAsideNotesOutsideIt() {
    Engine e = engine({note(1.0f), note(2.0f), note(10.5f), note(11.0f), note(30.0f, kHold, 31.0f)});
    e.reset(10.0f, 12.0f);
    CHECK_EQ(e.score().totalJudgements, 2);
    e.advanceTo(10.0f);
    CHECK_EQ(e.score().judged, 0);
    Event drained[8];
    CHECK_EQ(e.drainEvents(drained, 8), 0);
    e.advanceTo(10.45f);
    e.press(L, 10.5f, nullptr);
    e.advanceTo(10.95f);
    e.press(L, 11.0f, nullptr);
    const ScoreState s = e.score();
    CHECK_EQ(s.judged, 2);
    CHECK_EQ(s.counts[kMiss], 0);
    CHECK_NEAR(s.finalAccuracy, 1.0f, 1e-6);
    CHECK_EQ(s.score, Engine::kMaxScore);
    // Notes outside the loop are never drawn either.
    VisibleNote v[8];
    CHECK_EQ(e.fillVisible(25.0f, 35.0f, 30.0f, Scroll{}, v, 8), 0);
}

void eventsDrainInOrderAndInPieces() {
    Engine e = engine({note(1.0f), note(1.5f), note(2.0f)});
    e.advanceTo(3.0f);
    Event out[2];
    CHECK_EQ(e.drainEvents(out, 2), 2);
    CHECK_EQ(out[0].noteIndex, 0);
    CHECK_EQ(out[1].noteIndex, 1);
    CHECK_EQ(e.drainEvents(out, 2), 1);
    CHECK_EQ(out[0].noteIndex, 2);
    CHECK_EQ(e.drainEvents(out, 2), 0);
}

// ── scroll and the visible query ─────────────────────────────────────────

void scrollModesMatchTheKotlinNumbers() {
    const Engine steady(std::vector<Note>{}, constant(120.0f), Windows{}, Config{});
    Scroll x2;
    x2.kind = kXMod;
    x2.multiplier = 2.0f;
    CHECK_NEAR(steady.scrollUnits(x2, 0.0f, 1.0f), 4.0f, 1e-3);
    CHECK_NEAR(steady.scrollUnits(x2, 0.0f, 2.0f), 8.0f, 1e-3);

    const Engine stop(std::vector<Note>{}, Timing(0.0f, {{0.0f, 120.0f}}, {{4.0f, 1.0f}}), Windows{}, Config{});
    Scroll x1;
    x1.kind = kXMod;
    x1.multiplier = 1.0f;
    CHECK_NEAR(stop.scrollUnits(x1, 2.1f, 2.9f), 0.0f, 1e-2);
    CHECK(stop.scrollUnits(x1, 3.0f, 3.5f) > 0.9f);

    Scroll c400;
    c400.kind = kCMod;
    c400.targetBpm = 400.0f;
    CHECK_NEAR(steady.scrollUnits(c400, 0.0f, 1.0f), 400.0f / 60.0f, 1e-3);
    CHECK_NEAR(stop.scrollUnits(c400, 2.1f, 3.1f), 400.0f / 60.0f, 1e-3);
    Scroll c0 = c400;
    c0.targetBpm = 0.0f;
    CHECK_NEAR(steady.scrollUnits(c0, 0.0f, 5.0f), 0.0f, 1e-4);

    Scroll m;
    m.kind = kMMod;
    m.targetBpm = 480.0f;
    m.chartMaxBpm = 240.0f;
    CHECK_NEAR(steady.scrollUnits(m, 0.0f, 1.0f), 4.0f, 1e-3);  // 2 beats × 2
    m.chartMaxBpm = 0.0f;
    CHECK_NEAR(steady.scrollUnits(m, 0.0f, 1.0f), 2.0f, 1e-3);  // multiplier falls back to 1
}

void visibleQueryPlacesNotesLikeScrollUnits() {
    const Timing doubling(0.0f, {{0.0f, 120.0f}, {8.0f, 240.0f}}, {});
    std::vector<Note> notes{note(3.0f), note(4.5f, kHold, 6.0f, 2), note(9.0f)};
    Engine e(notes, doubling, Windows{}, Config{});
    Scroll x;
    x.kind = kXMod;
    x.multiplier = 1.5f;
    VisibleNote v[4];
    const int n = e.fillVisible(2.0f, 7.0f, 2.5f, x, v, 4);
    CHECK_EQ(n, 2);
    CHECK_EQ(v[0].noteIndex, 0);
    CHECK_EQ(v[1].noteIndex, 1);
    // Exactly what the per-note calls give, frame value for frame value.
    CHECK_EQ(v[0].headUnits, e.scrollUnits(x, 2.5f, 3.0f));
    CHECK_EQ(v[1].headUnits, e.scrollUnits(x, 2.5f, 4.5f));
    CHECK_EQ(v[1].tailUnits, e.scrollUnits(x, 2.5f, 6.0f));
    CHECK_EQ(v[1].bodyHeadUnits, v[1].headUnits);
    CHECK(!v[1].isHeld);

    // A short buffer still reports the true count, so the caller can grow.
    CHECK_EQ(e.fillVisible(2.0f, 7.0f, 2.5f, x, v, 1), 2);
}

void heldBodyStopsAtTheReceptor() {
    Engine e = engine({note(1.0f, kHold, 3.0f)});
    e.advanceTo(0.95f);
    e.press(L, 0.98f, nullptr);  // pressed a hair early: position before the head
    Scroll c;
    VisibleNote v[2];
    CHECK_EQ(e.fillVisible(0.5f, 4.0f, 0.98f, c, v, 2), 1);
    CHECK(v[0].isHeld);
    CHECK(v[0].isHeadJudged);
    CHECK_NEAR(v[0].bodyHeadUnits, 0.0f, 1e-6);
    CHECK(v[0].headUnits > 0.0f);
}

void judgedTapsLeaveTheField() {
    Engine e = engine({note(1.0f), note(1.5f)});
    e.advanceTo(0.95f);
    e.press(L, 1.0f, nullptr);
    e.advanceTo(1.0f);
    VisibleNote v[4];
    CHECK_EQ(e.fillVisible(0.5f, 2.0f, 1.0f, Scroll{}, v, 4), 1);
    CHECK_EQ(v[0].noteIndex, 1);
}

// A long chart is not slower per frame than a short one once its past is
// settled: the query starts at the cursor.
void longChartQueryStartsAtTheCursor() {
    std::vector<Note> notes;
    for (int i = 0; i < 5000; ++i) notes.push_back(note(1.0f + i * 0.1f, kTap, kNoEnd, i % 4));
    Engine e(notes, constant(150.0f), Windows{}, Config{});
    e.advanceTo(400.0f);  // most of the chart has timed out
    VisibleNote v[64];
    const int n = e.fillVisible(399.5f, 401.0f, 400.0f, Scroll{}, v, 64);
    CHECK(n > 0 && n <= 64);
    CHECK(v[0].noteIndex > 3900);
}

}  // namespace

int main() {
    constantTempoRoundTrips();
    offsetPlacesBeatZero();
    tempoChange();
    segmentsArriveInAnyOrder();
    stopFreezesTheBeat();
    noUsableTempoFallsBackTo120();
    windowEdgesAreInclusive();
    pressJudgesAndCombos();
    missIsRecordedOnceAtTheEdgeOfTheWindow();
    pressIntoEmptySpaceCostsNothing();
    nearestNoteTakesTheTap();
    holdTailTakesTheHeadJudgement();
    releasedHoldDropsAfterGrace();
    missedHoldHeadTakesItsTail();
    rollDropsWhenTapsStop();
    mineBreaksComboWithoutAJudgement();
    judgementCountSkipsMinesAndFakes();
    liftIsJudgedOnRelease();
    calibrationShiftsEveryPress();
    scoreAccuracyAndGradeNumbers();
    earlyLateDeadZone();
    resetPlaysTheChartAgain();
    resetReleasesHeldLanes();
    loopWindowSetsAsideNotesOutsideIt();
    eventsDrainInOrderAndInPieces();
    scrollModesMatchTheKotlinNumbers();
    visibleQueryPlacesNotesLikeScrollUnits();
    heldBodyStopsAtTheReceptor();
    judgedTapsLeaveTheField();
    longChartQueryStartsAtTheCursor();

    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
