// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI surface for the motion blur. Mirrors MotionBlurNative in MotionBlur.kt.
//
// One call a frame per tracker: nativeStep feeds the frame and writes the
// whole answer into a FloatArray the caller allocated once, so a frame costs
// one JNI transition and allocates nothing on either side. The slots below
// are duplicated in MotionBlurNative and must change with them.

#include <jni.h>

#include <new>

#include "motion_blur.h"

using tryptify::motion::Tracker;

namespace {

// nativeStep's output slots.
constexpr int kOutTaps = 0;
constexpr int kOutSpacing = 1;
constexpr int kOutAlpha = 2;
constexpr int kOutSigma = 3;
constexpr int kOutVelocity = 4;
constexpr int kOutCount = 5;

Tracker* tracker(jlong ptr) { return reinterpret_cast<Tracker*>(ptr); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_motion_MotionBlurNative_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) Tracker());
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_motion_MotionBlurNative_nativeDestroy(JNIEnv*, jobject, jlong ptr) {
    delete tracker(ptr);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_motion_MotionBlurNative_nativeReset(JNIEnv*, jobject, jlong ptr) {
    if (ptr != 0) tracker(ptr)->reset();
}

// filmExposure: expose for the slowest frame (1/30 s) rather than the one
// just drawn. A lyric glide is slow next to a note, and at 120 Hz its smear
// would be under a pixel; this keeps it the same at every refresh rate.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_motion_MotionBlurNative_nativeStep(
        JNIEnv* env, jobject, jlong ptr, jlong frameNanos, jfloat positionPx, jfloat jumpPx,
        jint quality, jfloat spriteSizePx, jint sprites, jboolean filmExposure, jfloatArray out) {
    if (ptr == 0 || out == nullptr || env->GetArrayLength(out) < kOutCount) return;
    Tracker* t = tracker(ptr);
    const float velocity = t->update(frameNanos, positionPx, jumpPx);
    const auto& q = tryptify::motion::qualityFor(quality);
    const float frame = filmExposure ? Tracker::kMaxFrameSeconds : t->frameSeconds();
    const float exposure = tryptify::motion::exposureSeconds(q, frame);
    const auto trail = tryptify::motion::planTrail(q, velocity, exposure, spriteSizePx, sprites);

    jfloat values[kOutCount];
    values[kOutTaps] = static_cast<jfloat>(trail.taps);
    values[kOutSpacing] = trail.spacingPx;
    values[kOutAlpha] = trail.alpha;
    values[kOutSigma] = tryptify::motion::blurSigma(q, velocity, exposure);
    values[kOutVelocity] = velocity;
    env->SetFloatArrayRegion(out, 0, kOutCount, values);
}

}  // extern "C"
