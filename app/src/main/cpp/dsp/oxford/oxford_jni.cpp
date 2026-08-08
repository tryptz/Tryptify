// SPDX-License-Identifier: GPL-3.0-or-later
// JNI bridge for oxford_dsp (Inflator + Compressor).
//
// Matches:
//   tf.monochrome.android.audio.dsp.oxford.InflatorNative
//   tf.monochrome.android.audio.dsp.oxford.CompressorNative
//
// Two process entry points are offered:
//   nativeProcess       — direct ByteBuffer, planar [L..., R...] float32
//   nativeProcessArrays — split L/R Java FloatArrays, matches MixBusProcessor

#include <jni.h>
#include <cstdint>
#include <cstring>
#include "oxford_dsp.h"

using trypt::dsp::InflatorProcessor;
using trypt::dsp::CompressorProcessor;

namespace {
template <typename T>
inline T* asPtr(jlong h) { return reinterpret_cast<T*>(static_cast<uintptr_t>(h)); }
} // anon

// ---- Inflator -------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new InflatorProcessor());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete asPtr<InflatorProcessor>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativePrepare(
        JNIEnv*, jclass, jlong h, jdouble sr, jint ch) {
    asPtr<InflatorProcessor>(h)->prepare(sr, ch);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeSetParams(
        JNIEnv*, jclass, jlong h,
        jfloat inputDb, jfloat outputDb, jfloat effect, jfloat curve,
        jboolean clipZeroDb, jboolean bandSplit, jboolean bypass) {
    auto* p = asPtr<InflatorProcessor>(h);
    p->params.inputDb   .store(inputDb);
    p->params.outputDb  .store(outputDb);
    p->params.effect    .store(effect);
    p->params.curve     .store(curve);
    p->params.clipZeroDb.store(clipZeroDb == JNI_TRUE);
    p->params.bandSplit .store(bandSplit  == JNI_TRUE);
    p->params.bypass    .store(bypass     == JNI_TRUE);
}

// Anti-alias oversampling for the waveshaper: 1 (off), 2, or 4. Separate from
// nativeSetParams because it's an independent atomic the audio thread picks up
// on its next block, and keeping it out of the params push avoids reshuffling
// that signature.
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeSetOversampling(
        JNIEnv*, jclass, jlong h, jint factor) {
    auto* p = asPtr<InflatorProcessor>(h);
    p->params.oversampling.store(factor);
}

// Packed: top 32 bits peakL, low 32 bits peakR (raw float bits).
extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeReadMeters(
        JNIEnv*, jclass, jlong h) {
    auto* p = asPtr<InflatorProcessor>(h);
    float l = p->params.peakL.load(std::memory_order_relaxed);
    float r = p->params.peakR.load(std::memory_order_relaxed);
    uint32_t li, ri;
    std::memcpy(&li, &l, 4);
    std::memcpy(&ri, &r, 4);
    return (static_cast<jlong>(li) << 32) | static_cast<jlong>(ri);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeProcess(
        JNIEnv* env, jclass, jlong h, jobject buf, jint frames, jint channels) {
    auto* base = static_cast<float*>(env->GetDirectBufferAddress(buf));
    if (!base) return;
    float* planes[2];
    planes[0] = base;
    planes[1] = (channels > 1) ? base + frames : base;
    asPtr<InflatorProcessor>(h)->process(planes, frames);
}

// FloatArray-based entry to match MixBusProcessor's deinterleaved call site.
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_InflatorNative_nativeProcessArrays(
        JNIEnv* env, jclass, jlong h, jfloatArray l, jfloatArray r, jint frames) {
    if (frames <= 0) return;
    auto* p = asPtr<InflatorProcessor>(h);
    if (!p) return;
    float* lp = env->GetFloatArrayElements(l, nullptr);
    float* rp = env->GetFloatArrayElements(r, nullptr);
    if (!lp || !rp) {
        if (lp) env->ReleaseFloatArrayElements(l, lp, JNI_ABORT);
        if (rp) env->ReleaseFloatArrayElements(r, rp, JNI_ABORT);
        return;
    }
    float* planes[2] = { lp, rp };
    p->process(planes, frames);
    env->ReleaseFloatArrayElements(l, lp, 0);  // commit
    env->ReleaseFloatArrayElements(r, rp, 0);
}

// ---- Compressor -----------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new CompressorProcessor());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete asPtr<CompressorProcessor>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativePrepare(
        JNIEnv*, jclass, jlong h, jdouble sr, jint ch) {
    asPtr<CompressorProcessor>(h)->prepare(sr, ch);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeSetParams(
        JNIEnv*, jclass, jlong h,
        jfloat thresholdDb, jfloat ratio, jfloat attackMs, jfloat releaseMs,
        jfloat kneeDb, jfloat makeupDb, jboolean bypass) {
    auto* p = asPtr<CompressorProcessor>(h);
    p->params.thresholdDb.store(thresholdDb);
    p->params.ratio      .store(ratio);
    p->params.attackMs   .store(attackMs);
    p->params.releaseMs  .store(releaseMs);
    p->params.kneeDb     .store(kneeDb);
    p->params.makeupDb   .store(makeupDb);
    p->params.bypass     .store(bypass == JNI_TRUE);
}

// Anti-alias oversampling for the detector + gain stage: 1, 2 or 4.
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeSetOversampling(
        JNIEnv*, jclass, jlong h, jint factor) {
    asPtr<CompressorProcessor>(h)->params.oversampling.store(factor);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeGainReductionDb(
        JNIEnv*, jclass, jlong h) {
    return asPtr<CompressorProcessor>(h)->params.gainReductionDb.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeReadMeters(
        JNIEnv*, jclass, jlong h) {
    auto* p = asPtr<CompressorProcessor>(h);
    float l = p->params.peakL.load(std::memory_order_relaxed);
    float r = p->params.peakR.load(std::memory_order_relaxed);
    uint32_t li, ri;
    std::memcpy(&li, &l, 4);
    std::memcpy(&ri, &r, 4);
    return (static_cast<jlong>(li) << 32) | static_cast<jlong>(ri);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeProcess(
        JNIEnv* env, jclass, jlong h, jobject buf, jint frames, jint channels) {
    auto* base = static_cast<float*>(env->GetDirectBufferAddress(buf));
    if (!base) return;
    float* planes[2];
    planes[0] = base;
    planes[1] = (channels > 1) ? base + frames : base;
    asPtr<CompressorProcessor>(h)->process(planes, frames);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_oxford_CompressorNative_nativeProcessArrays(
        JNIEnv* env, jclass, jlong h, jfloatArray l, jfloatArray r, jint frames) {
    if (frames <= 0) return;
    auto* p = asPtr<CompressorProcessor>(h);
    if (!p) return;
    float* lp = env->GetFloatArrayElements(l, nullptr);
    float* rp = env->GetFloatArrayElements(r, nullptr);
    if (!lp || !rp) {
        if (lp) env->ReleaseFloatArrayElements(l, lp, JNI_ABORT);
        if (rp) env->ReleaseFloatArrayElements(r, rp, JNI_ABORT);
        return;
    }
    float* planes[2] = { lp, rp };
    p->process(planes, frames);
    env->ReleaseFloatArrayElements(l, lp, 0);
    env->ReleaseFloatArrayElements(r, rp, 0);
}
