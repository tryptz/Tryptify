// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tf.monochrome.android.data.api.ApiServer
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.cloud.CloudRepository
import tf.monochrome.android.data.preferences.PreferencesManager

/**
 * A chart's separation done on a TryptMania Parser server
 * (github.com/tryptz/TryptMania-Parser), on a GPU where it has one.
 *
 * The song file goes up as it is; what comes back is not stems but the
 * envelopes the chart generator would have built from them — drums and each
 * [StemLayer], 100 values a second — made by the server from the same
 * arithmetic as [TransientEnvelopeBuilder] (held to it by
 * EnvelopeParityTest). A few hundred kilobytes instead of six stems of audio,
 * so the network costs about what uploading the song does.
 *
 * Any failure is the caller's to fall back from: [StepManiaConversionService]
 * separates on the phone instead.
 */
@Singleton
class RemoteStemAnalysis @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val json: Json,
    private val cloud: CloudRepository,
    private val charting: ChartingAccountStore,
) {

    /** Every server under Settings › Connections that separates stems, in the list's order. */
    val stemServerFlow: Flow<List<ApiServer>> =
        preferences.apiServers.map { servers -> servers.filter { ApiService.STEMS in it.services } }

    suspend fun stemServers(): List<ApiServer> = stemServerFlow.first()

    /** The servers [mode] tries, in the order it tries them; empty when it has none. */
    suspend fun plan(mode: ChartMode): List<ApiServer> = tryptify() + planFor(stemServers(), mode)

    /**
     * Tryptify's own server, first in either mode, while the signed-in
     * listener has minutes or their free song (ChartingAccount). It is
     * reached through Cloud (`/v1/charting`), never by an address of its
     * own; it charts on a CPU, so Slow may use it too.
     */
    private suspend fun tryptify(): List<ApiServer> {
        if (!charting.account.value.loaded) charting.refresh()
        val account = charting.account.value
        val canChart = account.signedIn && (account.freeSongAvailable || account.minutesLeft >= ChartingAccount.MIN_SONG_MINUTES)
        return if (canChart) listOf(ApiServer(url = cloud.chartingServerUrl, services = setOf(ApiService.STEMS), stemDevice = "cpu")) else emptyList()
    }

    /** Whether [server] is Tryptify's own, reached through Cloud. */
    fun isTryptify(server: String): Boolean = server == cloud.chartingServerUrl

    /**
     * Remembers what [server] separates on, from its own answer: a server
     * added before servers were asked learns it from its first song.
     */
    suspend fun learnDevice(server: String, device: String?) {
        if (device == null) return
        val servers = preferences.apiServers.first()
        if (servers.none { it.url == server && it.stemDevice != device }) return
        preferences.setApiServers(servers.map { if (it.url == server) it.copy(stemDevice = device) else it })
    }

    /**
     * The song separated on [server]. Tryptify's own server also needs the
     * song's [title] and length in [seconds]: Cloud takes its minutes first,
     * signed in with the session, and answers like any server.
     */
    suspend fun analyze(server: String, source: Uri, title: String = "", seconds: Int = 0): RemoteEnvelopes {
        val tryptify = isTryptify(server)
        if (tryptify && seconds <= 0) throw IOException("Tryptify's server needs the song's length")
        val length = sizeOf(source)
        suspend fun attempt(bearer: String?) = httpClient.post(if (tryptify) "$server/analyze" else "$server/api/stems/analyze") {
            if (tryptify) {
                parameter("seconds", seconds)
                parameter("title", title.take(200))
                bearerAuth(bearer!!)
            }
            // The server says nothing until the song is done: minutes on a CPU
            // host, seconds on a warm GPU, plus a cold start when it slept.
            timeout {
                requestTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
            setBody(object : OutgoingContent.ReadChannelContent() {
                override val contentType: ContentType = ContentType.Application.OctetStream
                override val contentLength: Long? = length?.takeIf { it > 0 }
                override fun readFrom(): ByteReadChannel =
                    (context.contentResolver.openInputStream(source) ?: throw IOException("Could not open the song"))
                        .toByteReadChannel()
            })
        }
        val token = if (tryptify) cloud.bearer() else null
        var response = attempt(token)
        if (tryptify && response.status.value == 401) response = attempt(cloud.bearer(stale = token))
        val body = response.bodyAsText()
        if (tryptify) {
            // Whatever happened, the account moved: a song charted or a reservation given back.
            runCatching { charting.refresh() }
            when (response.status.value) {
                402 -> throw IOException("No minutes left on Tryptify's server; buy a pack under Generate charts")
                413 -> throw IOException("Tryptify's server charts songs up to ${ChartingAccount.MAX_SONG_MINUTES} minutes")
            }
        }
        if (!response.status.isSuccess() && !body.trimStart().startsWith("{")) {
            throw IOException("The server answered HTTP ${response.status.value}")
        }
        return parse(json, body, if (tryptify) "Tryptify" else hostOf(server))
    }

    private fun sizeOf(source: Uri): Long? = runCatching {
        context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    companion object {
        private const val TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * [FAST][ChartMode.FAST]: the GPU servers, then any that has not said
         * what it runs on, then the CPU ones as the fallback.
         * [SLOW][ChartMode.SLOW]: the CPU servers alone — an unknown one may
         * be a GPU, and Slow must not bill one.
         */
        fun planFor(servers: List<ApiServer>, mode: ChartMode): List<ApiServer> = when (mode) {
            ChartMode.FAST ->
                servers.filter { it.separatesOnGpu == true } +
                    servers.filter { it.separatesOnGpu == null } +
                    servers.filter { it.separatesOnGpu == false }
            ChartMode.SLOW -> servers.filter { it.separatesOnGpu == false }
        }

        fun hostOf(server: String): String = server.substringAfter("://").substringBefore('/')

        /**
         * A server's answer as the chart generator's builders. Throws with the
         * server's own message when it reports a failure.
         */
        fun parse(json: Json, body: String, host: String): RemoteEnvelopes {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("The server did not answer with JSON") }
            if (root["success"]?.jsonPrimitive?.booleanOrNull != true) {
                val message = root["error"]?.jsonPrimitive?.contentOrNull ?: "no reason given"
                throw IOException("The server could not separate it: $message")
            }
            val data = root["data"]?.jsonObject ?: throw IOException("The server's answer has no data")
            val sampleRate = data["sampleRate"]?.jsonPrimitive?.intOrNull ?: throw IOException("No sample rate")
            val frames = data["frames"]?.jsonPrimitive?.longOrNull ?: throw IOException("No length")
            val envelopes = data["envelopes"]?.jsonObject ?: throw IOException("No envelopes")

            fun builder(name: String): TransientEnvelopeBuilder? {
                val entry = envelopes[name] as? JsonObject ?: return null
                val raw = floats(entry["raw"]?.jsonPrimitive?.contentOrNull ?: return null)
                val rates = floats(entry["rates"]?.jsonPrimitive?.contentOrNull ?: return null)
                return TransientEnvelopeBuilder.restored(sampleRate, raw, rates, frames)
            }

            val drums = builder("drums") ?: throw IOException("No drum envelope")
            val layers = StemLayerEnvelopes(
                sampleRate,
                StemLayer.entries.associateWith { layer ->
                    builder(layer.name.lowercase())
                        // A layer the server did not send is silence, as one
                        // a separator never returned is on the phone.
                        ?: TransientEnvelopeBuilder.restored(sampleRate, FloatArray(drums.raw().size), FloatArray(drums.raw().size), frames)
                },
            )
            val run = runOf(data, sampleRate, frames)
            val model = data["model"]?.jsonPrimitive?.contentOrNull ?: "a server"
            val device = data["device"]?.jsonPrimitive?.contentOrNull
            val label = buildString {
                append(model)
                append(" on ")
                append(host)
                if (device == "cuda") append(" (GPU)")
            }
            return RemoteEnvelopes(drums, layers, label, run)
        }

        /** The server's timings and terms, as far as it gave them. */
        private fun runOf(data: JsonObject, sampleRate: Int, frames: Long): RemoteRun {
            val seconds = data["seconds"] as? JsonObject
            fun timing(name: String): Double = seconds?.get(name)?.jsonPrimitive?.doubleOrNull ?: 0.0
            return RemoteRun(
                device = data["device"]?.jsonPrimitive?.contentOrNull,
                audioSeconds = frames.toDouble() / sampleRate.coerceAtLeast(1),
                uploadSeconds = timing("upload"),
                decodeSeconds = timing("decode"),
                separateSeconds = timing("separate") + timing("envelopes"),
                pricePerHour = data["pricePerHour"]?.jsonPrimitive?.doubleOrNull,
                idleSeconds = data["idleSeconds"]?.jsonPrimitive?.intOrNull,
                parallel = data["parallel"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 },
            )
        }

        private fun floats(base64: String): FloatArray {
            val bytes = Base64.getDecoder().decode(base64)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}

/** What a server sent back, ready for [StemRhythmAnalyzer], and who made it. */
class RemoteEnvelopes(
    val drums: TransientEnvelopeBuilder,
    val layers: StemLayerEnvelopes,
    val label: String,
    val run: RemoteRun,
)

/**
 * How long a server took over one song, and what it charges, from its own
 * answer: what [ChartPace] learns a batch's estimate from.
 */
data class RemoteRun(
    /** What the server separated on, as it said: `cuda` or `cpu`. */
    val device: String?,
    val audioSeconds: Double,
    /** Receiving the song from the phone: the phone's upload, as the server saw it. */
    val uploadSeconds: Double,
    val decodeSeconds: Double,
    /** Separation and the envelopes: the server's one-at-a-time part. */
    val separateSeconds: Double,
    /** Null when the deploy does not say (a server not on Modal, or an older one). */
    val pricePerHour: Double?,
    val idleSeconds: Int?,
    /**
     * How many songs the deploy separates at once: its copies, each one song
     * at a time (Modal's CPU copies, Railway's replicas). Null when it does not say.
     */
    val parallel: Int? = null,
) {
    /**
     * Seconds of a batch this song costs, per second of its audio. A batch
     * sends the next song while this one separates, so a song costs whichever
     * is longer, getting it there or separating it, not both.
     */
    val batchSecondsPerAudioSecond: Double?
        get() = if (audioSeconds <= 0.0) null else maxOf(separateSeconds, uploadSeconds + decodeSeconds) / audioSeconds
}
