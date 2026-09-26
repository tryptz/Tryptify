#pragma once
#include "snapin_processor.h"
#include <vector>
#include <memory>
#include <mutex>
#include <cmath>
#include <string>
#include <atomic>

// Mix buses are added and removed at run time, between 4 and 48. The master
// stays at index 4 whatever the count — every saved mix and preset has it
// there — so buses 1–4 are indices 0–3 and bus 5 onwards are 5, 6, … The
// active buses are always the contiguous indices 0..mixBusCount().
static constexpr int MIN_MIX_BUSES = 4;
static constexpr int MAX_MIX_BUSES = 48;
static constexpr int MASTER_BUS = 4;
static constexpr int TOTAL_BUSES = MAX_MIX_BUSES + 1;  // capacity: 48 mix + master
static constexpr int MAX_PLUGINS_PER_BUS = 16;

// Per-bus post-fader waveform tap ring size. Power of two (index masking);
// ~43 ms at 48 kHz — enough history for the FX-chain scope displays.
static constexpr int WAVE_TAP_SIZE = 2048;

// The routing stage runs a block in pieces of at most this many frames, so
// each bus's input buffer is this long rather than the engine's max block
// (16384): 49 of them per lane would otherwise be megabytes. Effects stream,
// so where a block is cut changes nothing they compute.
static constexpr int ROUTE_BLOCK = 1024;

// Longest delay a bus can be held back by to line up with the slowest bus:
// sixteen effects at 4x at 22.05 kHz is ~1600 samples. Power of two.
static constexpr int PDC_SIZE = 2048;

struct Bus {
    std::vector<std::unique_ptr<SnapinProcessor>> plugins;

    // Atomic parameters — written by UI thread, read by audio thread
    std::atomic<float> gainDb{0.0f};
    std::atomic<float> pan{0.0f};       // -1 left, 0 center, +1 right
    std::atomic<bool> muted{false};
    std::atomic<bool> soloed{false};
    std::atomic<bool> inputEnabled{false};

    // Routing (mix buses only): post-fader send level to each other bus,
    // linear 0..1, indexed by destination engine index (MASTER_BUS = the
    // master); 0 = not routed. A new mix bus goes to the master alone. Written
    // under chainMutex_ (setSend, loadStateJson, add/removeBus), which keep
    // the graph free of loops; read by the audio thread.
    std::atomic<float> send[TOTAL_BUSES] = {};
    // Levels applied last block, ramped toward `send` (audio thread only).
    float sendApplied[TOTAL_BUSES] = {};
    // This bus's input for the current piece: the player's signal if it
    // takes it, plus every send into it. ROUTE_BLOCK frames, processed in place.
    std::vector<float> inL, inR;

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

    // The ballistics as the meters read them: published by publishMeters()
    // after each block. The fields above are the audio thread's working
    // copy; reading them from the UI's 60 Hz poll was a data race.
    std::atomic<float> shownDecayL{-60.0f};
    std::atomic<float> shownDecayR{-60.0f};
    std::atomic<float> shownHoldL{-60.0f};
    std::atomic<float> shownHoldR{-60.0f};

    void publishMeters() {
        shownDecayL.store(decayL, std::memory_order_relaxed);
        shownDecayR.store(decayR, std::memory_order_relaxed);
        shownHoldL.store(holdL, std::memory_order_relaxed);
        shownHoldR.store(holdR, std::memory_order_relaxed);
    }

    // Delay compensation (audio thread only): the history of this bus's
    // post-fader output, PDC_SIZE each, allocated once by the engine. Every
    // route out of the bus reads it at its own delay, so a signal meeting
    // another — at a bus or at the master — arrives in step with it.
    std::vector<float> pdcL, pdcR;
    int pdcPos = 0;
    // Set when the ring holds audio from before a pause in this bus's
    // processing (it was muted, off or moved), so it is cleared first.
    bool pdcStale = true;
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

    // Routes mix bus [src]'s post-fader output to bus [dst] (another mix bus
    // or MASTER_BUS) at linear [level], clamped 0..1; 0 removes the route.
    // Refused (false) for inactive buses and for a route that would close a
    // loop — [dst] already feeding [src], directly or through other buses.
    bool setSend(int src, int dst, float level);
    float getSend(int src, int dst) const;

    // Adds a mix bus after the last one: unity, centre, input off, no
    // plugins. Returns its index, or -1 at MAX_MIX_BUSES.
    int addBus();
    // Removes mix bus [busIndex] and moves every bus above it down one
    // index. Buses 1–4 (indices 0–3) and the master cannot be removed.
    bool removeBus(int busIndex);
    int mixBusCount() const { return mixBusCount_.load(std::memory_order_relaxed); }

    // ── Channel routing, for MultiLaneEngine ────────────────────────────
    // A stream wider than stereo is spread across the mixer: each lane (a
    // channel group — front pair, centre, LFE, …) feeds one bus of its own,
    // lane k to bus number k + 1. This engine is one lane: it feeds only
    // [routedBus] among the first [routedCount] mix buses, while buses past
    // those still take input by their own switch (a whole-bed send, say).
    // routedBus -1 turns routing off: every bus by its switch, as in stereo.
    //
    // The mixer grows to at least [routedCount] mix buses, and remembers it
    // did: when the routing narrows again the buses the growth added are
    // removed, if they are still untouched. While routed those buses cannot
    // be removed, a saved state keeps its own bus count through a load, and
    // getStateJson() leaves the untouched grown buses out — so a mix saved
    // during an Atmos track is still the mix the user built.
    void setRouting(int routedBus, int routedCount);
    int routedBus() const { return routedBus_.load(std::memory_order_relaxed); }
    int routedCount() const { return routedCount_.load(std::memory_order_relaxed); }
    // The mix-bus count routing grew this engine from, or -1. A lane cloned
    // from another takes the original's with inheritGrowth, so both hand the
    // same buses back when the stream narrows.
    int autoGrownFrom() const { return autoGrownFrom_.load(std::memory_order_relaxed); }
    void inheritGrowth(int grownFrom) { autoGrownFrom_.store(grownFrom, std::memory_order_relaxed); }
    // Bus number (1–16, as the strips show it) to engine index, and back.
    static int busIndexForNumber(int n) { return n <= MASTER_BUS ? n - 1 : n; }
    static int busNumberForIndex(int i) { return i < MASTER_BUS ? i + 1 : i; }

    // Metering — returns levels in dB
    // Output: [bus0_peakL, bus0_peakR, bus0_holdL, bus0_holdR, ..., master_holdR]
    // Total: TOTAL_BUSES * 4 floats
    void getBusLevels(float* outLevels, int maxFloats);
    // Drops every meter to silence. The meters only fall while audio is
    // processed, so after a pause they would otherwise hold their last level
    // and show it again the moment playback resumes.
    void resetMeters();
    // One bus's [peakL, peakR, holdL, holdR]; false if it is not active.
    bool getBusLevel(int busIndex, float* out4) const;

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
    // [full] includes the buses routing grew that are still untouched; the
    // default leaves them out, which is the form to save or export.
    std::string getStateJson(bool full = false) const;
    void loadStateJson(const std::string& json);

private:
    Bus buses_[TOTAL_BUSES];
    int sampleRate_;
    int maxBlockSize_;
    std::mutex chainMutex_;

    // Scratch buffers
    std::vector<float> sumL_, sumR_;
    std::vector<float> dryBufL_, dryBufR_;  // Pre-allocated for dry/wet blending

    bool anySoloed() const;
    // Active buses are 0..mixBusCount_ (master at 4 among them).
    int activeBusCount() const { return mixBusCount_.load(std::memory_order_relaxed) + 1; }
    bool isActiveBus(int i) const { return i >= 0 && i < activeBusCount(); }
    bool isMixBus(int i) const { return isActiveBus(i) && i != MASTER_BUS; }
    static void resetBusLocked(Bus& bus);
    static void moveBusLocked(Bus& dst, Bus& src);
    std::atomic<int> mixBusCount_{MIN_MIX_BUSES};
    std::atomic<int> routedBus_{-1};
    std::atomic<int> routedCount_{0};
    // The mix-bus count before routing grew it, or -1 when it has not.
    std::atomic<int> autoGrownFrom_{-1};
    // Untouched: default everything, routed to the master alone, and nothing
    // sending to it.
    bool pristineLocked(int busIndex) const;
    static void defaultSendsLocked(Bus& bus);
    // Whether [from] feeds [to] along routes with level > 0 (mix buses only).
    bool reachesLocked(int from, int to) const;
    // The player's signal, PDC_SIZE of history, for the input delays.
    std::vector<float> inRingL_, inRingR_;
    int inRingPos_ = 0;
    // Mix-bus order for this block, every bus after the buses sending to it.
    int orderLocked(int* order) const;
    // One piece (<= ROUTE_BLOCK frames at [offset]) of processMixBusesLocked,
    // using the graph worked out for the block in the members below.
    void processMixPieceLocked(const float* left, const float* right, int offset, int numFrames, bool first);
    // The block's graph (audio thread only): processing order, each bus's
    // input delay and output latency in base-rate samples, the master's input
    // delay, and which buses run (live), are heard (solo) and take the
    // player's signal on this lane.
    int order_[TOTAL_BUSES] = {};
    int orderCount_ = 0;
    int inDelay_[TOTAL_BUSES] = {};
    int outLat_[TOTAL_BUSES] = {};
    int masterInDelay_ = 0;
    bool live_[TOTAL_BUSES] = {};
    bool audible_[TOTAL_BUSES] = {};
    bool takesInput_[TOTAL_BUSES] = {};
    bool busPristine(int busIndex);
    // Per-block peaks across the pieces, stored once at the end.
    float blockPeakL_[TOTAL_BUSES] = {};
    float blockPeakR_[TOTAL_BUSES] = {};
    // How many mix buses a save carries: the grown, untouched tail left off.
    int savedMixBusCountLocked() const;
    bool skippedOnThisLane(const SnapinProcessor& plugin) const;
    // One slot of a chain, in place on [l]/[r]: processed and blended when
    // [run], otherwise delayed by the effect's latency so the chain's delay
    // is the same bypassed or not. A block the effect turns non-finite is
    // replaced by its dry signal and the effect reset.
    void runSlotLocked(SnapinProcessor& plugin, float* l, float* r, int numFrames, bool run);
    // Base-rate samples the chain on [bus] delays its signal by.
    static int chainLatencyLocked(const Bus& bus);
    // The pieces of process(), for one block no longer than maxBlockSize_.
    void processBlockLocked(float* left, float* right, int numFrames);
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
