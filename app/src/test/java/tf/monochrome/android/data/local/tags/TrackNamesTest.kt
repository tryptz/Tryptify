package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** File names by their place on a release give their title without the numbering. */
class TrackNamesTest {

    @Test
    fun `track and disc numbering comes off`() {
        for (name in listOf(
            "01 - Song", "1-05 - Song", "1-05 Song", "2-05 Song", "05. Song", "05 Song",
            "12 - Song", "12. Song", "CD1 - 05 - Song", "cd 2 - 03 - Song", "Disc 2 - 03 - Song",
            "Disc 2 03 Song", "CD1 - Song",
        )) {
            assertEquals(name, "Song", TrackNames.withoutNumberPrefix(name))
        }
    }

    @Test
    fun `a title that starts with a number keeps it`() {
        for (name in listOf("99 Luftballons", "7 Rings", "1999", "22", "4 Minutes", "Song 2", "Artist - Song")) {
            assertEquals(name, name, TrackNames.withoutNumberPrefix(name))
        }
    }

    @Test
    fun `numbering then an artist and title leaves the artist and title`() {
        assertEquals("Dolly Parton - Jolene", TrackNames.withoutNumberPrefix("05 - Dolly Parton - Jolene"))
        assertEquals("Dolly Parton" to "Jolene", splitArtistTitle(TrackNames.withoutNumberPrefix("05 - Dolly Parton - Jolene")))
    }

    @Test
    fun `a name that is only numbering stays as it is`() {
        assertEquals("01", TrackNames.withoutNumberPrefix("01"))
    }

    @Test
    fun `numbering an older scan took for an artist is recognised`() {
        for (artist in listOf("01", "1-05", "CD1", "Disc 2", "12", "０３")) assertTrue(artist, TrackNames.looksLikeNumbering(artist))
        for (artist in listOf("Dolly Parton", "Blink-182", "2Pac", "The 1975", null)) {
            assertFalse("$artist", TrackNames.looksLikeNumbering(artist))
        }
    }

    @Test
    fun `numbering is never cut from a longer run of digits, and any dash ends it`() {
        for (name in listOf("1-2-3 Redlight", "2-4-6-8 Motorway", "3.14159")) assertEquals(name, name, TrackNames.withoutNumberPrefix(name))
        for (name in listOf("03 — Song", "03 ~ Song", "03 ‐ Song", "3 — Song", "1-05 — Song", "01 -Song", "1-05 -Song", "02 -Song")) {
            assertEquals(name, "Song", TrackNames.withoutNumberPrefix(name))
        }
        assertEquals("2Pac - California Love", TrackNames.withoutNumberPrefix("02 2Pac - California Love"))
        // Zero-padded, it is numbering whatever follows it, digits too.
        for ((name, rest) in listOf(
            "05-2Pac - California Love" to "2Pac - California Love",
            "01-10cc - Dreadlock Holiday" to "10cc - Dreadlock Holiday",
            "01.2Pac - X" to "2Pac - X",
            "03_1999" to "1999",
            "1-05-2Pac - X" to "2Pac - X",
            "CD1-05-2Pac - X" to "2Pac - X",
            "01-02-03 Song" to "03 Song",
            "2-05-1999 Song" to "1999 Song",
            "1-05-99 Luftballons" to "99 Luftballons",
            "CD1-05-12 Monkeys" to "12 Monkeys",   // after "CD1" only a track
            "CD01 - Song" to "Song",
            "CD01 - 05 - Song" to "Song",
        )) assertEquals(name, rest, TrackNames.withoutNumberPrefix(name))
        assertEquals("05 -", TrackNames.withoutNumberPrefix("05 -"))
        // A spaced tilde after a number that is not zero-padded is an artist's:
        // the band 311, as scene rips name files.
        assertEquals("311 ~ Amber", TrackNames.withoutNumberPrefix("311 ~ Amber"))
    }

    // ── A moon's label ──────────────────────────────────────────────────
    // Some rips write the whole file name into the title tag.

    private fun label(title: String, track: Int?, artist: String? = "Bioweapon", disc: Int? = 1, albumArtist: String? = null) =
        TrackNames.label(title, track, disc, listOf(artist, albumArtist))

    @Test
    fun `a title tag holding the file's whole name gives the title`() {
        assertEquals("A.A.C.S.", label("03 Bioweapon, Audiofreq, Code Black - A.A.C.S.", 3))
        assertEquals("A.A.C.S.", label("03 Bioweapon, Audiofreq, Code Black - A.A.C.S.", 3, artist = "Bioweapon, Audiofreq, Code Black"))
        // Not a numbering form, so its 2 stays.
        assertEquals("2 The Dancefloor", label("02 Bioweapon, Audiofreq, Code Black - 2 The Dancefloor", 2))
        assertEquals("Jolene", label("05 - Dolly Parton - Jolene", 5, artist = "Dolly Parton"))
        assertEquals("Song", label("1-05 Song", 5))
        assertEquals("Song", label("CD2 - 05 - Song", 5, disc = 2))
        assertEquals("Song", label("5. Song", 5))
        assertEquals("Song", label("12 - Song", 12))
        assertEquals("Song", label("03 — Song", 3))
        assertEquals("California Love", label("05-2Pac - California Love", 5, artist = "2Pac"))
        // Read as the track's own tags confirm, not as the first form found.
        assertEquals("In da Club", label("05-50 Cent - In da Club", 5, artist = "50 Cent"))
        assertEquals("Wasteland", label("05-10 Years - Wasteland", 5, artist = "10 Years"))
        assertEquals("My Culture", label("01-1 Giant Leap - My Culture", 1, artist = "1 Giant Leap"))
        assertEquals("Song", label("01-5 Song", 5))
        assertEquals("12 Monkeys", label("CD1-05-12 Monkeys", 5))
        assertEquals("CD1-05-12-cd", label("CD1-05-12-cd", 12))       // after "CD1" only a track
        // Its track confirmed, its disc not: none of it comes off.
        assertEquals("02-02 Song", label("02-02 Song", 2, disc = 1))
        assertEquals("01-01 Song", label("01-01 Song", 1, disc = null))
        assertEquals("Song", label("02-02 Song", 2, disc = 2))
        assertEquals("California Love", label("02 2Pac - California Love", 2, artist = "2Pac"))  // a space ends the number
        assertEquals("夜に駆ける", label("０３　夜に駆ける", 3, artist = "YOASOBI"))                // full width
        assertEquals("夜に駆ける", label("YOASOBI　feat.　Ayase - 夜に駆ける", 1, artist = "YOASOBI"))  // ideographic spaces
    }

    @Test
    fun `each comes off once`() {
        assertEquals("3 - Song", label("03 - 3 - Song", 3))
        assertEquals("Bioweapon - Waves", label("Bioweapon - Bioweapon - Waves", 1))
    }

    @Test
    fun `only the track's own number comes off`() {
        assertEquals("7 Rings", label("7 Rings", 7))                 // not a numbering form
        assertEquals("03 Song", label("03 Song", 4))                 // another track's number
        assertEquals("03 Song", label("03 Song", null))              // nothing to check it by
        assertEquals("2-05 Song", label("2-05 Song", 5, disc = 1))   // another disc's
        assertEquals("24-7 Heaven", label("24-7 Heaven", 7))         // "disc 24" is not this one
        assertEquals("1-2-3 Redlight", label("1-2-3 Redlight", 2))   // a run of digits stays whole
        assertEquals("3.14159", label("3.14159", 3))
        assertEquals("5.15 (Remastered)", label("5.15 (Remastered)", 15, disc = null))  // no disc tag: not disc 5
        assertEquals("1999", label("1999", 1))
        assertEquals("99 Luftballons", label("99 Luftballons", 99))
    }

    @Test
    fun `only the track's own artist comes off`() {
        assertEquals("Waves", label("Bioweapon - Waves", 1))
        assertEquals("Waves", label("Bioweapon ~ Waves", 1))
        assertEquals("Waves", label("Audiofreq & Bioweapon - Waves", 1))
        assertEquals("Waves", label("Bioweapon - Waves", 1, artist = null, albumArtist = "Bioweapon"))
        assertEquals("Waves", label("BIOWEAPON - Waves", 1))
        assertEquals("Heart of Hearts", label("!!! - Heart of Hearts", 1, artist = "!!!"))  // folds to nothing
        assertEquals("Waves", label("Bioweapon / Audiofreq - Waves", 1))
        assertEquals("Waves", label("Bioweapon feat. Audiofreq - Waves", 1))
        assertEquals("Come Together", label("Beatles - Come Together", 1, artist = "The Beatles"))
        assertEquals("Losing My Religion", label("REM - Losing My Religion", 1, artist = "R.E.M."))
        // A guest is not the credit, and "and" joins words of a title.
        assertEquals("Waves feat. Audiofreq - Club Mix", label("Waves feat. Audiofreq - Club Mix", 1, artist = "Bioweapon; Audiofreq"))
        assertEquals("Live and Let Die - Remastered", label("Live and Let Die - Remastered", 3, artist = "Live"))
        // A capital X ends a name; a small x joins two.
        assertEquals("Industry Baby", label("Lil Nas X & Jack Harlow - Industry Baby", 1, artist = "Lil Nas X"))
        assertEquals("Industry Baby", label("Lil Nas X - Industry Baby", 1, artist = "Lil Nas X feat. Jack Harlow"))
        assertEquals("Song", label("Foo x Bar - Song", 1, artist = "Bar"))
        assertEquals("The Boxer", label("Simon & Garfunkel - The Boxer", 1, artist = "Simon and Garfunkel"))
        assertEquals("Song", label("!!! feat. Nic - Song", 1, artist = "!!!"))
        assertEquals("Song", label("!!! - Song", 1, artist = "!!! & Foo"))
        assertEquals("Dynamite", label("BTS - Dynamite", 1, artist = "𝐁𝐓𝐒"))      // styled letters are letters
        // Folded, "+" is "and": a band called "+" is not every "And".
        assertEquals("And - Song", label("And - Song", 1, artist = "+"))
        assertEquals("Song", label("+ - Song", 1, artist = "+"))
        assertEquals("Someone Else - Waves", label("Someone Else - Waves", 1))
        assertEquals("Bioweapon — Pat B Remix", label("Bioweapon — Pat B Remix", 1))  // an em dash is the title's own
        assertEquals("Waves", label("Waves", 1, artist = null))
    }

    @Test(timeout = 2_000)
    fun `a long run of spaces in a tag is read at once`() {
        val gap = " ".repeat(20_000)
        assertEquals("C", label("A${gap}B - C", 3, artist = "A${gap}B"))
        assertEquals("Foo${gap}feat.${gap}Bar - Song", label("Foo${gap}feat.${gap}Bar - Song", 3, artist = "Baz${gap}Qux"))
        assertEquals("Song", TrackNames.withoutNumberPrefix("$gap 05 - Song"))
    }

    @Test
    fun `a label is never blank`() {
        assertEquals("03 -", label("03 - ", 3))          // the number was all there was
        assertEquals("Bioweapon -", label("Bioweapon - ", 1))
        assertEquals("03", label("03", 3))
    }
}
