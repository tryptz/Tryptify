#pragma once
#include "biquad.h"
#include <vector>
#include <cstring>
#include <cmath>

// Simple 2x oversampler with half-band anti-alias filter.
class Oversampler {
public:
    void prepare(int maxBlockSize) {
        upBuf_.resize(maxBlockSize * 2, 0.0f);
        downBuf_.resize(maxBlockSize, 0.0f);
        std::memset(h_, 0, sizeof(h_));
    }

    void reset() {
        std::memset(h_, 0, sizeof(h_));
    }

    // Upsample: insert zeros between samples, then filter
    float* upsample(const float* input, int numFrames) {
        for (int i = 0; i < numFrames; i++) {
            upBuf_[i * 2] = input[i] * 2.0f;
            upBuf_[i * 2 + 1] = 0.0f;
        }
        // Simple 5-tap half-band FIR: [0.0625, 0.25, 0.375, 0.25, 0.0625]
        for (int i = 0; i < numFrames * 2; i++) {
            float x = upBuf_[i];
            float y = 0.0625f * h_[3] + 0.25f * h_[2] + 0.375f * h_[1] + 0.25f * h_[0] + 0.0625f * x;
            h_[3] = h_[2]; h_[2] = h_[1]; h_[1] = h_[0]; h_[0] = x;
            upBuf_[i] = y;
        }
        return upBuf_.data();
    }

    // Downsample: filter then decimate by 2
    float* downsample(const float* input, int numFrames2x) {
        int outFrames = numFrames2x / 2;
        // Apply same filter
        for (int i = 0; i < numFrames2x; i++) {
            float x = input[i];
            float y = 0.0625f * dh_[3] + 0.25f * dh_[2] + 0.375f * dh_[1] + 0.25f * dh_[0] + 0.0625f * x;
            dh_[3] = dh_[2]; dh_[2] = dh_[1]; dh_[1] = dh_[0]; dh_[0] = x;
            if (i % 2 == 0) {
                downBuf_[i / 2] = y;
            }
        }
        return downBuf_.data();
    }

    int getOversampleFactor() const { return 2; }

private:
    std::vector<float> upBuf_;
    std::vector<float> downBuf_;
    float h_[4] = {};
    float dh_[4] = {};
};

// N-times oversampler (2x/4x) for one audio channel, used by the per-snapin
// oversampling wrapper in SnapinProcessor and by the Inflator's waveshaper:
// zero-stuff + 8th-order Butterworth anti-image filter on the way up, matching
// anti-alias filter + decimation on the way down. Both filters sit at 0.9 ×
// the base Nyquist, so nonlinear stages fold harmonics far above the audio
// band instead of aliasing back into it.
//
// 4x is two cascaded 2x stages, not a single 4x zero-stuff. The Butterworth is
// deliberately gentle, and across a single 4x step it leaves enough of the
// zero-stuffing images that a following nonlinearity folds them straight back
// down — measured on the Inflator at 44.1 kHz with a 9 kHz tone, one-step 4x
// scored -39.6 dBc against 2x's -52.7, i.e. the higher setting sounded worse.
// Cascading gives each filter only one octave to cover, and 4x then measures
// at or below 2x (-52.5 dBc broadband) as it should.
class ChannelOversampler {
public:
    void prepare(double baseRate, int factor) {
        factor_ = factor >= 4 ? 4 : (factor >= 2 ? 2 : 1);
        if (factor_ <= 1 || baseRate <= 0.0) { reset(); return; }
        configureStage(s1_, baseRate);
        // The second stage runs an octave up, where the first stage left it.
        if (factor_ >= 4) configureStage(s2_, baseRate * 2.0);
        reset();
    }

    void reset() {
        resetStage(s1_);
        resetStage(s2_);
    }

    int factor() const { return factor_; }

    // [out] must hold n * factor samples.
    void upsample(const float* in, float* out, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) {
            float pair[2];
            up2(s1_, in[i], pair);
            if (factor_ >= 4) {
                up2(s2_, pair[0], &out[k]); k += 2;
                up2(s2_, pair[1], &out[k]); k += 2;
            } else {
                out[k++] = pair[0];
                out[k++] = pair[1];
            }
        }
    }

    // Consumes n * factor samples from [in], writes n samples to [out]. Every
    // high-rate sample runs through the anti-alias filter so its state stays
    // continuous; one output per group is kept.
    void downsample(const float* in, float* out, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (factor_ >= 4) {
                const float pair[2] = { down2(s2_, &in[k]), down2(s2_, &in[k + 2]) };
                k += 4;
                out[i] = down2(s1_, pair);
            } else {
                out[i] = down2(s1_, &in[k]);
                k += 2;
            }
        }
    }

private:
    static constexpr int kStages = 4;

    struct Stage {
        Biquad up[kStages];
        Biquad down[kStages];
    };

    static void configureStage(Stage& s, double baseRate) {
        const double osRate = baseRate * 2.0;
        const double fc = 0.45 * baseRate;  // 0.9 × base Nyquist
        // 8th-order Butterworth cascade Q values
        static const double kQ[kStages] = {0.50980, 0.60134, 0.89998, 2.56292};
        for (int i = 0; i < kStages; i++) {
            s.up[i].configure(BiquadType::LowPass, osRate, fc, kQ[i]);
            s.down[i].configure(BiquadType::LowPass, osRate, fc, kQ[i]);
        }
    }

    static void resetStage(Stage& s) {
        for (int i = 0; i < kStages; i++) { s.up[i].reset(); s.down[i].reset(); }
    }

    // One 2x step: the sample scaled to hold level through the zero-stuff, then
    // the inserted zero, both filtered so the stage's state stays continuous.
    static inline void up2(Stage& s, float x, float* out2) {
        float a = x * 2.0f;
        for (int b = 0; b < kStages; b++) a = s.up[b].process(a);
        out2[0] = a;
        float z = 0.0f;
        for (int b = 0; b < kStages; b++) z = s.up[b].process(z);
        out2[1] = z;
    }

    // Inverse of up2: filter both, keep the first.
    static inline float down2(Stage& s, const float* in2) {
        float y0 = in2[0];
        for (int b = 0; b < kStages; b++) y0 = s.down[b].process(y0);
        float y1 = in2[1];
        for (int b = 0; b < kStages; b++) y1 = s.down[b].process(y1);
        (void)y1;
        return y0;
    }

    Stage s1_;
    Stage s2_;
    int factor_ = 1;
};
