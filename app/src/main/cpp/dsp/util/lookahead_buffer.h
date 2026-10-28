#pragma once
#include <vector>
#include <cstring>
#include <algorithm>

// Pre-delay ring buffer for lookahead limiters/gates.
class LookaheadBuffer {
public:
    // For a new rate: starts silent, rather than playing out audio held from
    // the old one.
    void prepare(int delaySamples) {
        delay_ = delaySamples;
        buffer_.assign(static_cast<size_t>(delaySamples), 0.0f);
        writePos_ = 0;
    }

    // For a knob that sets the delay mid-stream: keeps what is held, as this
    // has always done, so turning it does not cut the signal out for the
    // length of the lookahead.
    void setDelay(int delaySamples) {
        delay_ = delaySamples;
        buffer_.resize(static_cast<size_t>(delaySamples), 0.0f);
        writePos_ = 0;
    }

    void reset() {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        writePos_ = 0;
    }

    float process(float in) {
        if (delay_ == 0) return in;
        float out = buffer_[writePos_];
        buffer_[writePos_] = in;
        if (++writePos_ == delay_) writePos_ = 0;
        return out;
    }

    int getDelay() const { return delay_; }

private:
    std::vector<float> buffer_;
    int writePos_ = 0;
    int delay_ = 0;
};
