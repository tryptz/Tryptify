#pragma once
#include <vector>
#include <cstring>
#include <algorithm>

// Pre-delay ring buffer for lookahead limiters/gates. The ring holds the
// latest inputs, as many as it is long, in order whatever the delay, and the
// delay only sets how far back the read is, so the delay can change
// mid-stream without reordering what is held.
class LookaheadBuffer {
public:
    // For a new rate: starts silent, rather than playing out audio held from
    // the old one. [maxDelaySamples] is the longest delay setDelay() will be
    // given at this rate: the ring is made that long here, so a knob or a
    // music link that moves the delay on the audio thread never allocates.
    // [fadeSamples] is how long a change of delay crossfades from the old
    // read to the new one; 0 changes it at once.
    void prepare(int delaySamples, int maxDelaySamples = 0, int fadeSamples = 0) {
        delay_ = target_ = delaySamples;
        size_ = std::max(delaySamples, maxDelaySamples);
        buffer_.assign(static_cast<size_t>(size_), 0.0f);
        writePos_ = 0;
        fadeLen_ = std::max(0, fadeSamples);
        fadeLeft_ = 0;
    }

    // For a knob that sets the delay mid-stream: keeps what is held, so
    // turning it does not cut the signal out for the length of the lookahead.
    // The output becomes the input delayed by the new amount: a longer delay
    // plays back audio the ring still holds, a shorter one skips ahead, and
    // the same delay changes nothing. Either jump would click on every block
    // a music link moves the delay in, so with a fade the old read fades out
    // as the new one fades in, and a change that comes during a fade starts
    // when it ends. Within the length given to prepare() this only moves the
    // read. A longer delay (only before prepare(), off the audio thread)
    // grows the ring, with silence before the oldest sample it held.
    void setDelay(int delaySamples) {
        if (delaySamples > size_) grow(delaySamples);
        target_ = delaySamples;
    }

    void reset() {
        std::fill(buffer_.begin(), buffer_.end(), 0.0f);
        writePos_ = 0;
        delay_ = target_;
        fadeLeft_ = 0;
    }

    float process(float in) {
        if (size_ == 0) return in;
        if (target_ != delay_ && fadeLeft_ == 0) {
            from_ = delay_;
            delay_ = target_;
            fadeLeft_ = fadeLen_;
        }
        float out = tap(delay_, in);
        if (fadeLeft_ > 0) {
            const float old = static_cast<float>(fadeLeft_--) / static_cast<float>(fadeLen_ + 1);
            out += old * (tap(from_, in) - out);
        }
        buffer_[writePos_] = in;
        if (++writePos_ == size_) writePos_ = 0;
        return out;
    }

    // The delay last set, which the output is at or fading to.
    int getDelay() const { return target_; }

private:
    // The input [delay] samples back; the next write goes to writePos_. At no
    // delay the input goes straight out, and the ring still records it, for a
    // longer delay later.
    float tap(int delay, float in) const {
        if (delay <= 0) return in;
        int readPos = writePos_ - delay;
        if (readPos < 0) readPos += size_;
        return buffer_[readPos];
    }

    // Puts the ring in order, oldest first, and lengthens it at the end; the
    // next write goes to the first of the new samples, which are silence
    // older than anything held.
    void grow(int size) {
        std::rotate(buffer_.begin(), buffer_.begin() + writePos_, buffer_.end());
        writePos_ = size_;
        size_ = size;
        buffer_.resize(static_cast<size_t>(size_), 0.0f);
    }

    std::vector<float> buffer_;
    int writePos_ = 0;
    int size_ = 0;
    int delay_ = 0;    // the delay read now
    int target_ = 0;   // the delay last set
    int from_ = 0;     // the delay a fade is leaving
    int fadeLen_ = 0;
    int fadeLeft_ = 0;
};
