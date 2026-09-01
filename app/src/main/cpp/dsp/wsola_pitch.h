// SPDX-License-Identifier: GPL-3.0-or-later
//
// Streaming pitch shift built on WSOLA, for material a phase vocoder handles
// badly.

#pragma once

#include <cmath>
#include <cstring>
#include <vector>

#include "wsola.h"

namespace tryptify {

/**
 * How much CPU the stretcher may spend, and what it can hold together.
 *
 * Carried over verbatim from the sampler's engine, where the numbers were
 * chosen and measured. They are not decorative labels: each is a grain length
 * and a search radius, and the radius sets the lowest frequency that survives
 * a splice -- roughly `sampleRate / radius`. FAST gives up on bass below about
 * 375 Hz; HIGH holds down to 94 Hz. A 110 Hz note through FAST measures about
 * 8% flat, which is a bass line quietly playing out of tune.
 */
enum class WsolaQuality : int { kFast = 0, kBalanced = 1, kHigh = 2 };

struct WsolaQualityConfig {
    int window;
    int searchRadius;
};

inline WsolaQualityConfig wsolaConfigFor(WsolaQuality q) {
    switch (q) {
        case WsolaQuality::kFast: return {512, 128};
        case WsolaQuality::kHigh: return {2048, 512};
        default: return {1024, 256};
    }
}

/**
 * Transposes a continuous stereo stream without moving the tempo.
 *
 * ## Why a second engine
 *
 * The player already transposes with signalsmith-stretch, a phase vocoder
 * configured for a 0.35 s analysis block -- chosen, and measured, for pitch
 * accuracy on sustained tones. That is the right criterion for melodic
 * material and the wrong one for drums: a window that long is many kick
 * lengths, and what comes out is the smeared "wet cardboard" attack a phase
 * vocoder is known for. WSOLA has the opposite bias. It splices in the time
 * domain at waveform-similar points, so transients survive nearly intact,
 * while sustained polyphony picks up the phasiness that the vocoder avoids.
 *
 * Neither is better. They fail in opposite directions, so the pair covers what
 * either alone cannot, and the listener picks.
 *
 * ## How pitch comes out of a time stretcher
 *
 * The same construction the sampler's voice uses. Stretching by `P` makes the
 * audio `P` times longer at the original pitch; reading that back `P` times
 * faster restores the duration and multiplies every frequency by `P`. So the
 * stretcher runs at ratio `P` and the resampler steps at `P`, and each output
 * frame consumes exactly one input frame.
 *
 * ## Streaming
 *
 * [Wsola] reads a source buffer it can seek around in, which the sampler can
 * hand over whole. Here the source arrives a block at a time and has to be
 * kept in a sliding window: far enough back that the similarity search can
 * still reach, far enough forward that a whole grain is available. When the
 * window fills, the front is dropped and [Wsola::rebaseSource] moves the read
 * position with it, which is a memmove rather than a reset and so makes no
 * sound.
 *
 * ## Real-time contract
 *
 * The buffers are sized at [configure] and never grow during processing, so
 * [process] does not allocate. Cost per output frame is the WSOLA search --
 * bounded, and independent of the audio -- plus four multiply-adds of Hermite
 * interpolation.
 */
class WsolaPitchShifter {
public:
    /** Sizes every buffer for [channels] and the largest supported grain. */
    /**
     * [sampleRate] is accepted and not used, which is worth saying out loud:
     * the quality presets are windows in *samples*, so the same setting is a
     * different length of time at 44.1 and at 96 kHz. Whether that should be
     * rate-relative is a real question; today it is not, and a field quietly
     * holding the rate only made it look answered.
     */
    void configure(int channels, int /*sampleRate*/, WsolaQuality quality) {
        channels_ = (channels == 2) ? 2 : 1;
        setQuality(quality);
        // Room for the grain the search may reach behind, the grain ahead that
        // `generate` requires, and a block's worth of new input on top. Sized
        // once, here, so nothing downstream ever reallocates.
        capacity_ = Wsola::kMaxWindow * 8;
        historyL_.assign(static_cast<size_t>(capacity_), 0.0f);
        historyR_.assign(static_cast<size_t>(capacity_), 0.0f);
        reset();
    }

    /**
     * Grain and search radius. Applied immediately: [Wsola::configure] resets
     * the stretcher, which is audible, so callers should avoid changing this
     * mid-phrase.
     */
    void setQuality(WsolaQuality quality) {
        quality_ = quality;
        const WsolaQualityConfig cfg = wsolaConfigFor(quality);
        wsola_.configure(cfg.window, cfg.searchRadius);
        wsola_.setRatio(ratio_);
        // How far ahead of the stretcher the buffer has to run before a grain
        // can be cut. See [primeTarget_].
        primeTarget_ = wsola_.window() + wsola_.searchRadius() + wsola_.window() / 2 + 16;
    }

    WsolaQuality quality() const { return quality_; }

    /** Frequency multiplier. 1.0 is no shift. */
    void setPitchRatio(double ratio) {
        if (!(ratio > 0.0) || !std::isfinite(ratio)) return;
        if (ratio < 0.25) ratio = 0.25;
        if (ratio > 4.0) ratio = 4.0;
        ratio_ = ratio;
        wsola_.setRatio(ratio_);
    }

    void setSemitones(double semitones) {
        setPitchRatio(std::pow(2.0, semitones / 12.0));
    }

    double pitchRatio() const { return ratio_; }

    /** Drops all history. The next block starts a new stream. */
    void reset() {
        std::fill(historyL_.begin(), historyL_.end(), 0.0f);
        std::fill(historyR_.begin(), historyR_.end(), 0.0f);
        filled_ = 0;
        wsola_.reset(0.0);
        stretchedCount_ = 0;
        stretchedPos_ = 0.0;
        for (int i = 0; i < 4; ++i) { histL_[i] = 0.0f; histR_[i] = 0.0f; }
    }

    /**
     * The delay this adds, in frames: the gap the stream has to open before
     * the first grain can be cut. About 80 ms at HIGH and 20 ms at FAST,
     * against the 350 ms the phase vocoder needs.
     */
    int latencyFrames() const { return primeTarget_; }

    /**
     * Transposes [frames] of interleaved input into [out], which may be the
     * same buffer. Always writes exactly [frames] frames: until enough history
     * has accumulated to splice, it writes the input through unchanged, so the
     * stream never gains or loses samples and never goes silent.
     */
    void process(const float* in, float* out, int frames) {
        if (frames <= 0) return;
        for (int i = 0; i < frames; ++i) {
            appendFrame(in + static_cast<size_t>(i) * channels_);
            float l = 0.0f;
            float r = 0.0f;
            if (!nextFrame(l, r)) {
                // Not enough source yet. Passing the input through keeps the
                // frame count exact; the alternative is a gap at every start.
                l = in[static_cast<size_t>(i) * channels_];
                r = (channels_ == 2) ? in[static_cast<size_t>(i) * channels_ + 1] : l;
            }
            out[static_cast<size_t>(i) * channels_] = l;
            if (channels_ == 2) out[static_cast<size_t>(i) * channels_ + 1] = r;
        }
    }

private:
    void appendFrame(const float* frame) {
        if (filled_ >= capacity_) compact();
        historyL_[static_cast<size_t>(filled_)] = frame[0];
        historyR_[static_cast<size_t>(filled_)] = (channels_ == 2) ? frame[1] : frame[0];
        ++filled_;
    }

    /**
     * Slides the window down, keeping everything the stretcher can still
     * reach: its read position, a grain behind it for the search, and the
     * interpolator's own few samples.
     */
    void compact() {
        const int keepBehind = wsola_.window() + wsola_.searchRadius() + 8;
        int drop = static_cast<int>(wsola_.sourcePosition()) - keepBehind;
        if (drop <= 0) {
            // The stretcher has not moved far enough to free anything, which
            // means the buffer is too small for this grain. Start over rather
            // than write past the end.
            reset();
            return;
        }
        if (drop > filled_) drop = filled_;
        const size_t remaining = static_cast<size_t>(filled_ - drop);
        std::memmove(historyL_.data(), historyL_.data() + drop, remaining * sizeof(float));
        std::memmove(historyR_.data(), historyR_.data() + drop, remaining * sizeof(float));
        filled_ -= drop;
        wsola_.rebaseSource(drop);
    }

    /** One output frame, or false while the stretcher is still short of source. */
    bool nextFrame(float& l, float& r) {
        // Nothing is produced until the buffer is a full grain plus a search
        // ahead of the read position, and this is not an optimisation -- it is
        // the only thing that makes the loop stable.
        //
        // Each output frame consumes exactly one input frame, by construction:
        // the stretcher advances `hop / ratio` per grain and the resampler eats
        // `ratio` stretched frames per output frame, which multiply to one. So
        // the gap between what has arrived and where the stretcher is reading
        // never grows on its own -- whatever it is when output starts, it stays
        // forever. Start with too small a gap and `generate` can never find a
        // whole grain ahead of itself, and the shifter produces almost nothing
        // for the rest of the stream. Opening the gap once, here, is what
        // [latencyFrames] is reporting.
        if (filled_ < primeTarget_) return false;
        // Hermite needs one sample beyond the fractional position, so the
        // ring always runs one ahead of what is being read.
        while (stretchedPos_ >= 1.0) {
            float sl = 0.0f;
            float sr = 0.0f;
            if (!wsola_.pull(historyL_.data(), historyR_.data(), filled_, sl, sr)) {
                return false;
            }
            histL_[0] = histL_[1]; histL_[1] = histL_[2]; histL_[2] = histL_[3]; histL_[3] = sl;
            histR_[0] = histR_[1]; histR_[1] = histR_[2]; histR_[2] = histR_[3]; histR_[3] = sr;
            stretchedPos_ -= 1.0;
            ++stretchedCount_;
        }
        if (stretchedCount_ < 4) {
            // Fewer than four stretched samples in hand: the interpolator has
            // no window yet.
            stretchedPos_ += ratio_;
            return false;
        }
        const float t = static_cast<float>(stretchedPos_);
        l = hermite(histL_, t);
        r = (channels_ == 2) ? hermite(histR_, t) : l;
        stretchedPos_ += ratio_;
        return true;
    }

    /**
     * Four-point Hermite, not linear.
     *
     * Linear interpolation is a lowpass whose corner moves with the fractional
     * position, so a shifted stream loses air unevenly and the loss modulates
     * at the resampling period -- audible as a dull, slightly grainy top end.
     * Four points cost three more multiply-adds and remove it.
     */
    static float hermite(const float* y, float t) {
        const float c0 = y[1];
        const float c1 = 0.5f * (y[2] - y[0]);
        const float c2 = y[0] - 2.5f * y[1] + 2.0f * y[2] - 0.5f * y[3];
        const float c3 = 0.5f * (y[3] - y[0]) + 1.5f * (y[1] - y[2]);
        return ((c3 * t + c2) * t + c1) * t + c0;
    }

    Wsola wsola_;
    WsolaQuality quality_ = WsolaQuality::kBalanced;
    int channels_ = 2;
    double ratio_ = 1.0;

    std::vector<float> historyL_;
    std::vector<float> historyR_;
    int capacity_ = 0;
    int filled_ = 0;
    int primeTarget_ = 0;

    double stretchedPos_ = 0.0;
    long long stretchedCount_ = 0;
    float histL_[4] = {};
    float histR_[4] = {};
};

}  // namespace tryptify
