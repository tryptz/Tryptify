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
import tf.monochrome.android.ui.theme.DynamicColorExtractor

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
    // Skip previous/next read 50% larger than the other transport glyphs.
    val SkipIconSize = 51.dp
    val ActionIconSize = 24.dp
    // Dock glyphs are label-less, so the icon fills the freed vertical space.
    val DockIconSize = 38.dp

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
    // Deliberately not keyed on imageUrl: keying it reset the colours to these
    // neutral defaults the instant the track changed, so the player crossfaded
    // old → grey → new and showed a grey wash in the middle of every
    // transition. Holding the previous track's colours until the next ones are
    // extracted makes it a single move, and means a cover that fails to decode
    // leaves the player looking like the last one that worked rather than grey.
    var colors by remember {
        mutableStateOf(AlbumColors(Color(0xFF1B1B1B), Color(0xFF7EB6FF)))
    }

    LaunchedEffect(imageUrl) {
        if (imageUrl.isNullOrBlank()) return@LaunchedEffect
        // Shares DynamicColorExtractor's single Palette pass and its cache. This
        // used to decode the bitmap and run Palette a second time for the same
        // URL — the theme's extractor had already done it on every track change
        // — so the two could also disagree about what a cover looks like.
        val extracted = DynamicColorExtractor.extract(context, imageUrl) ?: return@LaunchedEffect
        val dominant = extracted.dominant
        val vibrant = extracted.vibrant
        if (dominant == null && vibrant == null) return@LaunchedEffect
        colors = AlbumColors(
            dominant = dominant ?: Color(0xFF1B1B1B),
            vibrant = vibrant ?: dominant ?: Color(0xFF7EB6FF),
        )
    }

    return colors
}

@Composable
fun rememberDominantColor(imageUrl: String?): Color = rememberAlbumColors(imageUrl).dominant

/** Vertical gradient wash derived from the album art, fading into pure black. */
fun dynamicPlayerBackground(color: Color): Brush {
    // Darken the album color before the top stop so the hardcoded-white player
    // chrome (chevron, output/speed chips) keeps adequate contrast even on a
    // bright cover, instead of washing out to ~3:1 white-on-light.
    val wash = androidx.compose.ui.graphics.lerp(color, Color.Black, 0.5f)
    return Brush.verticalGradient(
        colors = listOf(
            wash.copy(alpha = 0.5f),
            wash.copy(alpha = 0.2f),
            PlayerDesignTokens.BackgroundBlack
        )
    )
}

/** Soft radial glow behind the hero, tinted by the current album color. */
@Composable
fun DynamicAlbumGlow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize().dithered().graphicsLayer { alpha = 0.45f }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.3f), Color.Transparent)
            ),
            radius = size.width * 0.8f,
            center = Offset(size.width * 0.5f, size.height * 0.3f)
        )
    }
}
