#pragma once
#include "snapin_processor.h"
#include <vector>
#include <memory>
#include <mutex>
#include <cmath>
#include <string>
#include <atomic>

static constexpr int NUM_MIX_BUSES = 4;
static constexpr int MASTER_BUS = 4;
static constexpr int TOTAL_BUSES = 5;  // 4 mix + 1 master
static constexpr int MAX_PLUGINS_PER_BUS = 16;

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
    void setBusInputEnabled(int busIndex, bool enabled);
    void setMixBypassed(bool bypassed);

    // Metering — returns levels in dB
    // Output: [bus0_peakL, bus0_peakR, bus0_holdL, bus0_holdR, ..., master_holdR]
    // Total: TOTAL_BUSES * 4 floats
    void getBusLevels(float* outLevels, int maxFloats);

    // Clipping detection — returns true if master output clipped since last check
    bool getAndResetClipped();

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
