#pragma once
#include "snapin_processor.h"
#include "../util/biquad.h"
#include <cmath>
#include <algorithm>

// 10-Band Parametric EQ with preamp.
// Each band: configurable type (peaking/low shelf/high shelf), freq, gain, Q, enable.
// Used by AutoEQ for headphone frequency-response correction.
class Eq10BandProcessor : public SnapinProcessor {
public:
    static constexpr int NUM_BANDS = 10;

    // Parameter layout:
    //   0        = preamp (dB, -24..+24)
    //   1+n*5+0  = band n frequency (Hz)
    //   1+n*5+1  = band n gain (dB)
    //   1+n*5+2  = band n Q
    //   1+n*5+3  = band n type (0=peaking, 1=lowshelf, 2=highshelf)
    //   1+n*5+4  = band n enabled (0 or 1)
    enum { PREAMP = 0, PARAMS_PER_BAND = 5, NUM_PARAMS = 1 + NUM_BANDS * PARAMS_PER_BAND };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        for (int i = 0; i < NUM_BANDS; i++) {
            bandsL_[i].reset();
            bandsR_[i].reset();
        }
        paramsDirty_ = true;
        updateFilters();
    }

    void process(float* left, float* right, int numFrames) override {
        if (paramsDirty_) {
            updateFilters();
            paramsDirty_ = false;
        }

        // Apply preamp
        float preampLinear = preampLinear_;
        if (preampLinear != 1.0f) {
            for (int i = 0; i < numFrames; i++) {
                left[i] *= preampLinear;
                right[i] *= preampLinear;
            }
        }

        // Apply each enabled band
        for (int b = 0; b < NUM_BANDS; b++) {
            if (!bandEnabled_[b] || bandGain_[b] == 0.0f) continue;
            bandsL_[b].processBlock(left, numFrames);
            bandsR_[b].processBlock(right, numFrames);
        }
    }

    void reset() override {
        for (int i = 0; i < NUM_BANDS; i++) {
            bandsL_[i].reset();
            bandsR_[i].reset();
        }
    }

    void setParameter(int index, float value) override {
        if (index < 0 || index >= NUM_PARAMS) return;

        if (index == PREAMP) {
            preampDb_ = std::max(-24.0f, std::min(24.0f, value));
            preampLinear_ = std::pow(10.0f, preampDb_ / 20.0f);
            return;
        }

        int bandIdx = (index - 1) / PARAMS_PER_BAND;
        int paramInBand = (index - 1) % PARAMS_PER_BAND;
        if (bandIdx < 0 || bandIdx >= NUM_BANDS) return;

        switch (paramInBand) {
            case 0: bandFreq_[bandIdx]    = std::max(20.0f, std::min(20000.0f, value)); break;
            case 1: bandGain_[bandIdx]    = std::max(-24.0f, std::min(24.0f, value)); break;
            case 2: bandQ_[bandIdx]       = std::max(0.1f, std::min(30.0f, value)); break;
            case 3: bandType_[bandIdx]    = static_cast<int>(value); break;
            case 4: bandEnabled_[bandIdx] = value >= 0.5f; break;
        }
        paramsDirty_ = true;
    }

    float getParameter(int index) const override {
        if (index == PREAMP) return preampDb_;
        if (index < 1 || index >= NUM_PARAMS) return 0.0f;

        int bandIdx = (index - 1) / PARAMS_PER_BAND;
        int paramInBand = (index - 1) % PARAMS_PER_BAND;
        if (bandIdx < 0 || bandIdx >= NUM_BANDS) return 0.0f;

        switch (paramInBand) {
            case 0: return bandFreq_[bandIdx];
            case 1: return bandGain_[bandIdx];
            case 2: return bandQ_[bandIdx];
            case 3: return static_cast<float>(bandType_[bandIdx]);
            case 4: return bandEnabled_[bandIdx] ? 1.0f : 0.0f;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "10-Band EQ"; }
    SnapinType getType() const override { return SnapinType::EQ_10BAND; }

private:
    void updateFilters() {
        if (sampleRate_ <= 0.0) return;

        for (int b = 0; b < NUM_BANDS; b++) {
            if (!bandEnabled_[b] || bandGain_[b] == 0.0f) continue;

            BiquadType bqType;
            switch (bandType_[b]) {
                case 1:  bqType = BiquadType::LowShelf; break;
                case 2:  bqType = BiquadType::HighShelf; break;
                default: bqType = BiquadType::Peaking; break;
            }

            bandsL_[b].configure(bqType, sampleRate_, bandFreq_[b], bandQ_[b], bandGain_[b]);
            bandsR_[b].configure(bqType, sampleRate_, bandFreq_[b], bandQ_[b], bandGain_[b]);
        }
    }

    // Preamp
    float preampDb_ = 0.0f;
    float preampLinear_ = 1.0f;

    // Per-band state
    float bandFreq_[NUM_BANDS]    = { 31.0f, 63.0f, 125.0f, 250.0f, 500.0f,
                                      1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f };
    float bandGain_[NUM_BANDS]    = {};  // all 0 dB
    float bandQ_[NUM_BANDS]       = { 1.0f, 1.0f, 1.0f, 1.0f, 1.0f,
                                      1.0f, 1.0f, 1.0f, 1.0f, 1.0f };
    int   bandType_[NUM_BANDS]    = {};  // all 0 = peaking
    bool  bandEnabled_[NUM_BANDS] = { true, true, true, true, true,
                                      true, true, true, true, true };
    bool  paramsDirty_ = true;

    Biquad bandsL_[NUM_BANDS];
    Biquad bandsR_[NUM_BANDS];
};
