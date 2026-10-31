package tf.monochrome.android.ui.glyph

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the archive browser queues: always the link the page gave, named after its file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StepManiaArchiveDownloadTest {

    @Test
    fun anAttachmentIsFetchedFromItsOwnLinkUnderTheServersName() {
        // The case the download listener exists for: no extension in the link,
        // the name only in Content-Disposition. It used to fetch https://josevarela.net/Foo.zip?id=42.
        val url = "https://josevarela.net/SMArchive/Themes/get?id=42"
        assertEquals(ArchiveDownload(url, "Foo"), offeredDownload(url, "Foo.zip"))
    }

    @Test
    fun everyArchiveKindKeepsTheLink() {
        val url = "https://files.example.net/d/8f3a?token=abc"
        for (name in listOf("Pack.zip", "Skin.SMZIP", "Dancer.7z", "Voice.rar")) {
            val offered = offeredDownload(url, name)
            assertEquals(name, url, offered.url)
            assertEquals(name, name.substringBeforeLast('.'), offered.name)
        }
    }

    @Test
    fun aNameThatIsNoArchiveFallsBackToTheLinksOwnFile() {
        val url = "https://josevarela.net/SMArchive/NoteSkins/Some%20Skin.zip"
        assertEquals(ArchiveDownload(url, "Some Skin"), offeredDownload(url, "downloadfile.bin"))
    }

    @Test
    fun aTappedLinkIsNamedAfterItsLastSegment() {
        val url = "https://github.com/someone/skins/releases/download/v1/Retro%20Arrows.smzip"
        assertEquals(ArchiveDownload(url, "Retro Arrows"), linkedDownload(Uri.parse(url)))
    }
}
