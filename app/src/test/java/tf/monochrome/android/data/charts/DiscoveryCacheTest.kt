package tf.monochrome.android.data.charts

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import java.io.File

/**
 * The feed's cache file: what it keeps, and that what it kept before still
 * comes back.
 *
 * The file stopped writing out default values, which halves it, and the
 * artists' top tracks gained a ceiling on tracks as well as on lists. Neither
 * may cost a listener the cache they already have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DiscoveryCacheTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file get() = File(context.cacheDir, "discovery-cache.json")

    @Before
    fun noFile() {
        file.delete()
    }

    private fun track(id: Long, title: String = "Track $id") = UnifiedTrack(
        id = "tidal_$id",
        title = title,
        durationSeconds = 200,
        artistName = "Ólafur Arnalds",
        artists = listOf(UnifiedArtistRef(id = 7, name = "Ólafur Arnalds")),
        albumTitle = "re:member",
        isDolbyAtmos = id % 2 == 0L,
        source = PlaybackSource.HiFiApi(id, AudioQuality.HI_RES),
        sourceType = SourceType.API,
    )

    @Test
    fun `a file written with every default still loads`() = runBlocking {
        // What builds before this one wrote: every field, defaults included.
        val before = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val snapshot = DiscoveryCache.Snapshot(
            tagCharts = mapOf("techno" to DiscoveryCache.Stamped(listOf(ChartEntry(1, "Acid Storm", "AIROD")), at = 1_000)),
            resolved = mapOf(
                "airod acid storm" to DiscoveryCache.Stamped(track(1), at = 2_000),
                "nobody stocks this" to DiscoveryCache.Stamped(null, at = 3_000),
            ),
            artistTopTracks = mapOf("olafur arnalds" to DiscoveryCache.Stamped(listOf(track(2), track(3)), at = 4_000)),
        )
        file.writeText(before.encodeToString(DiscoveryCache.Snapshot.serializer(), snapshot))

        assertEquals(snapshot, DiscoveryCache(context).restore())
    }

    @Test
    fun `leaving defaults out of the file reads back the same answers`() {
        // DiscoveryCache's own settings. A track is mostly defaults, and every
        // one of them is a constant, so an omitted field decodes to what it was.
        val now = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        val snapshot = DiscoveryCache.Snapshot(
            resolved = mapOf("a" to DiscoveryCache.Stamped(track(1), at = 5_000), "b" to DiscoveryCache.Stamped(null, at = 6_000)),
            artistTopTracks = mapOf("c" to DiscoveryCache.Stamped(listOf(track(2), track(3, title = "")), at = 7_000)),
        )
        val text = now.encodeToString(DiscoveryCache.Snapshot.serializer(), snapshot)
        assertEquals(snapshot, now.decodeFromString(DiscoveryCache.Snapshot.serializer(), text))
        // The stamp is written even though it has a default: that default is
        // the clock, and reading it back as "now" would make old answers new.
        assertTrue(text, "\"at\":5000" in text)
        assertFalse(text, "\"trackNumber\"" in text)
    }

    @Test
    fun `artists' top tracks are bounded by tracks, oldest dropped first`() = runBlocking {
        val cache = DiscoveryCache(context)
        cache.restore()
        val lists = DiscoveryCache.MAX_TOP_TRACKS / 20 + 50
        repeat(lists) { i ->
            cache.putArtistTopTracks("artist $i", (0 until 20).map { track(i * 100L + it) }, at = 10_000L + i)
        }
        val kept = cache.restore().artistTopTracks
        // Also cancels the write those puts scheduled, which would otherwise
        // land in whichever test runs next.
        cache.clear()
        assertTrue(kept.values.sumOf { it.value.size } <= DiscoveryCache.MAX_TOP_TRACKS)
        assertTrue("the newest list is kept", "artist ${lists - 1}" in kept)
        assertFalse("the oldest list is dropped", "artist 0" in kept)
    }

    @Test
    fun `replacing an artist's list does not count the old one against the budget`() = runBlocking {
        val cache = DiscoveryCache(context)
        cache.restore()
        val full = DiscoveryCache.MAX_TOP_TRACKS / 20
        repeat(full) { i ->
            cache.putArtistTopTracks("artist $i", (0 until 20).map { track(i * 100L + it) }, at = 10_000L + i)
        }
        // Exactly at the ceiling. A fresh answer for the newest artist replaces
        // its list rather than adding to it, so nobody else has to go.
        cache.putArtistTopTracks("artist ${full - 1}", (0 until 20).map { track(it.toLong()) }, at = 99_000L)
        val kept = cache.restore().artistTopTracks.size
        cache.clear()
        assertEquals(full, kept)
    }
}
