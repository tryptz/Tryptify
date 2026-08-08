#pragma once
#include "snapin_processor.h"
#include "biquad.h"
#include "dc_blocker.h"
#include "envelope_follower.h"
#include <cmath>
#include <algorithm>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Versatile distortion/saturation with 6 waveshaper types, post-distortion
// tone control, envelope-following drive, and stereo spread. Anti-aliasing
// comes from the per-snapin oversampling wrapper in SnapinProcessor.
class DistortionProcessor : public SnapinProcessor {
public:
    enum Params {
        DRIVE = 0,      // 0-48 dB
        TYPE,           // 0-5: soft clip, hard clip, tanh sat, foldback, rectify, asymmetric
        TONE,           // 200-20000 Hz lowpass post-filter
        BIAS,           // -1 to 1
        DYNAMICS,       // 0-100% envelope-following drive
        SPREAD,         // 0-100% stereo drive offset
        OUTPUT,         // -24 to 0 dB trim
        MIX,            // 0-100%
        NUM_PARAMS
    };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;

        dcL_.prepare(sampleRate);
        dcR_.prepare(sampleRate);
        dcL_.reset();
        dcR_.reset();

        envL_.prepare(sampleRate);
        envR_.prepare(sampleRate);
        envL_.setAttack(5.0f);
        envL_.setRelease(50.0f);
        envR_.setAttack(5.0f);
        envR_.setRelease(50.0f);
        envL_.reset();
        envR_.reset();

        updateToneFilter();
    }

    void process(float* left, float* right, int numFrames) override {
        if (paramsDirty_) {
            updateToneFilter();
            paramsDirty_ = false;
        }

        float baseDriveLin = std::pow(10.0f, driveDb_ / 20.0f);
        float outputLin = std::pow(10.0f, outputDb_ / 20.0f);
        float spreadAmt = spread_ * 0.5f;

        for (int i = 0; i < numFrames; i++) {
            float dryL = left[i];
            float dryR = right[i];

            // Envelope-following drive modulation
            float envValL = envL_.process(dryL);
            float envValR = envR_.process(dryR);
            float driveLnL = baseDriveLin * (1.0f + dynamics_ * envValL * 4.0f);
            float driveLnR = baseDriveLin * (1.0f + dynamics_ * envValR * 4.0f);

            // Stereo spread: offset drive between channels
            float driveL = driveLnL * (1.0f + spreadAmt);
            float driveR = driveLnR * (1.0f - spreadAmt);

            // Apply drive + bias
            float wetL = dryL * driveL + bias_;
            float wetR = dryR * driveR + bias_;

            // Anti-aliasing is the per-snapin oversampling wrapper's job
            // (SnapinProcessor::processOS, Off/2x/4x from the FX chain UI),
            // which actually raises the rate through a filter pair. What used
            // to sit here — shape the sample, shape it again with 5% of the
            // first result mixed in, average the two — was labelled "2x
            // oversampling" but never changed the rate, so it could not remove
            // an alias product; all it did was soften the transfer function
            // away from the curve the user picked, and it kept doing that
            // inside the real oversampler when that was switched on.
            wetL = waveshape(wetL);
            wetR = waveshape(wetR);

            // DC blocker
            wetL = dcL_.process(wetL);
            wetR = dcR_.process(wetR);

            // Post-distortion tone control (lowpass)
            wetL = toneL_.process(wetL);
            wetR = toneR_.process(wetR);

            // Output trim
            wetL *= outputLin;
            wetR *= outputLin;

            // Dry/wet mix
            left[i]  = dryL * (1.0f - mix_) + wetL * mix_;
            right[i] = dryR * (1.0f - mix_) + wetR * mix_;
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case DRIVE:    driveDb_  = std::max(0.0f, std::min(48.0f, value)); break;
            case TYPE:     type_     = static_cast<int>(std::max(0.0f, std::min(5.0f, value))); break;
            case TONE:     toneFreq_ = std::max(200.0f, std::min(20000.0f, value)); paramsDirty_ = true; break;
            case BIAS:     bias_     = std::max(-1.0f, std::min(1.0f, value)); break;
            case DYNAMICS: dynamics_ = std::max(0.0f, std::min(1.0f, value / 100.0f)); break;
            case SPREAD:   spread_   = std::max(0.0f, std::min(1.0f, value / 100.0f)); break;
            case OUTPUT:   outputDb_ = std::max(-24.0f, std::min(0.0f, value)); break;
            case MIX:      mix_      = std::max(0.0f, std::min(1.0f, value / 100.0f)); break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case DRIVE:    return driveDb_;
            case TYPE:     return static_cast<float>(type_);
            case TONE:     return toneFreq_;
            case BIAS:     return bias_;
            case DYNAMICS: return dynamics_ * 100.0f;
            case SPREAD:   return spread_ * 100.0f;
            case OUTPUT:   return outputDb_;
            case MIX:      return mix_ * 100.0f;
            default: return 0.0f;
        }
    }

    void reset() override {
        dcL_.reset(); dcR_.reset();
        toneL_.reset(); toneR_.reset();
        envL_.reset(); envR_.reset();
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Distortion"; }
    SnapinType getType() const override { return SnapinType::DISTORTION; }

private:
    float waveshape(float x) const {
        switch (type_) {
            case 0: {
                // Soft clip (cubic): x - x^3/3 for |x| <= 1, clamped beyond
                float ax = std::fabs(x);
                if (ax > 1.5f) return x > 0.0f ? 1.0f : -1.0f;
                if (ax > 1.0f) {
                    float t = (3.0f - (2.0f - ax) * (2.0f - ax)) / 3.0f;
                    return x > 0.0f ? t : -t;
                }
                return x - (x * x * x) / 3.0f;
            }
            case 1:
                // Hard clip
                return std::max(-1.0f, std::min(1.0f, x));
            case 2:
                // Tanh saturation
                return std::tanh(x);
            case 3:
                // Foldback (sine wavefolder)
                return std::sin(x * static_cast<float>(M_PI));
            case 4:
                // Full-wave rectify
                return std::fabs(x);
            case 5: {
                // Asymmetric (tube-like odd+even harmonics)
                // Positive half gets softer clipping, negative half gets harder
                if (x >= 0.0f) {
                    return 1.0f - std::exp(-x);
                } else {
                    return -std::tanh(-x * 1.5f);
                }
            }
            default:
                return std::tanh(x);
        }
    }

    void updateToneFilter() {
        if (sampleRate_ <= 0.0) return;
        toneL_.configure(BiquadType::LowPass, sampleRate_, toneFreq_, 0.707);
        toneR_.configure(BiquadType::LowPass, sampleRate_, toneFreq_, 0.707);
    }

    // Parameters
    float driveDb_  = 12.0f;
    int   type_     = 0;
    float toneFreq_ = 8000.0f;
    float bias_     = 0.0f;
    float dynamics_ = 0.0f;
    float spread_   = 0.0f;
    float outputDb_ = 0.0f;
    float mix_      = 1.0f;
    bool  paramsDirty_ = true;

    // DSP components
    DcBlocker dcL_, dcR_;
    Biquad toneL_, toneR_;
    EnvelopeFollower envL_, envR_;
};
