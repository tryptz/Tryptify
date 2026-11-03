package tf.monochrome.android.ui.player

import android.os.Build
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.ceil

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

    val decodePx = rememberBlurredCoverDecodePx(blurRadius)
    Box(modifier.blur(blurRadius).dithered()) {
        // The outgoing cover stays fully opaque underneath and the incoming one
        // fades in over it. Fading both would thin the middle of the transition
        // and let the black behind show through.
        outgoing?.let { StretchedCover(it, decodePx) { 1f } }
        incoming?.takeIf { it.isNotBlank() }?.let { url ->
            StretchedCover(url, decodePx) { progress.value }
        }
    }
}

@Composable
private fun StretchedCover(url: String, decodePx: Int?, alpha: () -> Float) {
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            // Still useful for the load itself — this covers the gap between
            // the request starting and the bitmap arriving, which the dissolve
            // above knows nothing about.
            .crossfade(true)
            .apply { if (decodePx != null) size(decodePx) }
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

/**
 * The side to decode a full-screen cover at under a [blurRadius] blur, or null
 * to decode it at the size it is drawn. Modifier.blur does nothing below
 * Android 12, where the cover is drawn sharp, so there it keeps its full size.
 *
 * The screen's size comes from the configuration, which is known from the
 * first frame. A size that arrived a frame late would change the request
 * under a cover that had just started loading, and load it twice.
 */
@Composable
internal fun rememberBlurredCoverDecodePx(blurRadius: Dp): Int? {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val radiusPx = with(density) { blurRadius.toPx() }
    val longSidePx = with(density) {
        maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.roundToPx()
    }
    return remember(radiusPx, longSidePx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@remember null
        blurredCoverDecodePx(radiusPx, longSidePx)
    }
}

/**
 * Side, in pixels, to decode a cover drawn [drawnPx] across under a blur of
 * [blurRadiusPx], or null to decode it at the size it is drawn.
 *
 * Left to the size it is drawn, the background's cover was decoded at the
 * source's full size, 6.5 MB a song for a 1280 px cover and 36 MB for a
 * 3000 px one, all of it detail the blur then takes out again. The blur is a
 * gaussian of sigma 0.577·r + 0.5 (how the renderer reads a blur radius). With
 * at least [BLUR_TEXELS_PER_SIGMA] decoded pixels under one sigma, the finest
 * detail the smaller decode can hold comes through the blur at e^-79 of its
 * strength, and what it drops is finer still: the blurred picture comes out
 * the same, within a step of 8-bit colour. [MIN_BLURRED_COVER_PX] keeps a
 * floor under that; at the player's 40 and 64dp a phone decodes 256 px.
 */
internal fun blurredCoverDecodePx(blurRadiusPx: Float, drawnPx: Int): Int? {
    if (blurRadiusPx <= 0f || drawnPx <= 0) return null
    val sigma = 0.57735f * blurRadiusPx + 0.5f
    val side = ceil(drawnPx * BLUR_TEXELS_PER_SIGMA / sigma).toInt().coerceAtLeast(MIN_BLURRED_COVER_PX)
    return side.takeIf { it < drawnPx }
}

internal const val BLUR_TEXELS_PER_SIGMA = 4f
internal const val MIN_BLURRED_COVER_PX = 256
