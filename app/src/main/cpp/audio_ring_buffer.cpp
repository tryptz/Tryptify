#include "audio_ring_buffer.h"

#include <algorithm>

AudioRingBuffer::AudioRingBuffer(uint32_t requested_capacity_samples)
        : capacity_(NextPowerOfTwo(std::max<uint32_t>(requested_capacity_samples, 1))),
          mask_(capacity_ - 1),
          samples_(capacity_) {}

void AudioRingBuffer::Push(const float* data, size_t count, int channel_count, int sample_rate) {
    if (data == nullptr || count == 0) {
        return;
    }

    channel_count_.store(channel_count, std::memory_order_relaxed);
    sample_rate_.store(sample_rate, std::memory_order_relaxed);

    uint64_t write = write_index_.value.load(std::memory_order_relaxed);
    uint64_t read = read_index_.value.load(std::memory_order_acquire);

    if (count >= capacity_) {
        data += count - capacity_;
        count = capacity_;
        read = write;
    } else {
        const uint64_t used = write - read;
        const uint64_t needed = used + count;
        if (needed > capacity_) {
            read += needed - capacity_;
        }
    }

    for (size_t i = 0; i < count; ++i) {
        samples_[(write + i) & mask_] = data[i];
    }
    read_index_.value.store(read, std::memory_order_release);
    write_index_.value.store(write + count, std::memory_order_release);
}

bool AudioRingBuffer::Pop(std::vector<float>& out, int& channel_count, int& sample_rate) {
    const uint64_t read = read_index_.value.load(std::memory_order_relaxed);
    const uint64_t write = write_index_.value.load(std::memory_order_acquire);
    const uint64_t available = write - read;

    if (available == 0) {
        return false;
    }

    out.resize(static_cast<size_t>(available));
    for (uint64_t i = 0; i < available; ++i) {
        out[static_cast<size_t>(i)] = samples_[(read + i) & mask_];
    }

    read_index_.value.store(write, std::memory_order_release);
    channel_count = channel_count_.load(std::memory_order_relaxed);
    sample_rate = sample_rate_.load(std::memory_order_relaxed);
    return true;
}

void AudioRingBuffer::Clear() {
    const uint64_t write = write_index_.value.load(std::memory_order_acquire);
    read_index_.value.store(write, std::memory_order_release);
}

uint32_t AudioRingBuffer::NextPowerOfTwo(uint32_t value) {
    if (value <= 1) {
        return 1;
    }
    value--;
    value |= value >> 1;
    value |= value >> 2;
    value |= value >> 4;
    value |= value >> 8;
    value |= value >> 16;
    return value + 1;
}
