package tf.monochrome.android.performance

import androidx.compose.runtime.compositionLocalOf

/**
 * The user's own low-performance overrides, from Settings › System ›
 * Performance.
 *
 * Distinct from [PerformanceProfile], which is detected once at process start
 * from the hardware and never changes. This is the manual switch beside it: a
 * flagship on a long day out, a GPU driver that chokes on the AGSL glass
 * shader, or simply a preference for a still interface.
 *
 * The two compose rather than override — [disableLiquidGlass] is folded into
 * `PerformanceProfile.allowHazeBlur` when the profile is provided (see
 * `MainActivity`), so the ~25 call sites of `Modifier.liquidGlass` that already
 * gate on the tier need no change at all.
 */
data class LowPerformanceSettings(
    val disableAnimations: Boolean = false,
    val legacyPlayer: Boolean = false,
    val disableLiquidGlass: Boolean = false,
) {
    /**
     * Whether the "Low performance mode" master switch reads as on.
     *
     * The master stores no state the three switches don't already carry — it is
     * a shortcut for setting all of them, and it is on exactly while all of them
     * are. Derived here rather than in `PreferencesManager` so the rule the UI
     * shows and the rule the store writes are the same one.
     */
    val allEnabled: Boolean
        get() = disableAnimations && legacyPlayer && disableLiquidGlass
}

/**
 * Ambient access to [LowPerformanceSettings] from any Composable, including
 * Modifier factories that can't be `@Inject`-ed. Provided once at the root.
 *
 * Dynamic, not `static`: unlike the device profile these flip while the app is
 * running, and every reader has to invalidate when they do.
 */
val LocalLowPerformance = compositionLocalOf { LowPerformanceSettings() }
