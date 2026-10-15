package tf.monochrome.android.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import tf.monochrome.android.data.achievements.AchievementStore

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AchievementEntryPoint {
    fun achievementStore(): AchievementStore
}

/**
 * The achievement log, for a screen that records something without a view
 * model of its own to inject it into. Null where there is no Hilt application
 * (a preview), so recording there is simply skipped.
 */
@Composable
fun rememberAchievementStore(): AchievementStore? {
    val app = LocalContext.current.applicationContext
    return remember(app) {
        runCatching { EntryPointAccessors.fromApplication(app, AchievementEntryPoint::class.java).achievementStore() }.getOrNull()
    }
}
