// EBU R128 loudness, as ITU-R BS.1770-4 and EBU Tech 3341 / 3342 define it:
// K-weighted Momentary (400 ms), Short-term (3 s) and gated Integrated
// loudness, Loudness Range, and true peak from 4x oversampling.
//
// Header-only and free of Android so the host tests can build it with the
// desktop compiler. The JNI wrapper (loudness_jni.cpp) owns the threading;
// this class is single-threaded and does all of its work in process().
//
// Audio-thread rules (docs/agent-playbook.md, Realtime Audio): process()
// allocates nothing, locks nothing and logs nothing. Every buffer is a fixed
// array sized at compile time.
//
// The DSP library builds with -ffast-math, under which NaN and infinity are not
// values the compiler promises to keep. So nothing here produces either: "no
// reading" is the kNone sentinel, and silence is floored at kFloorLufs.

#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>

namespace tryptify {

class LoudnessMeter {
public:
    /** The tap sees up to 24 channels (9.1.4 / 9.1.6 travel as 24-channel frames). */
    static constexpr int kMaxChannels = 24;
    /** "There is no reading yet." Far below anything a meter can show. */
    static constexpr float kNone = -1000.f;
    /** Digital silence, and anything quieter, reads as this. */
    static constexpr float kFloorLufs = -120.f;

    struct Reading {
        float momentary = kNone;   // LUFS, last 400 ms
        float shortTerm = kNone;   // LUFS, last 3 s
        float integrated = kNone;  // LUFS, gated, since reset
        float range = kNone;       // LU, EBU Tech 3342, since reset
        float truePeak = kNone;    // dBTP, max since reset
    };

    LoudnessMeter() { buildTruePeakFilter(); reset(); }

    /** Sets the stream format. A change of either restarts the measurement. */
    void configure(int sampleRate, int channels) {
        if (channels < 1) channels = 1;
        if (channels > kMaxChannels) channels = kMaxChannels;
        if (sampleRate < 8000) sampleRate = 8000;
        if (sampleRate == sampleRate_ && channels == channels_) return;
        sampleRate_ = sampleRate;
        channels_ = channels;
        buildKWeighting();
        subBlockFrames_ = (sampleRate_ + 5) / 10;  // 100 ms
        reset();
    }

    /** Starts every measurement again: a new track, or a tap on the readout. */
    void reset() {
        std::memset(z_, 0, sizeof(z_));
        std::memset(subBlocks_, 0, sizeof(subBlocks_));
        std::memset(blockCount_, 0, sizeof(blockCount_));
        std::memset(blockEnergy_, 0, sizeof(blockEnergy_));
        std::memset(stCount_, 0, sizeof(stCount_));
        std::memset(stEnergy_, 0, sizeof(stEnergy_));
        std::memset(tpHistory_, 0, sizeof(tpHistory_));
        subAccum_ = 0.0;
        subFrames_ = 0;
        subFilled_ = 0;
        subHead_ = 0;
        tpHead_ = 0;
        peakLinear_ = 0.f;
        reading_ = Reading{};
    }

    /** [frames] interleaved frames of the configured channel count. */
    void process(const float* interleaved, int frames) {
        const int ch = channels_;
        for (int f = 0; f < frames; ++f) {
            const float* frame = interleaved + f * ch;
            double sum = 0.0;
            for (int c = 0; c < ch; ++c) {
                const float x = frame[c];
                trackTruePeak(c, x);
                const double w = weights_[c];
                if (w == 0.0) continue;
                const double y = kWeight(c, x);
                sum += w * y * y;
            }
            subAccum_ += sum;
            if (++subFrames_ >= subBlockFrames_) closeSubBlock();
        }
        if (frames > 0) {
            reading_.truePeak = peakLinear_ > 1e-6f
                ? clampFloor(20.f * std::log10(peakLinear_))
                : kFloorLufs;
        }
    }

    const Reading& reading() const { return reading_; }

    /** Mean square → LUFS, per BS.1770: −0.691 + 10·log10(Σ Gᵢ·zᵢ). */
    static float lufsOf(double meanSquare) {
        if (!(meanSquare > 1e-15)) return kFloorLufs;
        return clampFloor(float(-0.691 + 10.0 * std::log10(meanSquare)));
    }

private:
    // ── K-weighting: shelf then high-pass, coefficients for any rate ────────
    // The formulas libebur128 uses to re-derive BS.1770's 48 kHz tables at
    // other rates, so 44.1 kHz and 96 kHz measure the same curve.
    void buildKWeighting() {
        const double fs = sampleRate_;
        {
            const double f0 = 1681.974450955533, G = 3.999843853973347, Q = 0.7071752369554196;
            const double K = std::tan(M_PI * f0 / fs);
            const double Vh = std::pow(10.0, G / 20.0);
            const double Vb = std::pow(Vh, 0.4996667741545416);
            const double a0 = 1.0 + K / Q + K * K;
            s1b_[0] = (Vh + Vb * K / Q + K * K) / a0;
            s1b_[1] = 2.0 * (K * K - Vh) / a0;
            s1b_[2] = (Vh - Vb * K / Q + K * K) / a0;
            s1a_[0] = 2.0 * (K * K - 1.0) / a0;
            s1a_[1] = (1.0 - K / Q + K * K) / a0;
        }
        {
            const double f0 = 38.13547087602444, Q = 0.5003270373238773;
            const double K = std::tan(M_PI * f0 / fs);
            const double a0 = 1.0 + K / Q + K * K;
            s2a_[0] = 2.0 * (K * K - 1.0) / a0;
            s2a_[1] = (1.0 - K / Q + K * K) / a0;
        }
        // Channel weights (BS.1770-4 Table 3) in Android's channel order:
        // FL FR FC LFE BL BR … — the LFE is not measured, and everything from
        // the rear pair on is a surround at +1.5 dB. Mono and stereo are 1.0.
        for (int c = 0; c < kMaxChannels; ++c) {
            double w = 1.0;
            if (channels_ >= 6) {
                if (c == 3) w = 0.0;
                else if (c >= 4) w = 1.41;
            }
            weights_[c] = w;
        }
    }

    double kWeight(int c, float x) {
        double* z = z_[c];
        // Stage 1, transposed direct form II.
        const double y1 = s1b_[0] * x + z[0];
        z[0] = s1b_[1] * x - s1a_[0] * y1 + z[1];
        z[1] = s1b_[2] * x - s1a_[1] * y1;
        // Stage 2: b = {1, −2, 1}.
        const double y2 = y1 + z[2];
        z[2] = -2.0 * y1 - s2a_[0] * y2 + z[3];
        z[3] = y1 - s2a_[1] * y2;
        return y2;
    }

    // ── 100 ms sub-blocks feed every window ─────────────────────────────────
    // Momentary is the last 4 sub-blocks, short-term the last 30; a 400 ms
    // gating block closes every 100 ms, which is the 75% overlap BS.1770 asks
    // for, and a short-term value every 100 ms is the 10 Hz Tech 3342 wants.
    void closeSubBlock() {
        subBlocks_[subHead_] = subAccum_ / double(subFrames_);
        subHead_ = (subHead_ + 1) % kShortSubBlocks;
        if (subFilled_ < kShortSubBlocks) ++subFilled_;
        subAccum_ = 0.0;
        subFrames_ = 0;

        if (subFilled_ >= kMomentarySubBlocks) {
            const double m = meanOfLast(kMomentarySubBlocks);
            reading_.momentary = lufsOf(m);
            addToHistogram(blockCount_, blockEnergy_, reading_.momentary, m);
            reading_.integrated = integrated();
        }
        if (subFilled_ >= kShortSubBlocks) {
            const double s = meanOfLast(kShortSubBlocks);
            reading_.shortTerm = lufsOf(s);
            addToHistogram(stCount_, stEnergy_, reading_.shortTerm, s);
            reading_.range = loudnessRange();
        }
    }

    double meanOfLast(int n) const {
        double sum = 0.0;
        for (int i = 1; i <= n; ++i) {
            sum += subBlocks_[(subHead_ - i + kShortSubBlocks) % kShortSubBlocks];
        }
        return sum / n;
    }

    // ── Gating, over histograms rather than lists ───────────────────────────
    // A track's worth of blocks is tens of thousands of values; a list would
    // grow on the audio thread. Each value lands in a 0.1 LU bin that keeps
    // its count and the sum of its exact mean squares, so the gated mean is
    // exact and only the gate's own threshold is rounded to the bin.
    static constexpr float kAbsGate = -70.f;
    static constexpr float kBinLu = 0.1f;
    static constexpr int kBins = 900;  // −70 … +20 LUFS

    static int binOf(float lufs) {
        int b = int((lufs - kAbsGate) / kBinLu);
        return b < 0 ? 0 : (b >= kBins ? kBins - 1 : b);
    }

    static void addToHistogram(uint32_t* count, double* energy, float lufs, double meanSquare) {
        if (lufs <= kAbsGate) return;  // absolute gate
        const int b = binOf(lufs);
        ++count[b];
        energy[b] += meanSquare;
    }

    float integrated() const {
        uint64_t n = 0;
        double e = 0.0;
        for (int b = 0; b < kBins; ++b) { n += blockCount_[b]; e += blockEnergy_[b]; }
        if (n == 0) return kNone;
        const float relGate = lufsOf(e / double(n)) - 10.f;
        const int from = relGate <= kAbsGate ? 0 : binOf(relGate);
        n = 0;
        e = 0.0;
        for (int b = from; b < kBins; ++b) { n += blockCount_[b]; e += blockEnergy_[b]; }
        return n == 0 ? kNone : lufsOf(e / double(n));
    }

    float loudnessRange() const {
        uint64_t n = 0;
        double e = 0.0;
        for (int b = 0; b < kBins; ++b) { n += stCount_[b]; e += stEnergy_[b]; }
        if (n == 0) return kNone;
        const float relGate = lufsOf(e / double(n)) - 20.f;
        const int from = relGate <= kAbsGate ? 0 : binOf(relGate);
        uint64_t total = 0;
        for (int b = from; b < kBins; ++b) total += stCount_[b];
        if (total < 2) return kNone;
        const float lo = percentile(from, total, 0.10);
        const float hi = percentile(from, total, 0.95);
        return hi > lo ? hi - lo : 0.f;
    }

    float percentile(int from, uint64_t total, double p) const {
        const uint64_t target = uint64_t(p * double(total - 1));
        uint64_t seen = 0;
        for (int b = from; b < kBins; ++b) {
            seen += stCount_[b];
            if (seen > target) return kAbsGate + (b + 0.5f) * kBinLu;
        }
        return kAbsGate + (kBins - 0.5f) * kBinLu;
    }

    // ── True peak: 4x polyphase interpolation ───────────────────────────────
    // BS.1770-4 Annex 2 asks for at least 4x oversampling before taking the
    // peak, so the inter-sample overs a DAC's reconstruction filter will make
    // are caught. A 48-tap windowed sinc split into four 12-tap phases, each
    // normalised to unity DC gain; the sample itself is checked as well, so the
    // reading can never come in under the sample peak.
    static constexpr int kTpPhases = 4;
    static constexpr int kTpTaps = 12;

    void buildTruePeakFilter() {
        const int n = kTpPhases * kTpTaps;
        const double centre = (n - 1) / 2.0;
        for (int m = 0; m < n; ++m) {
            const double t = (m - centre) / kTpPhases;
            const double sinc = std::fabs(t) < 1e-9 ? 1.0 : std::sin(M_PI * t) / (M_PI * t);
            // Blackman-Harris: −92 dB sidelobes, plenty for a peak reading.
            const double a = 2.0 * M_PI * m / (n - 1);
            const double w = 0.35875 - 0.48829 * std::cos(a) + 0.14128 * std::cos(2 * a)
                - 0.01168 * std::cos(3 * a);
            tpCoef_[m % kTpPhases][m / kTpPhases] = float(sinc * w);
        }
        for (int p = 0; p < kTpPhases; ++p) {
            double s = 0.0;
            for (int k = 0; k < kTpTaps; ++k) s += tpCoef_[p][k];
            for (int k = 0; k < kTpTaps; ++k) tpCoef_[p][k] = float(tpCoef_[p][k] / s);
        }
    }

    void trackTruePeak(int c, float x) {
        float* h = tpHistory_[c];
        // Newest sample at index tpHead_, older ones walking backwards.
        const int head = (c == 0) ? (tpHead_ = (tpHead_ + 1) % kTpTaps) : tpHead_;
        h[head] = x;
        float peak = std::fabs(x);
        for (int p = 0; p < kTpPhases; ++p) {
            float acc = 0.f;
            for (int k = 0; k < kTpTaps; ++k) {
                acc += tpCoef_[p][k] * h[(head - k + kTpTaps) % kTpTaps];
            }
            const float a = std::fabs(acc);
            if (a > peak) peak = a;
        }
        if (peak > peakLinear_) peakLinear_ = peak;
    }

    static float clampFloor(float v) { return v < kFloorLufs ? kFloorLufs : v; }

    static constexpr int kMomentarySubBlocks = 4;
    static constexpr int kShortSubBlocks = 30;

    int sampleRate_ = 0;
    int channels_ = 0;
    int subBlockFrames_ = 4800;

    double s1b_[3] = {}, s1a_[2] = {}, s2a_[2] = {};
    double weights_[kMaxChannels] = {};
    double z_[kMaxChannels][4] = {};

    double subAccum_ = 0.0;
    int subFrames_ = 0;
    double subBlocks_[kShortSubBlocks] = {};
    int subFilled_ = 0;
    int subHead_ = 0;

    uint32_t blockCount_[kBins] = {};
    double blockEnergy_[kBins] = {};
    uint32_t stCount_[kBins] = {};
    double stEnergy_[kBins] = {};

    float tpCoef_[kTpPhases][kTpTaps] = {};
    float tpHistory_[kMaxChannels][kTpTaps] = {};
    int tpHead_ = 0;
    float peakLinear_ = 0.f;

    Reading reading_;
};

}  // namespace tryptify
