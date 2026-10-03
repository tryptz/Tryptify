#pragma once
#include "snapin_processor.h"
#include "delay_line.h"
#include "envelope_follower.h"
#include <cmath>
#include <algorithm>

// Industry-standard feed-forward compressor with soft knee, lookahead,
// linked stereo detection, and parallel compression (MIX).
class CompressorProcessor : public SnapinProcessor {
public:
    enum Params {
        ATTACK = 0, RELEASE, RATIO, THRESHOLD, KNEE,
        MAKEUP, MODE, LOOKAHEAD, MIX, NUM_PARAMS
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
        envL_.setMode(mode_ == 0 ? DetectionMode::RMS : DetectionMode::Peak);
        envR_.setMode(mode_ == 0 ? DetectionMode::RMS : DetectionMode::Peak);

        // Max lookahead = 10 ms
        int maxLookaheadSamples = static_cast<int>(0.010 * sampleRate) + 1;
        delayL_.prepare(maxLookaheadSamples);
        delayR_.prepare(maxLookaheadSamples);
        delayL_.reset();
        delayR_.reset();

        lookaheadSamples_ = static_cast<int>(lookaheadMs_ * 0.001f * static_cast<float>(sampleRate));

        gainEnvDb_ = 0.0f;
        updateAttackReleaseCoeffs();
    }

    bool supportsLinkedDetection() const override { return true; }

    void process(float* left, float* right, int numFrames) override {
        const float makeupLin = dbToLin(makeupDb_);

        for (int i = 0; i < numFrames; i++) {
            float inL = left[i];
            float inR = right[i];

            // --- Detection path (pre-delay) ---
            // Linked across lanes when a key is set (see setDetectorKey).
            float envValL = envL_.process(key_ ? key_[i] : inL);
            float envValR = envR_.process(key_ ? key_[i] : inR);
            float envVal = std::max(envValL, envValR); // linked stereo

            float levelDb = linToDb(envVal);

            // --- Gain computer with soft knee ---
            float gcDb = computeGain(levelDb);
            float gainReductionDb = gcDb - levelDb; // always <= 0

            // --- Smooth the gain reduction envelope (attack/release) ---
            if (gainReductionDb < gainEnvDb_) {
                // Attack (gain is dropping = more compression)
                gainEnvDb_ += attackCoeff_ * (gainReductionDb - gainEnvDb_);
            } else {
                // Release (gain is rising = less compression)
                gainEnvDb_ += releaseCoeff_ * (gainReductionDb - gainEnvDb_);
            }

            // --- Lookahead delay on dry signal ---
            delayL_.write(inL);
            delayR_.write(inR);
            float delL = delayL_.read(lookaheadSamples_);
            float delR = delayR_.read(lookaheadSamples_);

            // --- Apply gain ---
            float gainLin = dbToLin(gainEnvDb_ + makeupDb_);
            float wetL = delL * gainLin;
            float wetR = delR * gainLin;

            // --- Parallel compression (MIX) ---
            float dryMakeup = delL; // dry path (delayed to stay aligned)
            float dryMakeupR = delR;
            left[i]  = dryMakeup  + mix_ * (wetL - dryMakeup);
            right[i] = dryMakeupR + mix_ * (wetR - dryMakeupR);
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case ATTACK:
                attackMs_ = clamp(value, 0.1f, 300.0f);
                envL_.setAttack(attackMs_);
                envR_.setAttack(attackMs_);
                updateAttackReleaseCoeffs();
                break;
            case RELEASE:
                releaseMs_ = clamp(value, 1.0f, 3000.0f);
                envL_.setRelease(releaseMs_);
                envR_.setRelease(releaseMs_);
                updateAttackReleaseCoeffs();
                break;
            case RATIO:
                ratio_ = clamp(value, 1.0f, 100.0f);
                break;
            case THRESHOLD:
                thresholdDb_ = clamp(value, -60.0f, 0.0f);
                break;
            case KNEE:
                kneeDb_ = clamp(value, 0.0f, 24.0f);
                break;
            case MAKEUP:
                makeupDb_ = clamp(value, 0.0f, 40.0f);
                break;
            case MODE:
                mode_ = static_cast<int>(value);
                envL_.setMode(mode_ == 0 ? DetectionMode::RMS : DetectionMode::Peak);
                envR_.setMode(mode_ == 0 ? DetectionMode::RMS : DetectionMode::Peak);
                break;
            case LOOKAHEAD:
                lookaheadMs_ = clamp(value, 0.0f, 10.0f);
                lookaheadSamples_ = static_cast<int>(lookaheadMs_ * 0.001f * static_cast<float>(sampleRate_));
                break;
            case MIX:
                mix_ = clamp(value, 0.0f, 100.0f) / 100.0f;
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case ATTACK:    return attackMs_;
            case RELEASE:   return releaseMs_;
            case RATIO:     return ratio_;
            case THRESHOLD: return thresholdDb_;
            case KNEE:      return kneeDb_;
            case MAKEUP:    return makeupDb_;
            case MODE:      return static_cast<float>(mode_);
            case LOOKAHEAD: return lookaheadMs_;
            case MIX:       return mix_ * 100.0f;
            default: return 0.0f;
        }
    }

    void reset() override {
        envL_.reset(); envR_.reset();
        delayL_.reset(); delayR_.reset();
        gainEnvDb_ = 0.0f;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Compressor"; }
    SnapinType getType() const override { return SnapinType::COMPRESSOR; }

private:
    // --- Gain computer ---
    // Implements hard/soft knee compression curve.
    // Returns the desired output level in dB for a given input level in dB.
    float computeGain(float inputDb) const {
        float T = thresholdDb_;
        float R = ratio_;
        float W = kneeDb_;

        if (W <= 0.0f || inputDb < (T - W * 0.5f)) {
            // Below knee region: hard-knee behavior
            if (inputDb <= T) {
                return inputDb; // no compression
            } else {
                return T + (inputDb - T) / R;
            }
        } else if (inputDb > (T + W * 0.5f)) {
            // Above knee region
            return T + (inputDb - T) / R;
        } else {
            // Inside soft knee region: quadratic interpolation
            float x = inputDb - T + W * 0.5f;
            return inputDb + ((1.0f / R) - 1.0f) * x * x / (2.0f * W);
        }
    }

    void updateAttackReleaseCoeffs() {
        if (sampleRate_ <= 0.0) return;
        float sr = static_cast<float>(sampleRate_);
        attackCoeff_ = (attackMs_ <= 0.0f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (attackMs_ * 0.001f * sr));
        releaseCoeff_ = (releaseMs_ <= 0.0f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (releaseMs_ * 0.001f * sr));
    }

    static float clamp(float v, float lo, float hi) {
        return std::max(lo, std::min(hi, v));
    }

    static float dbToLin(float db) {
        return std::pow(10.0f, db * 0.05f);
    }

    static float linToDb(float lin) {
        return (lin > 1e-10f) ? 20.0f * std::log10(lin) : -200.0f;
    }

    // Parameters
    float attackMs_ = 10.0f;
    float releaseMs_ = 100.0f;
    float ratio_ = 4.0f;
    float thresholdDb_ = -18.0f;
    float kneeDb_ = 6.0f;
    float makeupDb_ = 0.0f;
    int mode_ = 0; // 0=RMS, 1=Peak
    float lookaheadMs_ = 0.0f;
    float mix_ = 1.0f; // 0..1 (parameter exposed as 0..100%)

    // State
    EnvelopeFollower envL_, envR_;
    DelayLine delayL_, delayR_;
    int lookaheadSamples_ = 0;
    float gainEnvDb_ = 0.0f;
    float attackCoeff_ = 1.0f;
    float releaseCoeff_ = 1.0f;
};
