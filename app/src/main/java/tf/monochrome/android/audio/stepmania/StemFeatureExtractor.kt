// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem

/**
 * One [TransientEnvelopeBuilder] per [StemLayer], fed a piece at a time like
 * the drums' own.
 *
 * Every layer is fed every frame the drums are. A layer whose stems the
 * separator did not return for a piece hears silence there, so all of them
 * stay on the drums' timeline; one that was never returned at all stays
 * silent throughout, and [StemFeatureExtractor] leaves it out.
 */
class StemLayerEnvelopes(val sampleRate: Int) {

    val builders: Map<StemLayer, TransientEnvelopeBuilder> =
        StemLayer.entries.associateWith { TransientEnvelopeBuilder(sampleRate) }

    private var scratch = FloatArray(0)

    /**
     * Frames [from] until [from] + [frames] of each layer's stems in [stems],
     * where every stem was separated from the same [expectedFrames] of audio.
     * A stem that does not line up with that is treated as missing, as the
     * drums are.
     */
    fun add(stems: Map<Stem, SampleEdits.Buffer>, expectedFrames: Int, from: Int, frames: Int) {
        for ((layer, builder) in builders) {
            val parts = layer.stems.mapNotNull { stem ->
                stems[stem]?.takeIf { it.frames == expectedFrames && it.sampleRate == sampleRate }
            }
            if (parts.size == 1) {
                val only = parts[0]
                builder.add(only.left, only.right, from, from + frames)
                continue
            }
            // Silence for a layer with nothing, and the stems' mono sum for one
            // with several — the same mono the builder would take of each.
            if (scratch.size < frames) scratch = FloatArray(frames)
            scratch.fill(0f, 0, frames)
            for (part in parts) {
                val right = part.right
                for (i in 0 until frames) {
                    val at = from + i
                    scratch[i] += if (right != null) (part.left[at] + right[at]) * 0.5f else part.left[at]
                }
            }
            builder.add(scratch, null, 0, frames)
        }
    }
}

/**
 * What the non-drum stems do on the drums' sixteenth-note grid.
 *
 * Three things a charter listens for besides the beat:
 *
 * * where a note starts — a sung syllable, a melody note, a bass note — so an
 *   arrow can land on the tune and not only on the kit;
 * * how long a sung note is held, for a freeze arrow;
 * * which way the tune moves between notes, so the arrows can step with it:
 *   right as it rises, left as it falls.
 *
 * Note starts are read as rises in the stem's envelope, which ignores the long
 * body of a held note and catches its attack. Pitch is read from how often
 * the stem crosses zero — rough, and wrong in absolute terms, but it goes up
 * and down with the tune, and the direction is all the chart uses.
 */
internal object StemFeatureExtractor {

    private const val ENVELOPE_HZ = StemRhythmAnalyzer.ENVELOPE_HZ

    /** How much an instrument's note counts against a sung one: the voice usually carries the tune. */
    private const val INSTRUMENT_WEIGHT = 0.8f

    /** How sharply a sung note has to start before its length is measured for a hold. */
    private const val HOLD_ONSET = 0.5f

    /** The longest a sung note is followed for, so one drone cannot become one hold. */
    private const val MAX_HOLD_SECONDS = 8f

    fun extract(
        rhythm: RhythmAnalysis,
        layers: Map<StemLayer, TransientEnvelopeBuilder>,
    ): StemFeatures? {
        val tracks = layers.mapNotNull { (layer, builder) -> Track.of(builder)?.let { layer to it } }.toMap()
        if (tracks.isEmpty()) return null

        val ticks = rhythm.gridStrengths.size
        val hopsPerTick = rhythm.subdivisionSeconds * ENVELOPE_HZ
        val vocals = tracks[StemLayer.VOCALS]
        val melody = tracks[StemLayer.MELODY]
        val bass = tracks[StemLayer.BASS]

        val melodyOnsets = FloatArray(ticks)
        val bassOnsets = FloatArray(ticks)
        val sustainTicks = IntArray(ticks)
        val contour = FloatArray(ticks)

        for (tick in 0 until ticks) {
            val frame = ((rhythm.offsetSeconds + tick * rhythm.subdivisionSeconds) * ENVELOPE_HZ).roundToInt()
            val sung = vocals?.onsetAt(frame) ?: 0f
            val played = (melody?.onsetAt(frame) ?: 0f) * INSTRUMENT_WEIGHT
            melodyOnsets[tick] = max(sung, played)
            // The pitch of whichever started the note: the tune is the part
            // that moved, not a pad holding underneath it.
            contour[tick] = (if (sung >= played) vocals else melody)?.pitchAt(frame) ?: 0f
            bassOnsets[tick] = bass?.onsetAt(frame) ?: 0f
            if (vocals != null && sung >= HOLD_ONSET) {
                val hops = vocals.heldHops(frame, (MAX_HOLD_SECONDS * ENVELOPE_HZ).toInt())
                sustainTicks[tick] = (hops / hopsPerTick).toInt()
            }
        }

        return StemFeatures(
            melodyOnsets = melodyOnsets,
            bassOnsets = bassOnsets,
            sustainTicks = sustainTicks,
            contour = contour,
            layers = tracks.keys,
        )
    }

    /** One layer's envelope, read three ways. */
    private class Track(
        /** 0..1, how loud each hop is against the rest of the layer. */
        val level: FloatArray,
        /** 0..1, how sharply each hop rises over the few before it. */
        val onset: FloatArray,
        /** log2 of each hop's zero-crossing rate: up an octave is up one. */
        val pitch: FloatArray,
    ) {
        /** The strongest note start within a few hops of [frame], later more than earlier: an attack peaks after it begins. */
        fun onsetAt(frame: Int): Float = peak(onset, frame - 2, frame + 4)

        /** The pitch just after [frame], where the note has settled; 0 when the layer is quiet there. */
        fun pitchAt(frame: Int): Float {
            val heard = ArrayList<Float>(9)
            for (hop in frame..frame + 8) {
                if (hop in level.indices && level[hop] >= AUDIBLE && pitch[hop] > 0f) heard += pitch[hop]
            }
            if (heard.isEmpty()) return 0f
            heard.sort()
            return heard[heard.size / 2]
        }

        /**
         * How many hops a note starting at [frame] rings for: until it falls
         * well below its own peak, or a fresh note starts over it.
         */
        fun heldHops(frame: Int, limit: Int): Int {
            if (frame !in level.indices) return 0
            val attackEnd = (frame + ATTACK_HOPS).coerceAtMost(level.lastIndex)
            val noteLevel = peak(level, frame, attackEnd)
            if (noteLevel < HELD_LEVEL) return 0
            var hop = attackEnd + 1
            while (
                hop < level.size &&
                hop - frame < limit &&
                level[hop] >= noteLevel * SUSTAIN_SHARE &&
                onset[hop] < NEW_NOTE
            ) {
                hop += 1
            }
            return hop - frame
        }

        companion object {
            const val AUDIBLE = 0.25f
            const val ATTACK_HOPS = 6
            const val HELD_LEVEL = 0.35f
            const val SUSTAIN_SHARE = 0.55f
            const val NEW_NOTE = 0.6f

            /** Below this, on the builder's log scale, is about -66 dBFS: a stem the separator left empty. */
            private const val SILENT = 0.02f

            fun of(builder: TransientEnvelopeBuilder): Track? {
                val raw = builder.raw()
                if (raw.isEmpty() || percentile(raw, 0.98f) <= SILENT) return null

                val rise = FloatArray(raw.size) { hop ->
                    var floor = raw[hop]
                    for (back in 1..4) if (hop - back >= 0) floor = minOf(floor, raw[hop - back])
                    raw[hop] - floor
                }
                val rates = builder.crossingRates()
                return Track(
                    level = normalize(raw, percentile(raw, 0.1f), percentile(raw, 0.95f)),
                    onset = normalize(rise, 0f, percentile(rise, 0.98f)),
                    pitch = FloatArray(rates.size) { hop ->
                        if (rates[hop] > 1f) (ln(rates[hop].toDouble()) / LN2).toFloat() else 0f
                    },
                )
            }

            private val LN2 = ln(2.0)

            private fun percentile(values: FloatArray, share: Float): Float {
                val sorted = values.sortedArray()
                return sorted[(sorted.lastIndex * share).roundToInt()]
            }

            private fun normalize(values: FloatArray, floor: Float, ceiling: Float): FloatArray {
                val range = ceiling - floor
                if (range <= 1e-6f) return FloatArray(values.size)
                return FloatArray(values.size) { ((values[it] - floor) / range).coerceIn(0f, 1f) }
            }

            private fun peak(values: FloatArray, from: Int, to: Int): Float {
                var best = 0f
                for (index in from.coerceAtLeast(0)..to.coerceAtMost(values.lastIndex)) {
                    best = max(best, values[index])
                }
                return best
            }
        }
    }
}
