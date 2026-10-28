// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI for ReplayGainStage (replay_gain.h), behind
// tf.monochrome.android.audio.replaygain.ReplayGainNative. Buffers are direct
// ByteBuffers in native order, as Media3 hands them over; the Kotlin side
// passes whole frames only.

#include <jni.h>

#include <cstdint>

#include "replay_gain.h"

using trypt::dsp::ReplayGainStage;

namespace {
ReplayGainStage* stage(jlong h) { return reinterpret_cast<ReplayGainStage*>(h); }
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new ReplayGainStage());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete stage(h);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativePrepare(
        JNIEnv*, jclass, jlong h, jint sampleRate, jint channels) {
    if (h != 0) stage(h)->prepare(sampleRate, channels);
}

/** Below this, [followLufs] means "not following": a sentinel, not NaN, as the library builds with -ffast-math. */
constexpr float kFollowOff = -100.0f;

namespace {
/** The song's mode, before anything is worked from it. */
void apply(ReplayGainStage* s, float gain, float followLufs, jint song) {
    s->setGain(gain);
    if (followLufs > kFollowOff) {
        s->follow(static_cast<uint32_t>(song), followLufs);
    } else {
        s->fixed();
    }
}
}  // namespace

/** The start of a stream, in the mode of the song it starts: see ReplayGainStage::reset(). */
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativeReset(
        JNIEnv*, jclass, jlong h, jfloat gain, jfloat followLufs, jint song) {
    if (h == 0) return;
    apply(stage(h), gain, followLufs, song);
    stage(h)->reset();
}

/**
 * [frames] frames from [in] at byte [inOffset] to the start of [out]. With
 * [followLufs] a target (above kFollowOff), the song [song] is measured and
 * turned down to it; otherwise it plays at [gain]. Returns the gain the stage
 * is at after the buffer, or -1 when a buffer is not direct and nothing was
 * written.
 */
extern "C" JNIEXPORT jfloat JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativeProcess(
        JNIEnv* env, jclass, jlong h, jobject in, jint inOffset, jobject out, jint frames, jboolean isFloat,
        jfloat gain, jfloat followLufs, jint song) {
    if (h == 0) return 1.0f;
    ReplayGainStage* s = stage(h);
    if (frames <= 0) return s->current();
    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(in));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(out));
    if (src == nullptr || dst == nullptr) return -1.0f;
    src += inOffset;
    apply(s, gain, followLufs, song);
    if (isFloat) {
        s->processFloat(reinterpret_cast<const float*>(src), reinterpret_cast<float*>(dst), frames);
    } else {
        s->processInt16(reinterpret_cast<const int16_t*>(src), reinterpret_cast<int16_t*>(dst), frames);
    }
    return s->current();
}

/** The following song's integrated loudness so far, LUFS; below -999 with no reading. */
extern "C" JNIEXPORT jfloat JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativeMeasuredLufs(JNIEnv*, jclass, jlong h) {
    return h == 0 ? -1000.0f : stage(h)->measuredLufs();
}
