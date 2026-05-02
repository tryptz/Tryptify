// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for the USB Audio Class direct-output driver. One global
// LibusbUacDriver instance backs every Kotlin call — the Android
// permission flow only ever yields one DAC at a time, so a singleton
// avoids the question of "which driver does this fd belong to".

#include <jni.h>
#include <mutex>
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
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return driver().ensureContext() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeOpen(
    JNIEnv* /*env*/, jobject /*thiz*/, jint fd) {
    return driver().open(static_cast<int>(fd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    driver().close();
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeIsOpen(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return driver().isOpen() ? JNI_TRUE : JNI_FALSE;
}

// Stage-2 placeholder. Real implementation will negotiate the UAC2
// alt setting, claim the streaming interface, and submit iso transfers.
JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeStart(
    JNIEnv* /*env*/, jobject /*thiz*/, jint /*sampleRate*/, jint /*bitsPerSample*/, jint /*channels*/) {
    __android_log_print(ANDROID_LOG_WARN, TAG,
        "nativeStart: TODO Stage 2 (UAC2 alt-setting negotiation + iso pump)");
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeStop(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // No-op until Stage 2 ships.
}

// Returns the number of frames written. -1 = transfer error / device gone.
// Stage-1 stub returns the requested frame count so LibusbAudioSink
// drains its pending data without tripping the renderer's underrun
// detector while the iso pump is still TODO.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeWrite(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jobject /*directBuffer*/, jint frames) {
    return frames;
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeWritableFrames(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    // Plenty until the real ring buffer is hooked up.
    return 16384;
}

} // extern "C"
