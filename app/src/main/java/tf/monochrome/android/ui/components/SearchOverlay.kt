package tf.monochrome.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
 * It is also the only arrangement that doesn't move the page. Laid out as a row
 * of its screen's Column, opening the search pushed everything below it — the
 * tabs, the toolbars, the whole list — down by the bar's height, and the list
 * then began at its own new top edge and clipped there, so rows scrolling up
 * vanished at a boundary an inch short of the glass instead of passing under it.
 *
 * Content is handed however much room the bar is taking. Zero for a bar that is
 * toggled: the first row sits behind it for as long as it is open and scrolls
 * out from under it, which is a moment. The bar's measured height for one that
 * is [reserveSpace] — permanent — where "behind the bar" would mean *forever*.
 */
@Composable
fun SearchOverlay(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    /**
     * How the bar dismisses itself. Null for a bar that is a permanent part of
     * its screen and has nowhere to go — the trailing button then only clears.
     */
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /**
     * Whether opening the bar takes the keyboard with it. True for a bar summoned
     * by a tap, where the tap *was* the request to type. False for one that is
     * simply part of the screen, where throwing the keyboard up on arrival covers
     * half the content nobody asked to search yet.
     */
    autoFocus: Boolean = true,
    /** What to submit on the keyboard's search key. */
    onSubmit: () -> Unit = {},
    /**
     * Hangs under the field inside the same pane — suggestion pills, result
     * counts, filters. One sheet of glass rather than a bar with a second thing
     * floating below it.
     */
    barContent: @Composable ColumnScope.() -> Unit = {},
    /**
     * The screen's content, handed the room the bar is taking — its measured
     * height while it is open, and zero while it is closed.
     *
     * Pass it to the scrolling container as **content padding**, not as padding
     * on the container itself. That is the whole trick, and the two are not
     * interchangeable: content padding starts the rows below the glass while
     * leaving them free to travel up behind it, which is exactly the behaviour
     * wanted — nothing hidden when the bar appears, rows sliding under it as
     * soon as you scroll. Padding the container instead moves its top edge down
     * and the list then clips there, so rows vanish at a hard line an inch short
     * of the glass.
     */
    content: @Composable (topInset: Dp) -> Unit,
) {
    // The bar takes room, and gives it back on the way out.
    //
    // Two earlier arrangements were both wrong. Laying the bar out as a row of
    // the screen's own column pushed everything down and made the list clip at
    // its new top edge. Floating it over a list with no inset at all hid
    // whatever was under it the moment it opened — on a screen whose first row
    // is the thing you came for, that is the one row you cannot cover.
    //
    // Handing the height to the list's *content padding* is the arrangement
    // that is neither: at rest the first row sits just below the glass, and it
    // passes behind the glass as soon as anything scrolls. Which also keeps the
    // frost honest — there is real content under it whenever it matters.

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

    val density = LocalDensity.current
    // The bar's full height, measured on the bar itself rather than on the
    // AnimatedVisibility around it. Expand/shrink animates the *container's*
    // height, so measuring there would report a value climbing from zero and
    // the inset would chase it; the child inside is laid out at full size and
    // only clipped, so onSizeChanged here is the real, stable height — and it
    // still tracks a taller bar when barContent adds pills under the field.
    var barHeight by remember { mutableStateOf(0.dp) }

    // The room the list keeps clear, animated so it opens and closes with the
    // bar rather than snapping. Full height while open — the list starts below
    // the glass, so nothing is hidden the instant it appears — and zero while
    // closed, so a screen never holds a gap for a bar that isn't there.
    val inset by animateDpAsState(
        targetValue = if (open) barHeight else 0.dp,
        label = "searchInset",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().hazeSource(haze)) {
            content(inset)
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
                autoFocus = autoFocus,
                onSubmit = onSubmit,
                onClose = onClose,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .onSizeChanged { size ->
                        val h = with(density) { size.height.toDp() }
                        if (h != barHeight) barHeight = h
                    },
                content = barContent,
            )
        }
    }
}
