// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphTiming

/**
 * Loads `libmonochrome_glyph.so`, once, the first time Trypt Mania needs it.
 *
 * A failure is remembered rather than thrown: the engine then plays on its
 * Kotlin twin, which is the same rules. On the JVM — the unit tests — the load
 * always fails, and that is the path the tests take.
 */
internal object GlyphNativeLoader {
    @Volatile private var attempted = false
    @Volatile var available = false
        private set
    @Volatile var loadError: String? = null
        private set

    fun ensureLoaded(): Boolean {
        if (attempted) return available
        synchronized(this) {
            if (attempted) return available
            try {
                System.loadLibrary("monochrome_glyph")
                available = true
            } catch (error: Throwable) {
                loadError = error.message ?: error.javaClass.simpleName
                available = false
                // Logged if logging exists; on the JVM android.util.Log is a
                // stub that throws, and a missing log line is not a reason to
                // fail the fallback.
                runCatching { android.util.Log.w(TAG, "native engine unavailable: $loadError") }
            }
            attempted = true
        }
        return available
    }

    private const val TAG = "TryptifyGlyph"
}

/**
 * The JNI surface. Mirrors `cpp/glyph/glyph_jni.cpp`, strides included.
 */
internal object GlyphNativeBridge {
    // Strides shared with glyph_jni.cpp.
    const val EVENT_INTS = 6
    const val SCORE_INTS = 16
    const val SCORE_FLOATS = 4

    // GlyphScrollMode, as glyph_engine.h numbers it.
    const val SCROLL_X = 0
    const val SCROLL_C = 1
    const val SCROLL_M = 2

    external fun nativeCreate(
        times: FloatArray,
        ends: FloatArray,
        lanes: IntArray,
        types: IntArray,
        offsetSeconds: Float,
        segmentBeats: FloatArray,
        segmentBpms: FloatArray,
        stopBeats: FloatArray,
        stopSeconds: FloatArray,
        windows: FloatArray,
        config: FloatArray,
    ): Long

    external fun nativeDestroy(ptr: Long)
    external fun nativeAdvanceTo(ptr: Long, songSeconds: Float)
    external fun nativePress(ptr: Long, lane: Int, songSeconds: Float, outInts: IntArray, outFloats: FloatArray): Boolean
    external fun nativeRelease(ptr: Long, lane: Int, songSeconds: Float, outInts: IntArray, outFloats: FloatArray): Boolean
    external fun nativeReset(ptr: Long, fromSeconds: Float, untilSeconds: Float)
    external fun nativeDrainEvents(ptr: Long, outInts: IntArray, outFloats: FloatArray, capacity: Int): Int
    external fun nativeFillVisible(
        ptr: Long,
        fromSeconds: Float,
        toSeconds: Float,
        positionSeconds: Float,
        scrollKind: Int,
        multiplier: Float,
        targetBpm: Float,
        chartMaxBpm: Float,
        outInts: IntArray,
        outFloats: FloatArray,
        capacity: Int,
    ): Int
    external fun nativeReadScore(ptr: Long, outInts: IntArray, outFloats: FloatArray)
    external fun nativeCombo(ptr: Long): Int
    external fun nativeHeldMask(ptr: Long): Int
    external fun nativeIsFinished(ptr: Long): Boolean
    external fun nativePosition(ptr: Long): Float
}

/**
 * [GlyphEngineCore] on the C++ engine.
 *
 * Holds the native handle and the few arrays every call reuses. After
 * [close] every call is a no-op, so a playfield still holding this engine for
 * one more frame after a restart draws nothing rather than touching freed
 * memory.
 */
internal class NativeGlyphEngineCore private constructor(
    private val chart: GlyphChart,
    private var handle: Long,
) : GlyphEngineCore {

    private val eventInts = IntArray(GlyphNativeBridge.EVENT_INTS)
    private val eventFloats = FloatArray(1)
    private var drainInts = IntArray(DRAIN_BATCH * GlyphNativeBridge.EVENT_INTS)
    private var drainFloats = FloatArray(DRAIN_BATCH)
    private val scoreInts = IntArray(GlyphNativeBridge.SCORE_INTS)
    private val scoreFloats = FloatArray(GlyphNativeBridge.SCORE_FLOATS)

    override val positionSeconds: Float
        get() = handle.let { if (it == 0L) 0f else GlyphNativeBridge.nativePosition(it) }

    override val combo: Int
        get() = handle.let { if (it == 0L) 0 else GlyphNativeBridge.nativeCombo(it) }

    override val isFinished: Boolean
        get() = handle.let { it == 0L || GlyphNativeBridge.nativeIsFinished(it) }

    override val heldMask: Int
        get() = handle.let { if (it == 0L) 0 else GlyphNativeBridge.nativeHeldMask(it) }

    override fun advanceTo(songSeconds: Float) {
        val ptr = handle
        if (ptr != 0L) GlyphNativeBridge.nativeAdvanceTo(ptr, songSeconds)
    }

    override fun press(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? {
        val ptr = handle
        if (ptr == 0L) return null
        if (!GlyphNativeBridge.nativePress(ptr, lane.ordinal, songSeconds, eventInts, eventFloats)) return null
        return eventAt(eventInts, eventFloats, 0)
    }

    override fun release(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? {
        val ptr = handle
        if (ptr == 0L) return null
        if (!GlyphNativeBridge.nativeRelease(ptr, lane.ordinal, songSeconds, eventInts, eventFloats)) return null
        return eventAt(eventInts, eventFloats, 0)
    }

    override fun reset(fromSeconds: Float, untilSeconds: Float) {
        val ptr = handle
        if (ptr != 0L) GlyphNativeBridge.nativeReset(ptr, fromSeconds, untilSeconds)
    }

    override fun drainEvents(): List<GlyphJudgementEvent> {
        val ptr = handle
        if (ptr == 0L) return emptyList()
        var drained: ArrayList<GlyphJudgementEvent>? = null
        while (true) {
            val n = GlyphNativeBridge.nativeDrainEvents(ptr, drainInts, drainFloats, DRAIN_BATCH)
            if (n <= 0) break
            val list = drained ?: ArrayList<GlyphJudgementEvent>(n).also { drained = it }
            for (i in 0 until n) list += eventAt(drainInts, drainFloats, i)
            if (n < DRAIN_BATCH) break
        }
        return drained ?: emptyList()
    }

    override fun scoreSnapshot(): GlyphScoreSnapshot {
        val ptr = handle
        if (ptr == 0L) return GlyphScoreSnapshot()
        GlyphNativeBridge.nativeReadScore(ptr, scoreInts, scoreFloats)
        val i = scoreInts
        return GlyphScoreSnapshot(
            score = i[14],
            combo = i[6],
            maxCombo = i[7],
            accuracy = scoreFloats[0],
            finalAccuracy = scoreFloats[1],
            judged = i[12],
            totalJudgements = i[13],
            counts = JUDGEMENTS.associateWith { i[it.ordinal] },
            early = i[8],
            late = i[9],
            minesHit = i[10],
            holdsDropped = i[11],
            meanOffsetSeconds = scoreFloats[2],
            offsetDeviationSeconds = scoreFloats[3],
        )
    }

    override fun fillVisible(
        fromSeconds: Float,
        toSeconds: Float,
        positionSeconds: Float,
        scrollMode: GlyphScrollMode,
        frame: GlyphVisibleFrame,
    ) {
        val ptr = handle
        if (ptr == 0L) {
            frame.count = 0
            return
        }
        var kind = GlyphNativeBridge.SCROLL_C
        var multiplier = 1f
        var target = 0f
        var chartMax = 0f
        when (scrollMode) {
            is GlyphScrollMode.XMod -> {
                kind = GlyphNativeBridge.SCROLL_X
                multiplier = scrollMode.multiplier
            }
            is GlyphScrollMode.CMod -> target = scrollMode.targetBpm
            is GlyphScrollMode.MMod -> {
                kind = GlyphNativeBridge.SCROLL_M
                target = scrollMode.targetBpm
                chartMax = scrollMode.chartMaxBpm
            }
        }
        var total = GlyphNativeBridge.nativeFillVisible(
            ptr, fromSeconds, toSeconds, positionSeconds, kind, multiplier, target, chartMax,
            frame.ints, frame.floats, frame.capacity,
        )
        if (total > frame.capacity) {
            // A denser screen than any before it: grow once and ask again.
            frame.ensureCapacity(total)
            total = GlyphNativeBridge.nativeFillVisible(
                ptr, fromSeconds, toSeconds, positionSeconds, kind, multiplier, target, chartMax,
                frame.ints, frame.floats, frame.capacity,
            )
        }
        frame.count = minOf(total, frame.capacity)
    }

    override fun close() {
        val ptr = handle
        if (ptr == 0L) return
        handle = 0L
        GlyphNativeBridge.nativeDestroy(ptr)
    }

    private fun eventAt(ints: IntArray, floats: FloatArray, row: Int): GlyphJudgementEvent {
        val base = row * GlyphNativeBridge.EVENT_INTS
        val noteIndex = ints[base + 2]
        val offset = floats[row]
        return GlyphJudgementEvent(
            lane = LANES[ints[base]],
            judgement = JUDGEMENTS[ints[base + 1]],
            offsetSeconds = if (offset.isNaN()) null else offset,
            songSeconds = chart.notes.getOrNull(noteIndex)?.timeSeconds ?: 0f,
            isTail = ints[base + 4] != 0,
            isMine = ints[base + 5] != 0,
            combo = ints[base + 3],
        )
    }

    companion object {
        private const val DRAIN_BATCH = 64
        private val LANES = GlyphLane.entries
        private val JUDGEMENTS = GlyphJudgement.entries

        /** The native engine for [chart], or null when the library is unavailable. */
        fun create(
            chart: GlyphChart,
            timing: GlyphTiming,
            windows: GlyphTimingWindows,
            config: GlyphGameplayEngine.Config,
        ): NativeGlyphEngineCore? {
            if (!GlyphNativeLoader.ensureLoaded()) return null
            val notes = chart.notes
            val ptr = try {
                GlyphNativeBridge.nativeCreate(
                    times = FloatArray(notes.size) { notes[it].timeSeconds },
                    ends = FloatArray(notes.size) { notes[it].endTimeSeconds },
                    lanes = IntArray(notes.size) { notes[it].lane.ordinal },
                    types = IntArray(notes.size) { notes[it].type.ordinal },
                    offsetSeconds = timing.offsetSeconds,
                    segmentBeats = FloatArray(timing.segments.size) { timing.segments[it].beat },
                    segmentBpms = FloatArray(timing.segments.size) { timing.segments[it].bpm },
                    stopBeats = FloatArray(timing.stops.size) { timing.stops[it].beat },
                    stopSeconds = FloatArray(timing.stops.size) { timing.stops[it].seconds },
                    windows = floatArrayOf(
                        windows.marvelousSeconds,
                        windows.perfectSeconds,
                        windows.greatSeconds,
                        windows.goodSeconds,
                        windows.booSeconds,
                    ),
                    config = floatArrayOf(
                        config.holdGraceSeconds,
                        config.rollIntervalSeconds,
                        config.audioOffsetSeconds,
                    ),
                )
            } catch (error: UnsatisfiedLinkError) {
                // A library that loaded but lacks a symbol: an APK built from
                // mismatched sources. Play on the twin rather than crash.
                0L
            }
            return if (ptr == 0L) null else NativeGlyphEngineCore(chart, ptr)
        }
    }
}
