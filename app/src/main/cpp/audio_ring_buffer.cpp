#include "audio_ring_buffer.h"

#include <algorithm>
#include <cstring>

void AudioRingBuffer::Push(const float* data, size_t count, int channel_count, int sample_rate) {
    std::lock_guard<std::mutex> guard(mutex_);
    channel_count_ = channel_count;
    sample_rate_ = sample_rate;
    // Cap at ~100 ms of audio to prevent unbounded growth. Only the newest
    // max_samples survive, from what was held and this push together.
    const size_t max_samples = (sample_rate > 0 && channel_count > 0)
            ? static_cast<size_t>(sample_rate * channel_count) / 10
            : 0;
    if (count >= max_samples) {
        data += count - max_samples;
        count = max_samples;
        head_ = 0;
        size_ = 0;
    }
    if (count == 0) {
        return;
    }
    Reserve(max_samples);
    const size_t capacity = ring_.size();
    if (size_ + count > max_samples) {
        const size_t drop = size_ + count - max_samples;
        head_ = (head_ + drop) % capacity;
        size_ -= drop;
    }
    const size_t write = (head_ + size_) % capacity;
    const size_t first = std::min(count, capacity - write);
    std::memcpy(ring_.data() + write, data, first * sizeof(float));
    if (first < count) {
        std::memcpy(ring_.data(), data + first, (count - first) * sizeof(float));
    }
    size_ += count;
}

bool AudioRingBuffer::Pop(std::vector<float>& out, int& channel_count, int& sample_rate) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (size_ == 0) {
        return false;
    }
    out.resize(size_);
    const size_t first = std::min(size_, ring_.size() - head_);
    std::memcpy(out.data(), ring_.data() + head_, first * sizeof(float));
    if (first < size_) {
        std::memcpy(out.data() + first, ring_.data(), (size_ - first) * sizeof(float));
    }
    head_ = 0;
    size_ = 0;
    channel_count = channel_count_;
    sample_rate = sample_rate_;
    return true;
}

void AudioRingBuffer::Clear() {
    std::lock_guard<std::mutex> guard(mutex_);
    head_ = 0;
    size_ = 0;
}

void AudioRingBuffer::Reserve(size_t capacity) {
    if (ring_.size() >= capacity) {
        return;
    }
    // Only when a format needs more room than any before it.
    std::vector<float> grown(capacity);
    const size_t first = std::min(size_, ring_.size() - head_);
    if (size_ > 0) {
        std::memcpy(grown.data(), ring_.data() + head_, first * sizeof(float));
        if (first < size_) {
            std::memcpy(grown.data() + first, ring_.data(), (size_ - first) * sizeof(float));
        }
    }
    ring_.swap(grown);
    head_ = 0;
}
