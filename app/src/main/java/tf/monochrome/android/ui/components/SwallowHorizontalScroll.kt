package tf.monochrome.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Stops a horizontal row's leftover scroll from reaching the tab pager above it.
 *
 * Every `LazyRow` on Home, Discover and the search results sits inside the
 * Home/Discover/Library `HorizontalPager`. Left alone, a horizontal swipe on a
 * row — or any swipe once the row has hit its edge, or is too short to scroll
 * at all — leaks upward and flips to the next tab, so flicking through a shelf
 * throws the user off the page they were reading.
 *
 * This consumes the leftover horizontal scroll and fling, and only those: the
 * vertical component is passed through untouched so the outer list still
 * scrolls normally.
 */
@Composable
fun Modifier.swallowHorizontalScroll(): Modifier {
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available.copy(y = 0f)

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = available.copy(y = 0f)
        }
    }
    return this.nestedScroll(connection)
}
