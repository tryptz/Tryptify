package tf.monochrome.android.performance

import androidx.compose.runtime.compositionLocalOf
import tf.monochrome.android.MonochromeApp

/**
 * Ambient access to the resolved [PerformanceProfile] from any Composable
 * (including Modifier factories that can't be `@Inject`-ed). Provide it once
 * at the root with `CompositionLocalProvider(LocalPerformanceProfile provides
 * MonochromeApp.profile)`.
 *
 * Dynamic rather than `static`. It used to be static, on the grounds that the
 * detected profile is resolved once at process start and never mutates — true
 * of the hardware tier, but the provided value is no longer the raw tier. The
 * root folds the user's "Remove liquid glass" switch into `allowHazeBlur`
 * ([LowPerformanceSettings]), so the profile now changes while the app runs and
 * every reader has to invalidate when it does.
 */
val LocalPerformanceProfile = compositionLocalOf<PerformanceProfile> {
    // Fallback for previews or unit-test composables that bypass the real root
    // provider. Falls through to whatever MonochromeApp resolved at startup;
    // if the Application class hasn't loaded (pure-JVM Compose preview tools),
    // pick HIGH so the preview renders the full glass chrome.
    runCatching { MonochromeApp.profile }
        .getOrElse { PerformanceProfile.forTier(DeviceTier.HIGH) }
}
