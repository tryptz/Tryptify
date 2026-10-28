#pragma once
#include <vector>
#include <cmath>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Overlap-add crossfade buffer for reverser / pitch shifter.
class CrossfadeBuffer {
public:
    void prepare(int maxSegmentSamples) {
        maxSegment_ = maxSegmentSamples;
        // assign, not resize: after a rate change the buffer starts silent
        // rather than playing the old rate's audio back.
        buffer_.assign(static_cast<size_t>(maxSegmentSamples * 2), 0.0f);
        size_ = static_cast<int>(buffer_.size());
        writePos_ = 0;
    }

    void reset() {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        writePos_ = 0;
    }

    void write(float sample) {
        buffer_[writePos_] = sample;
        if (++writePos_ == size_) writePos_ = 0;
    }

    // Read backwards from current position. [offset] is under the buffer's
    // length (the reverser's segment is at most half of it), so one wrap
    // brings it back in, without the % that is a library call on 32-bit ARM.
    float readReverse(int offset) const {
        int idx = writePos_ - 1 - offset;
        if (idx < 0) idx += size_;
        return buffer_[idx];
    }

    // Hann window for crossfading
    static float hannWindow(float phase) {
        return 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(M_PI) * phase));
    }

    int getMaxSegment() const { return maxSegment_; }

private:
    std::vector<float> buffer_;
    int size_ = 0;
    int writePos_ = 0;
    int maxSegment_ = 0;
};
