package tf.monochrome.android.domain.model

/**
 * What a discovery shelf's title or reason says, without choosing the words.
 *
 * The feed used to write its lines as English templates — "Because you play
 * $name", "Popular in ${genre.lowercase()}" — which cannot be translated after
 * the fact: the name is already inside the sentence, and lower-casing it is an
 * English rule (German capitalises the noun, Chinese and Japanese have no case
 * at all). So the feed says *which* phrase it means and with what, and the UI
 * puts that into words in the listener's language.
 *
 * [english] is the line exactly as the feed always worded it. It is what
 * [DiscoveryShelf.title] and [DiscoveryShelf.reason] still carry, so anything
 * that reads those — de-duplication, track tagging, the tests that pin the
 * composition rules — sees no change at all.
 */
sealed interface ShelfPhrase {
    /** A name that is already final: a genre's, a curated seed's, a mood chip's own label as a title. */
    data class Name(val text: String) : ShelfPhrase

    data class ForMood(val mood: String) : ShelfPhrase
    data class StepOutFrom(val mood: String) : ShelfPhrase
    data object GenreItself : ShelfPhrase
    data object NextOnMap : ShelfPhrase
    data class PopularIn(val genre: String) : ShelfPhrase
    data class ByWayOf(val genre: String) : ShelfPhrase
    data object RankedByPlaysAndArtists : ShelfPhrase
    data object RankedByPlays : ShelfPhrase
    data object MostPlayedArtists : ShelfPhrase
    data object MatchedByName : ShelfPhrase
    data class Tempo(val low: Int, val high: Int) : ShelfPhrase
    data class TracksFor(val mood: String) : ShelfPhrase
    data class ReleasesThatFit(val mood: String) : ShelfPhrase
    data object ArtistsToStartFrom : ShelfPhrase
    data class LatestRelease(val year: String, val artist: String) : ShelfPhrase
    data class BecauseYouPlay(val artist: String) : ShelfPhrase
    data class ArtistsPlacedNextTo(val artist: String) : ShelfPhrase
    data object TracksYouHearted : ShelfPhrase

    // Titles
    data class MoodAlbums(val mood: String) : ShelfPhrase
    data class MoodArtists(val mood: String) : ShelfPhrase
    data class NewFrom(val artist: String) : ShelfPhrase
    data object FromYourFavorites : ShelfPhrase

    fun english(): String = when (this) {
        is Name -> text
        is ForMood -> "For ${mood.lowercase()}"
        is StepOutFrom -> "A step out from ${mood.lowercase()}"
        GenreItself -> "The genre itself"
        NextOnMap -> "Next to it on the map"
        is PopularIn -> "Popular in ${genre.lowercase()}"
        is ByWayOf -> "by way of $genre"
        RankedByPlaysAndArtists -> "ranked by plays and its most-played artists"
        RankedByPlays -> "ranked by plays"
        MostPlayedArtists -> "its most-played artists"
        MatchedByName -> "matched by name"
        is Tempo -> "$low–$high BPM"
        is TracksFor -> "Tracks for $mood"
        is ReleasesThatFit -> "Releases that fit $mood"
        ArtistsToStartFrom -> "Artists to start from"
        is LatestRelease -> "Their latest release ($year) — you play $artist"
        is BecauseYouPlay -> "Because you play $artist"
        is ArtistsPlacedNextTo -> "Artists Qobuz places next to $artist"
        TracksYouHearted -> "Tracks you hearted"
        is MoodAlbums -> "$mood albums"
        is MoodArtists -> "$mood artists"
        is NewFrom -> "New from $artist"
        FromYourFavorites -> "From your favorites"
    }
}

/** A shelf's title or reason: phrases joined by " · ". */
data class ShelfLine(val parts: List<ShelfPhrase>) {
    constructor(vararg parts: ShelfPhrase) : this(parts.toList())

    operator fun plus(phrase: ShelfPhrase): ShelfLine = ShelfLine(parts + phrase)

    /** The line in English, word for word as the feed has always written it. */
    fun english(): String = parts.joinToString(" · ") { it.english() }
}
