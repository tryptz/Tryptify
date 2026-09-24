#pragma once
#include "snapin_processor.h"
#include "envelope_follower.h"
#include "lookahead_buffer.h"
#include <cmath>
#include <algorithm>

// Noise gate with hysteresis, lookahead, and flip mode.
class GateProcessor : public SnapinProcessor {
public:
    enum Params {
        ATTACK = 0, HOLD, RELEASE, THRESHOLD, TOLERANCE,
        RANGE, LOOKAHEAD, FLIP, SIDECHAIN, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        envL_.prepare(sampleRate);
        envR_.prepare(sampleRate);
        envL_.setAttack(0.1f);
        envL_.setRelease(releaseMs_);
        envR_.setAttack(0.1f);
        envR_.setRelease(releaseMs_);
        envL_.setMode(DetectionMode::Peak);
        envR_.setMode(DetectionMode::Peak);

        // 5ms lookahead
        int laSamples = static_cast<int>(0.005 * sampleRate);
        lookaheadL_.prepare(laSamples);
        lookaheadR_.prepare(laSamples);
        lookaheadSamples_ = laSamples;

        gateGain_ = 0.0f;
        holdCounter_ = 0;
        gateOpen_ = false;
    }

    bool supportsLinkedDetection() const override { return true; }

    void process(float* left, float* right, int numFrames) override {
        float threshLin = std::pow(10.0f, thresholdDb_ / 20.0f);
        float tolLin = std::pow(10.0f, (thresholdDb_ - toleranceDb_) / 20.0f);
        float closedGain = std::pow(10.0f, -rangeDb_ / 20.0f);
        int holdSamples = static_cast<int>(holdMs_ * 0.001f * static_cast<float>(sampleRate_));

        // Attack/release smoothing coefficients
        float attackCoeff = (attackMs_ <= 0.01f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (attackMs_ * 0.001f * static_cast<float>(sampleRate_)));
        float releaseCoeff = (releaseMs_ <= 0.01f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (releaseMs_ * 0.001f * static_cast<float>(sampleRate_)));

        for (int i = 0; i < numFrames; i++) {
            // Detect level
            // Linked across lanes when a key is set (see setDetectorKey).
            float envValL = envL_.process(key_ ? key_[i] : left[i]);
            float envValR = envR_.process(key_ ? key_[i] : right[i]);
            float envVal = std::max(envValL, envValR);

            // Hysteresis gate logic
            if (envVal > threshLin) {
                gateOpen_ = true;
                holdCounter_ = holdSamples;
            } else if (envVal < tolLin) {
                if (holdCounter_ > 0) {
                    holdCounter_--;
                } else {
                    gateOpen_ = false;
                }
            }

            // Target gain
            float targetGain = gateOpen_ ? 1.0f : closedGain;
            if (flip_) targetGain = gateOpen_ ? closedGain : 1.0f;

            // Smooth gain
            float coeff = (targetGain > gateGain_) ? attackCoeff : releaseCoeff;
            gateGain_ += coeff * (targetGain - gateGain_);

            // Apply with optional lookahead
            float outL, outR;
            if (lookahead_) {
                outL = lookaheadL_.process(left[i]);
                outR = lookaheadR_.process(right[i]);
            } else {
                outL = left[i];
                outR = right[i];
            }

            left[i] = outL * gateGain_;
            right[i] = outR * gateGain_;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case ATTACK:    attackMs_ = std::max(0.01f, std::min(100.0f, value)); break;
            case HOLD:      holdMs_ = std::max(0.0f, std::min(500.0f, value)); break;
            case RELEASE:
                releaseMs_ = std::max(1.0f, std::min(2000.0f, value));
                envL_.setRelease(releaseMs_);
                envR_.setRelease(releaseMs_);
                break;
            case THRESHOLD: thresholdDb_ = std::max(-80.0f, std::min(0.0f, value)); break;
            case TOLERANCE: toleranceDb_ = std::max(0.0f, std::min(24.0f, value)); break;
            case RANGE:     rangeDb_ = std::max(0.0f, std::min(80.0f, value)); break;
            case LOOKAHEAD: lookahead_ = value > 0.5f; break;
            case FLIP:      flip_ = value > 0.5f; break;
            case SIDECHAIN: sidechain_ = value > 0.5f; break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case ATTACK:    return attackMs_;
            case HOLD:      return holdMs_;
            case RELEASE:   return releaseMs_;
            case THRESHOLD: return thresholdDb_;
            case TOLERANCE: return toleranceDb_;
            case RANGE:     return rangeDb_;
            case LOOKAHEAD: return lookahead_ ? 1.0f : 0.0f;
            case FLIP:      return flip_ ? 1.0f : 0.0f;
            case SIDECHAIN: return sidechain_ ? 1.0f : 0.0f;
            default: return 0.0f;
        }
    }

    void reset() override {
        envL_.reset(); envR_.reset();
        lookaheadL_.reset(); lookaheadR_.reset();
        gateGain_ = 0.0f;
        holdCounter_ = 0;
        gateOpen_ = false;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Gate"; }
    SnapinType getType() const override { return SnapinType::GATE; }

private:
    float attackMs_ = 0.1f;
    float holdMs_ = 50.0f;
    float releaseMs_ = 100.0f;
    float thresholdDb_ = -30.0f;
    float toleranceDb_ = 6.0f;
    float rangeDb_ = 80.0f;
    bool lookahead_ = false;
    bool flip_ = false;
    bool sidechain_ = false;

    EnvelopeFollower envL_, envR_;
    LookaheadBuffer lookaheadL_, lookaheadR_;
    int lookaheadSamples_ = 0;
    float gateGain_ = 0.0f;
    int holdCounter_ = 0;
    bool gateOpen_ = false;
};
