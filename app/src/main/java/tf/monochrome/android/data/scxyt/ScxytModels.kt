package tf.monochrome.android.data.scxyt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import kotlin.math.roundToInt

/**
 * The YouTube and SoundCloud source's wire, as trypt-hifi-scxyt speaks it
 * (its README, "The API Tryptify uses"). Every answer is
 * `{"success": true, "data": …}` or `{"success": false, "error": code, "message": …}`.
 * Only SoundCloud goes there now; YouTube is fetched on the phone
 * (data/youtube) into the same models.
 */

/**
 * The two services, and how each names its songs. YouTube's ids are the
 * ones the server handed out before YouTube moved onto the phone, so songs
 * saved then still play.
 */
enum class ScxytSource(
    /** The `source` the search takes. */
    val wire: String,
    /** A brand name: shown as it is in every language. */
    val label: String,
    val sourceType: SourceType,
    private val id: Regex,
) {
    YOUTUBE("youtube", "YouTube", SourceType.YOUTUBE, Regex("yt:[A-Za-z0-9_-]{11}")),
    SOUNDCLOUD("soundcloud", "SoundCloud", SourceType.SOUNDCLOUD, Regex("sc:[0-9]+"));

    companion object {
        /**
         * The service a server id belongs to; null for one the server never
         * hands out, which the app does not play or store.
         */
        fun ofId(id: String): ScxytSource? = entries.firstOrNull { it.id.matches(id) }

        fun of(type: SourceType): ScxytSource? = entries.firstOrNull { it.sourceType == type }
    }
}

@Serializable
internal data class ScxytEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val error: String? = null,
    val message: String? = null,
)

/** A failed answer's body, read without caring what its `data` would have been. */
@Serializable
internal data class ScxytFailure(val error: String? = null, val message: String? = null)

/** One song, as a search finds it. */
@Serializable
data class ScxytTrack(
    val id: String,
    val source: String? = null,
    val title: String = "",
    val artist: String = "",
    val artistUrl: String? = null,
    /** Whole seconds by the contract; read as a number of any kind. */
    val duration: Double = 0.0,
    val cover: String? = null,
    val url: String? = null,
)

@Serializable
internal data class ScxytSearchData(
    val query: String = "",
    val source: String = "",
    val offset: Int = 0,
    val items: List<ScxytTrack> = emptyList(),
)

@Serializable
internal data class ScxytPrepared(
    val id: String = "",
    val format: String = "",
    val mime: String = "",
    val size: Long = 0,
    val stream: String = "",
    val link: String = "",
)

/**
 * A song ready to play. From the server (SoundCloud), [link] is absolute,
 * signed for six hours and needs no headers, so the player and the
 * downloader take it as it is. Fetched on the phone (YouTube), [file] is the
 * whole song in the app's cache and [link] its `file://` address.
 */
data class ScxytStream(
    val id: String,
    /** `m4a` or `mp3` from the server; `m4a` or `webm` from the phone. */
    val format: String,
    /** `audio/mp4` or `audio/mpeg`; `audio/webm` for a WebM from the phone. */
    val mime: String,
    val size: Long,
    val link: String,
    val file: java.io.File? = null,
)

/** Why a call to the server failed, in terms the app can word. */
sealed class ScxytError(message: String) : Exception(message) {
    /** No sign-in, or one the server would not take: SoundCloud is off. YouTube never asks for one. */
    class SignedOut : ScxytError("signed out")
    /**
     * SoundCloud turned the server away, which lasts until the server is
     * fixed; or YouTube turned this phone's network away (its bot check),
     * which may pass, or not happen on another network.
     */
    class Blocked(message: String? = null) : ScxytError(message ?: "blocked")
    /** Locked by the service itself (DRM): SoundCloud's uploads by big labels. */
    class Protected(message: String? = null) : ScxytError(message ?: "protected")
    /** It exists but cannot be played there: age check, region, members only. */
    class Unavailable(message: String? = null) : ScxytError(message ?: "unavailable")
    /** Longer than the server fetches, or a live stream. */
    class TooLong(message: String? = null) : ScxytError(message ?: "too long")
    /** It does not exist, or is private. */
    class NotFound(message: String? = null) : ScxytError(message ?: "not found")
    /** Too many fetches at once, or Supabase Auth did not answer the server: try again shortly. */
    class Busy(message: String? = null) : ScxytError(message ?: "busy")
    class Timeout(message: String? = null) : ScxytError(message ?: "timeout")
    /** Anything else the server said, or an answer that was not its JSON. */
    class Failed(val code: String, message: String? = null) : ScxytError(message ?: code)
    /** Nothing answered. */
    class Network(cause: Throwable) : ScxytError(cause.message ?: "network")

    /** Whether trying the same thing again soon may work. */
    val retryable: Boolean get() = this is Busy || this is Timeout || this is Network

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The error a failed answer means, from its status and its `{"error": code}` body. */
        fun of(status: Int, body: String): ScxytError {
            val envelope = runCatching { json.decodeFromString<ScxytFailure>(body) }.getOrNull()
            val message = envelope?.message
            return when (val code = envelope?.error) {
                "signed_out" -> SignedOut()
                "blocked" -> Blocked(message)
                "protected" -> Protected(message)
                "unavailable" -> Unavailable(message)
                "too_long" -> TooLong(message)
                "not_found" -> NotFound(message)
                "busy", "auth_unreachable" -> Busy(message)
                "timeout" -> Timeout(message)
                null -> when (status) {
                    401 -> SignedOut()
                    404 -> NotFound()
                    else -> Failed("http_$status")
                }
                else -> Failed(code, message)
            }
        }
    }
}

/**
 * [this] as the app plays and lists it: the server's id as the track's own
 * (`yt:dQw4w9WgXcQ`), and nothing that would pass for a catalogue album or
 * artist, which no screen here could open. Null for an id the server never
 * hands out.
 */
fun ScxytTrack.toUnifiedTrack(): UnifiedTrack? {
    val service = ScxytSource.ofId(id) ?: return null
    val name = artist.trim().ifBlank { "Unknown Artist" }
    return UnifiedTrack(
        id = id,
        title = title.trim().ifBlank { id },
        durationSeconds = duration.roundToInt().coerceAtLeast(0),
        artistName = name,
        artistNames = listOf(name),
        artists = listOf(UnifiedArtistRef(id = null, name = name)),
        // Leaves the app with the song (the media session, Discord, the
        // synced history), so only a plain https picture.
        artworkUri = cover?.takeIf { it.startsWith("https://") },
        source = PlaybackSource.Scxyt(mediaId = id),
        sourceType = service.sourceType,
    )
}
