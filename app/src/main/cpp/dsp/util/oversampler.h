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
// oversampling wrapper in SnapinProcessor: zero-stuff + 8th-order Butterworth
// anti-image filter on the way up, matching anti-alias filter + decimation on
// the way down. Both filters sit at 0.9 × the base Nyquist, so nonlinear
// snapins fold harmonics far above the audio band instead of aliasing back
// into it.
class ChannelOversampler {
public:
    void prepare(double baseRate, int factor) {
        factor_ = factor < 1 ? 1 : (factor > 4 ? 4 : factor);
        if (factor_ <= 1 || baseRate <= 0.0) { reset(); return; }
        const double osRate = baseRate * factor_;
        const double fc = 0.45 * baseRate;  // 0.9 × base Nyquist
        // 8th-order Butterworth cascade Q values
        static const double kQ[kStages] = {0.50980, 0.60134, 0.89998, 2.56292};
        for (int i = 0; i < kStages; i++) {
            up_[i].configure(BiquadType::LowPass, osRate, fc, kQ[i]);
            down_[i].configure(BiquadType::LowPass, osRate, fc, kQ[i]);
        }
        reset();
    }

    void reset() {
        for (int i = 0; i < kStages; i++) {
            up_[i].reset();
            down_[i].reset();
        }
    }

    int factor() const { return factor_; }

    // [out] must hold n * factor samples.
    void upsample(const float* in, float* out, int n) {
        const float gain = static_cast<float>(factor_);
        int k = 0;
        for (int i = 0; i < n; i++) {
            for (int f = 0; f < factor_; f++) {
                float s = (f == 0) ? in[i] * gain : 0.0f;
                for (int b = 0; b < kStages; b++) s = up_[b].process(s);
                out[k++] = s;
            }
        }
    }

    // Consumes n * factor samples from [in], writes n samples to [out]. Every
    // high-rate sample runs through the anti-alias filter so its state stays
    // continuous; one output per group is kept.
    void downsample(const float* in, float* out, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) {
            float keep = 0.0f;
            for (int f = 0; f < factor_; f++) {
                float s = in[k++];
                for (int b = 0; b < kStages; b++) s = down_[b].process(s);
                if (f == 0) keep = s;
            }
            out[i] = keep;
        }
    }

private:
    static constexpr int kStages = 4;
    Biquad up_[kStages];
    Biquad down_[kStages];
    int factor_ = 1;
};
