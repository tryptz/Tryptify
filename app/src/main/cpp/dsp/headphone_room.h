// SPDX-License-Identifier: GPL-3.0-or-later
//
// The Crossfeed page's Room: the mixer's Binaural Room (snapins/binaural_room.h)
// on its own, run by the player where crossfeed runs, with two things the
// mixer's effect has not got:
//
// - Its own HRTF: a set packed from a listener's .sofa, in place of the
//   built-in KU100 (BinauralRoomProcessor::setSofaSet).
//
// - An LFE: the bass below a crossover taken out of the room and played
//   straight to both ears, as one channel, at its own level. The split is
//   linear phase and complementary: the bass is a symmetric FIR low-pass of
//   the song (a Hann-windowed sinc, DC gain exactly 1), and what goes into
//   the room is the song delayed by that filter's delay minus the bass. The
//   two add back to the song, delayed, whatever the crossover, so the split
//   bends no phase of its own; with the room passing its input through they
//   are the song itself, the bass now in the middle. The bass is then
//   delayed by the room's own latency, so it meets the room's output on time.
//
// The FIR is 2048 taps at 44.1 or 48 kHz and longer in step with the rate,
// so its delay is the same 21 ms at every rate and its transition the same
// width (about 75 Hz, centred on the crossover). It runs as a partitioned
// convolution (atmos/render/partitioned_fir.h).
//
// Threads: prepare(), setSofaSet() and clearSofaSet() allocate and may build
// for a moment, never on the audio thread while it plays; setParameter() and
// process() are the audio thread's. The LFE's own state is the audio thread's
// alone.
#ifndef TRYPT_DSP_HEADPHONE_ROOM_H
#define TRYPT_DSP_HEADPHONE_ROOM_H

#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>

#include "snapins/binaural_room.h"
#include "../atmos/render/partitioned_fir.h"

class HeadphoneRoom {
public:
    // The room's own nine (BinauralRoomProcessor::Params), then the LFE's.
    enum Params {
        ROOM = 0, BINAURAL, ANGLE, OUTPUT, MY_ROOM, WIDTH, DEPTH, HEIGHT, DAMPING,
        LFE_ON, LFE_HZ, LFE_DB, NUM_PARAMS,
    };

    static constexpr float kMinLfeHz = 40.0f, kMaxLfeHz = 200.0f, kDefaultLfeHz = 80.0f;
    static constexpr float kMinLfeDb = -12.0f, kMaxLfeDb = 6.0f;
    // The most a call is split at once; longer ones are taken in pieces.
    static constexpr int kChunk = 1024;

    void prepare(double sampleRate, int maxBlockSize) {
        rate_ = sampleRate;
        room_.prepare(sampleRate, maxBlockSize);
        // The FIR as long as 2048 taps is at 48 kHz: the same delay and
        // transition at every rate.
        const double scale = sampleRate / 48000.0;
        if (scale <= 1.05) fir_.reset(new Fir<2048>());
        else if (scale <= 2.1) fir_.reset(new Fir<4096>());
        else fir_.reset(new Fir<8192>());
        taps_ = fir_->taps();
        h_.assign(static_cast<size_t>(taps_), 0.0f);
        hd_.assign(static_cast<size_t>(taps_), 0.0);
        // An odd length round its middle: the last tap stays 0.
        splitDelay_ = (taps_ - 2) / 2;
        designedHz_ = -1.0f;
        design();
        inter_.assign(static_cast<size_t>(kChunk) * 2, 0.0f);
        lfe_.assign(static_cast<size_t>(kChunk), 0.0f);
        songDelay_.prepare(splitDelay_, kChunk);
        lfeDelay_.prepare(std::max(0, room_.ownLatency()), kChunk);
        resetSplit();
    }

    void setParameter(int index, float value) {
        if (index >= ROOM && index <= DAMPING) {
            room_.setParameter(index, value);
            return;
        }
        switch (index) {
            case LFE_ON: {
                const bool on = value >= 0.5f;
                if (on != lfeOn_) resetSplit();
                lfeOn_ = on;
                break;
            }
            case LFE_HZ:
                lfeHz_ = std::max(kMinLfeHz, std::min(kMaxLfeHz, value));
                break;
            case LFE_DB:
                lfeDb_ = std::max(kMinLfeDb, std::min(kMaxLfeDb, value));
                lfeGain_ = std::pow(10.0f, lfeDb_ / 20.0f);
                break;
        }
    }

    float getParameter(int index) const {
        if (index >= ROOM && index <= DAMPING) return room_.getParameter(index);
        switch (index) {
            case LFE_ON: return lfeOn_ ? 1.0f : 0.0f;
            case LFE_HZ: return lfeHz_;
            case LFE_DB: return lfeDb_;
        }
        return 0.0f;
    }

    bool setSofaSet(const float* packed, size_t size) { return room_.setSofaSet(packed, size); }
    void clearSofaSet() { room_.clearSofaSet(); }

    // How late the output is: the room's, and the split's while the LFE is on.
    int latency() const { return room_.ownLatency() + (lfeOn_ && fir_ ? splitDelay_ : 0); }
    bool settled() const { return room_.roomSettled(); }
    int splitDelay() const { return splitDelay_; }

    void reset() {
        room_.reset();
        resetSplit();
    }

    void process(float* left, float* right, int numFrames) {
        if (numFrames <= 0) return;
        if (!lfeOn_ || !fir_) {
            room_.process(left, right, numFrames);
            return;
        }
        if (lfeHz_ != designedHz_) design();
        for (int done = 0; done < numFrames; done += kChunk) {
            const int n = std::min(kChunk, numFrames - done);
            float* l = left + done;
            float* r = right + done;
            // The bass of each side, by the FIR.
            for (int i = 0; i < n; i++) {
                inter_[static_cast<size_t>(2 * i)] = l[i];
                inter_[static_cast<size_t>(2 * i + 1)] = r[i];
            }
            fir_->process(inter_.data(), n);
            // The song as late as the bass, less the bass: the room's share.
            songDelay_.push(l, r, n);
            for (int i = 0; i < n; i++) {
                const float lowL = inter_[static_cast<size_t>(2 * i)];
                const float lowR = inter_[static_cast<size_t>(2 * i + 1)];
                l[i] = songDelay_.outL(i) - lowL;
                r[i] = songDelay_.outR(i) - lowR;
                lfe_[static_cast<size_t>(i)] = 0.5f * (lowL + lowR);
            }
            room_.process(l, r, n);
            // The LFE as late as the room, at its level, to both ears.
            lfeDelay_.push(lfe_.data(), lfe_.data(), n);
            const float g = lfeGain_;
            for (int i = 0; i < n; i++) {
                const float b = lfeDelay_.outL(i) * g;
                l[i] += b;
                r[i] += b;
            }
        }
    }

private:
    struct FirBase {
        virtual ~FirBase() = default;
        virtual int taps() const = 0;
        virtual void set(const float* h) = 0;
        virtual void reset() = 0;
        virtual void process(float* io, int m) = 0;
    };
    template <int N>
    struct Fir : FirBase {
        tf::atmos::render::StereoPartitionedFir<N> f;
        int taps() const override { return N; }
        void set(const float* h) override { f.set_filter(h); }
        void reset() override { f.reset(); }
        void process(float* io, int m) override { f.process(io, m); }
    };

    // A delay line of two channels: push n, then read the n that come out.
    class Delay {
    public:
        void prepare(int delay, int chunk) {
            delay_ = delay;
            size_ = delay + chunk + 1;
            l_.assign(static_cast<size_t>(size_), 0.0f);
            r_.assign(static_cast<size_t>(size_), 0.0f);
            outL_.assign(static_cast<size_t>(chunk), 0.0f);
            outR_.assign(static_cast<size_t>(chunk), 0.0f);
            write_ = 0;
        }
        void reset() {
            std::fill(l_.begin(), l_.end(), 0.0f);
            std::fill(r_.begin(), r_.end(), 0.0f);
            write_ = 0;
        }
        void push(const float* l, const float* r, int n) {
            for (int i = 0; i < n; i++) {
                l_[static_cast<size_t>(write_)] = l[i];
                r_[static_cast<size_t>(write_)] = r[i];
                int read = write_ - delay_;
                if (read < 0) read += size_;
                outL_[static_cast<size_t>(i)] = l_[static_cast<size_t>(read)];
                outR_[static_cast<size_t>(i)] = r_[static_cast<size_t>(read)];
                if (++write_ == size_) write_ = 0;
            }
        }
        float outL(int i) const { return outL_[static_cast<size_t>(i)]; }
        float outR(int i) const { return outR_[static_cast<size_t>(i)]; }
    private:
        int delay_ = 0, size_ = 1, write_ = 0;
        std::vector<float> l_, r_, outL_, outR_;
    };

    // The low-pass at the crossover: a sinc windowed by a Hann over the
    // first taps - 1 taps, scaled to a DC gain of exactly 1.
    void design() {
        designedHz_ = lfeHz_;
        if (!fir_) return;
        const int m = taps_ - 1;
        const int mid = splitDelay_;
        const double fc = static_cast<double>(lfeHz_) / rate_;
        double sum = 0.0;
        std::vector<double>& h = hd_;  // sized by prepare(): nothing allocated here
        for (int k = 0; k < m; k++) {
            const int t = k - mid;
            const double sinc = t == 0 ? 2.0 * fc : std::sin(6.283185307179586 * fc * t) / (3.141592653589793 * t);
            const double w = 0.5 - 0.5 * std::cos(6.283185307179586 * k / (m - 1));
            h[static_cast<size_t>(k)] = sinc * w;
            sum += h[static_cast<size_t>(k)];
        }
        for (int k = 0; k < m; k++) h_[static_cast<size_t>(k)] = static_cast<float>(h[static_cast<size_t>(k)] / sum);
        h_[static_cast<size_t>(m)] = 0.0f;
        fir_->set(h_.data());
    }

    void resetSplit() {
        if (fir_) fir_->reset();
        songDelay_.reset();
        lfeDelay_.reset();
    }

    BinauralRoomProcessor room_;
    std::unique_ptr<FirBase> fir_;
    double rate_ = 48000.0;
    int taps_ = 0;
    int splitDelay_ = 0;
    std::vector<float> h_, inter_, lfe_;
    std::vector<double> hd_;
    Delay songDelay_, lfeDelay_;
    bool lfeOn_ = false;
    float lfeHz_ = kDefaultLfeHz, designedHz_ = -1.0f;
    float lfeDb_ = 0.0f, lfeGain_ = 1.0f;
};

#endif  // TRYPT_DSP_HEADPHONE_ROOM_H
