// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import tf.monochrome.android.glyph.data.TryptManiaRequest

/**
 * Trypt Mania's mark: the noteskin's own arrow (`noteskin/taps`, the up arrow
 * in its 64-unit box), outline and hollow as on the playfield, turned to
 * point up and right like a play button. One colour, so it takes the menu's
 * tint as any icon does.
 */
val TryptManiaArrow: ImageVector by lazy {
    ImageVector.Builder(
        name = "TryptManiaArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 64f,
        viewportHeight = 64f,
    ).apply {
        // Turned about the box's centre, the arrow sits a little low and left
        // of it; moved back by that much.
        group(rotate = 45f, pivotX = 32f, pivotY = 32f, translationX = 2.5f, translationY = -2.5f) {
            addPath(
                pathData = addPathNodes(ARROW_OUTLINE + ARROW_HOLLOW),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
        }
    }.build()
}

/** The arrow's outline, as the noteskin draws it. */
private const val ARROW_OUTLINE =
    "M32 4 L59 31 Q61 33 59 35 L47 47 Q45 49 43 47 L39 43 V57 Q39 60 36 60 H28 Q25 60 25 57 V43 " +
        "L21 47 Q19 49 17 47 L5 35 Q3 33 5 31 Z "

/** Its inner arrow, cut out: the noteskin's highlight, here a hole, so it reads as DDR's hollow arrow. */
private const val ARROW_HOLLOW = "M32 14 L50 32 L43 39 L35 31 V51 H29 V31 L21 39 L14 32 Z"

/**
 * "Play in Trypt Mania" from a song's menu: given at the app's root, where
 * navigation is, so every list's menu offers it without wiring each one.
 * Null where nothing provides it, and the menu then leaves the item out.
 */
val LocalPlayInTryptMania = staticCompositionLocalOf<((TryptManiaRequest) -> Unit)?> { null }

/**
 * "Find album art" from a song's menu and from a selection: songs that are
 * files on the phone, looked up online for a cover (AlbumArtFinder). Given at
 * the app's root like "Play in Trypt Mania"; a menu offers it only for a song
 * whose file it knows.
 */
val LocalFindAlbumArt = staticCompositionLocalOf<((List<tf.monochrome.android.data.local.art.ArtRequest>) -> Unit)?> { null }

/**
 * "Upload to Tryptify Cloud" from a song's menu or the selection bar: the
 * songs' files on the phone, queued for the listener's cloud storage. Given at
 * the app's root like "Find album art"; offered only for songs whose file it knows.
 */
val LocalUploadToCloud = staticCompositionLocalOf<((List<String>) -> Unit)?> { null }
