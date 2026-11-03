#pragma once
#include "snapin_processor.h"
#include <cmath>
#include <algorithm>
#include <cstdint>

// Lo-fi: reduced sample rate + bit depth with optional dither.
class BitcrushProcessor : public SnapinProcessor {
public:
    enum Params {
        RATE = 0, BITS, ADC_QUALITY, DAC_QUALITY, DITHER, MIX, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        holdL_ = 0.0f;
        holdR_ = 0.0f;
        phaseAcc_ = 0.0f;
    }

    void process(float* left, float* right, int numFrames) override {
        float targetRate = std::max(200.0f, std::min(static_cast<float>(sampleRate_), rate_));
        float phaseInc = targetRate / static_cast<float>(sampleRate_);
        float levels = std::pow(2.0f, std::max(1.0f, bits_));
        float ditherAmt = dither_ / 100.0f;
        float m = mix_ / 100.0f;

        for (int i = 0; i < numFrames; i++) {
            float dryL = left[i];
            float dryR = right[i];

            // Sample-rate reduction via sample-and-hold
            phaseAcc_ += phaseInc;
            if (phaseAcc_ >= 1.0f) {
                phaseAcc_ -= 1.0f;

                float inL = left[i];
                float inR = right[i];

                // ADC quality: simple LPF before quantize (quality 100 = no filter)
                float adcMix = 1.0f - adcQuality_ / 100.0f;
                inL = inL * (1.0f - adcMix) + holdL_ * adcMix;
                inR = inR * (1.0f - adcMix) + holdR_ * adcMix;

                // Triangular PDF dither
                if (ditherAmt > 0.0f) {
                    float d1 = nextUniform() * 2.0f - 1.0f;
                    float d2 = nextUniform() * 2.0f - 1.0f;
                    float tpdf = (d1 + d2) * 0.5f;
                    float lsb = 1.0f / levels;
                    inL += tpdf * lsb * ditherAmt;
                    inR += tpdf * lsb * ditherAmt;
                }

                // Bit depth quantization
                holdL_ = std::round(inL * levels) / levels;
                holdR_ = std::round(inR * levels) / levels;
            }

            // DAC quality: interpolation smoothing (quality 0 = ZOH, 100 = smooth)
            float wetL = holdL_;
            float wetR = holdR_;

            left[i]  = dryL * (1.0f - m) + wetL * m;
            right[i] = dryR * (1.0f - m) + wetR * m;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case RATE:        rate_ = std::max(200.0f, std::min(static_cast<float>(sampleRate_), value)); break;
            case BITS:        bits_ = std::max(1.0f, std::min(24.0f, value)); break;
            case ADC_QUALITY: adcQuality_ = std::max(0.0f, std::min(100.0f, value)); break;
            case DAC_QUALITY: dacQuality_ = std::max(0.0f, std::min(100.0f, value)); break;
            case DITHER:      dither_ = std::max(0.0f, std::min(100.0f, value)); break;
            case MIX:         mix_ = std::max(0.0f, std::min(100.0f, value)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case RATE:        return rate_;
            case BITS:        return bits_;
            case ADC_QUALITY: return adcQuality_;
            case DAC_QUALITY: return dacQuality_;
            case DITHER:      return dither_;
            case MIX:         return mix_;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Bitcrush"; }
    SnapinType getType() const override { return SnapinType::BITCRUSH; }

private:
    // 0..1 from a xorshift of this instance's own, as the pitch shifter's
    // drift has. rand() was called here on the audio thread, and bionic's
    // takes a lock.
    float nextUniform() {
        rng_ ^= rng_ << 13;
        rng_ ^= rng_ >> 17;
        rng_ ^= rng_ << 5;
        return static_cast<float>(rng_ & 0xFFFFFF) / static_cast<float>(0xFFFFFF);
    }

    float rate_ = 44100.0f;
    float bits_ = 24.0f;
    float adcQuality_ = 100.0f;
    float dacQuality_ = 100.0f;
    float dither_ = 0.0f;
    float mix_ = 100.0f;

    float holdL_ = 0.0f, holdR_ = 0.0f;
    float phaseAcc_ = 0.0f;
    uint32_t rng_ = 0x9E3779B9u;
};
