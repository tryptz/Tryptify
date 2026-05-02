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

// --- Diagnostics surface ---------------------------------------------
//
// These getters back the BypassDiagnostics StateFlow surfaced to the
// Settings UI. They're snapshot-cheap on the read side: lastError() is
// an atomic load; lastErrorDetail() and supportedRates() each take the
// driver's errorMutex_ briefly, which only contends with start() (a
// rare, off-the-hot-path event).

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeLastErrorCode(
    JNIEnv*, jobject) {
    return static_cast<jint>(driver().lastError());
}

JNIEXPORT jstring JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeLastErrorDetail(
    JNIEnv* env, jobject) {
    std::string s = driver().lastErrorDetail();
    return env->NewStringUTF(s.c_str());
}

// Returns a flat int[] of (clockId, minHz, maxHz, resHz) quads — one
// quad per ClockRateRange. Length is always a multiple of 4. Empty
// array if the driver hasn't started yet, the device returned no
// GET_RANGE data (UAC1 with no descriptor table), or every clock
// entity refused both SET_CUR and GET_CUR.
//
// Why a flat int[] instead of a typed object[]: keeps the JNI binding
// dependency-free (no FindClass / NewObject for a packed struct) and
// makes the marshalling unambiguous — the Kotlin side rebuilds typed
// objects in one pass over groups of 4.
JNIEXPORT jintArray JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeSupportedRates(
    JNIEnv* env, jobject) {
    auto ranges = driver().supportedRates();
    jintArray arr = env->NewIntArray(static_cast<jsize>(ranges.size() * 4));
    if (!arr || ranges.empty()) return arr;
    std::vector<jint> packed;
    packed.reserve(ranges.size() * 4);
    for (const auto& r : ranges) {
        packed.push_back(static_cast<jint>(r.clockId));
        // GET_RANGE values are 32-bit unsigned (per UAC2 §5.2.1).
        // Hz values fit in 31 bits comfortably (max valid is ~768k),
        // so the unsigned-to-signed cast is safe in practice.
        packed.push_back(static_cast<jint>(r.minHz));
        packed.push_back(static_cast<jint>(r.maxHz));
        packed.push_back(static_cast<jint>(r.resHz));
    }
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(packed.size()), packed.data());
    return arr;
}

// Returns a long[] packing the negotiated stream parameters in a
// fixed order so the Kotlin side can deserialize without tagged
// fields. Order:
//   [0] sampleRateHz
//   [1] bitsPerSample
//   [2] channels
//   [3] interfaceNumber
//   [4] altSetting
//   [5] endpointAddress
//   [6] maxPacketSize
//   [7] bInterval
//   [8] uacVersion (0x0100 = UAC1, 0x0200 = UAC2)
//   [9] clockSourceId (0 if UAC1 / no resolved clock)
//   [10] feedbackEndpointAddress (0 if absent)
//   [11] isHighSpeed (1/0)
//   [12] bytesPerSample (subslot size)
// Returns null when the driver isn't streaming — caller should hide
// the diagnostics block.
JNIEXPORT jlongArray JNICALL
Java_tf_monochrome_android_audio_usb_LibusbUacDriver_nativeActiveStream(
    JNIEnv* env, jobject) {
    if (!driver().isStreaming()) return nullptr;
    const auto& f = driver().currentFormat();
    jlong packed[13] = {
        static_cast<jlong>(f.sampleRateHz),
        static_cast<jlong>(f.bitsPerSample),
        static_cast<jlong>(f.channels),
        static_cast<jlong>(f.interfaceNumber),
        static_cast<jlong>(f.altSetting),
        static_cast<jlong>(f.endpointAddress),
        static_cast<jlong>(f.maxPacketSize),
        static_cast<jlong>(f.bInterval),
        static_cast<jlong>(f.uacVersion),
        static_cast<jlong>(f.clockSourceId),
        static_cast<jlong>(f.feedbackEndpointAddress),
        f.isHighSpeed ? jlong{1} : jlong{0},
        static_cast<jlong>(f.bytesPerSample),
    };
    jlongArray arr = env->NewLongArray(13);
    if (!arr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 13, packed);
    return arr;
}

} // extern "C"
