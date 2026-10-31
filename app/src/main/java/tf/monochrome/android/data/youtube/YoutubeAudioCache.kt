package tf.monochrome.android.data.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The YouTube songs fetched whole onto the phone, played and downloaded from
 * there: `<dir>/<videoId>.<m4a|webm>`, each written as a `.part` of its own
 * first and renamed only once complete, so a file under its real name is
 * always whole.
 *
 * Two listeners of one song (the player resolving it while the queue
 * preloads it, or a download started while it plays) share one fetch. A
 * fetch nobody waits for any more (the song was skipped before it came) is
 * cancelled; one that completes stays for next time.
 *
 * The folder is held to [capBytes] by dropping what was played least
 * recently. A file used in the last [keepRecentMs] is never dropped, even
 * over the cap: the player may already hold it for the song after this one
 * (gapless playback queues a file's address ahead of time), and opens it
 * again on every seek.
 */
internal class YoutubeAudioCache(
    private val dir: File,
    private val scope: CoroutineScope,
    private val capBytes: Long = CAP_BYTES,
    private val keepRecentMs: Long = KEEP_RECENT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Writes [videoId]'s audio into the `.part` file it is given (appending
     * to what is there) and returns the extension it is (`m4a`, `webm`).
     */
    private val fetch: suspend (videoId: String, part: File) -> String,
) {
    private class Fetch(val part: File, val job: Deferred<File>) {
        var waiters = 0
    }

    private val lock = Any()
    private val inFlight = HashMap<String, Fetch>()

    /** [videoId]'s whole file, fetched first when it is not here yet. */
    suspend fun get(videoId: String): File {
        // Checked again after the touch: a trim running alongside may have
        // just dropped a file nobody had played for an hour.
        cached(videoId)?.let(::touch)?.takeIf { it.exists() }?.let { return it }
        val fetch = synchronized(lock) {
            // A finished fetch still listed failed (one that worked left its
            // file, found above): this is a new try.
            val running = inFlight[videoId]?.takeUnless { it.job.isCompleted }
            (running ?: start(videoId).also { inFlight[videoId] = it }).also { it.waiters++ }
        }
        try {
            return touch(fetch.job.await())
        } finally {
            synchronized(lock) {
                fetch.waiters--
                if (fetch.waiters == 0 && !fetch.job.isCompleted) {
                    fetch.job.cancel()
                    if (inFlight[videoId] === fetch) inFlight.remove(videoId)
                }
            }
        }
    }

    /** [videoId]'s whole file when it is already here, without fetching. */
    fun cached(videoId: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$videoId.") && !it.name.endsWith(PART) }

    private fun start(videoId: String): Fetch {
        // Named for this fetch alone: a dropped fetch of the same song may
        // still be finishing its last piece, and its clean-up must not take
        // the new one's file.
        val part = File(dir, "$videoId.${UUID.randomUUID().toString().take(8)}$PART")
        val job = scope.async {
            dir.mkdirs()
            dropStaleParts(videoId, own = part)
            try {
                val extension = fetch(videoId, part)
                val whole = File(dir, "$videoId.$extension")
                if (!part.renameTo(whole)) throw IOException("could not keep $videoId")
                touch(whole)
                trim(keep = whole)
                whole
            } finally {
                part.delete()
            }
        }
        val fetch = Fetch(part, job)
        job.invokeOnCompletion {
            synchronized(lock) { if (inFlight[videoId] === fetch) inFlight.remove(videoId) }
        }
        return fetch
    }

    /**
     * Parts of [videoId] no fetch is writing: left by one that died with the
     * app, or by one dropped. Never resumed: their stream links, and maybe
     * their formats, are gone with them.
     */
    private fun dropStaleParts(videoId: String, own: File) {
        val writing = synchronized(lock) { inFlight.values.map { it.part } }.toSet()
        dir.listFiles()
            ?.filter { it.name.startsWith("$videoId.") && it.name.endsWith(PART) && it != own && it !in writing }
            ?.forEach { it.delete() }
    }

    private fun touch(file: File): File {
        file.setLastModified(clock())
        return file
    }

    /**
     * Drops the least recently used whole files until the folder fits the
     * cap, sparing [keep] and anything used lately, and parts no fetch is
     * writing any more.
     */
    private fun trim(keep: File) {
        val now = clock()
        val writing = synchronized(lock) { inFlight.values.map { it.part.name } }.toSet()
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        files.filter { it.name.endsWith(PART) && it.name !in writing && now - it.lastModified() > keepRecentMs }
            .forEach { it.delete() }
        val whole = files.filter { !it.name.endsWith(PART) && it.exists() }
        var total = whole.sumOf { it.length() }
        for (file in whole.sortedBy { it.lastModified() }) {
            if (total <= capBytes) break
            if (file == keep || now - file.lastModified() < keepRecentMs) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    companion object {
        private const val PART = ".part"

        /** About sixty songs of AAC at 128 kbps. */
        const val CAP_BYTES = 256L shl 20

        const val KEEP_RECENT_MS = 60 * 60 * 1000L
    }
}
