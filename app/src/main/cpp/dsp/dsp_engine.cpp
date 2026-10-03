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
#include <cstdlib>
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
    sumL_.resize(maxBlockSize, 0.0f);
    sumR_.resize(maxBlockSize, 0.0f);
    dryBufL_.resize(maxBlockSize, 0.0f);
    dryBufR_.resize(maxBlockSize, 0.0f);
    inRingL_.assign(PDC_SIZE, 0.0f);
    inRingR_.assign(PDC_SIZE, 0.0f);
    for (auto& bus : buses_) {
        bus.pdcL.assign(PDC_SIZE, 0.0f);
        bus.pdcR.assign(PDC_SIZE, 0.0f);
        bus.inL.assign(ROUTE_BLOCK, 0.0f);
        bus.inR.assign(ROUTE_BLOCK, 0.0f);
        defaultSendsLocked(bus);
        // Inserting a plugin happens under the chain lock; with the room
        // reserved here it never reallocates there.
        bus.plugins.reserve(MAX_PLUGINS_PER_BUS);
    }

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
        sumL_.resize(maxBlockSize_, 0.0f);
        sumR_.resize(maxBlockSize_, 0.0f);
        dryBufL_.resize(maxBlockSize_, 0.0f);
        dryBufR_.resize(maxBlockSize_, 0.0f);
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
    int wp = bus.waveTapPos.load(std::memory_order_relaxed);
    for (int i = 0; i < numFrames; i++) {
        bus.waveTap[wp] = 0.0f;
        wp = (wp + 1) & (WAVE_TAP_SIZE - 1);
    }
    bus.waveTapPos.store(wp, std::memory_order_relaxed);
}

void DspEngine::process(float* left, float* right, int numFrames) {
    if (numFrames <= 0) return;
    // Lock for reading plugin chains (brief lock — plugins don't allocate during process)
    std::lock_guard<std::mutex> lock(chainMutex_);
    // Every scratch buffer holds maxBlockSize_ frames; a longer block runs in
    // pieces instead of writing past them.
    for (int done = 0; done < numFrames; done += maxBlockSize_) {
        const int n = std::min(maxBlockSize_, numFrames - done);
        processBlockLocked(left + done, right + done, n);
    }
}

void DspEngine::processBlockLocked(float* left, float* right, int numFrames) {
    // A NaN or infinity from upstream would lodge in every filter and
    // feedback path it reached and silence the mix until the effects were
    // rebuilt. It never gets in.
    dspZeroNonFinite(left, numFrames);
    dspZeroNonFinite(right, numFrames);
    processMixBusesLocked(left, right, numFrames);
    const int slots = masterSlotCountLocked();
    for (int s = 0; s < slots; s++) processMasterSlotLocked(s, numFrames, nullptr);
    finishBlockLocked(left, right, numFrames);
}

void DspEngine::runSlotLocked(SnapinProcessor& plugin, float* l, float* r, int numFrames, bool run) {
    const float dw = plugin.getDryWet();
    if (!run || dw <= 0.001f) {
        // Not processing, but still as late as when it is: the chain's delay
        // must not change with the bypass switch or the mix knob.
        plugin.delayDry(l, r, numFrames);
        return;
    }
    // The dry signal, for the blend and for the guard below. Delayed by the
    // oversampling latency so it lines up with the wet, which also keeps the
    // effect's dry line fed while it runs.
    std::copy(l, l + numFrames, dryBufL_.begin());
    std::copy(r, r + numFrames, dryBufR_.begin());
    plugin.delayDry(dryBufL_.data(), dryBufR_.data(), numFrames);
    plugin.processOS(l, r, numFrames);
    if (!dspAllFinite(l, numFrames) || !dspAllFinite(r, numFrames)) {
        // The effect has blown up (a filter pushed past its limits, a
        // feedback path with no floor). Its state is poison now, so it is
        // cleared, and this block plays dry rather than as silence or noise.
        plugin.resetAll();
        std::copy(dryBufL_.begin(), dryBufL_.begin() + numFrames, l);
        std::copy(dryBufR_.begin(), dryBufR_.begin() + numFrames, r);
        return;
    }
    if (dw < 0.999f) {
        const float wet = dw, dry = 1.0f - dw;
        for (int i = 0; i < numFrames; i++) {
            l[i] = dryBufL_[i] * dry + l[i] * wet;
            r[i] = dryBufR_[i] * dry + r[i] * wet;
        }
    }
}

int DspEngine::chainLatencyLocked(const Bus& bus) {
    int total = 0;
    for (const auto& plugin : bus.plugins) {
        if (plugin) total += plugin->latency();
    }
    return total;
}

int DspEngine::masterSlotCountLocked() const {
    return static_cast<int>(buses_[MASTER_BUS].plugins.size());
}

bool DspEngine::masterSlotLinkableLocked(int slot) const {
    const Bus& master = buses_[MASTER_BUS];
    if (slot < 0 || slot >= static_cast<int>(master.plugins.size())) return false;
    const auto& plugin = master.plugins[static_cast<size_t>(slot)];
    return plugin && !plugin->isBypassed() && plugin->getDryWet() > 0.001f &&
           plugin->supportsLinkedDetection();
}

bool DspEngine::skippedOnThisLane(const SnapinProcessor& plugin) const {
    if (!monoLane_) return false;
    // Effects that only reshape a stereo image. On one channel there is no
    // image, and Haas on dual-mono folded back to mono is a comb filter.
    switch (plugin.getType()) {
        case SnapinType::STEREO:
        case SnapinType::HAAS:
        case SnapinType::CHANNEL_MIXER:
            return true;
        default:
            return false;
    }
}

// ── Routing ─────────────────────────────────────────────────────────────

// dst[i] += x[i - k] * g, where x is [cur] for this piece and [ring] (history,
// next write at [ringPos]) before it; g ramps g0 -> g1 across the piece.
static inline void tapAdd(float* dst, const float* cur, const std::vector<float>& ring, int ringPos,
                          int n, int k, float g0, float g1) {
    const float step = (g1 - g0) / static_cast<float>(n);
    float g = g0;
    for (int i = 0; i < n; i++) {
        g += step;
        const float x = i >= k ? cur[i - k]
                               : ring[static_cast<size_t>((ringPos - (k - i)) & (PDC_SIZE - 1))];
        dst[i] += x * g;
    }
}

static inline void ringAppend(std::vector<float>& ring, int& pos, const float* x, int n) {
    for (int i = 0; i < n; i++) {
        ring[static_cast<size_t>(pos)] = x[i];
        pos = (pos + 1) & (PDC_SIZE - 1);
    }
}

void DspEngine::defaultSendsLocked(Bus& bus) {
    for (int d = 0; d < TOTAL_BUSES; d++) {
        bus.send[d].store(d == MASTER_BUS ? 1.0f : 0.0f, std::memory_order_relaxed);
        bus.sendApplied[d] = d == MASTER_BUS ? 1.0f : 0.0f;
    }
}

int DspEngine::orderLocked(int* order) const {
    // Kahn's algorithm, lowest index first among the ready, so an unrouted
    // mixer runs in index order as it always has. The graph is kept free of
    // loops; were one ever to slip in, its buses still run, appended.
    const int active = activeBusCount();
    int indegree[TOTAL_BUSES] = {};
    for (int s = 0; s < active; s++) {
        if (s == MASTER_BUS) continue;
        for (int d = 0; d < active; d++) {
            if (d != MASTER_BUS && buses_[s].send[d].load(std::memory_order_relaxed) > 0.0f) indegree[d]++;
        }
    }
    bool placed[TOTAL_BUSES] = {};
    const int mixCount = active - 1;
    int n = 0;
    while (n < mixCount) {
        int pick = -1;
        for (int b = 0; b < active; b++) {
            if (b != MASTER_BUS && !placed[b] && indegree[b] == 0) { pick = b; break; }
        }
        if (pick < 0) break;
        placed[pick] = true;
        order[n++] = pick;
        for (int d = 0; d < active; d++) {
            if (d != MASTER_BUS && buses_[pick].send[d].load(std::memory_order_relaxed) > 0.0f) indegree[d]--;
        }
    }
    for (int b = 0; b < active && n < mixCount; b++) {
        if (b != MASTER_BUS && !placed[b]) order[n++] = b;
    }
    return n;
}

bool DspEngine::reachesLocked(int from, int to) const {
    if (from == to) return true;
    const int active = activeBusCount();
    bool seen[TOTAL_BUSES] = {};
    int stack[TOTAL_BUSES];
    int top = 0;
    stack[top++] = from;
    seen[from] = true;
    while (top > 0) {
        const int b = stack[--top];
        for (int d = 0; d < active; d++) {
            if (d == MASTER_BUS || seen[d]) continue;
            if (buses_[b].send[d].load(std::memory_order_relaxed) <= 0.0f) continue;
            if (d == to) return true;
            seen[d] = true;
            stack[top++] = d;
        }
    }
    return false;
}

void DspEngine::processMixBusesLocked(const float* left, const float* right, int numFrames) {
    // Flush denormals to zero — prevents 10-100x CPU spikes in feedback tails
    enableFlushToZero();

    // Clear sum buffers
    std::fill(sumL_.begin(), sumL_.begin() + numFrames, 0.0f);
    std::fill(sumR_.begin(), sumR_.begin() + numFrames, 0.0f);

    const int active = activeBusCount();
    orderCount_ = orderLocked(order_);

    // A routed lane feeds its own bus only, among the routed ones.
    const int routedBus = routedBus_.load(std::memory_order_relaxed);
    const int routedCount = routedCount_.load(std::memory_order_relaxed);

    // ── Delay compensation over the routing graph ──────────────────────
    // A bus's input is as late as the latest thing arriving at it (its
    // input delay); its output is that plus its own effects (its latency).
    // Every route reads the sender's output history at the difference, so
    // at each bus and at the master everything arrives in step. Worked out
    // from the graph alone — not from what sounds on this lane — so every
    // lane of a wide stream, which share the graph, is delayed alike and its
    // channels stay together.
    for (int b = 0; b < active; b++) inDelay_[b] = 0;
    masterInDelay_ = 0;
    for (int k = 0; k < orderCount_; k++) {
        const int b = order_[k];
        outLat_[b] = inDelay_[b] + chainLatencyLocked(buses_[b]);
        for (int d = 0; d < active; d++) {
            if (d == b || buses_[b].send[d].load(std::memory_order_relaxed) <= 0.0f) continue;
            if (d == MASTER_BUS) masterInDelay_ = std::max(masterInDelay_, outLat_[b]);
            else inDelay_[d] = std::max(inDelay_[d], outLat_[b]);
        }
    }

    // ── Who runs, and who is heard ─────────────────────────────────────
    // Live: takes the player's signal, or something live sends to it. Live
    // ignores mute, so an effect bus fed by a bus just muted keeps running on
    // silence and its tail rings out instead of freezing.
    for (int b = 0; b < active; b++) live_[b] = false;
    for (int k = 0; k < orderCount_; k++) {
        const int b = order_[k];
        bool input = buses_[b].inputEnabled.load(std::memory_order_relaxed);
        if (routedBus >= 0) {
            input = b == routedBus || (busNumberForIndex(b) > routedCount && input);
        }
        takesInput_[b] = input;
        if (input) live_[b] = true;
        if (!live_[b]) continue;
        for (int d = 0; d < active; d++) {
            if (d != MASTER_BUS && d != b && buses_[b].send[d].load(std::memory_order_relaxed) > 0.0f) live_[d] = true;
        }
    }
    // Solo, as in FL: a soloed bus is heard with everything it feeds and
    // everything feeding it; every other bus is silenced.
    if (anySoloed()) {
        bool down[TOTAL_BUSES] = {}, up[TOTAL_BUSES] = {};
        for (int k = 0; k < orderCount_; k++) {
            const int b = order_[k];
            if (buses_[b].soloed.load(std::memory_order_relaxed)) down[b] = true;
            if (!down[b]) continue;
            for (int d = 0; d < active; d++) {
                if (d != MASTER_BUS && buses_[b].send[d].load(std::memory_order_relaxed) > 0.0f) down[d] = true;
            }
        }
        for (int k = orderCount_ - 1; k >= 0; k--) {
            const int b = order_[k];
            if (buses_[b].soloed.load(std::memory_order_relaxed)) { up[b] = true; continue; }
            for (int d = 0; d < active; d++) {
                if (d != MASTER_BUS && up[d] && buses_[b].send[d].load(std::memory_order_relaxed) > 0.0f) {
                    up[b] = true;
                    break;
                }
            }
        }
        for (int b = 0; b < active; b++) audible_[b] = down[b] || up[b];
    } else {
        for (int b = 0; b < active; b++) audible_[b] = true;
    }

    for (int b = 0; b < active; b++) blockPeakL_[b] = blockPeakR_[b] = 0.0f;
    for (int done = 0; done < numFrames; done += ROUTE_BLOCK) {
        processMixPieceLocked(left, right, done, std::min(ROUTE_BLOCK, numFrames - done), done == 0);
    }
    // Update peak meters (relaxed store — UI reads are non-critical)
    for (int b = 0; b < active; b++) {
        if (b == MASTER_BUS) continue;
        buses_[b].peakL.store(blockPeakL_[b], std::memory_order_relaxed);
        buses_[b].peakR.store(blockPeakR_[b], std::memory_order_relaxed);
    }
}

void DspEngine::processMixPieceLocked(const float* left, const float* right, int offset, int numFrames,
                                      bool first) {
    const float* inPL = left + offset;
    const float* inPR = right + offset;
    const bool mixBypass = mixBypassed_.load(std::memory_order_relaxed);
    const int active = activeBusCount();

    // Every live bus starts the piece empty; the buses before it in the
    // order add their sends as they finish.
    for (int k = 0; k < orderCount_; k++) {
        Bus& bus = buses_[order_[k]];
        if (!live_[order_[k]]) continue;
        std::fill(bus.inL.begin(), bus.inL.begin() + numFrames, 0.0f);
        std::fill(bus.inR.begin(), bus.inR.begin() + numFrames, 0.0f);
    }

    for (int k = 0; k < orderCount_; k++) {
        const int b = order_[k];
        Bus& bus = buses_[b];

        bool busMuted = bus.muted.load(std::memory_order_relaxed);
        float busGainDb = mixBypass ? 0.0f : bus.gainDb.load(std::memory_order_relaxed);
        // A mono lane has no stereo image to place: its pan sits at centre.
        float busPan = (mixBypass || monoLane_) ? 0.0f : bus.pan.load(std::memory_order_relaxed);

        // Not running, muted, or solo'd out: contributes nothing.
        if (!live_[b] || busMuted || !audible_[b]) {
            if (first) zeroSlotMeters(bus);
            writeWaveSilence(bus, numFrames);
            // Its history is from before; cleared when it returns.
            bus.pdcStale = true;
            // Sends parked at their targets, so a bus coming back doesn't
            // glide in from a stale level.
            for (int d = 0; d < TOTAL_BUSES; d++) {
                bus.sendApplied[d] = bus.send[d].load(std::memory_order_relaxed);
            }
            continue;
        }
        if (bus.pdcStale) {
            std::fill(bus.pdcL.begin(), bus.pdcL.end(), 0.0f);
            std::fill(bus.pdcR.begin(), bus.pdcR.end(), 0.0f);
            bus.pdcStale = false;
        }

        float* l = bus.inL.data();
        float* r = bus.inR.data();
        // The player's signal, delayed to arrive with the sends into this bus.
        if (takesInput_[b]) {
            const int delay = std::min(PDC_SIZE - 1, inDelay_[b]);
            tapAdd(l, inPL, inRingL_, inRingPos_, numFrames, delay, 1.0f, 1.0f);
            tapAdd(r, inPR, inRingR_, inRingPos_, numFrames, delay, 1.0f, 1.0f);
        }

        // Run plugin chain with dry/wet blending (skip when mixer DSP is
        // bypassed — but still at the chain's delay, as bypassed slots are).
        if (mixBypass) {
            if (first) zeroSlotMeters(bus);
            for (auto& plugin : bus.plugins) {
                if (plugin) runSlotLocked(*plugin, l, r, numFrames, false);
            }
        } else {
            for (size_t s = 0; s < bus.plugins.size(); s++) {
                auto& plugin = bus.plugins[s];
                if (!plugin) continue;
                const bool run = !plugin->isBypassed() && !skippedOnThisLane(*plugin);
                // Block peak across the pieces: the first sets, the rest raise.
                auto meter = [&](std::atomic<float>& m) {
                    const float v = stereoPeak(l, r, numFrames);
                    m.store(first ? v : std::max(v, m.load(std::memory_order_relaxed)),
                            std::memory_order_relaxed);
                };
                if (run) meter(bus.slotInPeak[s]);
                runSlotLocked(*plugin, l, r, numFrames, run);
                if (run) {
                    meter(bus.slotOutPeak[s]);
                } else {
                    bus.slotInPeak[s].store(0.0f, std::memory_order_relaxed);
                    bus.slotOutPeak[s].store(0.0f, std::memory_order_relaxed);
                }
            }
        }

        // Recalculate target gains from dB + pan
        recalcBusGains(busGainDb, busPan, bus.targetGainL, bus.targetGainR);

        // Fader and pan with smoothing, in place: the buffer is post-fader now.
        float busPeakL = blockPeakL_[b], busPeakR = blockPeakR_[b];
        int wavePos = bus.waveTapPos.load(std::memory_order_relaxed);
        for (int i = 0; i < numFrames; i++) {
            bus.smoothGainL += gainSmoothCoeff_ * (bus.targetGainL - bus.smoothGainL);
            bus.smoothGainR += gainSmoothCoeff_ * (bus.targetGainR - bus.smoothGainR);
            float sL = l[i] * bus.smoothGainL;
            float sR = r[i] * bus.smoothGainR;
            l[i] = sL;
            r[i] = sR;
            float absL = std::fabs(sL);
            float absR = std::fabs(sR);
            if (absL > busPeakL) busPeakL = absL;
            if (absR > busPeakR) busPeakR = absR;
            // Post-fader mono scope tap
            bus.waveTap[wavePos] = 0.5f * (sL + sR);
            wavePos = (wavePos + 1) & (WAVE_TAP_SIZE - 1);
        }
        bus.waveTapPos.store(wavePos, std::memory_order_relaxed);
        blockPeakL_[b] = busPeakL;
        blockPeakR_[b] = busPeakR;

        // Routes out: each destination gets this bus's output at the delay
        // that lines it up there, at a level ramped from last piece's.
        for (int d = 0; d < active; d++) {
            if (d == b) continue;
            const float target = bus.send[d].load(std::memory_order_relaxed);
            const float from = bus.sendApplied[d];
            bus.sendApplied[d] = target;
            if (target <= 0.0f && from <= 0.0f) continue;
            float* dl;
            float* dr;
            int delay;
            if (d == MASTER_BUS) {
                dl = sumL_.data() + offset;
                dr = sumR_.data() + offset;
                delay = masterInDelay_ - outLat_[b];
            } else {
                // A route fading out may point at a bus that no longer runs
                // (or already ran): nothing there to add to.
                if (!live_[d]) continue;
                dl = buses_[d].inL.data();
                dr = buses_[d].inR.data();
                delay = inDelay_[d] - outLat_[b];
            }
            delay = std::max(0, std::min(PDC_SIZE - 1, delay));
            tapAdd(dl, l, bus.pdcL, bus.pdcPos, numFrames, delay, from, target);
            tapAdd(dr, r, bus.pdcR, bus.pdcPos, numFrames, delay, from, target);
        }
        int pos = bus.pdcPos;
        ringAppend(bus.pdcL, pos, l, numFrames);
        pos = bus.pdcPos;
        ringAppend(bus.pdcR, pos, r, numFrames);
        bus.pdcPos = pos;
    }

    int pos = inRingPos_;
    ringAppend(inRingL_, pos, inPL, numFrames);
    pos = inRingPos_;
    ringAppend(inRingR_, pos, inPR, numFrames);
    inRingPos_ = pos;
}

void DspEngine::processMasterSlotLocked(int slot, int numFrames, const float* detectorKey) {
    Bus& master = buses_[MASTER_BUS];
    if (slot < 0 || slot >= static_cast<int>(master.plugins.size())) return;
    const size_t s = static_cast<size_t>(slot);
    {
        auto& plugin = master.plugins[s];
        if (plugin && !plugin->isBypassed() && !skippedOnThisLane(*plugin)) {
            // Linked detection for this block only: a multi-lane host hands
            // every lane the same key, so every lane computes the same gain.
            plugin->setDetectorKey(plugin->supportsLinkedDetection() ? detectorKey : nullptr);
            master.slotInPeak[s].store(
                stereoPeak(sumL_.data(), sumR_.data(), numFrames),
                std::memory_order_relaxed);
            runSlotLocked(*plugin, sumL_.data(), sumR_.data(), numFrames, true);
            master.slotOutPeak[s].store(
                stereoPeak(sumL_.data(), sumR_.data(), numFrames),
                std::memory_order_relaxed);
            plugin->setDetectorKey(nullptr);
        } else {
            // Bypassed, it still delays by its latency, as on a mix bus.
            if (plugin) runSlotLocked(*plugin, sumL_.data(), sumR_.data(), numFrames, false);
            master.slotInPeak[s].store(0.0f, std::memory_order_relaxed);
            master.slotOutPeak[s].store(0.0f, std::memory_order_relaxed);
        }
    }
}

void DspEngine::finishBlockLocked(float* left, float* right, int numFrames) {
    Bus& master = buses_[MASTER_BUS];
    // Apply master gain and write to output
    float masterGainDb = master.gainDb.load(std::memory_order_relaxed);
    float masterPan = monoLane_ ? 0.0f : master.pan.load(std::memory_order_relaxed);
    recalcBusGains(masterGainDb, masterPan, master.targetGainL, master.targetGainR);
    float masterPeakL = 0.0f, masterPeakR = 0.0f;
    bool clipped = false;
    int masterWavePos = master.waveTapPos.load(std::memory_order_relaxed);
    for (int i = 0; i < numFrames; i++) {
        master.smoothGainL += gainSmoothCoeff_ * (master.targetGainL - master.smoothGainL);
        master.smoothGainR += gainSmoothCoeff_ * (master.targetGainR - master.smoothGainR);
        left[i]  = sumL_[i] * master.smoothGainL;
        right[i] = sumR_[i] * master.smoothGainR;
        // Last line: nothing non-finite leaves the engine.
        if (!dspIsFinite(left[i])) left[i] = 0.0f;
        if (!dspIsFinite(right[i])) right[i] = 0.0f;
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
    for (int b = 0; b < activeBusCount(); b++) {
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
        bus.publishMeters();
    }
}

// ── Bus control ─────────────────────────────────────────────────────────

// Sanitize: reject NaN/Inf (would poison the real-time atomics and trigger NaN audio).
static inline float finiteOr(float v, float fallback) {
    return dspIsFinite(v) ? v : fallback;
}

void DspEngine::setBusGain(int busIndex, float gainDb) {
    if (!isActiveBus(busIndex)) return;
    const float clamped = std::max(-60.0f, std::min(12.0f, finiteOr(gainDb, 0.0f)));
    buses_[busIndex].gainDb.store(clamped, std::memory_order_relaxed);
}

void DspEngine::setBusPan(int busIndex, float pan) {
    if (!isActiveBus(busIndex)) return;
    buses_[busIndex].pan.store(
        std::max(-1.0f, std::min(1.0f, finiteOr(pan, 0.0f))),
        std::memory_order_relaxed);
}

void DspEngine::setBusMute(int busIndex, bool muted) {
    if (!isActiveBus(busIndex)) return;
    buses_[busIndex].muted.store(muted, std::memory_order_relaxed);
}

void DspEngine::setBusSolo(int busIndex, bool soloed) {
    if (!isActiveBus(busIndex)) return;
    buses_[busIndex].soloed.store(soloed, std::memory_order_relaxed);
}

// ── Plugin chain management ─────────────────────────────────────────────

int DspEngine::addPlugin(int busIndex, int slotIndex, int pluginType) {
    if (!isActiveBus(busIndex)) return -1;
    if (pluginType < 0 || pluginType >= static_cast<int>(SnapinType::COUNT)) return -1;
    Bus& bus = buses_[busIndex];

    // Built and prepared before the lock, and if the chain turns out to be
    // full, freed after it.
    std::unique_ptr<SnapinProcessor> proc(createSnapin(static_cast<SnapinType>(pluginType)));
    if (!proc) return -1;
    proc->prepareOS(static_cast<double>(sampleRate_), maxBlockSize_);

    int idx;
    {
        std::lock_guard<std::mutex> lock(chainMutex_);
        // Size read under the lock: another add could have filled it since.
        const int size = static_cast<int>(bus.plugins.size());
        if (size >= MAX_PLUGINS_PER_BUS) return -1;
        // Past the end appends; a negative slot inserts first. It used to go
        // straight to begin() + slot, and slot -1 wrote before the vector.
        idx = std::max(0, std::min(slotIndex, size));
        bus.plugins.insert(bus.plugins.begin() + idx, std::move(proc));
    }

    LOGD("Added plugin type %d to bus %d slot %d", pluginType, busIndex, idx);
    return idx;
}

void DspEngine::removePlugin(int busIndex, int slotIndex) {
    if (!isActiveBus(busIndex)) return;
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
    if (!isActiveBus(busIndex)) return;
    Bus& bus = buses_[busIndex];
    std::lock_guard<std::mutex> lock(chainMutex_);
    // Bounds read under the lock, like removePlugin's.
    int sz = static_cast<int>(bus.plugins.size());
    if (fromSlot < 0 || fromSlot >= sz || toSlot < 0 || toSlot >= sz) return;
    if (fromSlot == toSlot) return;

    auto plugin = std::move(bus.plugins[fromSlot]);
    bus.plugins.erase(bus.plugins.begin() + fromSlot);
    bus.plugins.insert(bus.plugins.begin() + toSlot, std::move(plugin));
}

void DspEngine::setParameter(int busIndex, int slotIndex, int paramIndex, float value) {
    if (!isActiveBus(busIndex)) return;
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
    if (bus.plugins[slotIndex]) {
        bus.plugins[slotIndex]->applyParameter(paramIndex, value);
    }
}

void DspEngine::setPluginBypassed(int busIndex, int slotIndex, bool bypassed) {
    if (!isActiveBus(busIndex)) return;
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
    if (bus.plugins[slotIndex]) {
        bus.plugins[slotIndex]->setBypassed(bypassed);
    }
}

void DspEngine::setBusInputEnabled(int busIndex, bool enabled) {
    if (!isMixBus(busIndex)) return;  // Only mix buses, not master
    buses_[busIndex].inputEnabled.store(enabled, std::memory_order_relaxed);
}

void DspEngine::setMixBypassed(bool bypassed) {
    mixBypassed_.store(bypassed, std::memory_order_relaxed);
}

bool DspEngine::setSend(int src, int dst, float level) {
    const float clamped = std::max(0.0f, std::min(1.0f, finiteOr(level, 0.0f)));
    std::lock_guard<std::mutex> lock(chainMutex_);
    if (!isMixBus(src) || !isActiveBus(dst) || dst == src) return false;
    Bus& bus = buses_[src];
    const bool had = bus.send[dst].load(std::memory_order_relaxed) > 0.0f;
    // A new bus-to-bus route must not close a loop.
    if (clamped > 0.0f && !had && dst != MASTER_BUS && reachesLocked(dst, src)) return false;
    bus.send[dst].store(clamped, std::memory_order_relaxed);
    return true;
}

float DspEngine::getSend(int src, int dst) const {
    if (src < 0 || src >= TOTAL_BUSES || dst < 0 || dst >= TOTAL_BUSES) return 0.0f;
    return buses_[src].send[dst].load(std::memory_order_relaxed);
}

void DspEngine::setPluginDryWet(int busIndex, int slotIndex, float dryWet) {
    if (!isActiveBus(busIndex)) return;
    std::lock_guard<std::mutex> lock(chainMutex_);
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
    if (bus.plugins[slotIndex]) {
        bus.plugins[slotIndex]->setDryWet(dryWet);
    }
}

void DspEngine::setPluginOversampling(int busIndex, int slotIndex, int factor) {
    if (!isActiveBus(busIndex)) return;
    // A new factor means a new internal rate: every delay line, reverb tank
    // and filter of the effect re-made. Done in place, that ran under the
    // chain lock — milliseconds of allocation, at 4x megabytes of it, with
    // the audio thread waiting. So the effect is rebuilt beside the chain,
    // from a snapshot of its settings, and only the pointer swap is locked.
    SnapinType type;
    float params[64];
    int paramCount;
    float dryWet;
    bool bypassed;
    const SnapinProcessor* current;
    {
        std::lock_guard<std::mutex> lock(chainMutex_);
        Bus& bus = buses_[busIndex];
        if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;
        current = bus.plugins[slotIndex].get();
        if (!current) return;
        const int f = factor >= 4 ? 4 : (factor >= 2 ? 2 : 1);
        if (current->getOversampling() == f) return;
        type = current->getType();
        paramCount = std::min(64, current->getNumParameters());
        for (int i = 0; i < paramCount; i++) params[i] = current->getParameter(i);
        dryWet = current->getDryWet();
        bypassed = current->isBypassed();
    }
    std::unique_ptr<SnapinProcessor> fresh(createSnapin(type));
    if (!fresh) return;
    for (int i = 0; i < paramCount; i++) fresh->applyParameter(i, params[i]);
    fresh->setDryWet(dryWet);
    fresh->setBypassed(bypassed);
    fresh->setOversampling(factor);
    fresh->prepareOS(static_cast<double>(sampleRate_), maxBlockSize_);
    {
        std::lock_guard<std::mutex> lock(chainMutex_);
        Bus& bus = buses_[busIndex];
        // The chain may have changed meanwhile; only replace what was read.
        if (slotIndex >= static_cast<int>(bus.plugins.size()) ||
            bus.plugins[slotIndex].get() != current) return;
        bus.plugins[slotIndex].swap(fresh);
    }
    // `fresh` holds the old effect now, freed here, outside the lock.
}

// ── Adding and removing buses ───────────────────────────────────────────

void DspEngine::resetBusLocked(Bus& bus) {
    // Plugins are never destroyed here: callers move them out first, so the
    // lock is not held across a destructor.
    bus.gainDb.store(0.0f, std::memory_order_relaxed);
    bus.pan.store(0.0f, std::memory_order_relaxed);
    bus.muted.store(false, std::memory_order_relaxed);
    bus.soloed.store(false, std::memory_order_relaxed);
    bus.inputEnabled.store(false, std::memory_order_relaxed);
    bus.smoothGainL = bus.smoothGainR = bus.targetGainL = bus.targetGainR = 1.0f;
    bus.peakL.store(0.0f, std::memory_order_relaxed);
    bus.peakR.store(0.0f, std::memory_order_relaxed);
    for (int s = 0; s < MAX_PLUGINS_PER_BUS; s++) {
        bus.slotInPeak[s].store(0.0f, std::memory_order_relaxed);
        bus.slotOutPeak[s].store(0.0f, std::memory_order_relaxed);
    }
    std::fill(std::begin(bus.waveTap), std::end(bus.waveTap), 0.0f);
    bus.waveTapPos.store(0, std::memory_order_relaxed);
    bus.decayL = bus.decayR = bus.holdL = bus.holdR = -60.0f;
    bus.holdCounterL = bus.holdCounterR = 0;
    bus.publishMeters();
    bus.pdcStale = true;
    defaultSendsLocked(bus);
}

void DspEngine::moveBusLocked(Bus& dst, Bus& src) {
    // dst's chain is empty by the time this runs (see removeBus), so the swap
    // leaves src empty — no allocation, no free.
    dst.plugins.swap(src.plugins);
    dst.gainDb.store(src.gainDb.load(std::memory_order_relaxed), std::memory_order_relaxed);
    dst.pan.store(src.pan.load(std::memory_order_relaxed), std::memory_order_relaxed);
    dst.muted.store(src.muted.load(std::memory_order_relaxed), std::memory_order_relaxed);
    dst.soloed.store(src.soloed.load(std::memory_order_relaxed), std::memory_order_relaxed);
    dst.inputEnabled.store(src.inputEnabled.load(std::memory_order_relaxed), std::memory_order_relaxed);
    dst.smoothGainL = src.smoothGainL;
    dst.smoothGainR = src.smoothGainR;
    dst.targetGainL = src.targetGainL;
    dst.targetGainR = src.targetGainR;
    dst.peakL.store(src.peakL.load(std::memory_order_relaxed), std::memory_order_relaxed);
    dst.peakR.store(src.peakR.load(std::memory_order_relaxed), std::memory_order_relaxed);
    for (int s = 0; s < MAX_PLUGINS_PER_BUS; s++) {
        dst.slotInPeak[s].store(src.slotInPeak[s].load(std::memory_order_relaxed), std::memory_order_relaxed);
        dst.slotOutPeak[s].store(src.slotOutPeak[s].load(std::memory_order_relaxed), std::memory_order_relaxed);
    }
    std::copy(std::begin(src.waveTap), std::end(src.waveTap), std::begin(dst.waveTap));
    dst.waveTapPos.store(src.waveTapPos.load(std::memory_order_relaxed), std::memory_order_relaxed);
    dst.decayL = src.decayL;
    dst.decayR = src.decayR;
    dst.holdL = src.holdL;
    dst.holdR = src.holdR;
    dst.holdCounterL = src.holdCounterL;
    dst.holdCounterR = src.holdCounterR;
    dst.publishMeters();
    dst.pdcStale = true;
    src.pdcStale = true;
    // Destinations were already renumbered by removeBus.
    for (int d = 0; d < TOTAL_BUSES; d++) {
        dst.send[d].store(src.send[d].load(std::memory_order_relaxed), std::memory_order_relaxed);
        dst.sendApplied[d] = src.sendApplied[d];
    }
}

int DspEngine::addBus() {
    std::lock_guard<std::mutex> lock(chainMutex_);
    const int count = mixBusCount_.load(std::memory_order_relaxed);
    if (count >= MAX_MIX_BUSES) return -1;
    // Active buses are 0..count with the master at 4, so the next free index
    // is count + 1: bus 5 is index 5, bus 6 index 6, and so on.
    const int index = count + 1;
    resetBusLocked(buses_[index]);
    mixBusCount_.store(count + 1, std::memory_order_relaxed);
    LOGD("Added bus %d (%d mix buses)", index, count + 1);
    return index;
}

bool DspEngine::removeBus(int busIndex) {
    // Outlives the lock: the removed bus's plugins are freed after it is
    // released, never while the audio thread waits on it.
    std::vector<std::unique_ptr<SnapinProcessor>> retired;
    {
        std::lock_guard<std::mutex> lock(chainMutex_);
        const int count = mixBusCount_.load(std::memory_order_relaxed);
        if (busIndex <= MASTER_BUS || busIndex > count) return false;
        // A bus a channel group is routed to stays while it is.
        if (busNumberForIndex(busIndex) <= routedCount_.load(std::memory_order_relaxed)) return false;
        retired.swap(buses_[busIndex].plugins);
        // Routes into the removed bus go; routes to the buses above it follow
        // them down one index.
        for (int b = 0; b <= count; b++) {
            if (b == MASTER_BUS) continue;
            Bus& from = buses_[b];
            for (int d = busIndex; d < count; d++) {
                from.send[d].store(from.send[d + 1].load(std::memory_order_relaxed), std::memory_order_relaxed);
                from.sendApplied[d] = from.sendApplied[d + 1];
            }
            from.send[count].store(0.0f, std::memory_order_relaxed);
            from.sendApplied[count] = 0.0f;
        }
        for (int b = busIndex; b < count; b++) moveBusLocked(buses_[b], buses_[b + 1]);
        resetBusLocked(buses_[count]);
        mixBusCount_.store(count - 1, std::memory_order_relaxed);
    }
    LOGD("Removed bus %d", busIndex);
    return true;
}

// ── Metering ────────────────────────────────────────────────────────────

void DspEngine::getBusLevels(float* outLevels, int maxFloats) {
    // Output format: [peakL, peakR, holdL, holdR] per bus (4 floats each)
    int count = std::min(maxFloats, activeBusCount() * 4);
    for (int b = 0; b < TOTAL_BUSES && b * 4 + 3 < count; b++) {
        outLevels[b * 4]     = buses_[b].shownDecayL.load(std::memory_order_relaxed);
        outLevels[b * 4 + 1] = buses_[b].shownDecayR.load(std::memory_order_relaxed);
        outLevels[b * 4 + 2] = buses_[b].shownHoldL.load(std::memory_order_relaxed);
        outLevels[b * 4 + 3] = buses_[b].shownHoldR.load(std::memory_order_relaxed);
    }
}

void DspEngine::resetMeters() {
    std::lock_guard<std::mutex> lock(chainMutex_);
    for (int b = 0; b < TOTAL_BUSES; b++) {
        Bus& bus = buses_[b];
        bus.decayL = bus.decayR = bus.holdL = bus.holdR = -60.0f;
        bus.holdCounterL = bus.holdCounterR = 0;
        bus.publishMeters();
        bus.peakL.store(0.0f, std::memory_order_relaxed);
        bus.peakR.store(0.0f, std::memory_order_relaxed);
    }
}

bool DspEngine::getBusLevel(int busIndex, float* out4) const {
    if (!isActiveBus(busIndex) || !out4) return false;
    const Bus& bus = buses_[busIndex];
    out4[0] = bus.shownDecayL.load(std::memory_order_relaxed);
    out4[1] = bus.shownDecayR.load(std::memory_order_relaxed);
    out4[2] = bus.shownHoldL.load(std::memory_order_relaxed);
    out4[3] = bus.shownHoldR.load(std::memory_order_relaxed);
    return true;
}

// ── Channel routing ─────────────────────────────────────────────────────

bool DspEngine::pristineLocked(int busIndex) const {
    const Bus& bus = buses_[busIndex];
    for (int d = 0; d < TOTAL_BUSES; d++) {
        if (bus.send[d].load(std::memory_order_relaxed) != (d == MASTER_BUS ? 1.0f : 0.0f)) return false;
    }
    const int active = activeBusCount();
    for (int b = 0; b < active; b++) {
        if (b != MASTER_BUS && buses_[b].send[busIndex].load(std::memory_order_relaxed) > 0.0f) return false;
    }
    return bus.plugins.empty() &&
           bus.gainDb.load(std::memory_order_relaxed) == 0.0f &&
           bus.pan.load(std::memory_order_relaxed) == 0.0f &&
           !bus.muted.load(std::memory_order_relaxed) &&
           !bus.soloed.load(std::memory_order_relaxed) &&
           !bus.inputEnabled.load(std::memory_order_relaxed);
}

bool DspEngine::busPristine(int busIndex) {
    std::lock_guard<std::mutex> lock(chainMutex_);
    return isMixBus(busIndex) && pristineLocked(busIndex);
}

int DspEngine::savedMixBusCountLocked() const {
    int n = mixBusCount_.load(std::memory_order_relaxed);
    const int grownFrom = autoGrownFrom_.load(std::memory_order_relaxed);
    if (grownFrom < 0) return n;
    const int floor = std::max(MIN_MIX_BUSES, grownFrom);
    while (n > floor && pristineLocked(busIndexForNumber(n))) n--;
    return n;
}

void DspEngine::setRouting(int routedBus, int routedCount) {
    if (routedBus < 0) routedCount = 0;
    routedCount = std::max(0, std::min(MAX_MIX_BUSES, routedCount));
    routedCount_.store(routedCount, std::memory_order_relaxed);
    routedBus_.store(routedBus, std::memory_order_relaxed);

    const int count = mixBusCount();
    if (routedCount > count) {
        // Grow to one bus per channel group, remembering where from.
        if (autoGrownFrom_.load(std::memory_order_relaxed) < 0) {
            autoGrownFrom_.store(count, std::memory_order_relaxed);
        }
        while (mixBusCount() < routedCount && addBus() >= 0) {}
        return;
    }
    const int grownFrom = autoGrownFrom_.load(std::memory_order_relaxed);
    if (grownFrom < 0) return;
    // Narrower now: drop the grown buses nobody touched, from the top, down
    // to what the routing still needs. A touched one stops it — it is the
    // user's now, and so is everything below it.
    const int floor = std::max({MIN_MIX_BUSES, grownFrom, routedCount});
    while (mixBusCount() > floor) {
        const int idx = busIndexForNumber(mixBusCount());
        if (!busPristine(idx) || !removeBus(idx)) break;
    }
    if (routedCount <= grownFrom) autoGrownFrom_.store(-1, std::memory_order_relaxed);
}

bool DspEngine::getAndResetClipped() {
    return clipped_.exchange(false, std::memory_order_relaxed);
}

void DspEngine::getPluginMeters(int busIndex, float* out, int maxFloats) {
    if (!isActiveBus(busIndex) || !out) return;
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
    if (!isActiveBus(busIndex) || !out || maxSamples <= 0) return 0;
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
    const int activeBuses = activeBusCount();
    for (int i = 0; i < activeBuses; i++) {
        if (i == MASTER_BUS) continue;
        if (buses_[i].soloed.load(std::memory_order_relaxed)) return true;
    }
    return false;
}

void DspEngine::recalcBusGains(float gainDb, float pan, float& targetL, float& targetR) {
    float linear = (gainDb <= -100.0f) ? 0.0f
        : std::pow(10.0f, gainDb / 20.0f);

    // Balance, not pan: every bus and the master carry stereo, so centre is
    // unity and turning one way only turns the other side down. The
    // equal-power pan law this was put centre at -3 dB on each side, and a
    // signal crosses a bus and then the master — so the mixer ran everything
    // 6 dB below the source, and switching the DSP on made the music
    // quieter. The same curve scaled by sqrt(2) and capped at unity: a side
    // stays at 0 dB until the pan passes centre towards the other, then
    // falls along the equal-power curve to silence at the far end.
    float panNorm = (pan + 1.0f) * 0.5f;  // 0..1
    targetL = linear * std::min(1.0f, 1.41421356f * std::cos(panNorm * 1.5707963f));  // pi/2
    targetR = linear * std::min(1.0f, 1.41421356f * std::sin(panNorm * 1.5707963f));
}

// ── Plugin state reset ──────────────────────────────────────────────────

void DspEngine::resetPluginState() {
    std::lock_guard<std::mutex> lock(chainMutex_);
    for (int b = 0; b < activeBusCount(); b++) {
        Bus& bus = buses_[b];
        for (auto& plugin : bus.plugins) {
            if (plugin) plugin->resetAll();
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
        bus.publishMeters();
    }
    LOGD("Plugin state reset");
}

// ── State serialization (simple JSON) ───────────────────────────────────

std::string DspEngine::getStateJson(bool full) const {
    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(chainMutex_));
    // Mix buses 1..n are indices 0..n with the master among them (n >= 4).
    const int entries = (full ? mixBusCount() : savedMixBusCountLocked()) + 1;
    std::ostringstream ss;
    ss << "{\"buses\":[";
    for (int b = 0; b < entries; b++) {
        const Bus& bus = buses_[b];
        if (b > 0) ss << ",";
        ss << "{\"gain\":" << bus.gainDb.load(std::memory_order_relaxed)
           << ",\"pan\":" << bus.pan.load(std::memory_order_relaxed)
           << ",\"muted\":" << (bus.muted.load(std::memory_order_relaxed) ? "true" : "false")
           << ",\"soloed\":" << (bus.soloed.load(std::memory_order_relaxed) ? "true" : "false")
           << ",\"inputEnabled\":" << (bus.inputEnabled.load(std::memory_order_relaxed) ? "true" : "false");
        // Routes, as [dst, level, ...] by engine index, only when they differ
        // from the default (the master alone): a mix that routes nothing
        // saves exactly as it did before routing existed.
        if (b != MASTER_BUS) {
            bool isDefault = true;
            for (int d = 0; d < TOTAL_BUSES && isDefault; d++) {
                isDefault = bus.send[d].load(std::memory_order_relaxed) == (d == MASTER_BUS ? 1.0f : 0.0f);
            }
            if (!isDefault) {
                ss << ",\"sends\":[";
                bool firstSend = true;
                for (int d = 0; d < TOTAL_BUSES; d++) {
                    const float lv = bus.send[d].load(std::memory_order_relaxed);
                    if (lv <= 0.0f) continue;
                    if (!firstSend) ss << ",";
                    firstSend = false;
                    ss << d << "," << lv;
                }
                ss << "]";
            }
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
    // Swapped into the buses below: reserved like theirs, so a later insert
    // under the lock never reallocates.
    for (auto& chain : staged) chain.reserve(MAX_PLUGINS_PER_BUS);

    // Bus parameters land here first for the same reason — applied under the
    // lock alongside the chain so a preset never lands half-applied.
    struct StagedBus {
        float gainDb = 0.0f;
        float pan = 0.0f;
        bool muted = false;
        bool soloed = false;
        bool inputEnabled = false;
        // Routes as saved; absent (every save before routing) = master alone.
        bool hasSends = false;
        int sendCount = 0;
        int sendDst[TOTAL_BUSES] = {};
        float sendLevel[TOTAL_BUSES] = {};
    };
    StagedBus stagedBus[TOTAL_BUSES];
    for (int b = 0; b < TOTAL_BUSES; b++) {
        stagedBus[b].inputEnabled = (b == 0);
    }

    // Minimal JSON parsing
    size_t pos = 0;
    auto findNext = [&](const std::string& key) -> size_t {
        size_t found = json.find(key, pos);
        return found;
    };

    // strtof, not stof: the library is built without exceptions, where a
    // throw is an abort — and stof throws on anything that is not a number,
    // which a truncated or hand-edited save is. Unparseable reads as 0 and
    // non-finite stays non-finite for the callers to reject.
    auto readFloat = [&](size_t start) -> float {
        if (start >= json.size()) return 0.0f;
        const char* begin = json.c_str() + start;
        char* end = nullptr;
        const float v = std::strtof(begin, &end);
        return end == begin ? 0.0f : v;
    };
    // An integer field (a type, a factor): huge or NaN reads as -1 rather than
    // reaching a float-to-int cast, which is undefined for them.
    auto readInt = [&](size_t start) -> int {
        const float v = readFloat(start);
        if (!dspIsFinite(v) || v < -1e6f || v > 1e6f) return -1;
        return static_cast<int>(v);
    };

    auto readBool = [&](size_t start) -> bool {
        return json.substr(start, 4) == "true";
    };

    int busIdx = 0;
    pos = 0;

    while (pos < json.size() && busIdx < TOTAL_BUSES) {
        size_t busStart = json.find("{\"gain\":", pos);
        if (busStart == std::string::npos) break;

        pos = busStart + 8;
        // Same clamping + NaN filtering setBusGain/setBusPan apply to live
        // edits, inlined here because the staged value is not stored yet.
        stagedBus[busIdx].gainDb =
            std::max(-60.0f, std::min(12.0f, finiteOr(readFloat(pos), 0.0f)));

        size_t panPos = json.find("\"pan\":", pos);
        if (panPos != std::string::npos) {
            stagedBus[busIdx].pan =
                std::max(-1.0f, std::min(1.0f, finiteOr(readFloat(panPos + 6), 0.0f)));
            pos = panPos + 6;
        }

        size_t mutedPos = json.find("\"muted\":", pos);
        if (mutedPos != std::string::npos) {
            stagedBus[busIdx].muted = readBool(mutedPos + 8);
            pos = mutedPos + 8;
        }

        size_t soloedPos = json.find("\"soloed\":", pos);
        if (soloedPos != std::string::npos) {
            stagedBus[busIdx].soloed = readBool(soloedPos + 9);
            pos = soloedPos + 9;
        }

        size_t inputEnabledPos = json.find("\"inputEnabled\":", pos);
        if (inputEnabledPos != std::string::npos && inputEnabledPos < json.find("\"plugins\":", pos)) {
            stagedBus[busIdx].inputEnabled = readBool(inputEnabledPos + 15);
            pos = inputEnabledPos + 15;
        }

        size_t sendsPos = json.find("\"sends\":[", pos);
        if (sendsPos != std::string::npos && sendsPos < json.find("\"plugins\":", pos)) {
            StagedBus& sb = stagedBus[busIdx];
            sb.hasSends = true;
            pos = sendsPos + 9;
            int half = 0;
            int dst = -1;
            while (pos < json.size() && json[pos] != ']' && sb.sendCount < TOTAL_BUSES) {
                if (json[pos] == ',' || json[pos] == ' ') { pos++; continue; }
                if (half == 0) {
                    dst = readInt(pos);
                    half = 1;
                } else {
                    const float lv = readFloat(pos);
                    if (dst >= 0 && dst < TOTAL_BUSES && dspIsFinite(lv) && lv > 0.0f) {
                        sb.sendDst[sb.sendCount] = dst;
                        sb.sendLevel[sb.sendCount] = std::min(1.0f, lv);
                        sb.sendCount++;
                    }
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
                int plugType = readInt(pos);

                // A bus holds MAX_PLUGINS_PER_BUS; the meters and the UI are
                // sized for that many, so a save with more loses the extras.
                const bool room = static_cast<int>(staged[busIdx].size()) < MAX_PLUGINS_PER_BUS;
                auto* proc = (room && plugType >= 0 && plugType < static_cast<int>(SnapinType::COUNT))
                    ? createSnapin(static_cast<SnapinType>(plugType)) : nullptr;
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
                        proc->setOversampling(readInt(osPos + 5));
                        pos = osPos + 5;
                    }

                    size_t paramsPos = json.find("\"params\":[", pos);
                    if (paramsPos != std::string::npos) {
                        pos = paramsPos + 10;
                        int paramIdx = 0;
                        while (pos < json.size() && json[pos] != ']') {
                            if (json[pos] == ',' || json[pos] == ' ') { pos++; continue; }
                            float val = readFloat(pos);
                            // NaN, infinity and indices past the end stop
                            // at applyParameter.
                            proc->applyParameter(paramIdx, val);
                            paramIdx++;
                            size_t next = json.find_first_of(",]", pos);
                            if (next == std::string::npos) break;
                            pos = next;
                            if (json[pos] == ',') pos++;
                        }
                    }

                    staged[busIdx].push_back(std::unique_ptr<SnapinProcessor>(proc));
                }

                // Advance past this plugin object
                size_t endBrace = json.find("}", pos);
                if (endBrace == std::string::npos) break;
                pos = endBrace + 1;
            }
        }

        busIdx++;
    }

    // Everything above ran with the lock NOT held. Publish it in one short,
    // allocation-free critical section: swap each finished chain in and store
    // the bus parameters. vector::swap only exchanges the internal pointers,
    // so nothing here can allocate or free.
    {
        std::lock_guard<std::mutex> lock(chainMutex_);
        // A save with more than five entries carries buses 5 and up; an
        // older one (exactly five) is the four-bus mixer it always was.
        const int saved = std::max(MIN_MIX_BUSES, std::min(MAX_MIX_BUSES, busIdx - 1));
        // While routed, the channel groups keep their buses: a saved mix with
        // fewer is grown to fit (and those buses are the grown ones again).
        const int routed = routedCount_.load(std::memory_order_relaxed);
        mixBusCount_.store(std::max(saved, routed), std::memory_order_relaxed);
        autoGrownFrom_.store(saved < routed ? saved : -1, std::memory_order_relaxed);
        for (int b = 0; b < TOTAL_BUSES; b++) {
            buses_[b].plugins.swap(staged[b]);
            buses_[b].gainDb.store(stagedBus[b].gainDb, std::memory_order_relaxed);
            buses_[b].pan.store(stagedBus[b].pan, std::memory_order_relaxed);
            buses_[b].muted.store(stagedBus[b].muted, std::memory_order_relaxed);
            buses_[b].soloed.store(stagedBus[b].soloed, std::memory_order_relaxed);
            buses_[b].inputEnabled.store(stagedBus[b].inputEnabled, std::memory_order_relaxed);
            defaultSendsLocked(buses_[b]);
        }
        // Routes last, once every bus is in place: to an active bus only, one
        // at a time and skipping any that would close a loop, so even a
        // hand-edited file can't make the graph cyclic.
        const int active = activeBusCount();
        for (int b = 0; b < active; b++) {
            if (b == MASTER_BUS || !stagedBus[b].hasSends) continue;
            buses_[b].send[MASTER_BUS].store(0.0f, std::memory_order_relaxed);
            buses_[b].sendApplied[MASTER_BUS] = 0.0f;
        }
        for (int b = 0; b < active; b++) {
            if (b == MASTER_BUS) continue;
            const StagedBus& sb = stagedBus[b];
            for (int i = 0; i < sb.sendCount; i++) {
                const int d = sb.sendDst[i];
                if (d == b || !isActiveBus(d)) continue;
                if (d != MASTER_BUS && reachesLocked(d, b)) continue;
                buses_[b].send[d].store(sb.sendLevel[i], std::memory_order_relaxed);
                buses_[b].sendApplied[d] = sb.sendLevel[i];
            }
        }
    }

    // `staged` now holds the processors the preset displaced. They are freed
    // here, at scope exit, with the lock already released — so the audio thread
    // never waits on a destructor.
    LOGD("Loaded state JSON, %d buses parsed", busIdx);
}
