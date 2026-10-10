package tf.monochrome.android.ui.mixer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * One choice in a row of options, as the app's glass: a [GlassPill], the same
 * material as every panel and search bar — the Studio's UI panels settings,
 * the universal glass — with the solid slab the shader bevels and refracts,
 * frosting [hazeState] when the page provides one, and swelling under the
 * finger.
 *
 * It was `PressableGlass`, whose slab was a tenth of the tint: the shader had
 * almost nothing to bevel, and the pills came out as flat grey smudges that
 * looked like the low-performance fallback even where the real glass runs.
 *
 * Every chip in a row is the same shape — one height, one padding, one line of
 * centred text — and a selected chip differs only by its accent rim and label,
 * never by a different frame, so a row reads as a set. Callers that want equal
 * widths pass `Modifier.weight(1f)`.
 */
@Composable
internal fun GlassChoiceChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    description: String = label,
    /** A small icon before the label: a tick, a heart, a cross. */
    leadingIcon: ImageVector? = null,
    /** A small icon after it: the arrow for a chip that opens another screen. */
    trailingIcon: ImageVector? = null,
    /** Anything else before the label, such as a service's brand mark. */
    leadingContent: (@Composable () -> Unit)? = null,
    /** Anything else after it, such as a small button of its own. */
    trailingContent: (@Composable () -> Unit)? = null,
    /** Off greys the chip and ignores taps, keeping its place in the row. */
    enabled: Boolean = true,
    /** What the pill frosts: the page's backdrop, from [LocalGlassBackdrop] unless given. */
    hazeState: HazeState? = LocalGlassBackdrop.current,
) {
    val cs = MaterialTheme.colorScheme
    val labelColor = when {
        !enabled -> cs.onSurface.copy(alpha = 0.38f)
        selected -> accent
        else -> cs.onSurface.copy(alpha = 0.82f)
    }
    GlassPill(
        onClick = onClick,
        hazeState = hazeState,
        enabled = enabled,
        height = height,
        modifier = modifier
            .defaultMinSize(minWidth = 72.dp)
            .semantics {
                role = Role.Button
                this.selected = selected
            },
        onClickLabel = description,
    ) {
        // The selection rim, drawn over the glass rather than replacing it.
        // Only on the selected one: the shader draws every pill's own edge,
        // and a hairline outline on top of that is a second, flatter rim.
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(width = 1.5.dp, color = accent.copy(alpha = 0.85f), shape = MonoDimens.shapePill),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 14.dp),
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = labelColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            if (leadingContent != null) {
                leadingContent()
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(5.dp))
                Icon(trailingIcon, contentDescription = null, tint = labelColor, modifier = Modifier.size(14.dp))
            }
            if (trailingContent != null) {
                Spacer(Modifier.width(5.dp))
                trailingContent()
            }
        }
    }
}
