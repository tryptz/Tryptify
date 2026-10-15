// Dynamic range, the way the DR meter (the Pleasurize Music Foundation's "TT
// DR", as foobar2000 and dr14 measure it) reads a track:
//
// - each channel is cut into 3 s blocks; a block's RMS is sqrt(2 · mean x²),
//   so a full-scale sine reads 0 dBFS, as its peak does;
// - its loudness is the RMS of the loudest 20 % of those blocks;
// - its peak is the second highest of the blocks' peaks, so one stray sample
//   does not decide it;
// - DR = 20 · log10(peak / loudness), in dB, averaged over the channels.
//
// A brickwalled master reads about DR4, a dynamic one DR14 and up. The block
// still filling when the track ends is left out: the reading is the one after
// the last whole block, so it is ready the moment the track is.
//
// Header-only and free of Android so the host tests can build it with the
// desktop compiler. The JNI wrapper (dynamic_range_jni.cpp) owns the
// threading; this class is single-threaded and does its work in process().
//
// Audio-thread rules (docs/agent-playbook.md, Realtime Audio): process()
// allocates nothing, locks nothing and logs nothing. Every buffer is a fixed
// array sized at compile time. Built with -ffast-math, so nothing here makes a
// NaN or an infinity: "no reading" is the kNone sentinel, and non-finite input
// is silence.

#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

#include "finite.h"

namespace tryptify {

class DynamicRangeMeter {
public:
    /** The tap sees up to 24 channels (9.1.4 / 9.1.6 travel as 24-channel frames). */
    static constexpr int kMaxChannels = 24;
    /** Channels measured: the first eight, the LFE left out. A stereo track is two. */
    static constexpr int kMaxMeasured = 8;
    /** Blocks kept: two hours of 3 s blocks. Past that the reading stays as it was. */
    static constexpr int kMaxBlocks = 2400;
    /** "There is no reading": too little audio yet, or only silence. */
    static constexpr float kNone = -1000.f;

    DynamicRangeMeter() { reset(); }

    /** Whether [configure] with this format would start the measurement again. */
    bool formatChanges(int sampleRate, int channels) const {
        clampFormat(sampleRate, channels);
        return sampleRate != sampleRate_ || channels != channels_;
    }

    /** Sets the stream format. A change of either starts the measurement again. */
    void configure(int sampleRate, int channels) {
        clampFormat(sampleRate, channels);
        if (sampleRate == sampleRate_ && channels == channels_) return;
        sampleRate_ = sampleRate;
        channels_ = channels;
        blockFrames_ = sampleRate_ * 3;
        // Which of the stream's channels are measured, in Android's order:
        // FL FR FC LFE BL BR …, the LFE (index 3 of 6 and more) left out.
        measured_ = 0;
        for (int c = 0; c < channels_ && measured_ < kMaxMeasured; ++c) {
            if (channels_ >= 6 && c == 3) continue;
            source_[measured_++] = c;
        }
        reset();
    }

    /** Starts again: a new track. */
    void reset() {
        std::memset(sumSq_, 0, sizeof(sumSq_));
        std::memset(blockPeak_, 0, sizeof(blockPeak_));
        std::memset(peak1_, 0, sizeof(peak1_));
        std::memset(peak2_, 0, sizeof(peak2_));
        blockFill_ = 0;
        blocks_ = 0;
        frames_ = 0;
        reading_ = kNone;
    }

    /** [frames] interleaved frames of the configured channel count. */
    void process(const float* interleaved, int frames) {
        const int ch = channels_;
        for (int f = 0; f < frames; ++f) {
            const float* frame = interleaved + size_t(f) * ch;
            for (int m = 0; m < measured_; ++m) {
                float x = frame[source_[m]];
                if (!dspIsFinite(x)) x = 0.f;
                sumSq_[m] += double(x) * x;
                const float a = x < 0.f ? -x : x;
                if (a > blockPeak_[m]) blockPeak_[m] = a;
            }
            if (++blockFill_ == blockFrames_) closeBlock();
        }
        frames_ += frames;
    }

    /** DR in dB after the last whole block, or [kNone]. */
    float reading() const { return reading_; }

    /** Frames measured since the last reset, the unfinished block included. */
    int64_t frames() const { return frames_; }

    /** Whole blocks measured since the last reset. */
    int blocks() const { return blocks_; }

    int sampleRate() const { return sampleRate_; }

private:
    static void clampFormat(int& sampleRate, int& channels) {
        if (channels < 1) channels = 1;
        if (channels > kMaxChannels) channels = kMaxChannels;
        if (sampleRate < 8000) sampleRate = 8000;
    }

    void closeBlock() {
        if (blocks_ < kMaxBlocks) {
            for (int m = 0; m < measured_; ++m) {
                rms2_[m][blocks_] = float(2.0 * sumSq_[m] / blockFrames_);
                const float p = blockPeak_[m];
                if (p > peak1_[m]) {
                    peak2_[m] = peak1_[m];
                    peak1_[m] = p;
                } else if (p > peak2_[m]) {
                    peak2_[m] = p;
                }
            }
            ++blocks_;
            reading_ = compute();
        }
        std::memset(sumSq_, 0, sizeof(sumSq_));
        std::memset(blockPeak_, 0, sizeof(blockPeak_));
        blockFill_ = 0;
    }

    /** DR over the blocks so far: a partial sort per channel, every 3 s. */
    float compute() {
        const int n = blocks_;
        const int top = std::max(1, n / 5);
        double sum = 0.0;
        int counted = 0;
        for (int m = 0; m < measured_; ++m) {
            std::memcpy(scratch_, rms2_[m], sizeof(float) * size_t(n));
            std::nth_element(scratch_, scratch_ + (n - top), scratch_ + n);
            double loud = 0.0;
            for (int i = n - top; i < n; ++i) loud += scratch_[i];
            const double rms = std::sqrt(loud / top);
            // With one block there is no second peak: the only one stands in.
            const double peak = n >= 2 ? peak2_[m] : peak1_[m];
            if (rms < kSilentLinear || peak < kSilentLinear) continue;  // a silent channel says nothing
            sum += 20.0 * std::log10(peak / rms);
            ++counted;
        }
        if (counted == 0) return kNone;
        const double dr = sum / counted;
        return dspIsFinite(dr) ? float(dr) : kNone;
    }

    /** About -120 dBFS: below this a block is silence, not a level. */
    static constexpr double kSilentLinear = 1e-6;

    int sampleRate_ = 0;
    int channels_ = 0;
    int blockFrames_ = 0;
    int measured_ = 0;
    int source_[kMaxMeasured] = {};

    double sumSq_[kMaxMeasured];
    float blockPeak_[kMaxMeasured];
    float peak1_[kMaxMeasured];
    float peak2_[kMaxMeasured];
    float rms2_[kMaxMeasured][kMaxBlocks];
    float scratch_[kMaxBlocks];

    int blockFill_ = 0;
    int blocks_ = 0;
    int64_t frames_ = 0;
    float reading_ = kNone;
};

}  // namespace tryptify
