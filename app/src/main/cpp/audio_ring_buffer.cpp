#include "audio_ring_buffer.h"

void AudioRingBuffer::Push(const float* data, size_t count, int channel_count, int sample_rate) {
    std::lock_guard<std::mutex> guard(mutex_);
    channel_count_ = channel_count;
    sample_rate_ = sample_rate;
    // Accumulate samples so no audio data is lost between render frames
    samples_.insert(samples_.end(), data, data + count);
    // Cap at ~100 ms of audio to prevent unbounded growth
    const size_t max_samples = static_cast<size_t>(sample_rate * channel_count) / 10;
    if (samples_.size() > max_samples) {
        samples_.erase(samples_.begin(), samples_.end() - max_samples);
    }
}

bool AudioRingBuffer::Pop(std::vector<float>& out, int& channel_count, int& sample_rate) {
    std::lock_guard<std::mutex> guard(mutex_);
    if (samples_.empty()) {
        return false;
    }
    out = samples_;
    samples_.clear();
    channel_count = channel_count_;
    sample_rate = sample_rate_;
    return true;
}

void AudioRingBuffer::Clear() {
    std::lock_guard<std::mutex> guard(mutex_);
    samples_.clear();
}
