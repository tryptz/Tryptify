// Host test for the effects' buffers: the delay lines and rings read as they
// always did without their %, no effect reads past a line at the far end of
// its knobs, a rate change starts every buffer silent, the limiter runs past
// the point where its sample counter used to overflow, and the small per-
// sample savings (the RMS follower, the ladder at no resonance, bitcrush's
// noise) change nothing they should not. Built with DSP_CHECK_DELAY_RANGE,
// and run again under AddressSanitizer:
//
//   ./run_host_tests.sh
//
// Without -ffast-math: the comparisons with the old code are bit for bit.

#include <climits>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

#include "snapins/bitcrush.h"
#include "snapins/chorus.h"
#include "snapins/comb_filter.h"
#include "snapins/compactor.h"
#include "snapins/compressor.h"
#include "snapins/delay.h"
#include "snapins/ensemble.h"
#include "snapins/flanger.h"
#include "snapins/gate.h"
#include "snapins/haas.h"
#include "snapins/ladder_filter.h"
#include "snapins/limiter.h"
#include "snapins/resonator.h"
#include "snapins/reverb.h"
#include "snapins/reverser.h"
#include "snapins/tape_stop.h"

#ifndef DSP_CHECK_DELAY_RANGE
#error "build with -DDSP_CHECK_DELAY_RANGE"
#endif

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

uint32_t seed = 3;
float noise() {
    seed = seed * 1664525u + 1013904223u;
    return static_cast<float>(static_cast<int32_t>(seed >> 8) - (1 << 23)) / static_cast<float>(1 << 23);
}

bool sameBits(float a, float b) { return std::memcmp(&a, &b, sizeof a) == 0; }

// ── The buffers as they were, with % ─────────────────────────────────────

namespace old {

class DelayLine {
public:
    void prepare(int maxDelaySamples) {
        buffer_.resize(maxDelaySamples + 4, 0.0f);
        writePos_ = 0;
    }
    void write(float sample) {
        buffer_[writePos_] = sample;
        writePos_ = (writePos_ + 1) % static_cast<int>(buffer_.size());
    }
    float read(int delaySamples) const {
        int idx = writePos_ - 1 - delaySamples;
        int sz = static_cast<int>(buffer_.size());
        while (idx < 0) idx += sz;
        return buffer_[idx % sz];
    }
    float readCubic(float delaySamples) const {
        int sz = static_cast<int>(buffer_.size());
        int intDelay = static_cast<int>(delaySamples);
        float frac = delaySamples - static_cast<float>(intDelay);
        auto at = [&](int d) -> float {
            int idx = writePos_ - 1 - d;
            while (idx < 0) idx += sz;
            return buffer_[idx % sz];
        };
        float y0 = at(intDelay + 1);
        float y1 = at(intDelay);
        float y2 = at(intDelay - 1);
        float y3 = at(intDelay - 2);
        float c0 = y1;
        float c1 = 0.5f * (y2 - y0);
        float c2 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
        float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
        return ((c3 * frac + c2) * frac + c1) * frac + c0;
    }
    float readLinear(float delaySamples) const {
        int sz = static_cast<int>(buffer_.size());
        int intDelay = static_cast<int>(delaySamples);
        float frac = delaySamples - static_cast<float>(intDelay);
        int idx1 = writePos_ - 1 - intDelay;
        int idx2 = idx1 - 1;
        while (idx1 < 0) idx1 += sz;
        while (idx2 < 0) idx2 += sz;
        return buffer_[idx1 % sz] * (1.0f - frac) + buffer_[idx2 % sz] * frac;
    }

private:
    std::vector<float> buffer_;
    int writePos_ = 0;
};

class LookaheadBuffer {
public:
    void prepare(int delaySamples) {
        delay_ = delaySamples;
        buffer_.resize(delaySamples, 0.0f);
        writePos_ = 0;
    }
    float process(float in) {
        if (delay_ == 0) return in;
        float out = buffer_[writePos_];
        buffer_[writePos_] = in;
        writePos_ = (writePos_ + 1) % delay_;
        return out;
    }

private:
    std::vector<float> buffer_;
    int writePos_ = 0;
    int delay_ = 0;
};

class CrossfadeBuffer {
public:
    void prepare(int maxSegmentSamples) {
        buffer_.resize(maxSegmentSamples * 2, 0.0f);
        writePos_ = 0;
    }
    void write(float sample) {
        buffer_[writePos_] = sample;
        writePos_ = (writePos_ + 1) % static_cast<int>(buffer_.size());
    }
    float readReverse(int offset) const {
        int sz = static_cast<int>(buffer_.size());
        int idx = writePos_ - 1 - offset;
        while (idx < 0) idx += sz;
        return buffer_[idx % sz];
    }

private:
    std::vector<float> buffer_;
    int writePos_ = 0;
};

// The limiter's sliding maximum and gain ramp as they were, on a sample
// counter. A long long here, so the copy itself stays defined past 2^31: it
// is what the int would have given had it not overflowed.
class Limiter {
public:
    void prepare(double sampleRate, float lookaheadMs, float releaseMs) {
        sampleRate_ = sampleRate;
        lookaheadMs_ = lookaheadMs;
        maxLookaheadSamples_ = static_cast<int>(0.010 * sampleRate) + 1;
        lookaheadSamples_ = std::max(1, static_cast<int>(lookaheadMs_ * 0.001f * static_cast<float>(sampleRate)));
        delayL_.prepare(maxLookaheadSamples_);
        delayR_.prepare(maxLookaheadSamples_);
        peakRing_.assign(maxLookaheadSamples_ + 1, 0.0f);
        dequeIdx_.assign(maxLookaheadSamples_ + 1, 0);
        ringSize_ = lookaheadSamples_;
        gainRamp_.assign(maxLookaheadSamples_ + 1, 1.0f);
        releaseCoeff_ = 1.0f - std::exp(-1.0f / (releaseMs * 0.001f * static_cast<float>(sampleRate_)));
    }
    // Where the counter would be after [samples] of playing.
    void seed(long long samples) {
        globalSampleIdx_ = samples;
        ringWritePos_ = static_cast<int>(samples % static_cast<long long>(peakRing_.size()));
    }
    void setLookahead(float ms) {
        lookaheadMs_ = ms;
        lookaheadSamples_ = std::max(1, static_cast<int>(lookaheadMs_ * 0.001f * static_cast<float>(sampleRate_)));
        ringSize_ = lookaheadSamples_;
    }
    void process(float* left, float* right, int numFrames, float thresholdDb) {
        const float threshLin = std::pow(10.0f, thresholdDb * 0.05f);
        const float outputLin = std::pow(10.0f, 0.0f * 0.05f);
        const float inputLin = outputLin;
        for (int i = 0; i < numFrames; i++) {
            float inL = left[i] * inputLin;
            float inR = right[i] * inputLin;
            delayL_.write(inL);
            delayR_.write(inR);
            float delL = delayL_.read(lookaheadSamples_);
            float delR = delayR_.read(lookaheadSamples_);
            float peak = std::max(std::fabs(inL), std::fabs(inR));
            peakRing_[ringWritePos_] = peak;
            long long currentIdx = globalSampleIdx_;
            while (dqFront_ != dqBack_) {
                int backPos = dqBack_ - 1;
                if (backPos < 0) backPos += static_cast<int>(dequeIdx_.size());
                long long backIdx = dequeIdx_[backPos];
                int ringPos = static_cast<int>(backIdx % static_cast<long long>(peakRing_.size()));
                if (peakRing_[ringPos] <= peak) dqBack_ = backPos; else break;
            }
            dequeIdx_[dqBack_] = currentIdx;
            dqBack_ = (dqBack_ + 1) % static_cast<int>(dequeIdx_.size());
            while (dqFront_ != dqBack_) {
                if (currentIdx - dequeIdx_[dqFront_] >= ringSize_) {
                    dqFront_ = (dqFront_ + 1) % static_cast<int>(dequeIdx_.size());
                } else {
                    break;
                }
            }
            float maxPeak = 0.0f;
            if (dqFront_ != dqBack_) {
                long long frontIdx = dequeIdx_[dqFront_];
                maxPeak = peakRing_[static_cast<int>(frontIdx % static_cast<long long>(peakRing_.size()))];
            }
            ringWritePos_ = (ringWritePos_ + 1) % static_cast<int>(peakRing_.size());
            globalSampleIdx_++;
            float targetGainDb = 0.0f;
            if (maxPeak > threshLin && threshLin > 0.0f) targetGainDb = 20.0f * std::log10(threshLin / maxPeak);
            if (targetGainDb < gainReductionDb_) {
                float diff = targetGainDb - gainReductionDb_;
                int rampLen = lookaheadSamples_;
                for (int s = 0; s < rampLen; s++) {
                    float t = static_cast<float>(s) / static_cast<float>(std::max(1, rampLen - 1));
                    float cosineGain = 0.5f * (1.0f - std::cos(3.14159265358979f * t));
                    int pos = (rampWritePos_ + s) % static_cast<int>(gainRamp_.size());
                    gainRamp_[pos] = std::min(gainRamp_[pos], gainReductionDb_ + diff * cosineGain);
                }
                gainReductionDb_ = targetGainDb;
            } else {
                gainReductionDb_ += releaseCoeff_ * (targetGainDb - gainReductionDb_);
            }
            float rampGainDb = gainRamp_[rampWritePos_];
            float appliedGainDb = std::min(rampGainDb, gainReductionDb_);
            gainRamp_[rampWritePos_] = 0.0f;
            rampWritePos_ = (rampWritePos_ + 1) % static_cast<int>(gainRamp_.size());
            float gainLin = std::pow(10.0f, appliedGainDb * 0.05f);
            left[i] = delL * gainLin * outputLin;
            right[i] = delR * gainLin * outputLin;
            left[i] = std::max(-1.0f, std::min(1.0f, left[i]));
            right[i] = std::max(-1.0f, std::min(1.0f, right[i]));
        }
    }

private:
    double sampleRate_ = 0.0;
    float lookaheadMs_ = 5.0f;
    float releaseCoeff_ = 0.0f;
    float gainReductionDb_ = 0.0f;
    old::DelayLine delayL_, delayR_;
    int lookaheadSamples_ = 0, maxLookaheadSamples_ = 0;
    std::vector<float> peakRing_;
    std::vector<long long> dequeIdx_;
    int ringSize_ = 0, ringWritePos_ = 0, dqFront_ = 0, dqBack_ = 0;
    long long globalSampleIdx_ = 0;
    std::vector<float> gainRamp_;
    int rampWritePos_ = 0;
};

// The envelope follower with its RMS coefficient worked out every sample.
class EnvelopeFollower {
public:
    void prepare(double sampleRate) { sampleRate_ = sampleRate; update(); }
    void setAttack(float ms) { attackMs_ = ms; update(); }
    void setRelease(float ms) { releaseMs_ = ms; update(); }
    void setMode(DetectionMode mode) { mode_ = mode; }
    float process(float input) {
        float level;
        if (mode_ == DetectionMode::RMS) {
            float rmsCoeff = 1.0f - std::exp(-1.0f / (0.01f * static_cast<float>(sampleRate_)));
            rmsSum_ += rmsCoeff * (input * input - rmsSum_);
            level = std::sqrt(std::max(rmsSum_, 0.0f));
        } else {
            level = std::fabs(input);
        }
        float coeff = (level > envelope_) ? attackCoeff_ : releaseCoeff_;
        envelope_ += coeff * (level - envelope_);
        return envelope_;
    }

private:
    void update() {
        if (sampleRate_ <= 0.0) return;
        attackCoeff_ = (attackMs_ <= 0.0f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (attackMs_ * 0.001f * static_cast<float>(sampleRate_)));
        releaseCoeff_ = (releaseMs_ <= 0.0f) ? 1.0f
            : 1.0f - std::exp(-1.0f / (releaseMs_ * 0.001f * static_cast<float>(sampleRate_)));
    }
    double sampleRate_ = 44100.0;
    float attackMs_ = 10.0f, releaseMs_ = 100.0f;
    DetectionMode mode_ = DetectionMode::Peak;
    float attackCoeff_ = 1.0f, releaseCoeff_ = 1.0f, envelope_ = 0.0f, rmsSum_ = 0.0f;
};

// The ladder's loop with the feedback term always taken, at any resonance.
class Ladder {
public:
    void prepare(double sampleRate) {
        sampleRate_ = sampleRate;
        osL_.prepare(sampleRate, 2);
        osR_.prepare(sampleRate, 2);
    }
    float cutoff = 1000.0f, resonance = 0.0f, driveDb = 0.0f, bias = 0.0f;
    int topology = 0;
    bool saturate = false;
    void process(float* left, float* right, int numFrames) {
        const float fc = std::min(cutoff, 0.45f * static_cast<float>(sampleRate_));
        float wc = 2.0f * static_cast<float>(M_PI) * fc / static_cast<float>(sampleRate_);
        float g = std::tan(wc * 0.25f);
        float gComp = g / (1.0f + g);
        float res = resonance / 100.0f * 4.0f;
        float driveLin = saturate ? std::pow(10.0f, driveDb / 20.0f) : 1.0f;
        for (int i = 0; i < numFrames; i++) {
            float upL[2], upR[2];
            osL_.upsample(&left[i], upL, 1);
            osR_.upsample(&right[i], upR, 1);
            for (int os = 0; os < 2; os++) {
                float inL = upL[os] * driveLin + bias;
                float inR = upR[os] * driveLin + bias;
                inL -= res * std::tanh(feedbackL_);
                inR -= res * std::tanh(feedbackR_);
                for (int s = 0; s < 4; s++) {
                    float v = (inL - stageL_[s]) * gComp;
                    float lp = v + stageL_[s];
                    stageL_[s] = lp + v;
                    if (saturate) {
                        if (topology == 0) stageL_[s] = std::tanh(stageL_[s]);
                        else { float x = stageL_[s]; stageL_[s] = (x > 0) ? x / (1.0f + x) : x / (1.0f - 0.5f * x); }
                    }
                    inL = stageL_[s];
                    v = (inR - stageR_[s]) * gComp;
                    lp = v + stageR_[s];
                    stageR_[s] = lp + v;
                    if (saturate) {
                        if (topology == 0) stageR_[s] = std::tanh(stageR_[s]);
                        else { float x = stageR_[s]; stageR_[s] = (x > 0) ? x / (1.0f + x) : x / (1.0f - 0.5f * x); }
                    }
                    inR = stageR_[s];
                }
                feedbackL_ = stageL_[3];
                feedbackR_ = stageR_[3];
                upL[os] = stageL_[3];
                upR[os] = stageR_[3];
            }
            osL_.downsample(upL, &left[i], 1);
            osR_.downsample(upR, &right[i], 1);
        }
    }

private:
    double sampleRate_ = 48000.0;
    float stageL_[4] = {}, stageR_[4] = {};
    float feedbackL_ = 0.0f, feedbackR_ = 0.0f;
    IirOversampler osL_, osR_;
};

}  // namespace old

// ── The buffers ──────────────────────────────────────────────────────────

void delayLineReadsAsBefore() {
    bool same = true;
    for (int maxDelay : {1, 7, 100, 4410}) {
        DelayLine now;
        old::DelayLine was;
        now.prepare(maxDelay);
        was.prepare(maxDelay);
        const int size = maxDelay + 4;
        for (int step = 0; step < 3 * size + 17; step++) {
            const float x = noise();
            now.write(x);
            was.write(x);
            for (int d = -2; d <= size - 1; d++) same = same && sameBits(now.read(d), was.read(d));
            for (int k = 0; k < 8; k++) {
                const float pos = (static_cast<float>(seed % 1000) / 1000.0f) * static_cast<float>(maxDelay + 2);
                noise();
                same = same && sameBits(now.readCubic(pos), was.readCubic(pos));
                same = same && sameBits(now.readLinear(pos), was.readLinear(pos));
            }
        }
    }
    check(same, "a delay line reads every delay it holds exactly as it did with %");
    check(DelayLine::overruns == 0, "and none of those reads counted as past the line");
    DelayLine probe;
    probe.prepare(10);
    probe.read(14);
    check(DelayLine::overruns == 1, "a read past the line is counted");
    DelayLine::overruns = 0;
}

void ringsReadAsBefore() {
    bool same = true;
    for (int delay : {1, 2, 5, 300}) {
        LookaheadBuffer now;
        old::LookaheadBuffer was;
        now.prepare(delay);
        was.prepare(delay);
        for (int i = 0; i < 5 * delay + 11; i++) {
            const float x = noise();
            same = same && sameBits(now.process(x), was.process(x));
        }
        // A knob turned mid-stream keeps what is held, as it always did.
        now.setDelay(delay + 3);
        was.prepare(delay + 3);
        for (int i = 0; i < 3 * delay + 7; i++) {
            const float x = noise();
            same = same && sameBits(now.process(x), was.process(x));
        }
    }
    check(same, "a lookahead buffer delays exactly as it did, its delay knob included");
    same = true;
    for (int seg : {3, 64, 1000}) {
        CrossfadeBuffer now;
        old::CrossfadeBuffer was;
        now.prepare(seg);
        was.prepare(seg);
        for (int i = 0; i < 5 * seg + 3; i++) {
            const float x = noise();
            now.write(x);
            was.write(x);
            for (int o = 0; o < 2 * seg; o += 1 + seg / 50) same = same && sameBits(now.readReverse(o), was.readReverse(o));
        }
    }
    check(same, "a crossfade buffer reads back exactly as it did");
}

void preparedBuffersStartSilent() {
    DelayLine d;
    d.prepare(50);
    for (int i = 0; i < 200; i++) d.write(1.0f);
    d.prepare(50);
    bool silent = true;
    for (int k = 0; k <= 53; k++) silent = silent && d.read(k) == 0.0f;
    LookaheadBuffer la;
    la.prepare(20);
    for (int i = 0; i < 50; i++) la.process(1.0f);
    la.prepare(20);
    for (int i = 0; i < 20; i++) silent = silent && la.process(0.0f) == 0.0f;
    CrossfadeBuffer cf;
    cf.prepare(10);
    for (int i = 0; i < 50; i++) cf.write(1.0f);
    cf.prepare(10);
    for (int o = 0; o < 20; o++) silent = silent && cf.readReverse(o) == 0.0f;
    check(silent, "prepare() leaves a delay line, lookahead and crossfade buffer silent");
}

/**
 * Each effect that keeps audio in a buffer: a second of noise at 44.1 kHz,
 * then prepared at 48 kHz as a track change does, then a second of silence.
 * What comes out must be silence: before, resize() kept the old contents and
 * the new track's first moments played the old one's audio back.
 */
template <typename T>
bool silentAfterRateChange(void (*setup)(T&)) {
    auto p = std::make_unique<T>();
    if (setup) setup(*p);
    p->prepareOS(44100.0, 512);
    std::vector<float> l(512), r(512);
    for (int b = 0; b < 87; b++) {
        for (int i = 0; i < 512; i++) { l[i] = noise(); r[i] = noise(); }
        p->processOS(l.data(), r.data(), 512);
    }
    p->prepareOS(48000.0, 512);
    float loudest = 0.0f;
    for (int b = 0; b < 94; b++) {
        std::fill(l.begin(), l.end(), 0.0f);
        std::fill(r.begin(), r.end(), 0.0f);
        p->processOS(l.data(), r.data(), 512);
        for (int i = 0; i < 512; i++) loudest = std::max(loudest, std::max(std::fabs(l[i]), std::fabs(r[i])));
    }
    return loudest == 0.0f;
}

void aRateChangeKeepsNoOldAudio() {
    bool all = true;
    auto one = [&](bool ok, const char* name) {
        if (!ok) { std::printf("      %s played old audio after a rate change\n", name); all = false; }
    };
    one(silentAfterRateChange<ReverbProcessor>(nullptr), "reverb");
    one(silentAfterRateChange<DelayProcessor>([](DelayProcessor& p) { p.setParameter(DelayProcessor::TIME, 900.0f); }), "delay");
    one(silentAfterRateChange<ChorusProcessor>(nullptr), "chorus");
    one(silentAfterRateChange<FlangerProcessor>(nullptr), "flanger");
    one(silentAfterRateChange<HaasProcessor>(nullptr), "haas");
    one(silentAfterRateChange<CombFilterProcessor>(nullptr), "comb filter");
    one(silentAfterRateChange<ResonatorProcessor>(nullptr), "resonator");
    one(silentAfterRateChange<ReverserProcessor>(nullptr), "reverser");
    one(silentAfterRateChange<TapeStopProcessor>(nullptr), "tape stop");
    one(silentAfterRateChange<LimiterProcessor>(nullptr), "limiter");
    one(silentAfterRateChange<CompressorProcessor>([](CompressorProcessor& p) {
        p.setParameter(CompressorProcessor::LOOKAHEAD, 10.0f); }), "compressor");
    one(silentAfterRateChange<GateProcessor>(nullptr), "gate");
    one(silentAfterRateChange<CompactorProcessor>([](CompactorProcessor& p) {
        p.setParameter(CompactorProcessor::ATTACK, 40.0f); }), "compactor");
    check(all, "after a rate change, silence in is silence out: no old audio in any buffer");
}

// ── Reads within the line, at the far end of every knob ─────────────────

template <typename T>
void drive(T& p, double rate, int seconds) {
    p.prepareOS(rate, 1024);
    std::vector<float> l(1024), r(1024);
    const int blocks = static_cast<int>(seconds * rate / 1024);
    for (int b = 0; b < blocks; b++) {
        for (int i = 0; i < 1024; i++) { l[i] = 0.5f * noise(); r[i] = 0.5f * noise(); }
        p.processOS(l.data(), r.data(), 1024);
    }
}

void noEffectReadsPastItsLine() {
    DelayLine::overruns = 0;
    long chorusOverruns = 0;
    for (double rate : {22050.0, 44100.0, 48000.0, 96000.0, 192000.0}) {
        for (int os : {1, 4}) {
            for (int voices : {2, 3, 8}) {
                // The chorus at its widest: 40 ms, a full-depth sweep, many
                // voices. It read 82 ms back from an 81.3 ms line.
                ChorusProcessor c;
                c.setOversampling(os);
                c.setParameter(ChorusProcessor::DELAY_MS, 40.0f);
                c.setParameter(ChorusProcessor::DEPTH, 100.0f);
                c.setParameter(ChorusProcessor::RATE, 10.0f);
                c.setParameter(ChorusProcessor::VOICES, static_cast<float>(voices));
                c.setParameter(ChorusProcessor::FEEDBACK, 50.0f);
                drive(c, rate, 1);
            }
            chorusOverruns += DelayLine::overruns;
            DelayLine::overruns = 0;

            EnsembleProcessor en;
            en.setOversampling(os);
            en.setParameter(EnsembleProcessor::VOICES, 8.0f);
            en.setParameter(EnsembleProcessor::DETUNE, 100.0f);
            drive(en, rate, 1);
            FlangerProcessor f;
            f.setOversampling(os);
            f.setParameter(FlangerProcessor::DELAY_MS, 10.0f);
            f.setParameter(FlangerProcessor::DEPTH, 100.0f);
            f.setParameter(FlangerProcessor::RATE, 10.0f);
            drive(f, rate, 1);
            DelayProcessor d;
            d.setOversampling(os);
            d.setParameter(DelayProcessor::TIME, 2000.0f);
            d.setParameter(DelayProcessor::MOD_DEPTH, 100.0f);
            drive(d, rate, 3);
            ReverbProcessor rv;
            rv.setOversampling(os);
            rv.setParameter(ReverbProcessor::PRE_DELAY, 500.0f);
            rv.setParameter(ReverbProcessor::SIZE, 100.0f);
            rv.setParameter(ReverbProcessor::MOD_DEPTH, 100.0f);
            rv.setParameter(ReverbProcessor::EARLY_LATE, 100.0f);
            drive(rv, rate, 1);
            HaasProcessor h;
            h.setOversampling(os);
            h.setParameter(HaasProcessor::DELAY_MS, 30.0f);
            drive(h, rate, 1);
            for (float cutoff : {20.0f, 20000.0f}) {
                CombFilterProcessor cf;
                cf.setOversampling(os);
                cf.setParameter(CombFilterProcessor::CUTOFF, cutoff);
                drive(cf, rate, 1);
            }
            for (float pitch : {0.0f, 127.0f}) {
                ResonatorProcessor rs;
                rs.setOversampling(os);
                rs.setParameter(ResonatorProcessor::PITCH, pitch);
                rs.setParameter(ResonatorProcessor::TIMBRE, 1.0f);
                drive(rs, rate, 1);
            }
            TapeStopProcessor t;
            t.setOversampling(os);
            t.setParameter(TapeStopProcessor::STOP_TIME, 5000.0f);
            t.setParameter(TapeStopProcessor::CURVE, 0.0f);
            t.setParameter(TapeStopProcessor::PLAY, 0.0f);
            drive(t, rate, 6);
            CompressorProcessor cp;
            cp.setOversampling(os);
            cp.setParameter(CompressorProcessor::LOOKAHEAD, 10.0f);
            drive(cp, rate, 1);
            LimiterProcessor lm;
            lm.setOversampling(os);
            lm.setParameter(LimiterProcessor::LOOKAHEAD, 10.0f);
            drive(lm, rate, 1);
        }
    }
    check(chorusOverruns == 0, "the chorus at 40 ms, full depth and up to 8 voices reads within its line");
    check(DelayLine::overruns == 0, "every other delay effect at the far end of its knobs reads within its lines");
    DelayLine::overruns = 0;
}

// ── The limiter past 2^31 samples ────────────────────────────────────────

void limiterRunsPastTheOldOverflow() {
    // The old counter is seeded a little short of 2^31, and both run on past
    // it with a signal the limiter has to work on and the lookahead moving.
    // The new limiter has no counter left; it must do exactly what the old
    // one would have, had its int not wrapped.
    const double rate = 48000.0;
    LimiterProcessor now;
    now.setParameter(LimiterProcessor::THRESHOLD, -6.0f);
    now.setParameter(LimiterProcessor::LOOKAHEAD, 5.0f);
    now.setParameter(LimiterProcessor::RELEASE, 100.0f);
    now.prepare(rate, 1024);
    old::Limiter was;
    was.prepare(rate, 5.0f, 100.0f);
    was.seed(static_cast<long long>(INT_MAX) - 50000);
    std::vector<float> l(1000), r(1000), l2(1000), r2(1000);
    bool same = true;
    for (int b = 0; b < 160; b++) {
        if (b % 13 == 7) {
            const float ms = 1.0f + static_cast<float>(b % 10);
            now.setParameter(LimiterProcessor::LOOKAHEAD, ms);
            was.setLookahead(ms);
        }
        for (int i = 0; i < 1000; i++) {
            const float env = (b / 4) % 2 ? 1.4f : 0.3f;
            l[i] = l2[i] = env * noise();
            r[i] = r2[i] = env * noise();
        }
        now.process(l.data(), r.data(), 1000);
        was.process(l2.data(), r2.data(), 1000, -6.0f);
        for (int i = 0; i < 1000; i++) same = same && sameBits(l[i], l2[i]) && sameBits(r[i], r2[i]);
    }
    check(same, "the limiter across the old 2^31-sample overflow: exactly what the old one gave before it wrapped");

    // And on for a long stretch at 4x, where the old one ran out in 3 hours,
    // under AddressSanitizer in the sanitizer run: nothing read outside.
    LimiterProcessor longRun;
    longRun.setOversampling(4);
    longRun.setParameter(LimiterProcessor::THRESHOLD, -12.0f);
    longRun.prepareOS(rate, 1024);
    std::vector<float> a(1024), c(1024);
    bool finite = true;
    for (int b = 0; b < 1500; b++) {
        if (b % 97 == 0) longRun.setParameter(LimiterProcessor::LOOKAHEAD, 1.0f + static_cast<float>(b % 10));
        for (int i = 0; i < 1024; i++) { a[i] = 1.2f * noise(); c[i] = 1.2f * noise(); }
        longRun.processOS(a.data(), c.data(), 1024);
        finite = finite && dspAllFinite(a.data(), 1024) && dspAllFinite(c.data(), 1024);
    }
    check(finite, "six million samples of limiting at 4x, the lookahead moving, stay in range");
}

// ── Per-sample savings that change nothing ───────────────────────────────

void rmsFollowerIsAsBefore() {
    bool same = true;
    for (double rate : {44100.0, 96000.0, 192000.0}) {
        for (DetectionMode mode : {DetectionMode::RMS, DetectionMode::Peak}) {
            EnvelopeFollower now;
            old::EnvelopeFollower was;
            now.prepare(rate);
            was.prepare(rate);
            now.setAttack(3.0f); was.setAttack(3.0f);
            now.setRelease(80.0f); was.setRelease(80.0f);
            now.setMode(mode); was.setMode(mode);
            for (int i = 0; i < 20000; i++) {
                const float x = noise();
                same = same && sameBits(now.process(x), was.process(x));
            }
            // Prepared again at another rate, mid-stream.
            now.prepare(rate * 0.5); was.prepare(rate * 0.5);
            for (int i = 0; i < 5000; i++) {
                const float x = noise();
                same = same && sameBits(now.process(x), was.process(x));
            }
        }
    }
    EnvelopeFollower unprepared;
    old::EnvelopeFollower unpreparedWas;
    unprepared.setMode(DetectionMode::RMS);
    unpreparedWas.setMode(DetectionMode::RMS);
    for (int i = 0; i < 1000; i++) {
        const float x = noise();
        same = same && sameBits(unprepared.process(x), unpreparedWas.process(x));
    }
    check(same, "the RMS follower with its coefficient worked out once is bit for bit as before");
}

void ladderIsAsBefore() {
    struct Setting { float cutoff, resonance, driveDb, bias; int topology; bool saturate; };
    const Setting settings[] = {
        {1000.0f, 0.0f, 0.0f, 0.0f, 0, false},   // the default
        {8000.0f, 0.0f, 18.0f, 0.3f, 0, true},
        {300.0f, 0.0f, 6.0f, -0.5f, 1, true},
        {2000.0f, 40.0f, 0.0f, 0.0f, 0, false},  // resonant: the term is taken, as before
        {500.0f, 100.0f, 12.0f, 0.1f, 1, true},
    };
    bool same = true;
    for (const Setting& s : settings) {
        LadderFilterProcessor now;
        old::Ladder was;
        now.setParameter(LadderFilterProcessor::CUTOFF, s.cutoff);
        now.setParameter(LadderFilterProcessor::RESONANCE, s.resonance);
        now.setParameter(LadderFilterProcessor::DRIVE, s.driveDb);
        now.setParameter(LadderFilterProcessor::BIAS, s.bias);
        now.setParameter(LadderFilterProcessor::TOPOLOGY, static_cast<float>(s.topology));
        now.setParameter(LadderFilterProcessor::SATURATE, s.saturate ? 1.0f : 0.0f);
        was.cutoff = s.cutoff; was.resonance = s.resonance; was.driveDb = s.driveDb;
        was.bias = s.bias; was.topology = s.topology; was.saturate = s.saturate;
        now.prepare(48000.0, 512);
        was.prepare(48000.0);
        std::vector<float> l(512), r(512), l2(512), r2(512);
        for (int b = 0; b < 100; b++) {
            for (int i = 0; i < 512; i++) { l[i] = l2[i] = 0.7f * noise(); r[i] = r2[i] = 0.7f * noise(); }
            // Some digital silence too, where a sign of zero could show.
            if (b % 10 == 3) { std::fill(l.begin(), l.end(), 0.0f); l2 = l; std::fill(r.begin(), r.end(), 0.0f); r2 = r; }
            now.process(l.data(), r.data(), 512);
            was.process(l2.data(), r2.data(), 512);
            for (int i = 0; i < 512; i++) same = same && sameBits(l[i], l2[i]) && sameBits(r[i], r2[i]);
        }
    }
    check(same, "the ladder skipping its feedback at no resonance is bit for bit as before, at every setting");
}

void bitcrushLeavesRandAlone() {
    // Dither from its own generator, not rand(): rand()'s sequence is
    // untouched by a second of dithered crushing.
    std::srand(77);
    const int expected = std::rand();
    std::srand(77);
    auto run = [](std::vector<float>& out) {
        BitcrushProcessor b;
        b.setParameter(BitcrushProcessor::BITS, 6.0f);
        b.setParameter(BitcrushProcessor::DITHER, 100.0f);
        b.setParameter(BitcrushProcessor::RATE, 11025.0f);
        b.prepare(44100.0, 512);
        std::vector<float> l(512), r(512);
        for (int k = 0; k < 86; k++) {
            for (int i = 0; i < 512; i++) l[i] = r[i] = 0.5f * std::sin(0.01f * static_cast<float>(k * 512 + i));
            b.process(l.data(), r.data(), 512);
            out.insert(out.end(), l.begin(), l.end());
        }
    };
    std::vector<float> a, b;
    run(a);
    check(std::rand() == expected, "bitcrush's dither no longer calls rand()");
    run(b);
    bool sane = a == b;
    float worst = 0.0f;
    for (size_t i = 0; i < a.size(); i++) {
        const float clean = 0.5f * std::sin(0.01f * static_cast<float>(i));
        worst = std::max(worst, std::fabs(a[i] - clean));
    }
    // 6 bits: a step of 1/64, held for 4 samples at a quarter of the rate,
    // plus up to a step of dither.
    std::printf("      worst error from the clean tone %.4f\n", worst);
    check(sane && worst < 0.06f, "and its dithered output is the same each run, and within a few steps of the tone");
}

}  // namespace

int main() {
    delayLineReadsAsBefore();
    ringsReadAsBefore();
    preparedBuffersStartSilent();
    aRateChangeKeepsNoOldAudio();
    noEffectReadsPastItsLine();
    limiterRunsPastTheOldOverflow();
    rmsFollowerIsAsBefore();
    ladderIsAsBefore();
    bitcrushLeavesRandAlone();
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
