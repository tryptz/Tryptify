package tf.monochrome.android.data.cloud

import kotlinx.serialization.Serializable

/**
 * Tryptify Cloud's wire, as server/cloud speaks it: a personal Subsonic-API
 * music storage, made for fast streaming. The plans and the rules behind them
 * are the server's (server/cloud/src/rules.ts); the app only shows them.
 */

@Serializable
data class CloudPlan(
    val id: String,
    val storageGb: Int,
    val monthly: Double,
    val yearly: Double,
    /** The half-price first month for someone who has never paid; null when the plan has none. */
    val introMonthly: Double? = null,
)

/** The free trial, on one plan only ([plan]). */
@Serializable
data class CloudTrial(val days: Int = 7, val storageGb: Int = 100, val plan: String = "lite")

@Serializable
data class CloudPlans(
    val readOnlyDays: Int = 7,
    val trial: CloudTrial = CloudTrial(),
    val plans: List<CloudPlan> = emptyList(),
)

/** What the signed-in person may do: `full`, `read_only` or `none`. */
@Serializable
data class CloudAccount(
    val plan: String? = null,
    val access: String = "none",
    val paidUntil: String? = null,
    val deleteAfter: String? = null,
    val usedBytes: Long = 0,
    val quotaBytes: Long = 0,
    val trial: Boolean = false,
    val trialAvailable: Boolean = false,
    /** Whether a monthly checkout now gets the half-price first month. */
    val introAvailable: Boolean = false,
    /** Storage given, not sold (the server's cloud_grants): no quota, no end date, no subscription. */
    val unlimited: Boolean = false,
) {
    val canUpload: Boolean get() = access == "full"
    val canPlay: Boolean get() = access == "full" || access == "read_only"
}

@Serializable
data class CloudLibrary(
    val access: String = "none",
    val usedBytes: Long = 0,
    val reservedBytes: Long = 0,
    val quotaBytes: Long = 0,
    val songs: Int = 0,
    val partSize: Long = 0,
    val maxCoverBytes: Long = 0,
)

/** The tags a song is uploaded with; the server keeps them as its library. */
@Serializable
data class CloudSongMeta(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val duration: Int = 0,
    val bitRate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
)

@Serializable
internal data class UploadStartRequest(val size: Long, val suffix: String, val meta: CloudSongMeta)

@Serializable
internal data class UploadStart(val id: String, val partSize: Long, val parts: Int)

@Serializable
data class UploadedPart(val partNumber: Int, val etag: String)

@Serializable
internal data class UploadComplete(val parts: List<UploadedPart>)

@Serializable
internal data class UploadedSong(val id: String)

@Serializable
internal data class CheckoutRequest(val plan: String, val period: String, val trial: Boolean = false)

@Serializable
internal data class CheckoutUrl(val url: String)

@Serializable
internal data class KeyRequest(val label: String? = null)

/** One device's sign-in to the cloud's Subsonic API. */
@Serializable
internal data class SubsonicKey(val server: String, val username: String, val password: String)

/** Why a cloud call failed, in terms the screen can word. */
sealed class CloudError(message: String) : Exception(message) {
    class SignedOut : CloudError("signed out")
    class NoPlan : CloudError("no plan")
    class ReadOnly : CloudError("read only")
    class OverQuota : CloudError("over quota")
    class NotAudio : CloudError("not audio")
    class TooBig : CloudError("too big")
    class TrialUsed : CloudError("trial used")
    class Busy : CloudError("too many uploads")
    class NotSetUp : CloudError("not set up")
    class Server(val code: String) : CloudError(code)
    class Network(cause: Throwable) : CloudError(cause.message ?: "network")

    companion object {
        private val CODE = Regex("\"error\"\\s*:\\s*\"([a-z_]+)\"")

        /** The error a failed response means, from its status and `{"error": code}` body. */
        fun of(status: Int, body: String): CloudError = when (val code = CODE.find(body)?.groupValues?.get(1)) {
            "signed_out" -> SignedOut()
            "no_plan" -> NoPlan()
            "read_only" -> ReadOnly()
            "over_quota" -> OverQuota()
            "not_audio" -> NotAudio()
            "file_too_big", "cover_too_big" -> TooBig()
            "trial_used" -> TrialUsed()
            "too_many_uploads" -> Busy()
            "not_set_up", "billing_not_set_up", "storage_not_set_up" -> NotSetUp()
            null -> if (status == 401) SignedOut() else Server("http_$status")
            else -> Server(code)
        }
    }
}

/** Where part [n] (1-based) of a [size]-byte file starts, and how long it is. */
internal fun partRange(size: Long, partSize: Long, n: Int): LongRange {
    val start = (n - 1) * partSize
    val end = minOf(size, start + partSize)
    return start until end
}
