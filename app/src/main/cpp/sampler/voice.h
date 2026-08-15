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

namespace tryptify {

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

    void start(const SampleData* sample,
               int slotIndex,
               uint32_t slotGeneration,
               int channelIndex,
               double engineSampleRate,
               float semitones,
               float normalizedStart,
               float normalizedEnd,
               bool playReverse,
               float linearGain,
               float pan,
               float attackMs,
               float releaseMs,
               float filterHz,
               uint64_t blockCounter) {
        data = sample;
        slot = slotIndex;
        generation = slotGeneration;
        channel = channelIndex;
        startedAtBlock = blockCounter;
        reverse = playReverse;
        releasing = false;
        filterZL = 0.0f;
        filterZR = 0.0f;

        const double frames = static_cast<double>(sample->frames);
        double a = static_cast<double>(clamp01(normalizedStart)) * frames;
        double b = static_cast<double>(clamp01(normalizedEnd)) * frames;
        if (b <= a + 1.0) b = frames;  // a degenerate trim plays the whole thing
        startFrame = a;
        endFrame = b;

        const double rateRatio = static_cast<double>(sample->sampleRate) /
                                 (engineSampleRate > 0.0 ? engineSampleRate : 48000.0);
        increment = std::pow(2.0, static_cast<double>(semitones) / 12.0) * rateRatio;
        if (increment < 1e-6) increment = 1e-6;
        position = reverse ? (endFrame - 1.0) : startFrame;

        // Equal-power pan keeps a centred hit and a hard-panned hit the same
        // perceived loudness; linear panning drops ~3 dB in the middle.
        const float p = clampf(pan, -1.0f, 1.0f);
        const float angle = (p + 1.0f) * 0.25f * 3.14159265358979f;
        const float g = linearGain * sample->gain;
        gainL = g * std::cos(angle);
        gainR = g * std::sin(angle);

        const float sr = static_cast<float>(engineSampleRate > 0.0 ? engineSampleRate : 48000.0);
        const float minRamp = kMinRampSeconds * sr;
        float attackSamples = fmaxf(attackMs * 0.001f * sr, minRamp);
        float releaseSamples = fmaxf(releaseMs * 0.001f * sr, minRamp);

        // How long this voice will sound for, in output samples — which is not
        // the sample's length once pitch and trim are in play.
        const double span = reverse ? (position - startFrame) : (endFrame - position);
        const double outputSamples = span / increment;

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

        filterOn = filterHz < 20000.0f;
        if (filterOn) {
            // One-pole: y += k (x - y), k from the -3 dB point.
            const float f = clampf(filterHz, 20.0f, 20000.0f);
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
        const float* srcR = data->stereo() ? data->right.data() : srcL;
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

            const int idx = static_cast<int>(position);
            if (idx < 0 || idx >= limit - 1) { active = false; return; }
            const float frac = static_cast<float>(position - static_cast<double>(idx));

            // Linear interpolation. Audibly clean for the ±2 octaves a step
            // sequencer asks for, and a fraction of the cost of a 4-point
            // kernel on a pool this size.
            float l = srcL[idx] + (srcL[idx + 1] - srcL[idx]) * frac;
            float r = srcR[idx] + (srcR[idx + 1] - srcR[idx]) * frac;

            if (filterOn) {
                filterZL += filterCoeff * (l - filterZL);
                filterZR += filterCoeff * (r - filterZR);
                l = filterZL;
                r = filterZR;
            }

            const int o = offset + i;
            outL[o] += l * gainL * env;
            outR[o] += r * gainR * env;

            position += reverse ? -increment : increment;
            travelled += 1.0;

            if (reverse) {
                if (position <= startFrame) { active = false; return; }
            } else if (position >= endFrame) {
                active = false;
                return;
            }
        }
    }

private:
    double travelled = 0.0;

    static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    static float clamp01(float v) { return clampf(v, 0.0f, 1.0f); }
};

}  // namespace tryptify
