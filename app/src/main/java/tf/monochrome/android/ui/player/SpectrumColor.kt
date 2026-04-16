package tf.monochrome.android.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * Extracts the brightest (light-vibrant → vibrant → light-muted) swatch from
 * the current track's album art. Shared between the NowPlayingScreen cover-art
 * overlay and the EQ / Settings spectrum readouts so they all agree on a
 * single album-matched accent color.
 */
@Composable
fun rememberAlbumVibrantColor(imageUrl: String?, fallback: Color): Color {
    val context = LocalContext.current
    var vibrant by remember(imageUrl) { mutableStateOf(fallback) }

    Box(modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f }) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .build(),
            contentDescription = null
        ) {
            val state = painter.state
            if (state is AsyncImagePainter.State.Success) {
                LaunchedEffect(state) {
                    val bitmap = state.result.image.toBitmap()
                    androidx.palette.graphics.Palette.from(bitmap).generate { palette ->
                        vibrant = palette?.lightVibrantSwatch?.let { Color(it.rgb) }
                            ?: palette?.vibrantSwatch?.let { Color(it.rgb) }
                            ?: palette?.lightMutedSwatch?.let { Color(it.rgb) }
                            ?: palette?.dominantSwatch?.let { Color(it.rgb) }
                            ?: fallback
                    }
                }
            }
            SubcomposeAsyncImageContent()
        }
    }

    return vibrant
}

/**
 * Resolves the user-chosen spectrum color mode to a concrete [Color]. Called
 * by every screen that renders [SpectrumOverlay] so the pref is honored
 * consistently.
 *  - `"DYNAMIC"` → light-vibrant swatch from the current album art,
 *    falling back to `MaterialTheme.colorScheme.primary` when art is missing
 *    or palette extraction hasn't completed yet.
 *  - `"PRIMARY"` → theme primary.
 *  - `"WHITE"` → white.
 */
@Composable
fun rememberSpectrumColor(mode: String, imageUrl: String?): Color {
    val primary = MaterialTheme.colorScheme.primary
    return when (mode.uppercase()) {
        "WHITE" -> Color.White
        "PRIMARY" -> primary
        else -> rememberAlbumVibrantColor(imageUrl = imageUrl, fallback = primary)
    }
}
