// SPDX-License-Identifier: GPL-3.0-or-later
//
// ReplayGain's stage: a song's gain (ReplayGain.linear on the Kotlin side,
// which works it out from the tags, the mode and the target loudness),
// applied to the samples, ahead of the mixer and every effect after it, so
// they all see songs at one loudness.
//
// - At unity, settled, the samples are copied through exactly, so bit-perfect
//   output stays bit-perfect.
// - A new gain glides in over kRampMs rather than stepping; the step is fixed
//   when the glide starts, so it lands on the goal exactly (a step worked out
//   afresh from what is left would only approach it, and a gain that never
//   quite gets back to 1.0 is never bit-perfect again).
// - reset() is the start of a stream (a skip, a seek): it starts at the gain.
// - 16-bit is rounded half up, as Java's Math.round, and held to full scale
//   rather than wrapping; float is not clipped, headroom is the later stages'.
//
// Realtime: setGain() is one atomic store from any thread; process*() reads
// it once a call and neither locks nor allocates.

#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace trypt::dsp {

class ReplayGainStage {
public:
    /** How long a change of gain takes: long enough not to click, short enough to go unheard. */
    static constexpr float kRampMs = 50.0f;

    /** A new format. The gain reached so far carries on. */
    void prepare(int sampleRate, int channels) {
        sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
        channels_ = channels > 0 ? channels : 1;
    }

    /** Linear, 1.0 = unchanged. Anything not a positive finite number is unity. Any thread. */
    void setGain(float linear) {
        target_.store(std::isfinite(linear) && linear > 0.0f ? linear : 1.0f, std::memory_order_relaxed);
    }

    float gain() const { return target_.load(std::memory_order_relaxed); }

    /** The gain the stage is at now, gliding to gain(). */
    float current() const { return current_; }

    /** The start of a stream: at the gain at once, a glide here would be a fade-in. */
    void reset() {
        current_ = gain();
        rampGoal_ = current_;
        rampStep_ = 0.0f;
    }

    /** [frames] interleaved frames of [in] to [out], which may be the same buffer. */
    void processFloat(const float* in, float* out, int frames) {
        run(in, out, frames, [](float s, float g) { return s * g; });
    }

    void processInt16(const int16_t* in, int16_t* out, int frames) {
        run(in, out, frames, [](int16_t s, float g) {
            const float v = std::floor(static_cast<float>(s) * g + 0.5f);
            return static_cast<int16_t>(std::clamp(v, -32768.0f, 32767.0f));
        });
    }

private:
    template <typename T, typename Apply>
    void run(const T* in, T* out, int frames, Apply apply) {
        if (frames <= 0) return;
        const int samples = frames * channels_;
        const float goal = gain();
        if (current_ == goal && goal == 1.0f) {
            // Unity, settled: an exact copy.
            if (in != out) std::memcpy(out, in, sizeof(T) * static_cast<size_t>(samples));
            return;
        }
        if (goal != rampGoal_) {
            rampGoal_ = goal;
            rampStep_ = (goal - current_) / std::max(1.0f, static_cast<float>(sampleRate_) * kRampMs / 1000.0f);
        }
        float g = current_;
        int i = 0;
        for (int frame = 0; frame < frames; frame++) {
            if (g != goal) {
                g += rampStep_;
                if ((rampStep_ > 0.0f && g >= goal) || (rampStep_ < 0.0f && g <= goal) || rampStep_ == 0.0f) g = goal;
            }
            for (int c = 0; c < channels_; c++, i++) out[i] = apply(in[i], g);
        }
        current_ = g;
    }

    std::atomic<float> target_{1.0f};
    float current_ = 1.0f;
    float rampGoal_ = 1.0f;
    float rampStep_ = 0.0f;
    int sampleRate_ = 48000;
    int channels_ = 2;
};

}  // namespace trypt::dsp
