// SPDX-License-Identifier: GPL-3.0-or-later

#include "motion_blur.h"

#include <algorithm>
#include <cmath>

namespace tryptify::motion {

namespace {

// Level 0 is off: no shutter, so every plan is empty and costs nothing.
// From there each level adds copies (smoother trail, more draws) and reach.
// edgeCoverage is the same everywhere on purpose: see Quality.
constexpr Quality kQualities[kQualityLevels] = {
    // shutter, taps, spacing, reach, budget, edge, sigma
    {0.00f, 0, 1.0f, 0.00f, 0, 0.45f, 0.0f},     // Off
    {0.35f, 1, 1e9f, 0.35f, 64, 0.45f, 2.0f},    // Light: one copy, anywhere in the smear
    {0.50f, 2, 6.0f, 0.50f, 160, 0.45f, 4.0f},   // Balanced
    {0.50f, 4, 4.0f, 0.60f, 320, 0.45f, 6.0f},   // Smooth
    {0.75f, 8, 2.0f, 0.75f, 640, 0.45f, 10.0f},  // Cinematic
};

// A box filter of length L has the variance of a Gaussian of sigma L/sqrt(12):
// the blur that spreads a moving edge as far as the shutter did.
constexpr float kBoxToSigma = 0.28867513f;
constexpr float kSigmaStep = 0.25f;

float smoothing(float dt, float seconds) { return 1.0f - std::exp(-dt / seconds); }

}  // namespace

const Quality& qualityFor(int level) {
    return kQualities[std::clamp(level, 0, kQualityLevels - 1)];
}

float Tracker::update(int64_t frameNanos, float positionPx, float jumpPx) {
    if (!std::isfinite(positionPx)) return velocity_;
    if (!hasSample_) {
        hasSample_ = true;
        lastNanos_ = frameNanos;
        lastPosition_ = positionPx;
        return velocity_;
    }
    const int64_t gapNanos = frameNanos - lastNanos_;
    if (gapNanos == 0) return velocity_;  // the same frame drawn again
    const float dt = static_cast<float>(gapNanos) * 1e-9f;
    const float moved = positionPx - lastPosition_;
    lastNanos_ = frameNanos;
    lastPosition_ = positionPx;

    // A clock that went back, a long stall, or a move no scroll makes in one
    // frame: start again from rest at the new place. Averaged in, a seek
    // across the song would smear every note across the screen for a frame.
    if (gapNanos < 0 || dt > kMaxGapSeconds || (jumpPx > 0.0f && std::fabs(moved) > jumpPx)) {
        velocity_ = 0.0f;
        return velocity_;
    }

    const float frame = std::clamp(dt, kMinFrameSeconds, kMaxFrameSeconds);
    if (frameMeasured_) {
        frameSeconds_ += (frame - frameSeconds_) * smoothing(dt, kFrameSmoothingSeconds);
    } else {
        frameSeconds_ = frame;
        frameMeasured_ = true;
    }
    // Weighted by the real gap, so 60, 90 and 120 Hz settle in the same time.
    velocity_ += (moved / dt - velocity_) * smoothing(dt, kVelocitySmoothingSeconds);
    return velocity_;
}

void Tracker::reset() { *this = Tracker(); }

float exposureSeconds(const Quality& q, float frameSeconds) {
    if (!std::isfinite(frameSeconds)) return 0.0f;
    return q.shutter * std::clamp(frameSeconds, Tracker::kMinFrameSeconds, Tracker::kMaxFrameSeconds);
}

Trail planTrail(const Quality& q, float velocityPxPerSec, float exposure, float spriteSizePx, int sprites) {
    Trail trail;
    if (q.maxTaps <= 0 || sprites <= 0 || !(spriteSizePx > 0.0f)) return trail;
    if (!std::isfinite(velocityPxPerSec) || !std::isfinite(exposure) || exposure <= 0.0f) return trail;

    const float length = std::min(std::fabs(velocityPxPerSec) * exposure, q.maxTrailOfSprite * spriteSizePx);
    if (length < kMinTrailPx) return trail;

    int taps = static_cast<int>(std::ceil(length / q.tapSpacingPx));
    taps = std::clamp(taps, 1, std::min(q.maxTaps, kMaxTaps));
    // The frame's budget, shared out: a stream of notes gets fewer copies
    // each, and past the budget none, rather than a slower frame.
    taps = std::min(taps, q.frameTapBudget / sprites);
    if (taps <= 0) return trail;

    trail.taps = taps;
    trail.spacingPx = length / static_cast<float>(taps);
    trail.lengthPx = length;
    // Right behind the sprite all `taps` copies overlap, and src-over stacks
    // them to 1 - (1 - a)^taps. Solved for edgeCoverage, the trail starts
    // equally opaque at every level and fades by itself toward its end.
    trail.alpha = 1.0f - std::pow(1.0f - q.edgeCoverage, 1.0f / static_cast<float>(taps));
    return trail;
}

float blurSigma(const Quality& q, float velocityPxPerSec, float exposure) {
    if (q.maxSigmaPx <= 0.0f || !std::isfinite(velocityPxPerSec) || !std::isfinite(exposure)) return 0.0f;
    const float length = std::fabs(velocityPxPerSec) * std::max(exposure, 0.0f);
    if (length < kMinTrailPx) return 0.0f;
    const float sigma = std::min(length * kBoxToSigma, q.maxSigmaPx);
    return std::floor(sigma / kSigmaStep) * kSigmaStep;
}

}  // namespace tryptify::motion
