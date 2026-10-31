package tf.monochrome.android.data.youtube

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runInterruptible
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import tf.monochrome.android.data.scxyt.ScxytBackend
import tf.monochrome.android.data.scxyt.ScxytError
import tf.monochrome.android.data.scxyt.ScxytSource
import tf.monochrome.android.data.scxyt.ScxytStream
import tf.monochrome.android.data.scxyt.ScxytTrack
import tf.monochrome.android.locale.AppLanguage
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube, searched and fetched on the phone with NewPipeExtractor.
 *
 * YouTube answers a data-centre address with its bot check ("Sign in to
 * confirm you're not a bot"), which is all trypt-hifi-scxyt ever got from
 * it. A phone is on a home or mobile network, which YouTube serves, so
 * everything goes from here: the search (YouTube Music's songs), the player
 * answers, and the audio itself. No Tryptify sign-in is involved.
 *
 * A song is fetched whole into the app's cache ([YoutubeAudioCache]) before
 * it plays, and played from there: a stream link is tied to the address
 * that asked for it and expires within hours, which a file does not, and the
 * player and the downloader both get the same bytes.
 */
@Singleton
class YoutubeOnDevice @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScxytBackend {

    private val http = NewPipeDownloader.client()
    private val pieces = ChunkedDownload(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = YoutubeAudioCache(File(context.cacheDir, CACHE_DIR), scope, fetch = ::fetchAudio)

    @Volatile
    private var initialized = false

    /**
     * NewPipe keeps its HTTP and its language in statics, so it is set up
     * once, on first use: the app's language (or the phone's, when it
     * follows the phone), and the phone's country for what YouTube Music
     * ranks first.
     */
    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val phone = Locale.getDefault()
            val chosen = AppLanguage.current(context)
            val language = if (chosen.isBlank()) phone else Locale.forLanguageTag(chosen)
            val country = phone.country.takeIf { it.length == 2 } ?: language.country.takeIf { it.length == 2 } ?: "US"
            NewPipe.init(
                NewPipeDownloader(http),
                Localization(language.language, language.country.ifBlank { country }),
                ContentCountry(country),
            )
            initialized = true
        }
    }

    /**
     * YouTube Music's songs for [query], first page, or the one song a
     * pasted YouTube link names. Another site's link is not searched for as
     * words.
     *
     * YouTube Music's song catalogue is not offered everywhere: from a
     * country without YouTube Music every query answers "No results" there
     * (as one did from a test network), while its music videos still come.
     * So when the songs give nothing, the music videos stand in.
     */
    override suspend fun search(query: String, source: ScxytSource, limit: Int): List<ScxytTrack> = extract {
        val linked = YoutubeMapping.videoIdFromLink(query)
        when {
            linked != null -> listOfNotNull(YoutubeMapping.toTrack(streamInfo(linked)))
            YoutubeMapping.isLink(query) -> emptyList()
            else -> searchMusic(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, limit)
                .ifEmpty { searchMusic(query, YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS, limit) }
        }
    }

    private fun searchMusic(query: String, filter: String, limit: Int): List<ScxytTrack> {
        val search = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        search.fetchPage()
        return search.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull(YoutubeMapping::toTrack)
            .distinctBy { it.id }
            .take(limit)
    }

    /** Fetches [id] (`yt:…`) whole into the cache, unless it is there already. */
    override suspend fun prepare(id: String): ScxytStream {
        val videoId = YoutubeMapping.videoIdOf(id) ?: throw ScxytError.NotFound(id)
        val file = try {
            cache.get(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = YoutubeMapping.errorOf(e)
            Log.w(TAG, "$id could not be fetched: ${error::class.simpleName}: ${e.message}")
            throw error
        }
        val format = file.extension
        return ScxytStream(
            id = id,
            format = format,
            mime = if (format == "webm") "audio/webm" else "audio/mp4",
            size = file.length(),
            link = Uri.fromFile(file).toString(),
            file = file,
        )
    }

    /** [videoId]'s chosen audio, written into [part]; returns its extension. */
    private suspend fun fetchAudio(videoId: String, part: File): String {
        val first = audioOf(videoId)
        try {
            pieces.fetch(first.content, lengthOf(first), part)
            return first.format!!.suffix
        } catch (e: ChunkedDownload.Expired) {
            // The link ran out (or was refused) partway: extract a fresh one.
            // The same itag, length and revision are the same bytes, so the
            // pieces already written stay; anything else starts over.
            Log.i(TAG, "yt:$videoId: stream link refused at ${part.length()} bytes (HTTP ${e.status}), extracting again")
        }
        val again = audioOf(videoId)
        val same = again.itag == first.itag &&
            lengthOf(again) == lengthOf(first) &&
            again.itagItem?.lastModified == first.itagItem?.lastModified
        if (!same) RandomAccessFile(part, "rw").use { it.setLength(0) }
        try {
            pieces.fetch(again.content, lengthOf(again), part)
        } catch (e: ChunkedDownload.Expired) {
            throw ScxytError.Failed("stream_refused", "stream link refused twice (HTTP ${e.status})")
        }
        return again.format!!.suffix
    }

    private suspend fun audioOf(videoId: String): AudioStream = extract {
        val info = streamInfo(videoId)
        YoutubeMapping.refusalOf(info)?.let { throw it }
        YoutubeMapping.chooseAudio(info.audioStreams)
            ?: throw ScxytError.Failed("no_audio", "no whole-file audio among ${info.audioStreams.size} streams")
    }

    private fun lengthOf(stream: AudioStream): Long = stream.itagItem?.contentLength ?: -1L

    private fun streamInfo(videoId: String): StreamInfo =
        StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")

    /**
     * Runs NewPipe's blocking calls off the main thread, interruptibly, so a
     * search the listener typed past or a timeout stops its request, and
     * throws whatever went wrong as the [ScxytError] it means.
     */
    private suspend fun <T> extract(block: () -> T): T = runInterruptible(Dispatchers.IO) {
        try {
            ensureInitialized()
            block()
        } catch (e: ScxytError) {
            throw e
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            throw YoutubeMapping.errorOf(e)
        }
    }

    private companion object {
        const val TAG = "YoutubeOnDevice"
        const val CACHE_DIR = "youtube"
    }
}
