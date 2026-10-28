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

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativeReset(JNIEnv*, jclass, jlong h, jfloat gain) {
    if (h == 0) return;
    stage(h)->setGain(gain);
    stage(h)->reset();
}

/**
 * [frames] frames from [in] at byte [inOffset] to the start of [out], at
 * [gain]. False when a buffer is not direct, and nothing is written.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_replaygain_ReplayGainNative_nativeProcess(
        JNIEnv* env, jclass, jlong h, jobject in, jint inOffset, jobject out, jint frames, jboolean isFloat, jfloat gain) {
    if (h == 0 || frames <= 0) return JNI_TRUE;
    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(in));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(out));
    if (src == nullptr || dst == nullptr) return JNI_FALSE;
    src += inOffset;
    ReplayGainStage* s = stage(h);
    s->setGain(gain);
    if (isFloat) {
        s->processFloat(reinterpret_cast<const float*>(src), reinterpret_cast<float*>(dst), frames);
    } else {
        s->processInt16(reinterpret_cast<const int16_t*>(src), reinterpret_cast<int16_t*>(dst), frames);
    }
    return JNI_TRUE;
}
