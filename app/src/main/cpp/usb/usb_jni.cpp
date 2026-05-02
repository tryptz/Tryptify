// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for the libusb-backed USB Audio Class driver.

#include <jni.h>
#include <android/log.h>

#include "libusb_uac_driver.h"

#define TAG "UsbJni"

namespace {
monotrypt::usb::LibusbUacDriver& driver() {
    static monotrypt::usb::LibusbUacDriver instance;
    return instance;
}
} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeInit(
    JNIEnv*, jobject) {
    return driver().ensureContext() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeOpen(
    JNIEnv*, jobject, jint fd) {
    return driver().open(static_cast<int>(fd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeClose(
    JNIEnv*, jobject) {
    driver().close();
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeIsOpen(
    JNIEnv*, jobject) {
    return driver().isOpen() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeStart(
    JNIEnv*, jobject, jint sampleRate, jint bitsPerSample, jint channels) {
    return driver().start(sampleRate, bitsPerSample, channels)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeStop(
    JNIEnv*, jobject) {
    driver().stop();
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeFlushRing(
    JNIEnv*, jobject) {
    driver().flushRing();
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeIsStreamingFormat(
    JNIEnv*, jobject, jint sampleRate, jint bitsPerSample, jint channels) {
    return driver().isStreamingFormat(sampleRate, bitsPerSample, channels)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeIsStreaming(
    JNIEnv*, jobject) {
    return driver().isStreaming() ? JNI_TRUE : JNI_FALSE;
}

// Expects a direct ByteBuffer; we read [position, position+frames*frameSize).
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeWrite(
    JNIEnv* env, jobject, jobject directBuffer, jint frames) {
    if (!directBuffer || frames <= 0) return 0;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
    if (!base) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "nativeWrite: ByteBuffer.isDirect() must be true");
        return 0;
    }
    return driver().writePcm(base, frames);
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeWritableFrames(
    JNIEnv*, jobject) {
    return driver().writableFrames();
}

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativePlayedFrames(
    JNIEnv*, jobject) {
    return static_cast<jlong>(driver().playedFrames());
}

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativePendingFrames(
    JNIEnv*, jobject) {
    long w = driver().writtenFrames();
    long p = driver().playedFrames();
    return static_cast<jlong>(w > p ? w - p : 0);
}

} // extern "C"
