package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * The album art stretched across the whole screen and blurred, dissolving from
 * one cover to the next over [blendMillis].
 *
 * Coil's own `crossfade` can't do this job: it skips the transition entirely
 * for a memory-cache hit, and a cover that was just on screen in the queue or
 * the hero is always a memory-cache hit — so the backdrop hard-cut on exactly
 * the track changes this is meant to smooth. It is also fixed at 100ms, which
 * would leave the artwork finished while a multi-second blend is still running.
 *
 * Both covers are drawn into one layer and blurred once, rather than blurred
 * separately and then mixed. A full-screen 64dp blur is the expensive part of
 * this backdrop, and running a second one for the length of a 12s blend is not
 * a price worth paying for something deliberately out of focus.
 */
@Composable
internal fun BlurredCoverLayer(
    coverUrl: String?,
    blendMillis: Int,
    blurRadius: Dp,
    modifier: Modifier = Modifier,
) {
    var incoming by remember { mutableStateOf(coverUrl) }
    var outgoing by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(coverUrl) {
        if (coverUrl == incoming) return@LaunchedEffect
        val previous = incoming
        incoming = coverUrl
        // Nothing to dissolve between when either end is missing — a track with
        // no artwork, or the first one after the player opens.
        if (previous.isNullOrBlank() || coverUrl.isNullOrBlank() || blendMillis <= 0) {
            outgoing = null
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        outgoing = previous
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = blendMillis, easing = LinearEasing))
        outgoing = null
    }

    Box(modifier.blur(blurRadius).dithered()) {
        // The outgoing cover stays fully opaque underneath and the incoming one
        // fades in over it. Fading both would thin the middle of the transition
        // and let the black behind show through.
        outgoing?.let { StretchedCover(it) { 1f } }
        incoming?.takeIf { it.isNotBlank() }?.let { url ->
            StretchedCover(url) { progress.value }
        }
    }
}

@Composable
private fun StretchedCover(url: String, alpha: () -> Float) {
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            // Still useful for the load itself — this covers the gap between
            // the request starting and the bitmap arriving, which the dissolve
            // above knows nothing about.
            .crossfade(true)
            .build(),
        contentDescription = null,
        // Crop = fill the whole rectangle (stretch to cover), so a square cover
        // blooms across the full portrait screen.
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() },
    )
}
