#pragma once
#include "snapin_processor.h"
#include "delay_line.h"
#include <cmath>
#include <algorithm>

// Simulates tape slowdown/speedup via variable-rate playback.
class TapeStopProcessor : public SnapinProcessor {
public:
    enum Params {
        PLAY = 0, STOP_TIME, START_TIME, CURVE, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        // 5 seconds buffer for slowdown accumulation
        int maxSamples = static_cast<int>(5.0 * sampleRate) + 16;
        bufL_.prepare(maxSamples);
        bufR_.prepare(maxSamples);
        currentRate_ = 1.0f;
        lag_ = 0.0;
        catchUp_ = 0.0f;
        catchUpStep_ = 1.0f / static_cast<float>(0.02 * sampleRate);
        maxLag_ = static_cast<double>(maxSamples - 8);
        playing_ = true;
    }

    void process(float* left, float* right, int numFrames) override {
        float targetRate = playing_ ? 1.0f : 0.0f;
        float transTimeMs = playing_ ? startTimeMs_ : stopTimeMs_;
        float transTimeSamples = transTimeMs * 0.001f * static_cast<float>(sampleRate_);
        float rateStep = 1.0f / std::max(1.0f, transTimeSamples);

        // Curve: 0% = linear, 100% = exponential
        float curveAmt = curve_ / 100.0f;

        for (int i = 0; i < numFrames; i++) {
            // Write input
            bufL_.write(left[i]);
            bufR_.write(right[i]);

            // Ramp rate towards target
            if (currentRate_ < targetRate) {
                currentRate_ += rateStep;
                if (currentRate_ > targetRate) currentRate_ = targetRate;
            } else if (currentRate_ > targetRate) {
                currentRate_ -= rateStep;
                if (currentRate_ < targetRate) currentRate_ = targetRate;
            }

            // Apply curve shaping to rate
            float shapedRate = currentRate_;
            if (curveAmt > 0.0f) {
                // Exponential curve
                float linear = currentRate_;
                float exponential = (currentRate_ > 0.001f)
                    ? std::pow(currentRate_, 2.0f) : 0.0f;
                shapedRate = linear * (1.0f - curveAmt) + exponential * curveAmt;
            }

            // The read head falls behind the write head by (1 − rate) each
            // sample, so the tape plays slower — and lower — as it stops.
            // (It used to read the newest two samples whatever the speed,
            // so a stop kept its pitch and let the dry signal through.)
            if (shapedRate <= 0.001f) {
                // Stopped: silence. The head is parked; it rejoins live audio
                // when play resumes (below), so the lag never builds up.
                left[i] = 0.0f;
                right[i] = 0.0f;
                continue;
            }
            lag_ += 1.0 - static_cast<double>(shapedRate);
            lag_ = std::min(lag_, maxLag_);

            float outL = bufL_.readLinear(static_cast<float>(lag_));
            float outR = bufR_.readLinear(static_cast<float>(lag_));

            // Back at full speed but still behind: crossfade to live audio
            // over 20 ms rather than keep the delay for the rest of the song.
            if (playing_ && currentRate_ >= 1.0f && lag_ > 0.0) {
                catchUp_ += catchUpStep_;
                if (catchUp_ >= 1.0f) {
                    catchUp_ = 0.0f;
                    lag_ = 0.0;
                    outL = left[i];
                    outR = right[i];
                } else {
                    outL = outL * (1.0f - catchUp_) + left[i] * catchUp_;
                    outR = outR * (1.0f - catchUp_) + right[i] * catchUp_;
                }
            }
            left[i] = outL;
            right[i] = outR;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case PLAY: {
                const bool play = value > 0.5f;
                // Starting from a full stop: the output was silent, so the
                // head can jump back to live audio with nothing to hear.
                if (play && !playing_ && currentRate_ <= 0.001f) lag_ = 0.0;
                playing_ = play;
                break;
            }
            case STOP_TIME:  stopTimeMs_ = std::max(50.0f, std::min(5000.0f, value)); break;
            case START_TIME: startTimeMs_ = std::max(50.0f, std::min(5000.0f, value)); break;
            case CURVE:      curve_ = std::max(0.0f, std::min(100.0f, value)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case PLAY:       return playing_ ? 1.0f : 0.0f;
            case STOP_TIME:  return stopTimeMs_;
            case START_TIME: return startTimeMs_;
            case CURVE:      return curve_;
            default: return 0.0f;
        }
    }

    void reset() override {
        bufL_.reset(); bufR_.reset();
        currentRate_ = 1.0f;
        lag_ = 0.0;
        catchUp_ = 0.0f;
        playing_ = true;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Tape Stop"; }
    SnapinType getType() const override { return SnapinType::TAPE_STOP; }

private:
    bool playing_ = true;
    float stopTimeMs_ = 500.0f;
    float startTimeMs_ = 500.0f;
    float curve_ = 50.0f;

    DelayLine bufL_, bufR_;
    float currentRate_ = 1.0f;
    double lag_ = 0.0;          // samples the read head is behind the write head
    double maxLag_ = 0.0;
    float catchUp_ = 0.0f;      // 0..1 through the crossfade back to live
    float catchUpStep_ = 0.0f;
};
