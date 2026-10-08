package tf.monochrome.android.data.device

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.preferences.PreferencesManager
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceRegistry"

@Serializable
private data class SbUserDevice(
    val id: String? = null,
    val user_id: String,
    // No default — kotlinx-serialization's default behavior is to drop fields
    // that equal their default from the JSON (encodeDefaults=false), which is
    // what Supabase-kt inspects to build the `columns=` URL query. With
    // `platform = "android"` as a default, the insert shipped without a
    // `platform` column and the NOT NULL constraint on user_devices.platform
    // failed. Callers already pass it explicitly.
    val platform: String,
    /** [DeviceIdProvider]'s per-install id: with user_id, the upsert key. No default, for the same reason. */
    val local_id: String,
    val model: String? = null,
    val app_version: String? = null,
    val last_seen_at: String? = null,
)

@Serializable
private data class SbUserDeviceId(val id: String)

/**
 * Upserts the current (user, device) pair in Supabase `user_devices` and holds
 * the row's id so [currentRemoteId] can be read synchronously by the
 * play-event push path.
 *
 * Keyed by (user_id, local_id), where local_id is [DeviceIdProvider]'s
 * per-install id, unique together on the cloud table. It used to be keyed by
 * nothing: the row id was cached in preferences, the cache was cleared on
 * sign-out, and every sign-in inserted a new row — 774 rows for 327 accounts,
 * one account with 87. A cached id whose row was gone was worse: the update
 * matched nothing and the dead id went onto every play, which its foreign key
 * then refused. One upsert per launch now finds or creates the row and
 * answers its id.
 */
@Singleton
class DeviceRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authManager: SupabaseAuthManager,
    private val prefs: PreferencesManager,
    private val deviceIdProvider: DeviceIdProvider,
) {
    private val _currentRemoteId = MutableStateFlow<String?>(null)
    val currentRemoteId: StateFlow<String?> = _currentRemoteId.asStateFlow()

    /**
     * Called on app start and whenever the user profile changes. Safe to
     * call when signed-out (no-op).
     */
    suspend fun registerCurrentDevice() {
        val uid = authManager.userProfile.value?.id ?: run {
            _currentRemoteId.value = null
            return
        }
        // Survives sign-out and account switches, which is what makes it a key.
        val localId = deviceIdProvider.getOrCreate()
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        runCatching {
            val row = authManager.supabase.postgrest["user_devices"]
                .upsert(
                    SbUserDevice(
                        user_id = uid,
                        platform = "android",
                        local_id = localId,
                        model = model,
                        app_version = BuildConfig.VERSION_NAME,
                        last_seen_at = Instant.now().toString(),
                    )
                ) {
                    onConflict = "user_id,local_id"
                    select(Columns.list("id"))
                }
                .decodeSingleOrNull<SbUserDeviceId>()
            // An account switch while the request was out: the id belongs to
            // the account that sent it, not to whoever is signed in now.
            if (row != null && authManager.userProfile.value?.id == uid) {
                _currentRemoteId.value = row.id
            }
        }.onFailure {
            Log.e(TAG, "registerCurrentDevice failed: ${it.message}")
        }
    }

    /** Best-effort synchronous read for play-event enrichment. */
    fun snapshotRemoteId(): String? = _currentRemoteId.value

    suspend fun clearOnSignOut() {
        prefs.setDeviceRemoteId(null)
        _currentRemoteId.value = null
    }
}
