// JNI for data/local/tags/NativeTags.kt. One call a file, one byte array
// back: no Java object is built here and no Java method is called, so the
// release build's shrinker has nothing of this to rename or remove.

#include <jni.h>

#include <string>
#include <vector>

#include "tag_reader.h"

extern "C" JNIEXPORT jbyteArray JNICALL
Java_tf_monochrome_android_data_local_tags_NativeTags_nativeRead(
    JNIEnv *env, jobject /* thiz */, jbyteArray pathUtf8, jboolean wantPicture) {
    if (pathUtf8 == nullptr) return nullptr;
    // The path comes as UTF-8 bytes the Kotlin side encoded. GetStringUTFChars
    // would give JNI's modified UTF-8, in which a character outside the basic
    // plane (an emoji in a file name) is not the bytes the file system holds.
    const jsize pathSize = env->GetArrayLength(pathUtf8);
    if (pathSize <= 0) return nullptr;
    std::string path(static_cast<size_t>(pathSize), '\0');
    env->GetByteArrayRegion(pathUtf8, 0, pathSize, reinterpret_cast<jbyte *>(&path[0]));
    if (env->ExceptionCheck()) return nullptr;
    // A NUL inside a path is no path.
    if (path.find('\0') != std::string::npos) return nullptr;

    tryptags::Options options;
    options.picture = wantPicture == JNI_TRUE;
    std::vector<uint8_t> blob;
    if (!tryptags::read(path.c_str(), options, blob)) return nullptr;

    const jsize size = static_cast<jsize>(blob.size());
    jbyteArray result = env->NewByteArray(size);
    if (result == nullptr) return nullptr;  // out of memory: the exception is pending
    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(blob.data()));
    return result;
}
