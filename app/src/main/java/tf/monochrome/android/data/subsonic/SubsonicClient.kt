package tf.monochrome.android.data.subsonic

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Subsonic REST API, as Navidrome, Gonic, Airsonic-Advanced, Ampache and
 * the rest of the family serve it.
 *
 * Every call throws: [SubsonicException] when the server answered
 * `status: failed` (wrong password, unknown id), a serialization error when
 * what answered is not a Subsonic server (a router's login page, the wrong
 * port), and an I/O error when nothing answered at all. The repository turns
 * those into what the listener reads.
 */
@Singleton
class SubsonicClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun ping(server: SubsonicServer): SubsonicResponse = call(server, "ping")

    suspend fun albumList(server: SubsonicServer, type: String, size: Int, offset: Int): List<SubsonicAlbum> =
        call(server, "getAlbumList2", listOf("type" to type, "size" to "$size", "offset" to "$offset"))
            .albumList2?.album.orEmpty()

    suspend fun album(server: SubsonicServer, id: String): SubsonicAlbum? =
        call(server, "getAlbum", listOf("id" to id)).album

    suspend fun artists(server: SubsonicServer): List<SubsonicArtist> =
        call(server, "getArtists").artists?.index.orEmpty().flatMap { it.artist }

    suspend fun artist(server: SubsonicServer, id: String): SubsonicArtist? =
        call(server, "getArtist", listOf("id" to id)).artist

    suspend fun search(
        server: SubsonicServer,
        query: String,
        songCount: Int = 50,
        albumCount: Int = 0,
        artistCount: Int = 0,
        songOffset: Int = 0,
    ): SubsonicSearchResult = call(
        server,
        "search3",
        listOf(
            "query" to query,
            "songCount" to "$songCount",
            "albumCount" to "$albumCount",
            "artistCount" to "$artistCount",
            "songOffset" to "$songOffset",
        ),
    ).searchResult3 ?: SubsonicSearchResult()

    /** getCoverArt's bytes, or null when the server answered with something that is not an image. */
    suspend fun coverArt(server: SubsonicServer, id: String, size: Int): ByteArray? {
        val response = httpClient.get(Subsonic.coverArtUrl(server, id, size) ?: return null)
        val type = response.headers["Content-Type"].orEmpty()
        return if (response.status.value in 200..299 && type.startsWith("image/")) response.readRawBytes() else null
    }

    suspend fun randomSongs(server: SubsonicServer, size: Int): List<SubsonicSong> =
        call(server, "getRandomSongs", listOf("size" to "$size")).randomSongs?.song.orEmpty()

    private suspend fun call(
        server: SubsonicServer,
        method: String,
        params: List<Pair<String, String>> = emptyList(),
    ): SubsonicResponse {
        val body = httpClient.get(Subsonic.endpoint(server, method, params)).bodyAsText()
        return parse(json, body)
    }

    companion object {
        /** The envelope's content, or [SubsonicException] for a `failed` one. Pure, for tests. */
        fun parse(json: Json, body: String): SubsonicResponse {
            val response = json.decodeFromString<SubsonicEnvelope>(body).response
            if (response.status != "ok") {
                throw SubsonicException(response.error?.code, response.error?.message)
            }
            return response
        }
    }
}
