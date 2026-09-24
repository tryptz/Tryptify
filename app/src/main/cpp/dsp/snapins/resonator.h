#pragma once
#include "snapin_processor.h"
#include "delay_line.h"
#include <cmath>
#include <algorithm>

// Tuned harmonic resonance via feedback comb filter.
class ResonatorProcessor : public SnapinProcessor {
public:
    enum Params {
        PITCH = 0, DECAY_PCT, INTENSITY, TIMBRE, MIX, NUM_PARAMS
    };
    // Timbre: 0=Saw (all harmonics), 1=Square (odd harmonics)

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        // Max delay for C0 (~16Hz)
        int maxSamples = static_cast<int>(sampleRate / 16.0) + 16;
        combL_.prepare(maxSamples);
        combR_.prepare(maxSamples);
        dampL_ = 0.0f;
        dampR_ = 0.0f;
    }

    void process(float* left, float* right, int numFrames) override {
        // Convert MIDI-style pitch to Hz: A4=440, pitch 69
        float freq = 440.0f * std::pow(2.0f, (pitch_ - 69.0f) / 12.0f);
        float delaySamples = static_cast<float>(sampleRate_) / std::max(16.0f, freq);
        float fb = 0.5f + (decay_ / 100.0f) * 0.49f;  // 0.5 to 0.99
        float intensity = intensity_ / 100.0f;
        float dampCoeff = 0.3f * (1.0f - decay_ / 100.0f);  // More damping = shorter decay
        float m = mix_ / 100.0f;

        for (int i = 0; i < numFrames; i++) {
            float dryL = left[i];
            float dryR = right[i];

            // Read from comb
            float combOutL = combL_.readCubic(delaySamples);
            float combOutR = combR_.readCubic(delaySamples);

            // One-pole damping in feedback loop
            dampL_ += dampCoeff * (combOutL - dampL_);
            dampR_ += dampCoeff * (combOutR - dampR_);
            float fbL = combOutL * (1.0f - dampCoeff) + dampL_;
            float fbR = combOutR * (1.0f - dampCoeff) + dampR_;

            // Square timbre: add inverted comb at half delay (cancels even harmonics)
            if (timbre_ == 1) {
                // Subtracting the half-period copy cancels even harmonics and
                // doubles up odd ones: on those the loop gain is 1.5 × fb,
                // which ran away above ~34 % decay. Scaled back by 1.5 the
                // odd harmonics loop at fb (< 1) and the tone is unchanged.
                float halfDelay = delaySamples * 0.5f;
                fbL = (fbL - combL_.readCubic(halfDelay) * 0.5f) * (1.0f / 1.5f);
                fbR = (fbR - combR_.readCubic(halfDelay) * 0.5f) * (1.0f / 1.5f);
            }

            // Write input + feedback
            combL_.write(dryL * intensity + fbL * fb);
            combR_.write(dryR * intensity + fbR * fb);

            float wetL = combOutL;
            float wetR = combOutR;

            left[i]  = dryL * (1.0f - m) + wetL * m;
            right[i] = dryR * (1.0f - m) + wetR * m;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case PITCH:     pitch_ = std::max(0.0f, std::min(127.0f, value)); break;
            case DECAY_PCT: decay_ = std::max(0.0f, std::min(100.0f, value)); break;
            case INTENSITY: intensity_ = std::max(0.0f, std::min(100.0f, value)); break;
            case TIMBRE:    timbre_ = static_cast<int>(value > 0.5f ? 1 : 0); break;
            case MIX:       mix_ = std::max(0.0f, std::min(100.0f, value)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case PITCH:     return pitch_;
            case DECAY_PCT: return decay_;
            case INTENSITY: return intensity_;
            case TIMBRE:    return static_cast<float>(timbre_);
            case MIX:       return mix_;
            default: return 0.0f;
        }
    }

    void reset() override {
        combL_.reset(); combR_.reset();
        dampL_ = 0.0f;
        dampR_ = 0.0f;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Resonator"; }
    SnapinType getType() const override { return SnapinType::RESONATOR; }

private:
    float pitch_ = 69.0f;  // A4
    float decay_ = 50.0f;
    float intensity_ = 50.0f;
    int timbre_ = 0;  // 0=Saw, 1=Square
    float mix_ = 50.0f;

    DelayLine combL_, combR_;
    float dampL_ = 0.0f, dampR_ = 0.0f;
};
