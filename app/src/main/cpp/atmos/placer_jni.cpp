// JNI surface for render/channel_placer.h — the mixer's spatial map. Kotlin's
// ChannelPlacerNative owns a handle per stream format; DownmixProcessor feeds
// it planar blocks in a direct buffer and gets interleaved stereo back.
#include <jni.h>

#include <cstdint>

#include "render/channel_placer.h"

using tf::atmos::render::ChannelPlacer;

namespace {
inline ChannelPlacer* placer_of(jlong ptr) { return reinterpret_cast<ChannelPlacer*>(ptr); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_atmos_ChannelPlacerNative_nativeCreate(
    JNIEnv*, jclass, jint sampleRate, jint channels, jint lfeIndex) {
  auto* p = new ChannelPlacer();
  p->configure(sampleRate, channels, lfeIndex);
  return reinterpret_cast<jlong>(p);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_ChannelPlacerNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
  delete placer_of(h);
}

// Radians and linear gain, one entry per channel.
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_ChannelPlacerNative_nativeSetPlacement(
    JNIEnv* env, jclass, jlong h, jfloatArray az, jfloatArray el, jfloatArray gain) {
  ChannelPlacer* p = placer_of(h);
  if (!p || !az || !el || !gain) return;
  const jsize n = env->GetArrayLength(az);
  if (env->GetArrayLength(el) < n || env->GetArrayLength(gain) < n) return;
  float a[ChannelPlacer::kMaxChannels], e[ChannelPlacer::kMaxChannels], g[ChannelPlacer::kMaxChannels];
  const jsize count = n > ChannelPlacer::kMaxChannels ? ChannelPlacer::kMaxChannels : n;
  env->GetFloatArrayRegion(az, 0, count, a);
  env->GetFloatArrayRegion(el, 0, count, e);
  env->GetFloatArrayRegion(gain, 0, count, g);
  p->setPlacement(a, e, g, count);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_ChannelPlacerNative_nativeSetMode(
    JNIEnv*, jclass, jlong h, jboolean binaural, jfloat strength, jboolean height,
    jboolean bassManagement, jint crossoverHz) {
  ChannelPlacer* p = placer_of(h);
  if (p) p->setMode(binaural, strength, height, bassManagement, crossoverHz);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_ChannelPlacerNative_nativeReset(JNIEnv*, jclass, jlong h) {
  ChannelPlacer* p = placer_of(h);
  if (p) p->reset();
}

// [planar]: channel c at float offset c * stride, [numFrames] each (direct).
// [stereoOut]: 2 * numFrames floats, interleaved (direct).
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_atmos_ChannelPlacerNative_nativeProcess(
    JNIEnv* env, jclass, jlong h, jobject planar, jint stride, jint numFrames, jobject stereoOut) {
  ChannelPlacer* p = placer_of(h);
  if (!p || !planar || !stereoOut || numFrames <= 0 || numFrames > stride) return;
  auto* in = static_cast<float*>(env->GetDirectBufferAddress(planar));
  auto* out = static_cast<float*>(env->GetDirectBufferAddress(stereoOut));
  if (!in || !out) return;
  const jlong inCap = env->GetDirectBufferCapacity(planar);
  const jlong outCap = env->GetDirectBufferCapacity(stereoOut);
  const int ch = p->channels();
  if (inCap < static_cast<jlong>(ch) * stride * 4 || outCap < static_cast<jlong>(numFrames) * 8) return;
  const float* ptrs[ChannelPlacer::kMaxChannels];
  for (int c = 0; c < ch; ++c) ptrs[c] = in + static_cast<size_t>(c) * stride;
  p->process(ptrs, numFrames, out);
}

}  // extern "C"
