#include <jni.h>

#include "projectm_bridge.h"

namespace {

ProjectMBridge* FromHandle(jlong handle) {
    return reinterpret_cast<ProjectMBridge*>(handle);
}

jstring ToJString(JNIEnv* env, const std::string& value) {
    return value.empty() ? nullptr : env->NewStringUTF(value.c_str());
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeCreate(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring asset_root,
        jstring preset_root,
        jstring texture_root,
        jint width,
        jint height,
        jint mesh_width,
        jint mesh_height) {
    const char* asset_root_chars = env->GetStringUTFChars(asset_root, nullptr);
    const char* preset_root_chars = env->GetStringUTFChars(preset_root, nullptr);
    const char* texture_root_chars = env->GetStringUTFChars(texture_root, nullptr);

    auto* bridge = new ProjectMBridge(
            asset_root_chars,
            preset_root_chars,
            texture_root_chars,
            width,
            height,
            mesh_width,
            mesh_height
    );

    env->ReleaseStringUTFChars(asset_root, asset_root_chars);
    env->ReleaseStringUTFChars(preset_root, preset_root_chars);
    env->ReleaseStringUTFChars(texture_root, texture_root_chars);

    if (!bridge->IsReady()) {
        delete bridge;
        return 0L;
    }
    return reinterpret_cast<jlong>(bridge);
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeResize(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint width,
        jint height) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->Resize(width, height);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeRenderFrame(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jlong frame_time_nanos) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->RenderFrame(frame_time_nanos);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativePushPcm(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong handle,
        jfloatArray samples,
        jint channel_count,
        jint sample_rate) {
    auto* bridge = FromHandle(handle);
    if (bridge == nullptr || samples == nullptr) {
        return;
    }

    const jsize length = env->GetArrayLength(samples);
    if (length <= 0) {
        return;
    }

    jboolean is_copy = JNI_FALSE;
    auto* sample_ptr = env->GetFloatArrayElements(samples, &is_copy);
    bridge->PushPcm(sample_ptr, static_cast<size_t>(length), channel_count, sample_rate);
    env->ReleaseFloatArrayElements(samples, sample_ptr, JNI_ABORT);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetPreset(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong handle,
        jstring preset_path) {
    auto* bridge = FromHandle(handle);
    if (bridge == nullptr || preset_path == nullptr) {
        return JNI_FALSE;
    }

    const char* preset_path_chars = env->GetStringUTFChars(preset_path, nullptr);
    const bool success = bridge->SetPreset(preset_path_chars);
    env->ReleaseStringUTFChars(preset_path, preset_path_chars);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeNextPreset(
        JNIEnv* env,
        jobject /*thiz*/,
        jlong handle) {
    auto* bridge = FromHandle(handle);
    if (bridge == nullptr) {
        return nullptr;
    }
    return ToJString(env, bridge->NextPreset());
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetPresetShuffleEnabled(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jboolean enabled) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->SetShuffle(enabled == JNI_TRUE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetBeatSensitivity(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint value) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->SetBeatSensitivity(value);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetBrightness(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint value) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->SetBrightness(value);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetPaused(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jboolean paused) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->SetPaused(paused == JNI_TRUE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetQuality(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint mesh_width,
        jint mesh_height) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->SetQuality(mesh_width, mesh_height);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetFps(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint fps) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->SetFps(fps);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeSetPresetDuration(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jint seconds) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->SetPresetDuration(seconds);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeTouch(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jfloat x,
        jfloat y,
        jint pressure,
        jint touch_type) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->Touch(x, y, pressure, touch_type);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeTouchDrag(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jfloat x,
        jfloat y,
        jint pressure) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->TouchDrag(x, y, pressure);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeTouchDestroy(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle,
        jfloat x,
        jfloat y) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->TouchDestroy(x, y);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeTouchDestroyAll(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle) {
    auto* bridge = FromHandle(handle);
    if (bridge != nullptr) {
        bridge->TouchDestroyAll();
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_tf_monochrome_android_visualizer_ProjectMNativeBridge_nativeRelease(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jlong handle) {
    auto* bridge = FromHandle(handle);
    delete bridge;
}
