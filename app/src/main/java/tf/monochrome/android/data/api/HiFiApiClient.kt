package tf.monochrome.android.data.api

import android.util.Base64
import android.util.LruCache
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.data.api.model.AlbumResponse
import tf.monochrome.android.data.api.model.AlbumTrackItem
import tf.monochrome.android.data.api.model.ArtistContentResponse
import tf.monochrome.android.data.api.model.ArtistResponse
import tf.monochrome.android.data.api.model.LyricsResponse
import tf.monochrome.android.data.api.model.ManifestJson
import tf.monochrome.android.data.api.model.PlaylistResponse
import tf.monochrome.android.data.api.model.QobuzAlbumDetailEnvelope
import tf.monochrome.android.data.api.model.QobuzAlbumItem
import tf.monochrome.android.data.api.model.QobuzArtistDetail
import tf.monochrome.android.data.api.model.QobuzArtistDetailEnvelope
import tf.monochrome.android.data.api.model.QobuzArtistImages
import tf.monochrome.android.data.api.model.QobuzArtistItem
import tf.monochrome.android.data.api.model.QobuzArtistRef
import tf.monochrome.android.data.api.model.QobuzArtistTopTrack
import tf.monochrome.android.data.api.model.QobuzDownloadEnvelope
import tf.monochrome.android.data.api.model.QobuzNamedRef
import tf.monochrome.android.data.api.model.QobuzPerson
import tf.monochrome.android.data.api.model.QobuzRelease
import tf.monochrome.android.data.api.model.QobuzSearchEnvelope
import tf.monochrome.android.data.api.model.QobuzSimilarArtist
import tf.monochrome.android.data.api.model.QobuzTrackItem
import tf.monochrome.android.data.api.model.RecommendationsResponse
import tf.monochrome.android.data.api.model.SearchResponse
import tf.monochrome.android.data.api.model.TrackInfoResponse
import tf.monochrome.android.data.api.model.TrackStreamResponse
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.TrackStream
import tf.monochrome.android.domain.model.ArtistDetail
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.PlaylistCreator
import tf.monochrome.android.domain.model.ReplayGainValues
import tf.monochrome.android.domain.model.SearchResult
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.util.RomajiConverter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Minimal Qobuz match used by the TIDAL→Qobuz playback fallback. */
data class QobuzTrackMatch(
    val trackId: Long,
    val albumSlug: String?,
    val artistId: Long?,
)

@Singleton
class HiFiApiClient @Inject constructor(
    private val instanceManager: InstanceManager,
    private val httpClient: HttpClient,
    private val json: Json,
    private val preferences: tf.monochrome.android.data.preferences.PreferencesManager,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {
    private val cache = LruCache<String, CacheEntry>(200)

    companion object {
        // Hard ceiling per Qobuz sub-request so a slow or unreachable Qobuz
        // instance can't stall the search UI behind coroutineScope's
        // wait-for-all-children semantics.
        private const val QOBUZ_REQUEST_TIMEOUT_MS = 6_000L
    }

    private data class CacheEntry(
        val data: Any,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isValid(ttlMs: Long = 30 * 60 * 1000L): Boolean {
            return System.currentTimeMillis() - timestamp < ttlMs
        }
    }

    private suspend fun fetchWithRetry(
        path: String,
        instanceType: InstanceType = InstanceType.API,
        minVersion: String? = null
    ): String {
        val instances = instanceManager.getInstances(instanceType)
            .let { list ->
                if (minVersion != null) {
                    list.filter { instance ->
                        instance.version == null ||
                            (instance.version.toDoubleOrNull() ?: 0.0) >= (minVersion.toDoubleOrNull() ?: 0.0)
                    }.ifEmpty { list }
                } else list
            }

        if (instances.isEmpty()) throw Exception("No API instances available")

        var lastError: Throwable? = null
        val maxAttempts = instances.size * 2
        var instanceIndex = Random.nextInt(instances.size)

        repeat(maxAttempts) {
            val instance = instances[instanceIndex % instances.size]
            val url = instance.url.trimEnd('/') + path

            try {
                val response = httpClient.get(url)
                when {
                    response.status.value == 429 -> {
                        instanceIndex++
                        delay(500)
                    }
                    response.status.value in 500..599 -> {
                        instanceIndex++
                    }
                    response.status.value == 401 -> {
                        instanceIndex++
                    }
                    response.status.isSuccess() -> {
                        val body = response.bodyAsText()
                        // Reject HTML payloads — most often the user has Dev
                        // Mode pointed at a SPA (e.g. trypt-hifi root) that
                        // serves index.html for any unknown path. Surfacing
                        // the raw HTML to a JSON parser produces a confusing
                        // 'Unexpected JSON token at offset 0' error in the
                        // detail screens; treat it as an instance failure
                        // and fall through to the next one. Sniff only the
                        // first non-whitespace char so we don't allocate a
                        // copy of large bodies just to check.
                        val sniff = body.asSequence().take(64)
                            .dropWhile { it.isWhitespace() }
                            .firstOrNull()
                        if (sniff == '<') {
                            lastError = Exception(
                                "Instance returned HTML instead of JSON " +
                                    "(check Settings → Instances → Dev Mode URL)"
                            )
                            instanceIndex++
                        } else {
                            return body
                        }
                    }
                    else -> {
                        lastError = Exception("HTTP ${response.status.value}")
                        instanceIndex++
                    }
                }
            } catch (e: Exception) {
                lastError = e
                instanceIndex++
                delay(200)
            }
        }

        throw lastError ?: Exception("All instances failed for $path")
    }

    // --- Search ---

    // Page size used for initial search + each loadMore call. Backends that
    // don't honour &offset=&limit= will respond with the same first-page
    // payload; the ViewModel detects "end" when fewer than `limit` items come
    // back, so an unsupported page just stops paging early.
    private fun pagedQuery(key: String, query: String, offset: Int, limit: Int): String =
        "/search/?$key=${query.encodeUrl()}&offset=$offset&limit=$limit"

    suspend fun searchTracks(query: String, offset: Int = 0, limit: Int = 50): List<Track> {
        val cacheKey = "search_tracks_${query}_${offset}_${limit}"
        cache.get(cacheKey)?.let { entry ->
            if (entry.isValid()) {
                @Suppress("UNCHECKED_CAST")
                return entry.data as List<Track>
            }
        }

        val body = fetchWithRetry(pagedQuery("s", query, offset, limit))
        val response = parseSearchResponse(body)
        val tracks = response.items.map { it.toTrack() }
        cache.put(cacheKey, CacheEntry(tracks))
        return tracks
    }

    suspend fun searchAlbums(query: String, offset: Int = 0, limit: Int = 50): List<Album> {
        val cacheKey = "search_albums_${query}_${offset}_${limit}"
        cache.get(cacheKey)?.let { entry ->
            if (entry.isValid()) {
                @Suppress("UNCHECKED_CAST")
                return entry.data as List<Album>
            }
        }

        val body = fetchWithRetry(pagedQuery("al", query, offset, limit))
        val response = parseSearchResponse(body)
        val albums = response.items.map { it.toAlbum() }
        cache.put(cacheKey, CacheEntry(albums))
        return albums
    }

    suspend fun searchArtists(query: String, offset: Int = 0, limit: Int = 50): List<Artist> {
        val cacheKey = "search_artists_${query}_${offset}_${limit}"
        cache.get(cacheKey)?.let { entry ->
            if (entry.isValid()) {
                @Suppress("UNCHECKED_CAST")
                return entry.data as List<Artist>
            }
        }

        val body = fetchWithRetry(pagedQuery("a", query, offset, limit))
        val response = parseSearchResponse(body)
        val artists = response.items.map { it.toArtist() }
        cache.put(cacheKey, CacheEntry(artists))
        return artists
    }

    suspend fun searchPlaylists(query: String, offset: Int = 0, limit: Int = 50): List<Playlist> {
        val body = fetchWithRetry(pagedQuery("p", query, offset, limit))
        val response = parseSearchResponse(body)
        return response.items.map { it.toPlaylist() }
    }

    suspend fun search(query: String, offset: Int = 0, limit: Int = 50): SearchResult {
        return SearchResult(
            tracks = runCatching { searchTracks(query, offset, limit) }.getOrDefault(emptyList()),
            albums = runCatching { searchAlbums(query, offset, limit) }.getOrDefault(emptyList()),
            artists = runCatching { searchArtists(query, offset, limit) }.getOrDefault(emptyList()),
            playlists = runCatching { searchPlaylists(query, offset, limit) }.getOrDefault(emptyList())
        )
    }

    // Qobuz catalog search — hits the user-configured trypt-hifi instance at
    // GET /api/get-music?q=<query>&offset=<n>. Response envelope shape is
    // { success, data: { albums?: { items: [...] }, tracks?: ..., artists?:
    // ... } }. Whichever section the user's tab targeted is populated; the
    // others are absent. We accept all three so a single parser works.
    //
    // Returns an empty SearchResult when the instance isn't set, the request
    // times out, or the response can't be parsed, so the TIDAL search flow
    // is never blocked by Qobuz state.
    suspend fun searchQobuz(query: String, offset: Int = 0): SearchResult {
        val instance = instanceManager.qobuzInstanceOrNull() ?: return SearchResult()
        val base = instance.url.trimEnd('/')

        val envelope = withTimeoutOrNull(QOBUZ_REQUEST_TIMEOUT_MS) {
            runCatching {
                val res = httpClient.get("$base/api/get-music?q=${query.encodeUrl()}&offset=$offset")
                if (!res.status.isSuccess()) return@runCatching null
                json.decodeFromString<QobuzSearchEnvelope>(res.bodyAsText())
            }.getOrNull()
        } ?: return SearchResult()

        if (!envelope.success || envelope.data == null) return SearchResult()
        val data = envelope.data

        // Record (qobuz_id -> alphanumeric slug) so the detail VM can look the
        // slug back up at navigation time. Numeric artist ids are also tagged
        // as Qobuz so ArtistDetailViewModel can route appropriately. Tracks
        // also carry an album object — register those slugs too so navigation
        // from a track row resolves correctly.
        data.albums?.items?.forEach { registerAlbumWithRegistry(it) }
        data.tracks?.items?.forEach { item ->
            item.id?.let { qobuzIdRegistry.registerTrack(it) }
            item.album?.let { album -> registerAlbumWithRegistry(album) }
        }
        data.artists?.items?.forEach { item -> item.id?.let { qobuzIdRegistry.registerArtist(it) } }

        return SearchResult(
            tracks = data.tracks?.items?.map { it.toDomainTrack() } ?: emptyList(),
            albums = data.albums?.items?.map { it.toDomainAlbum() } ?: emptyList(),
            artists = data.artists?.items?.map { it.toDomainArtist() } ?: emptyList(),
            playlists = emptyList(),
        )
    }

    /**
     * Qobuz album detail — GET /api/get-album?album_id=<alphanumeric-slug>.
     * The response is the standard {success, data: …} envelope; data has all
     * the QobuzAlbumItem fields plus a `tracks: { items: [...] }` block with
     * the album's full track list. Returns null when the Qobuz instance
     * isn't configured, the request fails, or the body can't be parsed.
     */
    suspend fun getQobuzAlbum(albumSlug: String): AlbumDetail? {
        val instance = instanceManager.qobuzInstanceOrNull() ?: return null
        if (albumSlug.isBlank()) return null
        val base = instance.url.trimEnd('/')

        val envelope = withTimeoutOrNull(QOBUZ_REQUEST_TIMEOUT_MS) {
            runCatching {
                val res = httpClient.get(
                    "$base/api/get-album?album_id=${albumSlug.encodeUrl()}"
                )
                if (!res.status.isSuccess()) return@runCatching null
                json.decodeFromString<QobuzAlbumDetailEnvelope>(res.bodyAsText())
            }.getOrNull()
        } ?: return null

        if (!envelope.success || envelope.data == null) return null
        val albumItem = envelope.data
        registerAlbumWithRegistry(albumItem)
        val album = albumItem.toDomainAlbum()
        val tracks = albumItem.tracks?.items?.map { item ->
            // Tag every track id as Qobuz so PlayerViewModel.resolveAndPlay
            // routes it through the QobuzCached path rather than the TIDAL
            // streaming fallback.
            item.id?.let { qobuzIdRegistry.registerTrack(it) }
            // Detail responses don't repeat the album object inside each track
            // — provide it explicitly so playback / display has full context.
            item.toDomainTrack(fallbackAlbum = album)
        } ?: emptyList()
        return AlbumDetail(album = album, tracks = tracks)
    }

    /**
     * Qobuz artist detail — GET /api/get-artist?artist_id=<numeric-id>.
     *
     * Response shape is materially different from search/album:
     *   {success, data: {artist: {id, name:{display}, biography, images:
     *     {portrait:{hash, format}}, top_tracks:[…], similar_artists:
     *     {has_more, items:[…]}}}}
     *
     * Album lists per artist aren't part of this payload — the SPA likely
     * loads them via a separate call. Until that contract is known, the
     * mapped ArtistDetail leaves albums/eps/singles empty and populates
     * topTracks + similarArtists.
     */
    suspend fun getQobuzArtist(artistId: Long): ArtistDetail? {
        val instance = instanceManager.qobuzInstanceOrNull() ?: return null
        val base = instance.url.trimEnd('/')

        val envelope = withTimeoutOrNull(QOBUZ_REQUEST_TIMEOUT_MS) {
            runCatching {
                val res = httpClient.get("$base/api/get-artist?artist_id=$artistId")
                if (!res.status.isSuccess()) return@runCatching null
                json.decodeFromString<QobuzArtistDetailEnvelope>(res.bodyAsText())
            }.getOrNull()
        } ?: return null

        if (!envelope.success || envelope.data?.artist == null) return null
        val raw = envelope.data.artist

        // Register top_track album slugs so clicking through to an album
        // from the artist screen still resolves via QobuzIdRegistry.
        // Also tag each top_track id as Qobuz so playback routes through
        // QobuzCached instead of falling through to TIDAL streaming.
        raw.topTracks.forEach { topTrack ->
            topTrack.id?.let { qobuzIdRegistry.registerTrack(it) }
            topTrack.album?.let { album -> registerAlbumWithRegistry(album) }
        }

        val artist = Artist(
            id = raw.id ?: artistId,
            name = raw.name?.display ?: "",
            picture = raw.images?.portraitUrl(),
        )
        val topTracks = raw.topTracks.mapNotNull { it.toDomainTrack() }
        val similar = raw.similarArtists?.items?.mapNotNull { it.toDomainArtist() } ?: emptyList()

        // Map the artist's full discography (the `releases` groups) into
        // Albums / EPs / Singles so every release — and therefore every track —
        // is reachable from the artist screen. Each release item is a
        // QobuzAlbumItem; register its slug so AlbumDetail can resolve it.
        val albums = mutableListOf<tf.monochrome.android.domain.model.Album>()
        val eps = mutableListOf<tf.monochrome.android.domain.model.Album>()
        val singles = mutableListOf<tf.monochrome.android.domain.model.Album>()
        raw.releases.forEach { group ->
            group.items.forEach { item ->
                registerReleaseWithRegistry(item)
                val album = item.toDomainAlbum()
                when (group.type) {
                    "epSingle" -> if ((item.tracksCount ?: 0) <= 1) singles.add(album) else eps.add(album)
                    "album", "live", "compilation" -> albums.add(album)
                    else -> { /* download / awardedRelease / next — skip */ }
                }
            }
        }

        return ArtistDetail(
            artist = artist,
            topTracks = topTracks,
            albums = albums,
            eps = eps,
            singles = singles,
            unreleasedTracks = emptyList(),
            similarArtists = similar,
        )
    }

    /**
     * Fetch a TIDAL track's ISRC from the metadata pool (InstanceType.API,
     * separate from STREAMING) — so it's usually obtainable even when the
     * track's *stream* can't be resolved, which is exactly when the Qobuz
     * fallback needs it.
     */
    suspend fun getTidalIsrc(trackId: Long): String? = runCatching {
        val infoBody = fetchWithRetry("/info/?id=$trackId")
        json.decodeFromString<TrackInfoResponse>(unwrapResponse(infoBody)).isrc?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Resolve the Qobuz match for an ISRC. Qobuz indexes tracks by ISRC, so
     * /api/get-music?q=<isrc> returns the exact recording — far more reliable
     * than a title/artist match. Also carries the Qobuz album slug and artist
     * id so the playback fallback can bridge "Go to album/artist". Returns null
     * when Qobuz isn't configured or doesn't carry that ISRC.
     */
    suspend fun findQobuzTrackByIsrc(isrc: String): QobuzTrackMatch? {
        if (isrc.isBlank()) return null
        val instance = instanceManager.qobuzInstanceOrNull() ?: return null
        val base = instance.url.trimEnd('/')
        val envelope = withTimeoutOrNull(QOBUZ_REQUEST_TIMEOUT_MS) {
            runCatching {
                val res = httpClient.get("$base/api/get-music?q=${isrc.encodeUrl()}")
                if (!res.status.isSuccess()) return@runCatching null
                json.decodeFromString<QobuzSearchEnvelope>(res.bodyAsText())
            }.getOrNull()
        } ?: return null
        val items = envelope.data?.tracks?.items ?: return null
        val match = items.firstOrNull { it.isrc?.equals(isrc, ignoreCase = true) == true } ?: return null
        val id = match.id ?: return null
        qobuzIdRegistry.registerTrack(id)
        match.album?.let { registerAlbumWithRegistry(it) }
        return QobuzTrackMatch(
            trackId = id,
            albumSlug = match.album?.id,
            artistId = match.performer?.id,
        )
    }

    // Side-channel registry update for any QobuzAlbumItem we decode. Album.id
    // produced by toDomainAlbum is qobuz_id when available and slug.hashCode()
    // otherwise — register that exact value so AlbumDetailViewModel's
    // registry lookup always resolves regardless of which path produced the id.
    private fun registerAlbumWithRegistry(item: QobuzAlbumItem) {
        val albumId = item.qobuzId ?: item.id?.hashCode()?.toLong()
        val slug = item.id
        if (albumId != null && !slug.isNullOrBlank()) {
            qobuzIdRegistry.registerAlbum(albumId, slug)
        }
    }

    // Same registry side-channel for artist-page release items.
    private fun registerReleaseWithRegistry(item: QobuzRelease) {
        val albumId = item.qobuzId ?: item.id?.hashCode()?.toLong()
        val slug = item.id
        if (albumId != null && !slug.isNullOrBlank()) {
            qobuzIdRegistry.registerAlbum(albumId, slug)
        }
    }

    // --- Album ---

    suspend fun getAlbum(albumId: Long): AlbumDetail {
        val cacheKey = "album_$albumId"
        cache.get(cacheKey)?.let { entry ->
            if (entry.isValid()) return entry.data as AlbumDetail
        }

        val body = fetchWithRetry("/album/?id=$albumId")
        val response = json.decodeFromString<AlbumResponse>(unwrapResponse(body))

        val album = Album(
            id = response.id,
            title = response.title,
            artist = response.artist?.toDomain(),
            artists = response.artists.map { it.toDomain() },
            numberOfTracks = response.numberOfTracks,
            releaseDate = response.releaseDate,
            cover = response.cover,
            explicit = response.explicit,
            type = response.type,
            duration = response.duration
        )

        val trackItems = response.tracks?.items ?: response.items ?: emptyList()
        var tracks = trackItems.map { it.toTrack(album) }

        // Handle pagination for large albums
        val total = response.tracks?.totalNumberOfItems ?: response.numberOfTracks ?: tracks.size
        if (tracks.size < total) {
            val remaining = mutableListOf<Track>()
            var offset = tracks.size
            while (offset < total) {
                val pageBody = fetchWithRetry("/album/?id=$albumId&offset=$offset&limit=100")
                val pageResponse = json.decodeFromString<AlbumResponse>(unwrapResponse(pageBody))
                val pageItems = pageResponse.tracks?.items ?: pageResponse.items ?: emptyList()
                remaining.addAll(pageItems.map { it.toTrack(album) })
                offset += pageItems.size
                if (pageItems.isEmpty()) break
            }
            tracks = tracks + remaining
        }

        val detail = AlbumDetail(album = album, tracks = tracks)
        cache.put(cacheKey, CacheEntry(detail))
        return detail
    }

    // --- Artist ---

    suspend fun getArtist(artistId: Long): ArtistDetail {
        val cacheKey = "artist_$artistId"
        cache.get(cacheKey)?.let { entry ->
            if (entry.isValid()) return entry.data as ArtistDetail
        }

        // Fetch artist info
        val infoBody = fetchWithRetry("/artist/?id=$artistId")
        val artistResponse = json.decodeFromString<ArtistResponse>(unwrapResponse(infoBody))
        val artist = Artist(
            id = artistResponse.id,
            name = artistResponse.name,
            picture = artistResponse.picture,
            artistTypes = artistResponse.artistTypes
        )

        // Fetch artist content (albums, tracks)
        val contentBody = fetchWithRetry("/artist/?f=$artistId&skip_tracks=true")
        val contentResponse = json.decodeFromString<ArtistContentResponse>(unwrapResponse(contentBody))

        val allAlbums = (contentResponse.albums?.items ?: contentResponse.items?.map { item ->
            tf.monochrome.android.data.api.model.ApiAlbum(
                id = item.id,
                title = item.title,
                artist = item.artist,
                artists = item.artists,
                numberOfTracks = item.numberOfTracks,
                releaseDate = item.releaseDate,
                cover = item.cover,
                explicit = item.explicit,
                type = item.type
            )
        } ?: emptyList()).map { it.toDomain() }

        val albums = allAlbums.filter { it.type?.equals("ALBUM", ignoreCase = true) != false }
        val eps = allAlbums.filter { it.type?.equals("EP", ignoreCase = true) == true }
        val singles = allAlbums.filter { it.type?.equals("SINGLE", ignoreCase = true) == true }

        val topTracks = contentResponse.topTracks?.items?.map { it.toDomain() } ?: emptyList()

        // Try fetching similar artists
        val similarArtists = try {
            val similarBody = fetchWithRetry("/artist/similar/?id=$artistId", minVersion = "2.3")
            val items = json.decodeFromString<SearchResponse>(unwrapResponse(similarBody))
            items.items.map { it.toArtist() }
        } catch (_: Exception) {
            emptyList()
        }

        // Fetch unreleased/community tracks (ArtistGrid)
        val unreleasedTracks = try {
            val unreleasedBody = fetchWithRetry("/artist/unreleased/?id=$artistId", minVersion = "2.5")
            val items = json.decodeFromString<SearchResponse>(unwrapResponse(unreleasedBody))
            items.items.map { it.toTrack() }
        } catch (_: Exception) {
            emptyList()
        }

        val detail = ArtistDetail(
            artist = artist,
            topTracks = topTracks,
            albums = albums,
            eps = eps,
            singles = singles,
            unreleasedTracks = unreleasedTracks,
            similarArtists = similarArtists
        )
        cache.put(cacheKey, CacheEntry(detail))
        return detail
    }

    // --- Playlist ---

    suspend fun getPlaylist(playlistId: String): Playlist {
        val body = fetchWithRetry("/playlist/?id=$playlistId")
        val response = json.decodeFromString<PlaylistResponse>(unwrapResponse(body))

        val trackItems = response.items ?: response.tracks ?: emptyList()
        var tracks = trackItems.mapNotNull { it.toDomain() }

        // Handle pagination
        val total = response.numberOfTracks ?: tracks.size
        if (tracks.size < total) {
            var offset = tracks.size
            while (offset < total) {
                val pageBody = fetchWithRetry("/playlist/?id=$playlistId&offset=$offset")
                val pageResponse = json.decodeFromString<PlaylistResponse>(unwrapResponse(pageBody))
                val pageItems = pageResponse.items ?: pageResponse.tracks ?: emptyList()
                tracks = tracks + pageItems.mapNotNull { it.toDomain() }
                offset += pageItems.size
                if (pageItems.isEmpty()) break
            }
        }

        return Playlist(
            uuid = response.uuid,
            title = response.title,
            description = response.description,
            numberOfTracks = response.numberOfTracks,
            duration = response.duration,
            cover = response.cover ?: response.squareImage ?: response.image,
            creator = response.creator?.let { PlaylistCreator(it.id, it.name) },
            tracks = tracks
        )
    }

    // --- Streaming ---

    suspend fun getTrackStream(
        trackId: Long,
        quality: AudioQuality,
        forDownload: Boolean = false
    ): TrackStream {
        // Downloads route through the project-private trypt-hifi (Qobuz) API
        // when configured. The frontend's network trace shows the real call
        // is GET /api/download-music?track_id=<id>&quality=<qobuz_code>, which
        // returns a JSON envelope containing a short-lived HMAC-signed
        // /api/file?... URL we then stream the bytes from.
        if (forDownload) {
            val qobuzUrl = runCatching { resolveQobuzDownloadUrl(trackId, quality) }.getOrNull()
            if (qobuzUrl != null) {
                return TrackStream(
                    track = Track(id = trackId, title = "", duration = 0),
                    streamUrl = qobuzUrl,
                    isDash = false,
                    replayGain = ReplayGainValues()
                )
            }
            // Qobuz unset or upstream returned nothing — fall through to the
            // existing TIDAL streaming path so the user still gets bytes.
        }

        val body = fetchWithRetry(
            "/track/?id=$trackId&quality=${quality.apiValue}",
            instanceType = InstanceType.STREAMING
        )
        val streamResponse = json.decodeFromString<TrackStreamResponse>(unwrapResponse(body))

        val streamUrl = extractStreamUrlFromManifest(streamResponse.manifest)
        val isDash = streamUrl?.contains("<MPD") == true || streamUrl?.endsWith(".mpd") == true

        if (streamUrl == null) {
            // Fallback to lower quality
            if (quality == AudioQuality.HI_RES) {
                return getTrackStream(trackId, AudioQuality.LOSSLESS, forDownload)
            }
            throw Exception("Could not extract stream URL for track $trackId")
        }

        // Get track info for metadata
        val trackInfo = try {
            val infoBody = fetchWithRetry("/info/?id=$trackId")
            json.decodeFromString<TrackInfoResponse>(unwrapResponse(infoBody))
        } catch (_: Exception) {
            null
        }

        val track = Track(
            id = trackId,
            title = trackInfo?.title ?: "",
            duration = trackInfo?.duration ?: 0,
            artist = trackInfo?.artist?.toDomain(),
            artists = trackInfo?.artists?.map { it.toDomain() } ?: emptyList(),
            album = trackInfo?.album?.toDomain(),
            audioQuality = trackInfo?.audioQuality,
            explicit = trackInfo?.explicit ?: false,
            trackNumber = trackInfo?.trackNumber,
            volumeNumber = trackInfo?.volumeNumber
        )

        return TrackStream(
            track = track,
            streamUrl = streamUrl,
            isDash = isDash,
            replayGain = ReplayGainValues(
                trackReplayGain = streamResponse.trackReplayGain,
                trackPeakAmplitude = streamResponse.trackPeakAmplitude,
                albumReplayGain = streamResponse.albumReplayGain,
                albumPeakAmplitude = streamResponse.albumPeakAmplitude
            )
        )
    }

    // --- Recommendations ---

    suspend fun getRecommendations(trackId: Long): List<Track> {
        val body = fetchWithRetry("/recommendations/?id=$trackId", minVersion = "2.4")
        val response = json.decodeFromString<RecommendationsResponse>(unwrapResponse(body))
        return response.items.map { apiTrack ->
            // Recommendations may return incomplete metadata, so fetch full info
            if (apiTrack.title.isBlank() && apiTrack.id != 0L) {
                try {
                    val infoBody = fetchWithRetry("/info/?id=${apiTrack.id}")
                    val info = json.decodeFromString<TrackInfoResponse>(unwrapResponse(infoBody))
                    Track(
                        id = apiTrack.id,
                        title = info.title,
                        duration = info.duration,
                        artist = info.artist?.toDomain(),
                        artists = info.artists.map { it.toDomain() },
                        album = info.album?.toDomain(),
                        audioQuality = info.audioQuality,
                        explicit = info.explicit,
                        trackNumber = info.trackNumber,
                        volumeNumber = info.volumeNumber
                    )
                } catch (_: Exception) {
                    apiTrack.toDomain()
                }
            } else {
                apiTrack.toDomain()
            }
        }
    }

    // --- Lyrics ---

    suspend fun getLyrics(trackId: Long, convertToRomaji: Boolean = false): Lyrics? {
        // The /lyrics endpoint is keyed by TIDAL track id. Qobuz ids live in a
        // separate namespace, so querying TIDAL with one either 404s or — worse
        // — returns a *different* track's synced lyrics: right-looking text that
        // is never in sync. Skip TIDAL for known Qobuz ids; callers fall back to
        // the metadata-based LRCLib lookup instead.
        if (qobuzIdRegistry.isQobuzTrack(trackId)) return null
        return try {
            val body = fetchWithRetry("/lyrics/?id=$trackId")
            val response = json.decodeFromString<LyricsResponse>(unwrapResponse(body))
            parseLyrics(response, convertToRomaji)
        } catch (_: Exception) {
            null
        }
    }

    // --- Helper Methods ---

    fun extractStreamUrlFromManifest(manifest: String?): String? {
        if (manifest == null) return null

        // Try base64 decode
        val decoded = try {
            String(Base64.decode(manifest, Base64.DEFAULT))
        } catch (_: Exception) {
            manifest
        }

        // Check if DASH XML
        if (decoded.contains("<MPD")) {
            return decoded // Return raw MPD for ExoPlayer DashMediaSource
        }

        // Try JSON parse
        try {
            val manifestJson = json.decodeFromString<ManifestJson>(decoded)
            if (manifestJson.urls.isNotEmpty()) {
                return manifestJson.urls.sortedByQuality().first()
            }
        } catch (_: Exception) {
            // Not JSON
        }

        // Try as plain URL
        val urlRegex = Regex("https?://[\\w\\-.~:/?#\\[\\]@!$&'()*+,;=%]+")
        urlRegex.find(decoded)?.let { return it.value }

        return null
    }

    private fun List<String>.sortedByQuality(): List<String> {
        val keywords = listOf("flac", "lossless", "hi-res", "high")
        return sortedBy { url ->
            val lower = url.lowercase()
            keywords.indexOfFirst { lower.contains(it) }.let { if (it >= 0) it else 999 }
        }
    }

    private fun unwrapResponse(body: String): String {
        // Some API responses wrap data in { data: ... }
        return try {
            val element = json.parseToJsonElement(body)
            if (element is kotlinx.serialization.json.JsonObject && element.containsKey("data")) {
                element["data"].toString()
            } else {
                body
            }
        } catch (_: Exception) {
            body
        }
    }

    private fun parseSearchResponse(body: String): SearchResponse {
        val unwrapped = unwrapResponse(body)
        return try {
            json.decodeFromString<SearchResponse>(unwrapped)
        } catch (_: Exception) {
            // Try parsing as array
            try {
                val items = json.decodeFromString<List<tf.monochrome.android.data.api.model.SearchItem>>(unwrapped)
                SearchResponse(items = items)
            } catch (_: Exception) {
                SearchResponse()
            }
        }
    }

    private fun parseLyrics(response: LyricsResponse, convertToRomaji: Boolean): Lyrics? {
        val subtitles = response.subtitles ?: return response.lyrics?.let { raw ->
            val text = if (convertToRomaji) RomajiConverter.convert(raw) else raw
            Lyrics(lines = text.split("\n").map { line -> LyricLine(0, line) }, isSynced = false)
        }

        // Parse LRC-style subtitles (including enhanced word-level [mm:ss.ms]<mm:ss.ms>word)
        val lines = mutableListOf<LyricLine>()
        val lrcLineRegex = Regex("\\[(\\d+):(\\d+\\.\\d+)](.*)")
        val lrcWordRegex = Regex("<(\\d+):(\\d+\\.\\d+)>([^<]*)")

        subtitles.split("\n").forEach { rawLine ->
            lrcLineRegex.find(rawLine)?.let { lineMatch ->
                val minutes = lineMatch.groupValues[1].toLongOrNull() ?: 0
                val seconds = lineMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                val lineTimeMs = (minutes * 60 * 1000) + (seconds * 1000).toLong()
                val lineContent = lineMatch.groupValues[3]

                // Extract word-level sync if present. In enhanced LRC each
                // `<mm:ss.cs>` tag marks where ITS word starts, so word_i spans
                // [t_i, t_{i+1}). The previous logic used the prior tag as the
                // start, which shifted every word one slot early (and gave the
                // first word a zero-width span) — i.e. karaoke was always out of
                // sync. Pair each tag with the next tag's time as its end.
                val rawWords = lrcWordRegex.findAll(lineContent).map { wordMatch ->
                    val wMinutes = wordMatch.groupValues[1].toLongOrNull() ?: 0
                    val wSeconds = wordMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                    val wordTimeMs = (wMinutes * 60 * 1000) + (wSeconds * 1000).toLong()
                    var wordText = wordMatch.groupValues[3].trim()
                    if (convertToRomaji) wordText = RomajiConverter.convert(wordText)
                    wordTimeMs to wordText
                }.filter { it.second.isNotBlank() }.toList()

                val words = rawWords.mapIndexed { i, (startMs, wordText) ->
                    val endMs = rawWords.getOrNull(i + 1)?.first ?: (startMs + 2000)
                    LyricWord(startMs = startMs, endMs = endMs, text = wordText)
                }

                var finalText = if (words.isEmpty()) lineContent.trim() else words.joinToString(" ") { it.text }
                if (convertToRomaji && words.isEmpty()) finalText = RomajiConverter.convert(finalText)

                if (finalText.isNotBlank()) {
                    lines.add(LyricLine(timeMs = lineTimeMs, text = finalText, words = words))
                }
            }
        }

        return if (lines.isNotEmpty()) {
            Lyrics(lines = lines, isSynced = true)
        } else {
            val raw = subtitles.split("\n")
            Lyrics(
                lines = raw.map { LyricLine(0, if (convertToRomaji) RomajiConverter.convert(it) else it) },
                isSynced = false
            )
        }
    }

    private fun String.encodeUrl(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }

    // --- Qobuz (trypt-hifi) download resolution -----------------------------
    //
    // The trypt-hifi frontend at https://<host>/ ships a SPA, but the same
    // origin also exposes a JSON API under /api/. The download flow seen in
    // the frontend's network trace is:
    //
    //   1. GET /api/download-music?track_id=<id>&quality=<qobuz_code>
    //        → small JSON envelope with a stream URL (per-request HMAC-signed)
    //   2. GET that returned URL  → audio bytes (FLAC for quality 6/7/27,
    //                                MP3 for 5)
    //
    // Step 2 is just a regular byte download, which DownloadWorker already
    // handles. So all we need to do is resolve step 1 and return the URL.

    // Public entry point for callers (DownloadWorker via getTrackStream, the
    // streaming cache manager, etc.) that need to resolve a Qobuz file URL
    // without the TIDAL fallthrough getTrackStream(forDownload=true) does.
    suspend fun getQobuzDownloadUrl(trackId: Long, quality: AudioQuality): String? =
        resolveQobuzDownloadUrl(trackId, quality)

    private suspend fun resolveQobuzDownloadUrl(trackId: Long, quality: AudioQuality): String? {
        val instance = instanceManager.qobuzInstanceOrNull() ?: return null
        val base = instance.url.trimEnd('/')
        val code = quality.qobuzCode()
        val response = httpClient.get("$base/api/download-music?track_id=$trackId&quality=$code")
        if (!response.status.isSuccess()) return null
        val body = response.bodyAsText()
        val resolved = extractQobuzFileUrlFromEnvelope(body, base)
            ?: extractQobuzFileUrl(body, base)
        return resolved
            // Hi-Res isn't always available on Qobuz — fall back to lossless
            // exactly like the TIDAL path does, instead of failing the
            // download.
            ?: if (quality == AudioQuality.HI_RES) {
                resolveQobuzDownloadUrl(trackId, AudioQuality.LOSSLESS)
            } else null
    }

    private fun extractQobuzFileUrlFromEnvelope(body: String, base: String): String? {
        val parsed = runCatching { json.decodeFromString<QobuzDownloadEnvelope>(body) }.getOrNull()
            ?: return null
        if (!parsed.success && parsed.data == null && parsed.url == null
            && parsed.fileUrl == null && parsed.streamUrl == null && parsed.downloadUrl == null) {
            return null
        }
        val candidate = parsed.url
            ?: parsed.fileUrl
            ?: parsed.streamUrl
            ?: parsed.downloadUrl
            ?: parsed.data?.url
            ?: parsed.data?.fileUrl
            ?: parsed.data?.streamUrl
            ?: parsed.data?.downloadUrl
        return candidate?.let { absoluteUrl(it, base) }
    }

    // Qobuz exposes four quality codes; map our 4-tier AudioQuality onto them.
    // Reference: 5 = MP3 320kbps, 6 = FLAC 16/44.1, 7 = FLAC 24/96, 27 = FLAC
    // hi-res. Quality 27 was the one observed in the captured request.
    private fun AudioQuality.qobuzCode(): Int = when (this) {
        AudioQuality.LOW, AudioQuality.HIGH -> 5
        AudioQuality.LOSSLESS -> 6
        AudioQuality.HI_RES -> 27
    }

    // Defensive parser — the response body is short JSON but the field name
    // hasn't been verified, so we try a handful of common shapes (top-level
    // url, file_url, stream_url, download_url, nested data.url) and finally
    // fall back to a regex scan for any /api/file? URL hosted on the same
    // instance. Returns null if no candidate is found.
    private fun extractQobuzFileUrl(body: String, base: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val direct = (parsed as? JsonObject)?.let { obj ->
            obj["url"]?.jsonPrimitive?.contentOrNull
                ?: obj["file_url"]?.jsonPrimitive?.contentOrNull
                ?: obj["stream_url"]?.jsonPrimitive?.contentOrNull
                ?: obj["download_url"]?.jsonPrimitive?.contentOrNull
                ?: (obj["data"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
        } ?: (parsed as? JsonPrimitive)?.contentOrNull
        if (!direct.isNullOrBlank()) return absoluteUrl(direct, base)

        // Regex fallback — match either the full URL or the relative path
        // and resolve against the instance base. Trace showed the frontend's
        // file URL begins with "file?uid=...&eid=...&hmac=...", so we accept
        // both /api/file?... and bare file?... shapes.
        val urlMatch = Regex("https?://[^\"\\s]*?/api/file\\?[^\"\\s]+").find(body)?.value
        if (urlMatch != null) return urlMatch
        val pathMatch = Regex("/?(?:api/)?file\\?[^\"\\s]+").find(body)?.value
        return pathMatch?.let { absoluteUrl(it, base) }
    }

    private fun absoluteUrl(value: String, base: String): String {
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/api/") -> base + value
            value.startsWith("/") -> "$base/api${value}".replace("/api/api/", "/api/")
            else -> "$base/api/$value"
        }
    }

}

// --- Qobuz item → domain mappers (file-private extensions) -----------------

private fun QobuzRelease.toDomainAlbum(): tf.monochrome.android.domain.model.Album {
    val cover = image?.large ?: image?.small ?: image?.thumbnail
    val typeLabel = if ((tracksCount ?: 0) <= 4) "EP" else "ALBUM"
    return tf.monochrome.android.domain.model.Album(
        id = qobuzId ?: id?.hashCode()?.toLong() ?: 0L,
        title = listOfNotNull(title.takeIf { it.isNotBlank() }, version?.takeIf { it.isNotBlank() })
            .joinToString(" — "),
        numberOfTracks = tracksCount,
        cover = cover,
        explicit = parentalWarning,
        type = typeLabel,
        duration = duration,
    )
}

private fun QobuzAlbumItem.toDomainAlbum(): tf.monochrome.android.domain.model.Album {
    val resolvedArtist = artist?.toDomainArtistRef()
    val resolvedArtists = artists.map { it.toDomainArtistRef() }
    val cover = image?.large ?: image?.small ?: image?.thumbnail
    val explicit = parentalWarning
    val typeLabel = if ((tracksCount ?: 0) <= 4) "EP" else "ALBUM"
    return tf.monochrome.android.domain.model.Album(
        id = qobuzId ?: id?.hashCode()?.toLong() ?: 0L,
        title = listOfNotNull(title.takeIf { it.isNotBlank() }, version?.takeIf { it.isNotBlank() })
            .joinToString(" — "),
        artist = resolvedArtist,
        artists = resolvedArtists,
        numberOfTracks = tracksCount,
        releaseDate = releaseDateOriginal,
        cover = cover,
        explicit = explicit,
        type = typeLabel,
        duration = duration,
    )
}

private fun QobuzTrackItem.toDomainTrack(
    fallbackAlbum: tf.monochrome.android.domain.model.Album? = null,
): tf.monochrome.android.domain.model.Track {
    val resolvedAlbum = album?.toDomainAlbum() ?: fallbackAlbum
    // performer is the track-level primary artist ({id, name}). Fall back to
    // the album's main artist when missing (the response always populates one
    // or the other).
    val resolvedArtist = performer?.toDomainArtist()
        ?: album?.artist?.toDomainArtistRef()
        ?: fallbackAlbum?.artist
    // Qobuz track payloads carry only a single `performer`; the structured
    // multi-artist credits (main + featured, each with an id) live on the album.
    // Merge the primary performer with the album's *performing* artists so a
    // track credited to several artists can wire each name to its own profile.
    // Composer/producer-only credits are dropped (not artist-page material).
    val creditedArtists = (listOfNotNull(resolvedArtist) +
        (album?.artists?.filter { it.isPerformingCredit() }?.map { it.toDomainArtistRef() } ?: emptyList()))
        .filter { it.id != 0L }
        .distinctBy { it.id }
    return tf.monochrome.android.domain.model.Track(
        id = id ?: 0L,
        title = listOfNotNull(title.takeIf { it.isNotBlank() }, version?.takeIf { it.isNotBlank() })
            .joinToString(" — "),
        duration = duration ?: 0,
        artist = resolvedArtist,
        artists = creditedArtists.ifEmpty { listOfNotNull(resolvedArtist) },
        album = resolvedAlbum,
        audioQuality = if (hires) "HI_RES_LOSSLESS" else if ((maximumBitDepth ?: 0) >= 16) "LOSSLESS" else null,
        explicit = parentalWarning,
        trackNumber = trackNumber,
        volumeNumber = mediaNumber,
        channelCount = maximumChannelCount,
    )
}

private fun QobuzPerson.toDomainArtist(): tf.monochrome.android.domain.model.Artist =
    tf.monochrome.android.domain.model.Artist(
        id = id ?: 0L,
        name = name,
        picture = null,
    )

private fun QobuzArtistItem.toDomainArtist(): tf.monochrome.android.domain.model.Artist =
    tf.monochrome.android.domain.model.Artist(
        id = id ?: 0L,
        name = name,
        picture = image?.large ?: image?.small ?: picture,
    )

private fun QobuzArtistRef.toDomainArtistRef(): tf.monochrome.android.domain.model.Artist =
    tf.monochrome.android.domain.model.Artist(
        id = id ?: 0L,
        name = name,
        picture = image?.large ?: image?.small ?: picture,
    )

/**
 * Whether this album credit is a performing artist (main or featured) — the kind
 * worth a clickable artist link — versus a composer/producer/writer-only credit.
 * Credits with no declared role are treated as performers (the common case for
 * the album's primary artists). Role strings are matched leniently because Qobuz
 * uses varying separators/casing (e.g. "main-artist", "MainArtist", "Featured Artist").
 */
private fun QobuzArtistRef.isPerformingCredit(): Boolean {
    if (roles.isEmpty()) return true
    return roles.any { role ->
        val key = role.lowercase().filter { it.isLetter() }
        key.contains("mainartist") || key.contains("featuredartist") ||
            key.contains("performer") || key == "artist"
    }
}

// Qobuz hashes artist images in the response; the actual URL is a templated
// CDN path. "large" is a reasonable default for the artist detail screen.
private fun QobuzArtistImages.portraitUrl(size: String = "large"): String? {
    val p = portrait ?: return null
    val hash = p.hash ?: return null
    val format = p.format ?: "jpg"
    return "https://static.qobuz.com/images/artists/covers/$size/$hash.$format"
}

private fun QobuzSimilarArtist.toDomainArtist(): tf.monochrome.android.domain.model.Artist? {
    val artistId = id ?: return null
    return tf.monochrome.android.domain.model.Artist(
        id = artistId,
        name = name?.display ?: "",
        picture = images?.portraitUrl(size = "medium"),
    )
}

private fun QobuzNamedRef.toDomainArtist(): tf.monochrome.android.domain.model.Artist =
    tf.monochrome.android.domain.model.Artist(
        id = id ?: 0L,
        name = name?.display ?: "",
    )

// Top-tracks on the artist endpoint use a different shape than QobuzTrackItem
// (rights/physical_support/audio_info wrappers, nested name objects). Map
// directly so the artist screen surfaces playable rows.
private fun QobuzArtistTopTrack.toDomainTrack(): tf.monochrome.android.domain.model.Track? {
    val trackId = id ?: return null
    val resolvedAlbum = album?.toDomainAlbum()
    val resolvedArtist = artist?.toDomainArtist() ?: album?.artist?.toDomainArtistRef()
    val audioQuality = when {
        rights?.hiresStreamable == true -> "HI_RES_LOSSLESS"
        (audioInfo?.maximumBitDepth ?: 0) >= 16 -> "LOSSLESS"
        else -> null
    }
    return tf.monochrome.android.domain.model.Track(
        id = trackId,
        title = listOfNotNull(title.takeIf { it.isNotBlank() }, version?.takeIf { it.isNotBlank() })
            .joinToString(" — "),
        duration = duration ?: 0,
        artist = resolvedArtist,
        artists = listOfNotNull(resolvedArtist),
        album = resolvedAlbum,
        audioQuality = audioQuality,
        explicit = parentalWarning,
        trackNumber = physicalSupport?.trackNumber,
        volumeNumber = physicalSupport?.mediaNumber,
        channelCount = audioInfo?.maximumChannelCount,
    )
}

// --- Extension functions for API model → Domain model conversion ---

private fun tf.monochrome.android.data.api.model.ApiArtist.toDomain() = Artist(
    id = id,
    name = name,
    picture = picture,
    artistTypes = artistTypes
)

private fun tf.monochrome.android.data.api.model.ApiAlbum.toDomain() = Album(
    id = id,
    title = title,
    artist = artist?.toDomain(),
    artists = artists.map { it.toDomain() },
    numberOfTracks = numberOfTracks,
    releaseDate = releaseDate,
    cover = cover,
    explicit = explicit,
    type = type,
    duration = duration
)

private fun tf.monochrome.android.data.api.model.ApiTrack.toDomain() = Track(
    id = id,
    title = title,
    duration = duration,
    artist = artist?.toDomain(),
    artists = artists.map { it.toDomain() },
    album = album?.toDomain(),
    audioQuality = audioQuality,
    explicit = explicit,
    trackNumber = trackNumber,
    volumeNumber = volumeNumber,
    popularity = popularity,
    type = type ?: "track",
    isUnavailable = unavailable,
    streamStartDate = streamStartDate
)

private fun tf.monochrome.android.data.api.model.SearchItem.toTrack() = Track(
    id = id,
    title = title,
    duration = duration,
    artist = artist?.toDomain(),
    artists = artists.map { it.toDomain() },
    album = album?.toDomain(),
    audioQuality = audioQuality,
    explicit = explicit,
    trackNumber = trackNumber,
    volumeNumber = volumeNumber,
    popularity = popularity,
    type = type ?: "track",
    streamStartDate = streamStartDate
)

private fun tf.monochrome.android.data.api.model.SearchItem.toAlbum() = Album(
    id = id,
    title = title.ifBlank { name ?: "" },
    artist = artist?.toDomain(),
    artists = artists.map { it.toDomain() },
    numberOfTracks = numberOfTracks,
    releaseDate = releaseDate,
    cover = cover ?: picture,
    explicit = explicit,
    type = type
)

private fun tf.monochrome.android.data.api.model.SearchItem.toArtist() = Artist(
    id = id,
    name = name ?: title,
    picture = picture,
    artistTypes = artistTypes
)

private fun tf.monochrome.android.data.api.model.SearchItem.toPlaylist() = Playlist(
    uuid = uuid ?: id.toString(),
    title = title.ifBlank { name ?: "" },
    description = description,
    numberOfTracks = numberOfTracks,
    cover = cover ?: squareImage ?: image,
    creator = creator?.let { PlaylistCreator(it.id, it.name) }
)

private fun tf.monochrome.android.data.api.model.AlbumTrackItem.toTrack(album: Album) = Track(
    id = item?.id ?: id,
    title = item?.title ?: title,
    duration = item?.duration ?: duration,
    artist = (item?.artist ?: artist)?.toDomain(),
    artists = (item?.artists ?: artists).map { it.toDomain() },
    album = album,
    audioQuality = item?.audioQuality ?: audioQuality,
    explicit = item?.explicit ?: explicit,
    trackNumber = item?.trackNumber ?: trackNumber,
    volumeNumber = item?.volumeNumber ?: volumeNumber,
    popularity = item?.popularity ?: popularity
)

private fun tf.monochrome.android.data.api.model.PlaylistTrackItem.toDomain(): Track? {
    if (item != null) return item.toDomain()
    val trackId = id ?: return null
    return Track(
        id = trackId,
        title = title ?: "",
        duration = duration ?: 0,
        artist = artist?.toDomain(),
        artists = artists?.map { it.toDomain() } ?: emptyList(),
        album = album?.toDomain(),
        trackNumber = trackNumber,
        audioQuality = audioQuality,
        explicit = explicit ?: false
    )
}
