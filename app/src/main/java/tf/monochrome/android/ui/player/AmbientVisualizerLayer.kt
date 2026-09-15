package tf.monochrome.android.ui.player

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import tf.monochrome.android.visualizer.AmbientVisualizerSettings
import tf.monochrome.android.visualizer.ProjectMEngineRepository
import tf.monochrome.android.visualizer.ProjectMOverlayView

/**
 * The player's background, when ambient mode is on: the blurred cover, its
 * scrim, and MilkDrop composited over both.
 *
 * This *replaces* [PlayerBlurredArtBackground] rather than sitting over it.
 * The blend has to happen in the fragment shader — HWUI composites a
 * TextureView with plain source-over, and `glBlendFunc` only reaches inside
 * our own surface, so a Screen blend against the artwork is only possible if
 * the shader has the artwork. Which means this layer draws the backdrop
 * itself, opaquely, and there is no seam between the two to line up.
 *
 * The scrim tone is [backdropScrimTone] — the same darkened dominant the
 * glass shader uses for its mid-screen band — so the background under ambient
 * mode is the one the rest of the player was tuned against.
 */
@Composable
internal fun AmbientVisualizerLayer(
    repository: ProjectMEngineRepository,
    settings: AmbientVisualizerSettings,
    cover: Bitmap?,
    dominant: Color,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrim = backdropScrimTone(dominant)
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            ProjectMOverlayView(context, repository).apply {
                updateSettings(settings)
                updateAlbum(cover)
                updateScrimTone(scrim.red, scrim.green, scrim.blue)
                updatePlayback(isPlaying)
            }
        },
        update = { view ->
            view.updateSettings(settings)
            view.updateAlbum(cover)
            view.updateScrimTone(scrim.red, scrim.green, scrim.blue)
            view.updatePlayback(isPlaying)
        },
    )
}
