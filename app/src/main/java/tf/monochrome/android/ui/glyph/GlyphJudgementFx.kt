// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphJudgement

/**
 * The judgement and the combo, as StepMania's default theme draws them, and
 * held still.
 *
 * The judgement is the theme's own sprite — FLAWLESS!!, PERFECT!, GREAT, GOOD,
 * BAD, MISS... — in its early or late frame (the dot sits on the side the step
 * landed). It appears at full size, stays (longer for a Flawless, as the theme
 * keeps W1 up longest) and fades. Nothing about it waves, tilts or swells: it
 * is read at a glance in the middle of the lanes, and a word that moves is a
 * word read late. The combo is Roboto Bold, the theme's "Combo Numbers",
 * coloured by the full combo still alive.
 */
@Composable
fun GlyphJudgementFx(
    judgement: GlyphJudgement?,
    early: Boolean,
    /** Wall-clock instant the judgement landed. */
    shownAtMs: Long,
    combo: Int,
    fullCombo: GlyphFullCombo?,
    fonts: GlyphStepFonts,
    modifier: Modifier = Modifier,
) {
    if (judgement == null) return
    val sprite = rememberStepArt(GlyphStepArt.judgement(judgement, early))

    // Shown at once, held, then faded: StepMania's sleep then fade, without
    // its zoom.
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(shownAtMs) {
        alpha.snapTo(1f)
        delay(if (judgement == GlyphJudgement.MARVELOUS) HOLD_W1_MILLIS else HOLD_MILLIS)
        alpha.animateTo(0f, tween(FADE_MILLIS, easing = LinearEasing))
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val name = stepJudgementName(judgement)
        if (sprite != null) {
            Image(
                bitmap = sprite,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(JUDGEMENT_HEIGHT)
                    .graphicsLayer { this.alpha = alpha.value },
            )
        } else {
            Text(
                text = name.uppercase(),
                style = fonts.screenTitle,
                color = stepJudgementColour(judgement),
                modifier = Modifier
                    .semantics { contentDescription = name }
                    .graphicsLayer { this.alpha = alpha.value },
            )
        }
        // StepMania shows the combo from two.
        if (combo >= SHOW_COMBO_AT) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = combo.toString(), style = fonts.combo, color = comboColour(fullCombo))
                Text(
                    text = " COMBO",
                    style = fonts.rowTitle,
                    color = comboColour(fullCombo).copy(alpha = 0.85f),
                    modifier = Modifier.graphicsLayer { translationY = -8f },
                )
            }
        }
    }
}

/** The combo in the colour of the full combo it still is, white once that is gone. */
fun comboColour(fullCombo: GlyphFullCombo?): Color = when (fullCombo) {
    GlyphFullCombo.FLAWLESS -> stepJudgementColour(GlyphJudgement.MARVELOUS)
    GlyphFullCombo.PERFECT -> stepJudgementColour(GlyphJudgement.PERFECT)
    GlyphFullCombo.GREAT -> stepJudgementColour(GlyphJudgement.GREAT)
    null -> Color.White
}

private const val SHOW_COMBO_AT = 2
private const val HOLD_MILLIS = 500L
private const val HOLD_W1_MILLIS = 800L
private const val FADE_MILLIS = 100
private val JUDGEMENT_HEIGHT = 56.dp
