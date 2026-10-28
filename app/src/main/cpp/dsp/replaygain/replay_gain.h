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
// Following: a song with no gain tags has no gain to set, so the stage
// measures it instead (follow()): EBU R128 integrated loudness of what comes
// in, before any gain, the meter the Audio Pipeline panel shows. As the
// reading builds, the song glides down to the target over kFollowRampMs. It
// is only ever turned down: a song's loudest moment is not known until it has
// played, and a boost ahead of it could clip. A new song starts at the level
// the last one was left at (never above unity), so a loud song does not burst
// out for the half second before its first reading.
//
// Realtime: setGain() is one atomic store from any thread; process*() reads
// it once a call and neither locks nor allocates. follow() and fixed() are for
// the audio thread, between buffers. The library builds with -ffast-math, so
// no check here relies on NaN or infinity behaving.

#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>

#include "../meter/loudness_meter.h"

namespace trypt::dsp {

class ReplayGainStage {
public:
    /** How long a change of gain takes: long enough not to click, short enough to go unheard. */
    static constexpr float kRampMs = 50.0f;

    /** How long a following song's gain takes to move to a new reading: slow, so it is not heard working. */
    static constexpr float kFollowRampMs = 500.0f;

    /** A new format. The gain reached so far carries on; a measurement starts again if the format changed. */
    void prepare(int sampleRate, int channels) {
        sampleRate_ = sampleRate > 0 ? sampleRate : 48000;
        channels_ = channels > 0 ? channels : 1;
        meter_.configure(sampleRate_, std::min(channels_, tryptify::LoudnessMeter::kMaxChannels));
    }

    /** Linear, 1.0 = unchanged. Anything not a positive finite number is unity. Any thread. */
    void setGain(float linear) {
        target_.store(usable(linear) ? linear : 1.0f, std::memory_order_relaxed);
    }

    /**
     * Measure the song [song] as it plays and turn it down to [targetLufs].
     * The same [song] again (the target moved, a seek) carries on with what
     * has been measured; a different one starts measuring again, from the
     * level the stage is at now.
     */
    void follow(uint32_t song, float targetLufs) {
        targetLufs_ = targetLufs;
        if (following_ && song == song_) return;
        following_ = true;
        song_ = song;
        meter_.reset();
        followGoal_ = std::min(current_, 1.0f);
    }

    /** Back to the gain setGain() gives: the song has tags. */
    void fixed() { following_ = false; }

    bool following() const { return following_; }

    /** The following song's integrated loudness so far, LUFS, or below -999 with no reading yet. */
    float measuredLufs() const {
        const float lufs = meter_.reading().integrated;
        return lufs > kNoReading ? lufs : tryptify::LoudnessMeter::kNone;
    }

    float gain() const { return target_.load(std::memory_order_relaxed); }

    /** The gain the stage is at now, gliding to gain(). */
    float current() const { return current_; }

    /**
     * The start of a stream: at the gain at once, a glide here would be a
     * fade-in. A following song (a seek inside it) stays at the level it was
     * following.
     */
    void reset() {
        current_ = following_ ? followGoal_ : gain();
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
    /** The smallest change of a measured song's gain that is followed: 0.1 dB. */
    static constexpr float kFollowStep = 0.0116f;

    /** Any reading above this is one: kNone and the silence floor are below. */
    static constexpr float kNoReading = -100.0f;

    static bool usable(float v) {
        uint32_t bits;
        std::memcpy(&bits, &v, sizeof bits);
        // Not negative, not zero, and not NaN or infinity (an exponent of all ones).
        return (bits >> 31) == 0 && ((bits >> 23) & 0xffu) != 0xffu && v > 0.0f;
    }

    void measure(const float* in, int frames) { meter_.process(in, frames); }

    void measure(const int16_t* in, int frames) {
        const int per = kScratch / channels_;
        for (int done = 0; done < frames; done += per) {
            const int n = std::min(per, frames - done);
            const int16_t* src = in + static_cast<size_t>(done) * channels_;
            for (int i = 0; i < n * channels_; i++) scratch_[i] = static_cast<float>(src[i]) * (1.0f / 32768.0f);
            meter_.process(scratch_, n);
        }
    }

    template <typename T, typename Apply>
    void run(const T* in, T* out, int frames, Apply apply) {
        if (frames <= 0) return;
        const int samples = frames * channels_;
        float goal;
        float rampMs = kRampMs;
        if (following_ && channels_ <= tryptify::LoudnessMeter::kMaxChannels) {
            // The song as it comes in, before any gain: what the gain is worked from.
            measure(in, frames);
            const float lufs = meter_.reading().integrated;
            if (lufs > kNoReading) {
                const float want = std::min(1.0f, std::pow(10.0f, (targetLufs_ - lufs) / 20.0f));
                // The reading drifts a little with every 100 ms block. Each
                // drift followed would start the glide over, and it would
                // creep towards its goal without landing: only a move past
                // kFollowStep is followed, and unity always is, exactly.
                if (std::fabs(want / followGoal_ - 1.0f) > kFollowStep || ((want == 1.0f) != (followGoal_ == 1.0f))) {
                    followGoal_ = want;
                }
            }
            goal = followGoal_;
            rampMs = kFollowRampMs;
        } else {
            goal = gain();
        }
        if (current_ == goal && goal == 1.0f) {
            // Unity, settled: an exact copy.
            if (in != out) std::memcpy(out, in, sizeof(T) * static_cast<size_t>(samples));
            return;
        }
        if (goal != rampGoal_) {
            rampGoal_ = goal;
            rampStep_ = (goal - current_) / std::max(1.0f, static_cast<float>(sampleRate_) * rampMs / 1000.0f);
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

    /** 16-bit samples as float for the meter, a piece at a time. */
    static constexpr int kScratch = 4096;
    float scratch_[kScratch] = {};

    tryptify::LoudnessMeter meter_;
    bool following_ = false;
    uint32_t song_ = 0;
    float targetLufs_ = -14.0f;
    float followGoal_ = 1.0f;

    std::atomic<float> target_{1.0f};
    float current_ = 1.0f;
    float rampGoal_ = 1.0f;
    float rampStep_ = 0.0f;
    int sampleRate_ = 48000;
    int channels_ = 2;
};

}  // namespace trypt::dsp
