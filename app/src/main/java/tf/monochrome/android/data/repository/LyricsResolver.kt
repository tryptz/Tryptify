package tf.monochrome.android.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.api.AmllTtmlClient
import tf.monochrome.android.data.api.KpoeLyricsClient
import tf.monochrome.android.data.api.KugouLyricsClient
import tf.monochrome.android.data.api.LrcLibClient
import tf.monochrome.android.data.api.LrcRedLyricsClient
import tf.monochrome.android.data.api.LyricsQuery
import tf.monochrome.android.data.api.LyricsText
import tf.monochrome.android.data.api.NetEaseLyricsClient
import tf.monochrome.android.data.preferences.LyricsWordProvider
import tf.monochrome.android.domain.model.Lyrics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds lyrics for a song from every source the user allows, at once, and
 * keeps the best (see [LyricsRace]).
 *
 * Order breaks ties within a timing level, so it runs from the most exact
 * match to the loosest: the playing catalogue's own lyrics by id first, then
 * Apple's syllable timing (lrc.red, then LyricsPlus), the hand-timed AMLL
 * files, NetEase, Kugou, and LRCLib, which is line-timed at best.
 */
@Singleton
class LyricsResolver @Inject constructor(
    private val lrcRed: LrcRedLyricsClient,
    private val kpoe: KpoeLyricsClient,
    private val amll: AmllTtmlClient,
    private val netEase: NetEaseLyricsClient,
    private val kugou: KugouLyricsClient,
    private val lrcLib: LrcLibClient,
) {
    /**
     * [primary] is the playing catalogue's own lookup (TIDAL by id). It gets
     * [PRIMARY_HEAD_START_MS] alone first: word timing from it in that time
     * ends the lookup without a request to anyone else, as before. Otherwise
     * it joins the race first in line, still running if it has not answered:
     * its line timing beats every other line-timed source, but no longer
     * stops a word-timed one from replacing it.
     */
    suspend fun resolve(
        query: LyricsQuery,
        convertToRomaji: Boolean,
        mode: LyricsWordProvider,
        primary: (suspend () -> Lyrics?)? = null,
    ): Lyrics? = coroutineScope {
        val primaryAnswer = primary?.let { fetch ->
            async {
                try {
                    fetch()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
            }
        }
        val early = primaryAnswer?.let { withTimeoutOrNull(PRIMARY_HEAD_START_MS) { it.await() } }
        if (LyricsRace.tier(early) == 3) {
            Log.i(TAG, "\"${query.title}\": primary word-timed, no other source asked")
            return@coroutineScope early
        }

        val all = mode == LyricsWordProvider.BOTH
        val useNetEase = all || mode == LyricsWordProvider.NETEASE_ONLY
        val useKugou = all || mode == LyricsWordProvider.KUGOU_ONLY

        // One NetEase search serves both NetEase's lyrics and the AMLL file
        // named by the same id.
        val netEaseId = if (useNetEase) {
            async {
                try {
                    netEase.findSongId(query)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
            }
        } else {
            null
        }

        fun cleaned(fetch: suspend () -> Lyrics?): suspend () -> Lyrics? = { fetch()?.let { LyricsText.clean(it, query) } }

        val entries = buildList {
            if (primaryAnswer != null) add(LyricsRace.Entry("primary") { primaryAnswer.await() })
            if (all) {
                add(LyricsRace.Entry("lrc.red", cleaned { lrcRed.lookup(query, convertToRomaji) }))
                add(LyricsRace.Entry("lyricsplus", cleaned { kpoe.lookup(query, convertToRomaji) }))
                add(LyricsRace.Entry("amll", cleaned { netEaseId?.await()?.let { amll.lookup(it, convertToRomaji) } }))
            }
            if (useNetEase) {
                add(LyricsRace.Entry("netease", cleaned { netEaseId?.await()?.let { netEase.lyricsFor(it, convertToRomaji) } }))
            }
            if (useKugou) add(LyricsRace.Entry("kugou", cleaned { kugou.lookup(query, convertToRomaji) }))
            add(LyricsRace.Entry("lrclib", cleaned { lrcLib.lookup(query, convertToRomaji) }))
        }

        val winner = LyricsRace.run(entries)
        netEaseId?.cancel()
        primaryAnswer?.cancel()
        Log.i(
            TAG,
            if (winner == null) {
                "no lyrics for \"${query.title}\" by ${query.artist} (${entries.size} sources)"
            } else {
                "\"${query.title}\": ${winner.name} won, ${tierName(winner.lyrics)}, ${winner.elapsedMs} ms"
            },
        )
        winner?.lyrics
    }

    private fun tierName(lyrics: Lyrics): String = when (LyricsRace.tier(lyrics)) {
        3 -> "word-timed"
        2 -> "line-timed"
        else -> "plain"
    }

    private companion object {
        const val TAG = "LyricsResolver"

        /** Long enough for a TIDAL instance that answers at all; short enough not to stall the rest. */
        const val PRIMARY_HEAD_START_MS = 1_200L
    }
}
