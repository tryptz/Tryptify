#pragma once
#include "snapin_processor.h"
#include "biquad.h"
#include <algorithm>

class FilterProcessor : public SnapinProcessor {
public:
    enum Params { TYPE = 0, CUTOFF, Q, GAIN_DB, SLOPE, NUM_PARAMS };
    // Types: 0=LP, 1=BP, 2=HP, 3=Notch, 4=LowShelf, 5=Peak, 6=HighShelf
    // Slopes: 0=1x(12dB), 1=2x(24dB), 2=3x(36dB), 3=4x(48dB)

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        for (auto& stage : stagesL_) stage.reset();
        for (auto& stage : stagesR_) stage.reset();
        updateCoeffs();
    }

    void process(float* left, float* right, int numFrames) override {
        int stages = slopeMultiplier_ + 1;
        for (int s = 0; s < stages; s++) {
            stagesL_[s].processBlock(left, numFrames);
            stagesR_[s].processBlock(right, numFrames);
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case TYPE:    filterType_ = std::max(0, std::min(6, static_cast<int>(value))); break;
            case CUTOFF:  cutoff_ = std::max(20.0f, std::min(20000.0f, value)); break;
            case Q:       q_ = std::max(0.1f, std::min(20.0f, value)); break;
            case GAIN_DB: gainDb_ = std::max(-24.0f, std::min(24.0f, value)); break;
            case SLOPE:   slopeMultiplier_ = std::max(0, std::min(3, static_cast<int>(value))); break;
        }
        updateCoeffs();
    }

    float getParameter(int index) const override {
        switch (index) {
            case TYPE:    return static_cast<float>(filterType_);
            case CUTOFF:  return cutoff_;
            case Q:       return q_;
            case GAIN_DB: return gainDb_;
            case SLOPE:   return static_cast<float>(slopeMultiplier_);
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Filter"; }
    SnapinType getType() const override { return SnapinType::FILTER; }

private:
    void updateCoeffs() {
        static const BiquadType typeMap[] = {
            BiquadType::LowPass, BiquadType::BandPass, BiquadType::HighPass,
            BiquadType::Notch, BiquadType::LowShelf, BiquadType::Peaking,
            BiquadType::HighShelf
        };
        int idx = std::max(0, std::min(6, filterType_));
        BiquadType bt = typeMap[idx];
        int stages = slopeMultiplier_ + 1;
        // A steeper slope is more stages of the same filter, but not more of
        // its resonance or its gain. Cascading Q 20 four times stacked the
        // peak to +104 dB, and +24 dB of shelf four times to +96 dB. So the
        // resonance sits on the first stage only, the others Butterworth, and
        // a shelf's or peak's gain is shared between the stages so the total
        // is the knob's.
        const bool gainType = bt == BiquadType::LowShelf || bt == BiquadType::HighShelf ||
                              bt == BiquadType::Peaking;
        const bool resonant = bt == BiquadType::LowPass || bt == BiquadType::HighPass;
        for (int s = 0; s < stages; s++) {
            const float q = (resonant && s > 0) ? 0.7071f : q_;
            const float g = gainType ? gainDb_ / static_cast<float>(stages) : gainDb_;
            stagesL_[s].configure(bt, sampleRate_, cutoff_, q, g);
            stagesR_[s].configure(bt, sampleRate_, cutoff_, q, g);
        }
    }

    int filterType_ = 0;      // LP
    float cutoff_ = 1000.0f;
    float q_ = 0.707f;
    float gainDb_ = 0.0f;
    int slopeMultiplier_ = 0;  // 0 = 1x = 12dB/oct

    Biquad stagesL_[4];
    Biquad stagesR_[4];
};
