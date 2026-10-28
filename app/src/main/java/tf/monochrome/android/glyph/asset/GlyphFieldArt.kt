// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The playfield's art at one note size, looked up by place rather than name.
 *
 * Built once the field is warm ([GlyphAssetRepository.prewarm]) and read while
 * drawing. Asked by name every frame, each note built its asset's name as a
 * new string and each lookup a new key for the map: hundreds of small objects
 * a frame in a dense chart, for answers that do not change during a song. Here
 * a note's art is one array read. The effects are held at twice the note
 * size, the size they are drawn at.
 *
 * It holds its bitmaps itself, so the repository dropping its rasters (the
 * game warming another size, the mode being left) never takes art from a
 * field still on screen.
 */
class GlyphFieldArt private constructor(
    private val taps: Array<ImageBitmap?>,
    private val heads: Array<ImageBitmap?>,
    private val receptors: Array<ImageBitmap?>,
    private val bodies: Array<ImageBitmap?>,
    private val tails: Array<ImageBitmap?>,
    private val specials: Array<ImageBitmap?>,
    private val glows: Array<ImageBitmap?>,
    private val explosions: Array<ImageBitmap?>,
    /** A miss's crack, at twice the note size: the same art in every lane. */
    val missCrack: ImageBitmap?,
    /** The combo milestone's burst, at twice the note size. */
    val comboBurst: ImageBitmap?,
) {
    fun tap(lane: Int, division: GlyphBeatDivision): ImageBitmap? = taps[lane * DIVISIONS + division.ordinal]

    fun holdHead(lane: Int, roll: Boolean): ImageBitmap? = heads[lane * 2 + if (roll) 1 else 0]

    fun receptor(lane: Int, active: Boolean): ImageBitmap? = receptors[lane * 2 + if (active) 1 else 0]

    fun holdBody(roll: Boolean, active: Boolean): ImageBitmap? = bodies[pair(roll, active)]

    fun holdTail(roll: Boolean, active: Boolean): ImageBitmap? = tails[pair(roll, active)]

    fun special(note: GlyphSpecialNote): ImageBitmap? = specials[note.ordinal]

    /** At twice the note size. */
    fun holdGlow(lane: Int): ImageBitmap? = glows[lane]

    /** At twice the note size. */
    fun tapExplosion(lane: Int): ImageBitmap? = explosions[lane]

    companion object {
        private val LANES = GlyphLane.entries
        private val DIVISIONS = GlyphBeatDivision.entries.size

        private fun pair(roll: Boolean, active: Boolean): Int = (if (roll) 2 else 0) + if (active) 1 else 0

        /**
         * What [assets] holds warm for a field of [noteSizePx] notes, by the
         * same names the playfield used to ask for each frame; a slot nothing
         * warmed is null, as [GlyphAssetRepository.image] answered.
         */
        fun from(assets: GlyphAssetRepository, noteSizePx: Int): GlyphFieldArt {
            val twice = noteSizePx * 2
            fun note(id: GlyphAssetId) = assets.image(id, noteSizePx, noteSizePx)
            fun effect(id: GlyphAssetId) = assets.image(id, twice, twice)
            return GlyphFieldArt(
                taps = Array(LANES.size * DIVISIONS) { i ->
                    note(GlyphAssetCatalog.tap(LANES[i / DIVISIONS], GlyphBeatDivision.entries[i % DIVISIONS]))
                },
                heads = Array(LANES.size * 2) { i -> note(GlyphAssetCatalog.holdHead(LANES[i / 2], roll = i % 2 == 1)) },
                receptors = Array(LANES.size * 2) { i -> note(GlyphAssetCatalog.receptor(LANES[i / 2], active = i % 2 == 1)) },
                bodies = Array(4) { i -> note(GlyphAssetCatalog.holdBody(roll = i >= 2, active = i % 2 == 1)) },
                tails = Array(4) { i -> note(GlyphAssetCatalog.holdTail(roll = i >= 2, active = i % 2 == 1)) },
                specials = Array(GlyphSpecialNote.entries.size) { i -> note(GlyphAssetCatalog.special(GlyphSpecialNote.entries[i])) },
                glows = Array(LANES.size) { i -> effect(GlyphAssetCatalog.effect(GlyphEffectArt.HOLD_GLOW, LANES[i])) },
                explosions = Array(LANES.size) { i -> effect(GlyphAssetCatalog.effect(GlyphEffectArt.TAP_EXPLOSION, LANES[i])) },
                missCrack = effect(GlyphAssetCatalog.effect(GlyphEffectArt.MISS_CRACK)),
                comboBurst = effect(GlyphAssetCatalog.effect(GlyphEffectArt.COMBO_BURST)),
            )
        }
    }
}
