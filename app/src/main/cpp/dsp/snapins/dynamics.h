#pragma once
#include "snapin_processor.h"
#include "envelope_follower.h"
#include <cmath>
#include <algorithm>

// Combined upward/downward compressor + expander with dual thresholds.
// Below low threshold: apply low ratio (upward compression if ratio < 1)
// Between thresholds: unity
// Above high threshold: downward compression
class DynamicsProcessor : public SnapinProcessor {
public:
    enum Params {
        LOW_THRESHOLD = 0, LOW_RATIO, HIGH_THRESHOLD, HIGH_RATIO,
        ATTACK, RELEASE, KNEE, INPUT_GAIN, OUTPUT_GAIN, MIX, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        envL_.prepare(sampleRate);
        envR_.prepare(sampleRate);
        envL_.setAttack(attackMs_);
        envL_.setRelease(releaseMs_);
        envR_.setAttack(attackMs_);
        envR_.setRelease(releaseMs_);
        envL_.setMode(DetectionMode::RMS);
        envR_.setMode(DetectionMode::RMS);
        gainSmooth_ = 0.0f;
    }

    bool supportsLinkedDetection() const override { return true; }

    void process(float* left, float* right, int numFrames) override {
        float inputLin = std::pow(10.0f, inputGainDb_ / 20.0f);
        float outputLin = std::pow(10.0f, outputGainDb_ / 20.0f);
        float smoothCoeff = 1.0f - std::exp(-1.0f / (0.002f * static_cast<float>(sampleRate_)));

        for (int i = 0; i < numFrames; i++) {
            float inL = left[i] * inputLin;
            float inR = right[i] * inputLin;

            // Detect level (linked stereo)
            // Linked across lanes when a key is set, at the same input gain.
            float envValL = envL_.process(key_ ? key_[i] * inputLin : inL);
            float envValR = envR_.process(key_ ? key_[i] * inputLin : inR);
            float envVal = std::max(envValL, envValR);

            float levelDb = (envVal > 1e-10f) ? 20.0f * std::log10(envVal) : -200.0f;

            // Compute gain from transfer curve
            float gainDb = computeTransferGain(levelDb);

            // Smooth
            gainSmooth_ += smoothCoeff * (gainDb - gainSmooth_);

            float gain = std::pow(10.0f, gainSmooth_ / 20.0f);

            // Apply with mix (parallel compression)
            float wetL = inL * gain * outputLin;
            float wetR = inR * gain * outputLin;
            left[i]  = inL * (1.0f - mix_) + wetL * mix_;
            right[i] = inR * (1.0f - mix_) + wetR * mix_;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case LOW_THRESHOLD:  lowThreshDb_ = std::max(-60.0f, std::min(0.0f, value)); break;
            case LOW_RATIO:      lowRatio_ = std::max(0.5f, std::min(4.0f, value)); break;
            case HIGH_THRESHOLD: highThreshDb_ = std::max(-60.0f, std::min(0.0f, value)); break;
            case HIGH_RATIO:     highRatio_ = std::max(1.0f, std::min(100.0f, value)); break;
            case ATTACK:
                attackMs_ = std::max(0.1f, std::min(300.0f, value));
                envL_.setAttack(attackMs_);
                envR_.setAttack(attackMs_);
                break;
            case RELEASE:
                releaseMs_ = std::max(1.0f, std::min(3000.0f, value));
                envL_.setRelease(releaseMs_);
                envR_.setRelease(releaseMs_);
                break;
            case KNEE:        kneeDb_ = std::max(0.0f, std::min(24.0f, value)); break;
            case INPUT_GAIN:  inputGainDb_ = std::max(-24.0f, std::min(24.0f, value)); break;
            case OUTPUT_GAIN: outputGainDb_ = std::max(-24.0f, std::min(24.0f, value)); break;
            case MIX:         mix_ = std::max(0.0f, std::min(1.0f, value / 100.0f)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case LOW_THRESHOLD:  return lowThreshDb_;
            case LOW_RATIO:      return lowRatio_;
            case HIGH_THRESHOLD: return highThreshDb_;
            case HIGH_RATIO:     return highRatio_;
            case ATTACK:         return attackMs_;
            case RELEASE:        return releaseMs_;
            case KNEE:           return kneeDb_;
            case INPUT_GAIN:     return inputGainDb_;
            case OUTPUT_GAIN:    return outputGainDb_;
            case MIX:            return mix_ * 100.0f;
            default: return 0.0f;
        }
    }

    void reset() override {
        envL_.reset(); envR_.reset();
        gainSmooth_ = 0.0f;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Dynamics"; }
    SnapinType getType() const override { return SnapinType::DYNAMICS; }

private:
    float computeTransferGain(float levelDb) const {
        float halfKnee = kneeDb_ * 0.5f;

        // Below low threshold region
        if (levelDb < lowThreshDb_ - halfKnee) {
            // Upward/downward based on ratio
            return lowThreshDb_ + (levelDb - lowThreshDb_) / lowRatio_ - levelDb;
        }
        // Soft knee around low threshold
        else if (levelDb < lowThreshDb_ + halfKnee && kneeDb_ > 0.0f) {
            float x = levelDb - lowThreshDb_ + halfKnee;
            float t = x / kneeDb_;
            float kneeGain = (1.0f / lowRatio_ - 1.0f) * (1.0f - t) * (1.0f - t) * 0.5f;
            return kneeGain * kneeDb_;
        }
        // Unity region (between thresholds)
        else if (levelDb < highThreshDb_ - halfKnee) {
            return 0.0f;
        }
        // Soft knee around high threshold
        else if (levelDb < highThreshDb_ + halfKnee && kneeDb_ > 0.0f) {
            float x = levelDb - highThreshDb_ + halfKnee;
            float t = x / kneeDb_;
            float kneeGain = (1.0f / highRatio_ - 1.0f) * t * t * 0.5f;
            return kneeGain * kneeDb_;
        }
        // Above high threshold
        else {
            return highThreshDb_ + (levelDb - highThreshDb_) / highRatio_ - levelDb;
        }
    }

    float lowThreshDb_ = -40.0f;
    float lowRatio_ = 1.0f;
    float highThreshDb_ = -12.0f;
    float highRatio_ = 4.0f;
    float attackMs_ = 10.0f;
    float releaseMs_ = 100.0f;
    float kneeDb_ = 6.0f;
    float inputGainDb_ = 0.0f;
    float outputGainDb_ = 0.0f;
    float mix_ = 1.0f;

    EnvelopeFollower envL_, envR_;
    float gainSmooth_ = 0.0f;
};
