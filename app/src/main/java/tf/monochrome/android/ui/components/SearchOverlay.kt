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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

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
    /**
     * The screen's content. Handed zero — it runs under the bar rather than
     * below it, which is what gives the glass something to frost. The parameter
     * stays so a caller that genuinely cannot scroll under can be given room
     * later without changing every call site.
     */
    content: @Composable (topInset: Dp) -> Unit,
) {
    // No top inset, deliberately.
    //
    // Padding the list down by the bar's height put the rows *below* the glass,
    // which left it frosting an empty background — a blur of nothing is a flat
    // pane, and a flat pane is the container this is supposed not to be. The
    // list starts at the top and runs underneath instead, so what shows through
    // the glass is the list. The first row sits behind the bar while it is
    // open, and scrolls out from under it.

    // The overlay owns its backdrop.
    //
    // Handing it the app-wide source did not work: this bar is drawn *inside*
    // that layer, so it would be sampling a picture it is part of. Haze has
    // nothing valid to give it and paints its base colour instead — a flat
    // slab, which is exactly the "container" a sheet of glass is not supposed
    // to have. A source scoped to the content directly beneath the bar is the
    // arrangement the globe's bar always had, and the only one where the blur
    // is of something real.
    val haze = rememberHazeState()

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().hazeSource(haze)) {
            content(0.dp)
        }

        AnimatedVisibility(
            visible = open,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            GlassSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = placeholder,
                hazeState = haze,
                autoFocus = true,
                onClose = onClose,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
