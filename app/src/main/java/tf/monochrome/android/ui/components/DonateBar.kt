package tf.monochrome.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * When to offer the tip bar.
 *
 * Kept apart from the composable so the rule is testable, the same way
 * `WhatsNew.shouldNotify` is: the failure that matters is a bar that either
 * never leaves or never arrives, and neither shows up in a screenshot.
 */
object DonatePrompt {

    /**
     * Songs between offers.
     *
     * Long enough that it reads as occasional rather than as nagging, short
     * enough to reach someone who actually uses the app. It counts plays, not
     * days: somebody who opens the app twice a year should be asked on their
     * twentieth song, not on a calendar the app cannot see.
     */
    const val EVERY_N_SONGS = 20

    fun shouldShow(playsSincePrompt: Int, neverShow: Boolean): Boolean =
        !neverShow && playsSincePrompt >= EVERY_N_SONGS
}

/**
 * The tip bar: the same head-of-page notice shape as [WhatsNewBar], in glass.
 *
 * A bar and not a dialog, for the reason [WhatsNewBar] is one — asking for
 * money is worth offering and never worth blocking someone who opened the app
 * to play a song. The × puts it away until another [DonatePrompt.EVERY_N_SONGS]
 * have played; the checkbox, if ticked first, puts it away for good. Ticking
 * alone does nothing until dismissed, so the box can be unticked again and
 * there is exactly one action that closes the bar.
 *
 * [onDismiss] carries whether the box was ticked.
 */
@Composable
fun DonateBar(
    onTip: () -> Unit,
    onDismiss: (neverAgain: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var neverAgain by remember { mutableStateOf(false) }

    // `liquidGlass` returns the modifier untouched on LOW-tier devices — no
    // blur, no tint, no rim — which for a list row is right and for a bar is
    // not: a notice with no surface under it is text loose on the page. The
    // solid container is what it falls back to there, and it is transparent
    // wherever the glass will actually draw.
    val glassy = LocalPerformanceProfile.current.allowHazeBlur
    val content = if (glassy) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs)
            // No hazeState on purpose. This sits inside the app's one
            // hazeSource, and a haze child within its own source is the cycle
            // that crashes at draw time — the same rule every row follows.
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = if (glassy) Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = content,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onTip),
                ) {
                    Text(
                        text = "Enjoying Tryptify?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Tip on Ko-fi to keep it going",
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onDismiss(neverAgain) }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = if (neverAgain) "Dismiss for good" else "Dismiss",
                        tint = content,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The whole row toggles, not just the box: a 20dp target on
                    // a bar people want gone is a tap they will miss.
                    .clickable { neverAgain = !neverAgain }
                    .padding(end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = neverAgain,
                    // Null so the row above owns the click; the box would
                    // otherwise take its own and the two would disagree about
                    // the state on a tap that lands on the box itself.
                    onCheckedChange = null,
                    modifier = Modifier.size(20.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = content,
                        uncheckedColor = content.copy(alpha = 0.6f),
                        checkmarkColor = if (glassy) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    ),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Don't ask again",
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.9f),
                )
            }
        }
    }
}
