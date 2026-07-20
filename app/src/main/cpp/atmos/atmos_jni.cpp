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

#include "atmos_pipeline.h"
#include "cavern/extensible_metadata_decoder.h"
#include "object_engine.h"

namespace {
inline tf::atmos::ObjectEngine* engine_of(jlong ptr) {
  return reinterpret_cast<tf::atmos::ObjectEngine*>(ptr);
}
inline tf::atmos::AtmosPipeline* pipeline_of(jlong ptr) {
  return reinterpret_cast<tf::atmos::AtmosPipeline*>(ptr);
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

// ── Full render pipeline (P1 upmix -> P2 binaural render) ─────────────────
JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativePipelineCreate(
    JNIEnv* /*env*/, jclass /*clazz*/, jint sampleRate, jint maxObjects) {
  tf::atmos::AtmosPipeline* pipe = new tf::atmos::AtmosPipeline();
  pipe->configure(sampleRate, maxObjects);
  return reinterpret_cast<jlong>(pipe);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativePipelineDestroy(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline) {
  delete pipeline_of(pipeline);
}

// Pushes the user's RendererProfile into the pipeline. `mode` and `downmix` are
// the Kotlin RendererMode / StereoDownmixMode ordinals.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeSetRenderParams(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline, jint mode, jint downmix,
    jfloat binauralStrength, jboolean heightVirtualization, jfloat lfeGainDb,
    jboolean bassManagement, jint crossoverHz, jint drcMode,
    jboolean dialogNormalization) {
  tf::atmos::AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr) return;
  pipe->set_params(mode, downmix, binauralStrength,
                   heightVirtualization == JNI_TRUE, lfeGainDb,
                   bassManagement == JNI_TRUE, crossoverHz, drcMode,
                   dialogNormalization == JNI_TRUE);
}

// Installs a runtime HRTF from raw .sofa file bytes (off the audio thread).
// Returns 1 on success, 0 on parse failure. The renderer keeps using the baked
// default until this succeeds, and reverts on nativeClearSofa.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeLoadSofa(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jbyteArray sofa) {
  tf::atmos::AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr || sofa == nullptr) return 0;
  const jsize len = env->GetArrayLength(sofa);
  if (len <= 0) return 0;
  std::vector<int8_t> buf(static_cast<size_t>(len));
  env->GetByteArrayRegion(sofa, 0, len, reinterpret_cast<jbyte*>(buf.data()));
  return pipe->load_sofa(reinterpret_cast<const char*>(buf.data()), len) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeClearSofa(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline) {
  tf::atmos::AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe != nullptr) pipe->clear_sofa();
}

// Renders one E-AC-3 frame's Atmos content to interleaved binaural stereo.
// `bedInterleaved` is channels*samples floats; `stereoOut` receives 2*samples
// floats when Atmos is rendered. Returns 1 (rendered) or -1 (no objects — the
// caller passes the bed through unchanged). First-cut marshaling; P4 optimizes.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeProcessFrame(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jbyteArray frame,
    jfloatArray bedInterleaved, jint channels, jint samples, jfloatArray stereoOut) {
  tf::atmos::AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr || channels <= 0 || samples <= 0) return -1;

  // Runs on the audio thread: use the pipeline's reusable scratch so there is no
  // per-frame heap allocation (that alone was enough to cause micro-stutter).
  const jsize flen = env->GetArrayLength(frame);
  std::vector<uint8_t>& fbuf = pipe->frame_scratch(static_cast<size_t>(flen));
  env->GetByteArrayRegion(frame, 0, flen, reinterpret_cast<jbyte*>(fbuf.data()));

  const jsize blen = env->GetArrayLength(bedInterleaved);
  if (blen < static_cast<jsize>(channels) * samples) return -1;
  std::vector<float>& bbuf = pipe->bed_scratch(static_cast<size_t>(blen));
  env->GetFloatArrayRegion(bedInterleaved, 0, blen, bbuf.data());

  std::vector<float>& sbuf = pipe->stereo_scratch(static_cast<size_t>(2) * samples);
  const int rc = pipe->process_frame_interleaved(
      fbuf.data(), static_cast<size_t>(flen), bbuf.data(), channels, samples,
      sbuf.data());
  if (rc == 1) {
    if (env->GetArrayLength(stereoOut) < 2 * samples) return -1;
    env->SetFloatArrayRegion(stereoOut, 0, 2 * samples, sbuf.data());
  }
  return rc;
}

}  // extern "C"
