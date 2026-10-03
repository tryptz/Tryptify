#pragma once
#include "snapin_processor.h"
#include "wsola_pitch.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

// Pitch shifter on the WSOLA engine the transposer already uses
// (wsola_pitch.h), which splices at waveform-similar points.
//
// It replaced a two-grain overlap-add shifter that had no phase alignment:
// its grains partly cancelled the shifted tone, so what came out was the
// sidebands of the grain rate (an octave down on 440 Hz measured 200/240 Hz,
// not 220), and it shifted up-shifts the wrong way besides.
//
// Parameters keep their meaning for saved mixes and presets:
//  - Pitch  -24..+24 semitones.
//  - Jitter 0..100 %: a slow random drift of up to ±20 cents, the movement
//    that makes a doubled part sound like a second take.
//  - Grain  10..200 ms: the splice length, as the engine's quality —
//    up to 25 ms fast, up to 60 ms balanced, above that high (which holds
//    pitch down to about 94 Hz; shorter grains give up on bass sooner).
//  - Mix    0..100 %.
class PitchShifterProcessor : public SnapinProcessor {
public:
    enum Params {
        PITCH = 0, JITTER, GRAIN_SIZE, MIX, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        appliedQuality_ = qualityFor(grainMs_.load(std::memory_order_relaxed));
        shifter_.configure(2, static_cast<int>(sampleRate), appliedQuality_);
        shifter_.setSemitones(pitch_.load(std::memory_order_relaxed));
        frame_.assign(static_cast<size_t>(kChunk) * 2, 0.0f);
        driftNow_ = 0.0f;
        driftTarget_ = 0.0f;
        driftCountdown_ = 0;
    }

    void process(float* left, float* right, int numFrames) override {
        // Control changes land here, on the audio thread: a quality change
        // resets the stretcher, which must not happen under its feet.
        const WsolaQuality q = qualityFor(grainMs_.load(std::memory_order_relaxed));
        if (q != appliedQuality_) {
            appliedQuality_ = q;
            shifter_.setQuality(q);
        }
        const float semis = pitch_.load(std::memory_order_relaxed);
        const float jitter = jitter_.load(std::memory_order_relaxed) / 100.0f;
        const float m = mix_.load(std::memory_order_relaxed) / 100.0f;

        for (int done = 0; done < numFrames; done += kChunk) {
            const int n = std::min(kChunk, numFrames - done);
            // Drift: a new random target every ~120 ms, glided to, so the
            // pitch wanders rather than jumps. Applied per chunk.
            if (jitter > 0.0f) {
                if (--driftCountdown_ <= 0) {
                    driftTarget_ = (nextRandom() * 2.0f - 1.0f) * 0.20f * jitter;  // semitones
                    driftCountdown_ = static_cast<int>(0.12 * sampleRate_ / kChunk) + 1;
                }
                driftNow_ += (driftTarget_ - driftNow_) * 0.05f;
            } else {
                driftNow_ = 0.0f;
            }
            shifter_.setSemitones(semis + driftNow_);

            for (int i = 0; i < n; i++) {
                frame_[static_cast<size_t>(i) * 2] = left[done + i];
                frame_[static_cast<size_t>(i) * 2 + 1] = right[done + i];
            }
            shifter_.process(frame_.data(), frame_.data(), n);
            for (int i = 0; i < n; i++) {
                const float wetL = frame_[static_cast<size_t>(i) * 2];
                const float wetR = frame_[static_cast<size_t>(i) * 2 + 1];
                left[done + i] = left[done + i] * (1.0f - m) + wetL * m;
                right[done + i] = right[done + i] * (1.0f - m) + wetR * m;
            }
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case PITCH:      pitch_.store(std::max(-24.0f, std::min(24.0f, value)), std::memory_order_relaxed); break;
            case JITTER:     jitter_.store(std::max(0.0f, std::min(100.0f, value)), std::memory_order_relaxed); break;
            case GRAIN_SIZE: grainMs_.store(std::max(10.0f, std::min(200.0f, value)), std::memory_order_relaxed); break;
            case MIX:        mix_.store(std::max(0.0f, std::min(100.0f, value)), std::memory_order_relaxed); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case PITCH:      return pitch_.load(std::memory_order_relaxed);
            case JITTER:     return jitter_.load(std::memory_order_relaxed);
            case GRAIN_SIZE: return grainMs_.load(std::memory_order_relaxed);
            case MIX:        return mix_.load(std::memory_order_relaxed);
            default: return 0.0f;
        }
    }

    void reset() override {
        shifter_.reset();
        driftNow_ = 0.0f;
        driftTarget_ = 0.0f;
        driftCountdown_ = 0;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Pitch Shifter"; }
    SnapinType getType() const override { return SnapinType::PITCH_SHIFTER; }

private:
    using WsolaQuality = tryptify::WsolaQuality;

    // Frames per pass through the shifter; the scratch is sized for it once.
    static constexpr int kChunk = 256;

    static WsolaQuality qualityFor(float grainMs) {
        if (grainMs <= 25.0f) return WsolaQuality::kFast;
        if (grainMs <= 60.0f) return WsolaQuality::kBalanced;
        return WsolaQuality::kHigh;
    }

    /** 0..1, xorshift: no locks, no allocation, fine for a drift. */
    float nextRandom() {
        rng_ ^= rng_ << 13;
        rng_ ^= rng_ >> 17;
        rng_ ^= rng_ << 5;
        return static_cast<float>(rng_ & 0xFFFFFF) / static_cast<float>(0xFFFFFF);
    }

    std::atomic<float> pitch_{0.0f};
    std::atomic<float> jitter_{0.0f};
    std::atomic<float> grainMs_{50.0f};
    std::atomic<float> mix_{100.0f};

    tryptify::WsolaPitchShifter shifter_;
    WsolaQuality appliedQuality_ = WsolaQuality::kBalanced;
    std::vector<float> frame_;
    float driftNow_ = 0.0f;
    float driftTarget_ = 0.0f;
    int driftCountdown_ = 0;
    uint32_t rng_ = 0x9E3779B9u;
};
