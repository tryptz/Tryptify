package tf.monochrome.android.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.PressSpring
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

    // The swell is animated straight into press.amount, every frame, by the
    // animation itself. It used to be an animateFloatAsState copied across in
    // a SideEffect, and that copy only ran when this composable recomposed —
    // which the animation never made it do, since its value was read only
    // inside the SideEffect. So press.amount froze at whatever it was when the
    // finger lifted, and the pane stayed swollen. A faint slab hid it; the
    // solid glass slab shows it plainly.
    val instant = reduceMotion()
    LaunchedEffect(press, instant) {
        snapshotFlow { press.held }.collectLatest { held ->
            val target = if (held) 1f else 0f
            if (instant) {
                press.amount = target
            } else {
                // The dock's spring, so the mini player's slab answers a press
                // exactly the way the player's does.
                animate(press.amount, target, animationSpec = PressSpring) { value, _ -> press.amount = value }
            }
        }
    }
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
 * A sheet of glass that is itself a button: the app's real glass, from the
 * same recipe as [GlassPanel] ([GlassMaterial]) — the live lens or the haze
 * frost of [hazeState], and a *solid* slab the AGSL shader turns into a
 * bevelled, refracting pane with a lens rim as wide as [corner] — that swells
 * where the finger lands and gives a little under it.
 *
 * It used to lay its slab down at a tenth of the tint as insurance against a
 * device where the shader no-ops. The shader builds its bevel from that slab's
 * alpha, so every button that *did* get the shader came out a flat grey smudge
 * with no edge — the low-performance fallback's look, everywhere, on devices
 * that run the real glass. Asking whether the shader will run replaced the
 * guess (see [GlassMaterial]).
 *
 * [glass] is the whole material and defaults to the Studio's UI panels
 * settings, the universal glass every pane, pill and search bar takes. The
 * player's own controls pass the player's material instead. [corner] is the
 * corner of [shape]; [Dp.Infinity], the default, is right for a pill or a
 * disc. On devices without the shader the give is still there and the pane is
 * the plain glass of [glassBase].
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
    corner: Dp = Dp.Infinity,
    glass: PlayerGlassSettings = LocalMiniPlayerGlass.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val press = rememberGlassPress()
    Box(
        modifier = modifier
            .glassSqueeze(
                press = press,
                enabled = enabled,
                onClickLabel = onClickLabel,
                onClick = onClick,
            )
            .clip(shape)
            .glassBase(hazeState, glass, shape),
        contentAlignment = contentAlignment,
    ) {
        GlassMaterial(hazeState = hazeState, glass = glass, corner = corner, press = press)
        content()
    }
}
