package tf.monochrome.android.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.glassTint
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * How hard a sheet of glass is being pressed, and where.
 *
 * Glass in this app already knows how to deform: the AGSL shader takes a bulge
 * centre and an amount, and the player's transport and the mini player's two
 * controls have always swelled under a finger. Nothing else did — every other
 * pane was a slab that either did nothing on press or scaled as a flat
 * rectangle, so the same material behaved like two different substances
 * depending on which screen you were on.
 *
 * This carries the press so both halves of the effect can read it: the shader
 * swells toward the finger, and the pane gives under it. Kept as one object
 * rather than two loose values because the centre is meaningless without the
 * amount — a bulge at full strength in the middle of a pane nobody touched is
 * worse than no bulge at all.
 */
@Stable
class GlassPress internal constructor(
    internal val interactions: MutableInteractionSource,
) {
    internal var boxSize by mutableStateOf(IntSize.Zero)
    internal var pressPoint by mutableStateOf<Offset?>(null)
    internal var held by mutableStateOf(false)

    /** The swell, 0 at rest and 1 held down. Animated, so it never jumps. */
    var amount by mutableFloatStateOf(0f)
        internal set

    /**
     * Where the finger is, as a fraction of the pane. The shader wants it in
     * these units, and the centre of the pane is the honest answer before
     * anything has been touched.
     */
    val center: Offset
        get() {
            val point = pressPoint
            val size = boxSize
            if (point == null || size.width == 0 || size.height == 0) {
                return Offset(0.5f, 0.5f)
            }
            return Offset(
                (point.x / size.width).coerceIn(0f, 1f),
                (point.y / size.height).coerceIn(0f, 1f),
            )
        }
}

object GlassPressDefaults {
    /**
     * How far a pressed pane gives.
     *
     * Bigger than the 0.95 the app's card `bounceClick` uses, and doing a
     * different job: that scales a whole card as a rigid rectangle, which on a
     * sheet of glass reads as a picture of glass on a button rather than the
     * glass itself moving. Here the scale is the *quiet* half — the dome is what
     * you actually see — so it only has to stop the pane feeling rigid.
     */
    const val SQUEEZE = 0.965f

    /**
     * Dome width, as a fraction of the pane's longest side.
     *
     * The shader's own default is a sixth of the width, tuned for the transport
     * and the action dock, where the point is to pick out one icon from a row of
     * them. A pane that is itself the button wants the swell across the whole of
     * it — at a sixth of a full-width bar the dome is a dimple.
     */
    const val BULGE = 0.42f
}

/**
 * A [GlassPress] wired to its own interaction source.
 *
 * Springs in and eases out, deliberately asymmetric: a press should feel like it
 * is being met, and a release like it is settling. The same shape the mini
 * player's controls have used since they were written, lifted here so every
 * other pane agrees with them.
 */
@Composable
fun rememberGlassPress(): GlassPress {
    val press = remember { GlassPress(MutableInteractionSource()) }

    LaunchedEffect(press) {
        press.interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    press.pressPoint = interaction.pressPosition
                    press.held = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> press.held = false
            }
        }
    }

    val instant = reduceMotion()
    val amount by animateFloatAsState(
        targetValue = if (press.held) 1f else 0f,
        animationSpec = when {
            instant -> snap()
            press.held -> spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)
            else -> tween(durationMillis = 260)
        },
        label = "glassPress",
    )
    SideEffect { press.amount = amount }
    return press
}

/**
 * Make a sheet of glass squeeze when it is pressed.
 *
 * Two things happen and they are meant to be read as one. The pane gives a
 * little under the finger — [squeeze] is a scale, small on purpose, because
 * glass that visibly shrinks reads as a button with a picture of glass on it —
 * and, on panes drawn with the shader, the material swells toward wherever the
 * finger landed. Screens that cannot run the shader still get the give, so the
 * press is never silent.
 *
 * The scale is read inside [graphicsLayer]'s lambda, which runs at draw time, so
 * holding a pane down redraws it without recomposing anything.
 *
 * No ripple: the deformation *is* the feedback, and a Material ripple on top of
 * it is a second answer to a question already answered — and a rectangular one,
 * over a pane whose corners are round.
 */
fun Modifier.glassSqueeze(
    press: GlassPress,
    enabled: Boolean = true,
    squeeze: Float = GlassPressDefaults.SQUEEZE,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val flat = reduceMotion()
    this
        .onSizeChanged { press.boxSize = it }
        .then(
            if (flat) {
                Modifier
            } else {
                Modifier.graphicsLayer {
                    val s = 1f - (1f - squeeze) * press.amount
                    scaleX = s
                    scaleY = s
                }
            },
        )
        .clickable(
            interactionSource = press.interactions,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * A sheet of glass that is itself a button.
 *
 * Two layers, because the app's glass is two things. [Modifier.liquidGlass] is
 * the tint, the frost and the rim — what the pane is made of — and it has no
 * idea a finger exists. The dome is the AGSL surface, which is the only part
 * that can deform, and it has to be drawn on a canvas of its own beneath the
 * content. Everything clickable and glassy in the app was getting the first
 * without the second, which is why only the transport and the mini player's two
 * carved-out controls ever swelled: they were the only things reaching for the
 * shader directly.
 *
 * The dome follows the finger rather than sitting in the middle, so pressing a
 * corner of a wide pill swells that corner. On devices where the shader will not
 * compile the give is still there — the pane just doesn't lens.
 */
@Composable
fun PressableGlass(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MonoDimens.shapePill,
    enabled: Boolean = true,
    hazeState: HazeState? = null,
    onClickLabel: String? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val press = rememberGlassPress()
    val tint = glassTint(LocalPlayerGlass.current.tintColor)

    Box(
        modifier = modifier
            .clip(shape)
            .liquidGlass(hazeState = hazeState, shape = shape)
            .glassSqueeze(
                press = press,
                enabled = enabled,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
        contentAlignment = contentAlignment,
    ) {
        // The deforming layer, under the content.
        //
        // A near-transparent wash rather than nothing: the shader builds its
        // dome from the gradient of what the canvas draws, so an empty canvas
        // has no surface to swell. Kept faint because the pane's colour is
        // already coming from liquidGlass above — this is here to be bent, not
        // to be seen.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .playerGlass(
                    tint = tint,
                    bulgeCenter = press.center,
                    bulgeAmount = { press.amount },
                    bulgeRadiusFraction = GlassPressDefaults.BULGE,
                ),
        ) {
            drawRect(color = tint.copy(alpha = 0.10f))
        }
        content()
    }
}
