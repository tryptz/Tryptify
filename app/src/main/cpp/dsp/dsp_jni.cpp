#include <jni.h>
#include <string>
#include "dsp_engine.h"
#include "multilane_engine.h"
#include <android/log.h>

#define LOG_TAG "MonochromeDSP_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// The handle Kotlin holds is a MultiLaneEngine: one DspEngine per lane of the
// stream's channel layout, lane 0 being the one a stereo stream has always
// used. Setters reach every lane; readers (meters, scopes, saved state) read
// lane 0, the front pair.
static inline MultiLaneEngine* getEngine(jlong ptr) {
    return reinterpret_cast<MultiLaneEngine*>(ptr);
}

// ── Lifecycle ───────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeCreate(
    JNIEnv* /*env*/, jobject /*thiz*/, jint sampleRate, jint maxBlockSize) {
    auto* engine = new MultiLaneEngine(sampleRate, maxBlockSize);
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

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeReconfigure(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint sampleRate, jint maxBlockSize) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->reconfigure(sampleRate, maxBlockSize);
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

    engine->primary().process(outL, outR, numFrames);

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
    if (engine) engine->forEach([&](DspEngine& e) { e.setBusGain(busIndex, gainDb); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusPan(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jfloat pan) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setBusPan(busIndex, pan); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusMute(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jboolean muted) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setBusMute(busIndex, muted); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusSolo(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jboolean soloed) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setBusSolo(busIndex, soloed); });
}

// ── Plugin chain management ─────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeAddPlugin(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jint pluginType) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return -1;
    // Every lane gets the plugin; lane 0's slot is the one reported back.
    int result = -1;
    bool first = true;
    engine->forEach([&](DspEngine& e) {
        const int idx = e.addPlugin(busIndex, slotIndex, pluginType);
        if (first) { result = idx; first = false; }
    });
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeRemovePlugin(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.removePlugin(busIndex, slotIndex); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeMovePlugin(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint fromSlot, jint toSlot) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.movePlugin(busIndex, fromSlot, toSlot); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetParameter(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jint paramIndex, jfloat value) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setParameter(busIndex, slotIndex, paramIndex, value); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetPluginBypassed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jboolean bypassed) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setPluginBypassed(busIndex, slotIndex, bypassed); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetBusInputEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jboolean enabled) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setBusInputEnabled(busIndex, enabled); });
}

// Every lane carries the same graph, so a route is set on all of them; the
// primary's answer (refused when it would loop) speaks for the rest.
extern "C" JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetSend(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint srcBus, jint dstBus, jfloat level) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return JNI_FALSE;
    bool result = false;
    bool first = true;
    engine->forEach([&](DspEngine& e) {
        const bool ok = e.setSend(srcBus, dstBus, level);
        if (first) { result = ok; first = false; }
    });
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetPluginDryWet(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr,
    jint busIndex, jint slotIndex, jfloat dryWet) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setPluginDryWet(busIndex, slotIndex, dryWet); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetMixBypassed(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jboolean bypassed) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setMixBypassed(bypassed); });
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
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetPluginOversampling(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex, jint slotIndex, jint factor) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.setPluginOversampling(busIndex, slotIndex, factor); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeGetPluginMeters(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jint busIndex, jfloatArray outMeters) {
    auto* engine = getEngine(enginePtr);
    if (!engine || !outMeters) return;
    int len = env->GetArrayLength(outMeters);
    float* arr = env->GetFloatArrayElements(outMeters, nullptr);
    if (arr) {
        engine->withBusLane(busIndex, [&](DspEngine& e) { e.getPluginMeters(busIndex, arr, len); });
        env->ReleaseFloatArrayElements(outMeters, arr, 0);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeGetBusWaveform(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jint busIndex, jfloatArray outWave) {
    auto* engine = getEngine(enginePtr);
    if (!engine || !outWave) return 0;
    int len = env->GetArrayLength(outWave);
    float* arr = env->GetFloatArrayElements(outWave, nullptr);
    if (!arr) return 0;
    int written = engine->withBusLane(busIndex, [&](DspEngine& e) { return e.getBusWaveform(busIndex, arr, len); });
    env->ReleaseFloatArrayElements(outWave, arr, 0);
    return written;
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeResetMeters(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([](DspEngine& e) { e.resetMeters(); });
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeResetPluginState(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->forEach([&](DspEngine& e) { e.resetPluginState(); });
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
    std::string json = engine->primary().getStateJson();
    return env->NewStringUTF(json.c_str());
}

/**
 * The state as the engine runs it right now, including the buses a
 * multichannel stream grew and nobody has touched (nativeGetStateJson leaves
 * those out, being the form that is saved). For the UI's mirror of the mixer.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeGetLiveStateJson(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return env->NewStringUTF("{}");
    std::string json = engine->primary().getStateJson(/* full = */ true);
    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeLoadStateJson(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jstring stateJson) {
    auto* engine = getEngine(enginePtr);
    if (!engine || !stateJson) return;
    const char* json = env->GetStringUTFChars(stateJson, nullptr);
    if (json) {
        const std::string state(json);
        engine->forEach([&](DspEngine& e) { e.loadStateJson(state); });
        env->ReleaseStringUTFChars(stateJson, json);
    }
}

// ── Adding and removing buses ───────────────────────────────────────────

/** Adds a mix bus on every lane; returns lane 0's index for it, or -1 at the limit. */
extern "C" JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeAddBus(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return -1;
    int result = -1;
    bool first = true;
    engine->forEach([&](DspEngine& e) {
        const int idx = e.addBus();
        if (first) { result = idx; first = false; }
    });
    return result;
}

/** Removes mix bus [busIndex] on every lane; buses above it move down one. */
extern "C" JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeRemoveBus(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jint busIndex) {
    auto* engine = getEngine(enginePtr);
    if (!engine) return JNI_FALSE;
    bool removed = false;
    bool first = true;
    engine->forEach([&](DspEngine& e) {
        const bool ok = e.removeBus(busIndex);
        if (first) { removed = ok; first = false; }
    });
    return removed ? JNI_TRUE : JNI_FALSE;
}

// ── Multichannel ────────────────────────────────────────────────────────

/**
 * Sets the lanes for the next stream: lane k is channel first[k], plus
 * second[k] unless that is negative. From the playback thread, like process.
 */
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeConfigureLanes(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jintArray first, jintArray second) {
    auto* engine = getEngine(enginePtr);
    if (!engine || !first || !second) return;
    const int count = std::min(env->GetArrayLength(first), env->GetArrayLength(second));
    if (count <= 0 || count > MultiLaneEngine::MAX_LANES) return;
    jint a[MultiLaneEngine::MAX_LANES];
    jint b[MultiLaneEngine::MAX_LANES];
    env->GetIntArrayRegion(first, 0, count, a);
    env->GetIntArrayRegion(second, 0, count, b);
    int fa[MultiLaneEngine::MAX_LANES];
    int fb[MultiLaneEngine::MAX_LANES];
    for (int k = 0; k < count; k++) { fa[k] = a[k]; fb[k] = b[k]; }
    engine->configureLanes(fa, fb, count);
}

/** Spread a wide stream one channel group per bus, or run it whole. */
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeSetSpreadChannels(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong enginePtr, jboolean spread) {
    auto* engine = getEngine(enginePtr);
    if (engine) engine->setSpread(spread);
}

/**
 * Processes planar float channels in place, in a direct buffer: channel c at
 * byte offset c * stride * 4, [numFrames] samples each. Direct, so nothing is
 * copied or allocated per block.
 */
extern "C" JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_dsp_MixBusProcessor_nativeProcessPlanar(
    JNIEnv* env, jobject /*thiz*/, jlong enginePtr, jobject planar,
    jint numChannels, jint stride, jint numFrames) {
    auto* engine = getEngine(enginePtr);
    if (!engine || !planar || numFrames <= 0 || numFrames > stride) return;
    if (numChannels < 1 || numChannels > 2 * MultiLaneEngine::MAX_LANES) return;
    auto* base = static_cast<float*>(env->GetDirectBufferAddress(planar));
    if (!base) return;
    const jlong capacity = env->GetDirectBufferCapacity(planar);
    if (capacity < static_cast<jlong>(numChannels) * stride * 4) return;
    float* channels[2 * MultiLaneEngine::MAX_LANES];
    for (int c = 0; c < numChannels; c++) channels[c] = base + static_cast<size_t>(c) * stride;
    engine->processPlanar(channels, numChannels, numFrames);
}
