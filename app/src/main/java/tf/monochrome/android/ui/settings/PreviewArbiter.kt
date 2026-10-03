package tf.monochrome.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlin.math.roundToInt

/**
 * Which of a screen's live previews may run: the one most on screen, and only
 * that one.
 *
 * The Studio's Visualizer tab is one scrolling column, and a column composes
 * every child whether it is in view or not — so the spectrum waterfall's
 * preview and Wave Candy's both ran their frame loops all the time, each
 * drawing every refresh, including while scrolled out of sight. Each preview
 * reports how much of it is visible; the larger share wins, and the other
 * stops drawing until it is the one being looked at.
 */
@Stable
internal class PreviewArbiter {
    private val visible = mutableStateMapOf<String, Float>()

    /**
     * Reported on every layout pass, which while scrolling is every frame. In
     * 5% steps, and only on a change, so a scroll writes this a handful of
     * times rather than sixty a second.
     */
    fun report(id: String, fraction: Float) {
        val q = (fraction.coerceIn(0f, 1f) * 20f).roundToInt() / 20f
        if (visible[id] != q) visible[id] = q
    }

    fun forget(id: String) {
        visible.remove(id)
    }

    /** Ties go to the id that sorts first, so exactly one preview ever runs. */
    fun isActive(id: String): Boolean {
        val mine = visible[id] ?: return false
        if (mine <= 0f) return false
        return visible.none { (other, f) -> other != id && (f > mine || (f == mine && other < id)) }
    }
}

internal val LocalPreviewArbiter = staticCompositionLocalOf<PreviewArbiter?> { null }

/**
 * Whether the preview [id] should be running, and the modifier that reports
 * its visibility — put it on the preview's own box. With no arbiter provided
 * the preview simply runs, as it did before.
 */
@Composable
internal fun previewGate(id: String): Pair<Boolean, Modifier> {
    val arbiter = LocalPreviewArbiter.current ?: return true to Modifier
    DisposableEffect(arbiter, id) { onDispose { arbiter.forget(id) } }
    val active by remember(arbiter, id) { derivedStateOf { arbiter.isActive(id) } }
    val reporter = remember(arbiter, id) {
        Modifier.onGloballyPositioned { coords ->
            val height = coords.size.height
            // Clipped by the scroll viewport and the window, so this is the
            // share of the preview actually on screen.
            val shown = coords.boundsInWindow().height
            arbiter.report(id, if (height > 0) shown / height else 0f)
        }
    }
    return active to reporter
}
