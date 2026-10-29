// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphJudgement

/**
 * The judgement and the combo, as StepMania's default theme draws them: the
 * judgement held still, the combo popping as it grows and falling as it breaks.
 *
 * The judgement is the theme's own sprite — FLAWLESS!!, PERFECT!, GREAT, GOOD,
 * BAD, MISS... — in its early or late frame (the dot sits on the side the step
 * landed). It appears at full size, stays (longer for a Flawless, as the theme
 * keeps W1 up longest) and fades. Nothing about it waves, tilts or swells: it
 * is read at a glance in the middle of the lanes, and a word that moves is a
 * word read late. The combo is Roboto Bold, the theme's "Combo Numbers",
 * coloured by the full combo still alive. It springs up a size on each step
 * it gains (more on every fiftieth), and a broken combo drops and tips away in
 * red. Reduced motion keeps both still.
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
    /** Still: no pop, no fall. */
    reducedMotion: Boolean = false,
) {
    if (judgement == null) return
    val sprite = rememberStepArt(GlyphStepArt.judgement(judgement, early))
    // An imported theme's own combo digits and label, when it has them.
    val digits = rememberStepComboDigits()
    val comboLabel = rememberStepThemeArt(tf.monochrome.android.glyph.style.StepManiaStyleBaker.COMBO_LABEL)

    // The combo pops on every step it gains, harder at every fifty, and a
    // broken combo drops away in red rather than blinking out.
    val pop = remember { Animatable(1f) }
    val fall = remember { Animatable(1f) }
    var broken by remember { mutableIntStateOf(0) }
    var last by remember { mutableIntStateOf(combo) }
    LaunchedEffect(combo) {
        val before = last
        last = combo
        if (reducedMotion) return@LaunchedEffect
        if (combo > before && combo >= SHOW_COMBO_AT) {
            pop.snapTo(if (combo % COMBO_MILESTONE == 0) MILESTONE_POP else STEP_POP)
            pop.animateTo(1f, spring(dampingRatio = POP_DAMPING, stiffness = POP_STIFFNESS))
        } else if (combo < before && before >= SHOW_COMBO_AT) {
            broken = before
            fall.snapTo(0f)
            fall.animateTo(1f, tween(BREAK_MILLIS, easing = FastOutSlowInEasing))
        }
    }

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
            Row(
                verticalAlignment = Alignment.Bottom,
                // Read in the layer: the pop redraws, it never recomposes.
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    transformOrigin = TransformOrigin(0.5f, 0.8f)
                },
            ) {
                ComboLine(combo, comboColour(fullCombo), fonts, digits, comboLabel)
            }
        } else if (broken >= SHOW_COMBO_AT && fall.isRunning) {
            // The combo that was, falling and fading.
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.graphicsLayer {
                    val f = fall.value
                    translationY = f * BREAK_DROP.toPx()
                    rotationZ = f * BREAK_TILT_DEGREES
                    this.alpha = 1f - f
                },
            ) {
                ComboLine(broken, BROKEN, fonts, digits, comboLabel)
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

/** The combo's pop on a step, and on every [COMBO_MILESTONE]th, as a scale it springs back from. */
private const val STEP_POP = 1.22f
private const val MILESTONE_POP = 1.55f
private const val COMBO_MILESTONE = 50
private const val POP_DAMPING = 0.45f
private const val POP_STIFFNESS = 700f

/** A broken combo's fall: how long, how far, how far it tips, and its red. */
private const val BREAK_MILLIS = 450
private val BREAK_DROP = 28.dp
private const val BREAK_TILT_DEGREES = 7f
private val BROKEN = Color(0xFFFF5F6D)
private const val HOLD_MILLIS = 500L
private const val HOLD_W1_MILLIS = 800L
private const val FADE_MILLIS = 100
private val JUDGEMENT_HEIGHT = 56.dp

/**
 * The combo and its word: the theme's own digits and label when an imported
 * theme has them (drawn in their own colours, as StepMania draws a bitmap
 * font), else the number in the theme's typeface and " COMBO".
 */
@Composable
private fun ComboLine(
    value: Int,
    colour: Color,
    fonts: GlyphStepFonts,
    digits: StepComboDigits?,
    label: androidx.compose.ui.graphics.ImageBitmap?,
) {
    val text = value.toString()
    if (digits != null && text.all { it in digits.cells }) {
        val height = with(androidx.compose.ui.platform.LocalDensity.current) { fonts.combo.fontSize.toDp() }
        val width = height * (digits.cellWidth.toFloat() / digits.cellHeight.coerceAtLeast(1)) * text.length
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(width = width, height = height)
                .semantics { contentDescription = "$value combo" },
        ) {
            val cellW = size.width / text.length
            text.forEachIndexed { i, ch ->
                val cell = digits.cells[ch] ?: return@forEachIndexed
                drawImage(
                    image = digits.page,
                    srcOffset = androidx.compose.ui.unit.IntOffset((cell % digits.cols) * digits.cellWidth, (cell / digits.cols) * digits.cellHeight),
                    srcSize = androidx.compose.ui.unit.IntSize(digits.cellWidth, digits.cellHeight),
                    dstOffset = androidx.compose.ui.unit.IntOffset((i * cellW).toInt(), 0),
                    dstSize = androidx.compose.ui.unit.IntSize(cellW.toInt(), size.height.toInt()),
                )
            }
        }
    } else {
        Text(text = text, style = fonts.combo, color = colour)
    }
    if (label != null) {
        Image(
            bitmap = label,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(COMBO_LABEL_HEIGHT).graphicsLayer { translationY = -8f },
        )
    } else {
        Text(
            text = " COMBO",
            style = fonts.rowTitle,
            color = colour.copy(alpha = 0.85f),
            modifier = Modifier.graphicsLayer { translationY = -8f },
        )
    }
}

private val COMBO_LABEL_HEIGHT = 22.dp
