package tf.monochrome.android.data.subsonic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import tf.monochrome.android.data.net.CleartextPolicy
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject
import javax.inject.Singleton

/** Why connecting failed, in terms the Settings dialog can word. */
enum class SubsonicConnectError {
    /** The address is not an http(s) URL. */
    BAD_ADDRESS,

    /** http:// to a server that is not on the listener's own network (CleartextPolicy). */
    NEEDS_HTTPS,

    /** The server takes only the plain password, which never goes over http://. */
    PASSWORD_NEEDS_HTTPS,

    /** The server says the username or password is wrong (error 40). */
    WRONG_LOGIN,

    /** Something answered, but not a Subsonic server: the wrong port, a router's page. */
    NOT_SUBSONIC,

    /** Nothing answered: off, another network, a firewall. */
    UNREACHABLE,

    /** A Subsonic error this app has no wording for; [SubsonicConnectResult.Failed.detail] says which. */
    OTHER,
}

sealed interface SubsonicConnectResult {
    data class Connected(val server: SubsonicServer) : SubsonicConnectResult
    data class Failed(val error: SubsonicConnectError, val detail: String? = null) : SubsonicConnectResult
}

/**
 * The listener's own music server: connecting to it, and reading its library
 * as the app's unified models.
 *
 * Read live rather than mirrored into Room: the server is the library's single
 * copy, and a mirror would need a migration, a sync and a way to notice
 * deletions, for a list the server returns in one request anyway.
 */
@Singleton
class SubsonicRepository @Inject constructor(
    private val client: SubsonicClient,
    private val preferences: PreferencesManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {
    val server: Flow<SubsonicServer?> = preferences.subsonicServer

    /**
     * The same, held for readers that cannot suspend: the image loader's
     * [SubsonicCoverMapper] runs on whatever thread asked for the picture.
     */
    val current: StateFlow<SubsonicServer?> = preferences.subsonicServer
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, null)

    suspend fun currentServer(): SubsonicServer? = preferences.subsonicServer.first()

    /**
     * Signs in and, when the server answers, saves it. The token first; the
     * plain password only when the server says it cannot take a token (see
     * [SubsonicAuthMode]). The password itself is never kept in token mode.
     */
    suspend fun connect(address: String, username: String, password: String): SubsonicConnectResult {
        val url = Subsonic.normalizeUrl(address)
            ?: return SubsonicConnectResult.Failed(SubsonicConnectError.BAD_ADDRESS)
        if (CleartextPolicy.refusal(url) != null) return SubsonicConnectResult.Failed(SubsonicConnectError.NEEDS_HTTPS)
        val user = username.trim()
        val first = attempt(Subsonic.tokenServer(url, user, password))
        val result = if (first is Attempt.Refused && Subsonic.wantsPassword(first.error)) {
            // The password itself would cross the network readable: never over http://, home or not.
            if (url.startsWith("http://", ignoreCase = true)) {
                return SubsonicConnectResult.Failed(SubsonicConnectError.PASSWORD_NEEDS_HTTPS)
            }
            attempt(Subsonic.passwordServer(url, user, password))
        } else first
        return when (result) {
            is Attempt.Ok -> {
                preferences.setSubsonicServer(result.server)
                SubsonicConnectResult.Connected(result.server)
            }
            is Attempt.Refused -> SubsonicConnectResult.Failed(
                if (result.error.code == Subsonic.ERROR_WRONG_CREDENTIALS) SubsonicConnectError.WRONG_LOGIN
                else SubsonicConnectError.OTHER,
                result.error.message,
            )
            is Attempt.NotSubsonic -> SubsonicConnectResult.Failed(SubsonicConnectError.NOT_SUBSONIC)
            is Attempt.Unreachable -> SubsonicConnectResult.Failed(SubsonicConnectError.UNREACHABLE, result.detail)
        }
    }

    suspend fun disconnect() = preferences.setSubsonicServer(null)

    private sealed interface Attempt {
        data class Ok(val server: SubsonicServer) : Attempt
        data class Refused(val error: SubsonicException) : Attempt
        data object NotSubsonic : Attempt
        data class Unreachable(val detail: String?) : Attempt
    }

    private suspend fun attempt(server: SubsonicServer): Attempt = try {
        val pong = client.ping(server)
        Attempt.Ok(server.copy(serverType = pong.type, serverVersion = pong.serverVersion ?: pong.version))
    } catch (e: SubsonicException) {
        Attempt.Refused(e)
    } catch (e: SerializationException) {
        Attempt.NotSubsonic
    } catch (e: IllegalArgumentException) {
        // kotlinx reports some malformed bodies (an HTML page) this way too.
        Attempt.NotSubsonic
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Attempt.Unreachable(e.message)
    }

    // ── The library. Each needs a connected server and throws without one. ──

    private suspend fun requireServer(): SubsonicServer =
        currentServer() ?: throw IllegalStateException("No Subsonic server connected")

    suspend fun albums(sort: SubsonicAlbumSort, offset: Int = 0, size: Int = PAGE_SIZE): List<UnifiedAlbum> {
        val server = requireServer()
        return client.albumList(server, sort.apiType, size, offset).map { it.toUnifiedAlbum() }
    }

    suspend fun albumTracks(albumId: String): List<UnifiedTrack> {
        val server = requireServer()
        val album = client.album(server, rawId(albumId, ALBUM_PREFIX)) ?: return emptyList()
        return album.song.filterNot { it.isVideo }.map { it.toUnifiedTrack(album) }
    }

    suspend fun artists(): List<UnifiedArtist> {
        val server = requireServer()
        return client.artists(server).map { it.toUnifiedArtist() }
    }

    suspend fun artistAlbums(artistId: String): List<UnifiedAlbum> {
        val server = requireServer()
        val artist = client.artist(server, rawId(artistId, ARTIST_PREFIX)) ?: return emptyList()
        return artist.album.map { it.toUnifiedAlbum() }.sortedBy { it.year ?: Int.MAX_VALUE }
    }

    /**
     * [songId]'s stream on [server]: the original file, or on mobile data the
     * MP3 cap chosen under Settings › Connections. Decided when the song is
     * resolved, so a change of network applies from the next song.
     */
    suspend fun streamUrl(server: SubsonicServer, songId: String): String {
        val cap = if (onMobileData()) preferences.subsonicCellularMaxBitrate.first() else 0
        return Subsonic.streamUrl(server, songId, cap)
    }

    // Mobile data only, not "anything but Wi-Fi": a server on the home network
    // reached over Ethernet or a VPN keeps sending the original file.
    private fun onMobileData(): Boolean {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** A [Subsonic.coverArtRef]'s picture, for the media session; null on any failure. */
    suspend fun coverArtBytes(ref: String?, size: Int = NOTIFICATION_COVER_SIZE): ByteArray? {
        val id = Subsonic.coverArtId(ref) ?: return null
        val server = currentServer() ?: return null
        return try {
            client.coverArt(server, id, size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Tells the server a song is playing ([submission] false) or was played
     * ([submission] true, at [timeMillis]), so its play counts, Recently
     * Played and any Last.fm or ListenBrainz relay it runs see plays made
     * here. Best effort: a server that is away loses the report, never the
     * playback.
     */
    suspend fun reportPlay(songId: String, submission: Boolean, timeMillis: Long? = null) {
        val server = currentServer() ?: return
        try {
            client.scrobble(server, songId, submission, timeMillis)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Away or refused: nothing to do, the play itself is unaffected.
        }
    }

    suspend fun playlists(): List<ServerPlaylist> {
        val server = requireServer()
        return client.playlists(server).map { it.toServerPlaylist() }
    }

    suspend fun playlistTracks(playlistId: String): List<UnifiedTrack> {
        val server = requireServer()
        return client.playlist(server, playlistId)?.entry.orEmpty().filterNot { it.isVideo }.map { it.toUnifiedTrack() }
    }

    /** The songs starred on the server, by this app's likes or any other client's. */
    suspend fun starredSongs(): List<UnifiedTrack> {
        val server = requireServer()
        return client.starred(server).song.filterNot { it.isVideo }.map { it.toUnifiedTrack() }
    }

    /**
     * Stars [songId] on the server, or unstars it, when it is liked or
     * unliked here. Best effort, like [reportPlay]: the like is kept either way.
     */
    suspend fun setStarred(songId: String, starred: Boolean) {
        val server = currentServer() ?: return
        try {
            client.setStarred(server, songId, starred)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Away or refused: the server misses this one.
        }
    }

    /**
     * The lyrics the server keeps for [songId]: OpenSubsonic's, timed when the
     * server has them timed, else classic getLyrics by [artist] and [title]
     * (plain text, or LRC some servers hand back as it is). Null on any failure.
     */
    suspend fun lyrics(songId: String, artist: String?, title: String?): tf.monochrome.android.domain.model.Lyrics? {
        val server = currentServer() ?: return null
        orNull { client.lyricsBySongId(server, songId) }?.let { pickLyrics(it) }?.let { return it }
        if (artist.isNullOrBlank() || title.isNullOrBlank()) return null
        return orNull { client.lyrics(server, artist, title) }?.let(tf.monochrome.android.data.repository.LocalLyrics::parse)
    }

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun searchSongs(query: String, count: Int = 50): List<UnifiedTrack> {
        val server = currentServer() ?: return emptyList()
        if (query.isBlank()) return emptyList()
        return client.search(server, query, songCount = count).song
            .filterNot { it.isVideo }
            .map { it.toUnifiedTrack() }
    }

    /**
     * One page of the server's songs, matching [query]. A blank query is every
     * song: search3 with an empty query is how Navidrome and the Tryptify Cloud
     * hand out the whole library a page at a time.
     */
    suspend fun songs(query: String, offset: Int, size: Int): List<UnifiedTrack> {
        val server = requireServer()
        return client.search(server, query.trim(), songCount = size, songOffset = offset).song
            .filterNot { it.isVideo }
            .map { it.toUnifiedTrack() }
    }

    suspend fun randomSongs(size: Int = 200): List<UnifiedTrack> {
        val server = requireServer()
        return client.randomSongs(server, size).filterNot { it.isVideo }.map { it.toUnifiedTrack() }
    }

    companion object {
        const val PAGE_SIZE = 100
        const val NOTIFICATION_COVER_SIZE = 512
        const val TRACK_PREFIX = "subsonic_"
        const val ALBUM_PREFIX = "subsonic_album_"
        const val ARTIST_PREFIX = "subsonic_artist_"

        private fun rawId(id: String, prefix: String) = id.removePrefix(prefix)

        /**
         * The codec a file suffix names. `m4a` is AAC unless the server reports
         * a bit depth, which only a lossless (ALAC) file has.
         */
        fun codecFor(suffix: String?, bitDepth: Int?): AudioCodec? = when (suffix?.lowercase()) {
            "flac" -> AudioCodec.FLAC
            "mp3" -> AudioCodec.MP3
            "alac" -> AudioCodec.ALAC
            "m4a", "mp4", "m4b" -> if ((bitDepth ?: 0) > 0) AudioCodec.ALAC else AudioCodec.AAC
            "aac" -> AudioCodec.AAC
            "ogg", "oga" -> AudioCodec.OGG_VORBIS
            "opus" -> AudioCodec.OPUS
            "wav" -> AudioCodec.WAV
            "aif", "aiff" -> AudioCodec.AIFF
            "ape" -> AudioCodec.APE
            "wma" -> AudioCodec.WMA
            else -> null
        }

        fun SubsonicSong.toUnifiedTrack(album: SubsonicAlbum? = null): UnifiedTrack {
            val artistName = artist?.takeIf { it.isNotBlank() } ?: album?.artist ?: "Unknown Artist"
            return UnifiedTrack(
                id = TRACK_PREFIX + id,
                title = title.ifBlank { id },
                durationSeconds = duration,
                trackNumber = track,
                discNumber = discNumber,
                artistName = artistName,
                artistNames = listOf(artistName),
                albumArtistName = album?.artist,
                // No numeric id: the name shows, and is not a link to a catalogue page.
                artists = listOf(UnifiedArtistRef(id = null, name = artistName)),
                albumTitle = this.album ?: album?.displayName,
                albumId = (albumId ?: album?.id)?.let { ALBUM_PREFIX + it },
                releaseYear = year ?: album?.year,
                // Servers name an album's cover after the album, so its id is the last resort.
                artworkUri = Subsonic.coverArtRef(coverArt ?: album?.coverArt ?: albumId ?: album?.id),
                codec = codecFor(suffix, bitDepth),
                sampleRate = samplingRate,
                bitDepth = bitDepth?.takeIf { it > 0 },
                bitRate = bitRate,
                channelCount = channelCount,
                musicBrainzTrackId = musicBrainzId,
                source = PlaybackSource.Subsonic(songId = id, suffix = suffix, bitRateKbps = bitRate),
                sourceType = SourceType.SUBSONIC,
                fileSizeBytes = size,
                genre = genre ?: album?.genre,
            )
        }

        fun SubsonicAlbum.toUnifiedAlbum() = UnifiedAlbum(
            id = ALBUM_PREFIX + id,
            title = displayName,
            artistName = artist ?: "Unknown Artist",
            year = year,
            trackCount = songCount,
            totalDuration = duration,
            artworkUri = Subsonic.coverArtRef(coverArt ?: id),
            genres = listOfNotNull(genre),
            sourceType = SourceType.SUBSONIC,
        )

        fun SubsonicPlaylist.toServerPlaylist() = ServerPlaylist(
            id = id,
            name = name.ifBlank { id },
            songCount = songCount,
            artworkUri = Subsonic.coverArtRef(coverArt),
        )

        /** The first language's lyrics that are timed, else the first that has words. */
        fun pickLyrics(all: List<SubsonicStructuredLyrics>): tf.monochrome.android.domain.model.Lyrics? =
            all.sortedByDescending { it.synced }.firstNotNullOfOrNull { it.toLyrics() }

        /**
         * As the player shows them. Timed only when the server says so and
         * some line starts past zero; a blank timed line (a pause) is no line,
         * as in LRC. Untimed text keeps its blank lines between verses.
         */
        fun SubsonicStructuredLyrics.toLyrics(): tf.monochrome.android.domain.model.Lyrics? {
            if (line.none { it.value.isNotBlank() }) return null
            val timed = synced && line.any { (it.start ?: 0) > 0 }
            val lines = if (timed) {
                line.filter { it.value.isNotBlank() }
                    .map { tf.monochrome.android.domain.model.LyricLine(((it.start ?: 0) - offset).coerceAtLeast(0), it.value.trim()) }
                    .sortedBy { it.timeMs }
            } else {
                line.map { it.value.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                    .map { tf.monochrome.android.domain.model.LyricLine(0, it) }
            }
            return tf.monochrome.android.domain.model.Lyrics(lines = lines, isSynced = timed)
        }

        fun SubsonicArtist.toUnifiedArtist() = UnifiedArtist(
            id = ARTIST_PREFIX + id,
            name = name,
            artworkUri = Subsonic.coverArtRef(coverArt),
            albumCount = albumCount,
            sourceType = SourceType.SUBSONIC,
        )
    }
}

/** A playlist on the listener's server, as the My server page lists it. */
data class ServerPlaylist(
    val id: String,
    val name: String,
    val songCount: Int,
    /** A [Subsonic.coverArtRef], never a URL with the sign-in in it. */
    val artworkUri: String?,
)

/** getAlbumList2's `type`s this app offers as album sorts. */
enum class SubsonicAlbumSort(val apiType: String) {
    NAME("alphabeticalByName"),
    ARTIST("alphabeticalByArtist"),
    NEWEST("newest"),
    RECENT("recent"),
    FREQUENT("frequent"),
    RANDOM("random"),
}
