package tf.monochrome.android.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Visual design tokens for the redesigned main player. Keeping sizes, corner
 * radii and glass tints in one place makes design iteration on the player a
 * matter of tweaking constants instead of hunting through layout code.
 */
object PlayerDesignTokens {
    val ScreenPadding = 24.dp
    val TopBarHeight = 56.dp

    val HeroCorner = 28.dp
    // Aspect ratio shared by the album-art hero and the projectM visualizer so
    // the two always occupy identically proportioned slots (1:1, matching the
    // square cover art). Routing both through one constant guarantees the
    // visualizer's dimensions match the artwork exactly.
    const val AlbumArtAspectRatio = 1f
    val GlassCornerLarge = 28.dp
    val GlassCornerMedium = 22.dp
    val GlassCornerSmall = 16.dp

    val PlayButtonSize = 72.dp
    val TransportIconSize = 34.dp
    val ActionIconSize = 24.dp

    val ProgressHeight = 4.dp
    val ProgressThumbSize = 14.dp

    val GlassTintStrong = 0.18f
    val GlassTintMedium = 0.12f
    val GlassTintSoft = 0.08f

    val FallbackAccent = Color(0xFF8ED081)
    val BackgroundBlack = Color(0xFF050706)
}

// Shared accent palette for the player surfaces. Kept here (rather than in the
// screen file) so every extracted player composable can reference them.
internal val PlayerGlowBlue = Color(0xFF7EB6FF)
internal val PlayerGlowPink = Color(0xFFFF7EB3)
internal val PlayerGlowMint = Color(0xFF6EF0C2)
internal val PlayerGlowGold = Color(0xFFFFC857)

/** Dominant + vibrant colors extracted from the current album art. */
data class AlbumColors(val dominant: Color, val vibrant: Color)

@Composable
fun rememberAlbumColors(imageUrl: String?): AlbumColors {
    val context = LocalContext.current
    var colors by remember(imageUrl) {
        mutableStateOf(AlbumColors(Color(0xFF1B1B1B), Color(0xFF7EB6FF)))
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        // Decode the cover at a usable size (a 1dp image yields an empty bitmap
        // and Palette returns null swatches → everything falls back to defaults).
        // Palette needs a pixel-readable (software) bitmap, hence allowHardware(false).
        val bitmap = try {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .size(256, 256)
                .build()
            val result = ImageLoader(context).execute(request)
            (result as? SuccessResult)?.image?.toBitmap()
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        val extracted = withContext(Dispatchers.Default) {
            val palette = androidx.palette.graphics.Palette.from(bitmap).generate()
            val dominant = palette.dominantSwatch?.let { Color(it.rgb) }
                ?: palette.vibrantSwatch?.let { Color(it.rgb) }
                ?: palette.mutedSwatch?.let { Color(it.rgb) }
            val vibrant = palette.vibrantSwatch?.let { Color(it.rgb) }
                ?: palette.lightVibrantSwatch?.let { Color(it.rgb) }
                ?: palette.lightMutedSwatch?.let { Color(it.rgb) }
                ?: palette.dominantSwatch?.let { Color(it.rgb) }
            if (dominant == null && vibrant == null) null
            else AlbumColors(dominant ?: Color(0xFF1B1B1B), vibrant ?: dominant ?: Color(0xFF7EB6FF))
        }
        if (extracted != null) colors = extracted
    }

    return colors
}

@Composable
fun rememberDominantColor(imageUrl: String?): Color = rememberAlbumColors(imageUrl).dominant

/** Vertical gradient wash derived from the album art, fading into pure black. */
fun dynamicPlayerBackground(color: Color): Brush = Brush.verticalGradient(
    colors = listOf(
        color.copy(alpha = 0.5f),
        color.copy(alpha = 0.2f),
        PlayerDesignTokens.BackgroundBlack
    )
)

/** Soft radial glow behind the hero, tinted by the current album color. */
@Composable
fun DynamicAlbumGlow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize().graphicsLayer { alpha = 0.45f }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.3f), Color.Transparent)
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.5f, size.height * 0.3f)
        )
    }
}
