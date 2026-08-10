package tf.monochrome.android.data.presence

/**
 * Which animated spectrum belongs on the card for a given track.
 *
 * The badge is a scrolling spectrum curve with a real alpha channel, so it
 * floats on the card rather than sitting on a plate. It cannot be the
 * listener's actual audio — Discord renders this on somebody else's screen from
 * a payload sent once per track, and presence updates are rate limited orders
 * of magnitude below any frame rate worth the name. What it can match is the
 * two things that make a visualiser read as belonging to the music: the rhythm
 * it moves to, and what colour it is.
 *
 * Both come from data the app already has. The groove is chosen from the genre
 * graph; the colour from the cover palette the player extracts for its own
 * theming. Quantising them to eight grooves and eight hues is what keeps this a
 * fixed set of files served from the repository, rather than a video encoder
 * running on a phone once a minute and uploading the result somewhere.
 *
 * See `tools/build_presence_loops.py`, which draws them.
 */
object PresenceBadge {

    /**
     * Where the loops are served from.
     *
     * The repository itself: raw.githubusercontent returns real image content
     * types, which is all Discord's media proxy needs, and it costs nothing to
     * run. Pinned to a branch rather than a commit, so redrawing the loops does
     * not need an app release to point at the new ones.
     */
    const val BASE_URL =
        "https://raw.githubusercontent.com/tryptz/Tryptify/" +
            "claude/perf-mode-discovery-ux-7dn2l4/assets/presence"

    const val HUES = 8

    /**
     * The rhythms, and the genre keywords that pick them.
     *
     * A four-to-the-floor house record and a drum & bass break are not the same
     * animation at different speeds — they are different patterns, and a bass
     * lobe pumping on every beat under a jungle track is wrong in a way anyone
     * who listens to jungle would see instantly. So the groove is matched on
     * what the genre *is*, not on its tempo.
     *
     * Order matters: the first match wins, so the specific sits above the
     * general. "liquid drum and bass" has to reach `dnb` before "bass" can drag
     * it anywhere else, and "hardstyle" must not be caught by "hard rock".
     */
    private val GROOVES: List<Pair<String, List<String>>> = listOf(
        "dnb" to listOf(
            "drum-and-bass", "drum and bass", "dnb", "jungle", "breakbeat", "breakcore",
            "neurofunk", "liquid", "jump-up", "darkstep", "ragga", "footwork", "juke",
        ),
        "hard" to listOf(
            "hardstyle", "hardcore", "gabber", "uptempo", "frenchcore", "terrorcore",
            "rawstyle", "hardtekk", "hard-techno", "hard techno", "speedcore", "makina",
            "jumpstyle", "hard-dance", "hard dance", "hands-up", "happy-hardcore",
        ),
        "trap" to listOf("trap", "drill", "phonk", "hyperpop", "witch-house"),
        "boombap" to listOf(
            "hip-hop", "hip hop", "rap", "boom-bap", "boom bap", "g-funk", "grime",
            "conscious", "turntabl", "instrumental-hip",
        ),
        "dembow" to listOf(
            "reggaeton", "dancehall", "dembow", "afrobeat", "afroswing", "soca",
            "baile", "kuduro", "amapiano", "moombahton", "cumbia", "salsa", "bachata",
            "merengue", "reggae", "ska", "rocksteady", "zouk",
        ),
        "four" to listOf(
            "house", "techno", "trance", "disco", "edm", "garage", "eurodance",
            "electro", "acid", "minimal", "psytrance", "goa", "hi-nrg", "italo",
            "big-room", "future-bass", "dubstep", "bassline", "speed-garage",
        ),
        "halftime" to listOf(
            "ambient", "drone", "downtempo", "trip-hop", "trip hop", "dub", "lo-fi",
            "lofi", "chillout", "new-age", "classical", "baroque", "romantic", "opera",
            "choral", "gregorian", "field-recording", "noise", "shoegaze", "slowcore",
            "doom", "sludge", "funeral", "meditation", "soundtrack", "score",
        ),
    )

    /** Used when nothing matches: kick on one and three, snare on two and four. */
    const val DEFAULT_GROOVE = "backbeat"

    /**
     * The groove for a genre, matched on its id and any ancestor ids.
     *
     * Ancestors matter because the graph is deep: "neurofunk" is four levels
     * under "drum and bass", and matching only the leaf would send most of the
     * catalogue to the default. Walking up means a genre inherits its family's
     * rhythm unless it has one of its own.
     */
    fun groove(genreIds: List<String>): String {
        val haystack = genreIds.map { it.lowercase() }
        for ((groove, keywords) in GROOVES) {
            if (haystack.any { id -> keywords.any { id.contains(it) } }) return groove
        }
        return DEFAULT_GROOVE
    }

    /**
     * The hue bucket for a cover colour, or null when the cover has no colour
     * to speak of.
     *
     * A greyscale sleeve — and plenty are — has a hue, but it is whatever
     * rounding error survived the palette pass, so it would pick a bucket at
     * random and change on a re-extract. Below the saturation floor the answer
     * is "no opinion", which the caller turns into no badge rather than a
     * confident wrong colour.
     */
    fun hueBucket(red: Int, green: Int, blue: Int): Int? {
        val r = red / 255f
        val g = green / 255f
        val b = blue / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (max <= 0f || delta / max < SATURATION_FLOOR) return null

        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        // Rounded to the nearest bucket rather than floored, so a hue sitting a
        // degree below a boundary doesn't land a bucket away from where it
        // visibly belongs.
        return Math.round(hue / (360f / HUES)) % HUES
    }

    /**
     * The badge for a track, or null if there is nothing to base one on.
     *
     * Null rather than a default: a badge is a claim about the music, and one
     * with an arbitrary colour under a rhythm nobody chose is worse than no
     * badge at all. A greyscale cover is genuinely "no opinion", and the card
     * is complete without it.
     */
    fun url(genreIds: List<String>, coverRgb: Triple<Int, Int, Int>?): String? {
        val hue = coverRgb?.let { hueBucket(it.first, it.second, it.third) } ?: return null
        return "$BASE_URL/spectrum-${groove(genreIds)}-$hue.webp"
    }

    /** Below this, a colour is grey enough that its hue means nothing. */
    private const val SATURATION_FLOOR = 0.18f
}
