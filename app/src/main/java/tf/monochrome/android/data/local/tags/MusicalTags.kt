package tf.monochrome.android.data.local.tags

import kotlin.math.roundToInt

/**
 * Tempo, key and loudness, as a file's tags give them: the fields
 * MediaMetadataRetriever never surfaces. Read from the tag block alone; the
 * audio is never decoded.
 *
 * Every value is checked before it is kept, so junk in a tag ("0", "N/A",
 * "Allegro", a gain of 900 dB) is null, the same as no tag.
 */
data class MusicalTags(
    /** Beats per minute, [MIN_BPM]..[MAX_BPM]. */
    val bpm: Float? = null,
    /** Camelot code, 1..24: see [Camelot]. */
    val camelotKey: Int? = null,
    /** ReplayGain track gain, dB, relative to a -18 LUFS reference. */
    val rgTrackGain: Float? = null,
    /** ReplayGain album gain, dB, relative to a -18 LUFS reference. */
    val rgAlbumGain: Float? = null,
    /** EBU R128 track gain, Q7.8 dB (1/256 dB steps), relative to -23 LUFS: Opus's own tag. */
    val r128TrackGain: Int? = null,
    /** EBU R128 album gain, Q7.8 dB, relative to -23 LUFS. */
    val r128AlbumGain: Int? = null,
) {
    companion object {
        const val MIN_BPM = 30f
        const val MAX_BPM = 300f

        /**
         * Reads the fields out of a tag's text fields, keyed by name in upper
         * case: "BPM", "INITIALKEY", "REPLAYGAIN_ALBUM_GAIN", "R128_TRACK_GAIN".
         * The first spelling that holds a valid value wins.
         */
        fun from(fields: Map<String, String>): MusicalTags = MusicalTags(
            bpm = BPM_NAMES.firstNotNullOfOrNull { parseBpm(fields[it]) },
            camelotKey = KEY_NAMES.firstNotNullOfOrNull { Camelot.parse(fields[it]) },
            rgTrackGain = parseGain(fields["REPLAYGAIN_TRACK_GAIN"]),
            rgAlbumGain = parseGain(fields["REPLAYGAIN_ALBUM_GAIN"]),
            r128TrackGain = parseQ78(fields["R128_TRACK_GAIN"]),
            r128AlbumGain = parseQ78(fields["R128_ALBUM_GAIN"]),
        )

        /**
         * "128", "127.53", "128,5", "128 BPM": the leading number, kept to two
         * decimals. Null outside [MIN_BPM]..[MAX_BPM], where a value is a
         * placeholder ("0") or a typo rather than a tempo.
         */
        fun parseBpm(raw: String?): Float? {
            val value = leadingNumber(raw) ?: return null
            if (value < MIN_BPM || value > MAX_BPM) return null
            return (value * 100f).roundToInt() / 100f
        }

        /** "-6.54 dB", "+2.10 dB", "−3,2": a ReplayGain gain in dB, within ±60. */
        fun parseGain(raw: String?): Float? {
            val value = leadingNumber(raw) ?: return null
            return value.takeIf { it in -MAX_GAIN_DB..MAX_GAIN_DB }
        }

        /** "-2304": an R128 gain in 1/256 dB steps, a signed 16-bit integer. */
        fun parseQ78(raw: String?): Int? {
            val text = raw?.trim()?.replace('−', '-') ?: return null
            return text.toIntOrNull()?.takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }
        }

        private fun leadingNumber(raw: String?): Float? {
            val text = raw?.trim()?.replace('−', '-')?.replace(',', '.') ?: return null
            return NUMBER.find(text)?.value?.toFloatOrNull()?.takeIf { it.isFinite() }
        }

        private const val MAX_GAIN_DB = 60f
        private val NUMBER = Regex("""^[+-]?\d+(\.\d+)?""")

        // ID3 writes TBPM, Vorbis and MP4 BPM; FBPM is the fractional one some
        // DJ software adds beside it.
        private val BPM_NAMES = listOf("BPM", "TBPM", "FBPM")

        // ID3 writes TKEY; Vorbis comments carry INITIALKEY (Mixed In Key,
        // Mixxx, Beatport) or KEY; MP4 the iTunes "initialkey" atom.
        private val KEY_NAMES = listOf("KEY", "INITIALKEY", "TKEY", "INITIAL KEY")
    }
}
