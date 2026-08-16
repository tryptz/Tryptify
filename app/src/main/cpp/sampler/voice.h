// SPDX-License-Identifier: GPL-3.0-or-later
//
// One sounding note. Everything a trig needs to make a sound is resolved when
// the voice starts, so `render` touches nothing but its own fields and the
// sample buffer it captured.

#pragma once

#include <cmath>
#include <cstdint>

#include "sample_bank.h"
#include "sampler_types.h"
#include "wsola.h"

namespace tryptify {

/**
 * Everything a trig decides about a hit.
 *
 * A struct rather than eighteen positional arguments. Most of these are
 * floats, and a positional list that long is a silent-swap waiting to happen —
 * pass release where attack belongs and nothing fails to compile, the sound is
 * just wrong in a way that is very hard to trace back to a call site.
 */
struct VoiceSpec {
    const SampleData* sample = nullptr;
    int slot = -1;
    uint32_t generation = 0;
    int channel = 0;
    double engineSampleRate = 48000.0;

    float semitones = 0.0f;     // pitch offset, independent of speed
    float speed = 1.0f;         // 0.25 .. 4.0, independent of pitch
    bool linked = false;        // tape mode: speed and pitch move together

    float normalizedStart = 0.0f;
    float normalizedEnd = 1.0f;
    bool reverse = false;

    float linearGain = 1.0f;
    float pan = 0.0f;
    float attackMs = 0.0f;
    float releaseMs = 8.0f;
    float filterHz = 22000.0f;

    StretchConfig stretch{1024, 256};
    uint64_t blockCounter = 0;
};

/**
 * A single sample voice: pitched playback with a short attack/release
 * envelope, a one-pole tone filter, and equal-power panning.
 *
 * The envelope is not a nicety. A one-shot cut off mid-waveform is a step
 * discontinuity, and a grid of them at 16th notes is the click-per-step that
 * makes a sampler sound broken. Every path that ends a voice — reaching the
 * end of the sample, being retriggered on the same channel, being stolen
 * because the pool is full — goes through [beginRelease], so there is no way
 * to stop a voice without a ramp.
 *
 * ## Speed and pitch
 *
 * Resampling alone cannot separate the two: reading the sample faster raises
 * its pitch. So the voice runs two stages when it has to.
 *
 * * **Pitch** comes from the resampler, exactly as it always did — read the
 *   source at `rate × 2^(semitones/12)` and everything moves up together.
 * * **Speed** comes from [Wsola], which then puts the timing back where the
 *   user asked for it without touching the pitch.
 *
 * Writing `P` for the pitch ratio and `S` for the speed multiplier, the
 * resampler steps at `rateRatio × P` and the stretcher runs at ratio `P / S`.
 * Each output sample therefore eats `rateRatio × S` source frames — speed `S`
 * — while the stretcher holds the pitch at `P`. Setting either control to
 * unity collapses the other to plain resampling, and setting both to the same
 * factor makes `P / S` exactly 1, so *tape mode is the bypass case rather than
 * a separate code path*.
 *
 * Two things deliberately skip the stretcher:
 *
 * * **Unity.** `P / S == 1` means nothing to stretch, and the sample is read
 *   directly. This is the common case — a drum hit played as recorded — and it
 *   costs exactly what it did before any of this existed.
 * * **Reverse.** WSOLA lays grains down forwards; walking it backwards would
 *   give granular stutter, not reversed audio. A reversed channel plays at
 *   varispeed, which is what hardware samplers do too.
 */
struct Voice {
    // ── identity ────────────────────────────────────────────────────────
    bool active = false;
    int channel = -1;
    int slot = -1;
    uint32_t generation = 0;
    uint64_t startedAtBlock = 0;   // for oldest-first stealing
    const SampleData* data = nullptr;

    // ── playback ────────────────────────────────────────────────────────
    double position = 0.0;
    double increment = 1.0;
    double startFrame = 0.0;
    double endFrame = 0.0;
    bool reverse = false;

    // ── time stretch ────────────────────────────────────────────────────
    bool stretching = false;
    Wsola stretcher;

    // ── mix ─────────────────────────────────────────────────────────────
    float gainL = 1.0f;
    float gainR = 1.0f;

    // ── envelope ────────────────────────────────────────────────────────
    float env = 0.0f;
    float attackInc = 1.0f;    // per-sample rise, 1 = instant
    float releaseInc = 0.01f;  // per-sample fall
    bool releasing = false;
    double releaseAtFrame = 0.0;  // position (in frames travelled) to release at

    // ── tone ────────────────────────────────────────────────────────────
    bool filterOn = false;
    float filterCoeff = 1.0f;
    float filterZL = 0.0f;
    float filterZR = 0.0f;

    /** Shortest ramp we ever use, in seconds — inaudible, but not a step. */
    static constexpr float kMinRampSeconds = 0.0015f;

    /**
     * How close to 1.0 a stretch ratio has to be before it is treated as no
     * stretch at all. 1e-4 is a fifth of a cent — far below anything audible,
     * and small enough that "speed 2× and pitch +12" lands exactly on the
     * bypass rather than running the stretcher to do nothing.
     */
    static constexpr double kStretchBypass = 1e-4;

    void start(const VoiceSpec& spec) {
        const SampleData* sample = spec.sample;
        data = sample;
        slot = spec.slot;
        generation = spec.generation;
        channel = spec.channel;
        startedAtBlock = spec.blockCounter;
        reverse = spec.reverse;
        releasing = false;
        filterZL = 0.0f;
        filterZR = 0.0f;

        const double frames = static_cast<double>(sample->frames);
        double a = static_cast<double>(clamp01(spec.normalizedStart)) * frames;
        double b = static_cast<double>(clamp01(spec.normalizedEnd)) * frames;
        if (b <= a + 1.0) b = frames;  // a degenerate trim plays the whole thing
        startFrame = a;
        endFrame = b;

        const double engineRate = spec.engineSampleRate > 0.0 ? spec.engineSampleRate : 48000.0;
        const double rateRatio = static_cast<double>(sample->sampleRate) / engineRate;
        const double pitchRatio = std::pow(2.0, static_cast<double>(spec.semitones) / 12.0);
        const double speedRatio = clampd(static_cast<double>(spec.speed), kMinSpeed, kMaxSpeed);

        // `increment` walks whatever the resampler is reading — the raw sample
        // when bypassed, the stretcher's output when not. Either way it is the
        // stage that sets pitch.
        //
        // `sourcePerOutput` is the other half: how fast the *source* is
        // consumed, which is what the envelope timing has to be derived from.
        // The two are the same number only when the stretcher is out of the
        // picture.
        //
        // Reverse is grouped with tape mode because WSOLA lays grains down
        // forwards and cannot be walked backwards. Folding speed into the
        // varispeed factor is the honest fallback: a reversed channel asked to
        // go faster does go faster, it just takes the pitch with it — which is
        // what reversing a tape and speeding it up has always sounded like.
        // The alternative, ignoring the speed knob in reverse, would leave a
        // control that silently does nothing.
        const bool tape = spec.linked || reverse;
        double stretchRatio = 1.0;
        if (tape) {
            increment = rateRatio * pitchRatio * speedRatio;
        } else {
            increment = rateRatio * pitchRatio;
            stretchRatio = pitchRatio / speedRatio;
        }
        if (increment < 1e-6) increment = 1e-6;

        stretching = std::fabs(stretchRatio - 1.0) > kStretchBypass;

        // Reverse starts two frames in from the end, not one. The interpolator
        // reads `idx` and `idx + 1`, so a voice starting at the last frame has
        // no second sample to interpolate towards and the bounds check ends it
        // before it produces anything.
        position = reverse ? (endFrame - 2.0) : startFrame;
        if (position < startFrame) position = startFrame;

        const double span = reverse ? (position - startFrame) : (endFrame - position);
        const double grain = static_cast<double>(spec.stretch.window);

        // WSOLA needs a whole grain of source ahead of its read position, so a
        // sample shorter than one grain cannot be stretched at all. Falling
        // back to varispeed keeps a short hit audible; without this a 10 ms
        // click at HIGH quality would ask for a 2048-sample window it does not
        // have and produce nothing.
        if (stretching && span <= grain + 2.0) {
            stretching = false;
            increment = rateRatio * pitchRatio * speedRatio;
        }

        double sourcePerOutput = stretching ? (rateRatio * speedRatio) : increment;
        if (sourcePerOutput < 1e-6) sourcePerOutput = 1e-6;

        if (stretching) {
            stretcher.configure(spec.stretch.window, spec.stretch.searchRadius);
            stretcher.setRatio(stretchRatio);
            stretcher.reset(startFrame);
            // Two pulls prime the interpolator's pair of samples before the
            // first output frame is asked for.
            phase_ = 2.0;
            holdL_ = holdR_ = nextL_ = nextR_ = 0.0f;
        }

        // Equal-power pan keeps a centred hit and a hard-panned hit the same
        // perceived loudness; linear panning drops ~3 dB in the middle.
        const float p = clampf(spec.pan, -1.0f, 1.0f);
        const float angle = (p + 1.0f) * 0.25f * 3.14159265358979f;
        const float g = spec.linearGain * sample->gain;
        gainL = g * std::cos(angle);
        gainR = g * std::sin(angle);

        const float sr = static_cast<float>(engineRate);
        const float minRamp = kMinRampSeconds * sr;
        float attackSamples = fmaxf(spec.attackMs * 0.001f * sr, minRamp);
        float releaseSamples = fmaxf(spec.releaseMs * 0.001f * sr, minRamp);

        // How long this voice will sound for, in output samples — which is not
        // the sample's length once pitch, speed and trim are in play.
        //
        // The stretcher stops one grain short of the end of the source, so the
        // envelope has to stop there too. Get this wrong and the release never
        // starts: the voice runs at full level until the stretcher refuses to
        // produce another grain and then cuts off mid-waveform, which is the
        // click the envelope exists to prevent.
        const double usableSpan = stretching ? (span - grain - 1.0) : span;
        const double outputSamples = (usableSpan > 0.0 ? usableSpan : 0.0) / sourcePerOutput;

        // A short one-shot — a 20 ms tick, or any sample pitched up two
        // octaves — can be over before the two ramps have finished. Scaling
        // them to fit is what keeps such a hit audible at all: with fixed
        // ramps the release would begin before the attack had risen, the
        // envelope would cross zero on the first sample, and the voice would
        // free itself having produced silence.
        const float ramps = attackSamples + releaseSamples;
        if (outputSamples > 2.0 && static_cast<double>(ramps) > outputSamples) {
            const float scale = static_cast<float>(outputSamples) / ramps;
            attackSamples = fmaxf(attackSamples * scale, 1.0f);
            releaseSamples = fmaxf(releaseSamples * scale, 1.0f);
        }

        attackInc = 1.0f / attackSamples;
        releaseInc = 1.0f / releaseSamples;
        env = 0.0f;

        releaseAtFrame = outputSamples - static_cast<double>(releaseSamples);
        if (releaseAtFrame < 0.0) releaseAtFrame = 0.0;
        travelled = 0.0;

        filterOn = spec.filterHz < 20000.0f;
        if (filterOn) {
            // One-pole: y += k (x - y), k from the -3 dB point.
            const float f = clampf(spec.filterHz, 20.0f, 20000.0f);
            const float x = std::exp(-2.0f * 3.14159265358979f * f / sr);
            filterCoeff = 1.0f - x;
        }

        active = true;
    }

    /** Starts the tail. Idempotent — a voice already releasing keeps its ramp. */
    void beginRelease() {
        if (!active || releasing) return;
        releasing = true;
    }

    /** Fastest legal stop: used when the pool is full and a voice must go. */
    void beginSteal(double engineSampleRate) {
        if (!active) return;
        const float sr = static_cast<float>(engineSampleRate > 0.0 ? engineSampleRate : 48000.0);
        const float steal = 1.0f / fmaxf(kMinRampSeconds * sr, 1.0f);
        if (steal > releaseInc) releaseInc = steal;
        releasing = true;
    }

    /**
     * Adds this voice into [outL]/[outR] for [frames] samples starting at
     * [offset]. Clears [active] when the envelope closes or the sample runs
     * out, so the pool reclaims it on the next trigger.
     */
    void render(float* outL, float* outR, int offset, int frames) {
        if (!active || data == nullptr) return;
        const float* srcL = data->left.data();
        const bool isStereo = data->stereo();
        const float* srcR = isStereo ? data->right.data() : srcL;
        // The stretcher wants null for mono so it can skip the right channel
        // entirely, and it wants `endFrame` rather than the buffer length so
        // a trimmed channel stops where the user trimmed it.
        const float* stretchR = isStereo ? data->right.data() : nullptr;
        const int stretchLimit = static_cast<int>(endFrame) < data->frames
                                     ? static_cast<int>(endFrame)
                                     : data->frames;
        const int limit = data->frames;

        for (int i = 0; i < frames; ++i) {
            if (!releasing && travelled >= releaseAtFrame) releasing = true;

            if (releasing) {
                env -= releaseInc;
                if (env <= 0.0f) { active = false; env = 0.0f; return; }
            } else if (env < 1.0f) {
                env += attackInc;
                if (env > 1.0f) env = 1.0f;
            }

            float l, r;
            if (stretching) {
                // Pull whole stretched frames until the fractional read
                // position sits between the two we are holding, then
                // interpolate between them. This is the same linear
                // interpolation as the direct path, just reading a stream that
                // has already had its timing changed.
                while (phase_ >= 1.0) {
                    holdL_ = nextL_;
                    holdR_ = nextR_;
                    if (!stretcher.pull(srcL, stretchR, stretchLimit, nextL_, nextR_)) {
                        active = false;
                        return;
                    }
                    phase_ -= 1.0;
                }
                const float t = static_cast<float>(phase_);
                l = holdL_ + (nextL_ - holdL_) * t;
                r = holdR_ + (nextR_ - holdR_) * t;
                phase_ += increment;
            } else {
                const int idx = static_cast<int>(position);
                if (idx < 0 || idx >= limit - 1) { active = false; return; }
                const float frac = static_cast<float>(position - static_cast<double>(idx));

                // Linear interpolation. Audibly clean for the ±2 octaves a step
                // sequencer asks for, and a fraction of the cost of a 4-point
                // kernel on a pool this size.
                l = srcL[idx] + (srcL[idx + 1] - srcL[idx]) * frac;
                r = srcR[idx] + (srcR[idx + 1] - srcR[idx]) * frac;

                position += reverse ? -increment : increment;
            }

            if (filterOn) {
                filterZL += filterCoeff * (l - filterZL);
                filterZR += filterCoeff * (r - filterZR);
                l = filterZL;
                r = filterZR;
            }

            const int o = offset + i;
            outL[o] += l * gainL * env;
            outR[o] += r * gainR * env;

            travelled += 1.0;

            if (!stretching) {
                if (reverse) {
                    if (position <= startFrame) { active = false; return; }
                } else if (position >= endFrame) {
                    active = false;
                    return;
                }
            }
        }
    }

private:
    double travelled = 0.0;

    // Fractional reader over the stretcher's output.
    double phase_ = 0.0;
    float holdL_ = 0.0f, holdR_ = 0.0f;
    float nextL_ = 0.0f, nextR_ = 0.0f;

    static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    static double clampd(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    static float clamp01(float v) { return clampf(v, 0.0f, 1.0f); }
};

}  // namespace tryptify
