package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkKeysTest {

    private val store = "/data/user/0/tf.monochrome.android/files/artwork"
    private val oldStore = "/data/user/0/tf.monochrome.android/cache/artwork"

    // ── Naming ──────────────────────────────────────────────────────
    //
    // The point of the new scheme: one cover is one file. Keyed by the audio
    // file's path, a 500-track album wrote 500 identical JPEGs — which is what
    // made the store worth reclaiming, and every reclaim cost a full rescan.

    @Test
    fun `identical cover bytes produce one name`() {
        val cover = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(ArtworkKeys.nameFor(cover), ArtworkKeys.nameFor(cover.copyOf()))
    }

    @Test
    fun `different covers produce different names`() {
        assertNotEquals(
            ArtworkKeys.nameFor(byteArrayOf(1, 2, 3)),
            ArtworkKeys.nameFor(byteArrayOf(3, 2, 1)),
        )
    }

    @Test
    fun `name is a jpg`() {
        assertTrue(ArtworkKeys.nameFor(byteArrayOf(9)).endsWith(".jpg"))
    }

    @Test
    fun `empty cover still names something stable`() {
        assertEquals(ArtworkKeys.nameFor(ByteArray(0)), ArtworkKeys.nameFor(ByteArray(0)))
    }

    // ── Legacy detection ────────────────────────────────────────────

    @Test
    fun `art carried over from the old store needs a re-read`() {
        assertTrue(ArtworkKeys.isLegacyKey("$store/legacy/abc.jpg"))
    }

    @Test
    fun `art written by the new scheme does not`() {
        assertFalse(ArtworkKeys.isLegacyKey("$store/abc.jpg"))
    }

    @Test
    fun `a sidecar cover on external storage is not legacy`() {
        assertFalse(ArtworkKeys.isLegacyKey("/storage/ABCD-1234/Music/Album/cover.jpg"))
    }

    @Test
    fun `a directory merely starting with the legacy name is not matched`() {
        assertFalse(ArtworkKeys.isLegacyKey("$store/legacy_backup/abc.jpg"))
    }

    @Test
    fun `a null key is not legacy`() {
        assertFalse(ArtworkKeys.isLegacyKey(null))
    }

    // ── Key rewriting (the one-time move out of cacheDir) ───────────
    //
    // The column holds sidecar covers and raw audio paths beside stored art,
    // and a prefix match that ignores directory boundaries corrupts them.

    @Test
    fun `a key under the old store is repointed`() {
        assertEquals(
            "$store/legacy/abc.jpg",
            ArtworkKeys.rewriteKey("$oldStore/abc.jpg", oldStore, "$store/legacy"),
        )
    }

    @Test
    fun `a trailing slash on the old directory is normalized`() {
        assertEquals(
            "$store/legacy/abc.jpg",
            ArtworkKeys.rewriteKey("$oldStore/abc.jpg", "$oldStore/", "$store/legacy/"),
        )
    }

    @Test
    fun `a sibling directory sharing the prefix is left alone`() {
        val sibling = "${oldStore}_backup/abc.jpg"
        assertEquals(sibling, ArtworkKeys.rewriteKey(sibling, oldStore, "$store/legacy"))
    }

    @Test
    fun `a sidecar cover outside the old store is left alone`() {
        val sidecar = "/storage/emulated/0/Music/Album/cover.jpg"
        assertEquals(sidecar, ArtworkKeys.rewriteKey(sidecar, oldStore, "$store/legacy"))
    }

    // ── Downscaling ─────────────────────────────────────────────────

    @Test
    fun `an image already within the cap is not subsampled`() {
        assertEquals(1, ArtworkKeys.sampleSizeFor(1000, 800, 1024))
    }

    @Test
    fun `a huge embedded cover is subsampled but never below the cap`() {
        // 3000x3000 is an ordinary embedded FLAC cover. Halving twice lands
        // under the cap at 750, so it must stop at 1500 and leave the exact
        // scale to Bitmap.createScaledBitmap.
        assertEquals(2, ArtworkKeys.sampleSizeFor(3000, 3000, 1024))
    }

    @Test
    fun `subsampling uses the longest edge`() {
        assertEquals(2, ArtworkKeys.sampleSizeFor(4000, 500, 1024))
    }

    @Test
    fun `degenerate bounds fall back to no subsampling`() {
        assertEquals(1, ArtworkKeys.sampleSizeFor(0, 0, 1024))
        assertEquals(1, ArtworkKeys.sampleSizeFor(100, 100, 0))
    }
}
