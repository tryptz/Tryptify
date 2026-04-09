#include <jni.h>
#include <string>
#include "dsp_engine.h"
#include <android/log.h>

#define LOG_TAG "MonochromeDSP_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline DspEngine* getEngine(jlong ptr) {
    return reinterpret_cast<DspEngine*>(ptr);
}

// ── Lifecycle ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeCreate(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint maxBlockSize) {
    auto* engine = new DspEngine(sampleRate, maxBlockSize);
    LOGD("nativeCreate: engine=%p, sr=%d, block=%d", engine, sampleRate, maxBlockSize);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (engine) {
        delete engine;
        LOGD("nativeDestroy: engine=%p", engine);
    }
}

// ── Audio processing ────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeProcess(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr,
    jfloatArray inputL, jfloatArray inputR,
    jfloatArray outputL, jfloatArray outputR,
    jint numFrames) {
    auto* engine = getEngine(enginePtr);
    if (!engine || numFrames <= 0) return;

    float* inL = env->GetFloatArrayElements(inputL, nullptr);
    float* inR = env->GetFloatArrayElements(inputR, nullptr);
    float* outL = env->GetFloatArrayElements(outputL, nullptr);
    float* outR = env->GetFloatArrayElements(outputR, nullptr);

    if (!inL || !inR || !outL || !outR) {
        if (inL)  env->ReleaseFloatArrayElements(inputL, inL, JNI_ABORT);
        if (inR)  env->ReleaseFloatArrayElements(inputR, inR, JNI_ABORT);
        if (outL) env->ReleaseFloatArrayElements(outputL, outL, JNI_ABORT);
        if (outR) env->ReleaseFloatArrayElements(outputR, outR, JNI_ABORT);
        return;
    }

    // Copy input to output buffers, then process in-place
    std::copy(inL, inL + numFrames, outL);
    std::copy(inR, inR + numFrames, outR);

    engine->process(outL, outR, numFrames);

    env->ReleaseFloatArrayElements(inputL, inL, JNI_ABORT);
    env->ReleaseFloatArrayElements(inputR, inR, JNI_ABORT);
    env->ReleaseFloatArrayElements(outputL, outL, 0);  // commit changes
    env->ReleaseFloatArrayElements(outputR, outR, 0);
}

// ── Bus configuration ───────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusGain(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jfloat gainDb) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setBusGain(busIndex, gainDb);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusPan(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jfloat pan) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setBusPan(busIndex, pan);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusMute(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jboolean muted) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setBusMute(busIndex, muted);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusSolo(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jboolean soloed) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setBusSolo(busIndex, soloed);
}

// ── Plugin chain management ─────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeAddPlugin(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jint pluginType) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return -1;
    return engine->addPlugin(busIndex, slotIndex, pluginType);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeRemovePlugin(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->removePlugin(busIndex, slotIndex);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeMovePlugin(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint fromSlot, jint toSlot) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->movePlugin(busIndex, fromSlot, toSlot);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetParameter(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jint paramIndex, jfloat value) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setParameter(busIndex, slotIndex, paramIndex, value);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetPluginBypassed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jboolean bypassed) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setPluginBypassed(busIndex, slotIndex, bypassed);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusInputEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jboolean enabled) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setBusInputEnabled(busIndex, enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetPluginDryWet(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jfloat dryWet) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setPluginDryWet(busIndex, slotIndex, dryWet);
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetMixBypassed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jboolean bypassed) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setMixBypassed(bypassed);
}

// ── Metering ────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeGetBusLevels(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jfloatArray outLevels) {
    auto* engine = getEngine(enginePtr);
    if (!engine || !outLevels) return;
    int len = env->GetArrayLength(outLevels);
    float* arr = env->GetFloatArrayElements(outLevels, nullptr);
    if (arr) {
        engine->getBusLevels(arr, len);
        env->ReleaseFloatArrayElements(outLevels, arr, 0);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeResetPluginState(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->resetPluginState();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeGetAndResetClipped(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return false;
    return engine->getAndResetClipped();
}

// ── State serialization ─────────────────────────────────────────────────

extern "C" JNIEXPORT jstring JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeGetStateJson(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return env->NewStringUTF("{}");
    std::string json = engine->getStateJson();
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeLoadStateJson(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jstring stateJson) {
    auto* engine = getEngine(enginePtr);
    if (!engine || !stateJson) return;
    const char* json = env->GetStringUTFChars(stateJson, nullptr);
    if (json) {
        engine->loadStateJson(std::string(json));
        env->ReleaseStringUTFChars(stateJson, json);
    }
}
