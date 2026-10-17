// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.asset.GlyphPopnColour
import tf.monochrome.android.glyph.asset.GlyphPopnLane

/**
 * pop'n's notes and buttons, drawn rather than loaded.
 *
 * The noteskins are arrows cut per dance panel; pop'n's "pop-kun" are discs
 * in the colour of the button they fall on, and the same five colours are
 * all the field needs. Drawing them keeps the skins' art and tests as they
 * are, and the colour comes from the lane, as pop'n's does, where a dance
 * note's comes from the beat.
 */
object GlyphPopnArt {

    /** The button colours, as the cabinet paints them. */
    fun colour(colour: GlyphPopnColour): Color = when (colour) {
        GlyphPopnColour.WHITE -> Color(0xFFF4F4F8)
        GlyphPopnColour.YELLOW -> Color(0xFFFFD23F)
        GlyphPopnColour.GREEN -> Color(0xFF4CD964)
        GlyphPopnColour.BLUE -> Color(0xFF3F8CFF)
        GlyphPopnColour.RED -> Color(0xFFFF3B4E)
    }

    fun colour(lane: GlyphLane): Color = colour(GlyphPopnLane.of(lane).colour)

    /** The ink a note's outline and a button's rim are drawn in. */
    private val OUTLINE = Color(0xFF14121C)

    /** A pop-kun: a disc in its button's colour, a dark rim, a highlight on its shoulder. */
    fun DrawScope.drawPopnNote(centre: Offset, sizePx: Float, lane: GlyphLane, alpha: Float = 1f) {
        if (alpha <= 0f) return
        val radius = sizePx / 2f
        val fill = colour(lane)
        drawCircle(color = fill.copy(alpha = alpha), radius = radius, center = centre)
        drawCircle(
            color = OUTLINE.copy(alpha = 0.85f * alpha),
            radius = radius - sizePx * 0.03f,
            center = centre,
            style = Stroke(width = sizePx * 0.06f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.55f * alpha),
            radius = radius * 0.28f,
            center = Offset(centre.x - radius * 0.3f, centre.y - radius * 0.32f),
        )
    }

    /**
     * A button's receptor: a ring in its colour, filled while the button is
     * held, and brighter by [flash] on the beat.
     */
    fun DrawScope.drawPopnReceptor(centre: Offset, sizePx: Float, lane: GlyphLane, held: Boolean, flash: Float = 0f) {
        val radius = sizePx / 2f
        val fill = colour(lane)
        drawCircle(color = fill.copy(alpha = if (held) 0.75f else 0.18f + flash * 0.5f), radius = radius, center = centre)
        drawCircle(
            color = fill.copy(alpha = 0.95f),
            radius = radius - sizePx * 0.05f,
            center = centre,
            style = Stroke(width = sizePx * 0.08f),
        )
    }

    /** A long pop-kun's body: a bar of the button's colour from the tail down to the head. */
    fun DrawScope.drawPopnHoldBody(centreX: Float, headY: Float, tailY: Float, sizePx: Float, lane: GlyphLane, active: Boolean, alpha: Float = 1f) {
        if (headY <= tailY || alpha <= 0f) return
        val width = sizePx * 0.56f
        val fill = colour(lane)
        drawRoundRect(
            color = fill.copy(alpha = (if (active) 0.85f else 0.55f) * alpha),
            topLeft = Offset(centreX - width / 2f, tailY),
            size = Size(width, headY - tailY),
            cornerRadius = CornerRadius(width / 2f),
        )
        drawRoundRect(
            color = OUTLINE.copy(alpha = 0.6f * alpha),
            topLeft = Offset(centreX - width / 2f, tailY),
            size = Size(width, headY - tailY),
            cornerRadius = CornerRadius(width / 2f),
            style = Stroke(width = sizePx * 0.04f),
        )
    }

    /** A pad button, as the two-row controller has it: a disc, lit while down. */
    fun DrawScope.drawPopnButton(centre: Offset, sizePx: Float, lane: GlyphLane, held: Boolean, flash: Float = 0f) {
        val radius = sizePx / 2f
        val fill = colour(lane)
        drawCircle(color = OUTLINE.copy(alpha = 0.55f), radius = radius, center = Offset(centre.x, centre.y + sizePx * 0.04f))
        drawCircle(color = fill.copy(alpha = if (held) 1f else 0.45f + flash * 0.4f), radius = radius, center = centre)
        drawCircle(color = OUTLINE.copy(alpha = 0.8f), radius = radius - sizePx * 0.03f, center = centre, style = Stroke(width = sizePx * 0.05f))
        if (held || flash > 0f) {
            drawCircle(color = Color.White.copy(alpha = if (held) 0.5f else 0.35f * flash), radius = radius * 0.55f, center = centre)
        }
    }
}
