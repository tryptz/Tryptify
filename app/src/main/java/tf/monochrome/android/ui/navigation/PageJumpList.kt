package tf.monochrome.android.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.goToPage
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * The page list: every page, by name, tapped to go there.
 *
 * The seven pages used to be reachable only by swiping the pager, which put
 * Downloads six drags from Home. The Library pages had a three-dot "Other pages"
 * dropdown as a shortcut, but Home and Discover — the two pages people start
 * from — had nothing, so the swipe was the only way off them.
 *
 * This is that list, and it is the same list everywhere: Home renders it as its
 * whole body, and every other page reaches it through [PageJumpSheet].
 *
 * Jumping through [pager] rather than the NavController is deliberate and is
 * what makes Back work for free: `MonochromeNavHost`'s settle effect records
 * every page the pager lands on, so a tap here enters the page history exactly
 * as a swipe does.
 */
@Composable
internal fun PageJumpList(
    pages: List<String>,
    pager: PagerState,
    current: String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onJump: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Read once here rather than inside the click lambda: reduceMotion() is a
    // composable read and the jump happens in a coroutine.
    val animate = !reduceMotion()

    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = contentPadding) {
        items(pages, key = { it }, contentType = { "page" }) { id ->
            val isCurrent = id == current
            Text(
                text = APP_PAGE_TITLES[id] ?: id,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    // The page you are already on is shown but inert — it is
                    // there to tell you where you are, and a row that looks
                    // tappable and does nothing reads as a broken row.
                    .semantics { selected = isCurrent }
                    .then(
                        if (isCurrent) Modifier
                        else Modifier.bounceClick {
                            val page = pages.indexOf(id)
                            if (page >= 0) {
                                onJump()
                                scope.launch { pager.goToPage(page, animate) }
                            }
                        }
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            )
        }
    }
}

/**
 * [PageJumpList] over the current page, for the six pages that are not Home.
 *
 * Replaces the Library pages' `DropdownMenu`, which was a cramped list of small
 * rows anchored to a three-dot icon and existed on five of the seven pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageJumpSheet(
    pages: List<String>,
    pager: PagerState,
    current: String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            .copy(alpha = MonoDimens.cardAlpha),
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            PageJumpList(
                pages = pages,
                pager = pager,
                current = current,
                // Dismiss before the scroll, not after it: the sheet is over the
                // pager, so animating a page change underneath a sheet that is
                // still up hides the thing the tap was for.
                onJump = onDismiss,
            )
        }
    }
}
