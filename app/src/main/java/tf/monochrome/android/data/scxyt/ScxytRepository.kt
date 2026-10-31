package tf.monochrome.android.data.scxyt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.cloud.CloudError
import tf.monochrome.android.data.cloud.CloudRepository
import tf.monochrome.android.data.youtube.YoutubeOnDevice
import tf.monochrome.android.domain.model.UnifiedTrack
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The built-in YouTube and SoundCloud source, split by service
 * ([ScxytRouter]).
 *
 * YouTube is searched and fetched on the phone ([YoutubeOnDevice]), with no
 * sign-in: YouTube turns the server's data-centre address away.
 *
 * SoundCloud goes through trypt-hifi-scxyt, which searches and fetches
 * through yt-dlp on its own machine and serves the songs from there. It
 * takes the listener's Tryptify sign-in, the same Supabase session the Theme
 * Store and Tryptify Cloud take, and the token comes from
 * [CloudRepository.bearer], so a stale one is refreshed once for all three.
 * Signed out, SoundCloud is simply off: every call fails with
 * [ScxytError.SignedOut] before anything is sent.
 *
 * A shared song's Discord preview ([share]) is made by the server for both,
 * so it too needs the sign-in.
 */
@Singleton
class ScxytRepository @Inject constructor(
    private val auth: SupabaseAuthManager,
    private val cloud: CloudRepository,
    http: HttpClient,
    json: Json,
    youtube: YoutubeOnDevice,
) {
    private val client = ScxytClient(BASE, json, KtorTransport(http), ::token)

    private val router = ScxytRouter(
        server = object : ScxytServer {
            override suspend fun search(query: String, source: ScxytSource, limit: Int) = client.search(query, source, limit)
            override suspend fun prepare(id: String) = client.prepare(id)
            override suspend fun share(id: String, audio: File?, mime: String?) = client.share(id, audio, mime)
        },
        youtube = youtube,
    )

    /** Whether there is a sign-in to use SoundCloud with. */
    val signedIn: Flow<Boolean> = auth.userProfile.map { it != null }.distinctUntilChanged()

    /**
     * Whether SoundCloud can be used now, asked once: [signedIn] says no
     * until the saved sign-in is read back, and a shared link may open the
     * app cold.
     */
    suspend fun isSignedIn(): Boolean = auth.hasSession()

    /** The first [limit] songs [source] finds for [query]; a pasted link finds that one song. */
    suspend fun search(query: String, source: ScxytSource, limit: Int = SEARCH_LIMIT): Result<List<UnifiedTrack>> =
        router.search(query, source, limit)

    /**
     * Makes [mediaId] ready to play: a YouTube song (`yt:…`) fetched whole
     * into the phone's cache, a SoundCloud one (`sc:…`) fetched by the server,
     * which returns its six-hour link.
     */
    suspend fun prepare(mediaId: String): Result<ScxytStream> = router.prepare(mediaId)

    /**
     * Has the server make the preview Discord plays for [mediaId]'s shared
     * link, and returns the signature that goes in it ([ScxytRouter.share]):
     * a YouTube song's file goes up from the phone's cache, fetched first
     * when it is not there. Signed out it fails at once, before a YouTube
     * song is fetched only to be refused.
     */
    suspend fun share(mediaId: String): Result<ScxytShare> {
        if (!isSignedIn()) return Result.failure(ScxytError.SignedOut())
        return router.share(mediaId)
    }

    private suspend fun token(stale: String?): String = try {
        cloud.bearer(stale)
    } catch (e: CloudError.SignedOut) {
        throw ScxytError.SignedOut()
    } catch (e: CloudError) {
        throw ScxytError.Network(e)
    }

    private class KtorTransport(private val http: HttpClient) : ScxytClient.Transport {
        override suspend fun get(url: String, token: String, timeoutMs: Long): ScxytClient.Reply {
            val res = http.get(url) {
                bearerAuth(token)
                timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
            }
            return ScxytClient.Reply(res.status.value, res.bodyAsText())
        }

        override suspend fun post(url: String, token: String, body: File?, contentType: String?, timeoutMs: Long): ScxytClient.Reply {
            val type = contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.OctetStream
            val res = http.post(url) {
                bearerAuth(token)
                timeout { requestTimeoutMillis = timeoutMs; socketTimeoutMillis = timeoutMs }
                // Streamed from the file, never read into memory: a song is
                // megabytes. Opened anew on each send, so a retry sends it whole.
                if (body != null) setBody(object : OutgoingContent.ReadChannelContent() {
                    override val contentType: ContentType = type
                    override val contentLength: Long = body.length()
                    override fun readFrom(): ByteReadChannel = body.inputStream().toByteReadChannel()
                })
            }
            return ScxytClient.Reply(res.status.value, res.bodyAsText())
        }
    }

    companion object {
        val BASE: String = BuildConfig.SCXYT_URL

        /** A first page, as the search screen shows one source's songs. */
        const val SEARCH_LIMIT = 20
    }
}
