// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.player.preview

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.wav.TryptifyExtractors
import tf.monochrome.android.data.cache.DeezerPartialDataSource
import tf.monochrome.android.data.cache.DeezerStreamCacheManager
import tf.monochrome.android.data.cache.QobuzPartialDataSource
import tf.monochrome.android.data.cache.QobuzStreamCacheManager
import tf.monochrome.android.data.cache.SchemeRoutingDataSource
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.player.StreamResolver

/**
 * Hold a song to hear it: a 25 s preview of any song in any list, as Trypt
 * Mania's wheel previews its songs, playing for as long as the finger stays
 * down.
 *
 * Its own player, like Trypt Mania's preview and for the same reason: a
 * preview is not listening, and the queue, the notification and scrobbling
 * must not see it. It plays whatever the main player can — a file, a
 * download, a TIDAL, Qobuz, Deezer or Apple stream — through the same
 * resolver and the same data sources.
 *
 * It asks Android for *transient* focus, so the music already playing pauses
 * and carries on by itself when the preview gives the focus back; the main
 * player handles focus through Media3, which does exactly that. With "Play
 * alongside other apps" on, the main player ignores focus and keeps playing.
 *
 * One preview at a time, on the main thread.
 */
@Singleton
@OptIn(UnstableApi::class)
class SongPreview @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: StreamResolver,
    private val qobuzCache: QobuzStreamCacheManager,
    private val deezerCache: DeezerStreamCacheManager,
    private val serverAccess: tf.monochrome.android.data.api.ApiServerAccess,
) {
    /** The song being previewed, by its row's key; [playing] once the sound has started. */
    data class State(val key: String, val playing: Boolean)

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var job: Job? = null
    private var player: ExoPlayer? = null
    private var focus: AudioFocusRequest? = null

    /** Where in the song the sound started, in ms; negative until it has. */
    private var playedFromMs = -1L

    fun start(track: Track) = begin(keyOf(track)) {
        val (item, _) = resolver.resolveMediaItem(track, askForOtherService = false, quality = previewQuality(track.id, track.deezerId))
        item?.let { Clip(it, startMs(it.localConfiguration?.uri?.scheme, track.duration, drop = null)) }
    }

    fun start(track: UnifiedTrack) = begin(keyOf(track)) {
        val source = track.source
        val quality = when (source) {
            is PlaybackSource.QobuzCached -> previewQuality(source.qobuzId, null)
            is PlaybackSource.DeezerPreview -> previewQuality(null, source.deezerId)
            else -> PREVIEW_QUALITY
        }
        val resolved = resolver.resolveUnifiedTrack(track, askForOtherService = false, quality = quality)
        if (!resolved.isPlayable || resolved.isEncrypted) return@begin null
        val drop = if (track.sourceType == SourceType.LOCAL) rememberedDrop(track.id) else null
        Clip(resolved.mediaItem, startMs(resolved.mediaItem.localConfiguration?.uri?.scheme, track.durationSeconds, drop))
    }

    /** Ends [key]'s preview, fading it out: the finger lifted. Nothing when another is playing. */
    fun stop(key: String) {
        if (_state.value?.key != key) return
        job?.cancel()
        _state.value = null
        val p = player
        job = scope.launch {
            if (p != null && p.isPlaying) {
                val from = p.volume
                val steps = (STOP_FADE_MS / TICK_MS).toInt().coerceAtLeast(1)
                for (i in 1..steps) {
                    p.volume = from * (1f - i.toFloat() / steps)
                    delay(TICK_MS)
                }
            }
            p?.stop()
            abandonFocus()
        }
    }

    /** How much of [key]'s 25 s has played, 0 to 1. Main thread, as the player is. */
    fun elapsedFraction(key: String): Float {
        val p = player ?: return 0f
        if (_state.value?.key != key || playedFromMs < 0) return 0f
        return ((p.currentPosition - playedFromMs).toFloat() / PREVIEW_MS).coerceIn(0f, 1f)
    }

    private class Clip(val item: MediaItem, val startMs: Long)

    /**
     * What a preview streams at: 320 kbps, which starts in a moment and costs
     * little for 25 s of a song that may not be played at all — unless the
     * song is already in the Qobuz or Deezer cache whole at any quality, which
     * plays from disk and is better than any stream. A file on the phone
     * takes no quality at all; the resolver finds it first.
     */
    private fun previewQuality(qobuzId: Long?, deezerId: Long?): AudioQuality? {
        for (quality in AudioQuality.entries) {
            if (qobuzId != null && runCatching { qobuzCache.peekCached(qobuzId, quality) }.getOrNull() != null) return null
            if (deezerId != null && runCatching { deezerCache.peekCached(deezerId, quality) }.getOrNull() != null) return null
        }
        return PREVIEW_QUALITY
    }

    private fun begin(key: String, resolve: suspend () -> Clip?) {
        // Another song held: its preview ends now, not after a fade.
        job?.cancel()
        player?.stop()
        playedFromMs = -1L
        _state.value = State(key, playing = false)
        job = scope.launch {
            val clip = try {
                resolve()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "No preview for $key", failure)
                null
            }
            if (clip == null || !requestFocus()) {
                end(key)
                return@launch
            }
            val p = player ?: build().also { player = it }
            p.volume = 0f
            // A source that cannot seek (Apple's fMP4 over HTTP) plays from its
            // start rather than refusing, which a clipped item would.
            p.setMediaItem(clip.item, clip.startMs)
            p.prepare()
            p.playWhenReady = true
            var fitted = false
            try {
                while (isActive) {
                    val ready = p.playbackState
                    if (!fitted && playedFromMs < 0 && (ready == Player.STATE_READY || ready == Player.STATE_ENDED)) {
                        // A source shorter than the song — Deezer's 30 s preview
                        // of one that has none of its own — can start too near
                        // its end, or past it: once, back to where 25 s still fit.
                        fitted = true
                        val duration = p.duration
                        if (duration != C.TIME_UNSET && p.currentPosition > duration - PREVIEW_MS) {
                            p.seekTo((duration - PREVIEW_MS).coerceAtLeast(0L))
                            delay(TICK_MS)
                            continue
                        }
                    }
                    if (ready == Player.STATE_ENDED || (ready == Player.STATE_IDLE && p.playerError != null)) break
                    if (p.isPlaying) {
                        if (playedFromMs < 0) {
                            playedFromMs = p.currentPosition
                            _state.value = State(key, playing = true)
                        }
                        val elapsed = p.currentPosition - playedFromMs
                        if (elapsed >= PREVIEW_MS) break
                        p.volume = volumeAt(elapsed)
                    }
                    delay(TICK_MS)
                }
            } finally {
                if (_state.value?.key == key) end(key)
            }
        }
    }

    private fun end(key: String) {
        player?.stop()
        abandonFocus()
        if (_state.value?.key == key) _state.value = null
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        // Held already, from the song held just before: kept, so the music
        // does not resume between two previews, and never asked for twice,
        // which would leave the first request holding the focus for good.
        if (focus != null) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                PlatformAudioAttributes.Builder()
                    .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
                    .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            // A call, or another app taking the sound: the preview gives way.
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    _state.value?.key?.let(::stop)
                }
            }
            .build()
        focus = request
        return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val request = focus ?: return
        focus = null
        audioManager?.abandonAudioFocusRequest(request)
    }

    private fun rememberedDrop(trackId: String): Float? =
        context.getSharedPreferences(TRYPT_MANIA_PREFS, Context.MODE_PRIVATE)
            .getFloat(DROP_PREFIX + trackId, -1f).takeIf { it >= 0f }

    private fun build(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val guarded = androidx.media3.datasource.ResolvingDataSource.Factory(http) { spec ->
            tf.monochrome.android.data.net.CleartextPolicy.requireForMedia(spec.uri.toString())
            serverAccess.withPassword(spec)
        }
        val default = DefaultDataSource.Factory(context, guarded)
        val qobuz = QobuzPartialDataSource.Factory(qobuzCache)
        val deezer = DeezerPartialDataSource.Factory(deezerCache)
        // The main player's routing: qobuz:// and deezer:// read their caches.
        val sources = DataSource.Factory {
            SchemeRoutingDataSource(default.createDataSource(), qobuz.createDataSource(), deezer.createDataSource())
        }
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources, TryptifyExtractors.factory))
            // Focus is asked for by hand, transient, so the music resumes after.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false,
            )
            .build()
    }

    companion object {
        private const val TAG = "SongPreview"

        /** How long a preview plays. */
        const val PREVIEW_MS = 25_000L

        /** What a preview streams at: 320 kbps on every catalogue (AAC on TIDAL and Apple, MP3 on Qobuz and Deezer). */
        val PREVIEW_QUALITY = AudioQuality.HIGH

        private const val FADE_IN_MS = 120f
        private const val FADE_OUT_MS = 1_500f
        private const val STOP_FADE_MS = 200L
        private const val TICK_MS = 25L

        private const val USER_AGENT = "Tryptify/1.0 ( https://github.com/tryptz/tryptify )"

        /** Where Trypt Mania keeps the drops it has found, by library track id. */
        private const val TRYPT_MANIA_PREFS = "trypt_mania"
        private const val DROP_PREFIX = "drop_"

        /** The key a row's preview goes by: the song, whichever list shows it. */
        fun keyOf(track: Track): String = "track:${track.id}"
        fun keyOf(track: UnifiedTrack): String = "unified:${track.id}"

        /**
         * Where a preview starts, in ms: the drop when Trypt Mania has found one,
         * else a third of the way in, with the 25 s inside the song. From the top
         * for a song shorter than that, and for Qobuz and Deezer, whose cached
         * files fill from the front: a later start would wait on the download.
         */
        internal fun startMs(uriScheme: String?, durationSeconds: Int, drop: Float?): Long {
            val scheme = uriScheme?.lowercase()
            if (scheme == "qobuz" || scheme == "deezer") return 0L
            val duration = durationSeconds * 1000L
            if (duration <= PREVIEW_MS) return 0L
            val wanted = drop?.let { (it * 1000f).toLong() } ?: (duration / 3)
            return wanted.coerceIn(0L, duration - PREVIEW_MS)
        }

        /** The preview's volume [elapsedMs] in: up over 120 ms, down over its last 1.5 s. */
        internal fun volumeAt(elapsedMs: Long): Float {
            val rise = (elapsedMs / FADE_IN_MS).coerceIn(0f, 1f)
            val fall = ((PREVIEW_MS - elapsedMs) / FADE_OUT_MS).coerceIn(0f, 1f)
            return minOf(rise, fall)
        }
    }
}

/** For the app's root, which hands the preview to every row (`LocalSongPreview`). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SongPreviewEntryPoint {
    fun songPreview(): SongPreview
}
