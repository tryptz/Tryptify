package tf.monochrome.android.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The headphone an AutoEQ preset was made for, and the measured curves its
 * bands were fitted from.
 *
 * Kept inside the preset so that loading it puts its own headphone in the
 * picker and its own curve on the graph, with no network. Before this a
 * preset brought only its bands, and the picker kept whatever had been picked
 * last: a preset for one headphone showed another's name and curve.
 */
@Serializable
data class PresetHeadphone(
    /** [Headphone.id]; empty when the curve came from a file. */
    val id: String = "",
    /** [Headphone.name], what the picker shows; empty when the curve came from a file. */
    val name: String = "",
    /** The left ear, which is the only one on a mono preset. */
    val left: PresetEar = PresetEar(),
    /** The right ear, only on a preset saved in 2-channel mode. */
    val right: PresetEar? = null,
)

/** One ear of a [PresetHeadphone]. */
@Serializable
data class PresetEar(
    /** The ear's picker label as the EQ screen set it ("HD 600 · oratory1990"). */
    val label: String? = null,
    /**
     * A squig.link measurement and the numbered sweep the curve is ("L2"), so
     * the sample stepper still works after the preset is loaded. Null for
     * anything that has no samples to step through.
     */
    val measurement: AutoEqMeasurement? = null,
    val sample: String? = null,
    @Serializable(with = CompactCurveSerializer::class)
    val curve: List<FrequencyPoint> = emptyList(),
) {
    val isEmpty: Boolean get() = label == null && curve.isEmpty()
}

/**
 * A measured curve as text, `freq,gain;freq,gain;…`: about a third of the
 * size of [FrequencyPoint]'s JSON, which matters for a preset that syncs and
 * for thousands of measurements kept for offline use.
 *
 * Each number is written by `Float.toString`, which always reads back as the
 * same float, so a curve comes back bit for bit and a fit from the stored
 * copy is the fit from the download. Not locale-dependent.
 */
object CompactCurve {

    fun encode(points: List<FrequencyPoint>): String = buildString(points.size * 12) {
        points.forEachIndexed { i, p ->
            if (i > 0) append(';')
            append(p.freq.toString()).append(',').append(p.gain.toString())
        }
    }

    /** The points of [text]; a pair that does not read as two numbers is skipped. */
    fun decode(text: String): List<FrequencyPoint> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<FrequencyPoint>(text.length / 12 + 1)
        for (pair in text.split(';')) {
            val comma = pair.indexOf(',')
            if (comma <= 0) continue
            val freq = pair.substring(0, comma).trim().toFloatOrNull() ?: continue
            val gain = pair.substring(comma + 1).trim().toFloatOrNull() ?: continue
            out += FrequencyPoint(freq, gain)
        }
        return out
    }

    /**
     * The curve as a two-column CSV that `EqDataParser.parseRawData` reads
     * back to exactly [points], for callers that take a measurement as text.
     */
    fun toCsv(points: List<FrequencyPoint>): String = buildString(points.size * 14 + 16) {
        append("frequency,raw\n")
        for (p in points) {
            append(p.freq.toString()).append(',').append(p.gain.toString()).append('\n')
        }
    }
}

/** Serializes a curve as one [CompactCurve] string. */
object CompactCurveSerializer : KSerializer<List<FrequencyPoint>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("tf.monochrome.CompactCurve", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<FrequencyPoint>) =
        encoder.encodeString(CompactCurve.encode(value))

    override fun deserialize(decoder: Decoder): List<FrequencyPoint> =
        CompactCurve.decode(decoder.decodeString())
}
