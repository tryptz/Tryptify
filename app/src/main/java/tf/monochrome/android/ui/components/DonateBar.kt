package tf.monochrome.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
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
 * Both destinations are offered here rather than one, and neither is styled as
 * the recommendation: one is a one-off and the other is monthly, and which of
 * those suits a listener is not something the app knows. The title is no longer
 * a tap target now that there are two — a banner where the text and the buttons
 * go to different places is a banner that will be tapped wrong.
 *
 * [onDismiss] carries whether the box was ticked.
 */
@Composable
fun DonateBar(
    onKofi: () -> Unit,
    onPatreon: () -> Unit,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enjoying Tryptify?",
                        style = MaterialTheme.typography.bodyLarge,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "A tip keeps it going",
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
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DonateButton(
                    label = "Ko-fi",
                    logo = R.drawable.logo_kofi,
                    content = content,
                    onClick = onKofi,
                    modifier = Modifier.weight(1f),
                )
                DonateButton(
                    label = "Patreon",
                    logo = R.drawable.logo_patreon,
                    content = content,
                    onClick = onPatreon,
                    modifier = Modifier.weight(1f),
                )
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

/**
 * One destination on the tip bar: its mark, then its name.
 *
 * Takes [content] rather than reading the theme, because the bar's own text
 * colour depends on whether the glass drew — see [DonateBar] — and a button
 * that picked its own would go invisible on the LOW-tier fallback.
 */
@Composable
private fun DonateButton(
    label: String,
    @DrawableRes logo: Int,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, content.copy(alpha = 0.35f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = content),
    ) {
        Icon(
            painter = painterResource(id = logo),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}
