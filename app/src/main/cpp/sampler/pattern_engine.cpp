// SPDX-License-Identifier: GPL-3.0-or-later

#include "pattern_engine.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace tryptify {

namespace {

constexpr float kTwoPi = 6.28318530717958647692f;

inline int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampFloat(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

}  // namespace

// ── lifecycle ───────────────────────────────────────────────────────────

PatternEngine::PatternEngine(int sampleRate) {
    sampleRate_ = sampleRate > 0 ? static_cast<double>(sampleRate) : 48000.0;

    // One allocation for the whole bank. `pool_` is never resized again, so
    // the pointers handed to `slots_` and `free_` stay valid for the life of
    // the engine.
    pool_.resize(static_cast<size_t>(kMaxPatterns) + kPatternSpares);
    for (int i = 0; i < kMaxPatterns; ++i) {
        slots_[i].store(&pool_[static_cast<size_t>(i)], std::memory_order_release);
    }
    free_.reserve(kPatternSpares);
    for (int i = 0; i < kPatternSpares; ++i) {
        free_.push_back(&pool_[static_cast<size_t>(kMaxPatterns + i)]);
    }
    retiredPatterns_.reserve(kPatternSpares);

    scratchL_.assign(kMaxRenderBlock, 0.0f);
    scratchR_.assign(kMaxRenderBlock, 0.0f);

    for (int i = 0; i < kMaxChannels; ++i) triggerCount_[i].store(0, std::memory_order_relaxed);
}

PatternEngine::~PatternEngine() = default;

void PatternEngine::prepare(int sampleRate) {
    if (sampleRate <= 0) return;
    const double next = static_cast<double>(sampleRate);
    if (std::fabs(next - sampleRate_) < 0.5) return;
    sampleRate_ = next;
    // Voices hold an increment derived from the old rate. Rather than
    // re-deriving each one (and guessing at their envelopes), let the current
    // hits ring out at the fastest legal ramp — a rate change only happens on
    // a track transition or an output handover, where a 1.5 ms fade on the
    // sounding voices is inaudible next to the transition itself.
    for (Voice& v : voices_) v.beginSteal(sampleRate_);
    framesToNext_ = 0.0;
}

// ── render ──────────────────────────────────────────────────────────────

void PatternEngine::render(float* outL, float* outR, int frames, bool clearFirst) {
    if (outL == nullptr || outR == nullptr || frames <= 0) return;

    renderDepth_.fetch_add(1, std::memory_order_acq_rel);
    blockCounter_.fetch_add(1, std::memory_order_relaxed);
    drainCommands();

    const float master = masterGain_.load(std::memory_order_relaxed);

    int done = 0;
    while (done < frames) {
        const int n = std::min(kMaxRenderBlock, frames - done);
        float* sl = scratchL_.data();
        float* sr = scratchR_.data();
        std::memset(sl, 0, sizeof(float) * static_cast<size_t>(n));
        std::memset(sr, 0, sizeof(float) * static_cast<size_t>(n));

        renderChunk(sl, sr, n);

        if (clearFirst) {
            for (int i = 0; i < n; ++i) {
                outL[done + i] = sl[i] * master;
                outR[done + i] = sr[i] * master;
            }
        } else {
            for (int i = 0; i < n; ++i) {
                outL[done + i] += sl[i] * master;
                outR[done + i] += sr[i] * master;
            }
        }
        done += n;
    }

    framePos_.fetch_add(frames, std::memory_order_relaxed);
    renderDepth_.fetch_sub(1, std::memory_order_acq_rel);
}

void PatternEngine::renderChunk(float* sl, float* sr, int frames) {
    if (!playing_.load(std::memory_order_relaxed)) {
        // Stopped still sounds: pads tapped by hand, and the tails of whatever
        // was ringing when STOP was pressed.
        renderVoices(sl, sr, 0, frames);
        renderMetronome(sl, sr, 0, frames);
        return;
    }

    int offset = 0;
    int remaining = frames;
    while (remaining > 0) {
        if (framesToNext_ <= 0.0) {
            fireStep();
            const PatternState* p = activePattern();
            const int spb = p != nullptr ? std::max(1, p->stepsPerBeat) : 4;
            const double fps = framesPerStep(sampleRate_, static_cast<double>(bpm_.load(std::memory_order_relaxed)), spb);
            framesToNext_ += framesToNextStep(globalStep_, static_cast<double>(swing_.load(std::memory_order_relaxed)), fps);
            // A pathological tempo must never leave the countdown at zero, or
            // this loop would fire a step per sample.
            if (framesToNext_ <= 0.0) framesToNext_ = 1.0;
        }

        int k = static_cast<int>(std::ceil(framesToNext_));
        if (k > remaining) k = remaining;
        if (k < 1) k = 1;

        renderVoices(sl, sr, offset, k);
        renderMetronome(sl, sr, offset, k);

        framesToNext_ -= static_cast<double>(k);
        offset += k;
        remaining -= k;
    }
}

void PatternEngine::renderVoices(float* outL, float* outR, int offset, int frames) {
    int active = 0;
    for (Voice& v : voices_) {
        if (!v.active) continue;
        v.render(outL, outR, offset, frames);
        if (v.active) ++active;
    }
    activeVoices_.store(active, std::memory_order_relaxed);
}

void PatternEngine::renderMetronome(float* outL, float* outR, int offset, int frames) {
    if (clickEnv_ <= 0.0001f) return;
    const float w = kTwoPi * clickFreq_ / static_cast<float>(sampleRate_);
    // ~30 ms exponential decay, computed once per sub-block rather than per sample.
    const float decay = std::exp(-1.0f / (0.030f * static_cast<float>(sampleRate_)));
    for (int i = 0; i < frames; ++i) {
        const float s = std::sin(clickPhase_) * clickEnv_ * 0.35f;
        outL[offset + i] += s;
        outR[offset + i] += s;
        clickPhase_ += w;
        if (clickPhase_ > kTwoPi) clickPhase_ -= kTwoPi;
        clickEnv_ *= decay;
        if (clickEnv_ <= 0.0001f) { clickEnv_ = 0.0f; return; }
    }
}

// ── steps ───────────────────────────────────────────────────────────────

void PatternEngine::fireStep() {
    ++globalStep_;

    PatternState* p = activePattern();
    if (p == nullptr) return;
    const int stepsPerBeat = std::max(1, p->stepsPerBeat);
    const int beatsPerBar = std::max(1, p->beatsPerBar);

    // Count-in: the clock runs and the metronome ticks, but nothing plays and
    // nothing records, so a hit landing during the pre-roll cannot be written
    // into the pattern by accident.
    const int countIn = countInRemaining_.load(std::memory_order_relaxed);
    if (countIn > 0) {
        countInRemaining_.store(countIn - 1, std::memory_order_relaxed);
        if ((countIn - 1) % stepsPerBeat == 0) {
            clickEnv_ = 1.0f;
            clickPhase_ = 0.0f;
            clickFreq_ = ((countIn - 1) % (stepsPerBeat * beatsPerBar) == 0) ? 1600.0f : 1000.0f;
        }
        return;
    }

    int length = clampInt(p->lengthSteps, 1, kMaxSteps);
    ++stepInPattern_;
    if (stepInPattern_ >= length || stepInPattern_ < 0) {
        stepInPattern_ = 0;
        // The loop point is the musical boundary a queued pattern waits for.
        applyQueuedPattern();
        p = activePattern();
        if (p == nullptr) return;
        length = clampInt(p->lengthSteps, 1, kMaxSteps);
    }
    currentStep_.store(stepInPattern_, std::memory_order_relaxed);

    if (metronome_.load(std::memory_order_relaxed) && stepInPattern_ % stepsPerBeat == 0) {
        clickEnv_ = 1.0f;
        clickPhase_ = 0.0f;
        clickFreq_ = (stepInPattern_ % (stepsPerBeat * beatsPerBar) == 0) ? 1600.0f : 1000.0f;
    }

    const int channelCount = clampInt(p->channelCount, 0, kMaxChannels);
    bool anySoloed = false;
    for (int i = 0; i < channelCount; ++i) {
        if (p->channels[i].soloed != 0) { anySoloed = true; break; }
    }

    for (int i = 0; i < channelCount; ++i) {
        const ChannelState& ch = p->channels[i];
        if (ch.enabled == 0 || ch.sampleSlot < 0) continue;
        if (ch.muted != 0) continue;
        if (anySoloed && ch.soloed == 0) continue;

        const StepData& step = ch.steps[stepInPattern_];
        if (step.enabled == 0) continue;
        if (step.probability < 100 && (nextRandom() % 100u) >= static_cast<uint32_t>(step.probability)) {
            continue;
        }
        triggerChannel(ch, i, static_cast<float>(step.velocity) / 127.0f, &step);
    }
}

void PatternEngine::triggerChannel(const ChannelState& channel, int channelIndex,
                                   float velocity, const StepData* step) {
    const SampleData* data = samples_.get(channel.sampleSlot);
    if (data == nullptr || data->frames < 2) return;

    // A drum channel is monophonic: the new hit chokes the old one rather than
    // stacking, which is both how hardware behaves and what keeps a fast hat
    // roll from eating the whole voice pool.
    chokeChannel(channelIndex);

    Voice* voice = allocateVoice(channelIndex);
    if (voice == nullptr) return;

    float pitch = channel.pitch;
    float pan = channel.pan;
    float start = channel.sampleStart;
    if (step != nullptr) {
        if ((step->locks & kLockPitch) != 0) pitch += static_cast<float>(step->pitch);
        if ((step->locks & kLockPan) != 0) pan = static_cast<float>(step->pan) / 100.0f;
        if ((step->locks & kLockStart) != 0) start = static_cast<float>(step->sampleStart) / 255.0f;
    }

    voice->start(data, channel.sampleSlot, samples_.generation(channel.sampleSlot), channelIndex,
                 sampleRate_, pitch, start, channel.sampleEnd, channel.reverse != 0,
                 channel.volume * clampFloat(velocity, 0.0f, 1.0f), pan, channel.attackMs,
                 channel.releaseMs, channel.filterHz, blockCounter_.load(std::memory_order_relaxed));

    triggerCount_[channelIndex].fetch_add(1, std::memory_order_relaxed);
}

Voice* PatternEngine::allocateVoice(int channelIndex) {
    (void)channelIndex;
    for (Voice& v : voices_) {
        if (!v.active) return &v;
    }
    // Pool exhausted — take the oldest. With per-channel choking this only
    // happens under a dense live-recorded roll, and the alternative (dropping
    // the new hit) is more audible than cutting the oldest tail.
    Voice* oldest = &voices_[0];
    for (Voice& v : voices_) {
        if (v.startedAtBlock < oldest->startedAtBlock) oldest = &v;
    }
    return oldest;
}

void PatternEngine::chokeChannel(int channelIndex) {
    for (Voice& v : voices_) {
        if (v.active && v.channel == channelIndex) v.beginSteal(sampleRate_);
    }
}

void PatternEngine::applyQueuedPattern() {
    const int queued = queuedPattern_.load(std::memory_order_relaxed);
    if (queued < 0) return;
    currentPattern_.store(clampInt(queued, 0, kMaxPatterns - 1), std::memory_order_relaxed);
    queuedPattern_.store(-1, std::memory_order_relaxed);
}

void PatternEngine::resetTransport() {
    globalStep_ = -1;
    stepInPattern_ = -1;
    framesToNext_ = 0.0;
    framePos_.store(0, std::memory_order_relaxed);
    currentStep_.store(0, std::memory_order_relaxed);
}

void PatternEngine::recordLiveHit(int channelIndex, float velocity) {
    PatternState* p = activePattern();
    if (p == nullptr) return;
    const int channelCount = clampInt(p->channelCount, 0, kMaxChannels);
    if (channelIndex < 0 || channelIndex >= channelCount) return;

    const int length = clampInt(p->lengthSteps, 1, kMaxSteps);
    const int stepsPerBeat = std::max(1, p->stepsPerBeat);
    const double fps = framesPerStep(sampleRate_, static_cast<double>(bpm_.load(std::memory_order_relaxed)), stepsPerBeat);
    if (fps <= 0.0) return;

    // framesToNext_ counts down from the length of the current step, so the
    // distance already travelled into it is the remainder.
    const double intoStep = std::max(0.0, fps - framesToNext_);
    const int64_t target = quantizeToStep(globalStep_, intoStep, fps,
                                          recordQuantum_.load(std::memory_order_relaxed));
    const int64_t delta = target - globalStep_;
    const int index = wrapStep(static_cast<int64_t>(stepInPattern_) + delta, length);

    StepData& step = p->channels[channelIndex].steps[index];
    step.enabled = 1;
    step.velocity = static_cast<uint8_t>(clampInt(static_cast<int>(velocity * 127.0f), 1, 127));
    recordEdits_.fetch_add(1, std::memory_order_relaxed);
}

// ── commands ────────────────────────────────────────────────────────────

void PatternEngine::drainCommands() {
    Command cmd;
    while (commands_.pop(cmd)) applyCommand(cmd);
}

void PatternEngine::applyCommand(const Command& cmd) {
    switch (cmd.type) {
        case kCmdSetStep: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            const int ch = clampInt(cmd.b, 0, kMaxChannels - 1);
            const int st = clampInt(cmd.c, 0, kMaxSteps - 1);
            p->channels[ch].steps[st] = cmd.step;
            break;
        }
        case kCmdToggleStep: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            const int ch = clampInt(cmd.b, 0, kMaxChannels - 1);
            const int st = clampInt(cmd.c, 0, kMaxSteps - 1);
            StepData& step = p->channels[ch].steps[st];
            step.enabled = cmd.d != 0 ? 1 : 0;
            if (cmd.f > 0.0f) {
                step.velocity = static_cast<uint8_t>(clampInt(static_cast<int>(cmd.f * 127.0f), 1, 127));
            }
            break;
        }
        case kCmdSetChannelParam: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            ChannelState& ch = p->channels[clampInt(cmd.b, 0, kMaxChannels - 1)];
            switch (cmd.c) {
                case kParamVolume: ch.volume = clampFloat(cmd.f, 0.0f, 2.0f); break;
                case kParamPan: ch.pan = clampFloat(cmd.f, -1.0f, 1.0f); break;
                case kParamPitch: ch.pitch = clampFloat(cmd.f, -24.0f, 24.0f); break;
                case kParamSampleStart: ch.sampleStart = clampFloat(cmd.f, 0.0f, 1.0f); break;
                case kParamSampleEnd: ch.sampleEnd = clampFloat(cmd.f, 0.0f, 1.0f); break;
                case kParamAttack: ch.attackMs = clampFloat(cmd.f, 0.0f, 2000.0f); break;
                case kParamRelease: ch.releaseMs = clampFloat(cmd.f, 0.0f, 5000.0f); break;
                case kParamFilter: ch.filterHz = clampFloat(cmd.f, 20.0f, 22000.0f); break;
                case kParamMute: ch.muted = cmd.f > 0.5f ? 1 : 0; break;
                case kParamSolo: ch.soloed = cmd.f > 0.5f ? 1 : 0; break;
                case kParamEnabled: ch.enabled = cmd.f > 0.5f ? 1 : 0; break;
                case kParamReverse: ch.reverse = cmd.f > 0.5f ? 1 : 0; break;
                default: break;
            }
            break;
        }
        case kCmdSetChannelSample: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            ChannelState& ch = p->channels[clampInt(cmd.b, 0, kMaxChannels - 1)];
            const int slot = cmd.c;
            ch.sampleSlot = (slot >= 0 && slot < kMaxSampleSlots) ? slot : -1;
            break;
        }
        case kCmdSetPatternLength: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            p->lengthSteps = clampInt(cmd.b, 1, kMaxSteps);
            if (cmd.a == currentPattern_.load(std::memory_order_relaxed) &&
                stepInPattern_ >= p->lengthSteps) {
                stepInPattern_ = p->lengthSteps - 1;
            }
            break;
        }
        case kCmdSetPatternMeter: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            p->stepsPerBeat = clampInt(cmd.b, 1, 16);
            p->beatsPerBar = clampInt(cmd.c, 1, 16);
            break;
        }
        case kCmdClearPattern: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            for (int c = 0; c < kMaxChannels; ++c) {
                for (int s = 0; s < kMaxSteps; ++s) p->channels[c].steps[s] = StepData{};
            }
            break;
        }
        case kCmdClearChannel: {
            PatternState* p = pattern(cmd.a);
            if (p == nullptr) break;
            ChannelState& ch = p->channels[clampInt(cmd.b, 0, kMaxChannels - 1)];
            for (int s = 0; s < kMaxSteps; ++s) ch.steps[s] = StepData{};
            break;
        }
        case kCmdQueuePattern: {
            const int index = clampInt(cmd.a, 0, kMaxPatterns - 1);
            if (cmd.b != 0 || !playing_.load(std::memory_order_relaxed)) {
                // Immediate: swap the pattern but keep the clock, so a live
                // switch lands on the beat instead of restarting the bar.
                currentPattern_.store(index, std::memory_order_relaxed);
                queuedPattern_.store(-1, std::memory_order_relaxed);
                const PatternState* p = activePattern();
                if (p != nullptr) {
                    const int length = clampInt(p->lengthSteps, 1, kMaxSteps);
                    if (stepInPattern_ >= length) stepInPattern_ = length - 1;
                }
            } else {
                queuedPattern_.store(index, std::memory_order_relaxed);
            }
            break;
        }
        case kCmdSetBpm:
            bpm_.store(clampFloat(cmd.f, 20.0f, 300.0f), std::memory_order_relaxed);
            break;
        case kCmdSetSwing:
            swing_.store(clampFloat(cmd.f, 0.0f, 1.0f), std::memory_order_relaxed);
            break;
        case kCmdSetMasterGain:
            masterGain_.store(clampFloat(cmd.f, 0.0f, 2.0f), std::memory_order_relaxed);
            break;
        case kCmdSetMetronome:
            metronome_.store(cmd.a != 0, std::memory_order_relaxed);
            break;
        case kCmdSetCountIn:
            countInBars_.store(clampInt(cmd.a, 0, 4), std::memory_order_relaxed);
            break;
        case kCmdSetRecordQuantize:
            recordQuantum_.store(clampInt(cmd.a, 0, 16), std::memory_order_relaxed);
            break;
        case kCmdTransport: {
            switch (cmd.a) {
                case kTransportPlay: {
                    if (!playing_.load(std::memory_order_relaxed)) {
                        resetTransport();
                        const PatternState* p = activePattern();
                        const int stepsPerBeat = p != nullptr ? std::max(1, p->stepsPerBeat) : 4;
                        const int beatsPerBar = p != nullptr ? std::max(1, p->beatsPerBar) : 4;
                        countInRemaining_.store(
                            countInBars_.load(std::memory_order_relaxed) * stepsPerBeat * beatsPerBar,
                            std::memory_order_relaxed);
                        playing_.store(true, std::memory_order_relaxed);
                    }
                    break;
                }
                case kTransportStop:
                    playing_.store(false, std::memory_order_relaxed);
                    recording_.store(false, std::memory_order_relaxed);
                    countInRemaining_.store(0, std::memory_order_relaxed);
                    resetTransport();
                    for (Voice& v : voices_) v.beginRelease();
                    break;
                case kTransportRecordOn:
                    recording_.store(true, std::memory_order_relaxed);
                    break;
                case kTransportRecordOff:
                    recording_.store(false, std::memory_order_relaxed);
                    break;
                case kTransportRewind:
                    resetTransport();
                    break;
                default:
                    break;
            }
            break;
        }
        case kCmdTrigger: {
            const PatternState* p = activePattern();
            if (p == nullptr) break;
            const int index = clampInt(cmd.a, 0, kMaxChannels - 1);
            const float velocity = cmd.f > 0.0f ? cmd.f : 1.0f;
            triggerChannel(p->channels[index], index, velocity, nullptr);
            if (recording_.load(std::memory_order_relaxed) &&
                playing_.load(std::memory_order_relaxed) &&
                countInRemaining_.load(std::memory_order_relaxed) == 0) {
                recordLiveHit(index, velocity);
            }
            break;
        }
        case kCmdStopChannel:
            chokeChannel(clampInt(cmd.a, 0, kMaxChannels - 1));
            break;
        default:
            break;
    }
}

// ── pattern publication ─────────────────────────────────────────────────

bool PatternEngine::uploadPattern(int index, const PatternState& source) {
    if (index < 0 || index >= kMaxPatterns) return false;
    std::lock_guard<std::mutex> lock(mutex_);
    reclaimPatternsLocked();

    if (free_.empty()) return false;

    PatternState* buffer = free_.back();
    free_.pop_back();
    *buffer = source;
    PatternState* previous = slots_[index].exchange(buffer, std::memory_order_acq_rel);
    retiredPatterns_.push_back({previous, blockCounter_.load(std::memory_order_relaxed)});
    return true;
}

void PatternEngine::reclaimPatternsLocked() {
    const uint64_t now = blockCounter_.load(std::memory_order_relaxed);
    const bool idle = renderDepth_.load(std::memory_order_acquire) == 0;

    auto it = retiredPatterns_.begin();
    while (it != retiredPatterns_.end()) {
        // Two ways to know the audio thread cannot still be reading a retired
        // buffer. Either two render blocks have been through since it was
        // swapped out — a render only ever holds a pattern pointer for the
        // duration of one call — or no render has run at all since then and
        // none is running now, which is the cold-start and idle case.
        const bool drained = now >= it->block + 2;
        const bool neverRendered = idle && now == it->block;
        if (drained || neverRendered) {
            free_.push_back(it->buffer);
            it = retiredPatterns_.erase(it);
        } else {
            ++it;
        }
    }
}

bool PatternEngine::readPattern(int index, PatternState& out) const {
    PatternState* p = pattern(index);
    if (p == nullptr) return false;
    // A concurrent command could be editing a step as this copies. Callers are
    // diagnostics and tests, where a one-step-stale read is not a problem;
    // Room, not the engine, is the source of truth for the UI.
    out = *p;
    return true;
}

int PatternEngine::collectGarbage() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        reclaimPatternsLocked();
    }
    // A retired sample buffer is reachable only through a voice that captured
    // its pointer at trigger time, so "still in use" is exactly "some active
    // voice points at it".
    return samples_.collect([this](const SampleData* buffer) {
        for (const Voice& v : voices_) {
            if (v.active && v.data == buffer) return true;
        }
        return false;
    });
}

uint32_t PatternEngine::nextRandom() {
    // xorshift32 — no allocation, no lock, and plenty of quality for a
    // per-step probability roll.
    rngState_ ^= rngState_ << 13;
    rngState_ ^= rngState_ >> 17;
    rngState_ ^= rngState_ << 5;
    return rngState_;
}

}  // namespace tryptify
