#pragma once
#include "snapin_processor.h"
#include "delay_line.h"
#include <cmath>
#include <algorithm>
#include <vector>

// Brickwall lookahead limiter with efficient O(1) peak detection per sample
// using a monotone deque (sliding window maximum), raised-cosine attack ramp
// within the lookahead window, and exponential release.
class LimiterProcessor : public SnapinProcessor {
public:
    enum Params { INPUT_GAIN = 0, THRESHOLD, RELEASE, LOOKAHEAD, OUTPUT_GAIN, NUM_PARAMS };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;

        // Max lookahead = 10 ms
        maxLookaheadSamples_ = static_cast<int>(0.010 * sampleRate) + 1;
        lookaheadSamples_ = std::max(1, static_cast<int>(lookaheadMs_ * 0.001f * static_cast<float>(sampleRate)));

        delayL_.prepare(maxLookaheadSamples_);
        delayR_.prepare(maxLookaheadSamples_);
        delayL_.reset();
        delayR_.reset();

        // Monotone deque for sliding window max (stores indices into ring)
        peakRing_.resize(maxLookaheadSamples_ + 1, 0.0f);
        dequeIdx_.resize(maxLookaheadSamples_ + 1, 0);
        ringSize_ = lookaheadSamples_;
        ringWritePos_ = 0;
        dqFront_ = 0;
        dqBack_ = 0;
        globalSampleIdx_ = 0;

        // Gain ramp buffer for the attack envelope within the lookahead window
        gainRamp_.resize(maxLookaheadSamples_ + 1, 1.0f);
        rampWritePos_ = 0;

        gainReductionDb_ = 0.0f;
        releaseCoeff_ = calcReleaseCoeff(releaseMs_);
    }

    bool supportsLinkedDetection() const override { return true; }

    void process(float* left, float* right, int numFrames) override {
        const float inputLin = dbToLin(inputGainDb_);
        const float outputLin = dbToLin(outputGainDb_);
        const float threshLin = dbToLin(thresholdDb_);
        const float invThresh = (threshLin > 1e-20f) ? 1.0f / threshLin : 1e20f;

        for (int i = 0; i < numFrames; i++) {
            // Apply input gain
            float inL = left[i] * inputLin;
            float inR = right[i] * inputLin;

            // Delay the audio signal by lookahead samples
            delayL_.write(inL);
            delayR_.write(inR);
            float delL = delayL_.read(lookaheadSamples_);
            float delR = delayR_.read(lookaheadSamples_);

            // True peak of current input sample (linked stereo)
            // Linked across lanes when a key is set; it takes the input gain
            // the detected signal would have had.
            float peak = key_ ? key_[i] * inputLin : std::max(std::fabs(inL), std::fabs(inR));

            // --- Sliding window maximum via monotone deque (O(1) amortised) ---
            // Write peak into ring buffer
            peakRing_[ringWritePos_] = peak;
            int currentIdx = globalSampleIdx_;

            // Remove elements from back of deque that are <= current peak
            while (dqFront_ != dqBack_) {
                int backPos = dqBack_ - 1;
                if (backPos < 0) backPos += static_cast<int>(dequeIdx_.size());
                int backIdx = dequeIdx_[backPos];
                int ringPos = backIdx % static_cast<int>(peakRing_.size());
                if (peakRing_[ringPos] <= peak) {
                    dqBack_ = backPos;
                } else {
                    break;
                }
            }

            // Push current index
            dequeIdx_[dqBack_] = currentIdx;
            dqBack_ = (dqBack_ + 1) % static_cast<int>(dequeIdx_.size());

            // Remove expired elements from front
            while (dqFront_ != dqBack_) {
                if (currentIdx - dequeIdx_[dqFront_] >= ringSize_) {
                    dqFront_ = (dqFront_ + 1) % static_cast<int>(dequeIdx_.size());
                } else {
                    break;
                }
            }

            // The front of the deque is the max in the window
            float maxPeak = 0.0f;
            if (dqFront_ != dqBack_) {
                int frontIdx = dequeIdx_[dqFront_];
                int frontRingPos = frontIdx % static_cast<int>(peakRing_.size());
                maxPeak = peakRing_[frontRingPos];
            }

            ringWritePos_ = (ringWritePos_ + 1) % static_cast<int>(peakRing_.size());
            globalSampleIdx_++;

            // --- Compute target gain ---
            float targetGainDb = 0.0f;
            if (maxPeak > threshLin && threshLin > 0.0f) {
                targetGainDb = 20.0f * std::log10(threshLin / maxPeak);
            }

            // --- Attack: raised-cosine ramp within lookahead window ---
            // We pre-compute the gain ramp: if new limiting event is deeper than
            // what is already scheduled, we write a raised-cosine envelope into
            // the ramp buffer so the gain smoothly transitions over lookaheadSamples_.
            //
            // For efficiency, we apply the attack directly via the smoothed envelope:
            // instant attack on the gain reduction (since we have lookahead delay to
            // compensate), with the ramp applied as a soft onset.

            if (targetGainDb < gainReductionDb_) {
                // New, deeper limiting event - apply raised cosine ramp
                // across the lookahead window for the difference
                float diff = targetGainDb - gainReductionDb_;
                int rampLen = lookaheadSamples_;
                for (int s = 0; s < rampLen; s++) {
                    // Raised cosine: 0 at s=0, 1 at s=rampLen-1
                    float t = static_cast<float>(s) / static_cast<float>(std::max(1, rampLen - 1));
                    float cosineGain = 0.5f * (1.0f - std::cos(3.14159265358979f * t));
                    int pos = (rampWritePos_ + s) % static_cast<int>(gainRamp_.size());
                    // Blend the new reduction into whatever is already scheduled
                    gainRamp_[pos] = std::min(gainRamp_[pos], gainReductionDb_ + diff * cosineGain);
                }
                gainReductionDb_ = targetGainDb;
            } else {
                // Release
                gainReductionDb_ += releaseCoeff_ * (targetGainDb - gainReductionDb_);
            }

            // Read the gain ramp value for the current output sample
            float rampGainDb = gainRamp_[rampWritePos_];
            // Use the deeper of the ramp and the current envelope
            float appliedGainDb = std::min(rampGainDb, gainReductionDb_);

            // Reset this ramp slot for future use
            gainRamp_[rampWritePos_] = 0.0f;
            rampWritePos_ = (rampWritePos_ + 1) % static_cast<int>(gainRamp_.size());

            float gainLin = dbToLin(appliedGainDb);

            // Apply gain to delayed signal + output gain
            left[i]  = delL * gainLin * outputLin;
            right[i] = delR * gainLin * outputLin;

            // Hard clip at 0dBFS as safety net — never exceed digital full-scale
            left[i]  = std::max(-1.0f, std::min(1.0f, left[i]));
            right[i] = std::max(-1.0f, std::min(1.0f, right[i]));
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case INPUT_GAIN:
                inputGainDb_ = clamp(value, -24.0f, 24.0f);
                break;
            case THRESHOLD:
                thresholdDb_ = clamp(value, -24.0f, 0.0f);
                break;
            case RELEASE:
                releaseMs_ = clamp(value, 1.0f, 1000.0f);
                releaseCoeff_ = calcReleaseCoeff(releaseMs_);
                break;
            case LOOKAHEAD:
                lookaheadMs_ = clamp(value, 1.0f, 10.0f);
                lookaheadSamples_ = std::max(1, static_cast<int>(lookaheadMs_ * 0.001f * static_cast<float>(sampleRate_)));
                ringSize_ = lookaheadSamples_;
                break;
            case OUTPUT_GAIN:
                outputGainDb_ = clamp(value, -24.0f, 24.0f);
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case INPUT_GAIN:  return inputGainDb_;
            case THRESHOLD:   return thresholdDb_;
            case RELEASE:     return releaseMs_;
            case LOOKAHEAD:   return lookaheadMs_;
            case OUTPUT_GAIN: return outputGainDb_;
            default: return 0.0f;
        }
    }

    void reset() override {
        delayL_.reset(); delayR_.reset();
        std::fill(peakRing_.begin(), peakRing_.end(), 0.0f);
        std::fill(dequeIdx_.begin(), dequeIdx_.end(), 0);
        std::fill(gainRamp_.begin(), gainRamp_.end(), 0.0f);
        ringWritePos_ = 0;
        dqFront_ = 0;
        dqBack_ = 0;
        globalSampleIdx_ = 0;
        rampWritePos_ = 0;
        gainReductionDb_ = 0.0f;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Limiter"; }
    SnapinType getType() const override { return SnapinType::LIMITER; }

private:
    float calcReleaseCoeff(float ms) const {
        if (sampleRate_ <= 0.0) return 0.01f;
        return 1.0f - std::exp(-1.0f / (ms * 0.001f * static_cast<float>(sampleRate_)));
    }

    static float clamp(float v, float lo, float hi) {
        return std::max(lo, std::min(hi, v));
    }

    static float dbToLin(float db) {
        return std::pow(10.0f, db * 0.05f);
    }

    // Parameters
    float inputGainDb_ = 0.0f;
    float thresholdDb_ = 0.0f;
    float releaseMs_ = 100.0f;
    float lookaheadMs_ = 5.0f;
    float outputGainDb_ = 0.0f;

    // State
    float releaseCoeff_ = 0.01f;
    float gainReductionDb_ = 0.0f;

    // Delay lines for audio path
    DelayLine delayL_, delayR_;
    int lookaheadSamples_ = 0;
    int maxLookaheadSamples_ = 0;

    // Sliding window max via monotone deque
    std::vector<float> peakRing_;
    std::vector<int> dequeIdx_;
    int ringSize_ = 0;
    int ringWritePos_ = 0;
    int dqFront_ = 0;
    int dqBack_ = 0;
    int globalSampleIdx_ = 0;

    // Gain ramp buffer for raised-cosine attack
    std::vector<float> gainRamp_;
    int rampWritePos_ = 0;
};
