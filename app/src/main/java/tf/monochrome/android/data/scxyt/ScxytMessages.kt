package tf.monochrome.android.data.scxyt

import android.content.Context
import androidx.annotation.StringRes
import tf.monochrome.android.R

/**
 * What the listener reads when a YouTube or SoundCloud song could not be
 * played or downloaded: one line per error code, in the app's language. The
 * server's own `message` is English and goes to the log only.
 */
object ScxytMessages {

    /**
     * The line for [error] from [service]. Being turned away means the
     * server for SoundCloud, and the phone's own network for YouTube, which
     * is fetched from the phone.
     */
    @StringRes
    fun textRes(error: Throwable, service: ScxytSource? = null): Int = when (error) {
        is ScxytError.SignedOut -> R.string.scxyt_error_signed_out
        is ScxytError.Blocked ->
            if (service == ScxytSource.YOUTUBE) R.string.scxyt_error_blocked_network else R.string.scxyt_error_blocked
        is ScxytError.Protected -> R.string.scxyt_error_protected
        is ScxytError.Unavailable -> R.string.scxyt_error_unavailable
        is ScxytError.TooLong -> R.string.scxyt_error_too_long
        is ScxytError.NotFound -> R.string.scxyt_error_not_found
        is ScxytError.Busy -> R.string.scxyt_error_busy
        is ScxytError.Timeout -> R.string.scxyt_error_timeout
        else -> R.string.scxyt_error_failed
    }

    /** The lines that name the service. */
    private val NAMES_SERVICE = setOf(
        R.string.scxyt_error_signed_out,
        R.string.scxyt_error_blocked,
        R.string.scxyt_error_blocked_network,
        R.string.scxyt_error_protected,
        R.string.scxyt_error_not_found,
        R.string.scxyt_error_failed,
    )

    /** [error] in words, for the song [mediaId] (`yt:…` or `sc:…`). */
    fun text(context: Context, error: Throwable, mediaId: String): String {
        val service = ScxytSource.ofId(mediaId) ?: ScxytSource.YOUTUBE
        val res = textRes(error, service)
        val words = tf.monochrome.android.locale.AppLanguage.wrap(context)
        if (res !in NAMES_SERVICE) return words.getString(res)
        return words.getString(res, service.label)
    }
}
