package tf.monochrome.android.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.SupabaseAuthManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the signed-in listener's library back on the device, by itself.
 *
 * The gap this closes: app settings have healed themselves on sign-in ever
 * since [SettingsSyncCoordinator] landed, while library data — playlists,
 * their tracks, favourites, mix presets — was pushed on every edit and pulled
 * only when somebody found the Sync button on the profile screen. One half of
 * an account repaired itself and the other half did not, so a device that lost
 * its playlist rows showed an empty Playlists tab indefinitely while a complete
 * set of playlists sat in the cloud where every earlier push had left them.
 * Nothing in the app deletes playlists in bulk, so the loss was never the
 * dangerous part; having no way back was.
 *
 * Runs on every sign-in and on every launch that starts already signed in.
 * That is safe to repeat because the pull is built out of insert-if-not-exists
 * — it can only add rows the device is missing, and local data wins every
 * conflict — and it is bounded, because the automatic pass leaves the thousand
 * scrobbles of play history to the manual sync.
 *
 * Failures are logged and dropped. A restore is a repair, so a device that is
 * offline at launch is not broken, it is simply not repaired yet; it will try
 * again next launch.
 */
@Singleton
class LibraryRestoreCoordinator @Inject constructor(
    private val syncRepository: SupabaseSyncRepository,
    private val authManager: SupabaseAuthManager,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            authManager.userProfile
                // Keyed on the account rather than the profile object: the
                // profile re-emits on token refresh, and a pull per refresh is
                // a pull every hour for no new rows.
                .distinctUntilChanged { old, new -> old?.id == new?.id }
                .collect { user ->
                    if (user == null) return@collect
                    val failed = runCatching { syncRepository.pullLibrary(includePlayEvents = false) }
                        .getOrElse {
                            Log.e(TAG, "library restore failed: ${it.message}")
                            return@collect
                        }
                    if (failed.isEmpty()) {
                        Log.d(TAG, "library restore complete")
                    } else {
                        Log.w(TAG, "library restore finished with issues: ${failed.joinToString()}")
                    }
                }
        }
    }

    private companion object {
        const val TAG = "LibraryRestore"
    }
}
