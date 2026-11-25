// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI for HeadphoneRoom (headphone_room.h), behind
// tf.monochrome.android.audio.dsp.crossfeed.HeadphoneRoomNative: the Crossfeed
// page's Room. The player hands it the two channels as float arrays, as it
// hands them to crossfeed.

#include <jni.h>

#include <vector>

#include "headphone_room.h"

namespace {
HeadphoneRoom* room(jlong h) { return reinterpret_cast<HeadphoneRoom*>(h); }
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new HeadphoneRoom());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete room(h);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativePrepare(
        JNIEnv*, jclass, jlong h, jint sampleRate, jint maxBlockSize) {
    if (h != 0 && sampleRate > 0) room(h)->prepare(static_cast<double>(sampleRate), maxBlockSize);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativeSetParameter(
        JNIEnv*, jclass, jlong h, jint index, jfloat value) {
    if (h != 0) room(h)->setParameter(index, value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativeSetSofa(
        JNIEnv* env, jclass, jlong h, jfloatArray packed) {
    if (h == 0) return JNI_FALSE;
    if (packed == nullptr) {
        room(h)->clearSofaSet();
        return JNI_TRUE;
    }
    const jsize n = env->GetArrayLength(packed);
    if (n <= 0) return JNI_FALSE;
    std::vector<float> buf(static_cast<size_t>(n));
    env->GetFloatArrayRegion(packed, 0, n, buf.data());
    return room(h)->setSofaSet(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativeLatency(JNIEnv*, jclass, jlong h) {
    return h != 0 ? room(h)->latency() : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativeReset(JNIEnv*, jclass, jlong h) {
    if (h != 0) room(h)->reset();
}

// The two channels, [frames] long, in place. Audio thread.
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_crossfeed_HeadphoneRoomNative_nativeProcess(
        JNIEnv* env, jclass, jlong h, jfloatArray left, jfloatArray right, jint frames) {
    if (h == 0 || left == nullptr || right == nullptr || frames <= 0) return;
    if (env->GetArrayLength(left) < frames || env->GetArrayLength(right) < frames) return;
    auto* l = static_cast<float*>(env->GetPrimitiveArrayCritical(left, nullptr));
    if (l == nullptr) return;
    auto* r = static_cast<float*>(env->GetPrimitiveArrayCritical(right, nullptr));
    if (r == nullptr) {
        env->ReleasePrimitiveArrayCritical(left, l, JNI_ABORT);
        return;
    }
    room(h)->process(l, r, frames);
    env->ReleasePrimitiveArrayCritical(right, r, 0);
    env->ReleasePrimitiveArrayCritical(left, l, 0);
}
