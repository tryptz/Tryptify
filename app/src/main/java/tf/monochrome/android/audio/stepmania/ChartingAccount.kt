// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Charting on Tryptify's own server, paid by the minute of music
 * (docs/plans/trypt-mania-paid-charting.md): the listener's minutes, what
 * they have charted with them, and the packs on sale.
 *
 * A song counts its length rounded up to the whole minute, at least
 * [MIN_SONG_MINUTES]; a pack says about how many songs it holds at
 * [SONG_MINUTES], a pop song's length. The first song is free, once per
 * account.
 */
data class ChartingAccount(
    val minutesBought: Int = 0,
    val minutesUsed: Int = 0,
    val songsCharted: Int = 0,
    val freeSongUsed: Boolean = false,
    /** Newest first. */
    val history: List<ChartingEntry> = emptyList(),
) {
    val minutesLeft: Int get() = (minutesBought - minutesUsed).coerceAtLeast(0)

    /** How much of what was bought is gone; 0 with nothing bought. */
    val usedFraction: Float get() = if (minutesBought <= 0) 0f else (minutesUsed.toFloat() / minutesBought).coerceIn(0f, 1f)

    val songsLeft: Int get() = minutesLeft / SONG_MINUTES

    companion object {
        const val SONG_MINUTES = 4
        const val MIN_SONG_MINUTES = 2
        const val MAX_SONG_MINUTES = 10
    }
}

/** One line of the account's history: minutes bought, or a song charted. */
data class ChartingEntry(
    val title: String,
    val atMillis: Long,
    /** Minutes added (a pack) or taken (a song); 0 for a failed song, which gives its minutes back. */
    val minutes: Int,
    val kind: Kind,
) {
    enum class Kind { BOUGHT, CHARTED, FAILED, FREE }
}

/** A pack of minutes: a one-time purchase, never a subscription. Prices are in US cents. */
data class ChartingPack(val id: String, val minutes: Int, val priceCents: Int) {
    val songs: Int get() = minutes / ChartingAccount.SONG_MINUTES
}

/** The packs on sale, at 2¢ a minute (the plan, 2026-10-28). */
object ChartingPacks {
    val STARTER = ChartingPack("starter", minutes = 150, priceCents = 299)
    val LIBRARY = ChartingPack("library", minutes = 500, priceCents = 999)
    val BIG = ChartingPack("big", minutes = 1000, priceCents = 1999)
    val ALL = listOf(STARTER, LIBRARY, BIG)

    /** The one most listeners pick, marked as such. */
    val MOST_PICKED = LIBRARY
}

/**
 * The listener's charting account as this phone knows it.
 *
 * Tryptify Cloud will hold the minutes and the free song (the server is what
 * takes a minute, never the app); until it does, every account is empty and
 * nothing is for sale: [buy] says so. The screen is built on this so the
 * server slots in behind it.
 */
@Singleton
class ChartingAccountStore @Inject constructor() {

    private val _account = MutableStateFlow(ChartingAccount())
    val account: StateFlow<ChartingAccount> = _account.asStateFlow()

    /** Starts buying [pack]; false while packs are not on sale. */
    suspend fun buy(pack: ChartingPack): Boolean = false
}
