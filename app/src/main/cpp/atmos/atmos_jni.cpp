// JNI surface for the Atmos decode path.
//
// Bridges the ported EMDF -> OAMD + JOC decode chain to Kotlin. This is
// deliberately minimal and OFF the player's critical path: nothing loads this
// library at runtime yet, so it cannot affect existing playback. It gives the
// app a callable entry point to the completed metadata-decode path (and a place
// for the render/output wiring to grow into once a core E-AC-3 bed decoder — a
// Cavern port or an FFmpeg binding — is chosen).
//
// The heavy decode logic lives in header-only, host-unit-tested code under
// atmos/ and atmos/cavern/; this file is only the marshalling boundary.
#include <jni.h>

#include <cstdint>
#include <vector>

#include "bit_reader.h"
#include "cavern/extensible_metadata_decoder.h"
#include "vbap.h"

extern "C" {

// Compile/link self-check: constructs the shipped 7.1.4 bed layout and confirms
// the LFE flagging. Returns true when the native library is wired correctly.
JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosEngine_nativeSelfCheck(JNIEnv*, jobject) {
  tf::atmos::LoudspeakerLayout layout = tf::atmos::LoudspeakerLayout::atmos_7_1_4();
  return (layout.size() == 12 && layout.has_height() && layout.at(3).is_lfe)
             ? JNI_TRUE
             : JNI_FALSE;
}

// Runs the EMDF container walk over a raw side-data buffer and reports how many
// JOC objects the frame carries (0 if the buffer holds no EMDF/JOC payload).
// Exercises the real EMDF -> JOC decode path from Kotlin end to end.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosEngine_nativeDecodeEmdfObjectCount(
    JNIEnv* env, jobject, jbyteArray emdf) {
  if (emdf == nullptr) return 0;
  const jsize len = env->GetArrayLength(emdf);
  if (len <= 0) return 0;

  std::vector<uint8_t> buffer(static_cast<size_t>(len));
  env->GetByteArrayRegion(emdf, 0, len, reinterpret_cast<jbyte*>(buffer.data()));

  tf::atmos::BitReader reader(buffer.data(), buffer.size());
  tf::atmos::cavern::ExtensibleMetadataDecoder decoder;
  decoder.decode(reader);
  return decoder.has_objects() ? static_cast<jint>(decoder.joc().object_count()) : 0;
}

}  // extern "C"
