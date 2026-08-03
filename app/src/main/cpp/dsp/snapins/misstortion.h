#pragma once
#include "snapin_processor.h"
#include "biquad.h"
#include <algorithm>
#include <cmath>

// Asymmetric clip distortion in the style of Logic's Clip Distortion — the
// hardstyle-kick staple. Signal chain (mirroring the classic Misstortion 1
// processing order): input gain → tone high-pass → hard clip
// (clamp(-1..1, x·driveHard)) → hyperbolic soft clip (atan(x·driveSoft),
// clamped into the asymmetric window [-1+sym, sym]) → symmetry multiply
// (negative half × (1−sym), positive half × sym) → clip low-pass → dry/wet →
// output gain. Filter modes: 0 = legacy 2nd-order with Q = √64/63 ≈ 0.127
// (the characteristic gentle tone of the original), 1 = 6 dB/oct one-pole,
// 2 = 12 dB/oct Butterworth.
class MisstortionProcessor : public SnapinProcessor {
public:
    enum Params {
        GAIN_IN = 0, DRIVE_HARD, DRIVE_SOFT, SYMMETRY,
        TONE_HP, TONE_LP, FILTER_MODE, GAIN_OUT, MIX, NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        updateFilters();
        reset();
    }

    void process(float* left, float* right, int numFrames) override {
        const float gainIn = dbToGainFloored(gainInDb_);
        const float gainOut = dbToGainFloored(gainOutDb_);
        const float driveHard = std::pow(10.0f, driveHardDb_ / 20.0f);
        const float driveSoft = std::pow(10.0f, driveSoftDb_ / 20.0f);
        const float sym = symmetry_;
        const float mix = mix_ / 100.0f;
        const bool hpOn = toneHp_ >= 1.0f;

        for (int i = 0; i < numFrames; i++) {
            float dryL = left[i];
            float dryR = right[i];
            float l = dryL * gainIn;
            float r = dryR * gainIn;

            if (hpOn) {
                l = hpProcess(0, l);
                r = hpProcess(1, r);
            }

            l = shape(l, driveHard, driveSoft, sym);
            r = shape(r, driveHard, driveSoft, sym);

            l = lpProcess(0, l);
            r = lpProcess(1, r);

            left[i]  = (dryL + (l - dryL) * mix) * gainOut;
            right[i] = (dryR + (r - dryR) * mix) * gainOut;
        }
    }

    void reset() override {
        for (int c = 0; c < 2; c++) {
            hpLegacy_[c].reset();
            lpLegacy_[c].reset();
            hpBq_[c].reset();
            lpBq_[c].reset();
            onePoleHpState_[c] = 0.0f;
            onePoleLpState_[c] = 0.0f;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case GAIN_IN:   gainInDb_   = std::max(-50.0f, std::min(50.0f, value)); break;
            case DRIVE_HARD: driveHardDb_ = std::max(0.0f, std::min(50.0f, value)); break;
            case DRIVE_SOFT: driveSoftDb_ = std::max(0.0f, std::min(50.0f, value)); break;
            case SYMMETRY:  symmetry_ = std::max(0.0f, std::min(100.0f, value)) / 100.0f; break;
            case TONE_HP:   toneHp_ = std::max(0.0f, std::min(20000.0f, value)); updateFilters(); break;
            case TONE_LP:   toneLp_ = std::max(1.0f, std::min(20000.0f, value)); updateFilters(); break;
            case FILTER_MODE:
                filterMode_ = static_cast<int>(std::max(0.0f, std::min(2.0f, value)));
                updateFilters();
                break;
            case GAIN_OUT:  gainOutDb_ = std::max(-50.0f, std::min(50.0f, value)); break;
            case MIX:       mix_ = std::max(0.0f, std::min(100.0f, value)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case GAIN_IN:    return gainInDb_;
            case DRIVE_HARD: return driveHardDb_;
            case DRIVE_SOFT: return driveSoftDb_;
            case SYMMETRY:   return symmetry_ * 100.0f;
            case TONE_HP:    return toneHp_;
            case TONE_LP:    return toneLp_;
            case FILTER_MODE: return static_cast<float>(filterMode_);
            case GAIN_OUT:   return gainOutDb_;
            case MIX:        return mix_;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Misstortion"; }
    SnapinType getType() const override { return SnapinType::MISSTORTION; }

private:
    // ±50 dB gain with a hard floor: −50 dB and below is silence.
    static float dbToGainFloored(float db) {
        return (db <= -50.0f) ? 0.0f : std::pow(10.0f, db / 20.0f);
    }

    // Hard clip → soft clip into the asymmetric window → symmetry multiply.
    static float shape(float s, float driveHard, float driveSoft, float sym) {
        if (driveHard > 1.0f) {
            s = std::max(-1.0f, std::min(1.0f, s * driveHard));
        }
        if (driveSoft > 1.0f) {
            s = std::atan(s * driveSoft);
            s = std::max(-1.0f + sym, std::min(sym, s));
        }
        if (s < 0.0f) s *= (1.0f - sym);
        else          s *= sym;
        return s;
    }

    void updateFilters() {
        if (sampleRate_ <= 0.0) return;
        // Legacy tone: 2nd-order with the original's odd Q = √(2^6)/(2^6 − 1).
        const double legacyQ = std::sqrt(64.0) / (64.0 - 1.0);
        double hp = std::max(1.0, std::min(static_cast<double>(toneHp_), sampleRate_ * 0.45));
        double lp = std::max(1.0, std::min(static_cast<double>(toneLp_), sampleRate_ * 0.45));
        for (int c = 0; c < 2; c++) {
            hpLegacy_[c].configure(BiquadType::HighPass, sampleRate_, hp, legacyQ);
            lpLegacy_[c].configure(BiquadType::LowPass, sampleRate_, lp, legacyQ);
            hpBq_[c].configure(BiquadType::HighPass, sampleRate_, hp, 0.7071);
            lpBq_[c].configure(BiquadType::LowPass, sampleRate_, lp, 0.7071);
        }
        onePoleHpCoeff_ = 1.0f - std::exp(-2.0 * M_PI * hp / sampleRate_);
        onePoleLpCoeff_ = 1.0f - std::exp(-2.0 * M_PI * lp / sampleRate_);
    }

    float hpProcess(int c, float x) {
        switch (filterMode_) {
            case 1: {  // 6 dB/oct: x minus its one-pole low-passed copy
                onePoleHpState_[c] += onePoleHpCoeff_ * (x - onePoleHpState_[c]);
                return x - onePoleHpState_[c];
            }
            case 2:  return hpBq_[c].process(x);
            default: return hpLegacy_[c].process(x);
        }
    }

    float lpProcess(int c, float x) {
        switch (filterMode_) {
            case 1: {
                onePoleLpState_[c] += onePoleLpCoeff_ * (x - onePoleLpState_[c]);
                return onePoleLpState_[c];
            }
            case 2:  return lpBq_[c].process(x);
            default: return lpLegacy_[c].process(x);
        }
    }

    Biquad hpLegacy_[2], lpLegacy_[2];
    Biquad hpBq_[2], lpBq_[2];
    float onePoleHpState_[2] = {};
    float onePoleLpState_[2] = {};
    float onePoleHpCoeff_ = 1.0f;
    float onePoleLpCoeff_ = 1.0f;

    float gainInDb_ = 0.0f;
    float driveHardDb_ = 0.0f;
    float driveSoftDb_ = 0.0f;
    float symmetry_ = 0.5f;
    float toneHp_ = 20.0f;
    float toneLp_ = 20000.0f;
    int filterMode_ = 0;
    float gainOutDb_ = 0.0f;
    float mix_ = 100.0f;
};
