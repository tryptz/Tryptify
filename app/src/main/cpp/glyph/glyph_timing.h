// SPDX-License-Identifier: GPL-3.0-or-later
//
// The song's beat-to-second map, natively. Mirrors GlyphTiming.kt.
//
// Arithmetic is float, in the same order as the Kotlin class, so the two give
// the same answers and the JVM tests that pin the Kotlin side pin this one
// too. What differs is only how a value is found: segment boundaries and stop
// totals are precomputed, so every lookup is a binary search rather than a
// walk from beat zero. The precomputed values are produced by the very
// arithmetic the walk would have done, which is what keeps the results equal
// rather than merely close.
//
// No Android headers, so the host tests compile it as is.

#pragma once

#include <cstddef>
#include <vector>

namespace tryptify::glyph {

struct BpmSegment {
    float beat;
    float bpm;
};

struct Stop {
    float beat;
    float seconds;
};

class Timing {
public:
    static constexpr float kDefaultBpm = 120.0f;

    // Segments with a tempo of zero or less are dropped, and a song left with
    // none plays at kDefaultBpm. Stops of zero length or less are dropped.
    // Both are sorted by beat here, so callers may pass them in file order.
    Timing(float offsetSeconds, std::vector<BpmSegment> segments, std::vector<Stop> stops);

    float offsetSeconds() const { return offset_; }
    float startBpm() const { return segments_.front().bpm; }
    float minBpm() const { return minBpm_; }
    float maxBpm() const { return maxBpm_; }

    float bpmAt(float beat) const;
    float beatToSeconds(float beat) const;
    float secondsToBeat(float seconds) const;

private:
    std::size_t segmentIndexFor(float beat) const;
    float stoppedSecondsBefore(float beat) const;

    float offset_;
    std::vector<BpmSegment> segments_;
    std::vector<Stop> stops_;
    // Audio position of the top of each segment, stops excluded.
    std::vector<float> segmentStartSeconds_;
    // beatToSeconds(segment.beat) for each segment, stops included: where
    // secondsToBeat finds which segment a position falls in.
    std::vector<float> segmentBoundarySeconds_;
    // stopPrefix_[k] is the total length of the first k stops, summed in order.
    std::vector<float> stopPrefix_;
    float minBpm_;
    float maxBpm_;
};

}  // namespace tryptify::glyph
