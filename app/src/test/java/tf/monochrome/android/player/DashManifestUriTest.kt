package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashManifestUriTest {

    private val mpd = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT3M2.5S">
  <Period><AdaptationSet mimeType="audio/mp4"><Representation id="FLAC" codecs="flac" bandwidth="1411200">
    <SegmentTemplate initialization="https://example.invalid/0.mp4?token=a&amp;b=c" media="https://example.invalid/${'$'}Number${'$'}.mp4" startNumber="1">
      <SegmentTimeline><S d="176128" r="44"/></SegmentTimeline>
    </SegmentTemplate>
  </Representation></AdaptationSet></Period>
</MPD>"""

    @Test
    fun `a DASH manifest becomes a data URI that holds it unchanged`() {
        val uri = dashManifestUri(mpd)
        val prefix = "data:application/dash+xml;base64,"
        assertTrue(uri.startsWith(prefix))
        val decoded = String(java.util.Base64.getDecoder().decode(uri.removePrefix(prefix)))
        assertEquals(mpd, decoded)
        // One line: a wrapped base64 body would break the data: URI.
        assertFalse(uri.contains('\n'))
    }

    @Test
    fun `a link to a manifest is handed over as the link`() {
        val link = "https://example.invalid/track/1234/manifest.mpd?token=a"
        assertEquals(link, dashManifestUri(link))
        // Signed like the segments, so it is not queued for gapless either.
        assertFalse(GaplessEligibility.isStableUri(dashManifestUri(link)))
    }

    @Test
    fun `an inline DASH manifest is never queued for gapless`() {
        // Its segment links are time-limited, so it must be resolved when reached.
        assertFalse(GaplessEligibility.isStableUri(dashManifestUri(mpd)))
    }
}
