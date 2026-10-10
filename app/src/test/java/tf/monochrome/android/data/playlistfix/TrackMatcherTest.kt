package tf.monochrome.android.data.playlistfix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * The matcher is what decides a repaired or regenerated row's new id, so a
 * false match puts a different song in the playlist under the right title —
 * the very bug the repair exists to undo. Each case is a mismatch it must
 * refuse or a spelling difference between catalogues it must see through.
 */
class TrackMatcherTest {

    private fun track(
        title: String,
        artist: String,
        seconds: Int,
        id: Long = 1,
        album: String? = null,
        artists: List<String> = emptyList(),
    ) = Track(
        id = id,
        title = title,
        duration = seconds,
        artist = Artist(id = 0, name = artist),
        artists = artists.map { Artist(id = 0, name = it) },
        album = album?.let { Album(id = 0, title = it) },
    )

    private fun target(title: String, artist: String, seconds: Int, album: String? = null) =
        SongTarget(title = title, artist = artist, durationSeconds = seconds, album = album)

    // ── Same song, spelled differently ────────────────────────────────────

    @Test
    fun `case, punctuation and accents don't matter`() {
        val want = target("Lambada Vs On The Floor", "DJ Alan Quintero", 141)
        assertTrue(TrackMatcher.matches(want, track("Lambada vs. On the Floor", "DJ Alan Quintero", 141)))
        assertTrue(TrackMatcher.matches(target("Déjà Vu", "Beyoncé", 240), track("Deja Vu", "Beyonce", 240)))
    }

    @Test
    fun `a Qobuz em-dash version matches the same version in brackets`() {
        // Qobuz titles append the version after " — "; TIDAL puts it in brackets.
        val want = target("Purity — Radio Mix", "Steve Dekay", 177)
        assertTrue(TrackMatcher.matches(want, track("Purity (Radio Mix)", "Steve Dekay", 177)))
        assertTrue(TrackMatcher.matches(want, track("Purity - Radio Mix", "Steve Dekay", 178)))
    }

    @Test
    fun `labels that name no other recording are ignored`() {
        val want = target("Mamb — Original Mix", "Cloze Encounter", 338)
        assertTrue(TrackMatcher.matches(want, track("Mamb", "Cloze Encounter", 338)))
        assertTrue(TrackMatcher.matches(want, track("Mamb (Original Mix)", "Cloze Encounter", 336)))
        val song = target("Song (Remastered 2011)", "Band", 200)
        assertTrue(TrackMatcher.matches(song, track("Song", "Band", 205)))
        assertTrue(TrackMatcher.matches(song, track("Song - 2011 Remaster", "Band", 200)))
    }

    @Test
    fun `featured-artist credits are not part of the title`() {
        val want = target("Song (feat. Guest)", "Lead", 200)
        assertTrue(TrackMatcher.matches(want, track("Song", "Lead", 200, artists = listOf("Lead", "Guest"))))
        assertTrue(TrackMatcher.matches(target("Song feat. Guest", "Lead", 200), track("Song (feat. Guest)", "Lead", 200)))
        assertTrue(TrackMatcher.matches(target("Song [ft. Guest]", "Lead", 200), track("Song", "Lead", 201)))
    }

    @Test
    fun `any one credited artist is enough`() {
        val want = target("Let Your Body Talk", "Daddy DJ, Chico & Tonio", 219)
        assertTrue(TrackMatcher.matches(want, track("Let Your Body Talk", "Chico", 219)))
        assertTrue(TrackMatcher.matches(want, track("Let Your Body Talk", "Someone", 219, artists = listOf("Tonio"))))
        assertTrue(TrackMatcher.matches(target("Song", "The Weeknd", 200), track("Song", "Weeknd", 200)))
    }

    @Test
    fun `a long mix may drift further than a short song`() {
        // 5% of 9:34 is 28 s; a short song gets 15 s.
        assertTrue(TrackMatcher.matches(target("Reload (Space Club Mix)", "PPK", 574), track("Reload (Space Club Mix)", "PPK", 595)))
        assertNull(TrackMatcher.score(target("Song", "Band", 180), track("Song", "Band", 200)))
    }

    @Test
    fun `titles in other scripts match, and kana marks are kept`() {
        assertTrue(TrackMatcher.matches(target("夜に駆ける", "YOASOBI", 261), track("夜に駆ける", "YOASOBI", 261)))
        // が is not か: a dakuten is part of the letter, unlike an accent.
        assertNotEquals(TrackMatcher.fold("が"), TrackMatcher.fold("か"))
        assertEquals("beyonce", TrackMatcher.fold("Beyoncé"))
        assertEquals("dont stop", TrackMatcher.fold("Don't Stop"))
        assertEquals("a and b", TrackMatcher.fold("A&B"))
    }

    // ── Different songs ───────────────────────────────────────────────────

    @Test
    fun `two different named versions are two different songs`() {
        val want = target("Reload (Space Club Mix)", "PPK", 574)
        assertNull(TrackMatcher.score(want, track("Reload (Radio Edit)", "PPK", 574)))
        assertNull(TrackMatcher.score(want, track("Reload (Live)", "PPK", 574)))
    }

    @Test
    fun `a version on one side only needs the lengths to agree`() {
        // TIDAL's search results carry no version, so the radio mix and the
        // original are both just "Purity": only the length tells them apart.
        val want = target("Purity — Radio Mix", "Steve Dekay", 177)
        assertNull(TrackMatcher.score(want, track("Purity", "Steve Dekay", 390)))
        assertTrue(TrackMatcher.matches(want, track("Purity", "Steve Dekay", 178)))
        assertNull(TrackMatcher.score(target("Reload (Space Club Mix)", "PPK", 574), track("Reload (Original Mix)", "PPK", 420)))
    }

    @Test
    fun `another artist's song of the same name is refused`() {
        assertNull(TrackMatcher.score(target("Hello", "Adele", 295), track("Hello", "Lionel Richie", 295)))
        // "/" is not a separator, so AC/DC is not "DC".
        assertNull(TrackMatcher.score(target("Song", "AC/DC", 200), track("Song", "DC Talk", 200)))
    }

    @Test
    fun `a longer or different title is refused`() {
        assertNull(TrackMatcher.score(target("Love", "Band", 200), track("Love Song", "Band", 200)))
        assertNull(TrackMatcher.score(target("Part 1 - The Beginning", "Band", 200), track("Part 1", "Band", 200)))
        assertTrue(TrackMatcher.matches(target("Part 1 - The Beginning", "Band", 200), track("Part 1 - The Beginning", "Band", 200)))
    }

    @Test
    fun `a length far off is a different cut`() {
        assertNull(TrackMatcher.score(target("Song", "Band", 200), track("Song", "Band", 260)))
    }

    // ── Choosing ──────────────────────────────────────────────────────────

    @Test
    fun `best picks the closest sure match, not the first hit`() {
        val want = target("Purity — Radio Mix", "Steve Dekay", 177, album = "Purity")
        val hits = listOf(
            track("Purity", "Steve Dekay", 390, id = 1),          // the original mix
            track("Purity (Remix)", "Someone", 177, id = 2),      // a remix by another artist
            track("Purity", "Steve Dekay", 183, id = 3),          // close, but 6 s off
            track("Purity", "Steve Dekay", 177, id = 4, album = "Purity"),
        )
        assertEquals(4L, TrackMatcher.best(want, hits)?.id)
    }

    @Test
    fun `equal matches keep the service's order`() {
        val want = target("Song", "Band", 200)
        val hits = listOf(track("Song", "Band", 200, id = 7), track("Song", "Band", 200, id = 8))
        assertEquals(7L, TrackMatcher.best(want, hits)?.id)
    }

    @Test
    fun `best is null when nothing is the same song`() {
        val want = target("Song", "Band", 200)
        assertNull(TrackMatcher.best(want, listOf(track("Other", "Band", 200), track("Song", "Other Band", 200))))
        assertNull(TrackMatcher.best(want, emptyList()))
    }

    // ── Queries and versions ──────────────────────────────────────────────

    @Test
    fun `queries try the version first, then the bare title`() {
        assertEquals(
            listOf("Reload Space Club Mix PPK", "Reload PPK"),
            TrackMatcher.queries(target("Reload (Space Club Mix)", "PPK", 574)),
        )
        assertEquals(
            listOf("Purity Radio Mix Steve Dekay", "Purity Steve Dekay"),
            TrackMatcher.queries(target("Purity — Radio Mix", "Steve Dekay, Other", 177)),
        )
        // A credit is no version: one query, without it.
        assertEquals(listOf("Song Lead"), TrackMatcher.queries(target("Song (feat. Guest)", "Lead", 200)))
    }

    @Test
    fun `version keys keep only the words that tell recordings apart`() {
        assertNull(TrackMatcher.versionKey("Remastered 2011"))
        assertNull(TrackMatcher.versionKey("2011 Remaster"))
        assertNull(TrackMatcher.versionKey("Original Mix"))
        assertNull(TrackMatcher.versionKey("Album Version"))
        assertNull(TrackMatcher.versionKey("From the Motion Picture Soundtrack"))
        assertEquals("radio", TrackMatcher.versionKey("Radio Mix"))
        assertEquals("club space", TrackMatcher.versionKey("Space Club Mix"))
        assertEquals("edit radio", TrackMatcher.versionKey("Radio Edit"))
    }

    // ── The repair's verdict on a stored id ───────────────────────────────

    @Test
    fun `a stored id is right only when its own catalogue lists it as this song`() {
        val want = target("Purity — Radio Mix", "Steve Dekay", 177)
        val hits = listOf(
            track("Purity", "Steve Dekay", 177, id = 10),
            track("Something Else", "Other", 200, id = 20),
        )
        assertEquals(StoredIdCheck.RIGHT, checkStoredId(10, want, hits))
        assertEquals(StoredIdCheck.WRONG, checkStoredId(20, want, hits))
        assertEquals(StoredIdCheck.NOT_LISTED, checkStoredId(30, want, hits))
        assertFalse(checkStoredId(30, want, emptyList()) == StoredIdCheck.RIGHT)
    }
}
