package tf.monochrome.android.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * The hero album art, dissolving from one cover into the next.
 *
 * The new cover arrives slightly oversized and settles to its final size as it
 * becomes opaque, over an outgoing cover that stays put and fully opaque. That
 * asymmetry is what makes it read as one image becoming another rather than two
 * images ghosting: nothing ever thins out, so the player background never shows
 * through the middle of a transition the way it would if both sides faded.
 *
 * **How long it takes depends on why the track changed.** Left alone, the art
 * moves with the audio — [blendMillis], the "Blend Between Tracks" length, so a
 * long crossfade doesn't finish repainting while the previous song is still
 * playing. Asked to change, it takes [MANUAL_MORPH_MS]: a manual skip cancels
 * any blend in progress and cuts the audio immediately, so a slow dissolve
 * would be describing a transition that isn't happening, and the swipe gesture
 * already carries its own 260ms slide.
 *
 * [userTrackChanges] is the counter `PlayerViewModel` bumps on every playback
 * change the UI asked for. Compared at the moment the track changes, so a
 * change with no counter movement is one the player made on its own.
 */
@Composable
internal fun MorphingCoverArt(
    trackKey: Any?,
    coverUrl: String?,
    contentDescription: String?,
    blendMillis: Int,
    userTrackChanges: Int,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    var incoming by remember { mutableStateOf(coverUrl) }
    var outgoing by remember { mutableStateOf<String?>(null) }
    var seenUserChanges by remember { mutableIntStateOf(userTrackChanges) }
    val progress = remember { Animatable(1f) }

    // Keyed on the track, not the cover: skipping between two tracks off the
    // same album changes no artwork, but it does have to be recorded as a
    // change the user made — otherwise the counter stays stale and the next
    // transition the player makes on its own is mistaken for a skip.
    LaunchedEffect(trackKey) {
        val userDriven = userTrackChanges != seenUserChanges
        seenUserChanges = userTrackChanges
        if (coverUrl == incoming) return@LaunchedEffect

        val previous = incoming
        incoming = coverUrl
        // Nothing to dissolve between when either end is missing — a track with
        // no artwork, or the first one after the player opens.
        if (previous.isNullOrBlank() || coverUrl.isNullOrBlank()) {
            outgoing = null
            progress.snapTo(1f)
            return@LaunchedEffect
        }

        outgoing = previous
        progress.snapTo(0f)
        val durationMs =
            if (userDriven) MANUAL_MORPH_MS else blendMillis.coerceAtLeast(MANUAL_MORPH_MS)
        // Linear for the same reason the colours are: this is pacing an
        // equal-power audio crossfade, not decorating a tap.
        progress.animateTo(1f, tween(durationMillis = durationMs, easing = LinearEasing))
        outgoing = null
    }

    Box(
        modifier = modifier
            .clip(shape)
            // Opaque base + the music-note fallback behind both covers, matching
            // CoverImage: a url that fails to load shows the note rather than a
            // hole, and the dissolve never reveals whatever is behind the hero.
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.5f),
        )
        // The outgoing cover is a plain opaque base. Fading it out as well
        // would thin the middle of every transition.
        outgoing?.let { url -> CoverLayer(url, Modifier) }
        incoming?.takeIf { it.isNotBlank() }?.let { url ->
            CoverLayer(
                url,
                Modifier.graphicsLayer {
                    // Read in the draw phase: a transition costs no
                    // recomposition, which matters when it can run for the
                    // length of a 12s blend.
                    val settled = progress.value
                    alpha = settled
                    val scale = 1f + SETTLE_SCALE * (1f - settled)
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

@Composable
private fun CoverLayer(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * How long the art takes when the change was asked for. Matches the swipe
 * gesture's incoming slide, so a swipe-skip is one movement rather than a
 * slide with a dissolve of a different length running underneath it.
 */
internal const val MANUAL_MORPH_MS = 260

/** How much oversized the incoming cover starts. Enough to read as motion. */
private const val SETTLE_SCALE = 0.06f
