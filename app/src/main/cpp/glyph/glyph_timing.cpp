// SPDX-License-Identifier: GPL-3.0-or-later

#include "glyph_timing.h"

#include <algorithm>

namespace tryptify::glyph {

Timing::Timing(float offsetSeconds, std::vector<BpmSegment> segments, std::vector<Stop> stops)
    : offset_(offsetSeconds) {
    for (const auto& s : segments) {
        if (s.bpm > 0.0f) segments_.push_back(s);
    }
    // Stable, like Kotlin's sortedBy: two segments on one beat keep file order.
    std::stable_sort(segments_.begin(), segments_.end(),
                     [](const BpmSegment& a, const BpmSegment& b) { return a.beat < b.beat; });
    if (segments_.empty()) segments_.push_back({0.0f, kDefaultBpm});

    for (const auto& s : stops) {
        if (s.seconds > 0.0f) stops_.push_back(s);
    }
    std::stable_sort(stops_.begin(), stops_.end(),
                     [](const Stop& a, const Stop& b) { return a.beat < b.beat; });

    segmentStartSeconds_.resize(segments_.size());
    float seconds = offset_;
    for (std::size_t i = 0; i < segments_.size(); ++i) {
        if (i > 0) {
            const auto& previous = segments_[i - 1];
            const float beats = segments_[i].beat - previous.beat;
            seconds += beats * 60.0f / previous.bpm;
        }
        segmentStartSeconds_[i] = seconds;
    }

    stopPrefix_.resize(stops_.size() + 1);
    stopPrefix_[0] = 0.0f;
    float total = 0.0f;
    for (std::size_t i = 0; i < stops_.size(); ++i) {
        total += stops_[i].seconds;
        stopPrefix_[i + 1] = total;
    }

    segmentBoundarySeconds_.resize(segments_.size());
    for (std::size_t i = 0; i < segments_.size(); ++i) {
        segmentBoundarySeconds_[i] = beatToSeconds(segments_[i].beat);
    }

    minBpm_ = maxBpm_ = segments_.front().bpm;
    for (const auto& s : segments_) {
        minBpm_ = std::min(minBpm_, s.bpm);
        maxBpm_ = std::max(maxBpm_, s.bpm);
    }
}

std::size_t Timing::segmentIndexFor(float beat) const {
    // The last segment starting at or before the beat; the first if none does.
    std::size_t low = 0;
    std::size_t high = segments_.size();
    while (low < high) {
        const std::size_t middle = (low + high) / 2;
        if (segments_[middle].beat <= beat) low = middle + 1;
        else high = middle;
    }
    return low == 0 ? 0 : low - 1;
}

float Timing::stoppedSecondsBefore(float beat) const {
    if (stops_.empty()) return 0.0f;
    // Stops strictly before the beat. stopPrefix_ was summed in this order, so
    // the total is the one the Kotlin walk would add up.
    const auto it = std::lower_bound(stops_.begin(), stops_.end(), beat,
                                     [](const Stop& s, float b) { return s.beat < b; });
    return stopPrefix_[static_cast<std::size_t>(it - stops_.begin())];
}

float Timing::bpmAt(float beat) const {
    return segments_[segmentIndexFor(beat)].bpm;
}

float Timing::beatToSeconds(float beat) const {
    const std::size_t index = segmentIndexFor(beat);
    const auto& segment = segments_[index];
    const float base = segmentStartSeconds_[index] + (beat - segment.beat) * 60.0f / segment.bpm;
    return base + stoppedSecondsBefore(beat);
}

float Timing::secondsToBeat(float seconds) const {
    // The last segment whose start has been reached. Segment 0 is used before
    // the first boundary as well, as in the Kotlin walk.
    std::size_t index = 0;
    {
        std::size_t low = 1;
        std::size_t high = segments_.size();
        while (low < high) {
            const std::size_t middle = (low + high) / 2;
            if (segmentBoundarySeconds_[middle] <= seconds) low = middle + 1;
            else high = middle;
        }
        index = low - 1;
    }
    const auto& segment = segments_[index];
    const float elapsed = seconds - segmentStartSeconds_[index] - stoppedSecondsBefore(segment.beat);
    const float raw = segment.beat + elapsed * segment.bpm / 60.0f;

    if (stops_.empty()) return raw;
    float beat = segment.beat;
    float remaining = elapsed;
    // Stops before this segment were already taken out of `elapsed`.
    auto it = std::lower_bound(stops_.begin(), stops_.end(), segment.beat,
                               [](const Stop& s, float b) { return s.beat < b; });
    for (; it != stops_.end(); ++it) {
        const float toStop = (it->beat - beat) * 60.0f / segment.bpm;
        if (remaining < toStop) break;
        remaining -= toStop;
        beat = it->beat;
        if (remaining < it->seconds) return beat;
        remaining -= it->seconds;
    }
    return beat + remaining * segment.bpm / 60.0f;
}

}  // namespace tryptify::glyph
