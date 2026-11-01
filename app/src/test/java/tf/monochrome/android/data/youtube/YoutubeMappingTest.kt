package tf.monochrome.android.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import tf.monochrome.android.data.scxyt.ScxytError
import tf.monochrome.android.data.scxyt.toUnifiedTrack
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import java.io.IOException

/**
 * What NewPipeExtractor finds, as the app keeps it: search results as songs
 * under the server's old ids, links pasted into the search, the one audio
 * stream fetched, and every failure as the error the app words. No network.
 */
class YoutubeMappingTest {

    // ── Links ──────────────────────────────────────────────────────────

    @Test
    fun `every kind of YouTube link names its video`() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=42s",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id&list=RDAMVM$id",
            "https://music.youtube.com/watch?v=$id&si=AbCdEf",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=AbCdEf",
            "youtu.be/$id",
            "www.youtube.com/watch?v=$id",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube-nocookie.com/embed/$id",
            "  https://youtu.be/$id  ",
        ).forEach { assertEquals(it, id, YoutubeMapping.videoIdFromLink(it)) }
    }

    @Test
    fun `words, playlists and other sites are not links to a video`() {
        listOf(
            "never gonna give you up",
            "dQw4w9WgXcQ",
            "https://www.youtube.com/playlist?list=PL1234567890",
            "https://www.youtube.com/watch?v=tooShort",
            "https://www.youtube.com/@channel",
            "https://soundcloud.com/artist/song",
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "ftp://youtu.be/dQw4w9WgXcQ",
            "",
        ).forEach { assertNull(it, YoutubeMapping.videoIdFromLink(it)) }
        assertTrue(YoutubeMapping.isLink("https://soundcloud.com/artist/song"))
        assertTrue(!YoutubeMapping.isLink("daft punk"))
    }

    @Test
    fun `yt ids name a video and sc ids do not`() {
        assertEquals("dQw4w9WgXcQ", YoutubeMapping.videoIdOf("yt:dQw4w9WgXcQ"))
        assertNull(YoutubeMapping.videoIdOf("sc:123456"))
        assertNull(YoutubeMapping.videoIdOf("yt:short"))
        assertNull(YoutubeMapping.videoIdOf("dQw4w9WgXcQ"))
    }

    // ── Search results ─────────────────────────────────────────────────

    private fun song(
        url: String = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        type: StreamType = StreamType.VIDEO_STREAM,
    ) = StreamInfoItem(0, url, " Never Gonna Give You Up ", type).apply {
        uploaderName = "Rick Astley - Topic"
        uploaderUrl = "https://music.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw"
        duration = 213
        thumbnails = listOf(
            Image("https://lh3.googleusercontent.com/abc=w60-h60-l90-rj", 60, 60, Image.ResolutionLevel.LOW),
            Image("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj", 120, 120, Image.ResolutionLevel.LOW),
        )
    }

    @Test
    fun `a YouTube Music song becomes a track the queue and history keep`() {
        val track = YoutubeMapping.toTrack(song())!!
        assertEquals("yt:dQw4w9WgXcQ", track.id)
        assertEquals("youtube", track.source)
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artist)
        assertEquals(213.0, track.duration, 0.0)
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", track.cover)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", track.url)

        val unified = track.toUnifiedTrack()!!
        assertEquals(PlaybackSource.Scxyt("yt:dQw4w9WgXcQ"), unified.source)
        assertEquals(SourceType.YOUTUBE, unified.sourceType)
        assertEquals("Rick Astley", unified.artistName)
    }

    @Test
    fun `live streams, members-only videos and results without a video are left out`() {
        assertNull(YoutubeMapping.toTrack(song(type = StreamType.LIVE_STREAM)))
        assertNull(YoutubeMapping.toTrack(song(type = StreamType.AUDIO_LIVE_STREAM)))
        assertNull(YoutubeMapping.toTrack(song(url = "https://music.youtube.com/browse/MPREb_abc")))
        assertNull(YoutubeMapping.toTrack(song().apply { contentAvailability = ContentAvailability.MEMBERSHIP }))
        assertNull(YoutubeMapping.toTrack(song().apply { contentAvailability = ContentAvailability.PAID }))
    }

    private fun upload(title: String, channel: String = "rvd107", seconds: Long = 220) =
        StreamInfoItem(0, "https://www.youtube.com/watch?v=IBO2UWSslVE", title, StreamType.VIDEO_STREAM).apply {
            uploaderName = channel
            duration = seconds
        }

    @Test
    fun `a fan upload names its artist in the title, not in the channel`() {
        val track = YoutubeMapping.toTrack(upload("East Clubbers- To The Moon And Back"), plainVideo = true)!!
        assertEquals("yt:IBO2UWSslVE", track.id)
        assertEquals("To The Moon And Back", track.title)
        assertEquals("East Clubbers", track.artist)
    }

    @Test
    fun `a plain video's title is split at a spaced dash and loses its upload noise`() {
        fun read(title: String, channel: String = "SomeChannel") = YoutubeMapping.titleAndArtistOfVideo(title, channel)
        assertEquals("Never Gonna Give You Up" to "Rick Astley", read("Rick Astley - Never Gonna Give You Up (Official Music Video)"))
        assertEquals("Song" to "Artist", read("Artist –Song [HQ]"))
        assertEquals("Song" to "Artist", read("Artist — Song (Lyrics)"))
        // A hyphen inside a name is not a separator.
        assertEquals("Run This Town" to "Jay-Z", read("Jay-Z - Run This Town"))
        // A remix in brackets is the song's, not noise.
        assertEquals("Song (Club Remix)" to "Artist", read("Artist - Song (Club Remix)"))
        // No artist in the title: the channel stands in.
        assertEquals("Just A Title" to "SomeChannel", read("Just A Title (Official Video)"))
        // A version after the dash: the left side is the title.
        assertEquals("Song - Live at Wembley" to "SomeChannel", read("Song - Live at Wembley"))
        assertEquals("Song - 2011 Remaster" to "SomeChannel", read("Song - 2011 Remaster"))
        // A Topic channel's title is already the song's.
        assertEquals("Intro - Part 2" to "Band", read("Intro - Part 2", "Band - Topic"))
    }

    @Test
    fun `YouTube Music results keep their title whole`() {
        val track = YoutubeMapping.toTrack(upload("Intro - Part 2", channel = "Band"))!!
        assertEquals("Intro - Part 2", track.title)
        assertEquals("Band", track.artist)
    }

    @Test
    fun `results longer than the longest song fetched are left out`() {
        assertNull(YoutubeMapping.toTrack(upload("DJ - 2 Hour Mix", seconds = 2 * 60 * 60), plainVideo = true))
        assertTrue(YoutubeMapping.toTrack(upload("DJ - Song", seconds = 30 * 60), plainVideo = true) != null)
        // An unknown length is not a reason to leave one out.
        assertTrue(YoutubeMapping.toTrack(upload("DJ - Song", seconds = -1), plainVideo = true) != null)
    }

    private fun track(id: String) = tf.monochrome.android.data.scxyt.ScxytTrack(
        id = "yt:$id", source = "youtube", title = id, artist = "", duration = 0.0,
    )

    @Test
    fun `songs and videos are taken in turn, each once, up to the limit`() {
        val songs = listOf("s1", "s2", "both", "s3").map(::track)
        val videos = listOf("both", "v1", "v2").map(::track)
        assertEquals(
            listOf("s1", "both", "s2", "v1", "v2", "s3"),
            YoutubeMapping.mix(songs, videos, 20).map { it.title },
        )
        assertEquals(listOf("s1", "both", "s2"), YoutubeMapping.mix(songs, videos, 3).map { it.title })
        assertEquals(listOf("v1", "v2"), YoutubeMapping.mix(emptyList(), videos.drop(1), 20).map { it.title })
    }

    @Test
    fun `the cover is the largest square, else the largest, and always https`() {
        val video = listOf(
            Image("https://i.ytimg.com/vi/x/default.jpg", 90, 120, Image.ResolutionLevel.LOW),
            Image("https://i.ytimg.com/vi/x/hqdefault.jpg", 360, 480, Image.ResolutionLevel.MEDIUM),
            Image("http://i.ytimg.com/vi/x/maxres.jpg", 720, 1280, Image.ResolutionLevel.HIGH),
        )
        assertEquals("https://i.ytimg.com/vi/x/hqdefault.jpg", YoutubeMapping.coverOf(video))
        val square = Image("https://i.ytimg.com/vi/x/square.jpg", 300, 300, Image.ResolutionLevel.MEDIUM)
        assertEquals("https://i.ytimg.com/vi/x/square.jpg", YoutubeMapping.coverOf(video + square))
        assertNull(YoutubeMapping.coverOf(emptyList()))
    }

    // ── The audio ──────────────────────────────────────────────────────

    private fun audio(
        itag: Int,
        format: MediaFormat,
        bitrate: Int,
        drc: Boolean = false,
        track: AudioTrackType? = null,
        delivery: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
    ): AudioStream {
        val item = ItagItem.getItag(itag).apply {
            setBitrate(bitrate)
            setIsDrc(drc)
        }
        return AudioStream.Builder()
            .setId("$itag")
            .setContent("https://rr1---sn-abc.googlevideo.com/videoplayback?itag=$itag&drc=$drc&track=$track", true)
            .setMediaFormat(format)
            .setDeliveryMethod(delivery)
            .setAverageBitrate(item.averageBitrate)
            .setAudioTrackType(track)
            .setItagItem(item)
            .build()
    }

    private val m4a = audio(140, MediaFormat.M4A, 130_000)
    private val opus = audio(251, MediaFormat.WEBMA_OPUS, 150_000)
    private val opusLow = audio(250, MediaFormat.WEBMA_OPUS, 70_000)
    private val heAac = audio(139, MediaFormat.M4A, 49_000)

    @Test
    fun `itag 140 is taken whenever it is there, though Opus runs higher`() {
        assertSame(m4a, YoutubeMapping.chooseAudio(listOf(opus, opusLow, heAac, m4a)))
    }

    @Test
    fun `without 140 the highest bitrate wins, MP4 before WebM at the same rate`() {
        assertSame(opus, YoutubeMapping.chooseAudio(listOf(heAac, opusLow, opus)))
        val sameRateM4a = audio(141, MediaFormat.M4A, 150_000)
        assertSame(sameRateM4a, YoutubeMapping.chooseAudio(listOf(opus, sameRateM4a)))
    }

    @Test
    fun `the original track before a dubbed one, and the plain copy before DRC`() {
        val dubbed = audio(140, MediaFormat.M4A, 130_000, track = AudioTrackType.DUBBED)
        val original = audio(251, MediaFormat.WEBMA_OPUS, 150_000, track = AudioTrackType.ORIGINAL)
        assertSame(original, YoutubeMapping.chooseAudio(listOf(dubbed, original)))

        val drc = audio(140, MediaFormat.M4A, 130_000, drc = true)
        assertSame(m4a, YoutubeMapping.chooseAudio(listOf(drc, m4a)))
        // DRC only is still better than nothing.
        assertSame(drc, YoutubeMapping.chooseAudio(listOf(drc)))
    }

    @Test
    fun `only whole files over plain HTTP are fetched`() {
        val dash = audio(140, MediaFormat.M4A, 130_000, delivery = DeliveryMethod.DASH)
        val hls = audio(251, MediaFormat.WEBMA_OPUS, 150_000, delivery = DeliveryMethod.HLS)
        assertNull(YoutubeMapping.chooseAudio(listOf(dash, hls)))
        assertSame(heAac, YoutubeMapping.chooseAudio(listOf(dash, hls, heAac)))
        assertNull(YoutubeMapping.chooseAudio(emptyList()))
    }

    @Test
    fun `visionOS links get that client's User-Agent, the rest a browser's`() {
        assertEquals(YoutubeMapping.BROWSER_USER_AGENT, YoutubeMapping.userAgentFor("https://rr1.googlevideo.com/videoplayback?itag=140&c=ANDROID"))
        assertTrue(YoutubeMapping.userAgentFor("https://rr1.googlevideo.com/videoplayback?itag=140&c=VISIONOS").contains("visionos"))
    }

    // ── Errors ─────────────────────────────────────────────────────────

    @Test
    fun `each failure is the error the app already words`() {
        assertTrue(YoutubeMapping.errorOf(SignInConfirmNotBotException("Sign in to confirm you're not a bot")) is ScxytError.Blocked)
        assertTrue(YoutubeMapping.errorOf(ReCaptchaException("429", "https://www.youtube.com")) is ScxytError.Blocked)
        assertTrue(YoutubeMapping.errorOf(PrivateContentException("private")) is ScxytError.NotFound)
        assertTrue(YoutubeMapping.errorOf(AccountTerminatedException("terminated")) is ScxytError.NotFound)
        assertTrue(YoutubeMapping.errorOf(ContentNotAvailableException("Video unavailable")) is ScxytError.NotFound)
        assertTrue(YoutubeMapping.errorOf(AgeRestrictedContentException("age")) is ScxytError.Unavailable)
        assertTrue(YoutubeMapping.errorOf(GeographicRestrictionException("country")) is ScxytError.Unavailable)
        assertTrue(YoutubeMapping.errorOf(PaidContentException("members")) is ScxytError.Unavailable)
        assertTrue(YoutubeMapping.errorOf(YoutubeMusicPremiumContentException()) is ScxytError.Unavailable)
        assertTrue(YoutubeMapping.errorOf(IOException("no route to host")) is ScxytError.Network)
        val failed = YoutubeMapping.errorOf(ParsingException("Could not get name"))
        assertTrue(failed is ScxytError.Failed)
        assertTrue(!failed.retryable)
    }

    @Test
    fun `a failure wrapped in another is read by its cause`() {
        assertTrue(YoutubeMapping.errorOf(ExtractionException(IOException("reset"))) is ScxytError.Network)
        assertTrue(YoutubeMapping.errorOf(ParsingException("Could not get streams", SignInConfirmNotBotException("bot"))) is ScxytError.Blocked)
        val own = ScxytError.TooLong("live stream")
        assertSame(own, YoutubeMapping.errorOf(own))
    }
}
