package tf.monochrome.android.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-saves the user's app settings to their Supabase row whenever they change,
 * so "all settings" persist to the user database without a manual sync.
 *
 * Lifecycle (started once from [tf.monochrome.android.MonochromeApp]):
 *  - On sign-in: PULL the cloud settings first and apply them, THEN start the
 *    debounced change-watcher. Pulling first prevents the very first local
 *    emission (device defaults) from clobbering the cloud copy — the sign-in
 *    race the audit flagged.
 *  - While signed in: every allow-listed pref change, debounced ~2s and
 *    de-duplicated, triggers a push. Applying a remote snapshot is guarded by
 *    [applyingRemote] so the write it causes doesn't immediately echo back as a
 *    push (the ping-pong loop).
 *  - On sign-out: the watcher is torn down (flatMapLatest → emptyFlow).
 */
@Singleton
class SettingsSyncCoordinator @Inject constructor(
    private val preferences: PreferencesManager,
    private val syncRepository: SupabaseSyncRepository,
    private val authManager: SupabaseAuthManager,
) {
    private val applyingRemote = AtomicBoolean(false)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            authManager.userProfile
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .flatMapLatest { user ->
                    if (user == null) {
                        emptyFlow()
                    } else {
                        flow {
                            // Adopt the cloud settings before watching local changes.
                            applyingRemote.set(true)
                            try {
                                runCatching { syncRepository.pullSettings() }
                                    .onFailure { Log.e(TAG, "initial pullSettings failed: ${it.message}") }
                            } finally {
                                applyingRemote.set(false)
                            }
                            // drop(1): skip the current snapshot emitted on
                            // subscribe; only real subsequent changes push.
                            emitAll(
                                preferences.settingsSyncSnapshot
                                    .drop(1)
                                    .debounce(DEBOUNCE_MS)
                                    .distinctUntilChanged()
                            )
                        }
                    }
                }
                .collect {
                    if (!applyingRemote.get()) {
                        runCatching { syncRepository.pushSettings() }
                            .onFailure { Log.e(TAG, "auto pushSettings failed: ${it.message}") }
                    }
                }
        }
    }

    private companion object {
        const val TAG = "SettingsSync"
        const val DEBOUNCE_MS = 2_000L
    }
}
