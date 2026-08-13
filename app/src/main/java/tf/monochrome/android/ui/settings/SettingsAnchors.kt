package tf.monochrome.android.ui.settings

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * Scrolling a settings tab to the row a search result named.
 *
 * Landing on the right tab is most of the answer and, on the longer ones, not
 * the whole of it — Audio runs well past a screenful, so "you are on the tab
 * that contains it" still leaves someone scanning. This carries the wanted row
 * from the search bar to whichever tab draws it.
 *
 * Position-based rather than index-based, because a settings tab is not a list
 * of items: it is one lazy item containing the entire form. That is also what
 * makes this work at all — everything on the current tab is laid out, so the
 * wanted row reports its position without having to be scrolled into view
 * first.
 */
class SettingsAnchors {
    /** The row being looked for, by title. Null when nothing is pending. */
    var target: String? by mutableStateOf(null)
        private set

    /** Where it turned out to be, in root coordinates. */
    var foundAt: Float? by mutableStateOf(null)
        private set

    fun request(title: String) {
        target = title
        foundAt = null
    }

    fun report(y: Float) {
        // First report wins. A title that appears twice — a group header and a
        // row under it — should take you to the first of them, not bounce to
        // the last one to be measured.
        if (target != null && foundAt == null) foundAt = y
    }

    fun clear() {
        target = null
        foundAt = null
    }
}

val LocalSettingsAnchors = compositionLocalOf<SettingsAnchors?> { null }

/**
 * How far the open search bar reaches down over a settings tab.
 *
 * Fed into each tab's own scroll as top content padding, so the form runs full
 * height *under* the floating bar — giving its glass real settings to frost
 * instead of an empty strip that reads as a solid rectangle — while the first
 * row still starts just below it. Zero when the search is closed.
 */
val LocalSettingsSearchInset = compositionLocalOf { 0.dp }

/**
 * Marks this element as the row called [title], for search to scroll to.
 *
 * Costs nothing until something is being looked for: with no pending target the
 * callback compares two strings and returns.
 */
fun Modifier.settingsAnchor(title: String): Modifier = composed {
    val anchors = LocalSettingsAnchors.current ?: return@composed this
    this.onGloballyPositioned { coords ->
        if (anchors.target.equals(title, ignoreCase = true)) {
            anchors.report(coords.positionInRoot().y)
        }
    }
}
