// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI surface for the pattern looper. Mirrors NativeSamplerBridge.kt.
//
// Two rules shape everything here. Nothing on this side may block the audio
// thread, so the render entry point does no JNI work beyond pinning the two
// output arrays. And nothing may allocate on it either, which is why patterns
// and samples arrive through their own upload calls (loader thread, under the
// engine's mutex) rather than being marshalled per block.

#include <jni.h>

#include <android/log.h>

#include <memory>
#include <vector>

#include "pattern_engine.h"

#define LOG_TAG "TryptifySampler"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using tryptify::ChannelState;
using tryptify::Command;
using tryptify::PatternEngine;
using tryptify::PatternState;
using tryptify::SampleData;
using tryptify::StepData;

namespace {

inline PatternEngine* engineOf(jlong ptr) {
    return reinterpret_cast<PatternEngine*>(ptr);
}

}  // namespace

extern "C" {

// ── lifecycle ───────────────────────────────────────────────────────────

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeCreate(
    JNIEnv*, jobject, jint sampleRate) {
    auto* engine = new PatternEngine(sampleRate);
    LOGD("pattern engine created: %p @ %d Hz", engine, sampleRate);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeDestroy(
    JNIEnv*, jobject, jlong ptr) {
    delete engineOf(ptr);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativePrepare(
    JNIEnv*, jobject, jlong ptr, jint sampleRate) {
    auto* engine = engineOf(ptr);
    if (engine != nullptr) engine->prepare(sampleRate);
}

// ── render ──────────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeRender(
    JNIEnv* env, jobject, jlong ptr, jfloatArray outL, jfloatArray outR, jint frames,
    jboolean clearFirst) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr || frames <= 0) return;

    // GetPrimitiveArrayCritical rather than GetFloatArrayElements: the latter
    // is free to copy the whole buffer in and out on every audio callback,
    // which is exactly the kind of hidden allocation the render path must not
    // have. The critical section holds no locks and calls no JNI.
    auto* left = static_cast<float*>(env->GetPrimitiveArrayCritical(outL, nullptr));
    auto* right = static_cast<float*>(env->GetPrimitiveArrayCritical(outR, nullptr));
    if (left != nullptr && right != nullptr) {
        engine->render(left, right, frames, clearFirst == JNI_TRUE);
    }
    if (right != nullptr) env->ReleasePrimitiveArrayCritical(outR, right, 0);
    if (left != nullptr) env->ReleasePrimitiveArrayCritical(outL, left, 0);
}

// ── commands ────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativePostCommand(
    JNIEnv*, jobject, jlong ptr, jint type, jint a, jint b, jint c, jint d, jfloat f) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr) return JNI_FALSE;
    Command cmd;
    cmd.type = type;
    cmd.a = a;
    cmd.b = b;
    cmd.c = c;
    cmd.d = d;
    cmd.f = f;
    return engine->post(cmd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativePostStep(
    JNIEnv*, jobject, jlong ptr, jint patternIndex, jint channel, jint stepIndex,
    jint enabled, jint velocity, jint pitch, jint probability, jint pan, jint sampleStart,
    jint locks) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr) return JNI_FALSE;
    Command cmd;
    cmd.type = tryptify::kCmdSetStep;
    cmd.a = patternIndex;
    cmd.b = channel;
    cmd.c = stepIndex;
    cmd.step.enabled = static_cast<uint8_t>(enabled != 0 ? 1 : 0);
    cmd.step.velocity = static_cast<uint8_t>(velocity);
    cmd.step.pitch = static_cast<int8_t>(pitch);
    cmd.step.probability = static_cast<uint8_t>(probability);
    cmd.step.pan = static_cast<int8_t>(pan);
    cmd.step.sampleStart = static_cast<uint8_t>(sampleStart);
    cmd.step.locks = static_cast<uint8_t>(locks);
    return engine->post(cmd) ? JNI_TRUE : JNI_FALSE;
}

// ── pattern upload ──────────────────────────────────────────────────────

/**
 * Publishes one pattern. Layout, all of it fixed-stride so neither side needs
 * a parser:
 *
 *   header       [lengthSteps, stepsPerBeat, beatsPerBar, channelCount]
 *   channelInts  channelCount × [sampleSlot, muted, soloed, enabled, reverse]
 *   channelFloats channelCount × [volume, pan, pitch, start, end, attack,
 *                                 release, filterHz]
 *   steps        channelCount × kMaxSteps × 8 bytes, StepData field order
 */
JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeUploadPattern(
    JNIEnv* env, jobject, jlong ptr, jint index, jintArray header, jfloat bpmOverride,
    jintArray channelInts, jfloatArray channelFloats, jbyteArray steps) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr) return JNI_FALSE;

    jint head[4] = {16, 4, 4, 0};
    if (env->GetArrayLength(header) < 4) return JNI_FALSE;
    env->GetIntArrayRegion(header, 0, 4, head);

    const int channelCount = head[3] < 0 ? 0 : (head[3] > tryptify::kMaxChannels
                                                    ? tryptify::kMaxChannels
                                                    : head[3]);

    if (env->GetArrayLength(channelInts) < channelCount * 5 ||
        env->GetArrayLength(channelFloats) < channelCount * 8 ||
        env->GetArrayLength(steps) < channelCount * tryptify::kMaxSteps * 8) {
        LOGE("nativeUploadPattern: short arrays for %d channels", channelCount);
        return JNI_FALSE;
    }

    PatternState state;
    state.lengthSteps = head[0];
    state.stepsPerBeat = head[1];
    state.beatsPerBar = head[2];
    state.channelCount = channelCount;
    state.bpmOverride = bpmOverride;

    std::vector<jint> ints(static_cast<size_t>(channelCount) * 5);
    std::vector<jfloat> floats(static_cast<size_t>(channelCount) * 8);
    std::vector<jbyte> stepBytes(static_cast<size_t>(channelCount) * tryptify::kMaxSteps * 8);
    if (channelCount > 0) {
        env->GetIntArrayRegion(channelInts, 0, static_cast<jsize>(ints.size()), ints.data());
        env->GetFloatArrayRegion(channelFloats, 0, static_cast<jsize>(floats.size()), floats.data());
        env->GetByteArrayRegion(steps, 0, static_cast<jsize>(stepBytes.size()), stepBytes.data());
    }

    for (int c = 0; c < channelCount; ++c) {
        ChannelState& channel = state.channels[c];
        const size_t i = static_cast<size_t>(c) * 5;
        channel.sampleSlot = ints[i + 0];
        channel.muted = static_cast<uint8_t>(ints[i + 1] != 0 ? 1 : 0);
        channel.soloed = static_cast<uint8_t>(ints[i + 2] != 0 ? 1 : 0);
        channel.enabled = static_cast<uint8_t>(ints[i + 3] != 0 ? 1 : 0);
        channel.reverse = static_cast<uint8_t>(ints[i + 4] != 0 ? 1 : 0);

        const size_t f = static_cast<size_t>(c) * 8;
        channel.volume = floats[f + 0];
        channel.pan = floats[f + 1];
        channel.pitch = floats[f + 2];
        channel.sampleStart = floats[f + 3];
        channel.sampleEnd = floats[f + 4];
        channel.attackMs = floats[f + 5];
        channel.releaseMs = floats[f + 6];
        channel.filterHz = floats[f + 7];

        for (int s = 0; s < tryptify::kMaxSteps; ++s) {
            const size_t b = (static_cast<size_t>(c) * tryptify::kMaxSteps + s) * 8;
            StepData& step = channel.steps[s];
            step.enabled = static_cast<uint8_t>(stepBytes[b + 0]);
            step.velocity = static_cast<uint8_t>(stepBytes[b + 1]);
            step.pitch = static_cast<int8_t>(stepBytes[b + 2]);
            step.probability = static_cast<uint8_t>(stepBytes[b + 3]);
            step.pan = static_cast<int8_t>(stepBytes[b + 4]);
            step.sampleStart = static_cast<uint8_t>(stepBytes[b + 5]);
            step.locks = static_cast<uint8_t>(stepBytes[b + 6]);
            step.reserved = 0;
        }
    }

    return engine->uploadPattern(index, state) ? JNI_TRUE : JNI_FALSE;
}

// ── samples ─────────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativePutSample(
    JNIEnv* env, jobject, jlong ptr, jint slot, jfloatArray left, jfloatArray right,
    jint frames, jint sampleRate, jfloat gain) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr || frames <= 0 || left == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(left) < frames) return JNI_FALSE;

    auto data = std::make_shared<SampleData>();
    data->frames = frames;
    data->sampleRate = sampleRate > 0 ? sampleRate : 48000;
    data->gain = gain;
    data->left.resize(static_cast<size_t>(frames));
    env->GetFloatArrayRegion(left, 0, frames, data->left.data());
    if (right != nullptr && env->GetArrayLength(right) >= frames) {
        data->right.resize(static_cast<size_t>(frames));
        env->GetFloatArrayRegion(right, 0, frames, data->right.data());
    }
    return engine->samples().put(slot, std::move(data)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeClearSample(
    JNIEnv*, jobject, jlong ptr, jint slot) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr) return JNI_FALSE;
    return engine->samples().clear(slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeHasSample(
    JNIEnv*, jobject, jlong ptr, jint slot) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr) return JNI_FALSE;
    return engine->samples().occupied(slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeCollectGarbage(
    JNIEnv*, jobject, jlong ptr) {
    auto* engine = engineOf(ptr);
    return engine == nullptr ? 0 : engine->collectGarbage();
}

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeResidentFrames(
    JNIEnv*, jobject, jlong ptr) {
    auto* engine = engineOf(ptr);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->samples().residentFrames());
}

// ── published state ─────────────────────────────────────────────────────

/**
 * One call for everything the UI polls, so a 60 Hz playhead costs one JNI
 * transition per frame rather than eight.
 *
 *   out[0] currentStep      out[4] activeVoices
 *   out[1] currentPattern   out[5] countInRemaining
 *   out[2] queuedPattern    out[6] recordEditCount
 *   out[3] playing | recording << 1
 *   out[7..7+kMaxChannels)  per-channel trigger counters
 */
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeReadState(
    JNIEnv* env, jobject, jlong ptr, jintArray out) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr || out == nullptr) return;
    constexpr int kFixed = 7;
    const int wanted = kFixed + tryptify::kMaxChannels;
    if (env->GetArrayLength(out) < wanted) return;

    jint values[kFixed + tryptify::kMaxChannels];
    values[0] = engine->currentStep();
    values[1] = engine->currentPattern();
    values[2] = engine->queuedPattern();
    values[3] = (engine->playing() ? 1 : 0) | (engine->recording() ? 2 : 0);
    values[4] = engine->activeVoices();
    values[5] = engine->countInRemaining();
    values[6] = static_cast<jint>(engine->recordEditCount());
    for (int i = 0; i < tryptify::kMaxChannels; ++i) {
        values[kFixed + i] = static_cast<jint>(engine->channelTriggers(i));
    }
    env->SetIntArrayRegion(out, 0, wanted, values);
}

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeFramePosition(
    JNIEnv*, jobject, jlong ptr) {
    auto* engine = engineOf(ptr);
    return engine == nullptr ? 0 : static_cast<jlong>(engine->framePosition());
}

/**
 * Reads a pattern's step data back out of the engine.
 *
 * The one place the engine is authoritative for *content* rather than for
 * time: a hit played live while recording is quantized by the audio thread,
 * which is the only thing that knows where the playhead was when the tap
 * arrived. Recomputing that guess on the Kotlin side would land a step early
 * or late whenever a tap fell near a boundary, so the UI reads the engine's
 * answer instead.
 *
 * Layout matches nativeUploadPattern's `steps` array. Returns the channel
 * count written, or -1 if the pattern does not exist or the buffer is short.
 */
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_sampler_NativeSamplerBridge_nativeReadSteps(
    JNIEnv* env, jobject, jlong ptr, jint index, jbyteArray out) {
    auto* engine = engineOf(ptr);
    if (engine == nullptr || out == nullptr) return -1;

    PatternState state;
    if (!engine->readPattern(index, state)) return -1;

    const int channelCount = state.channelCount < 0
        ? 0
        : (state.channelCount > tryptify::kMaxChannels ? tryptify::kMaxChannels
                                                       : state.channelCount);
    const jsize needed = channelCount * tryptify::kMaxSteps * 8;
    if (env->GetArrayLength(out) < needed) return -1;
    if (needed == 0) return 0;

    std::vector<jbyte> bytes(static_cast<size_t>(needed));
    for (int c = 0; c < channelCount; ++c) {
        for (int s = 0; s < tryptify::kMaxSteps; ++s) {
            const StepData& step = state.channels[c].steps[s];
            const size_t b = (static_cast<size_t>(c) * tryptify::kMaxSteps + s) * 8;
            bytes[b + 0] = static_cast<jbyte>(step.enabled);
            bytes[b + 1] = static_cast<jbyte>(step.velocity);
            bytes[b + 2] = static_cast<jbyte>(step.pitch);
            bytes[b + 3] = static_cast<jbyte>(step.probability);
            bytes[b + 4] = static_cast<jbyte>(step.pan);
            bytes[b + 5] = static_cast<jbyte>(step.sampleStart);
            bytes[b + 6] = static_cast<jbyte>(step.locks);
            bytes[b + 7] = 0;
        }
    }
    env->SetByteArrayRegion(out, 0, needed, bytes.data());
    return channelCount;
}

}  // extern "C"
