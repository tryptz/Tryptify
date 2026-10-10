package tf.monochrome.android.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.domain.model.Lyrics
import kotlin.time.TimeSource

/**
 * Runs every lyrics source at once and keeps the best answer.
 *
 * Asking one source after another made a miss slow — four sources at up to
 * 15 s each — and let the first answer win even when it was only line-timed
 * and a later source had word timing.
 *
 * Better means: word timing over line timing over plain text, then the source
 * listed first. A word-timed answer is final the moment every source above it
 * has answered. If one arrives while higher sources are still out, they get
 * [graceMs] to beat it. Once only a line-timed or plain answer is in hand, the
 * rest get [settleMs] to come back with word timing: waiting for every source
 * left a song no source has karaoke for on "Loading lyrics…" until the
 * slowest gave up. [deadlineMs] bounds the whole wait.
 */
internal object LyricsRace {

    class Entry(val name: String, val fetch: suspend () -> Lyrics?)

    data class Winner(val name: String, val lyrics: Lyrics, val elapsedMs: Long)

    const val DEFAULT_GRACE_MS = 1_500L

    /** Every word-timed hit measured live (2026-10) came back within this of the first line-timed one. */
    const val DEFAULT_SETTLE_MS = 3_000L
    const val DEFAULT_DEADLINE_MS = 15_000L

    /** 3 word-timed, 2 line-timed, 1 plain, 0 nothing. */
    fun tier(lyrics: Lyrics?): Int = when {
        lyrics == null || lyrics.lines.none { it.text.isNotBlank() } -> 0
        lyrics.isSynced && lyrics.lines.any { it.words.isNotEmpty() } -> 3
        lyrics.isSynced -> 2
        else -> 1
    }

    suspend fun run(
        entries: List<Entry>,
        graceMs: Long = DEFAULT_GRACE_MS,
        settleMs: Long = DEFAULT_SETTLE_MS,
        deadlineMs: Long = DEFAULT_DEADLINE_MS,
    ): Winner? = coroutineScope {
        if (entries.isEmpty()) return@coroutineScope null
        val clock = TimeSource.Monotonic.markNow()
        val results = arrayOfNulls<Lyrics>(entries.size)
        val finished = BooleanArray(entries.size)
        // Each child reports its index once its result is stored; reading a
        // result only after its index arrives is what makes this safe on a
        // multi-threaded dispatcher.
        val done = Channel<Int>(Channel.UNLIMITED)
        val jobs = entries.mapIndexed { i, entry ->
            launch {
                results[i] = try {
                    entry.fetch()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    null
                }
                done.trySend(i)
            }
        }

        fun best(): Int? = entries.indices
            .filter { finished[it] && tier(results[it]) > 0 }
            .maxWithOrNull(compareBy<Int> { tier(results[it]) }.thenByDescending { it })

        var graceEndMs: Long? = null
        var settleEndMs: Long? = null
        var pending = entries.size
        while (pending > 0) {
            // With word timing in hand only the grace for higher sources is
            // left to wait out; until then, the settle window once anything is.
            val waitUntil = graceEndMs ?: settleEndMs ?: Long.MAX_VALUE
            val waitMs = minOf(deadlineMs, waitUntil) - clock.elapsedNow().inWholeMilliseconds
            if (waitMs <= 0) break
            val index = withTimeoutOrNull(waitMs) { done.receive() } ?: break
            finished[index] = true
            pending--
            val leader = best() ?: continue
            if (tier(results[leader]) == 3) {
                if ((0 until leader).all { finished[it] }) break
                if (graceEndMs == null) graceEndMs = clock.elapsedNow().inWholeMilliseconds + graceMs
            } else if (settleEndMs == null) {
                settleEndMs = clock.elapsedNow().inWholeMilliseconds + settleMs
            }
        }
        jobs.forEach { it.cancel() }
        val winner = best() ?: return@coroutineScope null
        Winner(entries[winner].name, results[winner]!!, clock.elapsedNow().inWholeMilliseconds)
    }
}
