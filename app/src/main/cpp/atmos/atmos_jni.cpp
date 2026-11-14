// The Atmos code's JNI surface (AtmosNative.kt). The E-AC-3 core is decoded
// by Media3's FfmpegAudioRenderer; this side does the object work on each
// frame: read its Atmos metadata, rebuild the objects from the decoded bed
// and render them (AtmosPipeline).
#include <android/log.h>
#include <jni.h>

#include <cstdint>
#include <vector>

#include "atmos_pipeline.h"
#include "cavern/extensible_metadata_decoder.h"
#include "object_engine.h"
#include "render/box_room.h"
#include "render/measured_room.h"
#include "render/room_sofa.h"

namespace {

using tf::atmos::AtmosPipeline;

inline AtmosPipeline* pipeline_of(jlong ptr) { return reinterpret_cast<AtmosPipeline*>(ptr); }

// A frame and its interleaved bed, copied into the pipeline's own buffers:
// the audio thread allocates nothing once they have grown. False when the
// bed is shorter than channels * samples.
struct FrameIn {
  const uint8_t* frame;
  size_t frame_size;
  const float* bed;
};
bool read_frame(JNIEnv* env, AtmosPipeline* pipe, jbyteArray frame, jfloatArray bed,
                jint channels, jint samples, FrameIn& in) {
  const jsize flen = env->GetArrayLength(frame);
  std::vector<uint8_t>& fbuf = pipe->frame_scratch(static_cast<size_t>(flen));
  env->GetByteArrayRegion(frame, 0, flen, reinterpret_cast<jbyte*>(fbuf.data()));
  const jsize blen = env->GetArrayLength(bed);
  if (blen < static_cast<jsize>(channels) * samples) return false;
  std::vector<float>& bbuf = pipe->bed_scratch(static_cast<size_t>(blen));
  env->GetFloatArrayRegion(bed, 0, blen, bbuf.data());
  in = {fbuf.data(), static_cast<size_t>(flen), bbuf.data()};
  return true;
}

// Objects' meter fields into `out`, as many as it and kMaxMeterObjects hold:
// `fill(buffer, capacity)` writes them and returns how many.
constexpr int kMaxMeterObjects = 32;
template <typename Fill>
jint write_object_meter(JNIEnv* env, jfloatArray out, Fill fill) {
  constexpr int kFields = AtmosPipeline::kMeterFields;
  float buf[kMaxMeterObjects * kFields];
  int capacity = env->GetArrayLength(out) / kFields;
  if (capacity > kMaxMeterObjects) capacity = kMaxMeterObjects;
  const int count = fill(buf, capacity);
  if (count > 0) env->SetFloatArrayRegion(out, 0, count * kFields, buf);
  return count;
}

jfloatArray to_java(JNIEnv* env, const std::vector<float>& values) {
  jfloatArray out = env->NewFloatArray(static_cast<jsize>(values.size()));
  if (out != nullptr) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(values.size()), values.data());
  return out;
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

// ── The render pipeline ──────────────────────────────────────────────────
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

// Clears the pipeline's time history (delay lines, convolution tails, DRC
// envelope) on seek/flush. Allocation-free — safe from the audio thread.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativePipelineFlush(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe != nullptr) pipe->flush_state();
}

// Pushes the user's RendererProfile into the pipeline. `mode` and `downmix` are
// the Kotlin RendererMode / StereoDownmixMode ordinals.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeSetRenderParams(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline, jint mode, jint downmix,
    jfloat binauralStrength, jboolean heightVirtualization, jfloat lfeGainDb,
    jboolean bassManagement, jint crossoverHz, jint drcMode,
    jboolean dialogNormalization) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
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
  AtmosPipeline* pipe = pipeline_of(pipeline);
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
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe != nullptr) pipe->clear_sofa();
}

// Renders one E-AC-3 frame's Atmos content to interleaved binaural stereo.
// `bedInterleaved` is channels*samples floats; `stereoOut` receives 2*samples
// floats when the pipeline produced output (the objects, or the bed while
// there are none; the pipeline crossfades between them). Returns 1 (output
// written) or -1 (inactive: the caller folds down itself). Audio thread.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeProcessFrame(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jbyteArray frame,
    jfloatArray bedInterleaved, jint channels, jint samples, jfloatArray stereoOut) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr || channels <= 0 || samples <= 0) return -1;

  FrameIn in;
  if (!read_frame(env, pipe, frame, bedInterleaved, channels, samples, in)) return -1;
  std::vector<float>& sbuf = pipe->out_scratch(static_cast<size_t>(2) * samples);
  const int rc = pipe->process_frame_interleaved(in.frame, in.frame_size, in.bed, channels,
                                                 samples, sbuf.data());
  // Truncation should never happen (DD+ JOC practice is <=16 objects) but must
  // never be silent if it does. One log per process lifetime.
  static bool truncation_warned = false;
  if (rc == 1 && !truncation_warned && pipe->last_truncated() > 0) {
    truncation_warned = true;
    __android_log_print(ANDROID_LOG_WARN, "AtmosPipeline",
                        "JOC stream carries more objects than the renderer cap;"
                        " dropping %d object(s) per frame",
                        pipe->last_truncated());
  }
  if (rc == 1) {
    if (env->GetArrayLength(stereoOut) < 2 * samples) return -1;
    env->SetFloatArrayRegion(stereoOut, 0, 2 * samples, sbuf.data());
  }
  return rc;
}

// Selects the loudspeaker layout for nativeProcessFrameSpeakers (Kotlin
// ChannelLayout.nativeId: 1 = 5.1 ... 8 = 9.1.6) and returns its channel count.
// Allocates, so it is called when the processor (re)configures, not per frame.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeSetOutputLayout(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline, jint layoutId) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr) return 0;
  return pipe->set_output_layout(layoutId);
}

// Renders one E-AC-3 frame to the loudspeaker layout set above. `out` receives
// outChannels*samples interleaved floats in Android channel-mask order when
// the pipeline produced output. Returns 1 (written) or -1 (inactive: the
// caller plays the bed itself). Audio thread.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeProcessFrameSpeakers(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jbyteArray frame,
    jfloatArray bedInterleaved, jint channels, jint samples, jfloatArray out,
    jint outChannels) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr || channels <= 0 || samples <= 0) return -1;
  if (outChannels != pipe->output_channels()) return -1;  // layout/buffer mismatch
  FrameIn in;
  if (!read_frame(env, pipe, frame, bedInterleaved, channels, samples, in)) return -1;
  const size_t n = static_cast<size_t>(outChannels) * samples;
  std::vector<float>& obuf = pipe->out_scratch(n);
  const int rc = pipe->process_frame_speakers_interleaved(in.frame, in.frame_size, in.bed,
                                                          channels, samples, obuf.data());
  if (rc == 1) {
    if (env->GetArrayLength(out) < static_cast<jsize>(n)) return -1;
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(n), obuf.data());
  }
  return rc;
}

// How much of the virtual room's reflections and tail the headphone render
// has, 0 to 1.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeSetRoomAmount(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline, jfloat room) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe != nullptr) pipe->set_room_amount(room);
}

// Objects rendered in the last frame, or 0 when the decoded bed played
// instead (no JOC in the stream, none yet after a seek, or the bed mode).
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeLastObjectCount(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pipeline) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  return pipe != nullptr ? pipe->last_objects() : 0;
}

// The last frame's objects for the Atmos page's map: five floats an object
// into `out` (room x, y, z, the frame's peak, and 0 object / 1 bed / 2 LFE).
// Returns how many were written. Audio thread; allocation-free.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeObjectMeter(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jfloatArray out) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr) return 0;
  return write_object_meter(env, out, [pipe](float* buf, int capacity) {
    return pipe->object_meter(buf, capacity);
  });
}

// Reads a frame's object metadata without rendering (the object render is
// off) and writes where the objects are into `out`, five floats each as
// nativeObjectMeter does, the level -1. Returns the object count.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeTrackObjects(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jbyteArray frame, jfloatArray out) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr) return 0;
  const jsize flen = env->GetArrayLength(frame);
  std::vector<uint8_t>& fbuf = pipe->frame_scratch(static_cast<size_t>(flen));
  if (flen > 0) env->GetByteArrayRegion(frame, 0, flen, reinterpret_cast<jbyte*>(fbuf.data()));
  pipe->track_metadata(fbuf.data(), static_cast<size_t>(flen));
  return write_object_meter(env, out, [pipe](float* buf, int capacity) {
    return pipe->track_meter(buf, capacity);
  });
}

// The last frame's metadata as the engine saw it (ObjectEngine::debug), for
// the app's log. Returns how many ints were written.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeDebugInfo(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jintArray out) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  constexpr int kFields = tf::atmos::ObjectEngine::kDebugFields;
  if (pipe == nullptr || env->GetArrayLength(out) < kFields) return 0;
  jint buf[kFields];
  int raw[kFields];
  pipe->debug(raw);
  for (int i = 0; i < kFields; ++i) buf[i] = raw[i];
  env->SetIntArrayRegion(out, 0, kFields, buf);
  return kFields;
}

// The virtual room's speakers for the Atmos page (AtmosPipeline::room_layout):
// four floats each into `out`. Returns how many were written. Any thread.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeRoomLayout(
    JNIEnv* env, jclass /*clazz*/, jfloatArray out) {
  constexpr int kFields = AtmosPipeline::kRoomFields;
  constexpr int kMax = AtmosPipeline::kRoomMeterMax;
  float buf[kMax * kFields];
  int capacity = env->GetArrayLength(out) / kFields;
  if (capacity > kMax) capacity = kMax;
  const int count = AtmosPipeline::room_layout(buf, capacity);
  if (count > 0) env->SetFloatArrayRegion(out, 0, count * kFields, buf);
  return count;
}

// How hard each of the room's speakers was driven in the last frame
// (AtmosPipeline::room_meter), one float a speaker into `out`; `tracked` for
// a frame that was only watched. Returns the speaker count. Audio thread.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeRoomMeter(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jboolean tracked, jfloatArray out) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr) return 0;
  constexpr int kMax = AtmosPipeline::kRoomMeterMax;
  float buf[kMax];
  int capacity = env->GetArrayLength(out);
  if (capacity > kMax) capacity = kMax;
  const int count = pipe->room_meter(buf, capacity, tracked == JNI_TRUE);
  if (count > 0) env->SetFloatArrayRegion(out, 0, count, buf);
  return count;
}

// ── Rooms for the headphone render ───────────────────────────────────────
// A room is prepared once, off the playback thread, into a packed room (an
// array of floats: render/measured_room.h says what is in it), which the app
// keeps and hands to every pipeline it makes.

// A measured room out of a SOFA file of room impulse responses (BRIRs, SRIRs,
// DRIRs). Null when the file cannot be read or holds no room. Any thread;
// slow (it parses the whole file) and allocates.
JNIEXPORT jfloatArray JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativePrepareRoomSofa(
    JNIEnv* env, jclass /*clazz*/, jbyteArray sofa) {
  const jsize len = env->GetArrayLength(sofa);
  if (len <= 0) return nullptr;
  std::vector<char> file(static_cast<size_t>(len));
  env->GetByteArrayRegion(sofa, 0, len, reinterpret_cast<jbyte*>(file.data()));
  std::vector<float> left, right;
  double rate = 0.0;
  tf::atmos::render::RoomGeometry geometry;
  if (!tf::atmos::read_room_sofa(file.data(), len, left, right, rate, geometry)) return nullptr;
  file = std::vector<char>();  // the file is the larger part of the memory; done with it
  tf::atmos::render::RoomResponse room;
  if (!tf::atmos::render::prepare_room_response(left.data(), right.data(), static_cast<int>(left.size()),
                                                rate, room)) {
    return nullptr;
  }
  return to_java(env, tf::atmos::render::pack_room(room, geometry));
}

// A room worked out from its size (render/box_room.h): metres, and the share
// of sound its walls take up, 0 to 1. Any thread; allocates.
JNIEXPORT jfloatArray JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeBuildBoxRoom(
    JNIEnv* env, jclass /*clazz*/, jfloat width, jfloat depth, jfloat height, jfloat absorption) {
  tf::atmos::render::BoxRoom box;
  box.width = width;
  box.depth = depth;
  box.height = height;
  box.absorption = absorption;
  std::vector<float> left, right;
  tf::atmos::render::box_response(box, left, right);
  tf::atmos::render::RoomResponse room;
  if (!tf::atmos::render::prepare_room_response(left.data(), right.data(), static_cast<int>(left.size()),
                                                48000.0, room)) {
    return nullptr;
  }
  return to_java(env, tf::atmos::render::pack_room(room, tf::atmos::render::box_geometry(box)));
}

// The room the pipeline's headphone render plays in: a packed room, or null
// for the built-in one. Returns 1 when it was taken. Never the audio thread.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_atmos_AtmosNative_nativeSetRoom(
    JNIEnv* env, jclass /*clazz*/, jlong pipeline, jfloatArray packed) {
  AtmosPipeline* pipe = pipeline_of(pipeline);
  if (pipe == nullptr) return 0;
  if (packed == nullptr) return pipe->set_room_response(nullptr, 0) ? 1 : 0;
  const jsize len = env->GetArrayLength(packed);
  std::vector<float> room(static_cast<size_t>(len));
  env->GetFloatArrayRegion(packed, 0, len, room.data());
  return pipe->set_room_response(room.data(), room.size()) ? 1 : 0;
}

}  // extern "C"
