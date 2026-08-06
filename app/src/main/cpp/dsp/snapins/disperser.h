#pragma once
#include "snapin_processor.h"
#include "biquad.h"
#include <algorithm>
#include <cmath>

// Phase rotator: a stack of identical 2nd-order all-pass filters tuned to one
// frequency/Q. The magnitude response stays perfectly flat — no gain change at
// any frequency — but every stage adds 360° of phase rotation around the
// cutoff, so the stack builds strong frequency-dependent group delay: energy
// near the cutoff arrives later than the rest and transients smear into
// chirps. Amount = filter order (number of stages), Frequency = where the
// delay concentrates (more pronounced when low), Pinch = the shared Q, which
// squeezes the delay into a narrower band.
class DisperserProcessor : public SnapinProcessor {
public:
    enum Params { AMOUNT = 0, FREQ, PINCH, NUM_PARAMS };

    static constexpr int MAX_STAGES = 96;

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        updateFilters();
        reset();
    }

    void process(float* left, float* right, int numFrames) override {
        const int stages = stages_;
        for (int i = 0; i < numFrames; i++) {
            float l = left[i];
            float r = right[i];
            for (int s = 0; s < stages; s++) {
                l = apL_[s].process(l);
                r = apR_[s].process(r);
            }
            left[i] = l;
            right[i] = r;
        }
    }

    void reset() override {
        for (int s = 0; s < MAX_STAGES; s++) {
            apL_[s].reset();
            apR_[s].reset();
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case AMOUNT: {
                int st = static_cast<int>(
                    std::max(1.0f, std::min(static_cast<float>(MAX_STAGES), value)));
                if (st != stages_) {
                    // Newly enabled stages start from silence so growing the
                    // stack doesn't replay stale filter state.
                    for (int s = stages_; s < st; s++) {
                        apL_[s].reset();
                        apR_[s].reset();
                    }
                    stages_ = st;
                }
                break;
            }
            case FREQ:
                freq_ = std::max(20.0f, std::min(20000.0f, value));
                updateFilters();
                break;
            case PINCH:
                q_ = std::max(0.2f, std::min(10.0f, value));
                updateFilters();
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case AMOUNT: return static_cast<float>(stages_);
            case FREQ:   return freq_;
            case PINCH:  return q_;
            default:     return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Disperser"; }
    SnapinType getType() const override { return SnapinType::DISPERSER; }

private:
    void updateFilters() {
        if (sampleRate_ <= 0.0) return;
        double f = std::min(static_cast<double>(freq_), sampleRate_ * 0.45);
        for (int s = 0; s < MAX_STAGES; s++) {
            apL_[s].configure(BiquadType::AllPass, sampleRate_, f, q_);
            apR_[s].configure(BiquadType::AllPass, sampleRate_, f, q_);
        }
    }

    Biquad apL_[MAX_STAGES];
    Biquad apR_[MAX_STAGES];
    int stages_ = 12;
    float freq_ = 250.0f;
    float q_ = 0.71f;
};
