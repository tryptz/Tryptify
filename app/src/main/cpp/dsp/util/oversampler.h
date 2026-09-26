#pragma once
#include <algorithm>
#include <array>
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// N-times oversampler (2x/4x) for one audio channel, used by the per-snapin
// oversampling wrapper in SnapinProcessor and by the Inflator's waveshaper.
//
// Each 2x step is a linear-phase halfband FIR (Kaiser-windowed sinc, 96 dB
// stopband), run polyphase: half its taps are zero, and the odd phase is a
// single tap, so a step costs about half its length in multiplies. 4x is two
// cascaded 2x steps, each filter covering only its own octave.
//
// It replaced an 8th-order Butterworth pair, which had two costs. Its phase
// shift varied with frequency, so an oversampled effect blended with its own
// dry signal, or summed with a bus that was not oversampled, comb-filtered —
// even a plain gain did. And it was gentle: at 44.1 kHz its stopband began
// well above 24 kHz, leaving images for a nonlinearity to fold back. The
// FIR's delay is the same at every frequency and a whole number of base-rate
// samples (latency()), so the engine can line the dry path and the other
// buses up with it exactly.
//
// The filters are designed for the rate: the passband ends at 20 kHz (or
// 0.4535 × the base rate, if that is lower) and the stopband starts where the
// passband's alias would, base rate − passband. At 44.1 kHz that leaves 4.1
// kHz of transition and 135 taps; at 96 kHz it is 56 kHz wide and a handful
// of taps.
//
// Nothing here allocates: every buffer is a fixed array sized for the longest
// filter, so prepare() may run on the audio thread (the Oxford stages do,
// when their factor changes).
//
// Above 192 kHz of internal rate there is nothing left to gain, only CPU and
// memory to spend: a stream at 96 kHz already has 76 kHz between the top of
// the audible band and the first alias. effectiveFactor() caps the requested
// factor so base × factor stays within that — 4x at 44.1 and 48 kHz, 2x at
// 88.2 and 96 kHz, off at 176.4 kHz and above.
constexpr int kMaxHalfbandTaps = 255;                  // 4k + 3
constexpr int kMaxEvenTaps = (kMaxHalfbandTaps + 1) / 2;
constexpr double kMaxOversampledRate = 192000.0;

class HalfbandStage {
public:
    // Designs for a 2x step whose low side runs at [baseRate].
    void design(double baseRate) {
        const double pass = std::min(20000.0, 0.4535 * baseRate);
        const double stop = baseRate - pass;
        const double high = 2.0 * baseRate;
        const double width = std::max(1e-4, (stop - pass) / high);
        constexpr double kAttenDb = 96.0;
        int n = static_cast<int>(std::ceil((kAttenDb - 7.95) / (14.36 * width))) + 1;
        // Halfband length 4k + 3: odd, with its centre on an odd index, so
        // every other tap but the centre is zero.
        n = std::max(7, std::min(kMaxHalfbandTaps, n));
        while (n % 4 != 3) n++;
        const int c = (n - 1) / 2;
        const double beta = 0.1102 * (kAttenDb - 8.7);
        const double i0b = besselI0(beta);

        // Only the even-indexed taps are stored; the odd ones are zero but
        // for the centre, which is exactly one half.
        const int taps = (n + 1) / 2;
        double h[kMaxEvenTaps];
        double sum = 0.0;
        for (int j = 0; j < taps; j++) {
            const int k = 2 * j - c;  // odd, never zero
            const double x = 0.5 * M_PI * k;
            const double r = static_cast<double>(k) / c;
            const double w = besselI0(beta * std::sqrt(std::max(0.0, 1.0 - r * r))) / i0b;
            h[j] = 0.5 * std::sin(x) / x * w;
            sum += h[j];
        }
        // They sum to exactly one half, so both polyphase branches pass DC at
        // unity and a constant stays constant.
        for (int j = 0; j < taps; j++) even_[static_cast<size_t>(j)] = static_cast<float>(h[j] * (0.5 / sum));
        centre_ = 0.5f;
        c_ = c;
        taps_ = taps;
    }

    int taps() const { return taps_; }
    int centreIndex() const { return c_; }  // group delay in high-rate samples
    const float* even() const { return even_.data(); }
    float centre() const { return centre_; }

private:
    static double besselI0(double x) {
        double sum = 1.0, term = 1.0;
        const double q = x * x * 0.25;
        for (int k = 1; k < 64; k++) {
            term *= q / (static_cast<double>(k) * k);
            sum += term;
            if (term < 1e-12 * sum) break;
        }
        return sum;
    }

    std::array<float, kMaxEvenTaps> even_{};
    float centre_ = 0.5f;
    int c_ = 1;
    int taps_ = 1;
};

// A sliding window over the last [size] samples, newest first, with no
// branch or modulo in the dot product: each sample is written twice, so the
// window is always contiguous.
class HistoryRing {
public:
    void prepare(int size) {
        size_ = std::max(1, std::min(kMaxEvenTaps, size));
        reset();
    }
    void reset() { buf_.fill(0.0f); pos_ = 0; }
    // Pushes [x]; window()[k] is then the sample k steps ago.
    inline void push(float x) {
        pos_ = (pos_ == 0 ? size_ : pos_) - 1;
        buf_[static_cast<size_t>(pos_)] = x;
        buf_[static_cast<size_t>(pos_ + size_)] = x;
    }
    inline const float* window() const { return &buf_[static_cast<size_t>(pos_)]; }

private:
    std::array<float, 2 * kMaxEvenTaps> buf_{};
    int size_ = 1;
    int pos_ = 0;
};

class ChannelOversampler {
public:
    // The factor that actually runs for [requested] at [baseRate]: 1, 2 or 4,
    // never taking the internal rate past kMaxOversampledRate.
    static int effectiveFactor(double baseRate, int requested) {
        int f = requested >= 4 ? 4 : (requested >= 2 ? 2 : 1);
        while (f > 1 && baseRate * f > kMaxOversampledRate + 1.0) f /= 2;
        return f;
    }

    // Base-rate latency [factor] costs at [baseRate], without preparing one.
    static int latencyFor(double baseRate, int factor) {
        if (factor <= 1 || baseRate <= 0.0) return 0;
        HalfbandStage s1;
        s1.design(baseRate);
        if (factor < 4) return s1.centreIndex();
        HalfbandStage s2;
        s2.design(baseRate * 2.0);
        return s1.centreIndex() + (s2.centreIndex() + 1) / 2;
    }

    // [factor] is 1, 2 or 4; anything else snaps down.
    void prepare(double baseRate, int factor) {
        factor_ = factor >= 4 ? 4 : (factor >= 2 ? 2 : 1);
        if (factor_ <= 1 || baseRate <= 0.0) { factor_ = 1; latency_ = 0; return; }
        s1_.design(baseRate);
        prepareState(st1_, s1_);
        if (factor_ >= 4) {
            // The second step runs an octave up, where the first left it.
            s2_.design(baseRate * 2.0);
            prepareState(st2_, s2_);
            // Its round trip is c2 samples at 2x — half a base sample when c2
            // is odd, as a halfband's centre always is. One more sample at 2x
            // on the way down makes the whole a whole number of base samples.
            latency_ = s1_.centreIndex() + (s2_.centreIndex() + 1) / 2;
        } else {
            latency_ = s1_.centreIndex();
        }
        reset();
    }

    void reset() {
        resetState(st1_);
        resetState(st2_);
        extra_ = 0.0f;
    }

    int factor() const { return factor_; }

    // Base-rate samples between a sample going up and its coming back down.
    int latency() const { return latency_; }

    // [out] must hold n * factor samples.
    void upsample(const float* in, float* out, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) {
            float pair[2];
            up2(s1_, st1_, in[i], pair);
            if (factor_ >= 4) {
                up2(s2_, st2_, pair[0], &out[k]); k += 2;
                up2(s2_, st2_, pair[1], &out[k]); k += 2;
            } else {
                out[k++] = pair[0];
                out[k++] = pair[1];
            }
        }
    }

    // Consumes n * factor samples from [in], writes n samples to [out].
    void downsample(const float* in, float* out, int n) {
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (factor_ >= 4) {
                const float a = down2(s2_, st2_, &in[k]);
                const float b = down2(s2_, st2_, &in[k + 2]);
                k += 4;
                // The one-sample delay at 2x that squares the latency up.
                const float pair[2] = { extra_, a };
                extra_ = b;
                out[i] = down2(s1_, st1_, pair);
            } else {
                out[i] = down2(s1_, st1_, &in[k]);
                k += 2;
            }
        }
    }

private:
    struct State {
        HistoryRing up;     // base-rate input of the step
        HistoryRing downE;  // high-rate input, even phase
        HistoryRing downO;  // high-rate input, odd phase
    };

    static void prepareState(State& st, const HalfbandStage& s) {
        st.up.prepare(s.taps());
        st.downE.prepare(s.taps());
        st.downO.prepare((s.centreIndex() + 1) / 2 + 1);
    }

    static void resetState(State& st) {
        st.up.reset();
        st.downE.reset();
        st.downO.reset();
    }

    static inline float dot(const float* a, const float* b, int n) {
        float acc = 0.0f;
        for (int k = 0; k < n; k++) acc += a[k] * b[k];
        return acc;
    }

    // One 2x step up: the zero-stuffed stream filtered, as its two phases.
    // Even outputs take every even tap; odd outputs only the centre, which is
    // a delayed copy. The ×2 restores the level zero-stuffing halves.
    static inline void up2(const HalfbandStage& s, State& st, float x, float* out2) {
        st.up.push(x);
        const float* w = st.up.window();
        out2[0] = 2.0f * dot(s.even(), w, s.taps());
        out2[1] = 2.0f * s.centre() * w[(s.centreIndex() - 1) / 2];
    }

    // One 2x step down: the filter's even taps on the even samples, its
    // centre on the odd ones, and only the output that is kept is computed.
    static inline float down2(const HalfbandStage& s, State& st, const float* in2) {
        st.downE.push(in2[0]);
        st.downO.push(in2[1]);
        return dot(s.even(), st.downE.window(), s.taps()) +
               s.centre() * st.downO.window()[(s.centreIndex() + 1) / 2];
    }

    HalfbandStage s1_, s2_;
    State st1_, st2_;
    float extra_ = 0.0f;
    int factor_ = 1;
    int latency_ = 0;
};

// A plain delay of [delay] samples, used to hold a signal back by what an
// oversampled path costs so the two stay in step. Fixed size, so it can be
// re-set on the audio thread.
class LatencyLine {
public:
    static constexpr int kMaxDelay = kMaxEvenTaps * 2;

    void prepare(int delay) {
        delay_ = std::max(0, std::min(kMaxDelay, delay));
        reset();
    }
    void reset() { buf_.fill(0.0f); pos_ = 0; }
    int delay() const { return delay_; }
    // In place.
    void process(float* x, int n) {
        if (delay_ == 0) return;
        for (int i = 0; i < n; i++) x[i] = process(x[i]);
    }
    inline float process(float x) {
        if (delay_ == 0) return x;
        const int size = delay_ + 1;
        buf_[static_cast<size_t>(pos_)] = x;
        int read = pos_ - delay_;
        if (read < 0) read += size;
        const float y = buf_[static_cast<size_t>(read)];
        if (++pos_ == size) pos_ = 0;
        return y;
    }

private:
    std::array<float, kMaxDelay + 1> buf_{};
    int delay_ = 0;
    int pos_ = 0;
};
