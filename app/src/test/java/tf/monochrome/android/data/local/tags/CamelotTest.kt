package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every spelling of a key lands on the same place on the Camelot wheel. The
 * tables are the published Camelot and Open Key charts, written out by hand,
 * not derived from the parser's arithmetic.
 */
class CamelotTest {

    // Camelot number -> the keys it names, minor (A) and major (B).
    private val minor = mapOf(
        1 to listOf("G#m", "Abm"), 2 to listOf("D#m", "Ebm"), 3 to listOf("A#m", "Bbm"),
        4 to listOf("Fm"), 5 to listOf("Cm"), 6 to listOf("Gm"), 7 to listOf("Dm"),
        8 to listOf("Am"), 9 to listOf("Em"), 10 to listOf("Bm"),
        11 to listOf("F#m", "Gbm"), 12 to listOf("C#m", "Dbm"),
    )
    private val major = mapOf(
        1 to listOf("B", "Cb"), 2 to listOf("F#", "Gb"), 3 to listOf("Db", "C#"), 4 to listOf("Ab", "G#"),
        5 to listOf("Eb", "D#"), 6 to listOf("Bb", "A#"), 7 to listOf("F"), 8 to listOf("C"),
        9 to listOf("G"), 10 to listOf("D"), 11 to listOf("A"), 12 to listOf("E"),
    )

    // Open Key: 1d is C major, 1m A minor.
    private val openKey = mapOf(
        "1m" to "Am", "2m" to "Em", "3m" to "Bm", "4m" to "F#m", "5m" to "Dbm", "6m" to "Abm",
        "7m" to "Ebm", "8m" to "Bbm", "9m" to "Fm", "10m" to "Cm", "11m" to "Gm", "12m" to "Dm",
        "1d" to "C", "2d" to "G", "3d" to "D", "4d" to "A", "5d" to "E", "6d" to "B",
        "7d" to "F#", "8d" to "Db", "9d" to "Ab", "10d" to "Eb", "11d" to "Bb", "12d" to "F",
    )

    @Test
    fun `note names land on their Camelot number`() {
        for ((n, names) in minor) for (name in names) assertEquals(name, n, Camelot.parse(name))
        for ((n, names) in major) for (name in names) assertEquals(name, 12 + n, Camelot.parse(name))
    }

    @Test
    fun `Camelot codes read as themselves`() {
        for (n in 1..12) {
            assertEquals(n, Camelot.parse("${n}A"))
            assertEquals(12 + n, Camelot.parse("${n}B"))
            assertEquals(n, Camelot.parse("${n}a"))
        }
        assertEquals(8, Camelot.parse("08A"))
        assertEquals(20, Camelot.parse(" 8 B "))
    }

    @Test
    fun `Open Key lands where its note name does`() {
        for ((open, note) in openKey) assertEquals(open, Camelot.parse(note), Camelot.parse(open))
    }

    @Test
    fun `relative keys share a number`() {
        assertEquals(8, Camelot.parse("Am"))
        assertEquals(20, Camelot.parse("C"))
        assertEquals(Camelot.number(Camelot.parse("Am")!!), Camelot.number(Camelot.parse("C")!!))
    }

    @Test
    fun `the ways tags spell minor and major`() {
        for (s in listOf("A minor", "A min", "Amin", "A-minor", "A Minor", "a minor", "A moll")) {
            assertEquals(s, 8, Camelot.parse(s))
        }
        for (s in listOf("C major", "C maj", "Cmaj", "CM", "C Major", "C dur")) assertEquals(s, 20, Camelot.parse(s))
        assertEquals(11, Camelot.parse("F♯m"))
        assertEquals(18, Camelot.parse("B♭"))
    }

    @Test
    fun `two spellings in one tag take the first that parses`() {
        assertEquals(8, Camelot.parse("8A/Am"))
        assertEquals(8, Camelot.parse("Am - 8A"))
        assertEquals(1, Camelot.parse("Abm/G#m"))
        assertEquals(20, Camelot.parse("C (8B)"))
    }

    @Test
    fun `what names no key is null`() {
        for (s in listOf(null, "", "  ", "X", "H", "13A", "0A", "8C", "Off", "Chromatic", "A lydian", "123", "Amaj7")) {
            assertNull("$s", Camelot.parse(s))
        }
    }

    @Test
    fun `a placeholder split apart is not a key`() {
        for (s in listOf("N/A", "n/a", "Key N/A", "a, b", "Not set (a)", "?/E", "-/-")) assertNull(s, Camelot.parse(s))
        // A bare letter on its own is still a key.
        assertEquals(23, Camelot.parse("A"))
        assertEquals(23, Camelot.parse(" A "))
    }

    @Test
    fun `number, mode and label read back`() {
        assertEquals(8, Camelot.number(8))
        assertEquals(8, Camelot.number(20))
        assertTrue(Camelot.isMinor(8))
        assertFalse(Camelot.isMinor(20))
        assertEquals("8A", Camelot.label(8))
        assertEquals("12B", Camelot.label(24))
        for (code in 1..24) assertEquals(code, Camelot.parse(Camelot.label(code)))
    }
}
