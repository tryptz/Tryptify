package tf.monochrome.android.data.scxyt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
 */
@Singleton
class ScxytRepository @Inject constructor(
    auth: SupabaseAuthManager,
    private val cloud: CloudRepository,
    http: HttpClient,
    json: Json,
    youtube: YoutubeOnDevice,
) {
    private val client = ScxytClient(BASE, json, KtorTransport(http), ::token)

    private val router = ScxytRouter(
        server = object : ScxytBackend {
            override suspend fun search(query: String, source: ScxytSource, limit: Int) = client.search(query, source, limit)
            override suspend fun prepare(id: String) = client.prepare(id)
        },
        youtube = youtube,
    )

    /** Whether there is a sign-in to use SoundCloud with. */
    val signedIn: Flow<Boolean> = auth.userProfile.map { it != null }.distinctUntilChanged()

    /** The first [limit] songs [source] finds for [query]; a pasted link finds that one song. */
    suspend fun search(query: String, source: ScxytSource, limit: Int = SEARCH_LIMIT): Result<List<UnifiedTrack>> =
        router.search(query, source, limit)

    /**
     * Makes [mediaId] ready to play: a YouTube song (`yt:…`) fetched whole
     * into the phone's cache, a SoundCloud one (`sc:…`) fetched by the server,
     * which returns its six-hour link.
     */
    suspend fun prepare(mediaId: String): Result<ScxytStream> = router.prepare(mediaId)

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
    }

    companion object {
        val BASE: String = BuildConfig.SCXYT_URL

        /** A first page, as the search screen shows one source's songs. */
        const val SEARCH_LIMIT = 20
    }
}
