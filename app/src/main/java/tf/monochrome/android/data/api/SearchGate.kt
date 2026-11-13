package tf.monochrome.android.data.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import tf.monochrome.android.data.charts.SingleFlight

/**
 * Every search the app sends its TIDAL server goes through here.
 *
 * TIDAL counts searches per account, per day, and the account is the
 * server's: every phone using that server draws on the same allowance. One
 * Discover page on TIDAL spent it for everybody (about 850 searches in two and
 * a half minutes, eleven a second at the peak), and search then answered 429
 * for the rest of the day. The client had nothing between a screen and the
 * server: no limit on how many searches ran at once, the same question asked
 * twice was sent twice, and a 429 was tried again half a second later.
 *
 * So, for searches only:
 * * the same search under way twice is one request ([SingleFlight]);
 * * at most [permits] run at once, started no closer than [spacingMs] apart.
 *   A burst is spread out; a search typed by hand, which sends its requests
 *   one after another, never waits;
 * * a 429 is not tried again. Searching pauses for the wait the server names
 *   in Retry-After (within [MIN_PAUSE_S] to [MAX_PAUSE_S]; [UNKNOWN_PAUSE_S]
 *   when it names none), and until then each search fails at once with
 *   [Refused] instead of being sent.
 *
 * Looking a song up by its id (/info/, /track/, /album/) is not a search and
 * does not come through here.
 */
class SearchGate(
    private val permits: Int = PERMITS,
    private val spacingMs: Long = SPACING_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The server refused searches (429); [waitMs] is how long they stay paused. */
    class Refused(val waitMs: Long) : Exception("TIDAL search is rate limited (HTTP 429)")

    private val slots = Semaphore(permits)
    private val flights = SingleFlight<String, String>()
    private val pacing = Mutex()
    private var nextStart = 0L

    @Volatile
    private var pausedUntil = 0L

    /** [fetch]'s answer for [path], shared with every caller asking for the same path meanwhile. */
    suspend fun run(path: String, fetch: suspend () -> String): String {
        failIfPaused()
        return flights.run(path) {
            slots.withPermit {
                val wait = pacing.withLock {
                    val start = maxOf(now(), nextStart)
                    nextStart = start + spacingMs
                    start - now()
                }
                if (wait > 0) sleep(wait)
                // A search ahead in the queue may have been refused meanwhile.
                failIfPaused()
                fetch()
            }
        }
    }

    /**
     * Records a 429, with the Retry-After the server sent (seconds) if any.
     * Returns the failure for the search that got it.
     */
    fun refused(retryAfterSeconds: Long?): Refused {
        val pauseMs = (retryAfterSeconds ?: UNKNOWN_PAUSE_S).coerceIn(MIN_PAUSE_S, MAX_PAUSE_S) * 1000
        pausedUntil = now() + pauseMs
        return Refused(pauseMs)
    }

    private fun failIfPaused() {
        val left = pausedUntil - now()
        if (left > 0) throw Refused(left)
    }

    companion object {
        const val PERMITS = 4
        const val SPACING_MS = 150L
        const val MIN_PAUSE_S = 2L
        const val MAX_PAUSE_S = 60L
        const val UNKNOWN_PAUSE_S = 5L
    }
}
