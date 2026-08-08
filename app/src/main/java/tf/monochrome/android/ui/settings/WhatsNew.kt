package tf.monochrome.android.ui.settings

import tf.monochrome.android.BuildConfig

/**
 * The "What's New" entries shown in About, and the version they belong to.
 *
 * Deliberately hand-written rather than generated from the changelog: the
 * changelog explains *how* something was fixed for whoever reads the diff,
 * and this has to say what changed for someone who just wants their music to
 * play. Two or three lines each, no jargon that isn't already on a settings
 * screen.
 *
 * When cutting a release, add a new [WhatsNewRelease] at the top with the new
 * versionCode. Anything with a versionCode above what the user has already
 * acknowledged is what the update notice offers them.
 */
data class WhatsNewEntry(
    val title: String,
    val body: String,
)

data class WhatsNewRelease(
    val versionCode: Int,
    val versionName: String,
    val entries: List<WhatsNewEntry>,
)

object WhatsNew {

    /** Newest first. */
    val releases: List<WhatsNewRelease> = listOf(
        WhatsNewRelease(
            versionCode = 184,
            versionName = "1.8.4",
            entries = listOf(
                WhatsNewEntry(
                    title = "Your own files play first",
                    body = "Tap a song anywhere and it plays the copy already on your device " +
                        "instead of streaming it — including tracks you downloaded, which used " +
                        "to re-stream from every screen but Downloads.",
                ),
                WhatsNewEntry(
                    title = "Blend between tracks",
                    body = "One slider in Settings decides what happens between songs. At zero " +
                        "they run straight into each other with no gap; add time and they " +
                        "overlap, one fading out as the next fades in.",
                ),
                WhatsNewEntry(
                    title = "Songs start faster",
                    body = "Playback begins after a quarter of the buffering it used to need, " +
                        "and Qobuz tracks now start as soon as the first audio arrives instead " +
                        "of waiting for the whole file to download.",
                ),
                WhatsNewEntry(
                    title = "Qobuz plays from Qobuz",
                    body = "Only the first track of a Qobuz album was actually coming from " +
                        "Qobuz — every song after it was fetched from the wrong service, which " +
                        "could fail outright or play a different recording.",
                ),
                WhatsNewEntry(
                    title = "Tracks no longer stall at the start",
                    body = "A song would sometimes load and then sit at 0:00 until you pressed " +
                        "play, most often the first time you heard it. It now starts on its own.",
                ),
                WhatsNewEntry(
                    title = "Auto-download liked songs",
                    body = "Turn it on in Settings and every song you like from then on is saved " +
                        "for offline. Songs you liked earlier are left alone, so switching it on " +
                        "never kicks off a huge download.",
                ),
                WhatsNewEntry(
                    title = "Inflator oversampling",
                    body = "The Inflator can now run at 2x or 4x to keep its harmonics from " +
                        "folding back into the audible band as grit. Measured about 30 dB " +
                        "cleaner on bright material.",
                ),
                WhatsNewEntry(
                    title = "Tap the artwork to play",
                    body = "Album art in a song row plays the song instead of opening the album, " +
                        "and the artist and album links are harder to hit by accident.",
                ),
            ),
        ),
    )

    /** The release the app is currently running, if it has notes. */
    val current: WhatsNewRelease? get() = releases.firstOrNull()

    /**
     * Releases the user hasn't acknowledged yet — everything newer than
     * [seenVersionCode]. Empty when they're up to date.
     */
    fun unseen(seenVersionCode: Int): List<WhatsNewRelease> =
        releases.filter { it.versionCode > seenVersionCode }

    /**
     * Whether the update notice should appear.
     *
     * Someone upgrading has never written this preference either, so there is
     * no way to tell them apart from a fresh install by the stored value
     * alone. Showing it to both is the safe side of that: a new user sees one
     * dismissible bar describing the app they just installed, whereas the
     * other choice silently hides the notes from every existing user on the
     * upgrade this exists for.
     */
    fun shouldNotify(seenVersionCode: Int, neverShow: Boolean): Boolean {
        if (neverShow) return false
        return unseen(seenVersionCode).isNotEmpty()
    }

    /** The versionCode to record once the notes have been read or dismissed. */
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
}
