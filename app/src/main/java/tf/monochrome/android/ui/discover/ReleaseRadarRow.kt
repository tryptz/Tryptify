package tf.monochrome.android.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.CoverImage
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.swallowHorizontalScroll
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val RADAR_COVER = 148.dp

/**
 * Release radar: new releases from the artists the listener plays.
 *
 * Shaped like a shelf, because it is one, with the count of new releases in
 * the header — the number is the reason to look. Hidden entirely when there
 * is nothing, rather than a row saying so: an empty radar on most days would
 * teach people to scroll past it on the days it has something.
 */
@Composable
internal fun ReleaseRadarRow(
    items: List<RadarItem>,
    onOpen: (RadarItem) -> Unit,
    onShown: () -> Unit,
) {
    if (items.isEmpty()) return
    // Once it has been on screen, what it showed is seen — next visit's
    // badges are for what came out after.
    LaunchedEffect(items) { onShown() }
    val newCount = items.count { it.isNew }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.radar_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (newCount > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.radar_new_count, newCount),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.radar_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 2.dp, bottom = 10.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().swallowHorizontalScroll(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { "radar_" + it.release.albumId }) { item ->
                RadarCard(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun RadarCard(item: RadarItem, onClick: () -> Unit) {
    val release = item.release
    Column(
        modifier = Modifier
            .width(RADAR_COVER)
            .bounceClick(onClick = onClick),
    ) {
        Box {
            CoverImage(url = release.cover, contentDescription = release.title, size = RADAR_COVER, cornerRadius = 18.dp)
            if (item.isNew) {
                Text(
                    text = stringResource(R.string.radar_new_badge),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = release.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = release.artist + " · " + whenReleased(release.day),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Today", "Yesterday", "3 days ago", then the date itself past two weeks. */
@Composable
private fun whenReleased(day: Long): String {
    val today = LocalDate.now().toEpochDay()
    val ago = (today - day).toInt()
    return when {
        ago <= 0 -> stringResource(R.string.radar_today)
        ago == 1 -> stringResource(R.string.radar_yesterday)
        ago < 14 -> pluralStringResource(R.plurals.radar_days_ago, ago, ago)
        else -> LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}
