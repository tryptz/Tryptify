#pragma once
#include "snapin_processor.h"
#include "parameter_smoother.h"
#include <cmath>

class StereoProcessor : public SnapinProcessor {
public:
    enum Params { MID_DB = 0, WIDTH_DB, PAN, NUM_PARAMS };

    void prepare(double sampleRate, int maxBlockSize) override {
        sampleRate_ = sampleRate;
        maxBlockSize_ = maxBlockSize;
        midSmooth_.prepare(sampleRate, 5.0);
        widthSmooth_.prepare(sampleRate, 5.0);
        panSmooth_.prepare(sampleRate, 5.0);
        midSmooth_.reset(1.0f);
        widthSmooth_.reset(1.0f);
        panSmooth_.reset(0.0f);
    }

    void process(float* left, float* right, int numFrames) override {
        for (int i = 0; i < numFrames; i++) {
            float midGain = midSmooth_.next();
            float sideGain = widthSmooth_.next();
            float pan = panSmooth_.next();

            // M/S encode
            float mid = (left[i] + right[i]) * 0.5f;
            float side = (left[i] - right[i]) * 0.5f;

            // Apply gains
            mid *= midGain;
            side *= sideGain;

            // M/S decode
            float l = mid + side;
            float r = mid - side;

            // Balance: centre leaves both sides at unity, and panning only
            // turns the far side down (equal-power, reaching silence at ±1).
            // An equal-power pan law here cost 3 dB at centre, so the effect
            // at its defaults was not transparent.
            const float a = std::fabs(pan) * 1.5707963f;
            left[i]  = l * (pan > 0.0f ? std::cos(a) : 1.0f);
            right[i] = r * (pan < 0.0f ? std::cos(a) : 1.0f);
        }
    }

    void setParameter(int index, float value) override {
        switch (index) {
            case MID_DB:
                midDb_ = value;
                midSmooth_.setTarget(std::pow(10.0f, value / 20.0f));
                break;
            case WIDTH_DB:
                widthDb_ = value;
                widthSmooth_.setTarget(std::pow(10.0f, value / 20.0f));
                break;
            case PAN:
                pan_ = std::max(-1.0f, std::min(1.0f, value));
                panSmooth_.setTarget(pan_);
                break;
        }
    }

    float getParameter(int index) const override {
        switch (index) {
            case MID_DB: return midDb_;
            case WIDTH_DB: return widthDb_;
            case PAN: return pan_;
            default: return 0.0f;
        }
    }

    int getNumParameters() const override { return NUM_PARAMS; }
    const char* getName() const override { return "Stereo"; }
    SnapinType getType() const override { return SnapinType::STEREO; }

private:
    float midDb_ = 0.0f;
    float widthDb_ = 0.0f;
    float pan_ = 0.0f;
    ParameterSmoother midSmooth_;
    ParameterSmoother widthSmooth_;
    ParameterSmoother panSmooth_;
};
