package tf.monochrome.android.widget

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * The last known now-playing state, written by the player and read by the widget.
 *
 * It exists so that drawing the widget does not have to *start the player*.
 * `readNowPlaying` used to build a `MediaController` against `PlaybackService`,
 * and connecting to a `MediaSessionService` starts it — so a widget refresh
 * constructed an ExoPlayer, a MediaSession and both FFmpeg renderers, read one
 * title off them, and let the whole thing be torn down again. A device log
 * caught it exactly: `ExoPlayerImpl: Init` at 23:36:35.848 and `Release` at
 * 23:36:36.244, four hundred milliseconds later, thirty-three seconds before
 * anybody opened the app — the process had been woken by WorkManager and the
 * widget refresh did the rest.
 *
 * The store is written from [PlaybackService]'s existing widget push, so it is
 * already current whenever there is a player to be current about, and it
 * survives the process: a widget drawn hours later shows the track that was
 * playing, paused, which is what it should show anyway.
 *
 * Its own DataStore file rather than `PreferencesManager`: the widget runs
 * outside Hilt — Glance hands a bare `Context` to `provideGlance` — and a few
 * fields the player owns do not belong in the file the settings screens edit.
 *
 * Transport taps still connect for real. Starting the service is the point of
 * pressing play; it is only *reading* that had no business doing it.
 */
private val Context.nowPlayingWidgetStore by preferencesDataStore(name = "now_playing_widget")

object NowPlayingSnapshotStore {

    suspend fun write(context: Context, snapshot: NowPlayingSnapshot) {
        context.nowPlayingWidgetStore.edit { prefs ->
            prefs[HAS_SESSION] = snapshot.hasSession
            prefs[IS_PLAYING] = snapshot.isPlaying
            prefs[TITLE] = snapshot.title
            prefs[ARTIST] = snapshot.artist
            if (snapshot.artworkUri != null) prefs[ARTWORK] = snapshot.artworkUri
            else prefs.remove(ARTWORK)
            prefs[POSITION] = snapshot.positionMs
            prefs[DURATION] = snapshot.durationMs
        }
    }

    /** The stored state, or [NowPlayingSnapshot.IDLE] before the player has written one. */
    suspend fun read(context: Context): NowPlayingSnapshot {
        val prefs = context.nowPlayingWidgetStore.data.first()
        if (prefs[HAS_SESSION] != true) return NowPlayingSnapshot.IDLE
        return NowPlayingSnapshot(
            hasSession = true,
            isPlaying = prefs[IS_PLAYING] ?: false,
            title = prefs[TITLE].orEmpty(),
            artist = prefs[ARTIST].orEmpty(),
            artworkUri = prefs[ARTWORK],
            positionMs = prefs[POSITION] ?: 0L,
            durationMs = prefs[DURATION] ?: 0L,
        )
    }

    private val HAS_SESSION = booleanPreferencesKey("has_session")
    private val IS_PLAYING = booleanPreferencesKey("is_playing")
    private val TITLE = stringPreferencesKey("title")
    private val ARTIST = stringPreferencesKey("artist")
    private val ARTWORK = stringPreferencesKey("artwork_uri")
    private val POSITION = longPreferencesKey("position_ms")
    private val DURATION = longPreferencesKey("duration_ms")
}
