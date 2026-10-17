// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI surface for the Trypt Mania engine. Mirrors GlyphNativeBridge.kt.
//
// Everything crosses as flat primitive arrays with fixed strides, the same
// shape the sampler uses: one JNI transition per call, and buffers that are
// allocated once on each side and reused every frame. The strides below are
// duplicated in GlyphNativeBridge and must change with them.
//
// Every entry point is called from the main thread (see glyph_engine.h), so
// the scratch buffers kept beside each engine need no locking.

#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <limits>
#include <new>
#include <utility>
#include <vector>

#include "glyph_engine.h"
#include "glyph_timing.h"

#define LOG_TAG "TryptifyGlyph"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using tryptify::glyph::BpmSegment;
using tryptify::glyph::Config;
using tryptify::glyph::Engine;
using tryptify::glyph::Event;
using tryptify::glyph::kJudgementCount;
using tryptify::glyph::Note;
using tryptify::glyph::Scroll;
using tryptify::glyph::ScoreState;
using tryptify::glyph::Stop;
using tryptify::glyph::Timing;
using tryptify::glyph::VisibleNote;
using tryptify::glyph::Windows;

namespace {

// GlyphNativeBridge.EVENT_INTS / VISIBLE_INTS / VISIBLE_FLOATS / SCORE_*.
constexpr int kEventInts = 6;     // lane, judgement, noteIndex, combo, isTail, isMine
constexpr int kVisibleInts = 2;   // noteIndex, flags (1 = held, 2 = head judged)
constexpr int kVisibleFloats = 3; // headUnits, bodyHeadUnits, tailUnits
constexpr int kScoreInts = 16;    // counts[6], combo, maxCombo, early, late, mines,
                                  // holdsDropped, judged, total, score, finished
constexpr int kScoreFloats = 4;   // accuracy, finalAccuracy, meanOffset, deviation

struct Handle {
    Engine engine;
    // Reused for every frame and drain; grown only if a caller asks for more.
    std::vector<VisibleNote> visible;
    std::vector<Event> events;
    std::vector<jint> ints;
    std::vector<jfloat> floats;

    Handle(Engine e) : engine(std::move(e)) {}

    void ensure(std::size_t notes, std::size_t intCount, std::size_t floatCount) {
        if (visible.size() < notes) visible.resize(notes);
        if (events.size() < notes) events.resize(notes);
        if (ints.size() < intCount) ints.resize(intCount);
        if (floats.size() < floatCount) floats.resize(floatCount);
    }
};

inline Handle* handleOf(jlong ptr) { return reinterpret_cast<Handle*>(ptr); }

std::vector<float> floats(JNIEnv* env, jfloatArray array) {
    std::vector<float> out;
    if (array == nullptr) return out;
    out.resize(static_cast<std::size_t>(env->GetArrayLength(array)));
    if (!out.empty()) env->GetFloatArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return out;
}

std::vector<jint> ints(JNIEnv* env, jintArray array) {
    std::vector<jint> out;
    if (array == nullptr) return out;
    out.resize(static_cast<std::size_t>(env->GetArrayLength(array)));
    if (!out.empty()) env->GetIntArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return out;
}

void writeEvent(JNIEnv* env, const Event& e, jintArray outInts, jfloatArray outFloats) {
    if (outInts == nullptr || outFloats == nullptr) return;
    if (env->GetArrayLength(outInts) < kEventInts || env->GetArrayLength(outFloats) < 1) return;
    const jint values[kEventInts] = {e.lane, e.judgement, e.noteIndex, e.combo,
                                     e.isTail ? 1 : 0, e.isMine ? 1 : 0};
    env->SetIntArrayRegion(outInts, 0, kEventInts, values);
    env->SetFloatArrayRegion(outFloats, 0, 1, &e.offsetSeconds);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeCreate(
    JNIEnv* env, jobject,
    jfloatArray times, jfloatArray ends, jintArray lanes, jintArray types,
    jfloat offsetSeconds,
    jfloatArray segmentBeats, jfloatArray segmentBpms,
    jfloatArray stopBeats, jfloatArray stopSeconds,
    jfloatArray windows, jfloatArray config) {
    const auto t = floats(env, times);
    const auto e = floats(env, ends);
    const auto l = ints(env, lanes);
    const auto ty = ints(env, types);
    if (t.size() != e.size() || t.size() != l.size() || t.size() != ty.size()) {
        LOGE("note arrays disagree in length: %zu %zu %zu %zu", t.size(), e.size(), l.size(), ty.size());
        return 0;
    }
    std::vector<Note> notes(t.size());
    for (std::size_t i = 0; i < t.size(); ++i) {
        // A lane past the arrays would be a write past them on its hold.
        if (l[i] < 0 || l[i] >= tryptify::glyph::kLaneCount) {
            LOGE("note %zu is on lane %d, outside 0..%d", i, l[i], tryptify::glyph::kLaneCount - 1);
            return 0;
        }
        notes[i] = Note{t[i], e[i], l[i], ty[i]};
    }

    const auto sb = floats(env, segmentBeats);
    const auto sp = floats(env, segmentBpms);
    const auto pb = floats(env, stopBeats);
    const auto ps = floats(env, stopSeconds);
    if (sb.size() != sp.size() || pb.size() != ps.size()) {
        LOGE("timing arrays disagree in length");
        return 0;
    }
    std::vector<BpmSegment> segments(sb.size());
    for (std::size_t i = 0; i < sb.size(); ++i) segments[i] = BpmSegment{sb[i], sp[i]};
    std::vector<Stop> stops(pb.size());
    for (std::size_t i = 0; i < pb.size(); ++i) stops[i] = Stop{pb[i], ps[i]};

    const auto w = floats(env, windows);
    const auto c = floats(env, config);
    if (w.size() < 5 || c.size() < 3) {
        LOGE("windows or config too short: %zu %zu", w.size(), c.size());
        return 0;
    }
    const Windows win{w[0], w[1], w[2], w[3], w[4]};
    Config cfg;
    cfg.holdGraceSeconds = c[0];
    cfg.rollIntervalSeconds = c[1];
    cfg.audioOffsetSeconds = c[2];

    auto* handle = new (std::nothrow) Handle(
        Engine(std::move(notes), Timing(offsetSeconds, std::move(segments), std::move(stops)), win, cfg));
    if (handle == nullptr) {
        LOGE("out of memory creating the engine");
        return 0;
    }
    const auto count = static_cast<std::size_t>(handle->engine.noteCount());
    // Room for every note at once, so no frame ever grows these.
    handle->ensure(count + 1, (count + 1) * kEventInts, (count + 1) * kVisibleFloats);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeDestroy(JNIEnv*, jobject, jlong ptr) {
    delete handleOf(ptr);
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeAdvanceTo(
    JNIEnv*, jobject, jlong ptr, jfloat songSeconds) {
    if (auto* h = handleOf(ptr)) h->engine.advanceTo(songSeconds);
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativePress(
    JNIEnv* env, jobject, jlong ptr, jint lane, jfloat songSeconds,
    jintArray outInts, jfloatArray outFloats) {
    auto* h = handleOf(ptr);
    if (h == nullptr) return JNI_FALSE;
    Event e{};
    if (!h->engine.press(lane, songSeconds, &e)) return JNI_FALSE;
    writeEvent(env, e, outInts, outFloats);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeRelease(
    JNIEnv* env, jobject, jlong ptr, jint lane, jfloat songSeconds,
    jintArray outInts, jfloatArray outFloats) {
    auto* h = handleOf(ptr);
    if (h == nullptr) return JNI_FALSE;
    Event e{};
    if (!h->engine.release(lane, songSeconds, &e)) return JNI_FALSE;
    writeEvent(env, e, outInts, outFloats);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeReset(
    JNIEnv*, jobject, jlong ptr, jfloat fromSeconds, jfloat untilSeconds) {
    if (auto* h = handleOf(ptr)) h->engine.reset(fromSeconds, untilSeconds);
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeDrainEvents(
    JNIEnv* env, jobject, jlong ptr, jintArray outInts, jfloatArray outFloats, jint capacity) {
    auto* h = handleOf(ptr);
    if (h == nullptr || outInts == nullptr || outFloats == nullptr || capacity <= 0) return 0;
    const jint fit = std::min<jint>(capacity, std::min<jint>(env->GetArrayLength(outInts) / kEventInts,
                                                             env->GetArrayLength(outFloats)));
    if (fit <= 0) return 0;
    h->ensure(static_cast<std::size_t>(fit), static_cast<std::size_t>(fit) * kEventInts,
              static_cast<std::size_t>(fit));
    const jint n = h->engine.drainEvents(h->events.data(), fit);
    for (jint i = 0; i < n; ++i) {
        const Event& e = h->events[static_cast<std::size_t>(i)];
        jint* row = &h->ints[static_cast<std::size_t>(i) * kEventInts];
        row[0] = e.lane;
        row[1] = e.judgement;
        row[2] = e.noteIndex;
        row[3] = e.combo;
        row[4] = e.isTail ? 1 : 0;
        row[5] = e.isMine ? 1 : 0;
        h->floats[static_cast<std::size_t>(i)] = e.offsetSeconds;
    }
    if (n > 0) {
        env->SetIntArrayRegion(outInts, 0, n * kEventInts, h->ints.data());
        env->SetFloatArrayRegion(outFloats, 0, n, h->floats.data());
    }
    return n;
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeFillVisible(
    JNIEnv* env, jobject, jlong ptr,
    jfloat fromSeconds, jfloat toSeconds, jfloat positionSeconds,
    jint scrollKind, jfloat multiplier, jfloat targetBpm, jfloat chartMaxBpm,
    jintArray outInts, jfloatArray outFloats, jint capacity) {
    auto* h = handleOf(ptr);
    if (h == nullptr || outInts == nullptr || outFloats == nullptr) return 0;
    const jint fit = std::max<jint>(0, std::min<jint>(capacity, std::min<jint>(
        env->GetArrayLength(outInts) / kVisibleInts, env->GetArrayLength(outFloats) / kVisibleFloats)));
    h->ensure(static_cast<std::size_t>(fit), static_cast<std::size_t>(fit) * kVisibleInts,
              static_cast<std::size_t>(fit) * kVisibleFloats);

    Scroll scroll;
    scroll.kind = scrollKind;
    scroll.multiplier = multiplier;
    scroll.targetBpm = targetBpm;
    scroll.chartMaxBpm = chartMaxBpm;
    const jint total = h->engine.fillVisible(fromSeconds, toSeconds, positionSeconds, scroll,
                                             h->visible.data(), fit);
    const jint written = std::min(total, fit);
    for (jint i = 0; i < written; ++i) {
        const VisibleNote& v = h->visible[static_cast<std::size_t>(i)];
        h->ints[static_cast<std::size_t>(i) * kVisibleInts] = v.noteIndex;
        h->ints[static_cast<std::size_t>(i) * kVisibleInts + 1] =
            (v.isHeld ? 1 : 0) | (v.isHeadJudged ? 2 : 0);
        jfloat* row = &h->floats[static_cast<std::size_t>(i) * kVisibleFloats];
        row[0] = v.headUnits;
        row[1] = v.bodyHeadUnits;
        row[2] = v.tailUnits;
    }
    if (written > 0) {
        env->SetIntArrayRegion(outInts, 0, written * kVisibleInts, h->ints.data());
        env->SetFloatArrayRegion(outFloats, 0, written * kVisibleFloats, h->floats.data());
    }
    // The true count, so a short buffer can be grown and the call repeated.
    return total;
}

JNIEXPORT void JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeReadScore(
    JNIEnv* env, jobject, jlong ptr, jintArray outInts, jfloatArray outFloats) {
    auto* h = handleOf(ptr);
    if (h == nullptr || outInts == nullptr || outFloats == nullptr) return;
    if (env->GetArrayLength(outInts) < kScoreInts || env->GetArrayLength(outFloats) < kScoreFloats) {
        LOGE("score arrays too short");
        return;
    }
    const ScoreState s = h->engine.score();
    jint i[kScoreInts];
    for (int j = 0; j < kJudgementCount; ++j) i[j] = s.counts[j];
    i[6] = s.combo;
    i[7] = s.maxCombo;
    i[8] = s.early;
    i[9] = s.late;
    i[10] = s.minesHit;
    i[11] = s.holdsDropped;
    i[12] = s.judged;
    i[13] = s.totalJudgements;
    i[14] = s.score;
    i[15] = h->engine.isFinished() ? 1 : 0;
    const jfloat f[kScoreFloats] = {s.accuracy, s.finalAccuracy, s.meanOffsetSeconds,
                                    s.offsetDeviationSeconds};
    env->SetIntArrayRegion(outInts, 0, kScoreInts, i);
    env->SetFloatArrayRegion(outFloats, 0, kScoreFloats, f);
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeCombo(JNIEnv*, jobject, jlong ptr) {
    auto* h = handleOf(ptr);
    return h == nullptr ? 0 : h->engine.combo();
}

JNIEXPORT jint JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeHeldMask(JNIEnv*, jobject, jlong ptr) {
    auto* h = handleOf(ptr);
    return h == nullptr ? 0 : h->engine.heldMask();
}

JNIEXPORT jboolean JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativeIsFinished(JNIEnv*, jobject, jlong ptr) {
    auto* h = handleOf(ptr);
    return (h == nullptr || h->engine.isFinished()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_tf_monochrome_android_glyph_engine_GlyphNativeBridge_nativePosition(JNIEnv*, jobject, jlong ptr) {
    auto* h = handleOf(ptr);
    return h == nullptr ? 0.0f : h->engine.positionSeconds();
}

}  // extern "C"
