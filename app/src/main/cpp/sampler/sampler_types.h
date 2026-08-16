// SPDX-License-Identifier: GPL-3.0-or-later
//
// Shared PODs and limits for the pattern looper / sampler engine.
//
// Everything here is trivially copyable and fixed-size on purpose: the audio
// thread reads these structures directly and must never allocate, so the whole
// pattern bank is carved out of one up-front allocation and the command queue
// moves fixed-size records rather than pointers to heap objects.

#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>

namespace tryptify {

// ── Limits ──────────────────────────────────────────────────────────────
//
// Sized so the resident bank stays well under a megabyte:
//   64 patterns × 16 channels × (64 steps × 8 B + header) ≈ 620 KB.
// The DSP engine already keeps ~380 KB of scratch resident, so this is in
// the same order as what the app already carries for audio.

static constexpr int kMaxChannels = 16;
static constexpr int kMaxSteps = 64;
static constexpr int kMaxPatterns = 64;
static constexpr int kMaxVoices = 32;
static constexpr int kMaxSampleSlots = 256;

/** Spare pattern buffers kept for lock-free publish + deferred reclaim. */
static constexpr int kPatternSpares = 8;

/** Sub-block the voice mixer works in when nothing forces a shorter one. */
static constexpr int kMaxRenderBlock = 4096;

// ── Step ────────────────────────────────────────────────────────────────

/**
 * One trig. Eight bytes, which is what keeps a whole 16-channel pattern
 * inside 10 KB and a bank inside a megabyte.
 *
 * `velocity` is the only per-step value v1 actually applies; `pitch`,
 * `probability` and `pan` are read by the engine already so parameter locks
 * can be turned on from the UI side without touching the audio path again.
 * A lock is only honoured when its bit is set in [locks] — otherwise the
 * channel's own value wins, which is what makes an unlocked step follow the
 * sound editor.
 */
struct StepData {
    uint8_t enabled = 0;      // 0 / 1
    uint8_t velocity = 100;   // 0..127
    int8_t pitch = 0;         // semitones, -24..+24
    uint8_t probability = 100; // 0..100
    int8_t pan = 0;           // -100..+100 → -1..+1
    uint8_t sampleStart = 0;  // 0..255 → 0..1 of the sample
    uint8_t locks = 0;        // bitmask, see kLock*
    uint8_t speed = 0;        // see stepSpeedFromCode
};

static constexpr uint8_t kLockPitch = 1 << 0;
static constexpr uint8_t kLockPan = 1 << 1;
static constexpr uint8_t kLockStart = 1 << 2;
static constexpr uint8_t kLockSpeed = 1 << 3;

// ── Speed ───────────────────────────────────────────────────────────────

static constexpr float kMinSpeed = 0.25f;
static constexpr float kMaxSpeed = 4.0f;

/**
 * Speed is stored per step in one byte, which has to cover 0.25× to 4.0× —
 * four octaves of tempo — with enough resolution that a parameter lock does
 * not sound stepped.
 *
 * The mapping is exponential rather than linear because speed is perceived
 * that way: the interval from 0.5× to 1× is the same musical distance as 1× to
 * 2×, and a linear byte would spend three quarters of its range above unity
 * while quantising everything below it into a handful of values. Exponential
 * spacing puts 1.0 exactly on code 128 and gives every code the same
 * proportional step, about 1.1% — a fifth of a semitone, which is under the
 * threshold where a tempo change reads as a staircase.
 *
 * Code 0 is not 0.25×; it means "no lock, follow the channel", which is why
 * the usable range starts at 1.
 */
inline float stepSpeedFromCode(uint8_t code) {
    if (code == 0) return 1.0f;
    return kMinSpeed * std::exp2(4.0f * (static_cast<float>(code) - 1.0f) / 254.0f);
}

inline uint8_t stepSpeedToCode(float speed) {
    if (speed < kMinSpeed) speed = kMinSpeed;
    if (speed > kMaxSpeed) speed = kMaxSpeed;
    const float steps = std::log2(speed / kMinSpeed) * 254.0f / 4.0f + 1.0f;
    int code = static_cast<int>(steps + 0.5f);
    if (code < 1) code = 1;
    if (code > 255) code = 255;
    return static_cast<uint8_t>(code);
}

// ── Stretch quality ─────────────────────────────────────────────────────

/**
 * How much CPU the time stretcher may spend per voice.
 *
 * These are not decorative labels: each one is a real grain size and search
 * radius, and the radius sets the lowest frequency the stretcher can hold
 * together (roughly `sampleRate / radius` — see [Wsola]). FAST gives up on
 * bass below ~375 Hz, HIGH holds down to ~94 Hz.
 */
enum StretchQuality : int32_t {
    kStretchFast = 0,
    kStretchBalanced = 1,
    kStretchHigh = 2,
};

struct StretchConfig {
    int window;
    int searchRadius;
};

inline StretchConfig stretchConfigFor(int quality) {
    switch (quality) {
        case kStretchFast: return {512, 128};
        case kStretchHigh: return {2048, 512};
        default: return {1024, 256};
    }
}

// ── Channel ─────────────────────────────────────────────────────────────

/** Sound parameters + step data for one channel of one pattern. */
struct ChannelState {
    int sampleSlot = -1;      // index into SampleBank, -1 = unassigned
    float volume = 1.0f;      // linear, 0..2
    float pan = 0.0f;         // -1 left .. +1 right
    float pitch = 0.0f;       // semitones
    float speed = 1.0f;       // 0.25 .. 4.0, independent of pitch unless linked
    float sampleStart = 0.0f; // 0..1
    float sampleEnd = 1.0f;   // 0..1
    float attackMs = 0.0f;
    float releaseMs = 8.0f;
    float filterHz = 22000.0f; // >= 20000 means "off"
    uint8_t muted = 0;
    uint8_t soloed = 0;
    uint8_t enabled = 1;
    uint8_t reverse = 0;
    /**
     * Tape mode. Linked, speed and pitch multiply into a single varispeed
     * factor and the time stretcher is bypassed — faster is higher, exactly
     * like pushing a reel. Unlinked, the two are genuinely independent and the
     * stretcher does the work.
     */
    uint8_t linked = 0;
    StepData steps[kMaxSteps];
};

/** Parameter ids for kCmdSetChannelParam. Mirrored by NativeSamplerBridge. */
enum ChannelParam : int32_t {
    kParamVolume = 0,
    kParamPan = 1,
    kParamPitch = 2,
    kParamSampleStart = 3,
    kParamSampleEnd = 4,
    kParamAttack = 5,
    kParamRelease = 6,
    kParamFilter = 7,
    kParamMute = 8,
    kParamSolo = 9,
    kParamEnabled = 10,
    kParamReverse = 11,
    kParamSpeed = 12,
    kParamLinked = 13,
};

// ── Pattern ─────────────────────────────────────────────────────────────

/**
 * One pattern: a length, a meter, and its channels.
 *
 * Published to the audio thread by atomic pointer swap (see PatternEngine),
 * so a pattern reloaded from Room never tears against a running loop.
 */
struct PatternState {
    int32_t lengthSteps = 16;
    int32_t stepsPerBeat = 4;   // 4 = sixteenth-note grid
    int32_t beatsPerBar = 4;
    float bpmOverride = 0.0f;   // <= 0 → follow the transport
    int32_t channelCount = 4;
    ChannelState channels[kMaxChannels];
};

// ── Commands ────────────────────────────────────────────────────────────

enum CommandType : int32_t {
    kCmdNone = 0,
    kCmdSetStep,           // a=pattern b=channel c=step, step payload
    kCmdToggleStep,        // a=pattern b=channel c=step d=enabled, f=velocity
    kCmdSetChannelParam,   // a=pattern b=channel c=ChannelParam, f=value
    kCmdSetChannelSample,  // a=pattern b=channel c=slot
    kCmdSetPatternLength,  // a=pattern b=length
    kCmdSetPatternMeter,   // a=pattern b=stepsPerBeat c=beatsPerBar
    kCmdClearPattern,      // a=pattern (-1 = every channel of current)
    kCmdClearChannel,      // a=pattern b=channel
    kCmdQueuePattern,      // a=index b=1 for immediate
    kCmdSetBpm,            // f=bpm
    kCmdSetSwing,          // f=0..1
    kCmdTransport,         // a=TransportAction
    kCmdTrigger,           // a=channel f=velocity(0..1)
    kCmdSetMetronome,      // a=enabled
    kCmdSetCountIn,        // a=bars
    kCmdSetRecordQuantize, // a=steps-per-quantum (0 = off)
    kCmdSetMasterGain,     // f=linear
    kCmdStopChannel,       // a=channel
    kCmdSetStretchQuality, // a=StretchQuality
};

enum TransportAction : int32_t {
    kTransportStop = 0,
    kTransportPlay = 1,
    kTransportRecordOn = 2,
    kTransportRecordOff = 3,
    kTransportRewind = 4,
};

/**
 * A fixed-size command record. The UI side writes these into a ring the audio
 * thread drains at the top of every render call — no locks, no allocation, and
 * an edit that lands mid-block still takes effect on a step boundary because
 * the step data it touches is only read when a step fires.
 */
struct Command {
    int32_t type = kCmdNone;
    int32_t a = 0;
    int32_t b = 0;
    int32_t c = 0;
    int32_t d = 0;
    float f = 0.0f;
    StepData step{};
};

/**
 * Single-producer / single-consumer ring.
 *
 * The producer side is Kotlin, funnelled through one synchronized bridge, so
 * "single producer" holds without the audio thread ever taking a lock. A full
 * ring drops the oldest-arriving command rather than blocking; at 1024 slots
 * that only happens if the UI posts thousands of edits between two audio
 * callbacks, which it cannot.
 */
template <int Capacity>
class CommandQueue {
public:
    bool push(const Command& cmd) {
        const uint32_t w = write_.load(std::memory_order_relaxed);
        const uint32_t next = (w + 1) % Capacity;
        if (next == read_.load(std::memory_order_acquire)) return false; // full
        buffer_[w] = cmd;
        write_.store(next, std::memory_order_release);
        return true;
    }

    bool pop(Command& out) {
        const uint32_t r = read_.load(std::memory_order_relaxed);
        if (r == write_.load(std::memory_order_acquire)) return false; // empty
        out = buffer_[r];
        read_.store((r + 1) % Capacity, std::memory_order_release);
        return true;
    }

private:
    Command buffer_[Capacity];
    std::atomic<uint32_t> write_{0};
    std::atomic<uint32_t> read_{0};
};

}  // namespace tryptify
