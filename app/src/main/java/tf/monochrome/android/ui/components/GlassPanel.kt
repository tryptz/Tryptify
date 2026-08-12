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
import tf.monochrome.android.ui.player.playerGlass
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
 *   selects something else, throwing away the panel you were reading. Consumed
 *   in the main pass, *after* the buttons and any scroll have had the event.
 *
 * Callers must provide [LocalPlayerGlass] around this — detail routes sit
 * outside the nav host's own provider, and the shader modifier reads its
 * parameters from that local.
 */
@Composable
fun GlassPanel(
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val allowHaze = LocalPerformanceProfile.current.allowHazeBlur
    val flat = LocalLowPerformance.current.disableLiquidGlass
    val shaderGlass = !flat && glass.enabled &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val tint = if (glass.tintColor != 0) Color(glass.tintColor) else MaterialTheme.colorScheme.primary
    val frostBg = MaterialTheme.colorScheme.background
    val isDark = frostBg.luminance() <= 0.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .navigationBarsPadding()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .clip(MonoDimens.shapeLg)
            .then(
                when {
                    shaderGlass -> Modifier
                    allowHaze && !flat ->
                        Modifier.liquidGlass(hazeState = hazeState, shape = MonoDimens.shapeLg)
                    else -> Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                },
            ),
    ) {
        if (shaderGlass) {
            if (allowHaze && glass.hazeBlurDp > 0f) {
                val frostTint = (
                    if (isDark) Color.Black.copy(alpha = 0.32f)
                    else Color.White.copy(alpha = 0.45f)
                    ).let { it.copy(alpha = (it.alpha * glass.hazeTint).coerceIn(0f, 1f)) }
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
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .playerGlass(tint = tint),
            ) {
                val r = MonoDimens.radiusLg.toPx()
                drawRoundRect(color = tint, cornerRadius = CornerRadius(r, r))
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
