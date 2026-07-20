// JNI surface for the clean-room Atmos metadata/object code (the Cavern-ported
// EMDF/OAMD/JOC chain). Under plan Option B the E-AC-3 core is decoded by
// Media3's FfmpegAudioRenderer (NextLib); this native side does the object work
// — parse the Atmos side-data out of a raw E-AC-3 frame, and (as the surface
// grows) run the JOC QMF upmix on the decoded bed PCM.
//
// This first entry point lets the Kotlin sample-tap probe a frame: it walks the
// EMDF container in the frame's aux data and reports the OAMD object count, which
// is what the AtmosAudioProcessor keys its per-frame upmix on.
#include <jni.h>

#include <cstdint>
#include <vector>

#include "cavern/extensible_metadata_decoder.h"

extern "C" {

// Parses a raw E-AC-3 frame for Atmos object metadata. Returns the OAMD object
// count (>= 1) if the frame carries an EMDF/OAMD payload, or -1 if it does not
// (a plain non-Atmos frame, or no decodable side-data).
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeParseAtmos(
    JNIEnv* env, jclass /*clazz*/, jbyteArray frame) {
  const jsize len = env->GetArrayLength(frame);
  if (len <= 0) return -1;
  std::vector<uint8_t> buf(static_cast<size_t>(len));
  env->GetByteArrayRegion(frame, 0, len, reinterpret_cast<jbyte*>(buf.data()));

  tf::atmos::BitReader reader(buf.data(), buf.size());
  tf::atmos::cavern::ExtensibleMetadataDecoder emdf;
  emdf.decode(reader);

  const int objects = emdf.oamd().object_count();
  return (emdf.oamd().valid() && objects > 0) ? objects : -1;
}

}  // extern "C"
