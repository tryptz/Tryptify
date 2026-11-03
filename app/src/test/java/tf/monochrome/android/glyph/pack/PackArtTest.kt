// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pictures of Trypt Mania's pack songs stay until their pack goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PackArtTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pack(root: File, name: String, source: String?) = File(root, name).apply {
        mkdirs()
        val from = source?.let { ",\"source\":\"$it\"" }.orEmpty()
        File(this, StepManiaPack.INDEX_FILE).writeText(
            """{"name":"$name","songs":[{"folder":"Song","chart":"Song.ssc","music":"song.ogg","title":"Song","artist":"A"}]$from}""",
        )
    }

    /** In the cache, Android and Clear cache deleted them while the wheel still showed them. */
    @Test
    fun `covers taken out of the music are kept outside the cache`() {
        val art = GlyphPackLibrary(context).artCache
        assertTrue(art.canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertFalse(art.canonicalPath.startsWith(context.cacheDir.canonicalPath))
    }

    @Test
    fun `a removed pack takes its covers with it, and only its own`() = runBlocking {
        val library = GlyphPackLibrary(context)
        pack(library.root, "Gone", null)
        pack(library.root, "Kept", null)
        fun cover(packName: String) =
            File(library.artCache, "${"${GlyphPackLibrary.PREFIX}$packName/Song".hashCode().toUInt().toString(16)}.jpg")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val gone = cover("Gone")
        val kept = cover("Kept")

        library.remove("Gone")

        assertFalse(gone.exists())
        assertTrue(kept.exists())
    }

    /** "Forget" on the charts folder keeps reading it while a pack is read from it in place. */
    @Test
    fun `a folder a pack is read from is known to be in use`() {
        val root = File(context.filesDir, "packs-in-use").apply { mkdirs() }
        pack(root, "InPlace", "content://tree/A")
        pack(root, "Copied", null)
        assertTrue(GlyphPackLibrary.isReadFrom(root, "content://tree/A"))
        assertFalse(GlyphPackLibrary.isReadFrom(root, "content://tree/B"))
    }
}
