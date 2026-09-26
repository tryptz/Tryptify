#include "dsp_engine.h"
#include "snapins/gain.h"
#include "snapins/stereo.h"
#include "snapins/filter.h"
#include "snapins/eq_3band.h"
#include "snapins/compressor.h"
#include "snapins/limiter.h"
#include "snapins/gate.h"
#include "snapins/dynamics.h"
#include "snapins/compactor.h"
#include "snapins/transient_shaper.h"
#include "snapins/distortion.h"
#include "snapins/shaper.h"
#include "snapins/chorus.h"
#include "snapins/ensemble.h"
#include "snapins/flanger.h"
#include "snapins/phaser.h"
#include "snapins/delay.h"
#include "snapins/reverb.h"
#include "snapins/bitcrush.h"
#include "snapins/comb_filter.h"
#include "snapins/channel_mixer.h"
#include "snapins/formant_filter.h"
#include "snapins/frequency_shifter.h"
#include "snapins/haas.h"
#include "snapins/ladder_filter.h"
#include "snapins/nonlinear_filter.h"
#include "snapins/phase_distortion.h"
#include "snapins/pitch_shifter.h"
#include "snapins/resonator.h"
#include "snapins/reverser.h"
#include "snapins/ring_mod.h"
#include "snapins/tape_stop.h"
#include "snapins/trance_gate.h"
#include "snapins/eq_10band.h"
#include "snapins/disperser.h"
#include "snapins/misstortion.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "MonochromeDSP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Denormal protection ────────────────────────────────────────────────
#if defined(__aarch64__)
static inline void enableFlushToZero() {
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1 << 24);  // FZ bit — flush denormals to zero
    asm volatile("msr fpcr, %0" :: "r"(fpcr));
}
#elif defined(__arm__)
static inline void enableFlushToZero() {
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1 << 24);
    asm volatile("vmsr fpscr, %0" :: "r"(fpscr));
}
#elif defined(__i386__) || defined(__x86_64__)
#include <xmmintrin.h>
static inline void enableFlushToZero() {
    _mm_setcsr(_mm_getcsr() | 0x8040);  // FTZ + DAZ
}
#else
static inline void enableFlushToZero() {}
#endif

// ── Factory ─────────────────────────────────────────────────────────────

SnapinProcessor* createSnapin(SnapinType type) {
    switch (type) {
        case SnapinType::GAIN:       return new GainProcessor();
        case SnapinType::STEREO:     return new StereoProcessor();
        case SnapinType::FILTER:     return new FilterProcessor();
        case SnapinType::EQ_3BAND:   return new Eq3BandProcessor();
        case SnapinType::COMPRESSOR: return new CompressorProcessor();
        case SnapinType::LIMITER:    return new LimiterProcessor();
        case SnapinType::GATE:       return new GateProcessor();
        case SnapinType::DYNAMICS:   return new DynamicsProcessor();
        case SnapinType::COMPACTOR:  return new CompactorProcessor();
        case SnapinType::TRANSIENT_SHAPER: return new TransientShaperProcessor();
        case SnapinType::DISTORTION: return new DistortionProcessor();
        case SnapinType::SHAPER:     return new ShaperProcessor();
        case SnapinType::CHORUS:     return new ChorusProcessor();
        case SnapinType::ENSEMBLE:   return new EnsembleProcessor();
        case SnapinType::FLANGER:    return new FlangerProcessor();
        case SnapinType::PHASER:     return new PhaserProcessor();
        case SnapinType::DELAY:      return new DelayProcessor();
        case SnapinType::REVERB:     return new ReverbProcessor();
        case SnapinType::BITCRUSH:   return new BitcrushProcessor();
        case SnapinType::COMB_FILTER: return new CombFilterProcessor();
        case SnapinType::CHANNEL_MIXER: return new ChannelMixerProcessor();
        case SnapinType::FORMANT_FILTER: return new FormantFilterProcessor();
        case SnapinType::FREQUENCY_SHIFTER: return new FrequencyShifterProcessor();
        case SnapinType::HAAS:       return new HaasProcessor();
        case SnapinType::LADDER_FILTER: return new LadderFilterProcessor();
        case SnapinType::NONLINEAR_FILTER: return new NonlinearFilterProcessor();
        case SnapinType::PHASE_DISTORTION: return new PhaseDistortionProcessor();
        case SnapinType::PITCH_SHIFTER: return new PitchShifterProcessor();
        case SnapinType::RESONATOR:  return new ResonatorProcessor();
        case SnapinType::REVERSER:   return new ReverserProcessor();
        case SnapinType::RING_MOD:   return new RingModProcessor();
        case SnapinType::TAPE_STOP:  return new TapeStopProcessor();
        case SnapinType::TRANCE_GATE: return new TranceGateProcessor();
        case SnapinType::EQ_10BAND:  return new Eq10BandProcessor();
        case SnapinType::DISPERSER:  return new DisperserProcessor();
        case SnapinType::MISSTORTION: return new MisstortionProcessor();
        default:
            LOGE("Unsupported snapin type: %d", static_cast<int>(type));
            return nullptr;
    }
}

// ── Engine lifecycle ────────────────────────────────────────────────────

DspEngine::DspEngine(int sampleRate, int maxBlockSize)
    : sampleRate_(sampleRate), maxBlockSize_(maxBlockSize) {
    resizeScratch(maxBlockSize);

    // Every strip starts routed to the master alone, like a fresh FL mixer.
    for (int b = 0; b < NUM_MIX_BUSES; b++) {
        buses_[b].send[MASTER_BUS].store(1.0f, std::memory_order_relaxed);
        buses_[b].sendApplied[MASTER_BUS] = 1.0f;
    }
    rebuildOrder();

    // Smoothing coeff: ~5ms time constant
    gainSmoothCoeff_ = 1.0f - std::exp(-1.0f / (0.005f * sampleRate));

    // By default only bus 0 receives audio input
    buses_[0].inputEnabled.store(true, std::memory_order_relaxed);

    // Meter ballistics: ~20dB/sec decay, 1.5s peak hold
    // Decay per sample: 20dB / sampleRate (in linear domain per sample)
    meterDecayPerSample_ = 20.0f / static_cast<float>(sampleRate);  // dB per sample
    meterHoldSamples_ = static_cast<int>(1.5f * sampleRate);

    LOGD("DspEngine created: sr=%d, maxBlock=%d", sampleRate, maxBlockSize);
}

// ── Live reconfigure — no destroy, no state reload ─────────────────────
//
// ExoPlayer calls our AudioProcessor.configure()/flush() on every track
// change; for transitions across sample-rate boundaries (44.1k → 48k or
// vice-versa) a destroy + recreate takes 5–10 ms while plugin constructors
// allocate FFT tables, delay lines, and filter state. Audible as a gap
// between tracks.
//
// Instead, keep the bus graph and every plugin instance alive, and only
// update:
//   - the cached sample rate
//   - SR-dependent engine-level coefficients (gain smoothing + meter ballistics)
//   - each plugin's internal coefficients via prepare(newSr)
//
// Scratch buffers grow if `maxBlockSize` exceeded the previous allocation;
// plugin prepare() calls are mandatory on SR changes because biquad
// coefficients, FFT lengths, LFO phase increments, etc. are all SR-derived.
void DspEngine::reconfigure(int sampleRate, int maxBlockSize) {
    std::lock_guard<std::mutex> lock(chainMutex_);

    bool srChanged = (sampleRate != sampleRate_);
    bool blockGrew = (maxBlockSize > maxBlockSize_);

    if (!srChanged && !blockGrew) return;

    sampleRate_ = sampleRate;
    if (blockGrew) {
        maxBlockSize_ = maxBlockSize;
        resizeScratch(maxBlockSize_);
    }

    if (srChanged) {
        // Match DspEngine() ctor derivations — keep the formulas in lock-step.
        gainSmoothCoeff_ = 1.0f - std::exp(-1.0f / (0.005f * sampleRate_));
        meterDecayPerSample_ = 20.0f / static_cast<float>(sampleRate_);
        meterHoldSamples_ = static_cast<int>(1.5f * sampleRate_);

        for (auto& bus : buses_) {
            for (auto& plugin : bus.plugins) {
                if (plugin) plugin->prepareOS(static_cast<double>(sampleRate_), maxBlockSize_);
            }
        }
    } else if (blockGrew) {
        // Oversampling scratch buffers are sized from the max block — re-prepare
        // so processOS never writes past them after the block grows.
        for (auto& bus : buses_) {
            for (auto& plugin : bus.plugins) {
                if (plugin) plugin->prepareOS(static_cast<double>(sampleRate_), maxBlockSize_);
            }
        }
    }

    LOGD("DspEngine reconfigured: sr=%d maxBlock=%d (graph preserved, srChanged=%d, blockGrew=%d)",
         sampleRate_, maxBlockSize_, srChanged ? 1 : 0, blockGrew ? 1 : 0);
}

void DspEngine::resizeScratch(int maxBlockSize) {
    sumL_.resize(maxBlockSize, 0.0f);
    sumR_.resize(maxBlockSize, 0.0f);
    busL_.resize(maxBlockSize, 0.0f);
    busR_.resize(maxBlockSize, 0.0f);
    dryBufL_.resize(maxBlockSize, 0.0f);
    dryBufR_.resize(maxBlockSize, 0.0f);
    for (int b = 0; b < NUM_MIX_BUSES; b++) {
        mixInL_[b].resize(maxBlockSize, 0.0f);
        mixInR_[b].resize(maxBlockSize, 0.0f);
    }
}

DspEngine::~DspEngine() {
    LOGD("DspEngine destroyed");
}

// ── Audio processing ────────────────────────────────────────────────────

// Linear peak across a stereo pair — used for the per-plugin tap meters.
static inline float stereoPeak(const float* l, const float* r, int n) {
    float p = 0.0f;
    for (int i = 0; i < n; i++) {
        float al = std::fabs(l[i]);
        if (al > p) p = al;
        float ar = std::fabs(r[i]);
        if (ar > p) p = ar;
    }
    return p;
}

static inline void zeroSlotMeters(Bus& bus) {
    for (int s = 0; s < MAX_PLUGINS_PER_BUS; s++) {
        bus.slotInPeak[s].store(0.0f, std::memory_order_relaxed);
        bus.slotOutPeak[s].store(0.0f, std::memory_order_relaxed);
    }
}

// Append silence to a bus's waveform tap so the scope decays instead of
// freezing on the last audible block.
static inline void writeWaveSilence(Bus& bus, int numFrames) {
    // With 48 strips most are idle; once a strip's whole ring is zero, further
    // silence changes nothing a reader can see, so stop rewriting it.
    if (bus.waveSilentSamples >= WAVE_TAP_SIZE) return;
    bus.waveSilentSamples += numFrames;
    int wp = bus.waveTapPos.load(std::memory_order_relaxed);
    for (int i = 0; i < numFrames; i++) {
        bus.waveTap[wp] = 0.0f;
        wp = (wp + 1) & (WAVE_TAP_SIZE - 1);
    }
    bus.waveTapPos.store(wp, std::memory_order_relaxed);
}

void DspEngine::process(float* left, float* right, int numFrames) {
    // Flush denormals to zero — prevents 10-100x CPU spikes in feedback tails
    enableFlushToZero();

    // Clear sum buffers
    std::fill(sumL_.begin(), sumL_.begin() + numFrames, 0.0f);
    std::fill(sumR_.begin(), sumR_.begin() + numFrames, 0.0f);

    bool hasSolo = anySoloed();
    bool mixBypass = mixBypassed_.load(std::memory_order_relaxed);

    // Lock for reading plugin chains (brief lock — plugins don't allocate during process)
    std::lock_guard<std::mutex> lock(chainMutex_);

    // ── Routing graph for this block ────────────────────────────────────
    // A strip is LIVE when it takes the playback input or something live
    // sends to it. Liveness is structural — a muted strip upstream still
    // counts — so a reverb strip fed by a strip that was just muted keeps
    // running on silence and its tail rings out instead of freezing.
    //
    // Solo follows FL: a soloed strip stays audible together with everything
    // it feeds (downstream) and everything feeding it (upstream); all other
    // strips are silenced.
    bool live[NUM_MIX_BUSES] = {};
    bool hasIn[NUM_MIX_BUSES] = {};
    for (int k = 0; k < NUM_MIX_BUSES; k++) {
        const int b = order_[k];
        if (buses_[b].inputEnabled.load(std::memory_order_relaxed)) live[b] = true;
        if (!live[b]) continue;
        for (int d = 0; d < NUM_MIX_BUSES; d++) {
            if (buses_[b].send[d].load(std::memory_order_relaxed) > 0.0f) live[d] = true;
        }
    }
    bool audible[NUM_MIX_BUSES];
    if (hasSolo) {
        bool down[NUM_MIX_BUSES] = {}, up[NUM_MIX_BUSES] = {};
        for (int k = 0; k < NUM_MIX_BUSES; k++) {          // forward: soloed → what it feeds
            const int b = order_[k];
            if (buses_[b].soloed.load(std::memory_order_relaxed)) down[b] = true;
            if (!down[b]) continue;
            for (int d = 0; d < NUM_MIX_BUSES; d++)
                if (buses_[b].send[d].load(std::memory_order_relaxed) > 0.0f) down[d] = true;
        }
        for (int k = NUM_MIX_BUSES - 1; k >= 0; k--) {     // backward: what feeds a soloed strip
            const int b = order_[k];
            if (buses_[b].soloed.load(std::memory_order_relaxed)) { up[b] = true; continue; }
            for (int d = 0; d < NUM_MIX_BUSES; d++) {
                if (up[d] && buses_[b].send[d].load(std::memory_order_relaxed) > 0.0f) { up[b] = true; break; }
            }
        }
        for (int b = 0; b < NUM_MIX_BUSES; b++) audible[b] = down[b] || up[b];
    } else {
        for (int b = 0; b < NUM_MIX_BUSES; b++) audible[b] = true;
    }
    const float rampStep = numFrames > 0 ? 1.0f / static_cast<float>(numFrames) : 1.0f;

    // Mix strips in dependency order, so each one's input is complete (the
    // playback signal plus every send into it) before it runs.
    for (int k = 0; k < NUM_MIX_BUSES; k++) {
        const int b = order_[k];
        Bus& bus = buses_[b];

        // Load atomic parameters once into locals
        bool busInputEnabled = bus.inputEnabled.load(std::memory_order_relaxed);
        bool busMuted = bus.muted.load(std::memory_order_relaxed);
        float busGainDb = mixBypass ? 0.0f : bus.gainDb.load(std::memory_order_relaxed);
        float busPan = mixBypass ? 0.0f : bus.pan.load(std::memory_order_relaxed);

        // Dead, muted or solo'd out: contributes nothing this block.
        if (!live[b] || busMuted || !audible[b]) {
            bus.peakL.store(0.0f, std::memory_order_relaxed);
            bus.peakR.store(0.0f, std::memory_order_relaxed);
            zeroSlotMeters(bus);
            writeWaveSilence(bus, numFrames);
            // Keep the send ramps parked at their targets so a strip coming
            // back doesn't glide in from a stale level.
            for (int d = 0; d < TOTAL_BUSES; d++)
                bus.sendApplied[d] = bus.send[d].load(std::memory_order_relaxed);
            continue;
        }

        // This strip's input: sends already accumulated into its buffer by the
        // strips before it, plus the playback signal when it takes input.
        float* inL = mixInL_[b].data();
        float* inR = mixInR_[b].data();
        if (!hasIn[b]) {
            if (busInputEnabled) {
                std::copy(left, left + numFrames, inL);
                std::copy(right, right + numFrames, inR);
            } else {
                std::fill(inL, inL + numFrames, 0.0f);
                std::fill(inR, inR + numFrames, 0.0f);
            }
        } else if (busInputEnabled) {
            for (int i = 0; i < numFrames; i++) { inL[i] += left[i]; inR[i] += right[i]; }
        }

        // Run plugin chain with dry/wet blending (skip when mixer DSP is bypassed)
        if (mixBypass) {
            zeroSlotMeters(bus);
        } else {
            for (size_t s = 0; s < bus.plugins.size(); s++) {
                auto& plugin = bus.plugins[s];
                if (plugin && !plugin->isBypassed()) {
                    bus.slotInPeak[s].store(
                        stereoPeak(inL, inR, numFrames),
                        std::memory_order_relaxed);
                    float dw = plugin->getDryWet();
                    if (dw >= 0.999f) {
                        // Fully wet — no copy needed
                        plugin->processOS(inL, inR, numFrames);
                    } else if (dw <= 0.001f) {
                        // Fully dry — skip processing
                    } else {
                        // Blend: save dry into pre-allocated buffers, process, mix
                        std::copy(inL, inL + numFrames, dryBufL_.begin());
                        std::copy(inR, inR + numFrames, dryBufR_.begin());
                        plugin->processOS(inL, inR, numFrames);
                        float wet = dw, dry = 1.0f - dw;
                        for (int i = 0; i < numFrames; i++) {
                            inL[i] = dryBufL_[i] * dry + inL[i] * wet;
                            inR[i] = dryBufR_[i] * dry + inR[i] * wet;
                        }
                    }
                    bus.slotOutPeak[s].store(
                        stereoPeak(inL, inR, numFrames),
                        std::memory_order_relaxed);
                } else {
                    bus.slotInPeak[s].store(0.0f, std::memory_order_relaxed);
                    bus.slotOutPeak[s].store(0.0f, std::memory_order_relaxed);
                }
            }
        }

        // Recalculate target gains from dB + pan
        recalcBusGains(busGainDb, busPan, bus.targetGainL, bus.targetGainR);

        // Fader + pan with smoothing, in place: the buffer is now post-fader.
        float busPeakL = 0.0f, busPeakR = 0.0f;
        int wavePos = bus.waveTapPos.load(std::memory_order_relaxed);
        for (int i = 0; i < numFrames; i++) {
            bus.smoothGainL += gainSmoothCoeff_ * (bus.targetGainL - bus.smoothGainL);
            bus.smoothGainR += gainSmoothCoeff_ * (bus.targetGainR - bus.smoothGainR);
            float sL = inL[i] * bus.smoothGainL;
            float sR = inR[i] * bus.smoothGainR;
            inL[i] = sL;
            inR[i] = sR;
            float absL = std::fabs(sL);
            float absR = std::fabs(sR);
            if (absL > busPeakL) busPeakL = absL;
            if (absR > busPeakR) busPeakR = absR;
            // Post-fader mono scope tap
            bus.waveTap[wavePos] = 0.5f * (sL + sR);
            wavePos = (wavePos + 1) & (WAVE_TAP_SIZE - 1);
        }
        bus.waveTapPos.store(wavePos, std::memory_order_relaxed);
        bus.waveSilentSamples = 0;
        // Update peak meters (relaxed store — UI reads are non-critical)
        bus.peakL.store(busPeakL, std::memory_order_relaxed);
        bus.peakR.store(busPeakR, std::memory_order_relaxed);

        // Sends: the post-fader signal into every routed bus, each level ramped
        // across the block from what was applied last time.
        for (int d = 0; d < TOTAL_BUSES; d++) {
            const float target = bus.send[d].load(std::memory_order_relaxed);
            const float from = bus.sendApplied[d];
            bus.sendApplied[d] = target;
            if (target <= 0.0f && from <= 0.0f) continue;
            float* outL;
            float* outR;
            bool first = false;
            if (d == MASTER_BUS) {
                outL = sumL_.data();
                outR = sumR_.data();
            } else {
                outL = mixInL_[d].data();
                outR = mixInR_[d].data();
                first = !hasIn[d];
                hasIn[d] = true;
            }
            const float step = (target - from) * rampStep;
            float g = from;
            if (first) {
                for (int i = 0; i < numFrames; i++) { g += step; outL[i] = inL[i] * g; outR[i] = inR[i] * g; }
            } else {
                for (int i = 0; i < numFrames; i++) { g += step; outL[i] += inL[i] * g; outR[i] += inR[i] * g; }
            }
        }
    }

    // Run master bus chain with dry/wet blending
    Bus& master = buses_[MASTER_BUS];
    for (size_t s = 0; s < master.plugins.size(); s++) {
        auto& plugin = master.plugins[s];
        if (plugin && !plugin->isBypassed()) {
            master.slotInPeak[s].store(
                stereoPeak(sumL_.data(), sumR_.data(), numFrames),
                std::memory_order_relaxed);
            float dw = plugin->getDryWet();
            if (dw >= 0.999f) {
                plugin->processOS(sumL_.data(), sumR_.data(), numFrames);
            } else if (dw <= 0.001f) {
                // Fully dry — skip
            } else {
                std::copy(sumL_.begin(), sumL_.begin() + numFrames, dryBufL_.begin());
                std::copy(sumR_.begin(), sumR_.begin() + numFrames, dryBufR_.begin());
                plugin->processOS(sumL_.data(), sumR_.data(), numFrames);
                float wet = dw, dry = 1.0f - dw;
                for (int i = 0; i < numFrames; i++) {
                    sumL_[i] = dryBufL_[i] * dry + sumL_[i] * wet;
                    sumR_[i] = dryBufR_[i] * dry + sumR_[i] * wet;
                }
            }
            master.slotOutPeak[s].store(
                stereoPeak(sumL_.data(), sumR_.data(), numFrames),
                std::memory_order_relaxed);
        } else {
            master.slotInPeak[s].store(0.0f, std::memory_order_relaxed);
            master.slotOutPeak[s].store(0.0f, std::memory_order_relaxed);
        }
    }

    // Apply master gain and write to output
    float masterGainDb = master.gainDb.load(std::memory_order_relaxed);
    float masterPan = master.pan.load(std::memory_order_relaxed);
    recalcBusGains(masterGainDb, masterPan, master.targetGainL, master.targetGainR);
    float masterPeakL = 0.0f, masterPeakR = 0.0f;
    bool clipped = false;
    int masterWavePos = master.waveTapPos.load(std::memory_order_relaxed);
    for (int i = 0; i < numFrames; i++) {
        master.smoothGainL += gainSmoothCoeff_ * (master.targetGainL - master.smoothGainL);
        master.smoothGainR += gainSmoothCoeff_ * (master.targetGainR - master.smoothGainR);
        left[i]  = sumL_[i] * master.smoothGainL;
        right[i] = sumR_[i] * master.smoothGainR;
        float absL = std::fabs(left[i]);
        float absR = std::fabs(right[i]);
        if (absL > masterPeakL) masterPeakL = absL;
        if (absR > masterPeakR) masterPeakR = absR;
        if (absL > 1.0f || absR > 1.0f) clipped = true;
        // Post-fader mono scope tap
        master.waveTap[masterWavePos] = 0.5f * (left[i] + right[i]);
        masterWavePos = (masterWavePos + 1) & (WAVE_TAP_SIZE - 1);
    }
    master.waveTapPos.store(masterWavePos, std::memory_order_relaxed);
    master.peakL.store(masterPeakL, std::memory_order_relaxed);
    master.peakR.store(masterPeakR, std::memory_order_relaxed);
    if (clipped) clipped_.store(true, std::memory_order_relaxed);

    // Update meter ballistics for all buses
    float decayAmount = meterDecayPerSample_ * static_cast<float>(numFrames);
    for (int b = 0; b < TOTAL_BUSES; b++) {
        Bus& bus = buses_[b];
        float peakL = bus.peakL.load(std::memory_order_relaxed);
        float peakR = bus.peakR.load(std::memory_order_relaxed);

        // Convert to dB for ballistics
        float peakDbL = (peakL > 1e-10f) ? 20.0f * std::log10(peakL) : -60.0f;
        float peakDbR = (peakR > 1e-10f) ? 20.0f * std::log10(peakR) : -60.0f;

        // Decay: meter falls at 20dB/sec
        if (peakDbL >= bus.decayL) {
            bus.decayL = peakDbL;
        } else {
            bus.decayL -= decayAmount;
            if (bus.decayL < -60.0f) bus.decayL = -60.0f;
        }
        if (peakDbR >= bus.decayR) {
            bus.decayR = peakDbR;
        } else {
            bus.decayR -= decayAmount;
            if (bus.decayR < -60.0f) bus.decayR = -60.0f;
        }

        // Hold: peak hold for 1.5 seconds
        if (peakDbL >= bus.holdL) {
            bus.holdL = peakDbL;
            bus.holdCounterL = meterHoldSamples_;
        } else {
            bus.holdCounterL -= numFrames;
            if (bus.holdCounterL <= 0) {
                bus.holdL -= decayAmount;
                if (bus.holdL < -60.0f) bus.holdL = -60.0f;
            }
        }
        if (peakDbR >= bus.holdR) {
            bus.holdR = peakDbR;
            bus.holdCounterR = meterHoldSamples_;
        } else {
            bus.holdCounterR -= numFrames;
            if (bus.holdCounterR <= 0) {
                bus.holdR -= decayAmount;
                if (bus.holdR < -60.0f) bus.holdR = -60.0f;
            }
        }
    }
}

// ── Bus control ─────────────────────────────────────────────────────────

// Sanitize: reject NaN/Inf (would poison the real-time atomics and trigger NaN audio).
static inline float finiteOr(float v, float fallback) {
    return std::isfinite(v) ? v : fallback;
}

void DspEngine::setBusGain(int busIndex, float gainDb) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    const float clamped = std::max(-60.0f, std::min(12.0f, finiteOr(gainDb, 0.0f)));
    buses_[busIndex].gainDb.store(clamped, std::memory_order_relaxed);
}

void DspEngine::setBusPan(int busIndex, float pan) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    buses_[busIndex].pan.store(
        std::max(-1.0f, std::min(1.0f, finiteOr(pan, 0.0f))),
        std::memory_order_relaxed);
}

void DspEngine::setBusMute(int busIndex, bool muted) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    buses_[busIndex].muted.store(muted, std::memory_order_relaxed);
}

void DspEngine::setBusSolo(int busIndex, bool soloed) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    buses_[busIndex].soloed.store(soloed, std::memory_order_relaxed);
}

// ── Plugin chain management ─────────────────────────────────────────────

int DspEngine::addPlugin(int busIndex, int slotIndex, int pluginType) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return -1;
    Bus& bus = buses_[busIndex];
    if (static_cast<int>(bus.plugins.size()) >= MAX_PLUGINS_PER_BUS) return -1;

    auto* proc = createSnapin(static_cast<SnapinType>(pluginType));
    if (!proc) return -1;

    proc->prepareOS(static_cast<double>(sampleRate_), maxBlockSize_);

    std::lock_guard<std::mutex> lock(chainMutex_);

    int idx = std::min(slotIndex, static_cast<int>(bus.plugins.size()));
    bus.plugins.insert(bus.plugins.begin() + idx, std::unique_ptr<SnapinProcessor>(proc));

    LOGD("Added plugin type %d to bus %d slot %d", pluginType, busIndex, idx);
    return idx;
}

void DspEngine::removePlugin(int busIndex, int slotIndex) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    Bus& bus = buses_[busIndex];

    // Outlives the critical section on purpose: ~SnapinProcessor frees delay
    // lines and reverb tanks, and process() blocks on chainMutex_ for the whole
    // mix, so running a destructor under the lock is time the real-time thread
    // spends waiting on free(). Move the slot out, release, then destroy here.
    std::unique_ptr<SnapinProcessor> retired;
    {
        std::lock_guard<std::mutex> lock(chainMutex_);
        // Bounds re-checked inside the lock — the size read outside it could
        // race a concurrent add/remove from another UI action.
        if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
        retired = std::move(bus.plugins[slotIndex]);
        bus.plugins.erase(bus.plugins.begin() + slotIndex);
    }
    LOGD("Removed plugin from bus %d slot %d", busIndex, slotIndex);
}

void DspEngine::movePlugin(int busIndex, int fromSlot, int toSlot) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    Bus& bus = buses_[busIndex];
    int sz = static_cast<int>(bus.plugins.size());
    if (fromSlot < 0 || fromSlot >= sz || toSlot < 0 || toSlot >= sz) return;
    if (fromSlot == toSlot) return;

    std::lock_guard<std::mutex> lock(chainMutex_);
    auto plugin = std::move(bus.plugins[fromSlot]);
    bus.plugins.erase(bus.plugins.begin() + fromSlot);
    bus.plugins.insert(bus.plugins.begin() + toSlot, std::move(plugin));
}

void DspEngine::setParameter(int busIndex, int slotIndex, int paramIndex, float value) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
    if (bus.plugins[slotIndex]) {
        bus.plugins[slotIndex]->setParameter(paramIndex, value);
    }
}

void DspEngine::setPluginBypassed(int busIndex, int slotIndex, bool bypassed) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
    if (bus.plugins[slotIndex]) {
        bus.plugins[slotIndex]->setBypassed(bypassed);
    }
}

void DspEngine::setBusInputEnabled(int busIndex, bool enabled) {
    if (busIndex < 0 || busIndex >= NUM_MIX_BUSES) return;  // Only mix buses, not master
    buses_[busIndex].inputEnabled.store(enabled, std::memory_order_relaxed);
}

bool DspEngine::reaches(int from, int to) const {
    // Depth-first over the (<= 48-node) strip graph; iterative, fixed stack.
    if (from == to) return true;
    bool seen[NUM_MIX_BUSES] = {};
    int stack[NUM_MIX_BUSES];
    int top = 0;
    stack[top++] = from;
    seen[from] = true;
    while (top > 0) {
        const int b = stack[--top];
        for (int d = 0; d < NUM_MIX_BUSES; d++) {
            if (seen[d] || buses_[b].send[d].load(std::memory_order_relaxed) <= 0.0f) continue;
            if (d == to) return true;
            seen[d] = true;
            stack[top++] = d;
        }
    }
    return false;
}

void DspEngine::rebuildOrder() {
    // Kahn's algorithm, lowest index first among ready strips, so an
    // unrouted mixer runs 0..47 in order as before. The graph is kept acyclic
    // by setSend/loadStateJson; should a cycle ever slip through, the strips
    // left over are appended in index order rather than dropped.
    int indegree[NUM_MIX_BUSES] = {};
    for (int s = 0; s < NUM_MIX_BUSES; s++)
        for (int d = 0; d < NUM_MIX_BUSES; d++)
            if (buses_[s].send[d].load(std::memory_order_relaxed) > 0.0f) indegree[d]++;
    bool placed[NUM_MIX_BUSES] = {};
    int n = 0;
    while (n < NUM_MIX_BUSES) {
        int pick = -1;
        for (int b = 0; b < NUM_MIX_BUSES; b++) {
            if (!placed[b] && indegree[b] == 0) { pick = b; break; }
        }
        if (pick < 0) break;
        placed[pick] = true;
        order_[n++] = pick;
        for (int d = 0; d < NUM_MIX_BUSES; d++)
            if (buses_[pick].send[d].load(std::memory_order_relaxed) > 0.0f) indegree[d]--;
    }
    for (int b = 0; b < NUM_MIX_BUSES && n < NUM_MIX_BUSES; b++)
        if (!placed[b]) order_[n++] = b;
}

bool DspEngine::setSend(int src, int dst, float level) {
    if (src < 0 || src >= NUM_MIX_BUSES || dst < 0 || dst >= TOTAL_BUSES || dst == src) return false;
    const float clamped = std::max(0.0f, std::min(1.0f, finiteOr(level, 0.0f)));
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[src];
    const bool had = bus.send[dst].load(std::memory_order_relaxed) > 0.0f;
    const bool has = clamped > 0.0f;
    // A new strip-to-strip edge must not close a loop: refuse if dst already
    // (directly or through other strips) sends into src.
    if (has && !had && dst != MASTER_BUS && reaches(dst, src)) return false;
    bus.send[dst].store(clamped, std::memory_order_relaxed);
    if (had != has && dst != MASTER_BUS) rebuildOrder();
    return true;
}

float DspEngine::getSend(int src, int dst) const {
    if (src < 0 || src >= NUM_MIX_BUSES || dst < 0 || dst >= TOTAL_BUSES) return 0.0f;
    return buses_[src].send[dst].load(std::memory_order_relaxed);
}

void DspEngine::setMixBypassed(bool bypassed) {
    mixBypassed_.store(bypassed, std::memory_order_relaxed);
}

void DspEngine::setPluginDryWet(int busIndex, int slotIndex, float dryWet) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
    if (bus.plugins[slotIndex]) {
        bus.plugins[slotIndex]->setDryWet(dryWet);
    }
}

void DspEngine::setPluginOversampling(int busIndex, int slotIndex, int factor) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
    if (bus.plugins[slotIndex]) {
        bus.plugins[slotIndex]->setOversampling(factor);
    }
}

// ── Metering ────────────────────────────────────────────────────────────

void DspEngine::getBusLevels(float* outLevels, int maxFloats) {
    // Output format: [peakL, peakR, holdL, holdR] per bus (4 floats each)
    int count = std::min(maxFloats, TOTAL_BUSES * 4);
    for (int b = 0; b < TOTAL_BUSES && b * 4 + 3 < count; b++) {
        outLevels[b * 4]     = buses_[b].decayL;
        outLevels[b * 4 + 1] = buses_[b].decayR;
        outLevels[b * 4 + 2] = buses_[b].holdL;
        outLevels[b * 4 + 3] = buses_[b].holdR;
    }
}

bool DspEngine::getAndResetClipped() {
    return clipped_.exchange(false, std::memory_order_relaxed);
}

void DspEngine::getPluginMeters(int busIndex, float* out, int maxFloats) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES || !out) return;
    Bus& bus = buses_[busIndex];
    auto toDb = [](float lin) {
        return (lin > 1e-10f) ? std::max(-60.0f, 20.0f * std::log10(lin)) : -60.0f;
    };
    for (int s = 0; s < MAX_PLUGINS_PER_BUS && s * 2 + 1 < maxFloats; s++) {
        out[s * 2]     = toDb(bus.slotInPeak[s].load(std::memory_order_relaxed));
        out[s * 2 + 1] = toDb(bus.slotOutPeak[s].load(std::memory_order_relaxed));
    }
}

int DspEngine::getBusWaveform(int busIndex, float* out, int maxSamples) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES || !out || maxSamples <= 0) return 0;
    Bus& bus = buses_[busIndex];
    int n = std::min(maxSamples, WAVE_TAP_SIZE);
    // Single-writer ring; read without locking — a torn block boundary is
    // invisible in a scope display.
    int wp = bus.waveTapPos.load(std::memory_order_relaxed);
    for (int i = 0; i < n; i++) {
        out[i] = bus.waveTap[(wp - n + i + WAVE_TAP_SIZE) & (WAVE_TAP_SIZE - 1)];
    }
    return n;
}

// ── Helpers ─────────────────────────────────────────────────────────────

bool DspEngine::anySoloed() const {
    for (int i = 0; i < NUM_MIX_BUSES; i++) {
        if (buses_[i].soloed.load(std::memory_order_relaxed)) return true;
    }
    return false;
}

void DspEngine::recalcBusGains(float gainDb, float pan, float& targetL, float& targetR) {
    float linear = (gainDb <= -100.0f) ? 0.0f
        : std::pow(10.0f, gainDb / 20.0f);

    // Equal-power pan law
    float panNorm = (pan + 1.0f) * 0.5f;  // 0..1
    targetL = linear * std::cos(panNorm * 1.5707963f);  // pi/2
    targetR = linear * std::sin(panNorm * 1.5707963f);
}

// ── Plugin state reset ──────────────────────────────────────────────────

void DspEngine::resetPluginState() {
    std::lock_guard<std::mutex> lock(chainMutex_);
    for (int b = 0; b < TOTAL_BUSES; b++) {
        Bus& bus = buses_[b];
        for (auto& plugin : bus.plugins) {
            if (plugin) plugin->reset();
        }
        // Reset smooth gain to avoid ramp artifacts
        bus.smoothGainL = bus.targetGainL;
        bus.smoothGainR = bus.targetGainR;
        // Reset meter state
        bus.decayL = -60.0f;
        bus.decayR = -60.0f;
        bus.holdL = -60.0f;
        bus.holdR = -60.0f;
        bus.holdCounterL = 0;
        bus.holdCounterR = 0;
    }
    LOGD("Plugin state reset");
}

// ── State serialization (simple JSON) ───────────────────────────────────

std::string DspEngine::getStateJson() const {
    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(chainMutex_));
    std::ostringstream ss;
    ss << "{\"buses\":[";
    for (int b = 0; b < TOTAL_BUSES; b++) {
        const Bus& bus = buses_[b];
        if (b > 0) ss << ",";
        ss << "{\"gain\":" << bus.gainDb.load(std::memory_order_relaxed)
           << ",\"pan\":" << bus.pan.load(std::memory_order_relaxed)
           << ",\"muted\":" << (bus.muted.load(std::memory_order_relaxed) ? "true" : "false")
           << ",\"soloed\":" << (bus.soloed.load(std::memory_order_relaxed) ? "true" : "false")
           << ",\"inputEnabled\":" << (bus.inputEnabled.load(std::memory_order_relaxed) ? "true" : "false");
        if (b < NUM_MIX_BUSES) {
            // [dst, level, dst, level, ...]; the master is written as -1 so a
            // save stays readable whatever the strip count becomes.
            ss << ",\"sends\":[";
            bool firstSend = true;
            for (int d = 0; d < TOTAL_BUSES; d++) {
                const float lv = bus.send[d].load(std::memory_order_relaxed);
                if (lv <= 0.0f) continue;
                if (!firstSend) ss << ",";
                firstSend = false;
                ss << (d == MASTER_BUS ? -1 : d) << "," << lv;
            }
            ss << "]";
        }
        ss << ",\"plugins\":[";
        for (int p = 0; p < static_cast<int>(bus.plugins.size()); p++) {
            if (p > 0) ss << ",";
            auto& plug = bus.plugins[p];
            ss << "{\"type\":" << static_cast<int>(plug->getType())
               << ",\"bypassed\":" << (plug->isBypassed() ? "true" : "false")
               << ",\"dryWet\":" << plug->getDryWet()
               << ",\"os\":" << plug->getOversampling()
               << ",\"params\":[";
            for (int i = 0; i < plug->getNumParameters(); i++) {
                if (i > 0) ss << ",";
                ss << plug->getParameter(i);
            }
            ss << "]}";
        }
        ss << "]}";
    }
    ss << "]}";
    return ss.str();
}

void DspEngine::loadStateJson(const std::string& json) {
    // Simple parser — handles the format produced by getStateJson()
    // For robustness, a proper JSON library could be used, but we keep
    // dependencies minimal in the native DSP module.
    //
    // THREADING: process() holds chainMutex_ for the whole mix, so anything
    // this function does under that lock is time the real-time thread spends
    // blocked. Parsing a preset means destroying up to TOTAL_BUSES *
    // MAX_PLUGINS_PER_BUS processors, running an allocating string parse, and
    // constructing + prepareOS-ing a whole new set — which is milliseconds, and
    // at small block sizes that is a dropout, from a normal-priority thread
    // holding a lock the audio thread needs (a textbook priority inversion).
    //
    // So: build everything into `staged` with the lock NOT held, then take it
    // only to swap the finished chains in. The critical section becomes a
    // handful of pointer swaps and atomic stores with no allocation in it, and
    // the displaced processors are destroyed after the lock is released,
    // because `staged` still owns them when it goes out of scope.
    std::vector<std::unique_ptr<SnapinProcessor>> staged[TOTAL_BUSES];
    // Buses in the order the JSON lists them. The LAST one is the master and
    // the rest fill the mix strips from 0, so a pre-48-strip save (4 mix + master)
    // keeps its master on the master instead of landing on strip 5.
    std::vector<std::vector<std::unique_ptr<SnapinProcessor>>> parsedChains;

    // Bus parameters land here first for the same reason — applied under the
    // lock alongside the chain so a preset never lands half-applied.
    struct StagedBus {
        float gainDb = 0.0f;
        float pan = 0.0f;
        bool muted = false;
        bool soloed = false;
        bool inputEnabled = false;
        // Routes as saved: (destination as written — -1 = master, else a
        // strip position — , level). Saves from before routing have none and
        // get the default master route.
        bool hasSends = false;
        std::vector<std::pair<int, float>> sends;
    };
    StagedBus stagedBus[TOTAL_BUSES];
    for (int b = 0; b < TOTAL_BUSES; b++) {
        stagedBus[b].inputEnabled = (b == 0);
    }
    std::vector<StagedBus> parsedBus;
    // Generous bound so a malformed blob can't make this loop forever.
    constexpr int kMaxParsedBuses = TOTAL_BUSES * 2;

    // Minimal JSON parsing
    size_t pos = 0;
    auto findNext = [&](const std::string& key) -> size_t {
        size_t found = json.find(key, pos);
        return found;
    };

    auto readFloat = [&](size_t start) -> float {
        size_t end = json.find_first_of(",]}", start);
        if (end == std::string::npos) return 0.0f;
        return std::stof(json.substr(start, end - start));
    };

    auto readBool = [&](size_t start) -> bool {
        return json.substr(start, 4) == "true";
    };

    int busIdx = 0;
    pos = 0;

    while (pos < json.size() && busIdx < kMaxParsedBuses) {
        size_t busStart = json.find("{\"gain\":", pos);
        if (busStart == std::string::npos) break;
        parsedChains.emplace_back();
        parsedBus.emplace_back();
        parsedBus.back().inputEnabled = (busIdx == 0);

        pos = busStart + 8;
        // Same clamping + NaN filtering setBusGain/setBusPan apply to live
        // edits, inlined here because the staged value is not stored yet.
        parsedBus[busIdx].gainDb =
            std::max(-60.0f, std::min(12.0f, finiteOr(readFloat(pos), 0.0f)));

        size_t panPos = json.find("\"pan\":", pos);
        if (panPos != std::string::npos) {
            parsedBus[busIdx].pan =
                std::max(-1.0f, std::min(1.0f, finiteOr(readFloat(panPos + 6), 0.0f)));
            pos = panPos + 6;
        }

        size_t mutedPos = json.find("\"muted\":", pos);
        if (mutedPos != std::string::npos) {
            parsedBus[busIdx].muted = readBool(mutedPos + 8);
            pos = mutedPos + 8;
        }

        size_t soloedPos = json.find("\"soloed\":", pos);
        if (soloedPos != std::string::npos) {
            parsedBus[busIdx].soloed = readBool(soloedPos + 9);
            pos = soloedPos + 9;
        }

        size_t inputEnabledPos = json.find("\"inputEnabled\":", pos);
        if (inputEnabledPos != std::string::npos && inputEnabledPos < json.find("\"plugins\":", pos)) {
            parsedBus[busIdx].inputEnabled = readBool(inputEnabledPos + 15);
            pos = inputEnabledPos + 15;
        }

        size_t sendsPos = json.find("\"sends\":[", pos);
        if (sendsPos != std::string::npos && sendsPos < json.find("\"plugins\":", pos)) {
            parsedBus[busIdx].hasSends = true;
            pos = sendsPos + 9;
            float pair[2];
            int half = 0;
            while (pos < json.size() && json[pos] != ']' && parsedBus[busIdx].sends.size() < TOTAL_BUSES) {
                if (json[pos] == ',' || json[pos] == ' ') { pos++; continue; }
                pair[half++] = readFloat(pos);
                if (half == 2) {
                    if (std::isfinite(pair[0]) && std::isfinite(pair[1]))
                        parsedBus[busIdx].sends.emplace_back(static_cast<int>(pair[0]), pair[1]);
                    half = 0;
                }
                size_t next = json.find_first_of(",]", pos);
                if (next == std::string::npos) break;
                pos = next;
                if (json[pos] == ',') pos++;
            }
        }

        // Parse plugins array
        size_t pluginsPos = json.find("\"plugins\":[", pos);
        if (pluginsPos != std::string::npos) {
            pos = pluginsPos + 11;

            while (pos < json.size()) {
                size_t typePos = json.find("\"type\":", pos);
                if (typePos == std::string::npos || typePos > json.find("]}", pos)) break;

                pos = typePos + 7;
                int plugType = static_cast<int>(readFloat(pos));

                auto* proc = createSnapin(static_cast<SnapinType>(plugType));
                if (proc) {
                    proc->prepareOS(static_cast<double>(sampleRate_), maxBlockSize_);

                    size_t bypPos = json.find("\"bypassed\":", pos);
                    if (bypPos != std::string::npos) {
                        proc->setBypassed(readBool(bypPos + 11));
                        pos = bypPos + 11;
                    }

                    size_t dwPos = json.find("\"dryWet\":", pos);
                    if (dwPos != std::string::npos && dwPos < json.find("\"params\":", pos)) {
                        // setDryWet already filters NaN + clamps to [0,1]
                        proc->setDryWet(readFloat(dwPos + 9));
                        pos = dwPos + 9;
                    }

                    // Optional (absent in pre-oversampling saves; defaults to 1)
                    size_t osPos = json.find("\"os\":", pos);
                    if (osPos != std::string::npos && osPos < json.find("\"params\":", pos)) {
                        proc->setOversampling(static_cast<int>(readFloat(osPos + 5)));
                        pos = osPos + 5;
                    }

                    size_t paramsPos = json.find("\"params\":[", pos);
                    if (paramsPos != std::string::npos) {
                        pos = paramsPos + 10;
                        int paramIdx = 0;
                        while (pos < json.size() && json[pos] != ']') {
                            if (json[pos] == ',' || json[pos] == ' ') { pos++; continue; }
                            float val = readFloat(pos);
                            // Reject NaN/Inf before reaching per-plugin setParameter (not all
                            // plugins defensively filter non-finite inputs).
                            if (std::isfinite(val)) {
                                proc->setParameter(paramIdx, val);
                            }
                            paramIdx++;
                            size_t next = json.find_first_of(",]", pos);
                            if (next == std::string::npos) break;
                            pos = next;
                            if (json[pos] == ',') pos++;
                        }
                    }

                    parsedChains[busIdx].push_back(std::unique_ptr<SnapinProcessor>(proc));
                }

                // Advance past this plugin object
                size_t endBrace = json.find("}", pos);
                if (endBrace == std::string::npos) break;
                pos = endBrace + 1;
            }
        }

        busIdx++;
    }

    // Map parsed positions onto the bus graph: last = master, the rest = mix
    // strips in order (any beyond NUM_MIX_BUSES are dropped with parsedChains).
    const int parsed = static_cast<int>(parsedBus.size());
    for (int i = 0; i < parsed; i++) {
        const int target = (i == parsed - 1) ? MASTER_BUS : i;
        if (target != MASTER_BUS && target >= NUM_MIX_BUSES) continue;
        staged[target].swap(parsedChains[i]);
        stagedBus[target] = std::move(parsedBus[i]);
    }

    // Resolve routes into a send matrix, adding edges one by one and skipping
    // any that would close a loop, so even a hand-edited file can't make the
    // graph cyclic.
    float stagedSend[NUM_MIX_BUSES][TOTAL_BUSES] = {};
    auto stagedReaches = [&](int from, int to) {
        bool seen[NUM_MIX_BUSES] = {};
        int stack[NUM_MIX_BUSES];
        int top = 0;
        stack[top++] = from;
        seen[from] = true;
        while (top > 0) {
            const int b = stack[--top];
            if (b == to) return true;
            for (int d = 0; d < NUM_MIX_BUSES; d++) {
                if (!seen[d] && stagedSend[b][d] > 0.0f) { seen[d] = true; stack[top++] = d; }
            }
        }
        return false;
    };
    for (int b = 0; b < NUM_MIX_BUSES; b++) {
        if (!stagedBus[b].hasSends) { stagedSend[b][MASTER_BUS] = 1.0f; continue; }
        for (const auto& [where, level] : stagedBus[b].sends) {
            const int d = (where < 0) ? MASTER_BUS : where;
            if (d == b || d > MASTER_BUS || (d >= NUM_MIX_BUSES && d != MASTER_BUS)) continue;
            const float lv = std::max(0.0f, std::min(1.0f, level));
            if (lv <= 0.0f) continue;
            if (d != MASTER_BUS && stagedReaches(d, b)) continue;
            stagedSend[b][d] = lv;
        }
    }

    // Everything above ran with the lock NOT held. Publish it in one short,
    // allocation-free critical section: swap each finished chain in and store
    // the bus parameters. vector::swap only exchanges the internal pointers,
    // so nothing here can allocate or free.
    {
        std::lock_guard<std::mutex> lock(chainMutex_);
        for (int b = 0; b < TOTAL_BUSES; b++) {
            buses_[b].plugins.swap(staged[b]);
            buses_[b].gainDb.store(stagedBus[b].gainDb, std::memory_order_relaxed);
            buses_[b].pan.store(stagedBus[b].pan, std::memory_order_relaxed);
            buses_[b].muted.store(stagedBus[b].muted, std::memory_order_relaxed);
            buses_[b].soloed.store(stagedBus[b].soloed, std::memory_order_relaxed);
            buses_[b].inputEnabled.store(stagedBus[b].inputEnabled, std::memory_order_relaxed);
            if (b < NUM_MIX_BUSES) {
                for (int d = 0; d < TOTAL_BUSES; d++)
                    buses_[b].send[d].store(stagedSend[b][d], std::memory_order_relaxed);
            }
        }
        rebuildOrder();
    }

    // `staged` now holds the processors the preset displaced. They are freed
    // here, at scope exit, with the lock already released — so the audio thread
    // never waits on a destructor.
    LOGD("Loaded state JSON, %d buses parsed", busIdx);
}
