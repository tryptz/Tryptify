// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.ui.player.Letters3DRow
import tf.monochrome.android.ui.player.LocalLyricsFx
import tf.monochrome.android.ui.player.LocalPlayerBackdrop
import tf.monochrome.android.ui.player.PlayerBackdrop
import tf.monochrome.android.ui.player.liquidGlass
import tf.monochrome.android.ui.player.rememberFrameSeconds

/**
 * Judgement and combo, rendered through the lyrics FX pipeline.
 *
 * The same per-letter 3D path and glass relight the lyrics use, reused rather
 * than reimplemented: [Letters3DRow] rides each letter on a phase-locked wave
 * with a baked extrusion, and `liquidGlass` lenses the playfield through the
 * letterforms. Feedback is the one place in the mode where that treatment
 * belongs — it is transient, centred, and nothing is read *through* it, unlike
 * the lanes, which stay deliberately flat.
 *
 * One thing is deliberately not reused. The lyrics pipeline infers beats from
 * the audio with an analyzer, and gameplay does not have to guess: a judgement
 * *is* the impulse, exactly on time and already in hand. So the reactive half
 * of the FX settings is gated off ([LyricsFxSettings.bassReact] at 0, which
 * switches off pump, pop and bloom) and the swell is driven from the judgement
 * event instead. That keeps the spectrum analyzer off during play, where it
 * would be a second consumer of the audio for no gain.
 */
@Composable
fun GlyphJudgementFx(
    judgement: GlyphJudgement?,
    /** Wall-clock instant the judgement landed, for the swell. */
    shownAtMs: Long,
    combo: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    if (judgement == null) return

    val fx = remember { glyphJudgementFx() }
    // The playfield's own ink, so the shader lenses the surface actually behind
    // the letters rather than the album tones it gets in the player.
    val backdrop = remember {
        PlayerBackdrop(
            blurredArt = false,
            dominant = GlyphTheme.InkPanel,
            secondary = GlyphTheme.InkRaised,
        )
    }

    val colour = judgementColour(judgement)
    val family = rememberStepTechFontFamily()

    // Swell driven by the judgement, not by an analyzer. A spring rather than a
    // curve so a second hit landing mid-animation retargets from wherever the
    // first one had got to, instead of snapping back to the start.
    val swell by animateFloatAsState(
        targetValue = if (shownAtMs > 0L) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 900f),
        label = "glyph-judgement-swell",
    )

    CompositionLocalProvider(
        LocalLyricsFx provides fx,
        LocalPlayerBackdrop provides backdrop,
    ) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            JudgementWord(
                text = judgement.label.uppercase(),
                colour = colour,
                family = family,
                reducedMotion = reducedMotion,
                swell = swell,
            )
            if (combo > 1) {
                JudgementWord(
                    text = combo.toString(),
                    colour = GlyphTheme.Paper,
                    family = family,
                    reducedMotion = reducedMotion,
                    swell = swell,
                    sizeSp = 22f,
                )
            }
        }
    }
}

@Composable
private fun JudgementWord(
    text: String,
    colour: Color,
    family: androidx.compose.ui.text.font.FontFamily,
    reducedMotion: Boolean,
    swell: Float,
    sizeSp: Float = 34f,
) {
    val style = TextStyle(
        fontFamily = family,
        fontWeight = FontWeight.Bold,
        fontSize = sizeSp.sp,
        letterSpacing = 2.sp,
    )

    // Reduced motion takes the flat path: no wave, no glass, no frame clock.
    // The wordmark still says what it says, which is the part that matters.
    if (reducedMotion) {
        Text(text = text, style = style, color = colour)
        return
    }

    val time = rememberFrameSeconds()
    Letters3DRow(
        text = text,
        style = style,
        color = colour,
        time = time,
        modifier = Modifier
            .graphicsLayer {
                // Overshoot on arrival, settling to rest. Applied to the row
                // rather than per letter so the wave keeps its own phase.
                val scale = 0.86f + 0.14f * swell
                scaleX = scale
                scaleY = scale
                alpha = swell.coerceIn(0f, 1f)
            }
            .liquidGlass(tint = colour)
            .padding(horizontal = 6.dp),
    )
}

private fun judgementColour(judgement: GlyphJudgement): Color = when (judgement) {
    GlyphJudgement.MARVELOUS -> Color(0xFF63F2A2)
    GlyphJudgement.PERFECT -> Color(0xFF52E6D8)
    GlyphJudgement.GREAT -> Color(0xFF58D9FF)
    GlyphJudgement.GOOD -> Color(0xFFFFD95A)
    GlyphJudgement.BOO -> Color(0xFFFF9659)
    GlyphJudgement.MISS -> Color(0xFFFF5F6D)
}

/**
 * The FX settings the judgement is drawn with.
 *
 * Not a Studio preset: those are chips a listener picks for the now-playing
 * screen, and this is a fixed internal configuration for a different surface.
 * It is still run through `clamped()` so it cannot drift out of the ranges the
 * renderer assumes, the same guarantee the preset test gives the shipped chips.
 *
 * Shaped for a word that appears for a third of a second rather than a lyric
 * line that lives for several: a faster wave, a wider per-letter phase step so
 * the ripple is legible across six letters, and a short travel so it punches
 * rather than drifts. The body stays fairly opaque — this sits over moving
 * arrows and has to stay readable — while the rim and dispersion carry the
 * arcade glint.
 */
internal fun glyphJudgementFx(): LyricsFxSettings = LyricsFxSettings(
    liquidGlass = true,
    glassBodyOpacity = 0.82f,
    glassRefraction = 0.2f,
    glassRimBrightness = 1.55f,
    glassDispersion = 1.3f,
    rotationDegrees = 14f,
    waveSpeed = 1.8f,
    wavePhaseStep = 0.34f,
    waveTravelDp = 1.6f,
    shadowDepth = 0.88f,
    // Gate the reactive half off: the judgement event is the impulse, so the
    // analyzer would be a second consumer of the audio for nothing.
    bassReact = 0f,
).clamped()
