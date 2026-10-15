package tf.monochrome.android.player

import tf.monochrome.android.domain.model.Track

/**
 * Which play goes into Recently Played, and when.
 *
 * Plays used to be written from four places, and between them they missed
 * most local songs: a song tapped in the app reaches the service as a
 * playlist change, which was skipped; a song the player moved on to by itself
 * was looked up by its media id as a number, and a local file's media id is
 * its library id, a string, so it never matched. Streams showed up only
 * because a stream's next song restarts the queue, which wrote one.
 *
 * Now every track change is matched to its queue track here, whatever kind of
 * id it plays under, and written once playback actually starts.
 */
internal object PlayRecording {

    /**
     * The queue track [mediaId] is playing: matched by its numeric id (a
     * stream) or by the library id it was queued under ([unifiedIdOf], a local
     * file or collection). The current track is tried first. A blank id (a
     * stream built straight from its URL) is the current track.
     */
    fun trackFor(
        mediaId: String,
        current: Track?,
        queue: List<Track>,
        unifiedIdOf: (Long) -> String?,
    ): Track? {
        if (mediaId.isBlank()) return current
        fun matches(track: Track) = track.id.toString() == mediaId || unifiedIdOf(track.id) == mediaId
        if (current != null && matches(current)) return current
        return queue.firstOrNull(::matches)
    }
}

/**
 * Whether a play is a new one. The same track started again within [windowMs]
 * is the same play (an error retry, a playlist rebuilt around it), unless it
 * is a repeat, which is a play of its own.
 */
internal class PlayDeduper(private val windowMs: Long = 15_000L) {
    private var lastTrackId: Long? = null
    private var lastAtMs = Long.MIN_VALUE

    fun shouldRecord(trackId: Long, nowMs: Long, repeat: Boolean): Boolean {
        val same = !repeat && trackId == lastTrackId && nowMs - lastAtMs in 0 until windowMs
        if (same) return false
        lastTrackId = trackId
        lastAtMs = nowMs
        return true
    }
}
