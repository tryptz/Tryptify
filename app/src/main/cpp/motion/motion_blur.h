// SPDX-License-Identifier: GPL-3.0-or-later
//
// Low-cost motion blur for things that slide along one axis: Trypt Mania's
// notes and the lyric glide.
//
// Nothing here touches a pixel. A real motion blur renders the scene at many
// moments inside the shutter and averages them; that is far too dear for a
// phone drawing a few hundred notes. What this does instead is the part that
// is cheap and gets most of the look:
//
//   * Tracker measures how fast something moves on screen, in px/s, from the
//     positions it is given once a frame. Smoothed so a late frame does not
//     flash a long smear, and a jump (a seek, a restart, a new song) is taken
//     as a jump, not as a very fast move.
//   * planTrail turns that speed into a trail: a few faded copies of a sprite
//     behind it, drawn under the sharp one. The sprite itself stays sharp,
//     which a rhythm game needs: the note's edge is where the player reads it.
//   * blurSigma turns it into the radius of one directional blur, for a layer
//     that can be blurred as a whole (a lyric line).
//
// Both are free when nothing moves fast: no copies and no blur below about a
// pixel and a half of smear. The quality level (0 off .. 4) only decides how
// smooth the trail is and how far it may reach, never how strong it is, so
// moving the slider trades GPU work for grain without changing the picture's
// weight.
//
// No Android headers: tests/ builds this on a desktop. Single-threaded: each
// Tracker belongs to one view, driven from the main thread.

#pragma once

#include <cstdint>

namespace tryptify::motion {

constexpr int kQualityLevels = 5;  // 0 off, 1 light .. 4 cinematic
constexpr int kMaxTaps = 8;

struct Quality {
    // Fraction of a frame the "shutter" is open: 0.5 is film's 180°.
    float shutter;
    // Most faded copies behind one sprite.
    int maxTaps;
    // Widest gap between two copies, px. Smaller is smoother and costs taps.
    float tapSpacingPx;
    // Longest trail, as a share of the sprite's size, so a very fast scroll
    // stretches it into a streak rather than a second note.
    float maxTrailOfSprite;
    // Most extra copies in one frame across every sprite. A dense chart gets
    // shorter, coarser trails rather than a slower frame.
    int frameTapBudget;
    // How opaque the trail is right behind the sprite. The same at every
    // level, so quality changes grain and reach, never brightness.
    float edgeCoverage;
    // Widest lyric blur, px of Gaussian sigma.
    float maxSigmaPx;
};

// The table for levels 0..4; out-of-range levels are clamped.
const Quality& qualityFor(int level);

class Tracker {
public:
    // Seconds over which the speed follows a change: short enough that a
    // stop reads as a stop within a few frames, long enough that one late
    // frame does not double the smear.
    static constexpr float kVelocitySmoothingSeconds = 0.035f;
    static constexpr float kFrameSmoothingSeconds = 0.25f;
    // A gap longer than this is a pause, a hidden window or a stall: the
    // next frame starts from rest instead of averaging across the gap.
    static constexpr float kMaxGapSeconds = 0.25f;
    // The frame interval the exposure is worked out from, clamped to the
    // rates a phone actually draws at.
    static constexpr float kMinFrameSeconds = 1.0f / 240.0f;
    static constexpr float kMaxFrameSeconds = 1.0f / 30.0f;

    // Feed one frame: when it was drawn and where the thing is, px along its
    // axis (any origin; only the change counts). A move longer than
    // `jumpPx` in one frame is a jump: the speed drops to rest and the
    // position is taken as the new start. Returns the smoothed speed, px/s,
    // signed with the axis.
    float update(int64_t frameNanos, float positionPx, float jumpPx);

    void reset();

    float velocity() const { return velocity_; }
    // The smoothed time between frames, seconds; 1/60 until two have come.
    float frameSeconds() const { return frameSeconds_; }

private:
    bool hasSample_ = false;
    int64_t lastNanos_ = 0;
    float lastPosition_ = 0.0f;
    float velocity_ = 0.0f;
    float frameSeconds_ = 1.0f / 60.0f;
    bool frameMeasured_ = false;
};

struct Trail {
    // Copies to draw behind the sprite; 0 draws nothing extra.
    int taps = 0;
    // Distance between copies, px, along the motion; copy k (1..taps) sits
    // k * spacingPx behind the sprite.
    float spacingPx = 0.0f;
    // Alpha of every copy, before the sprite's own. Equal for all of them:
    // stacked behind the sprite they fade out by themselves, because the
    // farther from the sprite the fewer copies overlap there.
    float alpha = 0.0f;
    // Length of the smear, px: taps * spacingPx.
    float lengthPx = 0.0f;
};

// Smear below this is not worth a copy or a blur pass: it is under the
// sub-pixel wobble of the art itself.
constexpr float kMinTrailPx = 1.5f;

// How long the shutter is open for a frame of `frameSeconds`.
float exposureSeconds(const Quality& q, float frameSeconds);

// The trail for sprites `spriteSizePx` tall moving at `velocityPxPerSec`, with
// `sprites` of them on screen sharing the frame's budget.
Trail planTrail(const Quality& q, float velocityPxPerSec, float exposure, float spriteSizePx, int sprites);

// The Gaussian sigma, px, of a blur along the motion for a layer moving at
// `velocityPxPerSec`, rounded down to quarter pixels so a caller can keep one
// effect per step instead of building a new one each frame. 0 = no blur.
float blurSigma(const Quality& q, float velocityPxPerSec, float exposure);

}  // namespace tryptify::motion
