// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * The rules of play, behind [GlyphGameplayEngine].
 *
 * Two implementations: [NativeGlyphEngineCore], the C++ engine in
 * `cpp/glyph`, which is what plays on a device; and [KotlinGlyphEngineCore],
 * its twin, which the JVM unit tests drive (they cannot load a `.so`) and
 * which takes over if the native library fails to load. The two are kept to
 * the same arithmetic in the same order, and `cpp/glyph/tests` asserts the
 * numbers the Kotlin tests do, so they cannot quietly disagree.
 *
 * Single-threaded by contract: the tick, the input and the playfield's draw
 * all call in from the main thread.
 */
internal interface GlyphEngineCore {
    val positionSeconds: Float
    val combo: Int
    val isFinished: Boolean
    /** Bit n set while lane n is down. */
    val heldMask: Int

    fun advanceTo(songSeconds: Float)
    fun press(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent?
    fun release(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent?
    fun reset(fromSeconds: Float, untilSeconds: Float)
    fun drainEvents(): List<GlyphJudgementEvent>
    fun scoreSnapshot(): GlyphScoreSnapshot
    fun fillVisible(
        fromSeconds: Float,
        toSeconds: Float,
        positionSeconds: Float,
        scrollMode: GlyphScrollMode,
        frame: GlyphVisibleFrame,
    )

    /** Frees anything the core holds outside the heap. Safe to call twice. */
    fun close() {}
}

/**
 * The notes the playfield is about to draw, already placed.
 *
 * Filled in place every frame and kept between frames, so drawing a dense
 * chart allocates nothing: no list, no per-note object. Positions are in
 * scroll units from the current position (see [GlyphScrollMode]); the
 * playfield multiplies them by its pixels per unit.
 *
 * The arrays use the layout the native engine writes, so the C++ side fills
 * them directly.
 */
class GlyphVisibleFrame(initialCapacity: Int = 64) {
    var count: Int = 0
        internal set

    internal var ints = IntArray(initialCapacity * INTS)
        private set
    internal var floats = FloatArray(initialCapacity * FLOATS)
        private set

    internal val capacity: Int get() = ints.size / INTS

    internal fun ensureCapacity(notes: Int) {
        if (notes <= capacity) return
        val grown = maxOf(notes, capacity * 2)
        ints = IntArray(grown * INTS)
        floats = FloatArray(grown * FLOATS)
    }

    /** Index into the engine's chart notes. */
    fun noteIndex(i: Int): Int = ints[i * INTS]
    fun isHeld(i: Int): Boolean = ints[i * INTS + 1] and FLAG_HELD != 0
    fun isHeadJudged(i: Int): Boolean = ints[i * INTS + 1] and FLAG_HEAD_JUDGED != 0

    /** Units from the position to the note's head. */
    fun headUnits(i: Int): Float = floats[i * FLOATS]

    /** The same for the top of a hold body, which stops at the receptor while held. */
    fun bodyHeadUnits(i: Int): Float = floats[i * FLOATS + 1]

    /** Units from the position to the tail; the head's for a note without one. */
    fun tailUnits(i: Int): Float = floats[i * FLOATS + 2]

    internal fun put(
        slot: Int,
        noteIndex: Int,
        held: Boolean,
        headJudged: Boolean,
        headUnits: Float,
        bodyHeadUnits: Float,
        tailUnits: Float,
    ) {
        ints[slot * INTS] = noteIndex
        ints[slot * INTS + 1] = (if (held) FLAG_HELD else 0) or (if (headJudged) FLAG_HEAD_JUDGED else 0)
        floats[slot * FLOATS] = headUnits
        floats[slot * FLOATS + 1] = bodyHeadUnits
        floats[slot * FLOATS + 2] = tailUnits
    }

    internal companion object {
        // Strides shared with glyph_jni.cpp (kVisibleInts / kVisibleFloats).
        const val INTS = 2
        const val FLOATS = 3
        const val FLAG_HELD = 1
        const val FLAG_HEAD_JUDGED = 2
    }
}
