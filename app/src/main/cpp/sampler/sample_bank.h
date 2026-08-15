// SPDX-License-Identifier: GPL-3.0-or-later
//
// Resident PCM for the sampler, and the rules that let it be swapped out from
// under a running voice without a lock.

#pragma once

#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#include "sampler_types.h"

namespace tryptify {

/**
 * One decoded sample, resident as deinterleaved float.
 *
 * Decoding happens in Kotlin (MediaExtractor / MediaCodec or the WAV reader)
 * on a background thread; the audio thread only ever reads what is already
 * here. Mono samples keep a null right channel and are read as centred, which
 * is both the common case for one-shots and half the memory.
 */
struct SampleData {
    std::vector<float> left;
    std::vector<float> right;   // empty for mono
    int32_t frames = 0;
    int32_t sampleRate = 48000;
    float gain = 1.0f;

    bool stereo() const { return !right.empty(); }
};

/**
 * Fixed-slot bank with deferred reclaim.
 *
 * The audio thread reads `slot.data` once per trigger and holds the raw
 * pointer for the life of the voice. Replacing or clearing a slot therefore
 * cannot free the old buffer immediately — instead it moves to [retired_],
 * and [collect] frees it only once the engine reports that every voice which
 * could have captured it has finished. That check is the caller's job
 * (PatternEngine passes a predicate), which keeps this class free of any
 * knowledge of voices.
 *
 * Everything that mutates the bank runs on a loader thread under [mutex_].
 * The audio thread never takes that mutex; it only does an acquire load of
 * the slot pointer.
 */
class SampleBank {
public:
    /**
     * Publishes [data] into [slot]. Returns false for an out-of-range slot.
     * The previous occupant is retired, not freed.
     */
    bool put(int slot, std::shared_ptr<SampleData> data) {
        if (slot < 0 || slot >= kMaxSampleSlots) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        auto previous = slots_[slot].strong;
        slots_[slot].strong = data;
        slots_[slot].raw.store(data.get(), std::memory_order_release);
        slots_[slot].generation.fetch_add(1, std::memory_order_release);
        if (previous) retired_.push_back({slot, std::move(previous)});
        return true;
    }

    /** Empties [slot], retiring whatever was in it. */
    bool clear(int slot) {
        if (slot < 0 || slot >= kMaxSampleSlots) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        auto previous = slots_[slot].strong;
        slots_[slot].strong.reset();
        slots_[slot].raw.store(nullptr, std::memory_order_release);
        slots_[slot].generation.fetch_add(1, std::memory_order_release);
        if (previous) retired_.push_back({slot, std::move(previous)});
        return true;
    }

    /** Audio-thread read. Never allocates, never blocks. */
    const SampleData* get(int slot) const {
        if (slot < 0 || slot >= kMaxSampleSlots) return nullptr;
        return slots_[slot].raw.load(std::memory_order_acquire);
    }

    uint32_t generation(int slot) const {
        if (slot < 0 || slot >= kMaxSampleSlots) return 0;
        return slots_[slot].generation.load(std::memory_order_acquire);
    }

    bool occupied(int slot) const { return get(slot) != nullptr; }

    /**
     * Frees retired buffers that [inUse] says nothing is reading any more.
     * Called from the loader thread; the predicate is evaluated there too, so
     * it must only read atomics the audio thread publishes.
     */
    template <typename Predicate>
    int collect(Predicate inUse) {
        std::lock_guard<std::mutex> lock(mutex_);
        int freed = 0;
        auto it = retired_.begin();
        while (it != retired_.end()) {
            if (inUse(it->buffer.get())) {
                ++it;
            } else {
                it = retired_.erase(it);
                ++freed;
            }
        }
        return freed;
    }

    /** Total resident frames across every slot — for the UI's memory readout. */
    int64_t residentFrames() const {
        std::lock_guard<std::mutex> lock(mutex_);
        int64_t total = 0;
        for (const auto& slot : slots_) {
            if (slot.strong) total += slot.strong->frames;
        }
        return total;
    }

private:
    struct Slot {
        std::shared_ptr<SampleData> strong;      // loader thread only
        std::atomic<const SampleData*> raw{nullptr};
        std::atomic<uint32_t> generation{0};
    };

    struct Retired {
        int slot;
        std::shared_ptr<SampleData> buffer;
    };

    Slot slots_[kMaxSampleSlots];
    std::vector<Retired> retired_;
    mutable std::mutex mutex_;
};

}  // namespace tryptify
