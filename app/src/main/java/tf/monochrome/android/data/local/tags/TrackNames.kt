package tf.monochrome.android.data.local.tags

import tf.monochrome.android.data.playlistfix.TrackMatcher
import java.text.Normalizer

/**
 * What a file's name says about its track, once the numbering is off it.
 *
 * Rips and downloads name files by their place on the release: "01 - Song",
 * "2-05 Song", "05. Song", "CD1 - 05 - Song". A file with no title tag took
 * that whole name as its title, so lyrics were searched for "2-05 Jolene";
 * and one with no artist tag either had it split at the first " - ", so the
 * track number ("01") or the disc ("CD1") became the artist. Every lyrics
 * source then searched for an artist that does not exist. Some rips write
 * that whole name into the title tag as well; [label] is such a title as the
 * galaxy labels it.
 */
object TrackNames {

    /**
     * [name] without a leading disc or track number: "01 - Song", "1-05 Song",
     * "05. Song", "CD1 - 05 - Song", "Disc 2 - 03 - Song" all give "Song".
     *
     * Only numbering that is plainly numbering comes off: zero-padded ("05 "),
     * disc-and-track ("2-05 "), or followed by a dot or a spaced dash ("12 - ").
     * A title that starts with a number keeps it — "99 Luftballons", "7 Rings",
     * "1999" — and a name that is nothing but a number stays as it is.
     */
    fun withoutNumberPrefix(name: String): String {
        val stripped = NUMBER_PREFIX.replaceFirst(name, "").trim()
        return stripped.ifEmpty { name.trim() }
    }

    /**
     * A track's [title] as a label in its artist's own system: without a
     * leading track number that its own number tag confirms ("03 Song" on
     * track 3), and without a leading "Artist - " that names one of its own
     * [artists]. Some rips write the whole file name into the title tag,
     * "03 Bioweapon, Audiofreq, Code Black - A.A.C.S.", and the galaxy showed
     * all of it beside a moon of an artist already named by its star.
     *
     * Each comes off once, and only when it is plainly what it is. The
     * number in a numbering form — "03 ", "3. ", "3 - ", "03 — ", "1-03 ",
     * "05-2Pac", "CD2 - 05 - ", full-width "０３　" — but not one that is the
     * start of the title's own run of numbers ("1-2-3 Redlight", "3.14159"),
     * read the way the track's own tags confirm: equal to [trackNumber] and,
     * where it names a disc, to [discNumber] ("5.15" is not disc 5 when the
     * disc is not known; "05-50 Cent" on track 5 is track 5 and 50 Cent). The artist split off as [splitArtistTitle]
     * splits it, when its credit names one of [artists] before any "feat.":
     * "Waves feat. Audiofreq - Club Mix" is a title. "7 Rings" on track 10
     * keeps its 7; a title with no number tag to check keeps any number it
     * starts with; and "03 - 3 - Song" on track 3 gives "3 - Song". A label
     * of a title that is not blank is never blank.
     */
    fun label(title: String, trackNumber: Int?, discNumber: Int?, artists: List<String?>): String {
        var label = withoutOwnNumber(title, trackNumber, discNumber)
        splitArtistTitle(label)?.let { (credit, rest) -> if (namesAny(credit, artists)) label = rest }
        return label.trim().ifEmpty { title.trim() }
    }

    /**
     * [title] without the first of its number's [READINGS] that the track's
     * own tags confirm. One reading taken and then checked gave up too soon:
     * "05-50 Cent" read as disc 5, track 50 kept its "05-" on track 5, and
     * "01-1 Giant Leap" on disc 1, track 1 lost the band's own 1.
     */
    private fun withoutOwnNumber(title: String, trackNumber: Int?, discNumber: Int?): String {
        if (trackNumber == null) return title
        for (reading in READINGS) {
            val match = reading.find(title) ?: continue
            val (disc, track) = match.destructured
            if (number(track) != trackNumber) continue
            // Its track confirmed and its disc not: it is another disc's
            // numbering, and none of it comes off. Read on, and "02-02 Song"
            // on disc 1 lost its "02-" as if that were the track.
            if (disc.isNotEmpty() && number(disc) != discNumber) return title
            return title.substring(match.range.last + 1)
        }
        return title
    }

    /** [digits], ASCII or full width, as a number. */
    private fun number(digits: String): Int? =
        digits.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("").toIntOrNull()

    /**
     * Whether [credit] ("A, B & C", "A / B", "A feat. B") names any of
     * [artists] before any "feat.": after it is a guest, and before it a
     * title as often as an artist.
     */
    private fun namesAny(credit: String, artists: List<String?>): Boolean {
        val named = names(FEATURING.split(credit, 2)[0])
        if (named.isEmpty()) return false
        return artists.any { a -> a != null && names(a).any { it in named } }
    }

    /**
     * The names in a credit, each as one [key], and the whole credit as one
     * more: "Simon & Garfunkel" is "Simon and Garfunkel". Split where
     * artists are joined, not at "and" or "with", which titles are full of:
     * "Live and Let Die" is not Live's.
     */
    private fun names(credit: String): Set<String> {
        val keys = HashSet<String>()
        key(credit)?.let(keys::add)
        for (name in credit.split(CREDIT_SEPARATORS)) key(name)?.let(keys::add)
        return keys
    }

    /**
     * A name as one key: folded, without a leading "the", without spaces, so
     * "The Beatles" is "Beatles" and "R.E.M." is "REM". A name with no letter
     * or digit is its own key as written: folded, "!!!" is nothing, and "+"
     * or "&" is "and", which would make a band called "+" every "And".
     */
    private fun key(name: String): String? {
        val written = name.trim().lowercase().replace(" ", "")
        if (written.isEmpty()) return null
        // Compatibility forms first: "𝐁𝐓𝐒" and "Ⅻ" have letters, "①" a digit.
        if (Normalizer.normalize(written, Normalizer.Form.NFKC).none { it.isLetterOrDigit() }) return written
        return TrackMatcher.fold(name).removePrefix("the ").replace(" ", "").ifEmpty { null }
    }

    /**
     * Whether [artist] is numbering an older scan took for an artist: "01",
     * "1-05", "CD1", "Disc 2". Such a row is re-read so the fix reaches it.
     */
    fun looksLikeNumbering(artist: String?): Boolean =
        artist != null && NUMBERING_ONLY.matches(artist.trim())

    // A digit, ASCII or full width ("０３", as Japanese rips number tracks),
    // and a space, the ideographic one too, for numbering and credits alike.
    // Spelled out rather than \d and \s, which leave both out on the JVM.
    private const val DIGIT = """[0-9０-９]"""
    private const val SPACE = """[ \t\u3000]"""

    // A dash after a number: a hyphen, the other hyphens and dashes, a tilde
    // or an underscore. Only a hyphen or an en dash used to count, and
    // "03 — Song" lost its number and kept the dash.
    private const val DASH = """[-‐‑‒–—―−~_]"""

    // The same without the tilde, after a number that is not zero-padded:
    // "311 ~ Amber" is the band 311, named as scene rips name files.
    private const val SPACED_DASH = """[-‐‑‒–—―−_]"""

    // A separator after a number: a dash or a dot, or plain space.
    private const val SEP = """(?:$SPACE*(?:$DASH|\.)$SPACE*|$SPACE+)"""

    // [SEP] after a disc and an unpadded track, which may instead be the
    // start of the title's own run of numbers: a dash or a dot straight
    // between digits does not end it, and "1-2-3 Redlight" on track 2 lost
    // "1-2-". After a space it has ended: "1-5 -Song".
    private const val RUN_END =
        """(?:$SPACE+(?:$DASH|\.)$SPACE*|(?:$DASH|\.)(?:$SPACE+|(?!$DIGIT))|$SPACE+)"""

    // A disc, its number caught: "CD1 - ", "Disc 2 ".
    private const val DISC = """(?:cd|disc|disk)$SPACE*($DIGIT{1,2})$SEP"""

    // The track number's forms, each catching its disc (if it has one) and
    // its track. A zero-padded track is numbering whatever follows it
    // ("05-2Pac", "03_1999"); an unpadded one straight before more digits
    // may be the title's own, and "3.14159" keeps its "3.".
    private const val DISC_PADDED = """($DIGIT{1,2})[-.]([0０]$DIGIT{1,2})$SEP"""     // "1-05 ", "1-05-2Pac"
    private const val DISC_UNPADDED = """($DIGIT{1,2})[-.]($DIGIT{1,3})$RUN_END"""   // "1-5 "
    private const val PADDED = """([0０]$DIGIT{1,2})$SEP"""                           // "05 ", "05-2Pac"
    private const val UNPADDED = """($DIGIT{1,3})(?:$SPACE*$SPACED_DASH$SPACE+|\.$SPACE+|\.(?!$DIGIT))""" // "5. ", "12 - "

    // A file name's numbering, with no tags to check it by: the forms in this
    // order, and after a "CD1 - " only a track, the disc being named.
    private val NUMBER_PREFIX = Regex(
        """^$SPACE*(?:$DISC(?:$PADDED|$UNPADDED)?|$DISC_PADDED|$DISC_UNPADDED|$PADDED|$UNPADDED)""",
        RegexOption.IGNORE_CASE,
    )

    // A title's numbering, each reading catching its disc (1, empty for none)
    // and its track (2), tried in this order against the track's own tags.
    // A zero-padded number alone comes before a disc and an unpadded track:
    // "01-1 Giant Leap" is track 1 and the band 1 Giant Leap.
    private val READINGS = listOf(
        Regex("""^$SPACE*$DISC_PADDED""", RegexOption.IGNORE_CASE),
        Regex("""^$SPACE*(?:$DISC)?$PADDED""", RegexOption.IGNORE_CASE),
        Regex("""^$SPACE*$DISC_UNPADDED""", RegexOption.IGNORE_CASE),
        Regex("""^$SPACE*(?:$DISC)?$UNPADDED""", RegexOption.IGNORE_CASE),
    )

    // Where a credit's artists are joined: "A, B", "A & B", "A x B", "A / B"
    // (spaced: "AC/DC" is one), "A feat. B". Not "and" or "with", and not a
    // capital X, which ends names: "Lil Nas X & Jack Harlow". No spaces
    // around it either: a key drops them, and a run of them before a
    // separator was tried once for each, which took seconds on a long one.
    private val CREDIT_SEPARATORS = Regex(
        """,|;|&|\+|$SPACE/$SPACE|$SPACE(?:(?-i:x)|vs\.?|feat\.?|ft\.?|featuring)$SPACE""",
        RegexOption.IGNORE_CASE,
    )

    private val FEATURING = Regex("""$SPACE(?:feat\.?|ft\.?|featuring)$SPACE""", RegexOption.IGNORE_CASE)

    private val NUMBERING_ONLY = Regex(
        """(?:(?:cd|disc|disk)$SPACE*$DIGIT{1,2}|$DIGIT{1,3}(?:[-.]$DIGIT{1,3})?)""",
        RegexOption.IGNORE_CASE,
    )
}
