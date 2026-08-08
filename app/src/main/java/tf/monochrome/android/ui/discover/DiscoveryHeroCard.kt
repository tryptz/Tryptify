package tf.monochrome.android.ui.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.PlayRadioButton
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The featured card at the top of Discover.
 *
 * A discovery page with no top is a page you have to start reading somewhere,
 * and most people won't — so one thing is picked out and given room. It plays
 * through the existing radio station rather than a bespoke queue, which means
 * it inherits the generating / active / stop states and the refill behaviour
 * for free.
 */
@Composable
fun DiscoveryHeroCard(
    hero: DiscoveryHero,
    isRadioActive: Boolean,
    isRadioGenerating: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .liquidGlass(shape = MonoDimens.shapeLg),
        shape = MonoDimens.shapeLg,
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.padding(MonoDimens.spacingLg)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(MonoDimens.shapeMd),
            ) {
                CoverImage(
                    url = hero.artworkUrl,
                    contentDescription = hero.title,
                    size = 180.dp,
                    cornerRadius = MonoDimens.radiusMd,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.height(MonoDimens.spacingMd))
            Text(
                text = hero.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = hero.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(MonoDimens.spacingSm))
            PlayRadioButton(
                isActive = isRadioActive,
                isGenerating = isRadioGenerating,
                onClick = onPlay,
                // The button insets itself from the screen edge on Home; inside
                // the card that inset is already there.
                horizontalPadding = 0.dp,
            )
        }
    }
}
