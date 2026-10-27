package tf.monochrome.android.data.cloud

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.auth.UserProfile
import tf.monochrome.android.data.subsonic.SubsonicConnectResult
import tf.monochrome.android.data.subsonic.SubsonicRepository
import tf.monochrome.android.data.subsonic.SubsonicServer
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tryptify Cloud (server/cloud): the listener's personal Subsonic-API music
 * storage, made for fast streaming.
 *
 * Every `/v1` call carries the Supabase session, as the Theme Store's do. The
 * library itself is played through the ordinary Subsonic client: [connectPlayer]
 * asks the cloud for a device password and saves the cloud as the Subsonic
 * server, so My server shows it like any Navidrome.
 */
@Singleton
class CloudRepository @Inject constructor(
    private val auth: SupabaseAuthManager,
    private val subsonic: SubsonicRepository,
    private val http: HttpClient,
    private val json: Json,
) {
    private val refreshing = Mutex()

    val user: StateFlow<UserProfile?> = auth.userProfile

    /** The plans and the trial's terms. Needs no account. */
    suspend fun plans(): Result<CloudPlans> = call {
        val res = http.get { url { takeFrom(BASE); appendPathSegments("plans") } }
        if (!res.status.isSuccess()) throw CloudError.of(res.status.value, res.bodyAsText())
        json.decodeFromString<CloudPlans>(res.bodyAsText())
    }

    suspend fun account(): Result<CloudAccount> = call {
        json.decodeFromString<CloudAccount>(send(HttpMethod.Get, "account").bodyAsText())
    }

    suspend fun library(): Result<CloudLibrary> = call {
        json.decodeFromString<CloudLibrary>(send(HttpMethod.Get, "library").bodyAsText())
    }

    /**
     * The payment page for [plan] ("lite", "plus", "max"…) and [period]
     * ("monthly", "yearly"), at [provider] ("paypal", "creem"), or the
     * server's default when null.
     */
    suspend fun checkoutUrl(plan: String, period: String, trial: Boolean, provider: String? = null): Result<String> = call {
        val body = json.encodeToString(CheckoutRequest(plan, period, trial, provider))
        json.decodeFromString<CheckoutUrl>(send(HttpMethod.Post, "checkout", body = body).bodyAsText()).url
    }

    /** The payment provider's page where the listener changes or cancels their plan. */
    suspend fun portalUrl(): Result<String> = call {
        json.decodeFromString<CheckoutUrl>(send(HttpMethod.Post, "billing-portal").bodyAsText()).url
    }

    /** True when [server] is this cloud, rather than a server the listener runs. */
    fun isCloud(server: SubsonicServer?): Boolean =
        server != null && server.url.trimEnd('/') == BASE.trimEnd('/')

    /**
     * Makes the cloud this device's Subsonic server: a fresh device password
     * from the cloud, then the same sign-in any Subsonic server gets. It
     * replaces whichever server was connected before.
     */
    suspend fun connectPlayer(deviceLabel: String): Result<SubsonicServer> = call {
        val body = json.encodeToString(KeyRequest(deviceLabel.take(60)))
        val key = json.decodeFromString<SubsonicKey>(send(HttpMethod.Post, "subsonic-keys", body = body).bodyAsText())
        when (val r = subsonic.connect(key.server, key.username, key.password)) {
            is SubsonicConnectResult.Connected -> r.server
            is SubsonicConnectResult.Failed -> throw CloudError.Server(r.error.name.lowercase())
        }
    }

    /**
     * Uploads [file] as one song: opens the upload (which reserves its size
     * against the quota), sends it in the parts the server asks for, then
     * finishes it. A part that fails on the network is tried again a few
     * times; anything else aborts the upload, which frees the reservation.
     * Returns the new song's id.
     *
     * [onProgress] gets the bytes sent so far.
     */
    suspend fun upload(
        file: File,
        suffix: String,
        meta: CloudSongMeta,
        cover: File?,
        onProgress: suspend (Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val size = file.length()
        val start = json.decodeFromString<UploadStart>(
            send(HttpMethod.Post, "uploads", body = json.encodeToString(UploadStartRequest(size, suffix, meta))).bodyAsText(),
        )
        try {
            val done = ArrayList<UploadedPart>(start.parts)
            var sent = 0L
            for (n in 1..start.parts) {
                val range = partRange(size, start.partSize, n)
                val part = withRetries { sendPart(start.id, n, file, range) }
                done += part
                sent += range.last - range.first + 1
                onProgress(sent)
            }
            val body = json.encodeToString(UploadComplete(done))
            val songId = json.decodeFromString<UploadedSong>(
                send(HttpMethod.Post, "uploads", start.id, "complete", body = body).bodyAsText(),
            ).id
            if (cover != null) runCatching { setCover(songId, cover) }
            songId
        } catch (e: Throwable) {
            // Gives the reservation back now rather than at the six-hour cleanup.
            withContext(NonCancellable) { runCatching { send(HttpMethod.Delete, "uploads", start.id) } }
            throw e
        }
    }

    /** A song's cover picture. The server takes JPEG, PNG or WebP up to 1 MB. */
    suspend fun setCover(songId: String, picture: File) {
        if (picture.length() > MAX_COVER_BYTES) return
        val bytes = withContext(Dispatchers.IO) { picture.readBytes() }
        send(HttpMethod.Put, "songs", songId, "cover", bytes = bytes)
    }

    suspend fun deleteSong(songId: String): Result<Unit> = call { send(HttpMethod.Delete, "songs", songId); Unit }

    private suspend fun sendPart(uploadId: String, n: Int, file: File, range: LongRange): UploadedPart {
        val res = send(HttpMethod.Put, "uploads", uploadId, "parts", n.toString(), part = FilePart(file, range))
        return json.decodeFromString<UploadedPart>(res.bodyAsText())
    }

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var wait = 2_000L
        repeat(PART_ATTEMPTS - 1) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudError) {
                throw e
            } catch (e: Exception) {
                delay(wait)
                wait *= 2
            }
        }
        return block()
    }

    /**
     * One call under `/v1/[path]`. A 401 refreshes the session and sends it
     * once more; any other failure is thrown as the [CloudError] it means.
     */
    private suspend fun send(
        method: HttpMethod,
        vararg path: String,
        body: String? = null,
        bytes: ByteArray? = null,
        part: FilePart? = null,
    ): HttpResponse {
        suspend fun attempt(token: String) = http.request {
            this.method = method
            url {
                takeFrom(BASE)
                appendPathSegments("v1", *path)
            }
            bearerAuth(token)
            if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            if (bytes != null) setBody(ByteArrayContent(bytes, ContentType.Application.OctetStream))
            if (part != null) {
                setBody(part)
                // A 50 MB part on a slow uplink takes minutes, not the client's 30 s.
                timeout { requestTimeoutMillis = PART_TIMEOUT_MS; socketTimeoutMillis = PART_TIMEOUT_MS }
            }
        }

        val token = auth.supabase.auth.currentAccessTokenOrNull() ?: throw CloudError.SignedOut()
        var res = attempt(token)
        if (res.status == HttpStatusCode.Unauthorized) res = attempt(refreshed(token))
        if (!res.status.isSuccess()) throw CloudError.of(res.status.value, res.bodyAsText())
        return res
    }

    /** A token newer than [stale]; calls that fail together refresh once. */
    private suspend fun refreshed(stale: String): String = refreshing.withLock {
        val now = auth.supabase.auth.currentAccessTokenOrNull()
        if (now != null && now != stale) return@withLock now
        try {
            auth.supabase.auth.refreshCurrentSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RestException) {
            throw CloudError.SignedOut()
        } catch (e: Exception) {
            throw CloudError.Network(e)
        }
        auth.supabase.auth.currentAccessTokenOrNull() ?: throw CloudError.SignedOut()
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CloudError) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(CloudError.Network(e))
        }

    /**
     * Bytes [range] of [file] as a request body, read as they are sent so a
     * 50 MB part never sits in memory. The length is declared up front: the
     * server checks it before taking the part.
     */
    private class FilePart(private val file: File, private val range: LongRange) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = range.last - range.first + 1
        override val contentType: ContentType = ContentType.Application.OctetStream

        override suspend fun writeTo(channel: ByteWriteChannel) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(range.first)
                val buffer = ByteArray(BUFFER)
                var left = contentLength
                while (left > 0) {
                    val n = raf.read(buffer, 0, minOf(BUFFER.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException("file shrank while uploading")
                    channel.writeFully(buffer, 0, n)
                    left -= n
                }
            }
        }
    }

    companion object {
        val BASE: String = BuildConfig.TRYPTIFY_CLOUD_URL
        const val MAX_COVER_BYTES = 1L shl 20
        private const val PART_ATTEMPTS = 4
        private const val PART_TIMEOUT_MS = 15 * 60 * 1000L
        private const val BUFFER = 64 * 1024
    }
}
