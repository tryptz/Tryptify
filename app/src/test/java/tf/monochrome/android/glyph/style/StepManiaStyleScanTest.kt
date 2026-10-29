// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.style.StepManiaStyleScan.Kind

/**
 * Trypt Mania's StepMania import, held to the file names real downloads from
 * the StepMania Archive use (an SM 3.9 noteskin, an SM5 megapack, a theme with
 * its own noteskins, an announcer), read without running anything in them.
 */
class StepManiaStyleScanTest {

    // ── what is in an archive ───────────────────────────────────────────

    @Test
    fun aNoteskinAThemeAndAnAnnouncerAreToldApart() {
        val found = StepManiaStyleScan.scan(
            listOf(
                "2ndMIX-vivid/Down Tap Note 4th 4x4.png",
                "2ndMIX-vivid/metrics.ini",
                "Themes/AOI -1/Graphics/Judgment 1x6.png",
                "Themes/AOI -1/BGAnimations/ScreenGameplay go/ready.png",
                "Themes/AOI -1/metrics.ini",
                "Themes/AOI -1/NoteSkins/dance/aoi/Down Tap Note.png",
                "Themes/AOI -1/Graphics/MusicWheelItem Song NormalPart.png",
                "Ashens/gameplay ready/1.ogg",
                "Ashens/gameplay cleared/1.ogg",
                "Ashens/evaluation aa/1.ogg",
            ),
        )
        assertEquals(
            listOf(
                StepManiaStyleScan.Found(Kind.NOTESKIN, "2ndMIX-vivid", "2ndMIX-vivid"),
                StepManiaStyleScan.Found(Kind.NOTESKIN, "aoi", "Themes/AOI -1/NoteSkins/dance/aoi"),
                StepManiaStyleScan.Found(Kind.THEME, "AOI -1", "Themes/AOI -1"),
                StepManiaStyleScan.Found(Kind.ANNOUNCER, "Ashens", "Ashens"),
            ).sortedWith(compareBy({ it.kind }, { it.name.lowercase() })),
            found,
        )
    }

    @Test
    fun aThemesOwnGraphicsAreNotANoteskin() {
        val found = StepManiaStyleScan.scan(
            listOf(
                "Simply Love/Graphics/_shared/Down Tap Note.png",
                "Simply Love/BGAnimations/x.png",
            ),
        )
        assertEquals(listOf(Kind.THEME), found.map { it.kind })
    }

    @Test
    fun stepManiasNamesReadAsItReadsThem() {
        assertEquals("down tap note", NoteSkinNames.element("_Down Tap Note 1x8 (doubleres).png"))
        assertEquals(1 to 8, NoteSkinNames.sheet("_Down Tap Note 1x8 (doubleres).png"))
        assertEquals(4 to 4, NoteSkinNames.sheet("Down Tap Note 4th 4x4.png"))
        assertEquals(1 to 1, NoteSkinNames.sheet("Down Receptor Waiting.png"))
        assertTrue(NoteSkinNames.isDoubleRes("x (doubleres).png"))
        assertTrue(StepManiaStyleScan.isTapNote("Down Tap Note 12th 4x4.png"))
    }

    // ── a noteskin's parts ──────────────────────────────────────────────

    @Test
    fun anSm39SkinHasATapNoteForEachDivisionAndItsPartsThroughRedirects() {
        val files = mapOf(
            "s/Down Tap Note 4th 4x4.png" to "",
            "s/Down Tap Note 8th 4x4.png" to "",
            "s/Down Tap Note 16th 4x4.png" to "",
            "s/Down Receptor Waiting.png" to "",
            "s/Down Receptor Go.sprite" to "[Sprite]\nTexture=Down Receptor Waiting.png\n",
            "s/Down Hold Head Inactive.png" to "",
            "s/Down Hold Body Inactive.png" to "",
            "s/Down Hold BottomCap Inactive.png" to "",
            "s/Down Hold Tail Inactive.redir" to "Down Hold BottomCap Inactive",
            "s/Down Tap Explosion Dim.png" to "",
            "s/Down Tap Explosion Bright.sprite" to "[Sprite]\nTexture=Down Tap Explosion Dim.png",
        )
        val resolver = NoteSkinResolver(files.keys, "s") { files[it] }
        val taps = resolver.taps()
        assertEquals("s/Down Tap Note 4th 4x4.png", taps[GlyphBeatDivision.QUARTER]?.image?.path)
        assertEquals("s/Down Tap Note 16th 4x4.png", taps[GlyphBeatDivision.SIXTEENTH]?.image?.path)
        // A division the skin has no art for has none, and the game draws its 4th's.
        assertNull(taps[GlyphBeatDivision.TWELFTH])
        assertEquals(0, taps.getValue(GlyphBeatDivision.QUARTER).frame)
        val (rest, active) = resolver.receptor()!!
        assertEquals("s/Down Receptor Waiting.png", rest.image.path)
        assertEquals("s/Down Receptor Waiting.png", active.image.path)
        assertEquals("s/Down Tap Explosion Dim.png", resolver.explosion()?.image?.path)
        assertEquals("s/Down Hold BottomCap Inactive.png", resolver.holdTail(roll = false, active = false)?.image?.path)
        // A roll with no art of its own is the hold's.
        assertEquals("s/Down Hold Body Inactive.png", resolver.holdBody(roll = true, active = false)?.image?.path)
    }

    @Test
    fun anSm5SkinsQuantizedSheetIsARowPerDivisionReachedThroughItsLua() {
        val files = mapOf(
            "Alpha/Down Tap Note.lua" to "return NOTESKIN:LoadActor(Var \"Button\", \"_Down Tap Note\")",
            "Alpha/_Down Tap Note 1x8 (doubleres).png" to "",
            "Alpha/Down Receptor.lua" to "local t = Def.ActorFrame { LoadActor(\"_Down Tap Receptor\") }",
            "Alpha/_Down Tap Receptor (doubleres).png" to "",
            "Alpha/Down Tap Mine.lua" to "return LoadActor(\"_Down Tap Mine\")",
            "Alpha/_Down Tap Mine (doubleres).png" to "",
        )
        val resolver = NoteSkinResolver(files.keys, "Alpha") { files[it] }
        val taps = resolver.taps()
        val sheet = taps.getValue(GlyphBeatDivision.QUARTER).image
        assertEquals("Alpha/_Down Tap Note 1x8 (doubleres).png", sheet.path)
        assertTrue(sheet.doubleRes)
        // Row by row: the 16th is the fourth row of a one-column sheet.
        assertEquals(3, taps.getValue(GlyphBeatDivision.SIXTEENTH).frame)
        assertEquals(7, taps.getValue(GlyphBeatDivision.SIXTY_FOURTH).frame)
        assertEquals("Alpha/_Down Tap Receptor (doubleres).png", resolver.receptor()?.first?.image?.path)
        assertEquals("Alpha/_Down Tap Mine (doubleres).png", resolver.mine()?.image?.path)
    }

    @Test
    fun aRedirectNeverLeavesTheArchiveNorLoopsForever() {
        val files = mapOf(
            "s/Down Tap Note.redir" to "../../../../etc/passwd",
            "s/Down Receptor.redir" to "Down Receptor",
        )
        val resolver = NoteSkinResolver(files.keys, "s") { files[it] }
        assertTrue(resolver.taps().isEmpty())
        assertNull(resolver.receptor())
    }

    // ── a theme's art ───────────────────────────────────────────────────

    @Test
    fun aThemesJudgementsComboBackgroundAndSoundsAreFound() {
        val theme = ThemeResolver(
            listOf(
                "T/Graphics/Player Judgment/dance/judgelabels 1x6.png",
                "T/Graphics/Player Judgment/pump/judgelabels 1x6.png",
                "T/Graphics/HoldJudgment 1x2.png",
                "T/Graphics/Player Judgment/dance/Combo [numbers] 5x3.png",
                "T/Graphics/Player Judgment/dance/Combo [label].png",
                "T/Graphics/Common fallback background.png",
                "T/Sounds/ScreenGameplay assist tick.ogg",
                "T/Fonts/Common Normal.ttf",
                "T/NoteSkins/dance/x/Down Tap Note.png",
            ),
            "T",
        ).find()
        assertEquals(ThemeResolver.Sheet("T/Graphics/Player Judgment/dance/judgelabels 1x6.png", 1, 6), theme.judgements)
        assertEquals(5, theme.comboDigits?.cols)
        assertEquals("T/Graphics/Player Judgment/dance/Combo [label].png", theme.comboLabel)
        assertEquals("T/Graphics/Common fallback background.png", theme.background)
        assertEquals("T/Sounds/ScreenGameplay assist tick.ogg", theme.hitSound)
        assertEquals(listOf("T/Fonts/Common Normal.ttf"), theme.fonts)
        assertNull(theme.missSound)
    }

    @Test
    fun anSm39ThemesJudgementSheetIsFoundAtItsTop() {
        val theme = ThemeResolver(listOf("AOI/Graphics/Judgment 1x6.png", "AOI/Graphics/HoldJudgment 1x2.png"), "AOI").find()
        assertNotNull(theme.judgements)
        assertEquals(1, theme.judgements?.cols)
    }

    @Test
    fun comboDigitsAreReadFromTheFontsIniOrInOrder() {
        val fromIni = ThemeResolver.digitCells("[main]\nLine 0=01234\nLine 1=56789\nLine 2=x%.\n", cols = 5, rows = 3)
        assertEquals(0, fromIni['0'])
        assertEquals(9, fromIni['9'])
        assertEquals(10, fromIni['x'])
        val inOrder = ThemeResolver.digitCells(null, cols = 5, rows = 3)
        assertEquals((0..9).toList(), ('0'..'9').map { inOrder.getValue(it) })
    }

    // ── an announcer ────────────────────────────────────────────────────

    @Test
    fun announcerFoldersAreTheMomentsARunHas() {
        assertEquals(AnnouncerEvent.READY, AnnouncerEvent.forFolder("Gameplay Ready"))
        assertEquals(AnnouncerEvent.GRADE_AA, AnnouncerEvent.forFolder("evaluation aa"))
        assertEquals(AnnouncerEvent.COMBO, AnnouncerEvent.forFolder("gameplay comment hot"))
        assertNull(AnnouncerEvent.forFolder("select music intro"))
    }
}
