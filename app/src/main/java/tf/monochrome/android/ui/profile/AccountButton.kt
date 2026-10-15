package tf.monochrome.android.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import tf.monochrome.android.R
import tf.monochrome.android.data.auth.AccountAvatar
import tf.monochrome.android.data.auth.AvatarSource
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.PressableGlass
import java.io.File

/**
 * The account's picture in a circle: the picture when there is one, the
 * initials on the accent under it otherwise — and while it loads, or if it
 * fails to, since the picture is drawn over them.
 */
@Composable
fun ProfileAvatar(
    avatar: AccountAvatar?,
    modifier: Modifier = Modifier,
    initialsStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(accent),
        contentAlignment = Alignment.Center,
    ) {
        val initials = avatar?.initials.orEmpty()
        if (initials.isNotEmpty()) {
            Text(initials, style = initialsStyle, fontWeight = FontWeight.Bold, color = onAccent, maxLines = 1)
        } else {
            Icon(Icons.Default.Person, contentDescription = null, tint = onAccent, modifier = Modifier.fillMaxSize(0.62f))
        }
        val model: Any? = remember(avatar?.source) {
            when (val source = avatar?.source) {
                is AvatarSource.Custom -> File(source.path)
                is AvatarSource.Remote -> source.url
                null -> null
            }
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * The top bar's way into the account page, in glass. Signed in, a glass disc
 * holding the account's picture; signed out, a "Sign in" pill, so the way to
 * sign in says what it is rather than being a grey silhouette.
 *
 * It frosts [LocalGlassBackdrop] like every pill. Home provides none, which
 * leaves it the shader glass with no frost under it — still glass.
 */
@Composable
fun AccountButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    viewModel: AccountBadgeViewModel = hiltViewModel(),
) {
    val account by viewModel.account.collectAsStateWithLifecycle()
    val backdrop = LocalGlassBackdrop.current
    val signedIn = account
    if (signedIn != null) {
        val label = stringResource(R.string.account)
        PressableGlass(
            onClick = onClick,
            modifier = modifier
                .padding(horizontal = 4.dp)
                .size(size)
                .semantics { contentDescription = label },
            shape = CircleShape,
            hazeState = backdrop,
            onClickLabel = label,
        ) {
            // A ring of the glass shows round the picture.
            ProfileAvatar(
                avatar = signedIn,
                modifier = Modifier.size(size - 8.dp),
                initialsStyle = MaterialTheme.typography.labelLarge,
            )
        }
    } else {
        GlassPill(
            onClick = onClick,
            hazeState = backdrop,
            modifier = modifier.padding(horizontal = 4.dp),
            height = 36.dp,
            onClickLabel = stringResource(R.string.sign_in),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
