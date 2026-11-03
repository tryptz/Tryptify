// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Music links page's presets: the file, the list kept on the phone, and which one is in use. */
class MusicLinkPresetsTest {

    private val keys = VisualLinkTargets.ALL.map { it.key }

    private fun link(at: Int, paused: Boolean = false): VisualLink {
        val target = VisualLinkTargets.ALL[at]
        return VisualLink(
            target = target.key,
            shape = LinkShape(lowest = target.min, highest = target.max, band = LinkBand.VOCALS),
            paused = paused,
        )
    }

    private val preset = MusicLinkPreset("Club night", listOf(link(0), link(1, paused = true)))

    @Test
    fun anExportedPresetImportsAsItWas() {
        val back = MusicLinkPresets.import(MusicLinkPresets.export(preset)).getOrThrow()
        assertEquals(preset.name, back.name)
        assertEquals(preset.links, back.links)
        // The pause of each link travels with it.
        assertTrue(back.links[1].paused)
    }

    @Test
    fun aFileOfAnythingElseIsRefused() {
        assertTrue(MusicLinkPresets.import("not json").isFailure)
        assertTrue(MusicLinkPresets.import("""{"type":"tryptify-crt-preset","version":1,"name":"x"}""").isFailure)
        val newer = MusicLinkPresets.export(preset).replace("\"version\": 1", "\"version\": 99")
        assertEquals("Made by a newer Tryptify", MusicLinkPresets.import(newer).exceptionOrNull()?.message)
    }

    @Test
    fun aNewerFileOpensAsFarAsThisVersionUnderstandsIt() {
        val target = VisualLinkTargets.ALL[0]
        val text = """{"type":"tryptify-music-links","version":1,"name":"  From later  ","future":true,"links":[
            {"target":"${target.key}","shape":{"band":"SUBSONIC_WHALE","lowest":-1e9,"highest":1e9}},
            {"target":"somewhere.new","shape":{}},
            {"target":"${target.key}","shape":{}}]}"""
        val back = MusicLinkPresets.import(text).getOrThrow()
        assertEquals("From later", back.name)
        // The unknown setting is dropped, the setting named twice keeps its first link.
        assertEquals(listOf(target.key), back.links.map { it.target })
        // An unknown band falls back, and the range is held to the setting's.
        assertEquals(LinkBand.KICK, back.links[0].shape.band)
        assertTrue(back.links[0].shape.lowest >= target.min && back.links[0].shape.highest <= target.max)
    }

    @Test
    fun aFileWithNoLinksThisVersionKnowsIsRefusedRatherThanClearingThePage() {
        val text = """{"type":"tryptify-music-links","version":1,"name":"x","links":[{"target":"somewhere.new"}]}"""
        assertEquals("No links this version knows", MusicLinkPresets.import(text).exceptionOrNull()?.message)
    }

    @Test
    fun theSavedListRoundTripsAndABrokenOneIsEmpty() {
        val list = listOf(preset, MusicLinkPreset("Calm", listOf(link(2))))
        assertEquals(list, MusicLinkPresets.decodeAll(MusicLinkPresets.encodeAll(list)))
        assertEquals(emptyList<MusicLinkPreset>(), MusicLinkPresets.decodeAll("{broken"))
        assertEquals(emptyList<MusicLinkPreset>(), MusicLinkPresets.decodeAll(null))
    }

    @Test
    fun thePresetInUseIsTheSameLinksInAnyOrder() {
        assertTrue(MusicLinkPresets.matches(preset, preset.links.reversed()))
        assertFalse(MusicLinkPresets.matches(preset, preset.links.take(1)))
        // One link paused differently is another set.
        assertFalse(MusicLinkPresets.matches(preset, listOf(link(0), link(1, paused = false))))
        assertTrue(keys.size >= 3)
    }

    @Test
    fun fileNamesAreSafeEverywhere() {
        assertEquals("Club_night.links.json", MusicLinkPresets.fileName("Club night"))
        assertEquals("music-links.links.json", MusicLinkPresets.fileName("../:*?"))
    }
}
