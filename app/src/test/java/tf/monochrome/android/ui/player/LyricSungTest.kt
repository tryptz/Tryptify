package tf.monochrome.android.ui.player

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.LyricsFxPreset
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePackCodec

/**
 * When the lyrics' light is on ([LyricSung]): only while something is being
 * sung. And where the lyric text sits against the box its layers are drawn in
 * ([lyricSideInset], [lyricFitWidth]).
 */
class LyricSungTest {
    private fun word(start: Long, end: Long, text: String = "la") = LyricWord(start, end, text)

    /** Two word-timed lines with a gap inside the first and a long one between them. */
    private val karaoke = listOf(
        LyricLine(10_000, "Like a rush", listOf(word(10_000, 10_400), word(10_400, 10_700), word(11_000, 11_800))),
        LyricLine(20_000, "on the beach", listOf(word(20_100, 20_500), word(20_500, 21_000), word(21_000, 22_000))),
    )

    @Test
    fun `nothing is lit before the first line`() {
        for (byWord in listOf(false, true)) {
            assertEquals(LyricSung.NOT_STARTED, LyricSung.at(karaoke, 0, byWord))
            assertEquals(LyricSung.NOT_STARTED, LyricSung.at(karaoke, 9_999, byWord))
            assertFalse(LyricSung.at(emptyList(), 5_000, byWord).lit)
        }
    }

    @Test
    fun `following the word, the light is on from a word's start to its end`() {
        assertEquals(LyricSung.Now(0, 0, true), LyricSung.at(karaoke, 10_000, byWord = true))
        assertEquals(LyricSung.Now(0, 0, true), LyricSung.at(karaoke, 10_399, byWord = true))
        // The next word starts as this one ends: the light moves and stays on.
        assertEquals(LyricSung.Now(0, 1, true), LyricSung.at(karaoke, 10_400, byWord = true))
        // Between two words it is out, still on the word that just ended.
        assertEquals(LyricSung.Now(0, 1, false), LyricSung.at(karaoke, 10_700, byWord = true))
        assertEquals(LyricSung.Now(0, 1, false), LyricSung.at(karaoke, 10_999, byWord = true))
        assertEquals(LyricSung.Now(0, 2, true), LyricSung.at(karaoke, 11_000, byWord = true))
        // And after the line's last word, until the next line's first.
        assertEquals(LyricSung.Now(0, 2, false), LyricSung.at(karaoke, 11_800, byWord = true))
        assertEquals(LyricSung.Now(0, 2, false), LyricSung.at(karaoke, 19_999, byWord = true))
    }

    @Test
    fun `a line that has started is dark until its first word`() {
        // The second line's turn comes at 20.0 s and its first word at 20.1 s.
        assertEquals(LyricSung.Now(1, -1, false), LyricSung.at(karaoke, 20_050, byWord = true))
        assertEquals(LyricSung.Now(1, -1, false), LyricSung.at(karaoke, 20_050, byWord = false))
        assertEquals(LyricSung.Now(1, 0, true), LyricSung.at(karaoke, 20_100, byWord = true))
        assertEquals(LyricSung.Now(1, -1, true), LyricSung.at(karaoke, 20_100, byWord = false))
    }

    @Test
    fun `following the line, the light is on from its first word to the end of its last`() {
        assertEquals(LyricSung.Now(0, -1, true), LyricSung.at(karaoke, 10_000, byWord = false))
        // A gap between two of its words does not put it out.
        assertEquals(LyricSung.Now(0, -1, true), LyricSung.at(karaoke, 10_800, byWord = false))
        assertEquals(LyricSung.Now(0, -1, true), LyricSung.at(karaoke, 11_799, byWord = false))
        assertEquals(LyricSung.Now(0, -1, false), LyricSung.at(karaoke, 11_800, byWord = false))
        assertEquals(LyricSung.Now(0, -1, false), LyricSung.at(karaoke, 19_999, byWord = false))
        // The word is not part of the place then: a word passing is no move.
        assertEquals(
            LyricSung.at(karaoke, 10_100, byWord = false).place,
            LyricSung.at(karaoke, 11_500, byWord = false).place,
        )
    }

    @Test
    fun `a line with no word timings is sung until the next, and an empty one not at all`() {
        val lines = listOf(
            LyricLine(5_000, "At the end of the day"),
            LyricLine(9_000, ""),
            LyricLine(30_000, "When the sand is going to be cold"),
        )
        for (byWord in listOf(false, true)) {
            assertEquals(LyricSung.Now(0, -1, true), LyricSung.at(lines, 5_000, byWord))
            assertEquals(LyricSung.Now(0, -1, true), LyricSung.at(lines, 8_999, byWord))
            // The empty line is the break's own mark: the light is out for it.
            assertEquals(LyricSung.Now(1, -1, false), LyricSung.at(lines, 9_000, byWord))
            assertEquals(LyricSung.Now(1, -1, false), LyricSung.at(lines, 29_999, byWord))
            assertEquals(LyricSung.Now(2, -1, true), LyricSung.at(lines, 400_000, byWord))
        }
    }

    @Test
    fun `a word with no length lasts until the next word or line`() {
        // A line stored as one word that starts and ends at the line's start
        // (TimedLyricsStore), and a source that gives only starts.
        val lines = listOf(
            LyricLine(1_000, "whole line", listOf(word(1_000, 1_000, "whole line"))),
            LyricLine(4_000, "two words", listOf(word(4_000, 4_000), word(4_600, 4_600))),
        )
        assertEquals(LyricSung.Now(0, 0, true), LyricSung.at(lines, 3_999, byWord = true))
        assertEquals(LyricSung.Now(0, -1, true), LyricSung.at(lines, 3_999, byWord = false))
        assertEquals(LyricSung.Now(1, 0, true), LyricSung.at(lines, 4_599, byWord = true))
        // The last line's last word has nothing to end it.
        assertEquals(LyricSung.Now(1, 1, true), LyricSung.at(lines, 900_000, byWord = true))
        assertEquals(4_000L, LyricSung.wordEndMs(lines, 0, 0))
        assertEquals(4_600L, LyricSung.wordEndMs(lines, 1, 0))
    }

    @Test
    fun `each word and each line is a place of its own`() {
        val places = listOf(
            LyricSung.NOT_STARTED,
            LyricSung.Now(0, -1, true), LyricSung.Now(0, 0, true), LyricSung.Now(0, 1, true),
            LyricSung.Now(1, -1, true), LyricSung.Now(1, 0, true), LyricSung.Now(70_000, 3, true),
        ).map { it.place }
        assertEquals(places.size, places.toSet().size)
        // Going out does not move the light.
        assertEquals(LyricSung.Now(4, 2, true).place, LyricSung.Now(4, 2, false).place)
    }

    @Test
    fun `the light glides edge by edge and lands on the word`() {
        val from = Rect(100f, 400f, 220f, 460f)
        val to = Rect(260f, 400f, 420f, 460f)
        assertEquals(from, glideRect(from, to, 0f))
        assertEquals(to, glideRect(from, to, 1f))
        assertEquals(Rect(180f, 400f, 320f, 460f), glideRect(from, to, 0.5f))
        // Past either end it stays on the box.
        assertEquals(from, glideRect(from, to, -3f))
        assertEquals(to, glideRect(from, to, 7f))
        // A line's open sides stay open on the way to the next line.
        val u = GodRayGeometry.UNBOUNDED
        val mid = glideRect(Rect(-u, 300f, u, 360f), Rect(-u, 420f, u, 500f), 0.25f)
        assertEquals(-u, mid.left, 0f)
        assertEquals(u, mid.right, 0f)
        assertEquals(330f, mid.top, 1e-3f)
        assertEquals(395f, mid.bottom, 1e-3f)
    }

    @Test
    fun `the transition is a quarter second as shipped, and no theme sets it`() {
        assertEquals(250f, LyricsFxSettings.DEFAULT.godRayFadeMs, 0f)
        LyricsFxSettings.PRESETS.forEach { (name, preset) ->
            assertEquals(name, LyricsFxSettings.DEFAULT.godRayFadeMs, preset.godRayFadeMs, 0f)
        }
    }

    @Test
    fun `the transition is saved, synced and shared like any other part of the look`() {
        val mine = LyricsFxSettings.DEFAULT.copy(godRays = true, godRayFadeMs = 600f)
        // The saved blob, which is also what the settings sync sends to
        // Supabase (lyrics_fx_json), written as PreferencesManager writes it.
        val saved = Json { ignoreUnknownKeys = true }
        val blob = saved.encodeToString(mine)
        assertTrue(blob, blob.contains("\"godRayFadeMs\":600.0"))
        assertEquals(mine, saved.decodeFromString<LyricsFxSettings>(blob))
        // A blob from before the setting reads as the shipped quarter second,
        // and a preset saved then still lights its chip.
        val before = saved.decodeFromString<LyricsFxSettings>("""{"godRays":true,"godRayExposure":0.9}""")
        assertEquals(250f, before.godRayFadeMs, 0f)
        val oldPreset = saved.decodeFromString<LyricsFxPreset>(
            """{"name":"Mine","settings":{"godRays":true,"godRayExposure":0.9}}""",
        )
        assertTrue(before.matchesPreset(oldPreset.settings))
        // Every built-in theme still lights its own chip once applied.
        LyricsFxSettings.PRESETS.forEach { (name, preset) ->
            assertTrue(name, preset.withPersonalFrom(mine).matchesPreset(preset))
        }
        // A share code carries it, and one from before opens.
        val code = LyricsFxPreset.encode(LyricsFxPreset("Slow light", mine))
        assertEquals(600f, LyricsFxPreset.decode(code)!!.settings.godRayFadeMs, 0f)
        assertEquals(250f, LyricsFxPreset.decode("""TRYPTFX1:{"name":"Old","settings":{"godRays":true}}""")!!.settings.godRayFadeMs, 0f)
        // A Theme Store pack carries it, holds it to the slider's range, and a
        // pack made before the setting opens with the shipped value.
        val pack = ThemePackCodec.decode(ThemePackCodec.encode(ThemePack(name = "Slow light", lyrics = mine)))!!
        assertEquals(600f, pack.lyrics!!.godRayFadeMs, 0f)
        val wild = ThemePackCodec.decode("""{"format":"tryptheme","lyrics":{"godRays":true,"godRayFadeMs":90000}}""")!!
        assertEquals(1000f, wild.lyrics!!.godRayFadeMs, 0f)
        val old = ThemePackCodec.decode("""{"format":"tryptheme","lyrics":{"godRays":true}}""")!!
        assertEquals(250f, old.lyrics!!.godRayFadeMs, 0f)
        // It is part of the look, so publishing a pack keeps it.
        assertEquals(600f, ThemePackCodec.forSharing(ThemePack(name = "Slow light", lyrics = mine)).lyrics!!.godRayFadeMs, 0f)
    }

    @Test
    fun `the edge margin moves the text, and the text keeps its width`() {
        // The text starts the margin and the bevel-safe inset in from the
        // surface's side; the layers that draw it are the surface's width.
        val none = LyricsFxSettings.DEFAULT.copy(edgeMarginDp = 0f, bassReact = 0f)
        val wide = none.copy(edgeMarginDp = 40f)
        assertEquals(14.dp, lyricSideInset(none))
        assertEquals(54.dp, lyricSideInset(wide))
        // With no beat to leave room for, a line is fitted to exactly the
        // width between the insets, as it was before the inset moved inside.
        assertEquals(360.dp - 28.dp, lyricFitWidth(360.dp, none))
        assertEquals(360.dp - 108.dp, lyricFitWidth(360.dp, wide))
        // And with the beat on it is narrower still, never wider.
        assertTrue(lyricFitWidth(360.dp, wide.copy(bassReact = 1f, pumpAmount = 0.2f)) < lyricFitWidth(360.dp, wide))
        assertEquals(0.dp, lyricFitWidth(20.dp, wide))
    }
}
