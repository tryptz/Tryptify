package tf.monochrome.android.ui.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                bins = bins,
                settings = spectrogram3d,
                color = color,
                glow = glow,
                isPlaying = isPlaying,
                modifier = bottom.height(maxHeight * 0.4f),
                resetKey = resetKey,
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
