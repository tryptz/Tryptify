package tf.monochrome.android.audio.dsp.spatial

import kotlinx.serialization.Serializable
import tf.monochrome.android.audio.dsp.ChannelDetectorProcessor
import tf.monochrome.android.domain.model.SpeakerChannel

/**
 * Where one channel of a multichannel bed sits around the listener, as the
 * spatial map sets it: an angle (0 ahead, negative to the left, as in
 * [SpeakerChannel]) and a distance, 1 being the speaker ring.
 */
@Serializable
data class ChannelPlacement(
    val azimuthDeg: Float,
    val distance: Float = 1f,
)

/**
 * The spatial map's settings. Off by default: until the listener turns it on,
 * a multichannel song folds to stereo by the fixed matrix exactly as before.
 *
 * [layouts] is keyed by channel count, so a 5.1 arrangement and a 9.1.6 one
 * are remembered separately; a count with nothing saved uses its speakers'
 * standard positions.
 */
@Serializable
data class SpatialPlacement(
    val enabled: Boolean = false,
    /** Binaural (headphones) when true, a stereo pan (speakers) when false. */
    val binaural: Boolean = true,
    val layouts: Map<String, List<ChannelPlacement>> = emptyMap(),
) {
    /** The placement for a [count]-channel stream: saved, or the standard one. */
    fun placementFor(count: Int): List<ChannelPlacement> {
        val speakers = SpatialLayout.speakers(count)
        val saved = layouts[count.toString()]
        return if (saved != null && saved.size == speakers.size) saved.map { it.clamped() }
        else speakers.map { ChannelPlacement(it.azimuthDeg, 1f) }
    }

    /** With channel [index] of a [count]-channel stream moved to [placement]. */
    fun withChannel(count: Int, index: Int, placement: ChannelPlacement): SpatialPlacement {
        val list = placementFor(count).toMutableList()
        if (index !in list.indices) return this
        list[index] = placement.clamped()
        return copy(layouts = layouts + (count.toString() to list))
    }

    /** The [count]-channel layout back at its standard positions. */
    fun resetLayout(count: Int): SpatialPlacement = copy(layouts = layouts - count.toString())

    /** Whether the [count]-channel layout differs from the standard one. */
    fun isMoved(count: Int): Boolean = layouts.containsKey(count.toString())

    companion object {
        val DEFAULT = SpatialPlacement()
    }
}

/** Keeps a placement on the map: an angle in -180..180, a distance in range. */
fun ChannelPlacement.clamped(): ChannelPlacement {
    val az = if (azimuthDeg.isFinite()) {
        var a = azimuthDeg % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        a
    } else 0f
    val d = if (distance.isFinite()) distance.coerceIn(SpatialLayout.MIN_DISTANCE, SpatialLayout.MAX_DISTANCE) else 1f
    return ChannelPlacement(az, d)
}

/**
 * The speakers of each bed the map can show, in the order the decoder hands
 * the channels over, and what a distance does to a channel's level.
 */
object SpatialLayout {
    const val MIN_DISTANCE = 0.5f
    const val MAX_DISTANCE = 2f

    /**
     * Level for a distance: 1 on the ring, doubling as it halves. Pulled in to
     * half the radius it is 6 dB louder; pushed out to twice, 6 dB quieter.
     */
    fun gainFor(distance: Float): Float = 1f / distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)

    /**
     * The standard speakers of a [count]-channel bed. Names follow
     * [ChannelDetectorProcessor.channelNames] (the decoder's order), so the map
     * and the detector's live levels line up channel for channel; 5.1.4 and
     * 7.1.4, which the detector does not name, are in Android's order, as
     * [tf.monochrome.android.audio.dsp.ChannelLayout] reads them. Angles are
     * the reference positions: surrounds at 110 degrees in a 5.1, sides at 90
     * and rears at 150 once there are both, heights at 45 up.
     */
    fun speakers(count: Int): List<SpeakerChannel> {
        val names = when (count) {
            10 -> listOf("FL", "FR", "FC", "LFE", "BL", "BR", "TFL", "TFR", "TBL", "TBR")
            12 -> listOf("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR", "TFL", "TFR", "TBL", "TBR")
            else -> ChannelDetectorProcessor.channelNames(count)
        }
        // With sides present the backs are true rears; alone they are the
        // 5.1 surrounds.
        val backAngle = if ("SL" in names && "BL" in names) 150f else 110f
        return names.mapIndexed { i, n ->
            when (n) {
                "M" -> SpeakerChannel("M", 0f)
                "FL" -> SpeakerChannel("FL", -30f)
                "FR" -> SpeakerChannel("FR", 30f)
                "FC" -> SpeakerChannel("FC", 0f)
                "LFE" -> SpeakerChannel("LFE", 0f, isLfe = true)
                "FLC" -> SpeakerChannel("FLC", -60f)
                "FRC" -> SpeakerChannel("FRC", 60f)
                "SL" -> SpeakerChannel("SL", if ("BC" in names) -110f else -90f)
                "SR" -> SpeakerChannel("SR", if ("BC" in names) 110f else 90f)
                "BL" -> SpeakerChannel("BL", -backAngle)
                "BR" -> SpeakerChannel("BR", backAngle)
                "BC" -> SpeakerChannel("BC", 180f)
                "TFL" -> SpeakerChannel("TFL", -45f, 45f)
                "TFR" -> SpeakerChannel("TFR", 45f, 45f)
                "TSL" -> SpeakerChannel("TSL", -90f, 45f)
                "TSR" -> SpeakerChannel("TSR", 90f, 45f)
                "TBL" -> SpeakerChannel("TBL", -135f, 45f)
                "TBR" -> SpeakerChannel("TBR", 135f, 45f)
                // No layout for this count: spread evenly round the ring.
                else -> SpeakerChannel(n, -180f + 360f * (i + 0.5f) / names.size)
            }
        }
    }

    /** Index of the LFE in a [count]-channel bed, or -1. */
    fun lfeIndex(count: Int): Int = speakers(count).indexOfFirst { it.isLfe }

    /** The beds the map can edit with nothing playing, largest last. */
    val EDITABLE_COUNTS = listOf(6, 8, 10, 12, 16)

    /** A name for a [count]-channel bed. */
    fun layoutName(count: Int): String = when (count) {
        10 -> "5.1.4"
        12 -> "7.1.4"
        else -> ChannelDetectorProcessor.layoutName(count)
    }
}
