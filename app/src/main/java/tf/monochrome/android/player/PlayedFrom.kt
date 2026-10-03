package tf.monochrome.android.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import tf.monochrome.android.data.cache.QobuzStreamUri

/**
 * Which service a playing item's audio actually comes from, when that is not
 * the one the song was picked from — a TIDAL pick the listener let play from
 * Qobuz, or a catalog pick played from
 * its downloaded copy. Carried in the MediaItem's
 * metadata extras, so the now-playing screen can tag it honestly.
 */
object PlayedFrom {
    const val KEY = "tryptify.playedFrom"
    const val QOBUZ = "QOBUZ"
    /** An on-device copy of a song picked from a catalog. */
    const val LOCAL = "LOCAL"

    fun extras(service: String): Bundle = Bundle().apply { putString(KEY, service) }

    /** The service a playing item comes from, if it was marked. */
    fun of(metadata: MediaMetadata?): String? = metadata?.extras?.getString(KEY)

}
