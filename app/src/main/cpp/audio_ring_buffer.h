#pragma once

#include <cstddef>
#include <mutex>
#include <vector>

/**
 * The PCM pushed between two rendered frames, the newest ~100 ms of it.
 *
 * A ring: it used to be a vector that was appended to and then erased from the
 * front once full, which moved the whole ~38 KB of it on every push. What a
 * Pop returns is the same as before, sample for sample.
 */
class AudioRingBuffer {
public:
    void Push(const float* data, size_t count, int channel_count, int sample_rate);
    /** Everything held, oldest first, into [out] (its capacity is reused) and empties the ring. */
    bool Pop(std::vector<float>& out, int& channel_count, int& sample_rate);
    void Clear();

private:
    /** Grows the ring to at least [capacity] samples, keeping what it holds. */
    void Reserve(size_t capacity);

    std::mutex mutex_;
    std::vector<float> ring_;  // its size is the capacity
    size_t head_ = 0;          // index of the oldest sample
    size_t size_ = 0;          // samples held
    int channel_count_ = 2;
    int sample_rate_ = 44100;
};
