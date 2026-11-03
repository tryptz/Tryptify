package tf.monochrome.android.data.youtube

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import tf.monochrome.android.data.scxyt.ScxytError
import tf.monochrome.android.data.scxyt.ScxytSource
import tf.monochrome.android.data.scxyt.ScxytTrack
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/**
 * What NewPipeExtractor finds, in the YouTube and SoundCloud source's own
 * terms: songs as [ScxytTrack]s under the ids the server used to hand out
 * (`yt:<videoId>`), so saved queues, likes and downloads keep finding them;
 * the one audio stream worth fetching; and every failure as the
 * [ScxytError] the app already has words for. Free of Android, so all of it
 * is tested on the JVM.
 */
internal object YoutubeMapping {

    /** AAC in MP4 at 128 kbps: what downloads keep as an .m4a every player opens. */
    const val ITAG_AAC_128 = 140

    /**
     * The longest song fetched, as trypt-hifi-scxyt's `MAX_DURATION_MINUTES`
     * default. The whole file is fetched before it plays, so an hour-long mix
     * would keep the listener waiting and push everything else out of the
     * cache.
     */
    const val MAX_DURATION_SECONDS = 30 * 60L

    /** What NewPipe's own app sends as its User-Agent for everything but visionOS links. */
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** Hosts whose links name a video; `www.` is taken off first. */
    private val VIDEO_HOSTS = setOf("youtube.com", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com")

    /** youtube.com/<this>/<id>. */
    private val ID_PATHS = setOf("shorts", "embed", "live", "v")

    /** The formats a fetched file may come in: each is one DownloadFormat knows. */
    private val FETCHABLE = setOf(MediaFormat.M4A, MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS)

    /** Square art from YouTube Music's image server, asked for at any size by its address. */
    private val GOOGLE_IMAGE_SIZE = Regex("=w\\d+-h\\d+")

    fun mediaId(videoId: String): String = "yt:$videoId"

    /** The video a `yt:` id names; null for any other id. */
    fun videoIdOf(mediaId: String): String? =
        mediaId.takeIf { ScxytSource.ofId(it) == ScxytSource.YOUTUBE }?.removePrefix("yt:")

    /**
     * The video a pasted link names: youtube.com, m. and music.youtube.com
     * (`watch?v=`, `/shorts/`, `/embed/`, `/live/`), youtu.be, with or without
     * the scheme and whatever else the query carries (a playlist it was
     * opened from, a share id, a start time). NewPipe's own link handler
     * turns away a watch link that carries a playlist, which is how a song
     * copied from YouTube Music's address bar usually looks. Null for
     * anything else, a playlist on its own included.
     */
    fun videoIdFromLink(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(if ("://" in trimmed) trimmed else "https://$trimmed") }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val path = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val candidate = when (host) {
            "youtu.be" -> path.firstOrNull()
            in VIDEO_HOSTS -> when {
                path.firstOrNull() == "watch" -> queryValue(uri.rawQuery, "v")
                path.size >= 2 && path[0] in ID_PATHS -> path[1]
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { VIDEO_ID.matches(it) }
    }

    /** Whether [query] is a web address rather than words to search for. */
    fun isLink(query: String): Boolean {
        val q = query.trim().lowercase()
        return q.startsWith("https://") || q.startsWith("http://")
    }

    private fun queryValue(rawQuery: String?, key: String): String? =
        rawQuery.orEmpty().split('&').firstNotNullOfOrNull { pair ->
            val at = pair.indexOf('=')
            if (at > 0 && pair.substring(0, at) == key) {
                runCatching { URLDecoder.decode(pair.substring(at + 1), "UTF-8") }.getOrNull()
            } else {
                null
            }
        }

    /**
     * A channel's name as an artist's: YouTube's auto-generated channels for
     * a catalogue artist are called "<artist> - Topic".
     */
    fun artistOf(uploader: String?): String = uploader.orEmpty().trim().removeSuffix(" - Topic").trim()

    /**
     * "Artist - Title" (or "Artist– Title", "Artist —Title"): a dash with a
     * space on at least one side, so "Jay-Z" stays whole. The first such
     * dash splits.
     */
    private val ARTIST_DASH_TITLE = Regex("""(.+?)(?:\s+[-–—]+\s*|\s*[-–—]+\s+)(.+)""")

    /**
     * What follows the dash when it is the song's version rather than its
     * title ("Song - Live at Wembley", "Song - 2011 Remaster"): then the
     * left side is the title, not the artist.
     */
    private val VERSION_AFTER_DASH = Regex(
        """^(?:\d{4}\s+)?(?:live|remaster(?:ed)?|remix|re-?mix|version|edit|radio edit|extended|acoustic|""" +
            """instrumental|demo|mono|stereo|official|lyrics?|audio|video|hd|hq)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A bracket holding nothing but upload noise: "(Official Music Video)",
     * "[HQ]", "(Lyrics)", "(Visualizer)". A bracket naming a remix, an edit or
     * a version is the song's and stays.
     */
    private val UPLOAD_NOISE = Regex(
        """\s*[(\[]\s*(?:(?:official|music|video|videoclip|clip|audio|lyrics?|visuali[sz]er|hd|hq|4k|mv|m/v)[\s/]*)+[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A plain YouTube video's title and artist as a song's. An auto-generated
     * "<artist> - Topic" channel's title is already the song's own, and its
     * channel the artist. Anyone else's upload usually names both in its
     * title ("East Clubbers- To The Moon And Back"), with the uploader's
     * channel name meaning nothing; when the title names no artist, the
     * channel stands in. Upload noise in brackets is dropped either way.
     */
    fun titleAndArtistOfVideo(title: String?, uploader: String?): Pair<String, String> {
        val channel = artistOf(uploader)
        val raw = title.orEmpty().trim()
        if (uploader.orEmpty().trim().endsWith(" - Topic")) return raw to channel
        val clean = UPLOAD_NOISE.replace(raw, "").trim().ifEmpty { raw }
        val split = ARTIST_DASH_TITLE.matchEntire(clean) ?: return clean to channel
        val artist = split.groupValues[1].trim()
        val song = split.groupValues[2].trim()
        if (artist.isEmpty() || song.isEmpty() || VERSION_AFTER_DASH.containsMatchIn(song)) return clean to channel
        return song to artist
    }

    /**
     * The picture to show: the largest square one (YouTube Music's album
     * art), else the largest of any shape. YouTube Music lists its art at 60
     * and 120 px, too small for the player; its image server draws any size
     * the address asks for, so such an address is asked for 544 px. Only
     * https, since the cover leaves the app with the song.
     */
    fun coverOf(images: List<Image>): String? {
        val usable = images.filter { it.url.startsWith("https://") }
        val square = usable.filter { it.width > 0 && it.width == it.height }
        val best = square.maxByOrNull { it.width }
            ?: usable.maxByOrNull { it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0) }
            ?: return null
        val url = best.url
        val onImageServer = runCatching { URI(url).host.orEmpty() }.getOrDefault("").endsWith("googleusercontent.com")
        return if (onImageServer && GOOGLE_IMAGE_SIZE.containsMatchIn(url)) {
            GOOGLE_IMAGE_SIZE.replace(url, "=w544-h544")
        } else {
            url
        }
    }

    /**
     * A search result as a song. Null for what cannot be played as one: a
     * live stream, a members-only, paid or upcoming video, or a result whose
     * address names no video, or one longer than [MAX_DURATION_SECONDS]
     * (a mix or a whole album, which would be refused at play).
     *
     * [plainVideo] is for YouTube's own video search, whose uploads name the
     * artist in the title ([titleAndArtistOfVideo]); YouTube Music's songs
     * and music videos come with their title and artist apart already.
     */
    fun toTrack(item: StreamInfoItem, plainVideo: Boolean = false): ScxytTrack? {
        if (item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM) return null
        when (item.contentAvailability) {
            ContentAvailability.MEMBERSHIP, ContentAvailability.PAID, ContentAvailability.UPCOMING -> return null
            else -> Unit
        }
        if (item.duration > MAX_DURATION_SECONDS) return null
        val videoId = videoIdFromLink(item.url.orEmpty()) ?: return null
        val (title, artist) = if (plainVideo) {
            titleAndArtistOfVideo(item.name, item.uploaderName)
        } else {
            item.name.orEmpty().trim() to artistOf(item.uploaderName)
        }
        return ScxytTrack(
            id = mediaId(videoId),
            source = ScxytSource.YOUTUBE.wire,
            title = title,
            artist = artist,
            artistUrl = item.uploaderUrl,
            duration = item.duration.coerceAtLeast(0).toDouble(),
            cover = coverOf(item.thumbnails),
            url = item.url,
        )
    }

    /**
     * The song a pasted link opened, from its full extraction. A link may
     * name anyone's upload, so its title is read as a plain video's.
     */
    fun toTrack(info: StreamInfo): ScxytTrack? {
        val videoId = info.id?.takeIf { VIDEO_ID.matches(it) } ?: videoIdFromLink(info.url.orEmpty()) ?: return null
        val (title, artist) = titleAndArtistOfVideo(info.name, info.uploaderName)
        return ScxytTrack(
            id = mediaId(videoId),
            source = ScxytSource.YOUTUBE.wire,
            title = title,
            artist = artist,
            artistUrl = info.uploaderUrl,
            duration = info.duration.coerceAtLeast(0).toDouble(),
            cover = coverOf(info.thumbnails),
            url = "https://music.youtube.com/watch?v=$videoId",
        )
    }

    /**
     * YouTube Music's [songs] and YouTube's plain [videos] as one list of at
     * most [limit]: taken in turn, a song first, so neither crowds the other
     * out, and a video already among the songs only once. The search screen
     * ranks the list against the query afterwards.
     */
    fun mix(songs: List<ScxytTrack>, videos: List<ScxytTrack>, limit: Int): List<ScxytTrack> {
        val seen = HashSet<String>()
        val out = ArrayList<ScxytTrack>(limit)
        val a = songs.iterator()
        val b = videos.iterator()
        while (out.size < limit && (a.hasNext() || b.hasNext())) {
            if (a.hasNext()) a.next().let { if (seen.add(it.id)) out += it }
            if (out.size < limit && b.hasNext()) b.next().let { if (seen.add(it.id)) out += it }
        }
        return out
    }

    /**
     * The audio to fetch. Itag 140 (AAC) whenever YouTube offers it, so a
     * download stays an .m4a; otherwise the highest bitrate of the rest, MP4
     * before WebM when two tie. Only whole files served over plain HTTP,
     * never a dubbed or described track while the original is there, and
     * never the copy with its loudness squashed (DRC) while the plain one is.
     * Null when nothing qualifies.
     */
    fun chooseAudio(streams: List<AudioStream>): AudioStream? {
        val fetchable = streams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format in FETCHABLE
        }
        val original = fetchable
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { fetchable }
        val plain = original.filter { it.itagItem?.isDrc != true }.ifEmpty { original }
        return plain.firstOrNull { it.itag == ITAG_AAC_128 }
            ?: plain.maxWithOrNull(compareBy({ bitrateOf(it) }, { if (it.format == MediaFormat.M4A) 1 else 0 }))
    }

    private fun bitrateOf(stream: AudioStream): Int =
        if (stream.bitrate > 0) stream.bitrate else stream.averageBitrate.coerceAtLeast(0) * 1000

    /**
     * The User-Agent a stream link wants. visionOS links are refused without
     * that client's own; the rest take a browser's, as in NewPipe's player.
     */
    fun userAgentFor(url: String): String =
        if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else BROWSER_USER_AGENT

    /**
     * [error] as the app words it. NewPipe wraps one failure in another, so
     * the first cause it can name decides: YouTube's bot check (and its 429,
     * which NewPipe reads as a captcha) means YouTube is turning this network
     * away; a private, deleted or terminated video is not found; an age
     * check, a region, a paid or members-only video is unavailable here; no
     * answer at all is the network's.
     */
    fun errorOf(error: Throwable): ScxytError {
        if (error is ScxytError) return error
        val named = generateSequence(error) { it.cause }.take(8).firstNotNullOfOrNull { e ->
            when (e) {
                is ScxytError -> e
                is SignInConfirmNotBotException, is ReCaptchaException -> ScxytError.Blocked(e.message)
                is PrivateContentException, is AccountTerminatedException -> ScxytError.NotFound(e.message)
                is AgeRestrictedContentException, is GeographicRestrictionException, is PaidContentException,
                is YoutubeMusicPremiumContentException, is UnsupportedContentInCountryException,
                -> ScxytError.Unavailable(e.message)
                // Its subclasses are above; on its own it is "Video unavailable", a deleted one.
                is ContentNotAvailableException -> ScxytError.NotFound(e.message)
                is IOException -> ScxytError.Network(e)
                else -> null
            }
        }
        return named ?: ScxytError.Failed("youtube", error.message)
    }

    /** Why [info] is not fetched whole, or null when it can be. */
    fun refusalOf(info: StreamInfo): ScxytError? = when {
        info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM ->
            ScxytError.TooLong("live stream")
        info.duration > MAX_DURATION_SECONDS -> ScxytError.TooLong("${info.duration} s")
        else -> null
    }
}
