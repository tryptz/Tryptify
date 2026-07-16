package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkRefreshDetectorTest {

    private val cacheDir = "/data/user/0/tf.monochrome.android/cache/artwork"

    @Test
    fun `missing cache file is counted as evicted`() {
        val evicted = ArtworkRefreshDetector.countEvictedArtwork(
            cachedKeys = listOf("$cacheDir/abc.jpg"),
            artworkCacheDirPath = cacheDir,
            artworkFileExists = { false },
        )
        assertEquals(1, evicted)
    }

    @Test
    fun `intact cache files are not counted`() {
        val evicted = ArtworkRefreshDetector.countEvictedArtwork(
            cachedKeys = listOf("$cacheDir/abc.jpg", "$cacheDir/def.jpg"),
            artworkCacheDirPath = cacheDir,
            artworkFileExists = { true },
        )
        assertEquals(0, evicted)
    }

    @Test
    fun `missing sidecar outside the cache dir is ignored`() {
        // cover.jpg on external storage: missing may just mean an unmounted
        // SD card, which must never auto-trigger a (pruning) full scan.
        val evicted = ArtworkRefreshDetector.countEvictedArtwork(
            cachedKeys = listOf("/storage/ABCD-1234/Music/Album/cover.jpg"),
            artworkCacheDirPath = cacheDir,
            artworkFileExists = { false },
        )
        assertEquals(0, evicted)
    }

    @Test
    fun `sibling directory sharing the prefix is not matched`() {
        val evicted = ArtworkRefreshDetector.countEvictedArtwork(
            cachedKeys = listOf("${cacheDir}_backup/abc.jpg"),
            artworkCacheDirPath = cacheDir,
            artworkFileExists = { false },
        )
        assertEquals(0, evicted)
    }

    @Test
    fun `trailing slash on the cache dir path is normalized`() {
        val evicted = ArtworkRefreshDetector.countEvictedArtwork(
            cachedKeys = listOf("$cacheDir/abc.jpg"),
            artworkCacheDirPath = "$cacheDir/",
            artworkFileExists = { false },
        )
        assertEquals(1, evicted)
    }

    @Test
    fun `empty key list reports nothing evicted`() {
        val evicted = ArtworkRefreshDetector.countEvictedArtwork(
            cachedKeys = emptyList(),
            artworkCacheDirPath = cacheDir,
            artworkFileExists = { false },
        )
        assertEquals(0, evicted)
    }

    @Test
    fun `mixed keys count only the evicted cache entries`() {
        val gone = "$cacheDir/gone.jpg"
        val evicted = ArtworkRefreshDetector.countEvictedArtwork(
            cachedKeys = listOf(
                gone,                                        // evicted → counted
                "$cacheDir/present.jpg",                     // intact → skipped
                "/storage/emulated/0/Music/x/cover.jpg",     // sidecar → skipped
            ),
            artworkCacheDirPath = cacheDir,
            artworkFileExists = { it != gone },
        )
        assertEquals(1, evicted)
    }
}
