#pragma once
#include "snapin_processor.h"
#include "envelope_follower.h"
#include "lookahead_buffer.h"
#include <cmath>
#include <algorithm>

// Lookahead limiter / ducker with attack as lookahead pre-delay.
// Envelope follower modes: RMS, Peak, ISP (inter-sample peak).
class CompactorProcessor : public SnapinProcessor {
public:
    enum Params {
        ATTACK = 0, HOLD, RELEASE, THRESHOLD, MODE,
        RANGE_PCT, STEREO_LINK, SIDECHAIN, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;

        envL_.prepare(sampleRate);
        envR_.prepare(sampleRate);
        envL_.setAttack(0.01f);  // Near-instant for peak detection
        envR_.setAttack(0.01f);
        envL_.setRelease(releaseMs_);
        envR_.setRelease(releaseMs_);
        envL_.setMode(DetectionMode::Peak);
        envR_.setMode(DetectionMode::Peak);

        updateLookahead();

        gainL_ = 1.0f;
        gainR_ = 1.0f;
        holdCounterL_ = 0;
        holdCounterR_ = 0;
    }

    bool supportsLinkedDetection() const override { return true; }

    void process(float* left, float* right, int numFrames) override {
        float threshLin = std::pow(10.0f, thresholdDb_ / 20.0f);
        int holdSamples = static_cast<int>(holdMs_ * 0.001f * static_cast<float>(sampleRate_));
        float releaseCoeff = 1.0f - std::exp(-1.0f / (releaseMs_ * 0.001f * static_cast<float>(sampleRate_)));
        float rangeFactor = rangePct_ / 100.0f;

        for (int i = 0; i < numFrames; i++) {
            // Lookahead delay
            float delL = lookaheadL_.process(left[i]);
            float delR = lookaheadR_.process(right[i]);

            // Envelope detection on undelayed signal — or on the lanes' shared
            // key when linked (see setDetectorKey).
            const float detL = key_ ? key_[i] : left[i];
            const float detR = key_ ? key_[i] : right[i];
            float envValL = envL_.process(detL);
            float envValR = envR_.process(detR);

            // ISP mode: parabolic interpolation between samples
            if (mode_ == 2) {
                envValL = ispPeak(detL, prevL_);
                envValR = ispPeak(detR, prevR_);
                prevL_ = detL;
                prevR_ = detR;
            }

            // Stereo linking
            float envLinked;
            if (stereoLink_ >= 0.99f) {
                // Dual mono
                // Process each channel independently
                float targetL = computeGain(envValL, threshLin, rangeFactor);
                float targetR = computeGain(envValR, threshLin, rangeFactor);
                gainL_ = smoothGain(gainL_, targetL, holdCounterL_, holdSamples, releaseCoeff);
                gainR_ = smoothGain(gainR_, targetR, holdCounterR_, holdSamples, releaseCoeff);
                left[i] = delL * gainL_;
                right[i] = delR * gainR_;
                continue;
            }

            // Linked: use max of L/R
            envLinked = std::max(envValL, envValR) * (1.0f - stereoLink_)
                      + (envValL + envValR) * 0.5f * stereoLink_;
            float target = computeGain(envLinked, threshLin, rangeFactor);
            gainL_ = smoothGain(gainL_, target, holdCounterL_, holdSamples, releaseCoeff);

            left[i] = delL * gainL_;
            right[i] = delR * gainL_;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case ATTACK:
                attackMs_ = std::max(0.0f, std::min(50.0f, value));
                updateLookahead();
                break;
            case HOLD:    holdMs_ = std::max(0.0f, std::min(500.0f, value)); break;
            case RELEASE:
                releaseMs_ = std::max(1.0f, std::min(1000.0f, value));
                envL_.setRelease(releaseMs_);
                envR_.setRelease(releaseMs_);
                break;
            case THRESHOLD: thresholdDb_ = std::max(-60.0f, std::min(0.0f, value)); break;
            case MODE:
                mode_ = static_cast<int>(std::max(0.0f, std::min(2.0f, value)));
                if (mode_ == 0) { envL_.setMode(DetectionMode::RMS); envR_.setMode(DetectionMode::RMS); }
                else { envL_.setMode(DetectionMode::Peak); envR_.setMode(DetectionMode::Peak); }
                break;
            case RANGE_PCT:    rangePct_ = std::max(0.0f, std::min(200.0f, value)); break;
            case STEREO_LINK:  stereoLink_ = std::max(0.0f, std::min(1.0f, value / 100.0f)); break;
            case SIDECHAIN:    sidechain_ = value > 0.5f; break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case ATTACK:       return attackMs_;
            case HOLD:         return holdMs_;
            case RELEASE:      return releaseMs_;
            case THRESHOLD:    return thresholdDb_;
            case MODE:         return static_cast<float>(mode_);
            case RANGE_PCT:    return rangePct_;
            case STEREO_LINK:  return stereoLink_ * 100.0f;
            case SIDECHAIN:    return sidechain_ ? 1.0f : 0.0f;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Compactor"; }
    SnapinType getType() const override { return SnapinType::COMPACTOR; }

private:
    void updateLookahead() {
        int samples = static_cast<int>(attackMs_ * 0.001f * static_cast<float>(sampleRate_));
        if (samples < 1) samples = 1;
        lookaheadL_.prepare(samples);
        lookaheadR_.prepare(samples);
    }

    float computeGain(float envVal, float threshLin, float rangeFactor) const {
        if (envVal <= threshLin || threshLin <= 0.0f) return 1.0f;
        float reduction = threshLin / envVal;
        return 1.0f - (1.0f - reduction) * rangeFactor;
    }

    float smoothGain(float current, float target, int& holdCounter, int holdSamples, float releaseCoeff) {
        if (target < current) {
            // Instant attack (within lookahead window)
            holdCounter = holdSamples;
            return target;
        }
        if (holdCounter > 0) {
            holdCounter--;
            return current;
        }
        return current + releaseCoeff * (target - current);
    }

    // Inter-sample peak via parabolic interpolation
    float ispPeak(float current, float prev) const {
        float peak = std::max(std::fabs(current), std::fabs(prev));
        // Parabolic overshoot estimation
        float mid = (current + prev) * 0.5f;
        float overshoot = std::fabs(mid) + std::fabs(current - prev) * 0.25f;
        return std::max(peak, overshoot);
    }

    float attackMs_ = 5.0f;
    float holdMs_ = 10.0f;
    float releaseMs_ = 100.0f;
    float thresholdDb_ = -12.0f;
    int mode_ = 1;  // 0=RMS, 1=Peak, 2=ISP
    float rangePct_ = 100.0f;
    float stereoLink_ = 0.0f;  // 0=linked, 1=dual-mono
    bool sidechain_ = false;

    EnvelopeFollower envL_, envR_;
    LookaheadBuffer lookaheadL_, lookaheadR_;
    float gainL_ = 1.0f, gainR_ = 1.0f;
    int holdCounterL_ = 0, holdCounterR_ = 0;
    float prevL_ = 0.0f, prevR_ = 0.0f;
};
