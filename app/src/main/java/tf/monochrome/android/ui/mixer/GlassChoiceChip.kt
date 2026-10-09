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
import tf.monochrome.android.ui.components.PressableGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * One choice in a row of options, as the app's glass: [PressableGlass], so it
 * has the frost and rim of every other pane and swells under the finger.
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
    /** Off greys the chip and ignores taps, keeping its place in the row. */
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val labelColor = when {
        !enabled -> cs.onSurface.copy(alpha = 0.38f)
        selected -> accent
        else -> cs.onSurface.copy(alpha = 0.82f)
    }
    PressableGlass(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(height)
            .defaultMinSize(minWidth = 72.dp)
            .semantics {
                role = Role.Button
                this.selected = selected
            },
        shape = MonoDimens.shapePill,
        onClickLabel = description,
    ) {
        // The selection rim, drawn over the glass rather than replacing it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) accent.copy(alpha = 0.85f) else cs.outline.copy(alpha = 0.18f),
                    shape = MonoDimens.shapePill,
                )
        )
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
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
