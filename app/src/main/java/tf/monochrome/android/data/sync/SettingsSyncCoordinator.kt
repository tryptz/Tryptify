package tf.monochrome.android.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
 *  - The pull has to actually reach the cloud. It used to swallow its own
 *    failure, so a sign-in while offline started the watcher anyway, and the
 *    first setting touched afterwards uploaded this device's near-empty
 *    snapshot over the account's real one. Now a failed pull is retried with
 *    backoff and nothing is pushed until one succeeds. (Pushes also merge into
 *    the cloud copy rather than replacing it — see SettingsSyncCodec.merge —
 *    so even a push can only add or change keys, never drop them.)
 *  - An account with no cloud settings yet is seeded from this device straight
 *    away, instead of waiting for the first change.
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
                            // Adopt the cloud settings before watching local
                            // changes, and don't watch at all until that has
                            // worked. The delay is cancellable, so a sign-out
                            // during the wait ends it via flatMapLatest.
                            var backoffMs = RETRY_START_MS
                            while (true) {
                                applyingRemote.set(true)
                                val pulled = try {
                                    syncRepository.pullSettings()
                                } finally {
                                    applyingRemote.set(false)
                                }
                                if (pulled != SettingsPull.FAILED) {
                                    if (pulled == SettingsPull.NO_CLOUD_COPY) syncRepository.pushSettings()
                                    break
                                }
                                Log.w(TAG, "initial pullSettings failed; not pushing, retrying in ${backoffMs}ms")
                                delay(backoffMs)
                                backoffMs = (backoffMs * 2).coerceAtMost(RETRY_MAX_MS)
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
        const val RETRY_START_MS = 5_000L
        const val RETRY_MAX_MS = 5 * 60_000L
    }
}
