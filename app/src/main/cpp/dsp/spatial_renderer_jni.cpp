#include <jni.h>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include "spatial_renderer.h"

#define LOG_TAG "SpatialRendererJni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static inline SpatialRenderer* getRenderer(jlong ptr) {
    return reinterpret_cast<SpatialRenderer*>(ptr);
}

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_SpatialAudioProcessor_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jobject assetManager, jint sampleRate, jint maxBlockSize) {
    AAssetManager* am = AAssetManager_fromJava(env, assetManager);
    auto* renderer = new SpatialRenderer(am, sampleRate, maxBlockSize);
    LOGD("nativeCreate: renderer=%p, sr=%d, block=%d", renderer, sampleRate, maxBlockSize);
    return reinterpret_cast<jlong>(renderer);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_SpatialAudioProcessor_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto* renderer = getRenderer(ptr);
    if (renderer) {
        delete renderer;
        LOGD("nativeDestroy: renderer=%p", renderer);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_SpatialAudioProcessor_nativeProcess(
    JNIEnv* env, jobject /*thiz*/, jlong ptr,
    jfloatArray input, jint inputChannels,
    jfloatArray outputL, jfloatArray outputR,
    jint numFrames) {
    
    auto* renderer = getRenderer(ptr);
    if (!renderer || numFrames <= 0) return;

    float* inBuf = env->GetFloatArrayElements(input, nullptr);
    float* outL = env->GetFloatArrayElements(outputL, nullptr);
    float* outR = env->GetFloatArrayElements(outputR, nullptr);

    if (!inBuf || !outL || !outR) {
        if (inBuf) env->ReleaseFloatArrayElements(input, inBuf, JNI_ABORT);
        if (outL)  env->ReleaseFloatArrayElements(outputL, outL, JNI_ABORT);
        if (outR)  env->ReleaseFloatArrayElements(outputR, outR, JNI_ABORT);
        return;
    }

    renderer->process(inBuf, inputChannels, outL, outR, numFrames);

    env->ReleaseFloatArrayElements(input, inBuf, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputL, outL, 0);
    env->ReleaseFloatArrayElements(outputR, outR, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_SpatialAudioProcessor_nativeSetEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jboolean enabled) {
    auto* renderer = getRenderer(ptr);
    if (renderer) renderer->setEnabled(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_SpatialAudioProcessor_nativeSetRoomMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jint mode) {
    auto* renderer = getRenderer(ptr);
    if (renderer) renderer->setRoomMode(mode);
}
