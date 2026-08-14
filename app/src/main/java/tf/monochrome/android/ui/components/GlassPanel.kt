package tf.monochrome.android.ui.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.performance.LocalLowPerformance
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.playerFrostTint
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.player.rememberLiquidGlassAvailable
import tf.monochrome.android.ui.theme.glassTint
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The floating panel that maps put over their canvas.
 *
 * Extracted so the genre map and the world radio globe are the same sheet of
 * glass rather than two that drift apart. There is more subtlety in here than
 * its size suggests, and every bit of it was learned the hard way:
 *
 * * **Three tiers, and the last one is real.** Shader glass on API 33+ when the
 *   listener has it on, the app's plain glassmorphism where haze is allowed, and
 *   an opaque surface otherwise. That last branch cannot be a no-op modifier —
 *   it is the only thing standing between the panel and having no background at
 *   all on a low-tier device.
 * * **Frost first.** The slab goes down to 0.2 opacity, and without a haze layer
 *   beneath it the canvas reads straight through and fights the panel's text.
 * * **A round shader canvas, not a rect.** The glass shader builds its bevel and
 *   rim from the gradient of what the canvas draws. A full-bleed rect gives it
 *   straight edges only, the parent clip then cuts the corners away, and each
 *   corner is left with no rim — four visible gaps in the outline.
 * * **Nothing gets through.** The canvas underneath is one big tap target that
 *   selects whatever is nearest, so without swallowing what the panel's own
 *   children didn't want, a tap on the panel's background reaches down and
 *   selects something else, throwing away the panel you were reading. The
 *   backstop is the panel's *bottom sibling*, not a modifier on the panel
 *   itself: as an ancestor it swallowed drags before the content's scroll could
 *   claim them, and gestures that take several events to declare themselves —
 *   which is every scroll — were lost two times in three.
 *
 * The [glass] parameter is the whole material: the frost above reads it, and it
 * is published as [LocalPlayerGlass] for the shader below, which takes its
 * bevel, refraction and rim from that local. Callers used to have to provide
 * the local themselves and a detail route that forgot got a panel frosted from
 * one set of settings and relit from another.
 */
@Composable
fun GlassPanel(
    /**
     * The backdrop to frost. Null when the caller has none — the panel then
     * takes the plain translucent glass instead of asking haze to blur a
     * backdrop that was never fed, which paints its base colour and reads as a
     * solid container.
     */
    hazeState: HazeState?,
    glass: PlayerGlassSettings,
    modifier: Modifier = Modifier,
    /**
     * Whether to hold clear of the navigation bar. True for the panels this was
     * written for, which sit against the bottom of the screen; false for one
     * anchored at the top, where the inset is a gap under the panel holding
     * space for a bar that is nowhere near it.
     */
    avoidNavigationBar: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val allowHaze = LocalPerformanceProfile.current.allowHazeBlur
    val flat = LocalLowPerformance.current.disableLiquidGlass
    val shaderGlass = !flat && glass.enabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val tint = glassTint(glass.tintColor)
    val frostBg = MaterialTheme.colorScheme.background
    val isDark = frostBg.luminance() <= 0.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .then(if (avoidNavigationBar) Modifier.navigationBarsPadding() else Modifier)
            .clip(MonoDimens.shapeLg)
            .then(
                when {
                    shaderGlass -> Modifier
                    allowHaze && !flat ->
                        Modifier.liquidGlass(hazeState = hazeState, shape = MonoDimens.shapeLg)
                    // Last resort: no shader, no blur. It still must not be a
                    // fully opaque slab when the listener has asked for
                    // see-through glass, so body opacity governs this path too
                    // — floored so text stays readable over raw artwork.
                    else -> Modifier.background(
                        MaterialTheme.colorScheme.surfaceContainerHigh
                            .copy(alpha = glass.bodyOpacity.coerceIn(0.55f, 1f)),
                    )
                },
            ),
    ) {
        // The backstop for taps the panel's own children didn't want, and the
        // lowest layer on purpose.
        //
        // It used to live on the Box above, as an ancestor of everything. An
        // ancestor cannot swallow "what the children didn't want", because it
        // only learns what they wanted one event at a time: a tap resolves
        // within a single event and survived, but a *drag* has to cross touch
        // slop over several, and the ancestor consumed each one before the
        // scroll had accumulated enough of them to claim the gesture. The
        // station list needed two or three attempts before one got through.
        //
        // As the bottom sibling it is only reached where nothing above it
        // handles the touch, which is exactly the case it was written for — and
        // being a hit at all is what keeps the event inside this panel, so the
        // full-bleed map underneath never sees it.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )

        if (shaderGlass) {
            // Only with a real backdrop. Frosting an unfed state draws the
            // frost's own base colour as a flat pane, which is the slab this
            // whole component exists not to be.
            if (hazeState != null && allowHaze && glass.hazeBlurDp > 0f) {
                // The mini player's exact frost, from the one shared recipe —
                // this panel is the same material as that bar and is usually on
                // screen beside it.
                val frostTint = playerFrostTint(glass, isDark)
                Box(
                    Modifier
                        .matchParentSize()
                        .hazeEffect(
                            state = hazeState,
                            style = HazeStyle(
                                backgroundColor = frostBg,
                                blurRadius = glass.hazeBlurDp.dp,
                                tints = listOf(HazeTint(frostTint)),
                                noiseFactor = 0f,
                            ),
                        ),
                )
            }
            // The shader reads its bevel, refraction, rim and body opacity from
            // LocalPlayerGlass, so the settings this panel was *handed* have to
            // be published for it or half of them are quietly ignored — the
            // panel would frost with one material and relight with another.
            CompositionLocalProvider(LocalPlayerGlass provides glass) {
            // Solid when the shader is really coming, faint when it is not.
            //
            // The mini player draws this slab at full opacity and lets the AGSL
            // turn it into glass, and that is the whole reason it looks like
            // glass: the shader builds its bevel and rim from the alpha
            // heightfield underneath it. This panel used to draw at a tenth of
            // that as insurance — on a device where the shader silently no-ops,
            // a solid fill is left on screen as an opaque rounded rectangle in
            // the accent colour. The insurance worked and the cost was that
            // every panel that *did* have the shader had nearly no heightfield
            // to bevel, so it came out a soft smudge with no edge while the bar
            // beside it was a crisp pane.
            //
            // Asking whether the shader will run replaces the guess, so the good
            // case gets the mini player's fill and the bad case still cannot
            // paint a slab.
            val shaded = rememberLiquidGlassAvailable()
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .playerGlass(tint = tint),
            ) {
                val r = MonoDimens.radiusLg.toPx()
                drawRoundRect(
                    color = if (shaded) tint else tint.copy(alpha = 0.14f),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            }
        }
        content()
    }
}

/** One action in a panel's button row — icon, then label, centred. */
@Composable
fun PanelActionPill(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.bounceClick(onClick = onClick),
        shape = MonoDimens.shapePill,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
