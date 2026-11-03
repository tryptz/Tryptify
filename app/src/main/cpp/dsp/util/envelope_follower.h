#pragma once
#include <cmath>
#include <algorithm>

enum class DetectionMode { Peak, RMS };

// Peak/RMS envelope follower with independent attack/release.
class EnvelopeFollower {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        rmsCoeff_ = rmsCoeffFor(sampleRate_);
        updateCoefficients();
    }

    void setAttack(float ms) { attackMs_ = ms; updateCoefficients(); }
    void setRelease(float ms) { releaseMs_ = ms; updateCoefficients(); }
    void setMode(DetectionMode mode) { mode_ = mode; }

    void reset() { envelope_ = 0.0f; rmsSum_ = 0.0f; }

    float process(float input) {
        float level;
        if (mode_ == DetectionMode::RMS) {
            rmsSum_ += rmsCoeff_ * (input * input - rmsSum_);
            level = std::sqrt(std::max(rmsSum_, 0.0f));
        } else {
            level = std::fabs(input);
        }

        float coeff = (level > envelope_) ? attackCoeff_ : releaseCoeff_;
        envelope_ += coeff * (level - envelope_);
        return envelope_;
    }

    float getEnvelope() const { return envelope_; }

private:
    // ~10ms RMS window approximation via one-pole. It depends on the rate
    // alone, so it is worked out when that is set rather than with an exp()
    // on every sample.
    static float rmsCoeffFor(double sampleRate) {
        return 1.0f - std::exp(-1.0f / (0.01f * static_cast<float>(sampleRate)));
    }

    void updateCoefficients() {
        if (sampleRate_ <= 0.0) return;
        attackCoeff_ = (attackMs_ <= 0.0f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (attackMs_ * 0.001f * static_cast<float>(sampleRate_)));
        releaseCoeff_ = (releaseMs_ <= 0.0f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (releaseMs_ * 0.001f * static_cast<float>(sampleRate_)));
    }

    double sampleRate_ = 44100.0;
    float attackMs_ = 10.0f;
    float releaseMs_ = 100.0f;
    DetectionMode mode_ = DetectionMode::Peak;
    float attackCoeff_ = 1.0f;
    float releaseCoeff_ = 1.0f;
    float rmsCoeff_ = rmsCoeffFor(44100.0);
    float envelope_ = 0.0f;
    float rmsSum_ = 0.0f;
};
