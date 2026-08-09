package tf.monochrome.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The familiar ↔ adventurous knob.
 *
 * Two things are worth pinning. The knob has to actually change the feed —
 * a control that relabels the same page is worse than no control — and it
 * must not be able to produce a feed made entirely of strangers or entirely of
 * the listener's own library, since neither is discovery.
 */
class DiscoveryAdventureTest {

    @Test
    fun `moving the knob really changes the shelf mix`() {
        val familiar = DiscoveryAdventure.shelfMix(0f)
        val adventurous = DiscoveryAdventure.shelfMix(1f)
        assertNotEquals(familiar, adventurous)
        assertTrue(
            "adventurous should lean on neighbouring artists",
            adventurous.explore > familiar.explore,
        )
        assertTrue(
            "adventurous should lean on genres",
            adventurous.genre > familiar.genre,
        )
        assertTrue(
            "familiar should lean on artists already played",
            familiar.familiar > adventurous.familiar,
        )
    }

    @Test
    fun `neither end starves the other kind of shelf`() {
        // A feed of nothing but strangers is unusable; a feed with no
        // strangers in it isn't discovery. Every setting keeps some of each.
        for (step in 0..20) {
            val a = step / 20f
            val mix = DiscoveryAdventure.shelfMix(a)
            assertTrue("familiar starved at $a", mix.familiar >= 1)
            assertTrue("explore starved at $a", mix.explore >= 1)
            assertTrue("genre starved at $a", mix.genre >= 1)
        }
    }

    @Test
    fun `the shelf budget stays sane across the range`() {
        for (step in 0..20) {
            val mix = DiscoveryAdventure.shelfMix(step / 20f)
            assertTrue(
                "budget blew out at ${step / 20f}: ${mix.total}",
                mix.total in 3..DiscoveryAdventure.SHELF_BUDGET,
            )
        }
    }

    @Test
    fun `out-of-range and non-finite values fall back rather than wedging the feed`() {
        assertEquals(0f, DiscoveryAdventure.clamp(-3f), 0.0001f)
        assertEquals(1f, DiscoveryAdventure.clamp(9f), 0.0001f)
        assertEquals(DiscoveryAdventure.DEFAULT, DiscoveryAdventure.clamp(Float.NaN), 0.0001f)
        assertEquals(
            DiscoveryAdventure.DEFAULT,
            DiscoveryAdventure.clamp(Float.POSITIVE_INFINITY),
            0.0001f,
        )
    }

    @Test
    fun `the two artist bands can be sliced without overlapping`() {
        // The feed asks for familiar + explore seed artists and takes disjoint
        // slices. Sizing the request to the wider band and rotating instead
        // silently produced the identity — with N artists, rotating by N — so
        // both bands started at the same artist and the page carried
        // "New from X" and "Because you play X" for the same X.
        for (step in 0..20) {
            val mix = DiscoveryAdventure.shelfMix(step / 20f)
            val seeds = List(mix.familiar + mix.explore) { "artist$it" }
            val familiarBand = seeds.take(mix.familiar)
            val exploreBand = seeds.drop(mix.familiar).take(mix.explore)

            assertEquals(mix.familiar, familiarBand.size)
            assertEquals(mix.explore, exploreBand.size)
            assertTrue(
                "bands overlapped at ${step / 20f}",
                familiarBand.intersect(exploreBand.toSet()).isEmpty(),
            )
        }
    }

}
