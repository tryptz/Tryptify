package tf.monochrome.android.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.ImageLoader
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
 * Extracts a [DynamicPalette] from a cover image URL off the main thread.
 *
 * Results are memoized in a small in-memory map so repeated renders of the
 * same track don't re-run Palette. The cache is intentionally unbounded but
 * process-scoped; the dataset is small (one entry per distinct cover in a
 * session) and the app restart clears it.
 */
object DynamicColorExtractor {

    private val cache = mutableMapOf<String, DynamicPalette>()

    suspend fun extract(context: Context, coverUrl: String?): DynamicPalette? {
        if (coverUrl.isNullOrBlank()) return null
        cache[coverUrl]?.let { return it }

        val bitmap = loadBitmap(context, coverUrl) ?: return null
        val palette = withContext(Dispatchers.Default) {
            Palette.from(bitmap).maximumColorCount(24).generate()
        }

        // Prefer vibrant variants for the primary accent; fall back through
        // dominant/muted so a mostly-monochrome cover still produces something.
        val primarySwatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.dominantSwatch
            ?: return null

        val secondarySwatch = palette.mutedSwatch
            ?: palette.darkMutedSwatch
            ?: palette.lightMutedSwatch
            ?: primarySwatch

        val dp = DynamicPalette(
            primary = Color(primarySwatch.rgb),
            onPrimary = Color(primarySwatch.bodyTextColor),
            primaryContainer = Color(primarySwatch.rgb).copy(alpha = 0.18f),
            secondary = Color(secondarySwatch.rgb),
            onSecondary = Color(secondarySwatch.bodyTextColor)
        )
        cache[coverUrl] = dp
        return dp
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        return try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                // Palette needs pixel-readable (software) bitmaps.
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                result.image.toBitmap()
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Composable helper: returns the dynamic palette for the given cover URL,
 * or null while loading / when extraction fails / when disabled.
 */
@Composable
fun rememberDynamicPalette(coverUrl: String?, enabled: Boolean): State<DynamicPalette?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<DynamicPalette?>(null) }
    LaunchedEffect(coverUrl, enabled) {
        state.value = if (!enabled) null
        else DynamicColorExtractor.extract(context, coverUrl)
    }
    return state
}
