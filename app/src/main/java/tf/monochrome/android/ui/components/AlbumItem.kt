package tf.monochrome.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.ui.theme.MonoDimens

@Composable
fun AlbumItem(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(MonoDimens.coverCard)
            .bounceClick(onClick = onClick)
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.padding(MonoDimens.spacingMd)
        ) {
        CoverImage(
            url = album.coverUrl,
            contentDescription = album.title,
            // Subtract the card's own padding so the cover stays square inside
            // the padded column instead of being requested at the full card
            // width and cropped to a portrait 136x160.
            size = MonoDimens.coverCard - MonoDimens.spacingMd * 2,
            cornerRadius = MonoDimens.radiusSm
        )
        Spacer(modifier = Modifier.height(MonoDimens.spacingSm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (album.isThxSpatialAudio) {
                ThxBadgePill()
                Spacer(modifier = Modifier.width(MonoDimens.spacingXs))
            }
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = album.displayArtist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
}
