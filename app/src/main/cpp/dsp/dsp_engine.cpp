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
    busL_.resize(maxBlockSize, 0.0f);
    busR_.resize(maxBlockSize, 0.0f);
    dryBufL_.resize(maxBlockSize, 0.0f);
    dryBufR_.resize(maxBlockSize, 0.0f);

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
        busL_.resize(maxBlockSize_, 0.0f);
        busR_.resize(maxBlockSize_, 0.0f);
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
                if (plugin) plugin->prepare(static_cast<double>(sampleRate_), maxBlockSize_);
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

    // Process mix buses 0-3
    for (int b = 0; b < NUM_MIX_BUSES; b++) {
        Bus& bus = buses_[b];

        // Load atomic parameters once into locals
        bool busInputEnabled = bus.inputEnabled.load(std::memory_order_relaxed);
        bool busMuted = bus.muted.load(std::memory_order_relaxed);
        bool busSoloed = bus.soloed.load(std::memory_order_relaxed);
        float busGainDb = mixBypass ? 0.0f : bus.gainDb.load(std::memory_order_relaxed);
        float busPan = mixBypass ? 0.0f : bus.pan.load(std::memory_order_relaxed);

        // Skip if no input, muted, or (solo mode active and this bus not soloed)
        if (!busInputEnabled || busMuted || (hasSolo && !busSoloed)) {
            bus.peakL.store(0.0f, std::memory_order_relaxed);
            bus.peakR.store(0.0f, std::memory_order_relaxed);
            continue;
        }

        // Copy input to bus scratch buffer
        std::copy(left, left + numFrames, busL_.data());
        std::copy(right, right + numFrames, busR_.data());

        // Run plugin chain with dry/wet blending (skip when mixer DSP is bypassed)
        for (auto& plugin : bus.plugins) {
            if (mixBypass) break;
            if (plugin && !plugin->isBypassed()) {
                float dw = plugin->getDryWet();
                if (dw >= 0.999f) {
                    // Fully wet — no copy needed
                    plugin->process(busL_.data(), busR_.data(), numFrames);
                } else if (dw <= 0.001f) {
                    // Fully dry — skip processing
                } else {
                    // Blend: save dry into pre-allocated buffers, process, mix
                    std::copy(busL_.begin(), busL_.begin() + numFrames, dryBufL_.begin());
                    std::copy(busR_.begin(), busR_.begin() + numFrames, dryBufR_.begin());
                    plugin->process(busL_.data(), busR_.data(), numFrames);
                    float wet = dw, dry = 1.0f - dw;
                    for (int i = 0; i < numFrames; i++) {
                        busL_[i] = dryBufL_[i] * dry + busL_[i] * wet;
                        busR_[i] = dryBufR_[i] * dry + busR_[i] * wet;
                    }
                }
            }
        }

        // Recalculate target gains from dB + pan
        recalcBusGains(busGainDb, busPan, bus.targetGainL, bus.targetGainR);

        // Apply bus gain + pan with smoothing, sum into master input
        float busPeakL = 0.0f, busPeakR = 0.0f;
        for (int i = 0; i < numFrames; i++) {
            bus.smoothGainL += gainSmoothCoeff_ * (bus.targetGainL - bus.smoothGainL);
            bus.smoothGainR += gainSmoothCoeff_ * (bus.targetGainR - bus.smoothGainR);
            float sL = busL_[i] * bus.smoothGainL;
            float sR = busR_[i] * bus.smoothGainR;
            sumL_[i] += sL;
            sumR_[i] += sR;
            float absL = std::fabs(sL);
            float absR = std::fabs(sR);
            if (absL > busPeakL) busPeakL = absL;
            if (absR > busPeakR) busPeakR = absR;
        }
        // Update peak meters (relaxed store — UI reads are non-critical)
        bus.peakL.store(busPeakL, std::memory_order_relaxed);
        bus.peakR.store(busPeakR, std::memory_order_relaxed);
    }

    // Run master bus chain with dry/wet blending
    Bus& master = buses_[MASTER_BUS];
    for (auto& plugin : master.plugins) {
        if (plugin && !plugin->isBypassed()) {
            float dw = plugin->getDryWet();
            if (dw >= 0.999f) {
                plugin->process(sumL_.data(), sumR_.data(), numFrames);
            } else if (dw <= 0.001f) {
                // Fully dry — skip
            } else {
                std::copy(sumL_.begin(), sumL_.begin() + numFrames, dryBufL_.begin());
                std::copy(sumR_.begin(), sumR_.begin() + numFrames, dryBufR_.begin());
                plugin->process(sumL_.data(), sumR_.data(), numFrames);
                float wet = dw, dry = 1.0f - dw;
                for (int i = 0; i < numFrames; i++) {
                    sumL_[i] = dryBufL_[i] * dry + sumL_[i] * wet;
                    sumR_[i] = dryBufR_[i] * dry + sumR_[i] * wet;
                }
            }
        }
    }

    // Apply master gain and write to output
    float masterGainDb = master.gainDb.load(std::memory_order_relaxed);
    float masterPan = master.pan.load(std::memory_order_relaxed);
    recalcBusGains(masterGainDb, masterPan, master.targetGainL, master.targetGainR);
    float masterPeakL = 0.0f, masterPeakR = 0.0f;
    bool clipped = false;
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
    }
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

    proc->prepare(static_cast<double>(sampleRate_), maxBlockSize_);

    std::lock_guard<std::mutex> lock(chainMutex_);

    int idx = std::min(slotIndex, static_cast<int>(bus.plugins.size()));
    bus.plugins.insert(bus.plugins.begin() + idx, std::unique_ptr<SnapinProcessor>(proc));

    LOGD("Added plugin type %d to bus %d slot %d", pluginType, busIndex, idx);
    return idx;
}

void DspEngine::removePlugin(int busIndex, int slotIndex) {
    if (busIndex < 0 || busIndex >= TOTAL_BUSES) return;
    Bus& bus = buses_[busIndex];
    if (slotIndex < 0 || slotIndex >= static_cast<int>(bus.plugins.size())) return;

    std::lock_guard<std::mutex> lock(chainMutex_);
    bus.plugins.erase(bus.plugins.begin() + slotIndex);
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
           << ",\"inputEnabled\":" << (bus.inputEnabled.load(std::memory_order_relaxed) ? "true" : "false")
           << ",\"plugins\":[";
        for (int p = 0; p < static_cast<int>(bus.plugins.size()); p++) {
            if (p > 0) ss << ",";
            auto& plug = bus.plugins[p];
            ss << "{\"type\":" << static_cast<int>(plug->getType())
               << ",\"bypassed\":" << (plug->isBypassed() ? "true" : "false")
               << ",\"dryWet\":" << plug->getDryWet()
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

    std::lock_guard<std::mutex> lock(chainMutex_);

    // Clear all buses
    for (int b = 0; b < TOTAL_BUSES; b++) {
        buses_[b].plugins.clear();
        buses_[b].gainDb.store(0.0f, std::memory_order_relaxed);
        buses_[b].pan.store(0.0f, std::memory_order_relaxed);
        buses_[b].muted.store(false, std::memory_order_relaxed);
        buses_[b].soloed.store(false, std::memory_order_relaxed);
        buses_[b].inputEnabled.store(b == 0, std::memory_order_relaxed);
    }

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

    while (pos < json.size() && busIdx < TOTAL_BUSES) {
        size_t busStart = json.find("{\"gain\":", pos);
        if (busStart == std::string::npos) break;

        pos = busStart + 8;
        // Route through setBusGain/setBusPan so clamping + NaN filtering match live edits.
        setBusGain(busIdx, readFloat(pos));

        size_t panPos = json.find("\"pan\":", pos);
        if (panPos != std::string::npos) {
            setBusPan(busIdx, readFloat(panPos + 6));
            pos = panPos + 6;
        }

        size_t mutedPos = json.find("\"muted\":", pos);
        if (mutedPos != std::string::npos) {
            buses_[busIdx].muted.store(readBool(mutedPos + 8), std::memory_order_relaxed);
            pos = mutedPos + 8;
        }

        size_t soloedPos = json.find("\"soloed\":", pos);
        if (soloedPos != std::string::npos) {
            buses_[busIdx].soloed.store(readBool(soloedPos + 9), std::memory_order_relaxed);
            pos = soloedPos + 9;
        }

        size_t inputEnabledPos = json.find("\"inputEnabled\":", pos);
        if (inputEnabledPos != std::string::npos && inputEnabledPos < json.find("\"plugins\":", pos)) {
            buses_[busIdx].inputEnabled.store(readBool(inputEnabledPos + 15), std::memory_order_relaxed);
            pos = inputEnabledPos + 15;
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
                    proc->prepare(static_cast<double>(sampleRate_), maxBlockSize_);

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

                    buses_[busIdx].plugins.push_back(std::unique_ptr<SnapinProcessor>(proc));
                }

                // Advance past this plugin object
                size_t endBrace = json.find("}", pos);
                if (endBrace == std::string::npos) break;
                pos = endBrace + 1;
            }
        }

        busIdx++;
    }

    LOGD("Loaded state JSON, %d buses parsed", busIdx);
}
