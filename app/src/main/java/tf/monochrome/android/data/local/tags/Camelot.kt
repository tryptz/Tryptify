package tf.monochrome.android.data.local.tags

/**
 * A musical key as a place on the Camelot wheel, from however a tag spells it.
 *
 * DJ software writes keys three ways: Camelot ("8A"), Open Key ("1m", "1d"),
 * and note names ("Am", "F#m", "Bb", "A minor"). They all name the same 24
 * keys, so they are stored as one number, the code:
 *
 * - 1..12 is 1A..12A, the minor keys,
 * - 13..24 is 1B..12B, the major keys.
 *
 * Neighbours on the wheel are a fifth apart, and A and B of the same number are
 * relative keys: 8A is A minor, 8B is C major.
 */
object Camelot {

    /** The Camelot code of [raw], or null when it names no key. */
    fun parse(raw: String?): Int? {
        val text = raw?.let(::normalise)?.takeIf { it.isNotEmpty() } ?: return null
        parseOne(text)?.let { return it }
        // A tag can carry two spellings at once ("8A/Am", "Am - 8A", "Abm/G#m"):
        // the first that parses wins. A piece that is one bare letter does not
        // count: split out of "N/A" or "a, b", it is a fragment, not A major.
        return text.split('/', ',', ';', '|', '(', ')', '[', ']')
            .flatMap { it.split(" - ") }
            .map { it.trim() }
            .filter { it.length > 1 }
            .firstNotNullOfOrNull { parseOne(it) }
    }

    /** The wheel number of [code], 1..12. */
    fun number(code: Int): Int = (code - 1) % 12 + 1

    /** Whether [code] is a minor key (an A on the wheel). */
    fun isMinor(code: Int): Boolean = code in 1..12

    /** [code] as the wheel writes it: "8A", "12B". */
    fun label(code: Int): String = "${number(code)}${if (isMinor(code)) 'A' else 'B'}"

    private fun parseOne(text: String): Int? {
        if (text.isEmpty()) return null
        WHEEL.matchEntire(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            if (n !in 1..12) return null
            return when (m.groupValues[2].lowercase()) {
                "a" -> n
                "b" -> 12 + n
                // Open Key counts from C major = 1d, five steps round from Camelot's 8B.
                "m" -> (n + 6) % 12 + 1
                "d" -> 12 + (n + 6) % 12 + 1
                else -> null
            }
        }
        val m = NOTE.matchEntire(text) ?: return null
        val letter = m.groupValues[1].uppercase()[0]
        val accidental = when (m.groupValues[2]) {
            "#" -> 1
            "b" -> -1
            else -> 0
        }
        val minor = minorFromSuffix(m.groupValues[3]) ?: return null
        val pitch = Math.floorMod(PITCH.getValue(letter) + accidental, 12)
        return if (minor) wheelOfMajor(pitch + 3) else 12 + wheelOfMajor(pitch)
    }

    /**
     * The wheel number of the major key on [pitch] (0 = C): each fifth up is
     * one step round, and C major sits at 8. A minor key is numbered as its
     * relative major, three semitones up.
     */
    private fun wheelOfMajor(pitch: Int): Int = (Math.floorMod(pitch * 7, 12) + 7) % 12 + 1

    /**
     * True for a minor suffix, false for a major one, null for anything else.
     * A bare lower-case "m" is minor ("Am"); an upper-case "M" alone is major.
     */
    private fun minorFromSuffix(raw: String): Boolean? {
        val s = raw.trim().trimStart('-', '.', '_').trim()
        if (s.isEmpty() || s == "M") return false
        if (s == "m") return true
        return when (s.lowercase()) {
            "min", "minor", "mi", "moll" -> true
            "maj", "major", "ma", "dur" -> false
            else -> null
        }
    }

    private fun normalise(raw: String): String = raw.trim()
        .replace('♯', '#')
        .replace('♭', 'b')
        .replace(' ', ' ')

    private val WHEEL = Regex("""0?(\d{1,2})\s*([AaBbMmDd])""")
    private val NOTE = Regex("""([A-Ga-g])\s*([#b]?)(.*)""")
    private val PITCH = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
}
