// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `assets/stepmania/glyph/manifest.json`, as written by the pack generator.
 *
 * Gameplay code never builds a file name. It asks [GlyphAssetCatalog] for a
 * semantic asset and gets a manifest entry back, so renaming a file in the pack
 * is caught the next time the manifest is validated instead of becoming a blank
 * lane at run time.
 */
@Serializable
data class GlyphManifest(
    val name: String,
    val version: Int,
    @SerialName("designGrid") val designGrid: Int = 8,
    val palette: GlyphPalette,
    val assets: List<GlyphManifestAsset>,
) {
    /** Manifest entries by their semantic name. Names are unique per pack. */
    val byName: Map<String, GlyphManifestAsset> = assets.associateBy { it.name }

    fun asset(name: String): GlyphManifestAsset? = byName[name]
}

@Serializable
data class GlyphManifestAsset(
    val category: String,
    val name: String,
    val path: String,
    val viewBox: String,
    val tintable: Boolean,
) {
    /** The viewBox's width and height, or null if the manifest is malformed. */
    val size: Pair<Float, Float>?
        get() {
            val parts = viewBox.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            return if (parts.size == 4) parts[2] to parts[3] else null
        }
}

/**
 * The pack's colours.
 *
 * Beat colours encode rhythmic subdivision rather than lane, which is the
 * reason direction has to stay legible from shape: someone who cannot separate
 * the eight beat hues still has four distinct arrow silhouettes to read.
 */
@Serializable
data class GlyphPalette(
    val ink: String,
    val paper: String,
    val muted: String,
    @SerialName("beatColors") val beatColors: Map<String, String>,
    @SerialName("laneAccents") val laneAccents: Map<String, String>,
)
