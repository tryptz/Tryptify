package tf.monochrome.android.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncOutboxDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "sync_outbox")

/** The kinds of library row that travel between Room and Supabase one edit at a time. */
@Serializable
enum class SyncKind {
    FAVORITE_TRACK,
    FAVORITE_ALBUM,
    FAVORITE_ARTIST,
    // Declared before PLAYLIST_TRACK on purpose: the flush runs in this order,
    // and a playlist_tracks row is refused by RLS until its playlist exists.
    PLAYLIST,
    PLAYLIST_TRACK,
    EQ_PRESET,
    MIX_PRESET,
}

@Serializable
enum class SyncOp { UPSERT, DELETE }

/**
 * One local edit the cloud has not confirmed yet.
 *
 * [key] is the row's cross-device identity: the catalogue id for favourites,
 * the playlist UUID, [playlistTrackKey] for a playlist entry, the EQ preset id,
 * and the mixer preset's creation time. Only the identity is stored, never the
 * row itself — an upsert re-reads the row from Room when it is flushed, so it
 * always sends the latest edit rather than the one that queued it.
 */
@Serializable
data class PendingChange(
    val userId: String,
    val kind: SyncKind,
    val key: String,
    val op: SyncOp,
    /**
     * Unique per recording. Two edits to the same row with the same op would
     * otherwise be equal, and settling the first — already read and sent — would
     * erase the second before it was ever sent.
     */
    val stamp: String = java.util.UUID.randomUUID().toString(),
)

fun playlistTrackKey(playlistId: String, trackId: Long): String = "$playlistId/$trackId"

/** The playlist and track of a [playlistTrackKey], or null for a malformed key. */
fun parsePlaylistTrackKey(key: String): Pair<String, Long>? {
    val slash = key.lastIndexOf('/')
    if (slash <= 0) return null
    val trackId = key.substring(slash + 1).toLongOrNull() ?: return null
    return key.substring(0, slash) to trackId
}

/**
 * The rules for the pending-change list, kept free of storage so they can be
 * unit-tested.
 *
 * Why the list exists at all: a local edit used to reach the cloud either never
 * (favourites and playlists were only ever sent by the manual Sync button) or
 * once, fire-and-forget (presets). Meanwhile the launch-time pull re-inserts
 * every cloud row the device is missing. So a delete the cloud never heard about
 * — made offline, made signed-in but never synced, or simply dropped by a flaky
 * connection — came straight back on the next launch. The list is what turns
 * "tried once" into "held until the cloud says yes", and its pending deletes are
 * what the pull consults before it re-inserts anything.
 */
internal object Outbox {

    /**
     * Adds [change], replacing whatever was pending for the same row: only the
     * last edit matters, so unlike-then-like leaves one UPSERT, not a DELETE the
     * flush might run second.
     *
     * Deleting a playlist also drops its pending entry edits. The cloud
     * cascades the delete to its tracks, and an entry upsert flushed first would
     * only be refused anyway.
     */
    fun record(pending: List<PendingChange>, change: PendingChange): List<PendingChange> {
        val playlistGone = change.kind == SyncKind.PLAYLIST && change.op == SyncOp.DELETE
        return pending.filterNot { it.sameRow(change) }.filterNot {
            playlistGone && it.userId == change.userId && it.kind == SyncKind.PLAYLIST_TRACK &&
                parsePlaylistTrackKey(it.key)?.first == change.key
        } + change
    }

    /**
     * Removes [change] once the cloud has confirmed it — but only if it is still
     * the pending edit for that row (same [PendingChange.stamp]). A newer edit
     * recorded while this one was in flight must survive, or it would never be
     * sent.
     */
    fun settle(pending: List<PendingChange>, change: PendingChange): List<PendingChange> =
        pending.filterNot { it == change }

    /** This account's pending edits, in the order they must be flushed. */
    fun forUser(pending: List<PendingChange>, userId: String): List<PendingChange> =
        pending.filter { it.userId == userId }.sortedBy { it.kind.ordinal }

    /** Keys of [kind] this account has deleted locally and the cloud still holds. */
    fun pendingDeletes(pending: List<PendingChange>, userId: String, kind: SyncKind): Set<String> =
        pending.filter { it.userId == userId && it.kind == kind && it.op == SyncOp.DELETE }
            .mapTo(HashSet()) { it.key }

    private fun PendingChange.sameRow(other: PendingChange) =
        userId == other.userId && kind == other.kind && key == other.key
}

/**
 * What this device has changed that the cloud hasn't confirmed, persisted in
 * its own small DataStore so it outlives the process — an app killed between an
 * offline delete and the next launch must still know to hold that delete back
 * from the pull.
 *
 * Two things live here: the pending library edits, and the settings snapshot
 * the device and the cloud last agreed on (the base
 * [tf.monochrome.android.data.preferences.SettingsSyncCodec.changedSince]
 * diffs against).
 *
 * Scoped by account: an edit made under one account is never sent to another.
 */
@Singleton
class SyncOutbox @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.syncOutboxDataStore
    private val listKey = stringPreferencesKey("pending")
    // encodeDefaults: [PendingChange.stamp] is a default, and one that isn't
    // written would be re-minted on every read, so settle would never match.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(PendingChange.serializer())

    suspend fun record(change: PendingChange) = update { Outbox.record(it, change) }

    suspend fun settle(change: PendingChange) = update { Outbox.settle(it, change) }

    suspend fun forUser(userId: String): List<PendingChange> = Outbox.forUser(read(), userId)

    suspend fun pendingDeletes(userId: String, kind: SyncKind): Set<String> =
        Outbox.pendingDeletes(read(), userId, kind)

    /** The settings snapshot this account and device last agreed on, if they ever have. */
    suspend fun settingsBase(userId: String): String? = dataStore.data.first()[settingsBaseKey(userId)]

    suspend fun setSettingsBase(userId: String, payload: String) {
        dataStore.edit { it[settingsBaseKey(userId)] = payload }
    }

    private fun settingsBaseKey(userId: String) = stringPreferencesKey("settings_base_$userId")

    private suspend fun read(): List<PendingChange> = decode(dataStore.data.first()[listKey])

    private suspend fun update(transform: (List<PendingChange>) -> List<PendingChange>) {
        dataStore.edit { prefs ->
            prefs[listKey] = json.encodeToString(serializer, transform(decode(prefs[listKey])))
        }
    }

    private fun decode(raw: String?): List<PendingChange> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
}
