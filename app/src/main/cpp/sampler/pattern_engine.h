// SPDX-License-Identifier: GPL-3.0-or-later
//
// The real-time half of the pattern looper: a sample-accurate step clock, a
// pattern bank, and a fixed voice pool.

#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

#include "pattern_clock.h"
#include "sample_bank.h"
#include "sampler_types.h"
#include "voice.h"

namespace tryptify {

/**
 * Drives patterns into sound.
 *
 * Threading contract, which is the whole design:
 *
 * * **Audio thread** calls [render] and nothing else. It drains the command
 *   queue, advances the clock, fires steps and mixes voices. It never
 *   allocates, never locks, and never touches Kotlin.
 * * **Loader thread** (Kotlin, off the main thread) calls [uploadPattern],
 *   the [SampleBank] methods and [collectGarbage]. These take a mutex the
 *   audio thread does not.
 * * **UI thread** posts [Command]s and reads the published atomics. Both are
 *   wait-free.
 *
 * A pattern is published by atomic pointer swap out of a preallocated pool,
 * so re-reading a pattern from Room while the loop is running can never tear
 * against a step that is firing. Fine-grained edits — a trig toggled, a knob
 * moved — go the other way, through the command queue, and are applied by the
 * audio thread itself.
 */
class PatternEngine {
public:
    explicit PatternEngine(int sampleRate);
    ~PatternEngine();

    PatternEngine(const PatternEngine&) = delete;
    PatternEngine& operator=(const PatternEngine&) = delete;

    /**
     * Rate change without losing the loop. Called when the output path
     * reconfigures (44.1k → 48k on a track change, or the standalone pump
     * handing over to the ExoPlayer chain).
     */
    void prepare(int sampleRate);

    /**
     * Mixes [frames] of pattern audio into [outL]/[outR].
     *
     * When [clearFirst] the buffers are zeroed first (the standalone output
     * pump owns its buffer); otherwise the pattern is summed on top of what
     * is already there, which is how it joins the music inside the Media3
     * chain and therefore inherits the whole Tryptify DSP path.
     */
    void render(float* outL, float* outR, int frames, bool clearFirst);

    /** Wait-free; safe from any non-audio thread. */
    bool post(const Command& cmd) { return commands_.push(cmd); }

    /**
     * Publishes a whole pattern. Returns false when every spare buffer is
     * still in flight, in which case the caller should retry — that only
     * happens if uploads outrun the audio callback, and is not a failure the
     * user can reach from the UI.
     */
    bool uploadPattern(int index, const PatternState& pattern);

    /** Reads a pattern back — for tests and for the "what does the engine think" path. */
    bool readPattern(int index, PatternState& out) const;

    SampleBank& samples() { return samples_; }

    /** Frees retired sample buffers no voice can still be reading. */
    int collectGarbage();

    // ── Published state, polled by the UI at frame rate ──────────────────
    int currentStep() const { return currentStep_.load(std::memory_order_relaxed); }
    int currentPattern() const { return currentPattern_.load(std::memory_order_relaxed); }
    int queuedPattern() const { return queuedPattern_.load(std::memory_order_relaxed); }
    bool playing() const { return playing_.load(std::memory_order_relaxed); }
    bool recording() const { return recording_.load(std::memory_order_relaxed); }
    int countInRemaining() const { return countInRemaining_.load(std::memory_order_relaxed); }
    int activeVoices() const { return activeVoices_.load(std::memory_order_relaxed); }
    int64_t framePosition() const { return framePos_.load(std::memory_order_relaxed); }
    float bpm() const { return bpm_.load(std::memory_order_relaxed); }

    /**
     * Monotonic per-channel trigger counter. The UI lights a channel when the
     * count moves, which is cheaper and more robust than trying to observe an
     * instantaneous "is firing" flag across a frame boundary.
     */
    uint32_t channelTriggers(int channel) const {
        if (channel < 0 || channel >= kMaxChannels) return 0;
        return triggerCount_[channel].load(std::memory_order_relaxed);
    }

    /** Steps recorded live since the last read, so the UI can refresh from Room. */
    uint32_t recordEditCount() const { return recordEdits_.load(std::memory_order_relaxed); }

private:
    // ── audio thread ────────────────────────────────────────────────────
    void renderChunk(float* scratchL, float* scratchR, int frames);
    void drainCommands();
    void applyCommand(const Command& cmd);
    void fireStep();
    void triggerChannel(const ChannelState& channel, int channelIndex, float velocity,
                        const StepData* step);
    void renderVoices(float* outL, float* outR, int offset, int frames);
    void renderMetronome(float* outL, float* outR, int offset, int frames);
    Voice* allocateVoice(int channelIndex);
    void chokeChannel(int channelIndex);
    void recordLiveHit(int channelIndex, float velocity);
    void applyQueuedPattern();
    void resetTransport();

    /** Must be called with [mutex_] held. */
    void reclaimPatternsLocked();

    PatternState* pattern(int index) const {
        if (index < 0 || index >= kMaxPatterns) return nullptr;
        return slots_[index].load(std::memory_order_acquire);
    }
    PatternState* activePattern() const { return pattern(currentPattern_.load(std::memory_order_relaxed)); }

    uint32_t nextRandom();

    // ── storage ─────────────────────────────────────────────────────────
    std::vector<PatternState> pool_;              // kMaxPatterns + kPatternSpares
    std::atomic<PatternState*> slots_[kMaxPatterns];
    std::vector<PatternState*> free_;             // loader thread, under mutex_
    struct RetiredPattern { PatternState* buffer; uint64_t block; };
    std::vector<RetiredPattern> retiredPatterns_;
    mutable std::mutex mutex_;

    SampleBank samples_;
    Voice voices_[kMaxVoices];
    CommandQueue<1024> commands_;

    // Voice mix scratch. The pattern is summed here first so the master fader
    // can be applied to it alone — when the engine renders into the middle of
    // the Media3 chain the caller's buffer already holds the music, and a
    // gain over the whole thing would ride the track as well.
    std::vector<float> scratchL_, scratchR_;
    std::atomic<int> renderDepth_{0};

    // ── clock ───────────────────────────────────────────────────────────
    double sampleRate_ = 48000.0;
    std::atomic<float> bpm_{124.0f};
    std::atomic<float> swing_{0.0f};
    std::atomic<float> masterGain_{1.0f};
    std::atomic<bool> playing_{false};
    std::atomic<bool> recording_{false};
    std::atomic<bool> metronome_{false};
    std::atomic<int> countInBars_{0};
    std::atomic<int> countInRemaining_{0};
    std::atomic<int> recordQuantum_{1};

    int64_t globalStep_ = -1;
    double framesToNext_ = 0.0;       // countdown to the next step boundary
    int stepInPattern_ = -1;
    std::atomic<int> currentStep_{0};
    std::atomic<int> currentPattern_{0};
    std::atomic<int> queuedPattern_{-1};
    bool queuedImmediate_ = false;
    std::atomic<int64_t> framePos_{0};
    std::atomic<int> activeVoices_{0};
    std::atomic<uint32_t> triggerCount_[kMaxChannels];
    std::atomic<uint32_t> recordEdits_{0};
    std::atomic<uint64_t> blockCounter_{0};

    // ── metronome voice ─────────────────────────────────────────────────
    float clickEnv_ = 0.0f;
    float clickPhase_ = 0.0f;
    float clickFreq_ = 1000.0f;

    uint32_t rngState_ = 0x9E3779B9u;
};

}  // namespace tryptify
