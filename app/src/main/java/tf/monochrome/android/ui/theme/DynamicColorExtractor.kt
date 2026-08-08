package tf.monochrome.android.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Colors extracted from album art. Overrides a subset of the active color
 * scheme when the user has dynamic colors enabled — we intentionally only
 * touch primary/secondary slots so the rest of the chosen theme's hierarchy
 * (backgrounds, text-on-surface, outlines) stays intact.
 */
data class DynamicPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color
)

/**
 * Every slot interpolated at once, so a palette moves as one thing.
 *
 * Mixing per-slot animations would let primary arrive before onPrimary and put
 * text at the wrong contrast against its own background for a beat — visible
 * on a cover whose vibrant swatch is light and whose muted one is dark.
 */
internal fun lerp(start: DynamicPalette, stop: DynamicPalette, fraction: Float): DynamicPalette =
    DynamicPalette(
        primary = lerp(start.primary, stop.primary, fraction),
        onPrimary = lerp(start.onPrimary, stop.onPrimary, fraction),
        primaryContainer = lerp(start.primaryContainer, stop.primaryContainer, fraction),
        secondary = lerp(start.secondary, stop.secondary, fraction),
        onSecondary = lerp(start.onSecondary, stop.onSecondary, fraction),
    )

/**
 * Everything the app reads off one cover, from one Palette pass.
 *
 * The theme slots and the player's wash colours want different swatches, but
 * they want them from the same analysis. Two extractors used to decode the
 * bitmap and run Palette separately for the same URL on every track change —
 * both cached, so the cost was one extra pass per new cover, but it was also
 * two sets of results that could disagree about what the cover looks like.
 *
 * Each field is independently nullable because their fallback chains differ: a
 * cover with nothing but a muted swatch yields no [scheme] (the theme should
 * keep the user's own colours rather than tint everything grey-brown) while
 * still yielding a [dominant] for the player wash.
 */
data class CoverPalette(
    val scheme: DynamicPalette?,
    val dominant: Color?,
    val vibrant: Color?,
)

/**
 * Extracts a [CoverPalette] from a cover image URL off the main thread.
 *
 * Results are memoized in a bounded LruCache so repeated renders of the same
 * track don't re-run Palette, while a long listening session cycling through
 * hundreds of covers can't grow the map without limit. LruCache is internally
 * synchronized, which also covers concurrent calls from multiple composables.
 */
object DynamicColorExtractor {

    private val cache = android.util.LruCache<String, CoverPalette>(128)

    suspend fun extract(context: Context, coverUrl: String?): CoverPalette? {
        if (coverUrl.isNullOrBlank()) return null
        cache.get(coverUrl)?.let { return it }

        val bitmap = loadBitmap(context, coverUrl) ?: return null
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(MAX_COLOR_COUNT).generate()
        }

        // Prefer vibrant variants for the primary accent; fall back through
        // dominant/muted so a mostly-monochrome cover still produces something.
        val primarySwatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch

        val secondarySwatch = palette.mutedSwatch
            ?: palette.darkMutedSwatch
            ?: palette.lightMutedSwatch
            ?: primarySwatch

        val scheme = primarySwatch?.let {
            DynamicPalette(
                primary = Color(it.rgb),
                onPrimary = Color(it.bodyTextColor),
                primaryContainer = Color(it.rgb).copy(alpha = 0.18f),
                secondary = Color((secondarySwatch ?: it).rgb),
                onSecondary = Color((secondarySwatch ?: it).bodyTextColor),
            )
        }

        // The player's wash leads with the *dominant* colour, not the vibrant
        // one — it fills the whole screen, so the most-present colour is the
        // one that reads as "this album" rather than the loudest accent in it.
        val dominant = (palette.dominantSwatch ?: palette.vibrantSwatch ?: palette.mutedSwatch)
            ?.let { Color(it.rgb) }
        val vibrant = (palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.lightMutedSwatch
            ?: palette.dominantSwatch)
            ?.let { Color(it.rgb) }

        if (scheme == null && dominant == null && vibrant == null) return null

        val result = CoverPalette(scheme = scheme, dominant = dominant, vibrant = vibrant)
        cache.put(coverUrl, result)
        return result
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        return try {
            // Reuse the app-wide singleton loader (MonochromeApp tunes its
            // memory/disk caches per device tier). Building a fresh
            // ImageLoader here would allocate a new memory cache per track
            // change that is never shut down, and re-fetch covers Coil has
            // already cached.
            val loader = SingletonImageLoader.get(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                // Palette needs pixel-readable (software) bitmaps.
                .allowHardware(false)
                // Big enough that Palette has real pixels to quantise (a
                // thumbnail-sized decode returns empty swatches and everything
                // falls back to defaults), small enough to decode cheaply.
                .size(ANALYSIS_SIZE, ANALYSIS_SIZE)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                result.image.toBitmap()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_COLOR_COUNT = 24
    private const val ANALYSIS_SIZE = 256
}

/**
 * Composable helper: returns the dynamic palette for the given cover URL,
 * or null while loading / when extraction fails / when disabled.
 *
 * The result crosses over [blendMillis] rather than switching, so the mini
 * player, full player and lyrics change colour at the speed the audio changes
 * track. See [ColorBlend] for where that number comes from.
 */
@Composable
fun rememberDynamicPalette(
    coverUrl: String?,
    enabled: Boolean,
    blendMillis: Int = ColorBlend.GAPLESS_MS,
): State<DynamicPalette?> {
    val context = LocalContext.current
    val target = remember { mutableStateOf<DynamicPalette?>(null) }
    LaunchedEffect(coverUrl, enabled) {
        target.value = if (!enabled) null
        else DynamicColorExtractor.extract(context, coverUrl)?.scheme
    }
    return rememberBlendedPalette(target.value, blendMillis)
}

/**
 * Eases [target] in from whatever is currently showing.
 *
 * Only a palette-to-palette change is worth animating. Appearing (extraction
 * finished, Dynamic Colours switched on) and disappearing (switched off,
 * playback stopped) are handled by snapping, because the other end of the fade
 * is the user's plain theme and easing into it reads as a glitch rather than a
 * transition.
 *
 * A change mid-fade starts from where the blend actually got to, not from the
 * palette it was heading for — skipping through a queue must not step backwards
 * through colours that were never fully on screen.
 */
@Composable
private fun rememberBlendedPalette(
    target: DynamicPalette?,
    blendMillis: Int,
): State<DynamicPalette?> {
    val progress = remember { Animatable(1f) }
    val from = remember { mutableStateOf(target) }
    val to = remember { mutableStateOf(target) }

    LaunchedEffect(target) {
        if (target == to.value) return@LaunchedEffect
        val showing = blend(from.value, to.value, progress.value)
        if (showing == null || target == null || blendMillis <= 0) {
            from.value = target
            to.value = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        from.value = showing
        to.value = target
        progress.snapTo(0f)
        // Linear, unlike the rest of the app's tweens: this one is pacing an
        // equal-power audio crossfade, and an eased colour would sit ahead of
        // the sound in the middle of every transition.
        progress.animateTo(1f, tween(durationMillis = blendMillis, easing = LinearEasing))
    }

    return remember { derivedStateOf { blend(from.value, to.value, progress.value) } }
}

private fun blend(from: DynamicPalette?, to: DynamicPalette?, fraction: Float): DynamicPalette? {
    if (from == null || to == null) return to
    return lerp(from, to, fraction)
}
