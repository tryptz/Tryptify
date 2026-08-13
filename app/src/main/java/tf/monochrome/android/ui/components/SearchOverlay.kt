package tf.monochrome.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The search icon that belongs in a screen's app bar.
 *
 * Paired with [SearchOverlay]: this opens it, that is it. In the bar rather
 * than in the content because a screen's actions live in its bar — a search
 * button sitting in the list was a button that scrolled away from the list it
 * searched.
 */
@Composable
fun SearchAction(open: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            Icons.Default.Search,
            contentDescription = if (open) "Close search" else "Search",
            tint = if (open) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/**
 * A search bar that floats over a screen's content and frosts it.
 *
 * The bar is pinned and the content runs *underneath* it, which is the only
 * arrangement in which frosting means anything: a bar laid out above its
 * content has the page's background behind it and nothing to blur, which is how
 * a sheet of glass ends up looking like a grey slab.
 *
 * Content is handed the room the bar is taking so it can pad its own scroll by
 * it. Rows then pass behind the glass while it is open — the point of the glass
 * — without the first of them being parked under it permanently. Zero while the
 * bar is closed, so a screen never reserves space for a bar that is not there.
 */
@Composable
fun SearchOverlay(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (topInset: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var barHeightPx by remember { mutableIntStateOf(0) }
    val inset = if (open) with(density) { barHeightPx.toDp() } else 0.dp

    Box(modifier = modifier.fillMaxSize()) {
        content(inset)

        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { barHeightPx = it.height },
        ) {
            GlassSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = placeholder,
                autoFocus = true,
                onClose = onClose,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
