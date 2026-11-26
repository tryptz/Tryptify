package tf.monochrome.android.ui.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import tf.monochrome.android.domain.model.Spectrogram3DSettings
import tf.monochrome.android.domain.model.SpectrumWaterfallSettings
import tf.monochrome.android.domain.model.WaveCandySettings

/**
 * Lyrics FX › Visualizer under the lyrics: in the Lyrics view, the visualizer
 * the cover was showing ([visual]) stays along the bottom of the lyric area
 * while the album art goes, and the lyrics are drawn over it. Each view sits
 * at the share of the height it takes on the cover, at the bottom.
 */
@Composable
internal fun LyricsVisualizerBand(
    visual: CoverVisual,
    bins: () -> FloatArray,
    color: Color,
    glow: Color,
    waterfall: SpectrumWaterfallSettings,
    waveSettings: WaveCandySettings?,
    spectrogram3d: Spectrogram3DSettings,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    /** The words being sung, as a light on the 3D spectrogram's surface ([Spectrogram3DOverlay]). */
    lyricLight: ((SpectrogramLamp) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val bottom = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        when {
            visual == CoverVisual.WAVE_CANDY && waveSettings != null -> {
                val kick = rememberKickPulse(
                    enabled = isPlaying && waveSettings.kickEnabled,
                    onsetRatio = waveSettings.kickOnsetRatio,
                )
                WaveCandyOverlay(
                    settings = waveSettings,
                    accent = color,
                    kick = kick,
                    isPlaying = isPlaying,
                    modifier = bottom.height(maxHeight * 0.25f),
                )
            }
            visual == CoverVisual.SPECTROGRAM_3D && SPECTROGRAM_3D_SUPPORTED -> Spectrogram3DOverlay(
                bins = LocalSpectrogramBins.current ?: bins,
                settings = spectrogram3d,
                color = color,
                glow = glow,
                isPlaying = isPlaying,
                modifier = bottom.height(maxHeight * 0.4f),
                resetKey = resetKey,
                lyricLight = lyricLight,
            )
            else -> SpectrumOverlay(
                bins = bins,
                color = color,
                modifier = bottom,
                height = maxHeight * 0.35f,
                waterfall = waterfall,
                resetKey = resetKey,
            )
        }
    }
}

/**
 * Draws what it is put on out to the screen's left and right edges, and down
 * to its bottom edge when [toBottom], whatever padding its parent sits in:
 * the visualizer band under expanded lyrics, whose page keeps a margin for
 * the words and stops above the navigation bar, left the water short of the
 * phone's edges on three sides ("fill the phone edges", the owner). The
 * parent keeps its own size and place; only this is measured larger and set
 * back by the gap on the left.
 *
 * Only a margin is crossed ([EdgeBleed.MAX_MARGIN_DP]): a wider gap is
 * something standing beside it (the controls, in the player's side-by-side
 * layout), and the band stays out of that.
 */
internal fun Modifier.bleedToScreenEdges(toBottom: Boolean): Modifier = composed {
    val margin = with(LocalDensity.current) { EdgeBleed.MAX_MARGIN_DP.dp.roundToPx() }
    // Left, right and bottom gaps to the root's edges, in px.
    var gaps by remember { mutableStateOf(IntOffset.Zero to 0) }
    onGloballyPositioned { c ->
        val root = c.findRootCoordinates().size
        val at = c.positionInRoot()
        val next = IntOffset(
            EdgeBleed.gap(at.x, margin),
            EdgeBleed.gap(root.width - at.x - c.size.width, margin),
        ) to EdgeBleed.gap(root.height - at.y - c.size.height, margin)
        if (next != gaps) gaps = next
    }.layout { measurable, constraints ->
        val left = gaps.first.x
        val right = gaps.first.y
        val down = if (toBottom) gaps.second else 0
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable = measurable.measure(Constraints.fixed(width + left + right, height + down))
        layout(width, height) { placeable.place(-left, 0) }
    }
}

internal object EdgeBleed {
    /** The widest gap to a screen edge that counts as a margin to draw across. */
    const val MAX_MARGIN_DP = 64

    /** How far to draw past an edge that lies [distancePx] from the screen's: all of a margin, none of a wider gap. */
    fun gap(distancePx: Float, marginPx: Int): Int {
        val d = distancePx.roundToInt()
        return if (d in 1..marginPx) d else 0
    }
}

/**
 * Aims [lamp] at what the lyric view is singing: the sung word where the
 * light follows the word, else the middle of the lyric surface (a line's band
 * has no ends of its own), in [color], as much on as the lyric light is.
 * Leaves the lamp off while nothing is sung or no lyric surface is laid out.
 */
internal fun LyricLetterCapture.aim(lamp: SpectrogramLamp, color: Color) {
    val box = boxInRoot ?: return
    // The lamp's own feed where the lyric view gives one (the sung word, on
    // and off with it); else the god rays' light.
    val band = (lampInRoot ?: bandInRoot)() ?: return
    val whole = band.left <= -GodRayGeometry.UNBOUNDED / 2f || band.right >= GodRayGeometry.UNBOUNDED / 2f
    lamp.x = if (whole) box.center.x else band.center.x
    lamp.width = if (whole) box.width * 0.55f else band.width
    lamp.strength = (lampLevel ?: lightLevel)().coerceIn(0f, 1f)
    lamp.red = color.red
    lamp.green = color.green
    lamp.blue = color.blue
}
