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
#include "object_engine.h"

namespace {
inline tf::atmos::ObjectEngine* engine_of(jlong ptr) {
  return reinterpret_cast<tf::atmos::ObjectEngine*>(ptr);
}
}  // namespace

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

// ── Object reconstruction engine (P1) ────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeEngineCreate(
    JNIEnv* /*env*/, jclass /*clazz*/) {
  return reinterpret_cast<jlong>(new tf::atmos::ObjectEngine());
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeEngineDestroy(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong engine) {
  delete engine_of(engine);
}

// Upmixes one E-AC-3 frame's bed PCM into objects. `bedInterleaved` is
// channels * samples floats (interleaved, decoder channel order). Returns the
// object count (>= 1), or -1 if the frame has no JOC. The object PCM stays in
// the native engine for the HRTF render stage (P2). First-cut marshaling; the
// audio-thread-optimized path (direct buffers, reused scratch) lands with P4.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeUpmixFrame(
    JNIEnv* env, jclass /*clazz*/, jlong engine, jbyteArray frame,
    jfloatArray bedInterleaved, jint channels, jint samples) {
  tf::atmos::ObjectEngine* eng = engine_of(engine);
  if (eng == nullptr || channels <= 0 || samples <= 0) return -1;

  const jsize flen = env->GetArrayLength(frame);
  std::vector<uint8_t> fbuf(static_cast<size_t>(flen));
  env->GetByteArrayRegion(frame, 0, flen, reinterpret_cast<jbyte*>(fbuf.data()));

  const jsize blen = env->GetArrayLength(bedInterleaved);
  if (blen < static_cast<jsize>(channels) * samples) return -1;
  std::vector<float> interleaved(static_cast<size_t>(blen));
  env->GetFloatArrayRegion(bedInterleaved, 0, blen, interleaved.data());

  std::vector<std::vector<float>> planar(channels, std::vector<float>(samples));
  for (int i = 0; i < samples; ++i)
    for (int c = 0; c < channels; ++c)
      planar[c][i] = interleaved[static_cast<size_t>(i) * channels + c];
  std::vector<const float*> ptrs(channels);
  for (int c = 0; c < channels; ++c) ptrs[c] = planar[c].data();

  return eng->upmix_frame(fbuf.data(), fbuf.size(), ptrs.data(), channels, samples);
}

}  // extern "C"
