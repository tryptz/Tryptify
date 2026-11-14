package tf.monochrome.android.data.assistant

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.import_.ParsedEqProfile
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType

/**
 * What Claude is asked for when it lays out an equaliser, and how its answer
 * becomes filters: the words, the shape the answer must have, and the limits
 * the answer is held to before any of it is shown. Nothing here talks to the
 * network (that is [ClaudeEqClient]), so all of it is tested.
 */
object ClaudeEq {

    /** The filters and preamp in the answer, with what Claude says about them. */
    data class Proposal(val profile: ParsedEqProfile, val summary: String)

    const val MAX_BANDS = 10
    const val MAX_GAIN_DB = 12f
    const val MIN_Q = 0.2f
    const val MAX_Q = 10f

    const val SYSTEM = """You set up a parametric equaliser for headphones in a music player, for the person using it.

They give you one or more of: a picture of a frequency response graph, the filters they have now, and what they want in their own words. Work out the filters that get them there.

Reading a picture: find the frequency axis (usually logarithmic, 20 Hz to 20 kHz) and the level axis in dB, and read their scales before reading any curve. If the picture shows a measured response and a target, the filters should bring the measurement to the target: where the measurement is above the target, cut; where it is below, boost. If it shows one curve only, treat it as the measured response and correct it towards a smooth, neutral headphone response, unless they ask for something else. If it shows an equaliser curve that someone wants to copy, reproduce that curve. If the picture is not a frequency response graph, say so in the summary and return no filters.

The filters: at most $MAX_BANDS, each a peaking filter, a low shelf or a high shelf, with a centre or corner frequency from 20 to 20000 Hz, a gain from -$MAX_GAIN_DB to +$MAX_GAIN_DB dB and a Q from $MIN_Q to $MAX_Q. Prefer a few broad filters to many narrow ones: correct the broad shape first, then the largest remaining peaks. Cut a narrow peak rather than fill a narrow dip, since narrow dips in a measurement are often the measurement and filling them rings. Do not boost above 10 kHz by more than a few dB: measurements are least reliable there. Set the preamp to the negative of the largest boost the filters add together at any frequency, so nothing clips, and to 0 when nothing is boosted.

When they give you their current filters and ask for a change ("less bass", "a bit brighter"), start from those filters and change only what the request needs.

The summary is for them: two or three plain sentences on what you saw and what the filters do, and anything you were unsure of when reading the picture. No headings, no lists."""

    /** The shape the answer must have, as a JSON schema. */
    const val SCHEMA = """{
  "type": "object",
  "properties": {
    "summary": {"type": "string"},
    "preamp": {"type": "number"},
    "bands": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "type": {"type": "string", "enum": ["PEAKING", "LOWSHELF", "HIGHSHELF"]},
          "freq": {"type": "number"},
          "gain": {"type": "number"},
          "q": {"type": "number"}
        },
        "required": ["type", "freq", "gain", "q"],
        "additionalProperties": false
      }
    }
  },
  "required": ["summary", "preamp", "bands"],
  "additionalProperties": false
}"""

    /**
     * What is said to Claude with the picture, if there is one: the filters
     * in use when the listener wants them as the starting point, and their
     * wish. With neither, the picture speaks for itself.
     */
    fun request(wish: String, current: List<EqBand>?, currentPreamp: Float): String = buildString {
        if (!current.isNullOrEmpty()) {
            append("My filters now (preamp ").append(number(currentPreamp)).append(" dB):\n")
            for (band in current) {
                if (!band.enabled) continue
                append("- ").append(band.type.name).append(" at ").append(number(band.freq)).append(" Hz, ")
                append(number(band.gain)).append(" dB, Q ").append(number(band.q)).append('\n')
            }
            append('\n')
        }
        append(wish.trim().ifEmpty { "Lay out the filters for this." })
    }

    @Serializable
    private data class Answer(val summary: String = "", val preamp: Float = 0f, val bands: List<AnswerBand> = emptyList())

    @Serializable
    private data class AnswerBand(val type: String = "PEAKING", val freq: Float = 0f, val gain: Float = 0f, val q: Float = 1f)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Claude's answer as filters, or null when it is not the JSON asked for.
     * Every value is brought inside the limits Claude was given, whatever it
     * sent: an answer is never trusted with the listener's ears. A filter
     * that would do nothing, or whose numbers are not numbers, is left out.
     */
    fun parse(text: String): Proposal? {
        val answer = runCatching { json.decodeFromString<Answer>(text.trim()) }.getOrNull() ?: return null
        val bands = answer.bands.asSequence()
            .filter { it.freq.isFinite() && it.gain.isFinite() && it.q.isFinite() }
            .map {
                EqBand(
                    id = 0,
                    type = runCatching { FilterType.valueOf(it.type.uppercase()) }.getOrDefault(FilterType.PEAKING),
                    freq = it.freq.coerceIn(20f, 20_000f),
                    gain = it.gain.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB),
                    q = it.q.coerceIn(MIN_Q, MAX_Q),
                )
            }
            .filter { kotlin.math.abs(it.gain) >= 0.05f }
            .take(MAX_BANDS)
            .mapIndexed { i, band -> band.copy(id = i) }
            .toList()
        val preamp = if (answer.preamp.isFinite()) answer.preamp.coerceIn(-MAX_GAIN_DB * 2, 0f) else 0f
        return Proposal(ParsedEqProfile(bands = bands, preamp = preamp), answer.summary.trim())
    }

    private fun number(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else String.format(java.util.Locale.US, "%.2f", value)
}
