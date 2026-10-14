// SPDX-License-Identifier: GPL-3.0-or-later

#include "glyph_engine.h"

#include <cmath>
#include <utility>

namespace tryptify::glyph {

float judgementWeight(int32_t judgement) {
    switch (judgement) {
        case kMarvelous: return 1.00f;
        case kPerfect: return 0.95f;
        case kGreat: return 0.70f;
        case kGood: return 0.40f;
        case kBoo: return 0.10f;
        default: return 0.00f;
    }
}

int32_t Windows::judge(float offsetSeconds) const {
    const float distance = std::fabs(offsetSeconds);
    if (distance <= marvelous) return kMarvelous;
    if (distance <= perfect) return kPerfect;
    if (distance <= great) return kGreat;
    if (distance <= good) return kGood;
    if (distance <= boo) return kBoo;
    return kNoJudgement;
}

Engine::Engine(std::vector<Note> notes, Timing timing, Windows windows, Config config)
    : notes_(std::move(notes)), timing_(std::move(timing)), windows_(windows), config_(config) {
    runtime_.resize(notes_.size());
    headBeat_.resize(notes_.size());
    tailBeat_.resize(notes_.size());
    int32_t total = 0;
    for (std::size_t i = 0; i < notes_.size(); ++i) {
        headBeat_[i] = timing_.secondsToBeat(notes_[i].timeSeconds);
        tailBeat_[i] = timing_.secondsToBeat(notes_[i].endTimeSeconds);
        if (isScorable(notes_[i].type)) total += hasTail(notes_[i].type) ? 2 : 1;
    }
    fullTotalJudgements_ = total;
    totalJudgements_ = total;
    // Every scorable note yields at most a head and a tail, a mine at most one
    // hit. Sized once, so play never allocates.
    events_.resize(notes_.size() * 2 + 8);
}

bool Engine::resolved(std::size_t i) const {
    const auto& r = runtime_[i];
    return r.judgement != kNoJudgement && (r.tailResolved || !hasTail(notes_[i].type));
}

void Engine::advanceTo(float songSeconds) {
    if (songSeconds < position_) {
        // A seek backwards is a loop or a restart; the caller resets.
        position_ = songSeconds;
        return;
    }
    position_ = songSeconds;

    for (std::size_t i = cursor_; i < notes_.size(); ++i) {
        // Nothing past here can have expired: the list is in time order.
        if (notes_[i].timeSeconds - windows_.miss() > songSeconds) break;
        resolveByTime(i, songSeconds);
    }
    // Over the settled prefix only. A hold mid-window keeps what is behind it
    // in view, which is right: its tail has not happened yet.
    while (cursor_ < notes_.size() && resolved(cursor_)) ++cursor_;
}

void Engine::resolveByTime(std::size_t i, float songSeconds) {
    const Note& note = notes_[i];
    Runtime& r = runtime_[i];
    const float nan = std::numeric_limits<float>::quiet_NaN();

    if (r.judgement == kNoJudgement) {
        if (!isScorable(note.type)) {
            // Mines and fakes are never judged; a mine just stops being
            // dangerous once its window has passed.
            if (songSeconds > note.timeSeconds + windows_.miss()) {
                r.judgement = kMiss;
                r.tailResolved = true;
            }
        } else if (songSeconds > note.timeSeconds + windows_.miss()) {
            r.judgement = kMiss;
            record(kMiss, nan);
            emit(i, kMiss, nan, false, false, nullptr);
            if (hasTail(note.type)) {
                // An unhit head takes its tail with it.
                r.tailResolved = true;
                record(kMiss, nan);
            }
        }
        return;
    }

    if (!hasTail(note.type) || r.tailResolved) return;

    const int32_t lane = note.lane;
    bool dropped;
    if (note.type == kRoll) {
        dropped = songSeconds - r.lastRollTapSeconds > config_.rollIntervalSeconds;
    } else {
        const float releasedAt = r.releasedAtSeconds;
        dropped = !r.holding && !std::isnan(releasedAt) &&
                  songSeconds - releasedAt > config_.holdGraceSeconds;
    }

    if (dropped) {
        r.tailResolved = true;
        r.holding = false;
        if (activeHold_[lane] == static_cast<int32_t>(i)) activeHold_[lane] = -1;
        record(kMiss, nan);
        ++holdsDropped_;
        emit(i, kMiss, nan, true, false, nullptr);
        return;
    }

    if (songSeconds >= note.endTimeSeconds) {
        r.tailResolved = true;
        r.holding = false;
        if (activeHold_[lane] == static_cast<int32_t>(i)) activeHold_[lane] = -1;
        // A held tail is worth the head's judgement, not a flat Marvelous.
        const int32_t tail = r.judgement;
        record(tail, nan);
        emit(i, tail, nan, true, false, nullptr);
    }
}

bool Engine::press(int32_t lane, float songSeconds, Event* out) {
    if (lane < 0 || lane >= kLaneCount) return false;
    const float at = songSeconds + config_.audioOffsetSeconds;
    laneHeld_[lane] = true;

    // A roll being held takes the tap first, or re-tapping it would start
    // judging the note after it.
    const int32_t active = activeHold_[lane];
    if (active >= 0) {
        Runtime& held = runtime_[static_cast<std::size_t>(active)];
        if (notes_[static_cast<std::size_t>(active)].type == kRoll && !held.tailResolved) {
            held.lastRollTapSeconds = at;
            return false;
        }
    }

    const int32_t candidate = nearestUnjudged(lane, at, false);
    if (candidate < 0) return false;
    const auto i = static_cast<std::size_t>(candidate);
    const Note& note = notes_[i];
    Runtime& r = runtime_[i];
    const float offset = at - note.timeSeconds;

    if (note.type == kMine) {
        r.judgement = kMiss;
        r.tailResolved = true;
        ++minesHit_;
        combo_ = 0;
        emit(i, kMiss, offset, false, true, out);
        return true;
    }
    // A lift is scored on release, so pressing through one does nothing.
    if (note.type == kLift || note.type == kFake) return false;

    const int32_t judgement = windows_.judge(offset);
    if (judgement == kNoJudgement) return false;
    r.judgement = judgement;

    if (hasTail(note.type)) {
        r.holding = true;
        r.releasedAtSeconds = std::numeric_limits<float>::quiet_NaN();
        r.lastRollTapSeconds = at;
        activeHold_[lane] = candidate;
    } else {
        r.tailResolved = true;
    }

    record(judgement, offset);
    emit(i, judgement, offset, false, false, out);
    return true;
}

bool Engine::release(int32_t lane, float songSeconds, Event* out) {
    if (lane < 0 || lane >= kLaneCount) return false;
    const float at = songSeconds + config_.audioOffsetSeconds;
    laneHeld_[lane] = false;
    const int32_t active = activeHold_[lane];
    if (active >= 0) {
        Runtime& held = runtime_[static_cast<std::size_t>(active)];
        held.holding = false;
        // Only the first release starts the clock; a jittery digitizer's
        // stream of releases must not keep resetting it.
        if (std::isnan(held.releasedAtSeconds)) held.releasedAtSeconds = at;
    }

    const int32_t candidate = nearestUnjudged(lane, at, true);
    if (candidate < 0) return false;
    const auto i = static_cast<std::size_t>(candidate);
    const float offset = at - notes_[i].timeSeconds;
    const int32_t judgement = windows_.judge(offset);
    if (judgement == kNoJudgement) return false;

    runtime_[i].judgement = judgement;
    runtime_[i].tailResolved = true;
    record(judgement, offset);
    emit(i, judgement, offset, false, false, out);
    return true;
}

int32_t Engine::nearestUnjudged(int32_t lane, float at, bool liftsOnly) const {
    // Nearest rather than earliest: in a jump-heavy chart the note you meant is
    // the one under your finger.
    int32_t best = -1;
    float bestDistance = std::numeric_limits<float>::max();
    for (std::size_t i = cursor_; i < notes_.size(); ++i) {
        const Note& note = notes_[i];
        if (note.timeSeconds - at > windows_.miss()) break;
        if (note.lane != lane || runtime_[i].judgement != kNoJudgement) continue;
        if (liftsOnly && note.type != kLift) continue;
        if (!liftsOnly && note.type == kLift) continue;
        if (note.type == kFake) continue;
        const float distance = std::fabs(at - note.timeSeconds);
        if (distance <= windows_.miss() && distance < bestDistance) {
            bestDistance = distance;
            best = static_cast<int32_t>(i);
        }
    }
    return best;
}

void Engine::emit(std::size_t i, int32_t judgement, float offset, bool isTail, bool isMine, Event* out) {
    const Event event{notes_[i].lane, judgement, static_cast<int32_t>(i), combo_, isTail, isMine, offset};
    if (out != nullptr) *out = event;
    const std::size_t capacity = events_.size();
    if (eventCount_ == capacity) {
        // Cannot happen while the queue is drained between ticks; if it ever
        // does, the oldest event goes rather than anything allocating.
        eventHead_ = (eventHead_ + 1) % capacity;
        --eventCount_;
    }
    events_[(eventHead_ + eventCount_) % capacity] = event;
    ++eventCount_;
}

int32_t Engine::drainEvents(Event* out, int32_t capacity) {
    int32_t moved = 0;
    while (eventCount_ > 0 && moved < capacity) {
        out[moved++] = events_[eventHead_];
        eventHead_ = (eventHead_ + 1) % events_.size();
        --eventCount_;
    }
    if (eventCount_ == 0) eventHead_ = 0;
    return moved;
}

void Engine::record(int32_t judgement, float offset) {
    counts_[judgement] += 1;
    weightSum_ += judgementWeight(judgement);
    if (continuesCombo(judgement)) {
        combo_ += 1;
        if (combo_ > maxCombo_) maxCombo_ = combo_;
    } else {
        combo_ = 0;
    }
    if (!std::isnan(offset) && isHit(judgement)) {
        offsetSum_ += static_cast<double>(offset);
        offsetSquareSum_ += static_cast<double>(offset) * static_cast<double>(offset);
        offsetSamples_ += 1;
        // Inside a millisecond of the line is neither early nor late.
        if (offset < -kDeadZoneSeconds) early_ += 1;
        else if (offset > kDeadZoneSeconds) late_ += 1;
    }
}

void Engine::clearScore() {
    for (auto& c : counts_) c = 0;
    weightSum_ = 0.0f;
    offsetSum_ = 0.0;
    offsetSquareSum_ = 0.0;
    offsetSamples_ = 0;
    combo_ = 0;
    maxCombo_ = 0;
    early_ = 0;
    late_ = 0;
    minesHit_ = 0;
    holdsDropped_ = 0;
}

void Engine::reset(float fromSeconds, float untilSeconds) {
    int32_t total = 0;
    for (std::size_t i = 0; i < notes_.size(); ++i) {
        Runtime& r = runtime_[i];
        r = Runtime{};
        const Note& note = notes_[i];
        if (note.timeSeconds < fromSeconds || note.timeSeconds >= untilSeconds) {
            // Outside the window: settled, unscored and never drawn, so the
            // first pass of a loop does not open with a run of misses for
            // every note before it.
            r.judgement = kMiss;
            r.tailResolved = true;
            r.excluded = true;
        } else if (isScorable(note.type)) {
            total += hasTail(note.type) ? 2 : 1;
        }
    }
    totalJudgements_ = total;
    cursor_ = 0;
    while (cursor_ < notes_.size() && resolved(cursor_)) ++cursor_;
    eventHead_ = 0;
    eventCount_ = 0;
    clearScore();
    for (int l = 0; l < kLaneCount; ++l) {
        laneHeld_[l] = false;
        activeHold_[l] = -1;
    }
    position_ = std::isfinite(fromSeconds) ? fromSeconds : 0.0f;
}

float Engine::scrollUnits(const Scroll& scroll, float fromSeconds, float toSeconds) const {
    switch (scroll.kind) {
        case kCMod:
            if (scroll.targetBpm <= 0.0f) return 0.0f;
            return (toSeconds - fromSeconds) * scroll.targetBpm / 60.0f;
        case kMMod: {
            const float m = scroll.chartMaxBpm <= 0.0f ? 1.0f : scroll.targetBpm / scroll.chartMaxBpm;
            return (timing_.secondsToBeat(toSeconds) - timing_.secondsToBeat(fromSeconds)) * m;
        }
        default:
            return (timing_.secondsToBeat(toSeconds) - timing_.secondsToBeat(fromSeconds)) *
                   scroll.multiplier;
    }
}

int32_t Engine::fillVisible(float fromSeconds, float toSeconds, float positionSeconds,
                            const Scroll& scroll, VisibleNote* out, int32_t capacity) const {
    const bool musical = scroll.kind != kCMod;
    float multiplier = scroll.multiplier;
    if (scroll.kind == kMMod) {
        multiplier = scroll.chartMaxBpm <= 0.0f ? 1.0f : scroll.targetBpm / scroll.chartMaxBpm;
    }
    // Worked out once per frame instead of once per note.
    const float positionBeat = musical ? timing_.secondsToBeat(positionSeconds) : 0.0f;
    const auto units = [&](float seconds, float beat) {
        if (musical) return (beat - positionBeat) * multiplier;
        if (scroll.targetBpm <= 0.0f) return 0.0f;
        return (seconds - positionSeconds) * scroll.targetBpm / 60.0f;
    };

    int32_t count = 0;
    // From the cursor: everything before it is resolved, and a resolved note
    // is never drawn, so starting at zero would only re-skip the whole past.
    for (std::size_t i = cursor_; i < notes_.size(); ++i) {
        const Note& note = notes_[i];
        if (note.endTimeSeconds < fromSeconds) continue;
        if (note.timeSeconds > toSeconds) break;
        const Runtime& r = runtime_[i];
        // A judged tap disappears; a hold stays until its tail is done.
        if (r.judgement != kNoJudgement && resolved(i)) continue;
        if (count < capacity) {
            VisibleNote& v = out[count];
            v.noteIndex = static_cast<int32_t>(i);
            v.isHeld = r.holding;
            v.isHeadJudged = r.judgement != kNoJudgement;
            v.headUnits = units(note.timeSeconds, headBeat_[i]);
            // A held body's top stops at the receptor while its tail travels.
            if (r.holding && positionSeconds < note.timeSeconds) {
                v.bodyHeadUnits = units(positionSeconds, positionBeat);
            } else {
                v.bodyHeadUnits = v.headUnits;
            }
            v.tailUnits = units(note.endTimeSeconds, tailBeat_[i]);
        }
        ++count;
    }
    return count;
}

ScoreState Engine::score() const {
    ScoreState s{};
    int32_t judged = 0;
    for (int j = 0; j < kJudgementCount; ++j) {
        s.counts[j] = counts_[j];
        judged += counts_[j];
    }
    s.combo = combo_;
    s.maxCombo = maxCombo_;
    s.early = early_;
    s.late = late_;
    s.minesHit = minesHit_;
    s.holdsDropped = holdsDropped_;
    s.judged = judged;
    s.totalJudgements = totalJudgements_;
    if (totalJudgements_ <= 0) {
        s.score = 0;
        s.finalAccuracy = 0.0f;
    } else {
        const float raw = weightSum_ / static_cast<float>(totalJudgements_) * static_cast<float>(kMaxScore);
        // Clamped as a float first: converting an out-of-range float is UB.
        const float clamped = raw < 0.0f ? 0.0f : (raw > static_cast<float>(kMaxScore) ? static_cast<float>(kMaxScore) : raw);
        s.score = static_cast<int32_t>(clamped);
        s.finalAccuracy = weightSum_ / static_cast<float>(totalJudgements_);
    }
    s.accuracy = judged <= 0 ? 0.0f : weightSum_ / static_cast<float>(judged);
    s.meanOffsetSeconds = offsetSamples_ == 0 ? 0.0f : static_cast<float>(offsetSum_ / offsetSamples_);
    if (offsetSamples_ < 2) {
        s.offsetDeviationSeconds = 0.0f;
    } else {
        const double mean = offsetSum_ / offsetSamples_;
        double variance = offsetSquareSum_ / offsetSamples_ - mean * mean;
        if (variance < 0.0) variance = 0.0;
        s.offsetDeviationSeconds = static_cast<float>(std::sqrt(variance));
    }
    return s;
}

int32_t Engine::heldMask() const {
    int32_t mask = 0;
    for (int l = 0; l < kLaneCount; ++l) {
        if (laneHeld_[l]) mask |= 1 << l;
    }
    return mask;
}

}  // namespace tryptify::glyph
