#pragma once
#include "snapin_processor.h"
#include <vector>
#include <memory>
#include <mutex>
#include <cmath>
#include <string>
#include <atomic>

// Mix buses are added and removed at run time, between 4 and 16. The master
// stays at index 4 whatever the count — every saved mix and preset has it
// there — so buses 1–4 are indices 0–3 and bus 5 onwards are 5, 6, … The
// active buses are always the contiguous indices 0..mixBusCount().
static constexpr int MIN_MIX_BUSES = 4;
static constexpr int MAX_MIX_BUSES = 16;
static constexpr int MASTER_BUS = 4;
static constexpr int TOTAL_BUSES = MAX_MIX_BUSES + 1;  // capacity: 16 mix + master
static constexpr int MAX_PLUGINS_PER_BUS = 16;

// Per-bus post-fader waveform tap ring size. Power of two (index masking);
// ~43 ms at 48 kHz — enough history for the FX-chain scope displays.
static constexpr int WAVE_TAP_SIZE = 2048;

struct Bus {
    std::vector<std::unique_ptr<SnapinProcessor>> plugins;

    // Atomic parameters — written by UI thread, read by audio thread
    std::atomic<float> gainDb{0.0f};
    std::atomic<float> pan{0.0f};       // -1 left, 0 center, +1 right
    std::atomic<bool> muted{false};
    std::atomic<bool> soloed{false};
    std::atomic<bool> inputEnabled{false};

    // Smoothed gain values (audio thread only)
    float smoothGainL = 1.0f;
    float smoothGainR = 1.0f;
    float targetGainL = 1.0f;
    float targetGainR = 1.0f;

    // Peak meter levels (written by audio thread, read by UI thread)
    std::atomic<float> peakL{0.0f};
    std::atomic<float> peakR{0.0f};

    // Per-slot audio tap: linear block peak into / out of each plugin.
    // Written by the audio thread each block, read by the UI thread for the
    // FX-chain visualizations. Zeroed for bypassed/empty slots.
    std::atomic<float> slotInPeak[MAX_PLUGINS_PER_BUS] = {};
    std::atomic<float> slotOutPeak[MAX_PLUGINS_PER_BUS] = {};

    // Post-fader mono waveform tap. Single writer (audio thread); the UI
    // reads a snapshot without locking — a torn read across the write head
    // is imperceptible in a scope display.
    float waveTap[WAVE_TAP_SIZE] = {};
    std::atomic<int> waveTapPos{0};

    // Meter ballistics (audio thread only)
    float decayL = 0.0f;
    float decayR = 0.0f;
    float holdL = 0.0f;
    float holdR = 0.0f;
    int holdCounterL = 0;
    int holdCounterR = 0;
};

class DspEngine {
public:
    DspEngine(int sampleRate, int maxBlockSize);
    ~DspEngine();

    // Swap sample rate / max block without tearing down the bus graph, plugin
    // chains, or atomic parameter state. Keeps track transitions from producing
    // the 5–10 ms silence window we'd get from a full destroy-recreate on every
    // 44.1k → 48k shift. Safe to call from any thread — internally holds the
    // same chainMutex_ the audio thread does during process().
    void reconfigure(int sampleRate, int maxBlockSize);

    // Audio processing — called from audio thread
    void process(float* left, float* right, int numFrames);

    // ── Lane stepping, for MultiLaneEngine ──────────────────────────────
    // process() is these three in a row. A host running several engines as
    // the lanes of one multichannel stream steps them together instead, so
    // that at a linked master slot every lane can be handed the same
    // detector key. All of them expect chainMutex() to be held.
    std::mutex& chainMutex() { return chainMutex_; }
    void processMixBusesLocked(const float* left, const float* right, int numFrames);
    int masterSlotCountLocked() const;
    // Whether master slot [slot] would run and detects level (so linking it
    // across lanes means something).
    bool masterSlotLinkableLocked(int slot) const;
    // detectorKey, when non-null, replaces the slot's own level detection:
    // one non-negative sample per frame.
    void processMasterSlotLocked(int slot, int numFrames, const float* detectorKey);
    void finishBlockLocked(float* left, float* right, int numFrames);
    // The master bus signal between slots: what the next slot will receive.
    const float* masterSumL() const { return sumL_.data(); }
    const float* masterSumR() const { return sumR_.data(); }

    // A lane carrying one channel (centre, LFE) as dual-mono. Effects that
    // only reshape a stereo image are skipped, and pans sit at centre.
    void setMonoLane(bool mono) { monoLane_ = mono; }
    bool isMixBypassed() const { return mixBypassed_.load(std::memory_order_relaxed); }

    // Bus control — simple writes, safe for cross-thread
    void setBusGain(int busIndex, float gainDb);
    void setBusPan(int busIndex, float pan);
    void setBusMute(int busIndex, bool muted);
    void setBusSolo(int busIndex, bool soloed);

    // Plugin chain — uses mutex since changes are infrequent
    int addPlugin(int busIndex, int slotIndex, int pluginType);
    void removePlugin(int busIndex, int slotIndex);
    void movePlugin(int busIndex, int fromSlot, int toSlot);
    void setParameter(int busIndex, int slotIndex, int paramIndex, float value);
    void setPluginBypassed(int busIndex, int slotIndex, bool bypassed);
    void setPluginDryWet(int busIndex, int slotIndex, float dryWet);
    // Per-plugin oversampling factor: 1 (off), 2, or 4. Re-prepares the
    // plugin at baseRate × factor (resets its state, like a rate change).
    void setPluginOversampling(int busIndex, int slotIndex, int factor);
    void setBusInputEnabled(int busIndex, bool enabled);
    void setMixBypassed(bool bypassed);

    // Adds a mix bus after the last one: unity, centre, input off, no
    // plugins. Returns its index, or -1 at MAX_MIX_BUSES.
    int addBus();
    // Removes mix bus [busIndex] and moves every bus above it down one
    // index. Buses 1–4 (indices 0–3) and the master cannot be removed.
    bool removeBus(int busIndex);
    int mixBusCount() const { return mixBusCount_.load(std::memory_order_relaxed); }

    // Metering — returns levels in dB
    // Output: [bus0_peakL, bus0_peakR, bus0_holdL, bus0_holdR, ..., master_holdR]
    // Total: TOTAL_BUSES * 4 floats
    void getBusLevels(float* outLevels, int maxFloats);

    // Clipping detection — returns true if master output clipped since last check
    bool getAndResetClipped();

    // Per-plugin tap meters for one bus, in dB (floor -60):
    // [slot0_inDb, slot0_outDb, slot1_inDb, ...] — MAX_PLUGINS_PER_BUS * 2 floats.
    void getPluginMeters(int busIndex, float* out, int maxFloats);

    // Copy the most recent post-fader mono waveform for a bus, oldest sample
    // first. Returns the number of samples written (<= maxSamples).
    int getBusWaveform(int busIndex, float* out, int maxSamples);

    // Reset all plugin internal state (delay lines, filters, etc.) without destroying
    void resetPluginState();

    // State serialization
    std::string getStateJson() const;
    void loadStateJson(const std::string& json);

private:
    Bus buses_[TOTAL_BUSES];
    int sampleRate_;
    int maxBlockSize_;
    std::mutex chainMutex_;

    // Scratch buffers
    std::vector<float> sumL_, sumR_;
    std::vector<float> busL_, busR_;
    std::vector<float> dryBufL_, dryBufR_;  // Pre-allocated for dry/wet blending

    bool anySoloed() const;
    // Active buses are 0..mixBusCount_ (master at 4 among them).
    int activeBusCount() const { return mixBusCount_.load(std::memory_order_relaxed) + 1; }
    bool isActiveBus(int i) const { return i >= 0 && i < activeBusCount(); }
    bool isMixBus(int i) const { return isActiveBus(i) && i != MASTER_BUS; }
    static void resetBusLocked(Bus& bus);
    static void moveBusLocked(Bus& dst, Bus& src);
    std::atomic<int> mixBusCount_{MIN_MIX_BUSES};
    bool skippedOnThisLane(const SnapinProcessor& plugin) const;
    bool monoLane_ = false;
    void recalcBusGains(float gainDb, float pan, float& targetL, float& targetR);

    // Smoothing coefficient for gain changes
    float gainSmoothCoeff_ = 0.005f;

    // Meter ballistics
    float meterDecayPerSample_ = 0.0f;  // Computed from sample rate
    int meterHoldSamples_ = 0;          // 1.5 seconds in samples

    // When true, mix bus plugins (0-3) are bypassed but master bus still processes.
    // Allows AutoEQ on master to stay active when user toggles mixer DSP off.
    std::atomic<bool> mixBypassed_{false};

    // Clipping flag
    std::atomic<bool> clipped_{false};
};
