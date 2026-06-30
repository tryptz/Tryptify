#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

class AudioRingBuffer {
public:
    explicit AudioRingBuffer(uint32_t requested_capacity_samples = 32768);

    void Push(const float* data, size_t count, int channel_count, int sample_rate);
    bool Pop(std::vector<float>& out, int& channel_count, int& sample_rate);
    void Clear();

private:
    struct alignas(64) CacheLineAtomic {
        std::atomic<uint64_t> value{0};
    };

    static uint32_t NextPowerOfTwo(uint32_t value);

    const uint32_t capacity_;
    const uint32_t mask_;
    std::vector<float> samples_;

    CacheLineAtomic write_index_;
    CacheLineAtomic read_index_;
    alignas(64) std::atomic<int> channel_count_{2};
    alignas(64) std::atomic<int> sample_rate_{44100};
};
