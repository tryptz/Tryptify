package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.ui.graphics.Color
import tf.monochrome.android.audio.dsp.SnapinCategory

/**
 * Per-category neon accents for the Serum-style FX chain.
 *
 * These are used only as small accents — the card accent stripe, the tinted
 * title, and the active bypass glow — layered on top of `MaterialTheme`
 * surfaces so the base look still tracks the OS/app theme. Values mirror the
 * former canvas `NodeColorScheme.categoryColor`, kept here so the FX chain no
 * longer depends on the (deleted) canvas package.
 */
internal object FxChainColors {
    private val Cyan    = Color(0xFF00E5FF)   // UTILITY
    private val Green   = Color(0xFF00E676)   // EQ_FILTER
    private val Amber   = Color(0xFFFF9100)   // DYNAMICS
    private val Red     = Color(0xFFFF1744)   // DISTORTION
    private val Magenta = Color(0xFFE040FB)   // MODULATION
    private val Blue    = Color(0xFF448AFF)   // SPACE

    fun categoryColor(category: SnapinCategory): Color = when (category) {
        SnapinCategory.UTILITY    -> Cyan
        SnapinCategory.EQ_FILTER  -> Green
        SnapinCategory.DYNAMICS   -> Amber
        SnapinCategory.DISTORTION -> Red
        SnapinCategory.MODULATION -> Magenta
        SnapinCategory.SPACE      -> Blue
    }
}
