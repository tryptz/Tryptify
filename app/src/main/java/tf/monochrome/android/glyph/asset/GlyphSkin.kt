// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import kotlinx.serialization.Serializable

/**
 * Which artwork the playfield draws.
 *
 * The two retro skins are StepMania's "retro" dance noteskin, cut into the
 * pack's asset names by tools/stepmania_skin/build_retro_skin.py. The glass
 * one is the same art turned into the app's glass at load time (see
 * [GlyphGlassLook]), from the listener's own UI-panel glass settings, so the
 * notes are the material the mini player and the panels are. Classic is the
 * vector pack the mode shipped with.
 *
 * Whatever a skin does not provide — lifts, fakes, the UI icons — comes from
 * the vector pack, so every skin is complete.
 */
enum class GlyphSkin(val id: String, val label: String, val root: String?, val isGlass: Boolean) {
    RETRO_GLASS("retro_glass", "Retro glass", RETRO_ROOT, isGlass = true),
    RETRO("retro", "Retro", RETRO_ROOT, isGlass = false),
    CLASSIC("classic", "Classic", null, isGlass = false),
    ;

    companion object {
        val DEFAULT = RETRO_GLASS
        fun fromId(id: String?): GlyphSkin = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

private const val RETRO_ROOT = "stepmania/skins/retro"

/** A bitmap skin's index: asset name to PNG, beside the images. */
@Serializable
data class GlyphSkinManifest(
    val name: String,
    val version: Int,
    /**
     * Scaled by whole pixels before smoothing, so 64-pixel art stays square-
     * edged at the 200-odd pixels a lane is on a phone.
     */
    val pixelArt: Boolean = false,
    val source: String = "",
    val license: String = "",
    val assets: List<GlyphSkinAsset>,
) {
    private val byName: Map<String, GlyphSkinAsset> by lazy { assets.associateBy { it.name } }
    fun asset(name: String): GlyphSkinAsset? = byName[name]
}

@Serializable
data class GlyphSkinAsset(
    val category: String,
    val name: String,
    val path: String,
)
