package tf.monochrome.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The resting primary action on Home and at the top of Discover: seed a radio
 * station and let it run.
 *
 * On the glass path it is a pill of the shared `Modifier.liquidGlass` and the
 * label, and nothing else — no fill of its own, because a fill is the one thing
 * glass cannot be seen through. Its colour comes from the label rather than
 * from a slab behind it.
 *
 * It still needs a flat branch when glass is off, and there the accent has to
 * come back as a fill or the button would be text floating on the page. Both
 * branches keep the same shape, padding and three states, so nothing moves when
 * the setting changes; only the material does.
 */
@Composable
fun PlayRadioButton(
    isActive: Boolean,
    isGenerating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Inset from the screen edge on Home; zero inside the Discover hero card,
    // which is already padded.
    horizontalPadding: Dp = 16.dp,
) {
    val accent = if (isActive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.primary
    val glass = LocalPerformanceProfile.current.allowHazeBlur

    val outer = modifier
        .fillMaxWidth()
        .padding(horizontal = horizontalPadding, vertical = 8.dp)

    if (glass) {
        // The glass, and nothing else.
        //
        // Everything that used to be layered on top of it was in its way. An
        // accent wash under the glass is a container, and a container is the
        // one thing glass cannot be seen through. The specular sweep — a
        // vertical gradient fading to transparent partway down — drew a visible
        // seam straight across the pill where it ran out, which is the line
        // that appeared to go through the button. And the hand-rolled rim was a
        // second edge stacked on the one the shared material already draws.
        //
        // PressableGlass rather than a bare liquidGlass pill: this is a button,
        // and the app's glass buttons swell under a finger. It used to take a
        // plain `clickable` and do nothing at all on press, which on the same
        // material as the transport — where pressing raises a dome — read as two
        // different substances.
        PressableGlass(
            onClick = onClick,
            modifier = outer,
            shape = MonoDimens.shapePill,
        ) {
            PlayRadioLabel(glass, isActive, isGenerating)
        }
    } else {
        // Flat: one opaque accent fill, no shadow, no gradients, no rim.
        Row(
            modifier = outer
                .clip(MonoDimens.shapePill)
                .background(accent)
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayRadioLabel(glass, isActive, isGenerating)
        }
    }
}

/** Icon and label, identical on both paths so nothing moves when glass is off. */
@Composable
private fun PlayRadioLabel(glass: Boolean, isActive: Boolean, isGenerating: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // With no fill behind it, the label is what carries the accent — and it
        // is sitting on the page, so it needs a colour picked to be read there.
        // That is `primary`, which the light schemes hold to 4.5:1 on the
        // surface for exactly this kind of use. onPrimary would be wrong twice
        // over: it is chosen to contrast with a *solid* primary, and against
        // the page it inverts — black text on a dark theme.
        val contentColor = when {
            glass -> MaterialTheme.colorScheme.primary
            isActive -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onPrimary
        }
        if (isGenerating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
        } else {
            Icon(
                if (isActive) Icons.Default.GraphicEq else Icons.Default.Podcasts,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = when {
                isGenerating -> "Finding similar tracks…"
                isActive -> "Radio on — tap to stop"
                else -> "Play Radio"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
