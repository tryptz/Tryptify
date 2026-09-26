#pragma once
#include "finite.h"
#include "oversampler.h"
#include <algorithm>
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
        const float safe = dspIsFinite(v) ? v : 1.0f;
        dryWet_ = (safe < 0.0f) ? 0.0f : (safe > 1.0f) ? 1.0f : safe;
    }

    // Sets parameter [index] from anywhere a value can come from — a knob, a
    // preset, a saved mix. The effects clamp their own ranges, but not every
    // one survives NaN (a float-to-int cast of it is undefined), and an index
    // past the end must not reach a switch that assumes it cannot happen.
    void applyParameter(int index, float value) {
        if (index < 0 || index >= getNumParameters() || !dspIsFinite(value)) return;
        setParameter(index, std::fmax(-1e7f, std::fmin(1e7f, value)));
    }

    // ── Per-snapin oversampling (1x/2x/4x) ──────────────────────────────
    // The engine calls prepareOS/processOS instead of prepare/process. With a
    // factor > 1 the snapin itself is prepared at baseRate × factor and runs
    // between a ChannelOversampler pair, so nonlinear effects fold harmonics
    // above the audio band instead of aliasing into it. Factor 1 is a direct
    // pass-through with zero overhead.
    //
    // The factor asked for is what is saved and shown; the one that runs is
    // capped by the rate (ChannelOversampler::effectiveFactor) — a hi-res
    // stream at 192 kHz has the headroom 4x would buy already, and 4x of it
    // would run every delay line and reverb at 768 kHz.
    //
    // The round trip delays the effect's output by latency() samples, the
    // same at every frequency. The engine holds the dry signal back to match
    // (delayDry) and lines the other buses up with it, so an oversampled
    // effect blended with its dry, or summed with another bus, never
    // comb-filters. Never call on the audio thread while unlocked — the
    // engine serializes via its chain mutex.

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

    // The factor asked for (and saved): 1, 2 or 4.
    int getOversampling() const { return osFactor_; }

    // The factor running at the current rate.
    int getEffectiveOversampling() const { return osRun_; }

    // Base-rate samples the effect's output lags its input by.
    int latency() const { return osLatency_; }

    // Holds [left]/[right] back by latency(), in place: the dry signal of a
    // blend, or the whole signal while the effect is bypassed, so a bus's
    // delay does not change with the bypass switch.
    void delayDry(float* left, float* right, int numFrames) {
        if (osLatency_ == 0) return;
        dryL_.process(left, numFrames);
        dryR_.process(right, numFrames);
    }

    // Clears everything the effect remembers, the oversampling filters with
    // it: for when its output can no longer be trusted.
    void resetAll() {
        reset();
        osL_.reset();
        osR_.reset();
    }

    // ── Linked detection ─────────────────────────────────────────────────
    // Dynamics that measure level (compressor, limiter, dynamics, compactor,
    // gate) can take their detector from an outside key instead of their own
    // left/right: one non-negative sample per frame. A multichannel host
    // hands every lane's instance the same key — the loudest of all lanes —
    // so the whole bed is turned down together instead of lane by lane.
    // The key is set around one process call and cleared after; null means
    // detect on the signal, exactly as before.
    virtual bool supportsLinkedDetection() const { return false; }
    void setDetectorKey(const float* key) { key_ = key; }

    void processOS(float* left, float* right, int numFrames) {
        if (osRun_ <= 1 || osBufL_.empty()) {
            process(left, right, numFrames);
            return;
        }
        // The scratch holds osChunk_ frames at the high rate; a longer block
        // goes through in pieces rather than past its end.
        for (int done = 0; done < numFrames; done += osChunk_) {
            const int n = std::min(osChunk_, numFrames - done);
            processOSChunk(left + done, right + done, n,
                           key_ ? key_ + done : nullptr);
        }
    }

protected:
    double sampleRate_ = 44100.0;
    int maxBlockSize_ = 512;
    bool bypassed_ = false;
    float dryWet_ = 1.0f;  // 0 = fully dry, 1 = fully wet
    const float* key_ = nullptr;  // see setDetectorKey

private:
    void processOSChunk(float* left, float* right, int numFrames, const float* baseKey) {
        // At the oversampled rate the key has to be too: each base-rate
        // sample held for the factor's worth of frames. A peak key is a
        // level, not a waveform, so holding it is what it means.
        const float* savedKey = key_;
        if (baseKey && !osKey_.empty()) {
            for (int i = 0; i < numFrames; i++) {
                for (int k = 0; k < osRun_; k++) osKey_[static_cast<size_t>(i * osRun_ + k)] = baseKey[i];
            }
            key_ = osKey_.data();
        }
        osL_.upsample(left, osBufL_.data(), numFrames);
        osR_.upsample(right, osBufR_.data(), numFrames);
        process(osBufL_.data(), osBufR_.data(), numFrames * osRun_);
        osL_.downsample(osBufL_.data(), left, numFrames);
        osR_.downsample(osBufR_.data(), right, numFrames);
        key_ = savedKey;
    }

    void applyOS() {
        osRun_ = ChannelOversampler::effectiveFactor(osBaseRate_, osFactor_);
        osBaseBlock_ = std::max(1, osBaseBlock_);
        // The engine is prepared for blocks of up to 16384 frames; scratch
        // for that at 4x is 768 KB per effect per lane, and a wide stream has
        // a lane per channel group. Pieces of kOsChunk cost nothing audible
        // (the filters carry their state across) and keep it to ~48 KB.
        osChunk_ = std::min(osBaseBlock_, kOsChunk);
        if (osRun_ > 1) {
            osBufL_.assign(static_cast<size_t>(osChunk_) * osRun_, 0.0f);
            osBufR_.assign(static_cast<size_t>(osChunk_) * osRun_, 0.0f);
            osKey_.assign(static_cast<size_t>(osChunk_) * osRun_, 0.0f);
            osL_.prepare(osBaseRate_, osRun_);
            osR_.prepare(osBaseRate_, osRun_);
        } else {
            osBufL_.clear();
            osBufR_.clear();
            osKey_.clear();
            osL_.prepare(osBaseRate_, 1);
            osR_.prepare(osBaseRate_, 1);
        }
        osLatency_ = osL_.latency();
        dryL_.prepare(osLatency_);
        dryR_.prepare(osLatency_);
        // Oversampled, the effect only ever sees one piece at a time.
        prepare(osBaseRate_ * osRun_, osRun_ > 1 ? osChunk_ * osRun_ : osBaseBlock_);
    }

    static constexpr int kOsChunk = 1024;

    int osFactor_ = 1;   // asked for
    int osRun_ = 1;      // running, after the rate cap
    int osLatency_ = 0;
    double osBaseRate_ = 0.0;
    int osBaseBlock_ = 0;
    int osChunk_ = 1;
    std::vector<float> osBufL_, osBufR_, osKey_;
    ChannelOversampler osL_, osR_;
    LatencyLine dryL_, dryR_;
};

// Factory — implemented in dsp_engine.cpp
SnapinProcessor* createSnapin(SnapinType type);
