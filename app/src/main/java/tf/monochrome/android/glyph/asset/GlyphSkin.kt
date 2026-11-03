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
 * the vector pack, so every skin is complete. An imported StepMania noteskin
 * is baked at import into the same layout as the retro one (one PNG per lane
 * and part, and a manifest), so the playfield draws it exactly as it draws
 * the built-in ones (see `glyph/style/StepManiaStyleBaker`).
 */
class GlyphSkin(
    val id: String,
    val label: String,
    /** The skin's folder: an asset path for the built-in ones, a folder on the phone for an imported one. */
    val root: String?,
    val isGlass: Boolean,
    /** True when [root] is a folder on the phone (an imported StepMania noteskin), not one in the app. */
    val isFile: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is GlyphSkin && other.id == id && other.root == root
    override fun hashCode(): Int = id.hashCode() * 31 + (root?.hashCode() ?: 0)
    override fun toString(): String = id

    companion object {
        val RETRO_GLASS = GlyphSkin("retro_glass", "Retro glass", RETRO_ROOT, isGlass = true)
        val RETRO = GlyphSkin("retro", "Retro", RETRO_ROOT, isGlass = false)
        val CLASSIC = GlyphSkin("classic", "Classic", null, isGlass = false)

        /** The skins the app ships, in the order the pickers list them. */
        val entries: List<GlyphSkin> = listOf(RETRO_GLASS, RETRO, CLASSIC)

        val DEFAULT = RETRO_GLASS

        /**
         * The StepMania noteskins imported on this phone (Trypt Mania's
         * Themes), kept up to date by `GlyphStyleLibrary`, which reads them
         * before anything asks for a skin by its id.
         */
        @Volatile
        var imported: List<GlyphSkin> = emptyList()

        /** Every skin to pick from: the app's, then the imported ones. */
        fun all(): List<GlyphSkin> = entries + imported

        /** An imported skin's id: its folder's name, so it survives a restart and a re-import. */
        fun importedId(folder: String): String = "$IMPORTED_PREFIX$folder"

        fun isImportedId(id: String): Boolean = id.startsWith(IMPORTED_PREFIX)

        fun fromId(id: String?): GlyphSkin = all().firstOrNull { it.id == id } ?: DEFAULT

        private const val IMPORTED_PREFIX = "imported:"
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
