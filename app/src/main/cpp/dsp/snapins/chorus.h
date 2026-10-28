#pragma once
#include "snapin_processor.h"
#include "delay_line.h"
#include "lfo.h"
#include <cmath>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Modern multi-voice chorus with staggered LFO phases, stereo spread, and feedback.
class ChorusProcessor : public SnapinProcessor {
public:
    enum Params {
        DELAY_MS = 0,   // 1-40 ms base delay
        RATE,           // 0.01-10 Hz
        DEPTH,          // 0-100%
        VOICES,         // 1-8 integer
        SPREAD,         // 0-100% stereo
        FEEDBACK,       // 0-50%
        MIX,            // 0-100%
        NUM_PARAMS
    };

    static constexpr int MAX_VOICES = 8;

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;

        // The furthest a voice reads back: the 40 ms base, plus a voice's
        // offset of up to 5 % of it, plus at 100 % depth an LFO swing of the
        // whole base again: 2.05 × 40 ms = 82 ms. Sized for 80 ms, the line
        // was too short for the extreme by 2 ms, and those reads wrapped round
        // into the newest audio. The 64 covers the cubic's outer taps.
        int maxSamples = static_cast<int>(0.082 * sampleRate) + 64;
        for (int v = 0; v < MAX_VOICES; v++) {
            delayL_[v].prepare(maxSamples);
            delayR_[v].prepare(maxSamples);
            lfo_[v].prepare(sampleRate);
            lfo_[v].setShape(LfoShape::Sine);
        }
        updateLfos();
    }

    void process(float* left, float* right, int numFrames) override {
        const float baseDelaySamples = delayMs_ * 0.001f * static_cast<float>(sampleRate_);
        const float depthSamples = baseDelaySamples * (depth_ * 0.01f);
        const int voices = std::max(1, std::min(MAX_VOICES, voices_));
        const float fb = feedback_ * 0.01f;
        const float m = mix_ * 0.01f;
        const float spreadNorm = spread_ * 0.01f;
        const float norm = 1.0f / std::sqrt(static_cast<float>(voices));

        for (int i = 0; i < numFrames; i++) {
            const float dryL = left[i];
            const float dryR = right[i];
            float wetL = 0.0f;
            float wetR = 0.0f;

            for (int v = 0; v < voices; v++) {
                // Input to delay: dry signal + feedback through first voice only
                float inputL = dryL;
                float inputR = dryR;
                if (v == 0) {
                    inputL += feedbackL_ * fb;
                    inputR += feedbackR_ * fb;
                }

                delayL_[v].write(inputL);
                delayR_[v].write(inputR);

                // Per-voice base delay offset for richness
                float voiceOffset = baseDelaySamples * 0.1f *
                    (static_cast<float>(v) / std::max(1.0f, static_cast<float>(voices - 1)) - 0.5f);
                if (voices == 1) voiceOffset = 0.0f;

                // Modulated delay read position
                float mod = lfo_[v].next();
                float readPos = baseDelaySamples + voiceOffset + mod * depthSamples;
                readPos = std::max(1.0f, readPos);

                float tapL = delayL_[v].readCubic(readPos);
                float tapR = delayR_[v].readCubic(readPos);

                // Equal-power stereo panning per voice
                float panPos;
                if (voices == 1) {
                    panPos = 0.5f; // center
                } else {
                    // Distribute voices across stereo field based on spread
                    float voicePos = static_cast<float>(v) / static_cast<float>(voices - 1); // 0..1
                    panPos = 0.5f + spreadNorm * (voicePos - 0.5f);
                }
                float panAngle = panPos * static_cast<float>(M_PI) * 0.5f;
                float gainL = std::cos(panAngle);
                float gainR = std::sin(panAngle);

                wetL += tapL * gainL;
                wetR += tapR * gainR;

                // Store first voice output for feedback
                if (v == 0) {
                    feedbackL_ = tapL;
                    feedbackR_ = tapR;
                }
            }

            // Normalize by sqrt(voices) for equal energy
            wetL *= norm;
            wetR *= norm;

            // Mix dry/wet
            left[i]  = dryL * (1.0f - m) + wetL * m;
            right[i] = dryR * (1.0f - m) + wetR * m;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case DELAY_MS:
                delayMs_ = std::max(1.0f, std::min(40.0f, value));
                break;
            case RATE:
                rate_ = std::max(0.01f, std::min(10.0f, value));
                updateLfos();
                break;
            case DEPTH:
                depth_ = std::max(0.0f, std::min(100.0f, value));
                break;
            case VOICES:
                voices_ = static_cast<int>(std::max(1.0f, std::min(8.0f, value)));
                updateLfos();
                break;
            case SPREAD:
                spread_ = std::max(0.0f, std::min(100.0f, value));
                break;
            case FEEDBACK:
                feedback_ = std::max(0.0f, std::min(50.0f, value));
                break;
            case MIX:
                mix_ = std::max(0.0f, std::min(100.0f, value));
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case DELAY_MS: return delayMs_;
            case RATE:     return rate_;
            case DEPTH:    return depth_;
            case VOICES:   return static_cast<float>(voices_);
            case SPREAD:   return spread_;
            case FEEDBACK: return feedback_;
            case MIX:      return mix_;
            default:       return 0.0f;
        }
    }

    void reset() override {
        for (int v = 0; v < MAX_VOICES; v++) {
            delayL_[v].reset();
            delayR_[v].reset();
            lfo_[v].reset();
        }
        feedbackL_ = 0.0f;
        feedbackR_ = 0.0f;
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Chorus"; }
    SnapinType getType() const override { return SnapinType::CHORUS; }

private:
    void updateLfos() {
        for (int v = 0; v < MAX_VOICES; v++) {
            lfo_[v].setRate(rate_);
            // Stagger LFO phases: 360/voices degrees apart
            float phaseOffset = 360.0f * static_cast<float>(v) / static_cast<float>(std::max(1, voices_));
            lfo_[v].setPhaseOffset(phaseOffset);
        }
    }

    // Parameters
    float delayMs_ = 7.0f;
    float rate_ = 1.0f;
    float depth_ = 50.0f;
    int voices_ = 3;
    float spread_ = 50.0f;
    float feedback_ = 0.0f;
    float mix_ = 50.0f;

    // DSP state
    DelayLine delayL_[MAX_VOICES], delayR_[MAX_VOICES];
    Lfo lfo_[MAX_VOICES];
    float feedbackL_ = 0.0f, feedbackR_ = 0.0f;
};
