package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.native.FiioCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The .txt files of squig.link, graph.hangout.audio and AutoEQ in, Contour's own text out. */
class TextFilesTest {
    private fun res(name: String): String =
        checkNotNull(javaClass.getResource("/txt/$name")) { "missing test file $name" }.readText()

    // squig.link's own import regex (graphtool.js), run over every line Contour writes.
    private val squigImport = Regex("""Filter\s*\d+:\s*(\S+)\s*(\S+)\s*Fc\s*(\S+)\s*Hz\s*Gain\s*(\S+)\s*dB(\s*Q\s*(\S+))?""")

    private fun band(i: Int, type: FilterType, f: Double, g: Double, q: Double, on: Boolean = true) = Band("b$i", type, f, g, q, on)

    @Test fun `a file of only 0 dB filters keeps one and moves it into range, never touching a filter with gain`() {
        val flat = ApoText.parseImport("Preamp: -3 dB\nFilter 1: ON PK Fc 0 Hz Gain 0 dB Q 0\nFilter 2: ON PK Fc 0 Hz Gain 0 dB Q 0\n")
        assertEquals(1, flat.bands.size)
        assertEquals(20.0, flat.bands[0].freqHz)
        assertEquals(0.1, flat.bands[0].q)
        assertEquals(0.0, flat.bands[0].gainDb)
        val single = ApoText.parseImport("Filter 1: ON PK Fc 30000 Hz Gain 0 dB Q 50\n")
        assertEquals(listOf(20000.0, 10.0), listOf(single.bands[0].freqHz, single.bands[0].q))
        val fine = ApoText.parseImport("Filter 1: ON PK Fc 1000 Hz Gain 0 dB Q 1.5\n")
        assertEquals(listOf(1000.0, 1.5), listOf(fine.bands[0].freqHz, fine.bands[0].q))
        // a filter with gain keeps its values, and the 0 dB zero filter is dropped as before
        val mixed = ApoText.parseImport("Filter 1: ON PK Fc 100 Hz Gain 2 dB Q 1\nFilter 2: ON PK Fc 0 Hz Gain 0 dB Q 0\n")
        assertEquals(1, mixed.bands.size)
        assertEquals(listOf(100.0, 2.0, 1.0), listOf(mixed.bands[0].freqHz, mixed.bands[0].gainDb, mixed.bands[0].q))
    }

    // ---- squig.link two-channel export -----------------------------------------------------------------------

    @Test fun `squig two-channel file imports the left block only and says so`() {
        val eq = ApoText.parseImport(res("squig-two-channel.txt"))
        assertEquals(5, eq.bands.size)
        assertEquals(-4.2, eq.preampDb)
        assertEquals(105.0, eq.bands[0].freqHz)
        assertEquals(FilterType.LOW_SHELF, eq.bands[1].type)
        assertFalse(eq.bands[3].enabled, "OFF stays a disabled band")
        assertEquals(listOf("left channel imported (file has separate L/R)"), eq.notes)
        // ids are unique even though the lines came from one block
        assertEquals(5, eq.bands.map { it.id }.toSet().size)
    }

    @Test fun `squig two-channel file with identical blocks has no note and no doubled filters`() {
        val eq = ApoText.parseImport(res("squig-two-channel-identical.txt"))
        assertEquals(2, eq.bands.size)
        assertEquals(-3.0, eq.preampDb)
        assertTrue(eq.notes.isEmpty())
    }

    @Test fun `a block for all channels wins over L and R`() {
        val text = "Channel: L\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\nChannel: R\nFilter 1: ON PK Fc 200 Hz Gain 2 dB Q 1\n" +
            "Channel: all\nPreamp: -2 dB\nFilter 1: ON PK Fc 300 Hz Gain 3 dB Q 1\n"
        val eq = ApoText.parseImport(text)
        assertEquals(listOf(300.0), eq.bands.map { it.freqHz })
        assertEquals(-2.0, eq.preampDb)
        assertEquals(1, eq.notes.size)
        val lr = ApoText.parseImport("Channel: L R\nPreamp: -1 dB\nFilter 1: ON PK Fc 300 Hz Gain 3 dB Q 1\nChannel: L\nFilter 1: ON PK Fc 90 Hz Gain 1 dB Q 1\n")
        assertEquals(listOf(300.0), lr.bands.map { it.freqHz })
    }

    @Test fun `a preamp before the first Channel header is used when the block has none`() {
        val eq = ApoText.parseImport("Preamp: -7 dB\nChannel: L\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\nChannel: R\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\n")
        assertEquals(-7.0, eq.preampDb)
        assertEquals(1, eq.bands.size)
        assertTrue(eq.notes.isEmpty(), "identical L and R: no note")
    }

    @Test fun `numbered channels map 1 to left 2 to right and 1 2 to both`() {
        val lr = ApoText.parseImport("Channel: 1\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\nChannel: 2\nFilter 1: ON PK Fc 200 Hz Gain 2 dB Q 1\n")
        assertEquals(listOf(100.0), lr.bands.map { it.freqHz })
        assertEquals(listOf("left channel imported (file has separate L/R)"), lr.notes)
        val both = ApoText.parseImport("Channel: 1 2\nPreamp: -2 dB\nFilter 1: ON PK Fc 300 Hz Gain 3 dB Q 1\nChannel: 1\nFilter 1: ON PK Fc 90 Hz Gain 1 dB Q 1\n")
        assertEquals(listOf(300.0), both.bands.map { it.freqHz })
        assertEquals(-2.0, both.preampDb)
        val right = ApoText.parseImport("Channel: 2\nFilter 1: ON PK Fc 500 Hz Gain -2 dB Q 1\n")
        assertEquals(listOf(500.0), right.bands.map { it.freqHz })
    }

    @Test fun `other channel blocks are ignored when an L R or both block exists, else imported as both with a note`() {
        val withLeft = ApoText.parseImport("Channel: C\nFilter 1: ON PK Fc 700 Hz Gain 5 dB Q 1\nChannel: L\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\n")
        assertEquals(listOf(100.0), withLeft.bands.map { it.freqHz })
        assertTrue(withLeft.notes.isEmpty())
        val withGlobal = ApoText.parseImport("Filter 1: ON PK Fc 120 Hz Gain 1 dB Q 1\nChannel: LFE\nFilter 1: ON PK Fc 40 Hz Gain 4 dB Q 1\n")
        assertEquals(listOf(120.0), withGlobal.bands.map { it.freqHz })
        val onlyOther = ApoText.parseImport("Preamp: -1 dB\nChannel: C\nFilter 1: ON PK Fc 700 Hz Gain 5 dB Q 1\nChannel: SL SR\nFilter 1: ON PK Fc 900 Hz Gain -3 dB Q 2\n")
        assertEquals(listOf(700.0, 900.0), onlyOther.bands.map { it.freqHz })
        assertEquals(-1.0, onlyOther.preampDb)
        assertEquals(1, onlyOther.notes.size)
        assertTrue(onlyOther.notes[0].contains("C") && onlyOther.notes[0].contains("no L or R"), onlyOther.notes[0])
        assertEquals(2, onlyOther.bands.map { it.id }.toSet().size)
    }

    @Test fun `a file with only a right block imports it`() {
        val eq = ApoText.parseImport("Channel: R\nPreamp: -3 dB\nFilter 1: ON PK Fc 500 Hz Gain -2 dB Q 1\n")
        assertEquals(listOf(500.0), eq.bands.map { it.freqHz })
        assertEquals(-3.0, eq.preampDb)
    }

    @Test fun `a file without Channel lines is read as before`() {
        val text = res("autoeq-parametric.txt")
        val eq = ApoText.parse(text)
        assertEquals(5, eq.bands.size)
        assertEquals(-6.3, eq.preampDb)
        assertTrue(eq.notes.isEmpty())
        assertEquals(eq.copy(notes = emptyList()), ApoText.parseImport(text))
    }

    // ---- graph.hangout.audio: ten slots, unused ones at 0 dB -----------------------------------------------------

    @Test fun `hangout ten-filter file keeps its zero-gain slots, they have a frequency and a Q of their own`() {
        val eq = ApoText.parseImport(res("hangout-10-filters.txt"))
        assertEquals(listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0), eq.bands.map { it.freqHz })
        assertEquals(listOf(0.0, 1.8, 0.0, -1.4, 0.0, 0.5, 2.3, -3.2, 0.0, 0.0), eq.bands.map { it.gainDb })
        assertTrue(eq.notes.isEmpty())
        assertEquals(-2.6, eq.preampDb)
        assertEquals(10, eq.bands.map { it.id }.toSet().size)
        assertEquals(eq, ApoText.parse(res("hangout-10-filters.txt"))) // nothing was left out or changed
    }

    @Test fun `a zero-gain filter with no frequency and no Q is padding and is dropped and counted, one with its own frequency stays`() {
        val eq = ApoText.parseImport(
            "Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1\nFilter 2: ON PK Fc 0 Hz Gain 0 dB Q 0\nFilter 3: ON PK Fc 200 Hz Gain -3 dB Q 1\nFilter 4: ON PK Fc 0 Hz Gain 0 dB Q 1\n",
        )
        assertEquals(listOf(100.0, 200.0), eq.bands.map { it.freqHz })
        assertEquals(listOf("2 empty filters skipped"), eq.notes)
        assertEquals(2, eq.bands.map { it.id }.toSet().size)
    }

    @Test fun `a disabled filter with gain and a zero-gain one both stay`() {
        val eq = ApoText.parseImport(
            "Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1\nFilter 2: OFF PK Fc 200 Hz Gain -3 dB Q 1\nFilter 3: ON LSC Fc 90 Hz Gain 2 dB Q 0.7\n",
        )
        assertEquals(listOf(100.0, 200.0, 90.0), eq.bands.map { it.freqHz })
        assertTrue(eq.bands[0].enabled && !eq.bands[1].enabled)
        assertTrue(eq.notes.isEmpty())
    }

    @Test fun `zero-gain shelves stay like zero-gain peaks, and pass filters stay`() {
        val eq = ApoText.parseImport(
            "Filter 1: ON LSC Fc 90 Hz Gain 0 dB Q 0.7\nFilter 2: ON HSC Fc 9000 Hz Gain 0.0 dB Q 0.7\n" +
                "Filter 3: ON HP Fc 20 Hz Q 0.7\nFilter 4: ON PK Fc 1000 Hz Gain 1.5 dB Q 1\n",
        )
        assertEquals(listOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF, FilterType.HIGH_PASS, FilterType.PEAK), eq.bands.map { it.type })
        assertTrue(eq.notes.isEmpty())
    }

    @Test fun `a file of only empty filters keeps them, a flat export imports as flat, and padding alone keeps the first`() {
        val flat = ApoText.format(listOf(band(1, FilterType.PEAK, 1000.0, 0.0, 0.71)), 0.0)
        val eq = ApoText.parseImport(flat)
        assertEquals(1, eq.bands.size)
        assertEquals(0.0, eq.bands[0].gainDb)
        val two = ApoText.parseImport("Filter 1: ON PK Fc 100 Hz Gain 0 dB Q 1\nFilter 2: ON PK Fc 200 Hz Gain 0 dB Q 1\n")
        assertEquals(listOf(100.0, 200.0), two.bands.map { it.freqHz })
        assertTrue(two.notes.isEmpty())
    }

    @Test fun `a DAC with fewer bands than the file has filters gets the zero-gain ones left out, last first and only as many as it takes`() {
        val eq = ApoText.parseImport(res("hangout-10-filters.txt"))
        val (fits, n) = ApoText.dropEmptyToFit(eq.bands, 8)
        assertEquals(2, n)
        assertEquals(listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0), fits.map { it.freqHz }) // 8000 and 16000 Hz (0 dB) went, the rest kept in order
        assertEquals(8, ApoText.dropEmptyToFit(eq.bands, 8).first.size)
        assertEquals(10, ApoText.dropEmptyToFit(eq.bands, 10).first.size) // room for all: nothing is left out
        assertEquals(0, ApoText.dropEmptyToFit(eq.bands, 10).second)
        // filters with gain are never left out: with 4 slots the 5 empty ones all go, the 5 with gain stay (over by one, which the caller reports)
        val four = ApoText.dropEmptyToFit(eq.bands, 4)
        assertEquals(listOf(62.0, 250.0, 1000.0, 2000.0, 4000.0), four.first.map { it.freqHz })
        assertEquals(5, four.second)
        // a pass filter has no gain but is not empty
        val pass = ApoText.parseImport("Filter 1: ON HP Fc 20 Hz Q 0.7\nFilter 2: ON PK Fc 100 Hz Gain 0 dB Q 1\n")
        assertEquals(listOf(FilterType.HIGH_PASS), ApoText.dropEmptyToFit(pass.bands, 1).first.map { it.type })
    }

    @Test fun `a typed-in Q keeps its precision through an import`() {
        val eq = ApoText.parseImport("Filter 1: ON PK Fc 1000 Hz Gain 3 dB Q 0.707\n")
        assertEquals(0.707, eq.bands.single().q)
    }

    @Test fun `text with no filters is empty`() {
        assertTrue(ApoText.parseImport("hello\nworld").bands.isEmpty())
        assertTrue(ApoText.parseImport("").bands.isEmpty())
    }

    // ---- AutoEQ ParametricEQ.txt, decimal commas, BOM-free CRLF ---------------------------------------------------

    @Test fun `AutoEQ ParametricEQ txt reads exactly`() {
        val eq = ApoText.parseImport(res("autoeq-parametric.txt"))
        assertEquals(
            listOf(
                Triple(FilterType.LOW_SHELF, 105.0, 6.1), Triple(FilterType.PEAK, 21.0, 5.5), Triple(FilterType.PEAK, 2400.0, -4.4),
                Triple(FilterType.PEAK, 5600.0, 2.7), Triple(FilterType.HIGH_SHELF, 10000.0, -2.0),
            ),
            eq.bands.map { Triple(it.type, it.freqHz, it.gainDb) },
        )
        assertEquals(0.5, eq.bands[1].q)
        assertTrue(eq.notes.isEmpty())
    }

    @Test fun `decimal commas and CRLF still parse`() {
        val eq = ApoText.parseImport("Preamp: -3,5 dB\r\nFilter 1: ON PK Fc 1000 Hz Gain 2,5 dB Q 1,2\r\n")
        assertEquals(-3.5, eq.preampDb)
        assertEquals(2.5, eq.bands.single().gainDb)
        assertEquals(1.2, eq.bands.single().q)
    }

    // ---- numbers as people type and copy them (audit 10.11.2026, L15) ------------------------------------------------

    @Test fun `a number without its leading zero reads as written, never as 0 dB`() {
        val eq = ApoText.parseImport("Preamp: -.5 dB\nFilter 1: ON PK Fc 1000 Hz Gain -.5 dB Q .7\nFilter 2: ON PK Fc 2000 Hz Gain .5 dB Q 1\nFilter 3: ON PK Fc 3000 Hz Gain +.25 dB Q 1,5\n")
        assertEquals(-0.5, eq.preampDb)
        assertEquals(listOf(-0.5, 0.5, 0.25), eq.bands.map { it.gainDb })
        assertEquals(listOf(0.7, 1.0, 1.5), eq.bands.map { it.q })
        assertTrue(eq.notes.isEmpty(), eq.notes.toString())
        assertEquals(-0.5, ApoText.parseImport("Filter 1: ON PK Fc 1000 Hz Gain -,5 dB Q 1\n").bands.single().gainDb)
    }

    @Test fun `a typographic minus or a dash is a minus`() {
        for (minus in listOf('−', '–', '‒', '‐', '‑', '—', '﹣', '－')) {
            val eq = ApoText.parseImport("Preamp: ${minus}6.1 dB\nFilter 1: ON PK Fc 105 Hz Gain ${minus}2.5 dB Q 0.70\nFilter 2: ON LSC Fc 105 Hz Gain ${minus}.5 dB Q 0.71\n")
            assertEquals(-6.1, eq.preampDb, "U+%04X".format(minus.code))
            assertEquals(listOf(-2.5, -0.5), eq.bands.map { it.gainDb }, "U+%04X".format(minus.code))
            assertTrue(eq.notes.isEmpty(), eq.notes.toString())
        }
    }

    @Test fun `no-break narrow and thin spaces are spaces, so the line is read`() {
        for (space in listOf(' ', ' ', ' ', ' ', '　')) {
            val s = space.toString()
            val text = "Preamp:${s}-3${s}dB\nFilter${s}1:${s}ON${s}PK${s}Fc${s}100${s}Hz${s}Gain${s}-2${s}dB${s}Q${s}1.41\nFilter 2: ON HSC Fc 8000 Hz Gain${s}4.5 dB Q 0.7\n"
            val eq = ApoText.parseImport(text)
            assertEquals(-3.0, eq.preampDb, "U+%04X".format(space.code))
            assertEquals(listOf(Triple(FilterType.PEAK, 100.0, -2.0), Triple(FilterType.HIGH_SHELF, 8000.0, 4.5)), eq.bands.map { Triple(it.type, it.freqHz, it.gainDb) }, "U+%04X".format(space.code))
            assertEquals(1.41, eq.bands[0].q)
            assertTrue(eq.notes.isEmpty(), eq.notes.toString())
        }
    }

    @Test fun `a value that cannot be read leaves its filter out and the import says which, never a silent 0 dB`() {
        val eq = ApoText.parseImport(
            "Preamp: -3 dB\n" +
                "Filter 1: ON PK Fc 100 Hz Gain -2 dB Q 1\n" +
                "Filter 2: ON PK Fc 200 Hz Gain abc dB Q 1\n" + // gain not a number
                "Filter 3: ON PK Fc 300 Hz Q 1\n" + // a peak with no gain
                "Filter 4: ON PK Fc 400 Hz Gain 3 dB Q 1\n" +
                "Filter 5: ON NO Fc 500 Hz Q 2\n" + // a type Contour does not know (notch)
                "Filter 6: ON PK Fc x Hz Gain 1 dB Q 1\n" + // no frequency
                "Filter 7: ON PK Fc 700 Hz Gain 1 dB BW Oct 1.0\n" + // a bandwidth, not a Q
                "Filter 8: ON PK Fc 800 Hz Gain 1 dB Q -\n" + // a Q that is not a number
                "Filter 9: garbage\n" +
                "Filter 10: ON None\n", // REW's empty slot: nothing lost, nothing said
        )
        assertEquals(listOf(100.0 to -2.0, 400.0 to 3.0), eq.bands.map { it.freqHz to it.gainDb })
        assertEquals(listOf("filters 2, 3, 5, 6, 7, 8, 9 unreadable, skipped"), eq.notes)
        assertEquals(-3.0, eq.preampDb)
        // one filter: the singular; a filter line without a number is named by its place
        assertEquals(listOf("filter 2 unreadable, skipped"), ApoText.parseImport("Filter: ON PK Fc 100 Hz Gain 1 dB Q 1\nFilter: ON PK Fc 200 Hz Gain ? dB Q 1\n").notes)
        // a pass filter needs no gain, and a shelf with no Q keeps APO's default: both are read, as before
        val pass = ApoText.parseImport("Filter 1: ON HPQ Fc 30 Hz Q 0.7\nFilter 2: ON LSC Fc 105 Hz Gain 5 dB\n")
        assertEquals(listOf(FilterType.HIGH_PASS, FilterType.LOW_SHELF), pass.bands.map { it.type })
        assertEquals(ApoText.DEFAULT_Q, pass.bands[1].q)
        assertTrue(pass.notes.isEmpty(), pass.notes.toString())
    }

    @Test fun `a preamp that cannot be read is said, and the file's filters still come in`() {
        val eq = ApoText.parseImport("Preamp: about -6 dB\nFilter 1: ON PK Fc 100 Hz Gain -2 dB Q 1\n")
        assertNull(eq.preampDb)
        assertEquals(1, eq.bands.size)
        assertEquals(listOf("preamp unreadable, skipped"), eq.notes)
        // a block's own preamp that is read wins, and nothing is said
        assertTrue(ApoText.parseImport("Preamp: about -6 dB\nPreamp: -6 dB\nFilter 1: ON PK Fc 100 Hz Gain -2 dB Q 1\n").notes.isEmpty())
        // an unreadable line after a readable one: the readable value stays and the skipped line is said
        val later = ApoText.parseImport("Preamp: -6 dB\nPreamp: about -3 dB\nFilter 1: ON PK Fc 100 Hz Gain -2 dB Q 1\n")
        assertEquals(-6.0, later.preampDb)
        assertEquals(listOf("preamp unreadable, skipped"), later.notes)
        // a global line overridden by the imported channel's own preamp is not named
        val channel = ApoText.parseImport("Preamp: about -6 dB\nChannel: L R\nPreamp: -3 dB\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\n")
        assertEquals(-3.0, channel.preampDb)
        assertTrue(channel.notes.none { "preamp" in it }, channel.notes.toString())
    }

    @Test fun `a number that runs on is unreadable, not cut to its first digits`() {
        val eq = ApoText.parseImport(
            "Filter 1: ON PK Fc 100 Hz Gain 2e-1 dB Q 1\n" + // exponent: was read as 2 dB
                "Filter 2: ON PK Fc 200 Hz Gain -2..5 dB Q 1\n" + // was -2 dB
                "Filter 3: ON PK Fc 1,200.5 Hz Gain 1 dB Q 1\n" + // was 1.2 Hz
                "Filter 4: ON PK Fc 400 Hz Gain 1 dB Q 1.2.3\n" + // was Q 1.2
                "Filter 5: ON PK Fc 500Hz Gain 3dB Q 0.7,\n" + // units and a trailing comma still read
                "Filter 6: ON PK Fc 600 Hz, Gain -.5 dB, Q 1\n",
        )
        assertEquals(listOf(Triple(500.0, 3.0, 0.7), Triple(600.0, -0.5, 1.0)), eq.bands.map { Triple(it.freqHz, it.gainDb, it.q) })
        assertEquals(listOf("filters 1, 2, 3, 4 unreadable, skipped"), eq.notes)
    }

    @Test fun `unreadable lines of the right block, which is not imported, are not named`() {
        val text = "Channel: L\nPreamp: -2 dB\nFilter 1: ON PK Fc 100 Hz Gain 1 dB Q 1\nChannel: R\nPreamp: -2 dB\nFilter 1: ON PK Fc 100 Hz Gain ? dB Q 1\n"
        val eq = ApoText.parseImport(text)
        assertEquals(listOf(100.0 to 1.0), eq.bands.map { it.freqHz to it.gainDb })
        assertTrue(eq.notes.none { "unreadable" in it }, eq.notes.toString())
    }

    @Test fun `a file of only unreadable filters imports nothing and names them`() {
        val eq = ApoText.parseImport("Preamp: -3 dB\nFilter 1: ON PK Fc 100 Hz Gain ? dB Q 1\nFilter 2: ON PK Fc 200 Hz Gain ? dB Q 1\n")
        assertTrue(eq.bands.isEmpty())
        assertEquals(listOf("filters 1, 2 unreadable, skipped"), eq.notes)
    }

    @Test fun `every profile Contour writes reads back with no note`() {
        val bands = listOf(
            band(1, FilterType.PEAK, 105.0, -0.5, 0.7), band(2, FilterType.LOW_SHELF, 60.0, 0.25, 0.707), band(3, FilterType.HIGH_SHELF, 9000.0, -12.0, 1.0),
            band(4, FilterType.LOW_PASS, 16000.0, 0.0, 0.5), band(5, FilterType.HIGH_PASS, 20.0, 0.0, 0.707), band(6, FilterType.BAND_PASS, 800.0, 0.0, 2.5),
            band(7, FilterType.ALL_PASS, 4000.0, 0.0, 0.707), band(8, FilterType.PEAK, 1000.0, 3.5, 4.0, on = false),
        )
        val eq = ApoText.parseImport(ApoText.format(bands, -0.5))
        assertEquals(bands, eq.bands)
        assertEquals(-0.5, eq.preampDb)
        assertTrue(eq.notes.isEmpty(), eq.notes.toString())
    }

    // ---- export ---------------------------------------------------------------------------------------------------

    @Test fun `squig import regex matches every PK LSC and HSC line Contour writes`() {
        val bands = listOf(
            band(1, FilterType.PEAK, 105.0, -3.5, 0.7),
            band(2, FilterType.LOW_SHELF, 80.0, 2.25, 0.71),
            band(3, FilterType.HIGH_SHELF, 10000.0, -1.5, 0.707),
            band(4, FilterType.PEAK, 8200.5, 0.0, 3.125, on = false),
            band(5, FilterType.PEAK, 20000.0, 12.0, 10.0),
        )
        for (text in listOf(ApoText.format(bands, -6.1234), ApoText.fileText(Profile("p", "P", bands = bands, createdAt = 0, updatedAt = 0), ShelfForm.SLOPE))) {
            val filters = text.lines().filter { it.startsWith("Filter") }
            assertEquals(5, filters.size)
            for (line in filters) {
                val m = checkNotNull(squigImport.find(line)) { "squig regex must match: $line" }
                assertEquals(0, m.range.first, line)
                assertTrue(m.groupValues[6].isNotEmpty(), "Q captured: $line")
            }
            assertTrue(text.startsWith("Preamp: "), text)
        }
    }

    @Test fun `file text is CRLF and parses back to the same bands`() {
        val bands = listOf(band(1, FilterType.PEAK, 105.0, -3.5, 0.7), band(2, FilterType.HIGH_SHELF, 9000.0, 2.0, 0.71, on = false))
        val p = Profile("p", "P", bands = bands, preampDb = -4.5, createdAt = 0, updatedAt = 0)
        val file = ApoText.fileText(p, ShelfForm.SLOPE)
        assertTrue(file.contains("\r\n"))
        assertFalse(file.replace("\r\n", "").contains('\n'), "no bare LF in the file")
        val back = ApoText.parse(file)
        assertEquals(bands.map { it.copy(id = "") }, back.bands.map { it.copy(id = "") })
        assertEquals(-4.5, back.preampDb)
        // the same text goes to SHARE, with LF
        assertEquals(ApoText.formatProfile(p, ShelfForm.SLOPE), file.replace("\r\n", "\n"))
    }

    @Test fun `a KA15-targeted profile exports its stored Q and preamp, never the device values`() {
        // The KA15 stores shelf Q x sqrt(2) and preamp + 12 dB in its registers; the profile and its text must not.
        val ka15 = FiioCatalog.KA15
        assertTrue(ka15.shelfQScale != 1.0 && ka15.preampOffsetDb != 0.0, "the correction exists, so this test can catch a leak")
        val bands = listOf(
            band(1, FilterType.LOW_SHELF, 105.0, 4.0, 0.7),
            band(2, FilterType.PEAK, 3000.0, -2.0, 1.4),
            band(3, FilterType.HIGH_SHELF, 9000.0, -3.0, 0.8),
        )
        val p = Profile("p", "KA", bands = bands, preampDb = -5.0, createdAt = 0, updatedAt = 0)
        assertEquals(emptyList(), DeviceTarget.KA15.issues(p), "the profile is valid for the KA15")
        for (text in listOf(ApoText.formatProfile(p, DeviceTarget.KA15.shelfForm), ApoText.fileText(p, DeviceTarget.KA15.shelfForm).replace("\r\n", "\n"))) {
            assertEquals(
                "Preamp: -5.0 dB\n" +
                    "Filter 1: ON LSC Fc 105 Hz Gain 4.0 dB Q 0.70\n" +
                    "Filter 2: ON PK Fc 3000 Hz Gain -2.0 dB Q 1.40\n" +
                    "Filter 3: ON HSC Fc 9000 Hz Gain -3.0 dB Q 0.80\n",
                text,
            )
        }
        // AUTO preamp: the auto value of the user's curve in the shelf form the KA15 plays (Q), again without any register offset
        val auto = p.copy(preampDb = null)
        val form = DeviceTarget.KA15.shelfForm
        assertEquals(ShelfForm.Q, form)
        assertEquals("Preamp: ${"%.1f".format(java.util.Locale.ROOT, Preamp.auto(bands, form).toDouble())} dB", ApoText.formatProfile(auto, form).lines().first())
    }

    // ---- file names -------------------------------------------------------------------------------------------------

    @Test fun `profile name from a squig file name`() {
        assertEquals("MOONDROP DUSK", ApoText.nameFromFile("Moondrop Dusk Filters.txt", 18))
        assertEquals("MOONDROP DUSK", ApoText.nameFromFile("Moondrop Dusk filters.TXT", 18))
        assertEquals("KIWI EARS QUINTET", ApoText.nameFromFile("Kiwi Ears Quintet.txt", 18))
        assertEquals("A VERY LONG HEADPHON", ApoText.nameFromFile("a very long headphone name Filters.txt", 20))
        assertEquals("PARAMETRICEQ", ApoText.nameFromFile("primary:Download/ParametricEQ.txt", 18))
        assertEquals("X", ApoText.nameFromFile("content/X.txt", 18))
        assertNull(ApoText.nameFromFile(".txt", 18))
        assertNull(ApoText.nameFromFile("  .txt", 18))
        assertNull(ApoText.nameFromFile(null, 18))
    }

    @Test fun `suggested file name follows squig and avoids forbidden characters`() {
        assertEquals("MOONDROP DUSK Filters.txt", ApoText.fileNameFor("MOONDROP DUSK"))
        assertEquals("A B Filters.txt", ApoText.fileNameFor("A/B"))
        assertEquals("Profile Filters.txt", ApoText.fileNameFor("  "))
        // and it names the same profile again
        assertEquals("MOONDROP DUSK", ApoText.nameFromFile(ApoText.fileNameFor("MOONDROP DUSK"), 18))
    }
}
