package tf.monochrome.android.player

import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.UnifiedTrack
import java.text.Normalizer
import kotlin.math.abs

/**
 * Pure matching helpers behind [LocalTrackLocator] — "is this on-device file the
 * same recording as the one the user just tapped?".
 *
 * Deliberately strict: an on-device file is only accepted when the normalised
 * base titles are equal, at least one artist credit is shared, and the runtimes
 * agree. Playing the wrong recording (a live take, a different edit) is a much
 * worse failure than falling back to the stream, so anything short of that is
 * rejected rather than guessed at.
 */
internal object LocalTrackMatching {

    /** Runtimes must agree this closely (seconds) before a file is accepted. */
    const val DURATION_TOLERANCE_SECONDS = 5

    // A trailing "(...)"/"[...]" group — "(Remastered 2011)", "[Explicit]",
    // "(feat. Someone)". Stripped from both sides so a catalogue title and a
    // plainly-tagged file still line up.
    private val TRAILING_QUALIFIER = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")

    // Credit separators. Intentionally excludes "/" and "x" — "AC/DC" and
    // "Malcolm X" are single artists, and splitting them invents credits.
    private val ARTIST_SEPARATOR =
        Regex("""\s*(?:,|;|&|\bfeat\b\.?|\bft\b\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE)

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Casefold for comparison: strip diacritics, drop apostrophes so
     * "don't" == "dont", turn remaining punctuation into spaces so
     * "rock&roll" == "rock & roll", then collapse whitespace.
     */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> Unit
                ch == '\'' || ch == '’' || ch == 'ʼ' -> Unit
                ch.isLetterOrDigit() -> sb.append(ch.lowercaseChar())
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    /**
     * The title without its version decoration: Qobuz's em-dash version suffix
     * and any trailing parenthetical/bracketed qualifiers. Applied to both
     * sides of a comparison, so it only ever removes decoration they don't
     * share. Never returns blank — a title that is nothing but a qualifier
     * (e.g. "(Intro)") keeps its original text.
     */
    fun baseTitle(raw: String): String {
        var title = raw.substringBefore(" — ").trim().ifBlank { raw.trim() }
        while (true) {
            val stripped = title.replace(TRAILING_QUALIFIER, "").trim()
            if (stripped.isEmpty() || stripped == title) break
            title = stripped
        }
        return title.ifBlank { raw.trim() }
    }

    /** Individual artist credits, normalised. "A, B & C" -> {a, b, c}. */
    fun artistParts(raw: String): Set<String> =
        raw.split(ARTIST_SEPARATOR)
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()

    /** Every credit a candidate is tagged with (track artist, album artist). */
    fun artistParts(candidate: UnifiedTrack): Set<String> =
        (listOf(candidate.artistName) + candidate.artistNames + listOfNotNull(candidate.albumArtistName))
            .flatMapTo(mutableSetOf()) { artistParts(it) }

    /**
     * SQL LIKE pattern shortlisting files whose title starts with [title]'s base
     * form. Returns null when there's nothing usable to search on.
     */
    fun titlePattern(title: String): String? {
        val base = baseTitle(title).trim()
        if (base.isBlank()) return null
        val escaped = base
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "$escaped%"
    }

    /**
     * Unknown (non-positive) runtimes on either side can't disprove a match, so
     * they pass — the title and artist checks still have to hold.
     */
    fun durationsAgree(a: Int, b: Int): Boolean =
        a <= 0 || b <= 0 || abs(a - b) <= DURATION_TOLERANCE_SECONDS

    /**
     * Best on-device copy of the described song, or null when none of
     * [candidates] is confidently the same recording.
     *
     * Ties break towards the matching album, then the closest runtime, then the
     * better-sounding file — so an album rip wins over a compilation copy and a
     * FLAC wins over an MP3 of the same track.
     */
    fun pickBest(
        candidates: List<UnifiedTrack>,
        title: String,
        artist: String,
        albumTitle: String?,
        durationSeconds: Int,
    ): UnifiedTrack? {
        val wantedTitle = normalize(baseTitle(title))
        if (wantedTitle.isBlank()) return null
        val wantedArtists = artistParts(artist)
        if (wantedArtists.isEmpty()) return null
        val wantedAlbum = albumTitle?.let { normalize(baseTitle(it)) }.orEmpty()

        return candidates
            .filter { candidate ->
                normalize(baseTitle(candidate.title)) == wantedTitle &&
                    artistParts(candidate).any { it in wantedArtists } &&
                    durationsAgree(durationSeconds, candidate.durationSeconds)
            }
            .minWithOrNull(
                compareBy<UnifiedTrack> { candidate ->
                    val album = normalize(baseTitle(candidate.albumTitle.orEmpty()))
                    if (wantedAlbum.isNotBlank() && album == wantedAlbum) 0 else 1
                }
                    .thenBy { candidate ->
                        if (durationSeconds <= 0 || candidate.durationSeconds <= 0) {
                            DURATION_TOLERANCE_SECONDS
                        } else {
                            abs(durationSeconds - candidate.durationSeconds)
                        }
                    }
                    .thenByDescending { qualityScore(it) }
            )
    }

    /** Rough "which file sounds better" ordering; lossless beats lossy. */
    fun qualityScore(track: UnifiedTrack): Int {
        val codecScore = when (track.codec) {
            AudioCodec.FLAC -> 100
            AudioCodec.ALAC -> 95
            AudioCodec.WAV, AudioCodec.AIFF -> 90
            AudioCodec.APE -> 85
            AudioCodec.OGG_VORBIS -> 60
            AudioCodec.OPUS -> 55
            AudioCodec.AAC -> 50
            AudioCodec.MP3 -> 40
            else -> 20
        }
        return codecScore + (track.bitDepth ?: 16) * 2 + (track.sampleRate ?: 44_100) / 1_000
    }
}
