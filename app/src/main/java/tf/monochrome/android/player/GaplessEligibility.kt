package tf.monochrome.android.player

/**
 * Which media URIs may be handed to ExoPlayer *before* they are needed.
 *
 * Gapless playback requires the next track to be in the player's own playlist
 * while the current one is still going, so its decoder can spin up and hand
 * over without a gap. That means committing to a URI minutes ahead of use, and
 * only some of this app's URIs survive that wait:
 *
 *  - `file` / `content` — a path on the device. Stable by definition.
 *  - `qobuz` — resolved by QobuzPartialDataSource at open time, not now, so
 *    there is nothing in it to expire. This is what makes Qobuz gapless.
 *  - `http` / `https` — a signed or time-limited stream URL (TIDAL, Apple).
 *    Queuing one ahead is exactly the staleness the one-track-at-a-time design
 *    was built to avoid, so these are refused.
 *  - `data` — an inline DASH manifest, rebuilt per play by PlaybackService and
 *    meaningless to pre-queue.
 *
 * Refusing a scheme costs nothing but the gap: playback falls back to the
 * existing resolve-on-demand path for that transition.
 */
internal object GaplessEligibility {

    private val STABLE_SCHEMES = setOf(
        "file",
        "content",
        "asset",
        "rawresource",
        "android.resource",
        tf.monochrome.android.data.cache.QobuzStreamUri.SCHEME,
    )

    /** True when [uri] will still resolve to the same audio some minutes from now. */
    fun isStableUri(uri: String?): Boolean {
        val scheme = schemeOf(uri) ?: return false
        return scheme in STABLE_SCHEMES
    }

    /**
     * The scheme of [uri], lowercased, or null when there isn't one. A bare
     * filesystem path counts as `file` — that's how the download store records
     * internal-storage paths.
     */
    private fun schemeOf(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        if (uri.startsWith("/")) return "file"
        val separator = uri.indexOf("://")
        if (separator <= 0) return null
        return uri.substring(0, separator).lowercase()
    }
}
