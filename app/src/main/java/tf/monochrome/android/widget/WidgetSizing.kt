package tf.monochrome.android.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which layout the widget draws, and how big its cover is, from the size the
 * launcher actually gives it.
 *
 * It used to be fixed buckets (`SizeMode.Responsive`), and the medium one
 * asked for 140dp of height. ColorOS reports a 5x2 widget a little shorter
 * than that, so the launcher fell back to the 4x1 bar: a thin row with a tiny
 * cover in the middle of a tall, empty pane (seen on a ColorOS 17 phone). Now
 * the widget is laid out at its exact size (`SizeMode.Exact`), a layout is
 * picked by what fits, and the cover grows into the room there is.
 */
internal enum class WidgetLayout { Compact, Bar, Square, Medium, Large }

/** The title, the artist and the transport row stacked: what the medium layout's text column needs. */
internal val TEXT_BLOCK = 84.dp

/** The gap above the progress line and the line itself. */
internal val PROGRESS_SPACE = 16.dp

internal val MEDIUM_PAD = 14.dp

/** The medium layout's transport row: previous 40, gaps 6, play 44, next 40. */
internal val MEDIUM_TRANSPORT = 136.dp

/** The gap between the cover and the text. */
internal val COVER_GAP = 14.dp

private val WIDE = 270.dp

internal fun pickLayout(width: Dp, height: Dp, showProgress: Boolean): WidgetLayout {
    val progress = if (showProgress) PROGRESS_SPACE else 0.dp
    return when {
        width >= WIDE && height >= 230.dp -> WidgetLayout.Large
        width >= WIDE && height >= MEDIUM_PAD * 2 + TEXT_BLOCK + progress -> WidgetLayout.Medium
        width < WIDE && width >= 110.dp && height >= 120.dp -> WidgetLayout.Square
        width >= 240.dp -> WidgetLayout.Bar
        else -> WidgetLayout.Compact
    }
}

/**
 * The medium layout's cover: as tall as the row allows, but never so wide
 * that the transport beside it runs out of room, and never smaller than the
 * text column, so the title still lines up with its top.
 */
internal fun mediumCover(width: Dp, height: Dp, showProgress: Boolean): Dp {
    val progress = if (showProgress) PROGRESS_SPACE else 0.dp
    val byHeight = height - MEDIUM_PAD * 2 - progress
    val byWidth = width - MEDIUM_PAD * 2 - COVER_GAP - MEDIUM_TRANSPORT
    return minOf(byHeight, byWidth, 132.dp).coerceAtLeast(56.dp)
}

/** The bar's cover: the height of the row, within reason. */
internal fun barCover(height: Dp): Dp = (height - 20.dp).coerceIn(34.dp, 64.dp)

/** The large layout's cover: up to 140dp, and at most 45% of the width. */
internal fun largeCover(width: Dp): Dp = minOf(140.dp, (width - 40.dp) * 0.45f)
