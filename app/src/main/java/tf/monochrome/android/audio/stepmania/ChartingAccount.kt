// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.data.cloud.CloudChartingAccount
import tf.monochrome.android.data.cloud.CloudError
import tf.monochrome.android.data.cloud.CloudRepository

/**
 * Charting on Tryptify's own server, paid by the minute of music
 * (docs/plans/trypt-mania-paid-charting.md): the listener's minutes, what
 * they have charted with them, and the packs on sale, as Tryptify Cloud
 * holds them (tryptify-servers/cloud/src/charting.ts). The server is what
 * takes a minute; this is the phone's copy of its answer.
 *
 * A song counts its length rounded up to the whole minute, at least
 * [MIN_SONG_MINUTES]; a pack says about how many songs it holds at
 * [SONG_MINUTES], a pop song's length. The first song is free, once per
 * account.
 */
data class ChartingAccount(
    val minutesBought: Int = 0,
    val minutesUsed: Int = 0,
    /** Minutes of songs being charted right now. */
    val minutesReserved: Int = 0,
    val songsCharted: Int = 0,
    val freeSongUsed: Boolean = false,
    /** The free song is still there, and the server's daily brake on free songs is off. */
    val freeSongAvailable: Boolean = false,
    val packs: List<ChartingPack> = ChartingPacks.ALL,
    /** Newest first. */
    val history: List<ChartingEntry> = emptyList(),
    /** Read from Cloud: false before the first read, and when the read failed. */
    val loaded: Boolean = false,
    val signedIn: Boolean = false,
) {
    val minutesLeft: Int get() = (minutesBought - minutesUsed - minutesReserved).coerceAtLeast(0)

    /** How much of what was bought is gone; 0 with nothing bought. */
    val usedFraction: Float get() = if (minutesBought <= 0) 0f else ((minutesUsed + minutesReserved).toFloat() / minutesBought).coerceIn(0f, 1f)

    val songsLeft: Int get() = minutesLeft / SONG_MINUTES

    /** Whether a song [seconds] long can go to Tryptify's server now. */
    fun canChart(seconds: Int): Boolean =
        signedIn && seconds in 1..MAX_SONG_MINUTES * 60 && (freeSongAvailable || minutesLeft >= minutesFor(seconds))

    companion object {
        const val SONG_MINUTES = 4
        const val MIN_SONG_MINUTES = 2
        const val MAX_SONG_MINUTES = 10

        /** The minutes a song [seconds] long takes: its length rounded up, at least [MIN_SONG_MINUTES]. */
        fun minutesFor(seconds: Int): Int = maxOf(MIN_SONG_MINUTES, ceil(seconds / 60.0).toInt())

        internal fun of(dto: CloudChartingAccount): ChartingAccount = ChartingAccount(
            minutesBought = dto.minutesBought,
            minutesUsed = dto.minutesUsed,
            minutesReserved = dto.minutesReserved,
            songsCharted = dto.songsCharted,
            freeSongUsed = dto.freeSongUsed,
            freeSongAvailable = dto.freeSongAvailable,
            packs = dto.packs.map { ChartingPack(it.id, it.minutes, it.priceCents, it.onSale) }.ifEmpty { ChartingPacks.ALL },
            history = dto.history.map { entry ->
                ChartingEntry(
                    title = entry.title,
                    at = entry.at,
                    minutes = entry.minutes,
                    kind = when (entry.kind) {
                        "bought" -> ChartingEntry.Kind.BOUGHT
                        "charted" -> ChartingEntry.Kind.CHARTED
                        "free" -> ChartingEntry.Kind.FREE
                        else -> ChartingEntry.Kind.FAILED
                    },
                )
            },
            loaded = true,
            signedIn = true,
        )
    }
}

/** One line of the account's history: minutes bought, or a song charted. */
data class ChartingEntry(
    val title: String,
    /** When, as the server wrote it (ISO-8601). */
    val at: String,
    /** Minutes added (a pack) or taken (a song); 0 for a free or failed song. */
    val minutes: Int,
    val kind: Kind,
) {
    enum class Kind { BOUGHT, CHARTED, FAILED, FREE }
}

/** A pack of minutes: a one-time purchase, never a subscription. Prices are in US cents, as Creem charges them. */
data class ChartingPack(val id: String, val minutes: Int, val priceCents: Int, val onSale: Boolean = true) {
    val songs: Int get() = minutes / ChartingAccount.SONG_MINUTES
}

/** The packs as priced (2¢ a minute, 2026-10-28), shown until the server has said its own. */
object ChartingPacks {
    val STARTER = ChartingPack("starter", minutes = 150, priceCents = 299)
    val LIBRARY = ChartingPack("library", minutes = 500, priceCents = 999)
    val BIG = ChartingPack("big", minutes = 1000, priceCents = 1999)
    val ALL = listOf(STARTER, LIBRARY, BIG)

    /** The one most listeners pick, marked as such. */
    const val MOST_PICKED = "library"
}

/**
 * The listener's charting account as this phone last read it from Tryptify
 * Cloud. [refresh] reads it again; the screen does when it opens and comes
 * back, and the stems client after each song charted there.
 */
@Singleton
class ChartingAccountStore @Inject constructor(private val cloud: CloudRepository) {

    private val _account = MutableStateFlow(ChartingAccount())
    val account: StateFlow<ChartingAccount> = _account.asStateFlow()

    /** Reads the account from Cloud. Signed out, the account is empty and says so; unreachable, what was known stays. */
    suspend fun refresh(): Result<ChartingAccount> = cloud.chartingAccount()
        .map { dto -> ChartingAccount.of(dto).also { _account.value = it } }
        .onFailure { failure ->
            if (failure is CloudError.SignedOut) _account.value = ChartingAccount(loaded = true, signedIn = false)
        }

    /** A checkout link for [pack], for the browser; a failure says why there is none. */
    suspend fun buy(pack: ChartingPack): Result<String> = cloud.chartingCheckoutUrl(pack.id)
}
