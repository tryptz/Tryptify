#pragma once
#include <vector>
#include <cstring>
#include <cmath>
#include <algorithm>

// Fractional delay line with cubic interpolation.
//
// Positions wrap with a compare rather than %: an integer divide is a library
// call on 32-bit ARM, and a reverb makes over a hundred of these accesses per
// frame. Each read keeps the shape it had, a loop adding the length while the
// index is below the line, with one subtraction above it where the % was.
// The library builds with -ffast-math, which lets the compiler regroup the
// effects' sums by how the code around them is laid out; in this shape the
// 32-bit ARM build still rounds exactly as it did with the %. (The 64-bit one
// regroups a few sums anyway, a last-bit change; so did every shape tried.)
//
// A delay is meant to lie within the line: -2 to getMaxDelay() + 3, which for
// the interpolating reads (their taps reach one either side) is 0 to
// getMaxDelay() + 2. Every effect's prepare() sizes its lines for that. One
// that read further would wrap round into the newest audio, so the host tests
// build with DSP_CHECK_DELAY_RANGE, which counts such reads.
class DelayLine {
public:
    void prepare(int maxDelaySamples) {
        // assign, not resize: after a rate change the line starts silent
        // rather than playing the old rate's audio back out of its tail.
        buffer_.assign(static_cast<size_t>(maxDelaySamples + 4), 0.0f);
        maxDelay_ = maxDelaySamples;
        writePos_ = 0;
    }

    void reset() {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        writePos_ = 0;
    }

    void write(float sample) {
        buffer_[writePos_] = sample;
        if (++writePos_ == static_cast<int>(buffer_.size())) writePos_ = 0;
    }

    // Read at integer delay
    float read(int delaySamples) const {
        int idx = writePos_ - 1 - delaySamples;
        int sz = static_cast<int>(buffer_.size());
        countOverrun(delaySamples, sz);
        while (idx < 0) idx += sz;
        if (idx >= sz) idx -= sz;
        return buffer_[idx];
    }

    // Read backwards from most-recent write (0 = latest, 1 = one before, ...)
    float readReverse(int offset) const { return read(offset); }

    // Read at fractional delay with cubic interpolation
    float readCubic(float delaySamples) const {
        int sz = static_cast<int>(buffer_.size());
        int intDelay = static_cast<int>(delaySamples);
        float frac = delaySamples - static_cast<float>(intDelay);

        auto at = [&](int d) -> float {
            int idx = writePos_ - 1 - d;
            countOverrun(d, sz);
            while (idx < 0) idx += sz;
            if (idx >= sz) idx -= sz;
            return buffer_[idx];
        };

        float y0 = at(intDelay + 1);
        float y1 = at(intDelay);
        float y2 = at(intDelay - 1);
        float y3 = at(intDelay - 2);

        // Catmull-Rom cubic interpolation
        float c0 = y1;
        float c1 = 0.5f * (y2 - y0);
        float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);

        return ((c3 * frac + c2) * frac + c1) * frac + c0;
    }

    // Read with linear interpolation (cheaper)
    float readLinear(float delaySamples) const {
        int sz = static_cast<int>(buffer_.size());
        int intDelay = static_cast<int>(delaySamples);
        float frac = delaySamples - static_cast<float>(intDelay);

        int idx1 = writePos_ - 1 - intDelay;
        int idx2 = idx1 - 1;
        countOverrun(intDelay + 1, sz);
        while (idx1 < 0) idx1 += sz;
        while (idx2 < 0) idx2 += sz;
        if (idx1 >= sz) idx1 -= sz;
        if (idx2 >= sz) idx2 -= sz;

        return buffer_[idx1] * (1.0f - frac) + buffer_[idx2] * frac;
    }

    int getMaxDelay() const { return maxDelay_; }

#ifdef DSP_CHECK_DELAY_RANGE
    // Reads asked for further back than the line holds, across every line.
    static inline long overruns = 0;
#endif

private:
#ifdef DSP_CHECK_DELAY_RANGE
    static void countOverrun(int delay, int size) {
        if (delay < -2 || delay > size - 1) overruns++;
    }
#else
    static void countOverrun(int, int) {}
#endif

    std::vector<float> buffer_;
    int writePos_ = 0;
    int maxDelay_ = 0;
};
