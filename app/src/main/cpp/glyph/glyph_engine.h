// SPDX-License-Identifier: GPL-3.0-or-later
//
// The rules of Trypt Mania, natively. Mirrors GlyphGameplayEngine.kt, which
// stays as the JVM twin the unit tests drive and as the fallback when this
// library cannot be loaded.
//
// Time arrives from outside as a song position in seconds; the engine never
// advances on its own, so no frame can become the thing that decides when a
// note was due. advanceTo, press and release are the only ways state moves.
//
// Threading: single-threaded by contract. The Kotlin side calls every entry
// point from the main thread — the tick, the input and the playfield's draw
// all run there — so nothing here locks. Nothing allocates after construction
// either: the event queue is sized for the most judgements a chart can produce.
//
// Enum values are the Kotlin ordinals and must stay in step with them:
// GlyphLane, GlyphNoteType and GlyphJudgement.

#pragma once

#include <cstdint>
#include <limits>
#include <vector>

#include "glyph_timing.h"

namespace tryptify::glyph {

// Every column either course can have: dance uses the first four, pop'n all
// nine. Sized once for the most, so a chart of either plays in the same
// arrays; which are in use is the chart's business.
constexpr int kLaneCount = 9;

enum NoteType : int32_t { kTap = 0, kHold = 1, kRoll = 2, kMine = 3, kLift = 4, kFake = 5 };

enum Judgement : int32_t {
    kMarvelous = 0,
    kPerfect = 1,
    kGreat = 2,
    kGood = 3,
    kBoo = 4,
    kMiss = 5,
    kNoJudgement = -1,
};
constexpr int kJudgementCount = 6;

inline bool isScorable(int32_t type) { return type == kTap || type == kHold || type == kRoll || type == kLift; }
inline bool hasTail(int32_t type) { return type == kHold || type == kRoll; }
// StepMania's dance combo (ComboContinue/ComboMaintain): Great or better.
inline bool continuesCombo(int32_t j) { return j >= kMarvelous && j <= kGreat; }
inline bool isHit(int32_t j) { return j != kMiss; }
float judgementWeight(int32_t judgement);

struct Windows {
    float marvelous = 0.0225f;
    float perfect = 0.045f;
    float great = 0.090f;
    float good = 0.135f;
    float boo = 0.180f;
    float miss() const { return boo; }
    // Sign ignored: early and late score alike, direction is recorded apart.
    int32_t judge(float offsetSeconds) const;
};

struct Config {
    // StepMania's TimingWindowSecondsHold and TimingWindowSecondsRoll.
    float holdGraceSeconds = 0.25f;
    float rollIntervalSeconds = 0.50f;
    float audioOffsetSeconds = 0.0f;
};

struct Note {
    float timeSeconds;
    float endTimeSeconds;
    int32_t lane;
    int32_t type;
};

struct Event {
    int32_t lane;
    int32_t judgement;
    int32_t noteIndex;
    int32_t combo;
    bool isTail;
    bool isMine;
    // NaN when nothing was pressed: a timed-out note or a completed tail.
    float offsetSeconds;
};

// The readouts, derived the way GlyphScore.kt derives them.
struct ScoreState {
    int32_t counts[kJudgementCount];
    int32_t combo;
    int32_t maxCombo;
    int32_t early;
    int32_t late;
    int32_t minesHit;
    int32_t holdsDropped;
    int32_t judged;
    int32_t totalJudgements;
    int32_t score;
    float accuracy;
    float finalAccuracy;
    float meanOffsetSeconds;
    float offsetDeviationSeconds;
};

// How the playfield spaces notes. Mirrors GlyphScrollMode.kt.
enum ScrollKind : int32_t { kXMod = 0, kCMod = 1, kMMod = 2 };

struct Scroll {
    int32_t kind = kCMod;
    float multiplier = 1.0f;   // XMod
    float targetBpm = 400.0f;  // CMod, MMod
    float chartMaxBpm = 0.0f;  // MMod
};

// One visible note for the renderer, already placed.
struct VisibleNote {
    int32_t noteIndex;
    bool isHeld;
    bool isHeadJudged;
    float headUnits;      // scroll units from the position to the head
    float bodyHeadUnits;  // the same for a hold body's top, pinned while held
    float tailUnits;      // ...and to the tail
};

class Engine {
public:
    static constexpr int32_t kMaxScore = 1'000'000;
    static constexpr float kDeadZoneSeconds = 0.001f;

    // Notes must be in time order, as GlyphChart keeps them.
    Engine(std::vector<Note> notes, Timing timing, Windows windows, Config config);

    void advanceTo(float songSeconds);
    // Returns true and fills `out` when the press or release was judged.
    bool press(int32_t lane, float songSeconds, Event* out);
    bool release(int32_t lane, float songSeconds, Event* out);

    // Clear every judgement and play again from `fromSeconds`. Notes before it,
    // or at or after `untilSeconds`, are set aside unscored and leave the total:
    // a practice loop is scored on the notes inside it, not on the song.
    // With no window at all, nothing is set aside, as before loops existed.
    void reset(float fromSeconds = -std::numeric_limits<float>::infinity(),
               float untilSeconds = std::numeric_limits<float>::infinity());

    // Moves up to `capacity` pending events into `out`, oldest first, and
    // returns how many it moved. The rest stay for the next call.
    int32_t drainEvents(Event* out, int32_t capacity);

    // Writes up to `capacity` notes overlapping [from, to] into `out` and
    // returns how many overlap in total, so a caller whose buffer was short
    // can grow it and ask again.
    int32_t fillVisible(float fromSeconds, float toSeconds, float positionSeconds,
                        const Scroll& scroll, VisibleNote* out, int32_t capacity) const;

    float scrollUnits(const Scroll& scroll, float fromSeconds, float toSeconds) const;

    ScoreState score() const;
    int32_t combo() const { return combo_; }
    bool isHeld(int32_t lane) const { return lane >= 0 && lane < kLaneCount && laneHeld_[lane]; }
    int32_t heldMask() const;
    bool isFinished() const { return cursor_ >= notes_.size(); }
    float positionSeconds() const { return position_; }
    const Timing& timing() const { return timing_; }
    int32_t noteCount() const { return static_cast<int32_t>(notes_.size()); }

private:
    struct Runtime {
        int32_t judgement = kNoJudgement;
        bool tailResolved = false;
        bool holding = false;
        float lastRollTapSeconds = 0.0f;
        // When the lane came up, or NaN while it is still down. The grace runs
        // from here, so a long hold released a hair early gets the same
        // tolerance as a short one.
        float releasedAtSeconds = std::numeric_limits<float>::quiet_NaN();
        // Set aside by reset(): outside the practised window, never scored.
        bool excluded = false;
    };

    bool resolved(std::size_t i) const;
    void resolveByTime(std::size_t i, float songSeconds);
    int32_t nearestUnjudged(int32_t lane, float at, bool liftsOnly) const;
    void emit(std::size_t i, int32_t judgement, float offset, bool isTail, bool isMine, Event* out);
    void record(int32_t judgement, float offset);  // offset NaN = none
    void clearScore();

    std::vector<Note> notes_;
    // The beat of each note's head and tail, from secondsToBeat. Fixed for the
    // chart, so worked out once rather than twice per note per frame.
    std::vector<float> headBeat_;
    std::vector<float> tailBeat_;
    std::vector<Runtime> runtime_;
    Timing timing_;
    Windows windows_;
    Config config_;

    std::size_t cursor_ = 0;
    float position_ = 0.0f;
    bool laneHeld_[kLaneCount] = {};
    int32_t activeHold_[kLaneCount] = {-1, -1, -1, -1, -1, -1, -1, -1, -1};

    // Pending events: a ring sized for every judgement the chart can produce.
    std::vector<Event> events_;
    std::size_t eventHead_ = 0;
    std::size_t eventCount_ = 0;

    // Score.
    int32_t counts_[kJudgementCount] = {};
    int32_t totalJudgements_ = 0;
    int32_t fullTotalJudgements_ = 0;
    float weightSum_ = 0.0f;
    double offsetSum_ = 0.0;
    double offsetSquareSum_ = 0.0;
    int32_t offsetSamples_ = 0;
    int32_t combo_ = 0;
    int32_t maxCombo_ = 0;
    int32_t early_ = 0;
    int32_t late_ = 0;
    int32_t minesHit_ = 0;
    int32_t holdsDropped_ = 0;
};

}  // namespace tryptify::glyph
