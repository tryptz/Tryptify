package tf.monochrome.android.ui.discover

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.ShelfLine
import tf.monochrome.android.domain.model.ShelfPhrase

/**
 * A shelf's title or reason in the listener's language.
 *
 * [fallback] is the English the shelf already carries, for a shelf built
 * without phrases (none are, today, but a row from an older cache would be).
 */
@Composable
internal fun shelfText(line: ShelfLine?, fallback: String?): String? {
    if (line == null) return fallback
    val separator = stringResource(R.string.shelf_separator)
    return line.parts.map { phrase(it) }.joinToString(separator)
}

@Composable
private fun phrase(p: ShelfPhrase): String = when (p) {
    is ShelfPhrase.Name -> moodLabel(p.text)
    is ShelfPhrase.ForMood -> stringResource(R.string.shelf_for_mood, inSentence(moodLabel(p.mood)))
    is ShelfPhrase.StepOutFrom -> stringResource(R.string.shelf_step_out_from, inSentence(moodLabel(p.mood)))
    ShelfPhrase.GenreItself -> stringResource(R.string.shelf_genre_itself)
    ShelfPhrase.NextOnMap -> stringResource(R.string.shelf_next_on_map)
    // Genre names are proper names and stay as the map spells them; only
    // their case follows the sentence they are dropped into.
    is ShelfPhrase.PopularIn -> stringResource(R.string.shelf_popular_in, inSentence(p.genre))
    is ShelfPhrase.ByWayOf -> stringResource(R.string.shelf_by_way_of, p.genre)
    ShelfPhrase.RankedByPlaysAndArtists -> stringResource(R.string.shelf_ranked_by_plays_and_artists)
    ShelfPhrase.RankedByPlays -> stringResource(R.string.shelf_ranked_by_plays)
    ShelfPhrase.MostPlayedArtists -> stringResource(R.string.shelf_most_played_artists)
    ShelfPhrase.MatchedByName -> stringResource(R.string.shelf_matched_by_name)
    is ShelfPhrase.Tempo -> stringResource(R.string.shelf_tempo, p.low, p.high)
    is ShelfPhrase.TracksFor -> stringResource(R.string.shelf_tracks_for, inSentence(moodLabel(p.mood)))
    is ShelfPhrase.ReleasesThatFit -> stringResource(R.string.shelf_releases_that_fit, inSentence(moodLabel(p.mood)))
    ShelfPhrase.ArtistsToStartFrom -> stringResource(R.string.shelf_artists_to_start_from)
    is ShelfPhrase.LatestRelease -> stringResource(R.string.shelf_latest_release, p.year, p.artist)
    is ShelfPhrase.BecauseYouPlay -> stringResource(R.string.shelf_because_you_play, p.artist)
    is ShelfPhrase.ArtistsPlacedNextTo -> stringResource(R.string.shelf_artists_placed_next_to, p.artist)
    ShelfPhrase.TracksYouHearted -> stringResource(R.string.shelf_tracks_you_hearted)
    is ShelfPhrase.MoodAlbums -> stringResource(R.string.shelf_mood_albums, moodLabel(p.mood))
    is ShelfPhrase.MoodArtists -> stringResource(R.string.shelf_mood_artists, moodLabel(p.mood))
    is ShelfPhrase.NewFrom -> stringResource(R.string.shelf_new_from, p.artist)
    ShelfPhrase.FromYourFavorites -> stringResource(R.string.shelf_from_your_favorites)
}

/**
 * A name as it reads inside a sentence. English, French, Spanish and Turkish
 * lower-case it there ("Popular in jazz"); German keeps the capital a noun
 * has, and Chinese and Japanese have no case. Lower-cased in the reader's own
 * locale, so a Turkish word keeps its dotted and dotless i apart.
 */
@Composable
private fun inSentence(name: String): String {
    if (stringResource(R.string.l10n_lowercase_names_in_sentences) != "true") return name
    val locale = LocalConfiguration.current.locales[0]
    return name.lowercase(locale)
}

/**
 * A mood's name in the listener's language, by its English label in the
 * bundled data (genre_graph.json's moods and discovery_moods.json). Anything
 * else — a genre, a curated seed — is a name and comes back as it is.
 */
@Composable
internal fun moodLabel(english: String): String =
    MOOD_LABELS[english.lowercase()]?.let { stringResource(it) } ?: english

@StringRes
private val MOOD_LABELS: Map<String, Int> = mapOf(
    "focus" to R.string.mood_focus,
    "late night" to R.string.mood_late_night,
    "workout" to R.string.mood_workout,
    "party" to R.string.mood_party,
    "chill" to R.string.mood_chill,
    "commute" to R.string.mood_commute,
    "wind down" to R.string.mood_wind_down,
    "uplifting" to R.string.mood_uplifting,
    "sleep" to R.string.mood_sleep,
    "rage" to R.string.mood_rage,
    "unhinged" to R.string.mood_unhinged,
    "menacing" to R.string.mood_menacing,
    "dread" to R.string.mood_dread,
    "hypnotic" to R.string.mood_hypnotic,
    "euphoric" to R.string.mood_euphoric,
    "feral" to R.string.mood_feral,
    "melancholy" to R.string.mood_melancholy,
    "swagger" to R.string.mood_swagger,
    "doom" to R.string.mood_doom,
    "dysphoric" to R.string.mood_dysphoric,
    "blissed out" to R.string.mood_blissed_out,
    "deep cuts" to R.string.mood_deep_cuts,
    // Curated seeds that are words rather than genre names.
    "electronic" to R.string.chip_electronic,
    "classical" to R.string.chip_classical,
    "pop hits" to R.string.chip_pop_hits,
)
