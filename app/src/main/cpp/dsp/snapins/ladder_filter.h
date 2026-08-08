#pragma once
#include "snapin_processor.h"
#include "oversampler.h"
#include <cmath>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Analog-modeled Moog/diode ladder LPF with saturation.
// 4 cascaded one-pole filters with resonance feedback.
class LadderFilterProcessor : public SnapinProcessor {
public:
    enum Params {
        CUTOFF = 0, RESONANCE, TOPOLOGY, SATURATE, DRIVE, BIAS, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        // Internal 2x. The cutoff mapping below is tuned around it
        // (g = tan(wc * 0.25), "half because 2x OS"), so the factor stays at 2
        // and the per-snapin wrapper stacks on top for anyone wanting more.
        osL_.prepare(sampleRate, 2);
        osR_.prepare(sampleRate, 2);
        for (int s = 0; s < 4; s++) {
            stageL_[s] = 0.0f;
            stageR_[s] = 0.0f;
        }
        feedbackL_ = 0.0f;
        feedbackR_ = 0.0f;
    }

    void process(float* left, float* right, int numFrames) override {
        // Cutoff to coefficient (bilinear transform approximation)
        float wc = 2.0f * static_cast<float>(M_PI) * cutoff_ / static_cast<float>(sampleRate_);
        // Compensate for 2x oversampling
        float g = std::tan(wc * 0.25f);  // half because 2x OS
        float gComp = g / (1.0f + g);

        float res = resonance_ / 100.0f * 4.0f;  // 0-4 range for self-oscillation
        float driveLin = saturate_ ? std::pow(10.0f, driveDb_ / 20.0f) : 1.0f;

        for (int i = 0; i < numFrames; i++) {
            // Process at 2x internally for better behaviour near Nyquist.
            // This used to zero-stuff by hand — feed the sample, then feed a
            // literal zero — and keep the second sub-step, with no filter on
            // either side. Zero-stuffing without an anti-image filter injects a
            // mirror image that the per-stage tanh then folds back down, and
            // decimating without an anti-alias filter folds whatever the
            // resonance generated above Nyquist straight into the band. The
            // osL_/osR_ members were already here for this and simply never
            // called. Now they are.
            float upL[2], upR[2];
            osL_.upsample(&left[i], upL, 1);
            osR_.upsample(&right[i], upR, 1);
            for (int os = 0; os < 2; os++) {
                float inL = upL[os];
                float inR = upR[os];

                inL *= driveLin;
                inR *= driveLin;
                inL += bias_;
                inR += bias_;

                // Feedback with resonance
                inL -= res * feedbackL_;
                inR -= res * feedbackR_;

                // 4 cascaded one-pole filters
                for (int s = 0; s < 4; s++) {
                    float v = (inL - stageL_[s]) * gComp;
                    float lp = v + stageL_[s];
                    stageL_[s] = lp + v;

                    // Nonlinearity per stage
                    if (saturate_) {
                        if (topology_ == 0) {
                            // Transistor: tanh
                            stageL_[s] = std::tanh(stageL_[s]);
                        } else {
                            // Diode: asymmetric soft clip
                            float x = stageL_[s];
                            stageL_[s] = (x > 0) ? x / (1.0f + x) : x / (1.0f - 0.5f * x);
                        }
                    }

                    inL = stageL_[s];

                    v = (inR - stageR_[s]) * gComp;
                    lp = v + stageR_[s];
                    stageR_[s] = lp + v;

                    if (saturate_) {
                        if (topology_ == 0) {
                            stageR_[s] = std::tanh(stageR_[s]);
                        } else {
                            float x = stageR_[s];
                            stageR_[s] = (x > 0) ? x / (1.0f + x) : x / (1.0f - 0.5f * x);
                        }
                    }

                    inR = stageR_[s];
                }

                feedbackL_ = stageL_[3];
                feedbackR_ = stageR_[3];

                upL[os] = stageL_[3];
                upR[os] = stageR_[3];
            }
            osL_.downsample(upL, &left[i], 1);
            osR_.downsample(upR, &right[i], 1);
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case CUTOFF:    cutoff_ = std::max(20.0f, std::min(20000.0f, value)); break;
            case RESONANCE: resonance_ = std::max(0.0f, std::min(100.0f, value)); break;
            case TOPOLOGY:  topology_ = static_cast<int>(value > 0.5f ? 1 : 0); break;
            case SATURATE:  saturate_ = value > 0.5f; break;
            case DRIVE:     driveDb_ = std::max(0.0f, std::min(48.0f, value)); break;
            case BIAS:      bias_ = std::max(-1.0f, std::min(1.0f, value)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case CUTOFF:    return cutoff_;
            case RESONANCE: return resonance_;
            case TOPOLOGY:  return static_cast<float>(topology_);
            case SATURATE:  return saturate_ ? 1.0f : 0.0f;
            case DRIVE:     return driveDb_;
            case BIAS:      return bias_;
            default: return 0.0f;
        }
    }

    void reset() override {
        for (int s = 0; s < 4; s++) {
            stageL_[s] = 0.0f;
            stageR_[s] = 0.0f;
        }
        feedbackL_ = 0.0f;
        feedbackR_ = 0.0f;
        osL_.reset(); osR_.reset();
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Ladder Filter"; }
    SnapinType getType() const override { return SnapinType::LADDER_FILTER; }

private:
    float cutoff_ = 1000.0f;
    float resonance_ = 0.0f;
    int topology_ = 0;  // 0=Transistor, 1=Diode
    bool saturate_ = false;
    float driveDb_ = 0.0f;
    float bias_ = 0.0f;

    float stageL_[4] = {};
    float stageR_[4] = {};
    float feedbackL_ = 0.0f, feedbackR_ = 0.0f;
    ChannelOversampler osL_, osR_;
};
