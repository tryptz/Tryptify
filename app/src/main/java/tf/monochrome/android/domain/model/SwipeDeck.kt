package tf.monochrome.android.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tf.monochrome.android.data.charts.normalizeForMatch

/** Why a card is in the deck, said on the card. */
@Serializable
sealed interface DeckReason {
    /** From today's discovery genre. */
    @Serializable
    @SerialName("today")
    data class Today(val genre: String) : DeckReason

    /** From a genre next door to the listener's own. */
    @Serializable
    @SerialName("genre")
    data class Genre(val genre: String) : DeckReason

    /** By an artist placed next to one the listener plays. */
    @Serializable
    @SerialName("artist")
    data class Artist(val playedArtist: String) : DeckReason
}

@Serializable
data class DeckCard(val track: UnifiedTrack, val reason: DeckReason) {
    val key: String get() = SwipeDeck.trackKey(track)
}

/**
 * Swipe to discover: one stack of songs a day, each kept or skipped.
 *
 * The deck is dealt from three bands — today's discovery genre, genres next
 * door to the listener's, and artists placed beside the ones they play — taken
 * in turn so no one source fills the stack. What it must never do is the thing
 * that makes a deck feel broken: deal a song already liked, deal one skipped
 * last week, deal the same song twice, or put one artist on two cards in a row.
 */
object SwipeDeck {

    /** Cards in a day's stack. */
    const val SIZE = 15

    /** How long a skipped song stays out of the deck. */
    const val SKIP_DAYS = 30

    /** Skips remembered at most; the oldest go first. */
    const val MAX_SKIPS = 600

    /**
     * What makes two cards the same song.
     *
     * By artist and title rather than id: the same recording has a different
     * id on every service, and a song hearted from TIDAL is still the song when
     * Discover is on Qobuz.
     */
    fun trackKey(track: UnifiedTrack): String =
        normalizeForMatch(track.artistName) + "|" + normalizeForMatch(track.title)

    /**
     * Deals a stack of up to [size] from [bands], skipping [exclude]d keys.
     *
     * Round-robin over the bands, so a stack with one rich source and two thin
     * ones is still a mix; then spaced, so an artist never takes two cards in a
     * row while there is anyone else left to put between them.
     */
    fun deal(bands: List<List<DeckCard>>, exclude: Set<String>, size: Int = SIZE): List<DeckCard> {
        val seenKeys = HashSet(exclude)
        val seenIds = HashSet<String>()
        val picked = ArrayList<DeckCard>(size)
        val cursors = IntArray(bands.size)
        while (picked.size < size) {
            var progressed = false
            for ((b, band) in bands.withIndex()) {
                if (picked.size >= size) break
                while (cursors[b] < band.size) {
                    val card = band[cursors[b]++]
                    if (card.track.id in seenIds || !seenKeys.add(card.key)) continue
                    seenIds.add(card.track.id)
                    picked.add(card)
                    progressed = true
                    break
                }
            }
            if (!progressed) break
        }
        return spaced(picked)
    }

    /** Reorders so no artist is on two cards in a row, where that is possible. */
    fun spaced(cards: List<DeckCard>): List<DeckCard> {
        val left = cards.toMutableList()
        val out = ArrayList<DeckCard>(cards.size)
        while (left.isNotEmpty()) {
            val previous = out.lastOrNull()?.let { artistOf(it) }
            val at = left.indexOfFirst { artistOf(it) != previous }.takeIf { it >= 0 } ?: 0
            out.add(left.removeAt(at))
        }
        return out
    }

    /** Skips still in force on [today], newest kept when there are too many. */
    fun liveSkips(skipped: Map<String, Long>, today: Long): Map<String, Long> =
        skipped.filterValues { today - it < SKIP_DAYS }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_SKIPS)
            .associate { it.key to it.value }

    private fun artistOf(card: DeckCard): String = normalizeForMatch(card.track.artistName)
}
