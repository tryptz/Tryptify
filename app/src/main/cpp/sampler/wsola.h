// SPDX-License-Identifier: GPL-3.0-or-later
//
// Time stretching, so speed and pitch can move independently.

#pragma once

#include <cmath>
#include <cstring>

namespace tryptify {

/** Longest grain supported. 2048 at 48 kHz is ~43 ms. */
inline constexpr int kWsolaMaxWindow = 2048;

/** Samples compared when searching for the best splice point. */
inline constexpr int kWsolaOverlap = 256;

namespace detail {

/**
 * One Hann table for every stretcher in the process.
 *
 * A per-instance table would be 8 KB times however many voices exist, which at
 * 32 voices is a quarter of a megabyte of identical cosines. Because the window
 * is always a power of two no larger than [kWsolaMaxWindow], a single
 * full-length table serves every size exactly: a Hann of length N wants
 * `0.5 - 0.5 cos(2*pi*i/N)`, which is entry `i * (kWsolaMaxWindow / N)` of this
 * one. No interpolation, no rounding — the smaller windows are exact
 * subsamplings.
 *
 * Built during library load rather than lazily, so no audio-thread access ever
 * hits a thread-safe-static guard or computes a cosine.
 */
struct HannTable {
    float v[kWsolaMaxWindow];

    HannTable() {
        for (int i = 0; i < kWsolaMaxWindow; ++i) {
            v[i] = 0.5f - 0.5f * std::cos(2.0f * 3.14159265358979f *
                                          static_cast<float>(i) /
                                          static_cast<float>(kWsolaMaxWindow));
        }
    }
};

inline const HannTable kHann;

}  // namespace detail

/**
 * WSOLA — waveform-similarity overlap-add.
 *
 * ## Why this and not a phase vocoder
 *
 * The job is to change how long a sample takes to play without changing what
 * it sounds like. Resampling cannot do it: reading faster raises the pitch,
 * which is varispeed — useful, and offered as its own mode, but not the same
 * thing.
 *
 * WSOLA cuts the source into overlapping grains and lays them back down at a
 * different spacing. The "similarity" part is the whole trick: before laying
 * each grain down it searches a small neighbourhood for the position whose
 * waveform best continues what was already written, so successive grains stay
 * phase-aligned and the overlap does not comb-filter. Laying grains down at
 * fixed positions — plain SOLA — cancels partials wherever the phases happen
 * to disagree, and sounds like a flanger nobody asked for.
 *
 * A phase vocoder would be better on sustained polyphonic material and much
 * worse here: it needs an FFT per grain, it smears transients (the "phasiness"
 * that makes stretched drums sound like wet cardboard), and a sampler is
 * mostly pointed at drums. WSOLA is cheap enough to run per voice on the audio
 * thread, which a phase vocoder is not.
 *
 * ## Stereo
 *
 * Both channels are spliced at the *same* offset, chosen by correlating the
 * mid signal (L+R). Running two independent stretchers instead would let the
 * channels pick different splice points, and since the offset is what sets the
 * phase of everything in the grain, the image would swim between the speakers
 * on every grain boundary — audible as a wandering, unstable stereo picture.
 * Correlating the mid rather than the left channel alone matters for material
 * where one side is momentarily silent: a hard-panned fill would otherwise be
 * spliced against nothing.
 *
 * ## Real-time contract
 *
 * Everything is a fixed-size member. No allocation, no locks, no I/O. The
 * search is a bounded correlation over [kWsolaOverlap] samples across
 * `2 * searchRadius + 1` offsets, so the worst case per grain is known ahead
 * of time and does not depend on the audio.
 *
 * Cost is roughly `2 * searchRadius * kWsolaOverlap / hop` multiply-adds per
 * output sample — about 128 at BALANCED and 256 at HIGH. It is a dot product
 * over contiguous floats, which is the one shape a compiler reliably
 * vectorises.
 *
 * Bypassed entirely at unity ratio — see [Voice], where a voice that is
 * neither stretched nor pitched reads the sample directly. Nothing here runs
 * for the common case of a sample played as recorded.
 */
class Wsola {
public:
    static constexpr int kMaxWindow = kWsolaMaxWindow;
    static constexpr int kOverlap = kWsolaOverlap;

    /**
     * Search radius as a fraction of the window. A quarter of a 2048-sample
     * window reaches a 94 Hz period at 48 kHz, which covers bass; an eighth
     * would stop at 188 Hz and leave low notes drifting.
     */
    static constexpr int DEFAULT_SEARCH_DIVISOR = 4;

    /**
     * Sets the grain length and how far the similarity search may look.
     *
     * A longer window preserves tonal material better and smears transients
     * more. The size is rounded down to a power of two, which costs nothing
     * musically (1024 and 1000 samples are the same grain) and lets every
     * window share one Hann table exactly.
     *
     * The search radius is the parameter that actually decides how low the
     * stretcher works, and it is easy to set too small. To splice without a
     * phase discontinuity the search has to be able to reach a whole period of
     * the frequency being spliced, so the lowest frequency it can hold
     * together is `sampleRate / searchRadius`. At 48 kHz:
     *
     * | radius | lowest frequency held |
     * |--------|-----------------------|
     * | 128    | 375 Hz                |
     * | 256    | 188 Hz                |
     * | 512    | 94 Hz                 |
     *
     * Anything below that floor still stretches, but its pitch wanders,
     * because the splices land mid-cycle and eat or add partial periods. A
     * 110 Hz bass note through a 128-sample radius measures about 8% flat —
     * which is what [DEFAULT_SEARCH_DIVISOR] and the engine's 2048-sample
     * window are chosen to avoid.
     */
    void configure(int windowSize, int searchRadius) {
        int size = windowSize;
        if (size < 128) size = 128;
        if (size > kMaxWindow) size = kMaxWindow;
        // Round down to a power of two so `window_table_[i * stride]` is an
        // exact Hann of this length rather than a resampled approximation.
        int pow2 = 128;
        while (pow2 * 2 <= size) pow2 *= 2;
        window_ = pow2;
        hop_ = window_ / 2;
        windowStride_ = kMaxWindow / window_;

        searchRadius_ = searchRadius < 0 ? 0 : searchRadius;
        if (searchRadius_ > window_ / 2) searchRadius_ = window_ / 2;

        reset(0.0);
    }

    /** Starts again at [sourceStart], discarding any pending output. */
    void reset(double sourceStart) {
        readPos_ = sourceStart;
        pending_ = 0;
        pendingRead_ = 0;
        naturalNext_ = 0;
        std::memset(tailL_, 0, sizeof(tailL_));
        std::memset(tailR_, 0, sizeof(tailR_));
        std::memset(target_, 0, sizeof(target_));
        primed_ = false;
    }

    /**
     * Output samples per input sample. 2 plays half as fast, 0.5 twice as
     * fast. Clamped to a range where the algorithm still behaves.
     */
    void setRatio(double ratio) {
        if (ratio < 0.05) ratio = 0.05;
        if (ratio > 20.0) ratio = 20.0;
        ratio_ = ratio;
    }

    double ratio() const { return ratio_; }

    /** Where in the source the next grain will be taken from. */
    double sourcePosition() const { return readPos_; }

    int window() const { return window_; }
    int searchRadius() const { return searchRadius_; }

    /**
     * Fills [outL]/[outR] with up to [count] stretched frames read from
     * `src[0, srcLen)`. Returns how many were produced — fewer than asked for
     * means the source ran out.
     *
     * [srcR] null means a mono source; [outR] null means the caller does not
     * want the right channel written.
     */
    int produce(const float* srcL, const float* srcR, int srcLen,
                float* outL, float* outR, int count) {
        int written = 0;
        while (written < count) {
            if (pendingRead_ >= pending_) {
                if (!generate(srcL, srcR, srcLen)) break;
            }
            const int available = pending_ - pendingRead_;
            const int take = (count - written) < available ? (count - written) : available;
            std::memcpy(outL + written, outL_ + pendingRead_, sizeof(float) * take);
            if (outR != nullptr) {
                const float* from = (srcR != nullptr) ? (outR_ + pendingRead_) : (outL_ + pendingRead_);
                std::memcpy(outR + written, from, sizeof(float) * take);
            }
            pendingRead_ += take;
            written += take;
        }
        return written;
    }

    /** Mono convenience, used by the tests and by offline rendering. */
    int produce(const float* src, int srcLen, float* out, int count) {
        return produce(src, nullptr, srcLen, out, nullptr, count);
    }

    /**
     * One frame at a time.
     *
     * The voice reads the stretched stream through a fractional resampler for
     * pitch, which needs samples one at a time and cannot know in advance how
     * many it will consume. Returns false when the source is exhausted, which
     * is how the voice learns the sample has ended.
     */
    bool pull(const float* srcL, const float* srcR, int srcLen, float& l, float& r) {
        if (pendingRead_ >= pending_) {
            if (!generate(srcL, srcR, srcLen)) return false;
        }
        l = outL_[pendingRead_];
        r = (srcR != nullptr) ? outR_[pendingRead_] : l;
        ++pendingRead_;
        return true;
    }

private:
    /**
     * Produces one grain's worth of output — [hop_] frames.
     *
     * Returns false when the source cannot supply a full window any more,
     * which is how the voice learns it has reached the end.
     */
    bool generate(const float* srcL, const float* srcR, int srcLen) {
        const int base = static_cast<int>(readPos_);
        if (base < 0 || base + window_ >= srcLen) return false;

        int offset = 0;
        if (primed_ && searchRadius_ > 0) offset = bestOffset(srcL, srcR, srcLen, base);
        const int start = clampStart(base + offset, srcLen);

        const float* table = detail::kHann.v;
        const int stride = windowStride_;

        // First half overlaps the previous grain's tail; second half becomes
        // the next one's. Because two Hann windows at 50% overlap sum to one,
        // adding the windowed halves reconstructs unity gain without a
        // normalisation pass.
        for (int i = 0; i < hop_; ++i) {
            const float w = table[i * stride];
            outL_[i] = primed_ ? (tailL_[i] + srcL[start + i] * w) : srcL[start + i];
        }
        for (int i = 0; i < hop_; ++i) {
            tailL_[i] = srcL[start + hop_ + i] * table[(hop_ + i) * stride];
        }
        if (srcR != nullptr) {
            for (int i = 0; i < hop_; ++i) {
                const float w = table[i * stride];
                outR_[i] = primed_ ? (tailR_[i] + srcR[start + i] * w) : srcR[start + i];
            }
            for (int i = 0; i < hop_; ++i) {
                tailR_[i] = srcR[start + hop_ + i] * table[(hop_ + i) * stride];
            }
        }

        pending_ = hop_;
        pendingRead_ = 0;
        primed_ = true;

        // Where the *natural* continuation of this grain lives. Cached as mid
        // samples because the next search correlates every candidate against
        // it, and recomputing `L + R` per candidate would double the inner
        // loop for a value that does not change between candidates.
        naturalNext_ = start + hop_;
        if (naturalNext_ >= 0 && naturalNext_ + kOverlap < srcLen) {
            if (srcR != nullptr) {
                for (int i = 0; i < kOverlap; ++i) {
                    target_[i] = srcL[naturalNext_ + i] + srcR[naturalNext_ + i];
                }
            } else {
                for (int i = 0; i < kOverlap; ++i) target_[i] = srcL[naturalNext_ + i];
            }
            targetValid_ = true;
        } else {
            targetValid_ = false;
        }

        // Analysis hop. Dividing the synthesis hop by the ratio is what
        // actually performs the stretch: a ratio of 2 advances through the
        // source at half speed while output continues at full speed.
        readPos_ += static_cast<double>(hop_) / ratio_;
        return true;
    }

    /**
     * Finds the offset whose waveform best continues what was just written.
     *
     * Plain cross-correlation rather than a normalised one: the candidates all
     * come from the same neighbourhood of the same signal, so their energies
     * are comparable and the normalisation would cost a square root per
     * candidate to change almost nothing.
     */
    int bestOffset(const float* srcL, const float* srcR, int srcLen, int base) const {
        if (!targetValid_) return 0;

        float best = -1e30f;
        int bestK = 0;
        for (int k = -searchRadius_; k <= searchRadius_; ++k) {
            const int candidate = base + k;
            if (candidate < 0 || candidate + kOverlap >= srcLen) continue;
            float sum = 0.0f;
            if (srcR != nullptr) {
                for (int i = 0; i < kOverlap; ++i) {
                    sum += (srcL[candidate + i] + srcR[candidate + i]) * target_[i];
                }
            } else {
                for (int i = 0; i < kOverlap; ++i) {
                    sum += srcL[candidate + i] * target_[i];
                }
            }
            if (sum > best) {
                best = sum;
                bestK = k;
            }
        }
        return bestK;
    }

    int clampStart(int start, int srcLen) const {
        if (start < 0) return 0;
        if (start + window_ >= srcLen) return srcLen - window_ - 1;
        return start;
    }

    int window_ = 1024;
    int hop_ = 512;
    int windowStride_ = kMaxWindow / 1024;
    int searchRadius_ = 128;
    double ratio_ = 1.0;

    double readPos_ = 0.0;
    int naturalNext_ = 0;
    bool primed_ = false;
    bool targetValid_ = false;

    int pending_ = 0;
    int pendingRead_ = 0;

    float tailL_[kMaxWindow / 2] = {};
    float tailR_[kMaxWindow / 2] = {};
    float outL_[kMaxWindow / 2] = {};
    float outR_[kMaxWindow / 2] = {};
    float target_[kOverlap] = {};
};

}  // namespace tryptify
