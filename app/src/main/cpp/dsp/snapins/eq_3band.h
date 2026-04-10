#pragma once
#include "snapin_processor.h"
#include "biquad.h"
#include <cmath>
#include <algorithm>

// 3-Band Parametric EQ: Low Shelf + Peaking Mid + High Shelf.
// Independent L/R processing with 3 biquads per channel (6 total).
// Filters reconfigured only when parameters change.
class Eq3BandProcessor : public SnapinProcessor {
public:
    enum Params {
        LOW_FREQ = 0,   // 20-500 Hz
        LOW_GAIN,       // -24 to 24 dB
        LOW_Q,          // 0.1-10
        MID_FREQ,       // 200-8000 Hz
        MID_GAIN,       // -24 to 24 dB
        MID_Q,          // 0.1-10
        HIGH_FREQ,      // 2000-20000 Hz
        HIGH_GAIN,      // -24 to 24 dB
        HIGH_Q,         // 0.1-10
        NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        resetFilters();
        paramsDirty_ = true;
        updateFilters();
    }

    void process(float* left, float* right, int numFrames) override {
        if (paramsDirty_) {
            updateFilters();
            paramsDirty_ = false;
        }

        for (int i = 0; i < numFrames; i++) {
            // Left channel: low shelf -> mid peak -> high shelf
            float sL = left[i];
            sL = lowL_.process(sL);
            sL = midL_.process(sL);
            sL = highL_.process(sL);
            left[i] = sL;

            // Right channel: low shelf -> mid peak -> high shelf
            float sR = right[i];
            sR = lowR_.process(sR);
            sR = midR_.process(sR);
            sR = highR_.process(sR);
            right[i] = sR;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case LOW_FREQ:  lowFreq_  = std::max(20.0f, std::min(500.0f, value)); break;
            case LOW_GAIN:  lowGain_  = std::max(-24.0f, std::min(24.0f, value)); break;
            case LOW_Q:     lowQ_     = std::max(0.1f, std::min(10.0f, value)); break;
            case MID_FREQ:  midFreq_  = std::max(200.0f, std::min(8000.0f, value)); break;
            case MID_GAIN:  midGain_  = std::max(-24.0f, std::min(24.0f, value)); break;
            case MID_Q:     midQ_     = std::max(0.1f, std::min(10.0f, value)); break;
            case HIGH_FREQ: highFreq_ = std::max(2000.0f, std::min(20000.0f, value)); break;
            case HIGH_GAIN: highGain_ = std::max(-24.0f, std::min(24.0f, value)); break;
            case HIGH_Q:    highQ_    = std::max(0.1f, std::min(10.0f, value)); break;
        }
        paramsDirty_ = true;
    }

    float getParameter(int index) const override {
        switch (index) {
            case LOW_FREQ:  return lowFreq_;
            case LOW_GAIN:  return lowGain_;
            case LOW_Q:     return lowQ_;
            case MID_FREQ:  return midFreq_;
            case MID_GAIN:  return midGain_;
            case MID_Q:     return midQ_;
            case HIGH_FREQ: return highFreq_;
            case HIGH_GAIN: return highGain_;
            case HIGH_Q:    return highQ_;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "3-Band EQ"; }
    SnapinType getType() const override { return SnapinType::EQ_3BAND; }

private:
    void resetFilters() {
        lowL_.reset();  lowR_.reset();
        midL_.reset();  midR_.reset();
        highL_.reset(); highR_.reset();
    }

    void updateFilters() {
        if (sampleRate_ <= 0.0) return;

        // Low band: LowShelf
        lowL_.configure(BiquadType::LowShelf, sampleRate_, lowFreq_, lowQ_, lowGain_);
        lowR_.configure(BiquadType::LowShelf, sampleRate_, lowFreq_, lowQ_, lowGain_);

        // Mid band: Peaking
        midL_.configure(BiquadType::Peaking, sampleRate_, midFreq_, midQ_, midGain_);
        midR_.configure(BiquadType::Peaking, sampleRate_, midFreq_, midQ_, midGain_);

        // High band: HighShelf
        highL_.configure(BiquadType::HighShelf, sampleRate_, highFreq_, highQ_, highGain_);
        highR_.configure(BiquadType::HighShelf, sampleRate_, highFreq_, highQ_, highGain_);
    }

    // Parameters
    float lowFreq_  = 100.0f;
    float lowGain_  = 0.0f;
    float lowQ_     = 0.707f;
    float midFreq_  = 1000.0f;
    float midGain_  = 0.0f;
    float midQ_     = 1.0f;
    float highFreq_ = 8000.0f;
    float highGain_ = 0.0f;
    float highQ_    = 0.707f;
    bool  paramsDirty_ = true;

    // Biquad filters: 3 per channel, 6 total
    Biquad lowL_, lowR_;
    Biquad midL_, midR_;
    Biquad highL_, highR_;
};
