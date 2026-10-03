// JNI for SpectrumWaterfall (spectrum_waterfall.h), behind WaterfallNative.kt.
//
// One instance per overlay — the player's and the Settings preview each keep
// their own history — created and freed with the composable. Everything runs
// on the UI thread, so there is no locking; render allocates nothing.

#include <jni.h>
#include <new>

#include "spectrum_waterfall.h"

using tryptify::SpectrumWaterfall;

extern "C" {

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_audio_eq_WaterfallNative_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new (std::nothrow) SpectrumWaterfall());
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_eq_WaterfallNative_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<SpectrumWaterfall*>(handle);
}

// Returns the number of lines written, back to front; see SpectrumWaterfall::render.
JNIEXPORT jint JNICALL
Java_tf_monochrome_android_audio_eq_WaterfallNative_nativeRender(
        JNIEnv* env, jclass, jlong handle, jfloatArray bins, jdouble nowSec,
        jfloat width, jfloat height, jfloat depthSeconds, jfloat fadeStart, jfloat angleDeg,
        jfloat floorDb, jfloat headroomDb, jfloatArray segs, jfloatArray meta) {
    auto* w = reinterpret_cast<SpectrumWaterfall*>(handle);
    if (!w) return 0;
    constexpr int kLines = SpectrumWaterfall::kRows + 1;
    if (env->GetArrayLength(segs) < kLines * SpectrumWaterfall::kFloatsPerRow) return 0;
    if (env->GetArrayLength(meta) < kLines * SpectrumWaterfall::kMetaPerRow) return 0;

    SpectrumWaterfall::Params p;
    p.width = width;
    p.height = height;
    p.depthSeconds = depthSeconds;
    p.fadeStart = fadeStart;
    p.angleDeg = angleDeg;
    p.floorDb = floorDb;
    p.headroomDb = headroomDb;

    const jsize nBins = env->GetArrayLength(bins);
    // Three critical regions at once is allowed; nothing between them calls
    // back into the JVM.
    auto* b = static_cast<float*>(env->GetPrimitiveArrayCritical(bins, nullptr));
    auto* s = static_cast<float*>(env->GetPrimitiveArrayCritical(segs, nullptr));
    auto* m = static_cast<float*>(env->GetPrimitiveArrayCritical(meta, nullptr));
    int lines = 0;
    if (b && s && m) lines = w->render(b, nBins, nowSec, p, s, m);
    if (m) env->ReleasePrimitiveArrayCritical(meta, m, 0);
    if (s) env->ReleasePrimitiveArrayCritical(segs, s, 0);
    if (b) env->ReleasePrimitiveArrayCritical(bins, b, JNI_ABORT);
    return lines;
}

// The preview's guides: baselines (px from the top) of the front line, the
// line where the fade begins and the last line, in out[0..2].
JNIEXPORT void JNICALL
Java_tf_monochrome_android_audio_eq_WaterfallNative_nativeGuides(
        JNIEnv* env, jclass, jfloat width, jfloat height, jfloat fadeStart, jfloat angleDeg,
        jfloatArray out) {
    if (env->GetArrayLength(out) < 3) return;
    SpectrumWaterfall::Params p;
    p.width = width;
    p.height = height;
    p.fadeStart = fadeStart;
    p.angleDeg = angleDeg;
    p = SpectrumWaterfall::clamp(p);
    const jfloat v[3] = {
        SpectrumWaterfall::baselineAt(0.f, p),
        SpectrumWaterfall::baselineAt(p.fadeStart, p),
        SpectrumWaterfall::baselineAt(1.f, p),
    };
    env->SetFloatArrayRegion(out, 0, 3, v);
}

// The preview's fade guide under a finger: the depth whose baseline is at [y].
JNIEXPORT jfloat JNICALL
Java_tf_monochrome_android_audio_eq_WaterfallNative_nativeDepthAt(
        JNIEnv*, jclass, jfloat y, jfloat width, jfloat height, jfloat angleDeg) {
    SpectrumWaterfall::Params p;
    p.width = width;
    p.height = height;
    p.angleDeg = angleDeg;
    return SpectrumWaterfall::depthAtBaseline(y, p);
}

}  // extern "C"
