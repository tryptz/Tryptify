// SPDX-License-Identifier: GPL-3.0-or-later
// JNI for tf.monochrome.android.glyph.style.RarNative: a RarReader behind a
// handle. The handle is Kotlin's to close, once (RarNative.Reader does).
#include <jni.h>

#include <cstdint>
#include <string>

#include "rar_reader.h"

namespace {

RarReader* reader(jlong handle) { return reinterpret_cast<RarReader*>(static_cast<intptr_t>(handle)); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_glyph_style_RarNative_nativeOpen(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return 0;
    // Kept even when it does not open, so its error can be read; closed by Kotlin either way.
    auto* opened = new RarReader();
    opened->open(chars);
    env->ReleaseStringUTFChars(path, chars);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(opened));
}

/** The next regular file's name as UTF-8 bytes, its declared size in [sizeOut][0]; null at the end or on an error. */
JNIEXPORT jbyteArray JNICALL
Java_tf_monochrome_android_glyph_style_RarNative_nativeNext(JNIEnv* env, jobject, jlong handle, jlongArray sizeOut) {
    RarReader* r = reader(handle);
    if (r == nullptr || !r->error().empty()) return nullptr;
    std::string name;
    int64_t size = -1;
    if (!r->next(name, size)) return nullptr;
    jlong declared = static_cast<jlong>(size);
    env->SetLongArrayRegion(sizeOut, 0, 1, &declared);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(name.size()));
    if (out == nullptr) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(name.size()), reinterpret_cast<const jbyte*>(name.data()));
    return out;
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_glyph_style_RarNative_nativeRead(JNIEnv* env, jobject, jlong handle, jbyteArray buffer, jint offset, jint length) {
    RarReader* r = reader(handle);
    if (r == nullptr || length <= 0) return -1;
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    if (bytes == nullptr) return -1;
    long got = r->read(bytes + offset, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(buffer, bytes, got > 0 ? 0 : JNI_ABORT);
    return static_cast<jint>(got);
}

JNIEXPORT jstring JNICALL
Java_tf_monochrome_android_glyph_style_RarNative_nativeError(JNIEnv* env, jobject, jlong handle) {
    RarReader* r = reader(handle);
    if (r == nullptr || r->error().empty()) return nullptr;
    return env->NewStringUTF(r->error().c_str());
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_glyph_style_RarNative_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reader(handle);
}

}  // extern "C"
