#pragma once
#include "oversampler.h"
#include <cmath>
#include <cstdint>
#include <vector>

// Plugin type enum — matches SnapinType.kt ordinal
enum class SnapinType : int32_t {
    GAIN = 0, STEREO, FILTER, EQ_3BAND, COMPRESSOR, LIMITER,
    GATE, DYNAMICS, COMPACTOR, TRANSIENT_SHAPER, DISTORTION, SHAPER,
    CHORUS, ENSEMBLE, FLANGER, PHASER, DELAY, REVERB,
    BITCRUSH, COMB_FILTER, CHANNEL_MIXER, FORMANT_FILTER,
    FREQUENCY_SHIFTER, HAAS, LADDER_FILTER, NONLINEAR_FILTER,
    PHASE_DISTORTION, PITCH_SHIFTER, RESONATOR, REVERSER,
    RING_MOD, TAPE_STOP, TRANCE_GATE,
    EQ_10BAND, DISPERSER, MISSTORTION,
    COUNT
};

class SnapinProcessor {
public:
    virtual ~SnapinProcessor() = default;

    virtual void prepare(double sampleRate, int maxBlockSize) = 0;
    virtual void process(float* left, float* right, int numFrames) = 0;
    virtual void reset() {}  // Clear internal state (delay lines, filters, envelopes)
    virtual void setParameter(int index, float value) = 0;
    virtual float getParameter(int index) const = 0;
    virtual int getNumParameters() const = 0;
    virtual const char* getName() const = 0;
    virtual SnapinType getType() const = 0;

    bool isBypassed() const { return bypassed_; }
    void setBypassed(bool b) { bypassed_ = b; }

    float getDryWet() const { return dryWet_; }
    void setDryWet(float v) {
        const float safe = std::isfinite(v) ? v : 1.0f;
        dryWet_ = (safe < 0.0f) ? 0.0f : (safe > 1.0f) ? 1.0f : safe;
    }

    // ── Per-snapin oversampling (1x/2x/4x) ──────────────────────────────
    // The engine calls prepareOS/processOS instead of prepare/process. With a
    // factor > 1 the snapin itself is prepared at baseRate × factor and runs
    // between a ChannelOversampler pair, so nonlinear effects fold harmonics
    // above the audio band instead of aliasing into it. Factor 1 is a direct
    // pass-through with zero overhead. Never call on the audio thread while
    // unlocked — the engine serializes via its chain mutex.

    void prepareOS(double baseSampleRate, int maxBlockSize) {
        osBaseRate_ = baseSampleRate;
        osBaseBlock_ = maxBlockSize;
        applyOS();
    }

    void setOversampling(int factor) {
        const int f = factor >= 4 ? 4 : (factor >= 2 ? 2 : 1);
        if (f == osFactor_) return;
        osFactor_ = f;
        // Re-prepare at the new internal rate (resets effect state, same as a
        // sample-rate change).
        if (osBaseRate_ > 0.0) applyOS();
    }

    int getOversampling() const { return osFactor_; }

    void processOS(float* left, float* right, int numFrames) {
        if (osFactor_ <= 1 || osBufL_.empty()) {
            process(left, right, numFrames);
            return;
        }
        osL_.upsample(left, osBufL_.data(), numFrames);
        osR_.upsample(right, osBufR_.data(), numFrames);
        process(osBufL_.data(), osBufR_.data(), numFrames * osFactor_);
        osL_.downsample(osBufL_.data(), left, numFrames);
        osR_.downsample(osBufR_.data(), right, numFrames);
    }

protected:
    double sampleRate_ = 44100.0;
    int maxBlockSize_ = 512;
    bool bypassed_ = false;
    float dryWet_ = 1.0f;  // 0 = fully dry, 1 = fully wet

private:
    void applyOS() {
        if (osFactor_ > 1) {
            osBufL_.assign(static_cast<size_t>(osBaseBlock_) * osFactor_, 0.0f);
            osBufR_.assign(static_cast<size_t>(osBaseBlock_) * osFactor_, 0.0f);
            osL_.prepare(osBaseRate_, osFactor_);
            osR_.prepare(osBaseRate_, osFactor_);
        } else {
            osBufL_.clear();
            osBufR_.clear();
        }
        prepare(osBaseRate_ * osFactor_, osBaseBlock_ * osFactor_);
    }

    int osFactor_ = 1;
    double osBaseRate_ = 0.0;
    int osBaseBlock_ = 0;
    std::vector<float> osBufL_, osBufR_;
    ChannelOversampler osL_, osR_;
};

// Factory — implemented in dsp_engine.cpp
SnapinProcessor* createSnapin(SnapinType type);
