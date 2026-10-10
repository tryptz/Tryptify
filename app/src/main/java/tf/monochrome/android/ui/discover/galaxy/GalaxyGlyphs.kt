package tf.monochrome.android.ui.discover.galaxy

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A star system as a glyph: three planets of different sizes, big, middling
 * and small — the dock's button for what a long press on a star does, play it
 * and open its planets. Drawn on Material's 24 grid, filled, so it sits with
 * the dock's Play, Radio and Top 100 glyphs and tints like them.
 */
internal val SystemGlyph: ImageVector by lazy {
    ImageVector.Builder(
        name = "GalaxySystem",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            circle(8.6f, 14.4f, 5.4f)
            circle(17.6f, 7.4f, 3.2f)
            circle(18.9f, 17.9f, 1.9f)
        }
    }.build()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2f * r, dy1 = 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2f * r, dy1 = 0f)
    close()
}
