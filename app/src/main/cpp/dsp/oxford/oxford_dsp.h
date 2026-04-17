// SPDX-License-Identifier: GPL-3.0-or-later
// tryptz/monotrypt.android
// DSP: Oxford-style Inflator + Compressor
//
// Inflator transfer function is the canonical tviler reverse-engineering of
// the Sonnox Oxford Inflator: an odd-symmetric static polynomial waveshaper
// with a 3-band Linkwitz-Riley split option.
//
// References:
//   - tviler, Gearspace Sony Oxford Inflator thread (pages 5-6)
//   - Mighty23 / Allen, Hise Faust thread (polynomial coefficient verification)
//   - Sonnox Oxford Inflator User Guide (fader ranges, clip behaviour)
//
// Design rules (observed, per user's "severe" directive):
//   * No heap allocation on the audio path.
//   * Atomics for all user-visible params, cached once per block.
//   * Per-sample one-pole smoothing on continuous params to kill zipper noise.
//   * Denormal flush on biquad state.
//   * Stereo-linked detection where relevant (compressor).
//   * Channel count fixed at prepare-time; call prepare() again to reconfigure.

#pragma once

#include <atomic>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace trypt::dsp {

// ============================================================================
// Utilities
// ============================================================================

inline float dbToLin(float db) noexcept {
    return std::pow(10.0f, db * 0.05f);
}

inline float linToDb(float lin) noexcept {
    return (lin > 1e-9f) ? 20.0f * std::log10(lin) : -180.0f;
}

inline float flushDenormal(float v) noexcept {
    return (std::fabs(v) < 1e-20f) ? 0.0f : v;
}

// ============================================================================
// Biquad (Direct Form II Transposed, double state for stability at low fc)
// ============================================================================

class Biquad {
public:
    void setCoeffs(double b0, double b1, double b2, double a1, double a2) noexcept {
        b0_ = b0; b1_ = b1; b2_ = b2; a1_ = a1; a2_ = a2;
    }

    inline float process(float x) noexcept {
        const double xd = static_cast<double>(x);
        const double y  = b0_ * xd + z1_;
        z1_ = b1_ * xd - a1_ * y + z2_;
        z2_ = b2_ * xd - a2_ * y;
        // denormal guard
        if (std::fabs(z1_) < 1e-25) z1_ = 0.0;
        if (std::fabs(z2_) < 1e-25) z2_ = 0.0;
        return static_cast<float>(y);
    }

    void reset() noexcept { z1_ = z2_ = 0.0; }

private:
    double b0_{1.0}, b1_{0.0}, b2_{0.0}, a1_{0.0}, a2_{0.0};
    double z1_{0.0}, z2_{0.0};
};

// ============================================================================
// Linkwitz-Riley 4th order = two cascaded 2nd-order Butterworth of same cutoff
// (Q = 1/sqrt(2) ~ 0.7071). Gives 24 dB/oct slope.
// ============================================================================

class LinkwitzRiley4 {
public:
    enum class Type { LowPass, HighPass };

    void configure(Type t, double fc, double sr) noexcept {
        const double w0 = 2.0 * M_PI * fc / sr;
        const double cs = std::cos(w0);
        const double sn = std::sin(w0);
        const double Q  = 0.70710678118654752;   // Butterworth
        const double alpha = sn / (2.0 * Q);
        const double a0 = 1.0 + alpha;
        const double a1 = -2.0 * cs;
        const double a2 = 1.0 - alpha;
        double b0, b1, b2;
        if (t == Type::LowPass) {
            b0 = (1.0 - cs) * 0.5;
            b1 =  1.0 - cs;
            b2 =  b0;
        } else {
            b0 = (1.0 + cs) * 0.5;
            b1 = -(1.0 + cs);
            b2 =  b0;
        }
        const double inv = 1.0 / a0;
        stage1_.setCoeffs(b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv);
        stage2_.setCoeffs(b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv);
    }

    inline float process(float x) noexcept {
        return stage2_.process(stage1_.process(x));
    }

    void reset() noexcept { stage1_.reset(); stage2_.reset(); }

private:
    Biquad stage1_, stage2_;
};

// ============================================================================
// One-pole parameter smoother (exponential approach to target per-sample)
// ============================================================================

class Smoothed {
public:
    void configure(double sr, double timeMs) noexcept {
        const double tau = std::max(timeMs, 0.001) * 0.001;
        coeff_ = std::exp(-1.0 / (tau * sr));
    }
    void setTarget(float t) noexcept { target_ = t; }
    void snapTo(float v) noexcept { target_ = current_ = v; }
    inline float next() noexcept {
        current_ = target_ + (current_ - target_) * static_cast<float>(coeff_);
        return current_;
    }
    float currentValue() const noexcept { return current_; }

private:
    float  target_{0.0f}, current_{0.0f};
    double coeff_{0.0};
};

// ============================================================================
// Inflator
// ============================================================================

class InflatorProcessor {
public:
    struct Params {
        std::atomic<float> inputDb   {  0.0f };  // [-6, +12] dB
        std::atomic<float> outputDb  {  0.0f };  // [-12,  0] dB
        std::atomic<float> effect    {  1.0f };  // [0, 1]     (Effect fader / 100)
        std::atomic<float> curve     {  0.0f };  // [-50, +50]
        std::atomic<bool>  clipZeroDb{  true  };
        std::atomic<bool>  bandSplit {  false };
        std::atomic<bool>  bypass    {  false };
        // Output meter: updated by processor, read by UI
        std::atomic<float> peakL     {  0.0f };
        std::atomic<float> peakR     {  0.0f };
    } params;

    void prepare(double sampleRate, int numChannels) {
        sr_   = sampleRate;
        nCh_  = std::clamp(numChannels, 1, 2);

        for (int c = 0; c < nCh_; ++c) {
            lrLow_ [c].configure(LinkwitzRiley4::Type::LowPass,  240.0,  sr_);
            lrHigh_[c].configure(LinkwitzRiley4::Type::HighPass, 2400.0, sr_);
            lrLow_ [c].reset();
            lrHigh_[c].reset();
        }
        preGain_ .configure(sr_, 20.0);
        postGain_.configure(sr_, 20.0);
        effect_  .configure(sr_, 20.0);
        curve_   .configure(sr_, 50.0);
        preGain_ .snapTo(1.0f);
        postGain_.snapTo(1.0f);
        effect_  .snapTo(1.0f);
        curve_   .snapTo(0.0f);
    }

    // In-place processing on planar float buffers.
    void process(float* const* buffers, int numFrames) noexcept {
        if (params.bypass.load(std::memory_order_relaxed)) {
            updateMeters(buffers, numFrames);
            return;
        }

        preGain_ .setTarget(dbToLin(params.inputDb .load(std::memory_order_relaxed)));
        postGain_.setTarget(dbToLin(params.outputDb.load(std::memory_order_relaxed)));
        effect_  .setTarget(std::clamp(params.effect.load(std::memory_order_relaxed), 0.0f, 1.0f));
        curve_   .setTarget(std::clamp(params.curve .load(std::memory_order_relaxed), -50.0f, 50.0f));

        const bool clip  = params.clipZeroDb.load(std::memory_order_relaxed);
        const bool split = params.bandSplit .load(std::memory_order_relaxed);

        float pkL = 0.0f, pkR = 0.0f;

        for (int n = 0; n < numFrames; ++n) {
            const float pre   = preGain_.next();
            const float post  = postGain_.next();
            const float wet   = effect_.next();
            const float dry   = 1.0f - wet;
            const float curve = curve_.next();

            // Polynomial coefficients for this sample (curve is smoothed; cheap to recompute)
            const float A =  1.5f + curve * 0.01f;
            const float B = -curve * 0.02f;
            const float C = -0.5f + curve * 0.01f;
            const float D =  0.0625f - curve * 0.0025f + curve * curve * 2.5e-5f;

            for (int c = 0; c < nCh_; ++c) {
                // Input guard: keep x within the waveshape's valid range [-2, 2].
                // CLIP 0 dB is applied to the OUTPUT below, per Sonnox spec.
                float x = std::clamp(buffers[c][n] * pre, -2.0f, 2.0f);

                float shaped;
                if (split) {
                    const float lo  = lrLow_ [c].process(x);
                    const float hi  = lrHigh_[c].process(x);
                    const float mid = x - lo - hi;
                    shaped = waveshape(lo, A, B, C, D)
                           + waveshape(mid, A, B, C, D)
                           + waveshape(hi, A, B, C, D);
                } else {
                    shaped = waveshape(x, A, B, C, D);
                }

                float y = (dry * x + wet * shaped) * post;
                if (clip) y = std::clamp(y, -1.0f, 1.0f);
                buffers[c][n] = y;

                const float ay = std::fabs(y);
                if (c == 0) { if (ay > pkL) pkL = ay; }
                else        { if (ay > pkR) pkR = ay; }
            }
        }

        // Decay-and-hold style peak: publish the block peak; UI does its own decay
        params.peakL.store(pkL, std::memory_order_relaxed);
        params.peakR.store(nCh_ > 1 ? pkR : pkL, std::memory_order_relaxed);
    }

private:
    void updateMeters(float* const* buffers, int n) noexcept {
        float pkL = 0.0f, pkR = 0.0f;
        for (int i = 0; i < n; ++i) {
            const float a0 = std::fabs(buffers[0][i]);
            if (a0 > pkL) pkL = a0;
            if (nCh_ > 1) { const float a1 = std::fabs(buffers[1][i]); if (a1 > pkR) pkR = a1; }
        }
        params.peakL.store(pkL, std::memory_order_relaxed);
        params.peakR.store(nCh_ > 1 ? pkR : pkL, std::memory_order_relaxed);
    }

    // Odd-symmetric Inflator transfer function (tviler canonical form).
    // |x| in [0,1]: polynomial in |x|, re-signed.
    // |x| in (1,2]: 2|x| - x^2 (smooth fall to 0 at |x|=2).
    static inline float waveshape(float x, float A, float B, float C, float D) noexcept {
        const float ax = std::fabs(x);
        const float s  = std::copysign(1.0f, x);
        if (ax <= 1.0f) {
            const float x2 = ax * ax;
            const float x3 = x2 * ax;
            const float x4 = x2 * x2;
            return s * (A * ax + B * x2 + C * x3 - D * (x2 - 2.0f * x3 + x4));
        }
        if (ax <= 2.0f) {
            return s * (2.0f * ax - ax * ax);
        }
        return 0.0f;
    }

    double sr_{48000.0};
    int    nCh_{2};
    std::array<LinkwitzRiley4, 2> lrLow_, lrHigh_;
    Smoothed preGain_, postGain_, effect_, curve_;
};

// ============================================================================
// Compressor: feed-forward, log-domain, soft-knee, decoupled attack/release.
// Stereo-linked (max-of-channels peak detection).
// ============================================================================

class CompressorProcessor {
public:
    struct Params {
        std::atomic<float> thresholdDb{ -20.0f }; // [-60, 0]
        std::atomic<float> ratio      {   4.0f }; // [1, 20]
        std::atomic<float> attackMs   {  10.0f }; // [0.1, 200]
        std::atomic<float> releaseMs  { 100.0f }; // [5, 2000]
        std::atomic<float> kneeDb     {   6.0f }; // [0, 24]
        std::atomic<float> makeupDb   {   0.0f }; // [-12, +24]
        std::atomic<bool>  bypass     { false };
        // Read-only from UI:
        std::atomic<float> gainReductionDb{ 0.0f };  // momentary GR, absolute value dB
        std::atomic<float> peakL          { 0.0f };
        std::atomic<float> peakR          { 0.0f };
    } params;

    void prepare(double sampleRate, int numChannels) {
        sr_   = sampleRate;
        nCh_  = std::clamp(numChannels, 1, 2);
        envDb_ = -120.0f;
        grSmoothDb_ = 0.0f;
        grCoef_ = std::exp(-1.0f / (0.030f * static_cast<float>(sr_)));  // 30 ms UI smoothing
    }

    void process(float* const* buffers, int numFrames) noexcept {
        if (params.bypass.load(std::memory_order_relaxed)) return;

        const float threshDb = params.thresholdDb.load(std::memory_order_relaxed);
        const float ratio    = std::max(1.0f, params.ratio.load(std::memory_order_relaxed));
        const float kneeDb   = std::max(0.0f, params.kneeDb.load(std::memory_order_relaxed));
        const float mkupDb   = params.makeupDb.load(std::memory_order_relaxed);
        const float aMs      = std::max(0.05f, params.attackMs .load(std::memory_order_relaxed));
        const float rMs      = std::max(1.0f,  params.releaseMs.load(std::memory_order_relaxed));

        const float aCoef = std::exp(-1.0f / (aMs * 0.001f * static_cast<float>(sr_)));
        const float rCoef = std::exp(-1.0f / (rMs * 0.001f * static_cast<float>(sr_)));
        const float makeupLin = dbToLin(mkupDb);
        const float halfKnee  = kneeDb * 0.5f;
        const float slope     = 1.0f - 1.0f / ratio;  // dB reduction per dB over threshold

        float pkL = 0.0f, pkR = 0.0f;

        for (int n = 0; n < numFrames; ++n) {
            // Linked detection: max abs across channels
            float peak = std::fabs(buffers[0][n]);
            if (nCh_ > 1) peak = std::max(peak, std::fabs(buffers[1][n]));

            const float inDb = (peak > 1e-9f) ? 20.0f * std::log10(peak) : -120.0f;

            // Level envelope with separate attack/release (decoupled peak detector)
            if (inDb > envDb_) envDb_ = inDb + (envDb_ - inDb) * aCoef;
            else               envDb_ = inDb + (envDb_ - inDb) * rCoef;

            // Static curve with quadratic soft knee
            const float over = envDb_ - threshDb;
            float grDb;
            if (over <= -halfKnee) {
                grDb = 0.0f;
            } else if (over >= halfKnee) {
                grDb = slope * over;
            } else {
                const float t = over + halfKnee;
                grDb = slope * (t * t) / (2.0f * kneeDb + 1e-9f);
            }

            // Convert dB -> linear via exp (cheaper than pow)
            // 10^(-grDb/20) = exp(-grDb * ln(10)/20) = exp(-grDb * 0.11512925f)
            const float gainLin = std::exp(-grDb * 0.11512925f) * makeupLin;

            for (int c = 0; c < nCh_; ++c) {
                const float y = buffers[c][n] * gainLin;
                buffers[c][n] = y;
                const float ay = std::fabs(y);
                if (c == 0) { if (ay > pkL) pkL = ay; }
                else        { if (ay > pkR) pkR = ay; }
            }

            // Per-sample UI-smoothed GR: asymmetric — attack fast (follow increases),
            // release slow (linger on the meter so user can read it)
            if (grDb > grSmoothDb_) grSmoothDb_ = grDb;
            else                    grSmoothDb_ = grDb + (grSmoothDb_ - grDb) * grCoef_;
        }

        params.gainReductionDb.store(grSmoothDb_, std::memory_order_relaxed);
        params.peakL.store(pkL, std::memory_order_relaxed);
        params.peakR.store(nCh_ > 1 ? pkR : pkL, std::memory_order_relaxed);
    }

private:
    double sr_{48000.0};
    int    nCh_{2};
    float  envDb_{-120.0f};
    float  grSmoothDb_{0.0f};
    float  grCoef_{0.0f};
};

} // namespace trypt::dsp
