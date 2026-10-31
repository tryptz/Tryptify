package tf.monochrome.android.data.scxyt

import kotlinx.coroutines.CancellationException
import tf.monochrome.android.domain.model.UnifiedTrack

/** Where the songs of one of the two services are searched and fetched. */
interface ScxytBackend {
    suspend fun search(query: String, source: ScxytSource, limit: Int): List<ScxytTrack>

    /** Makes [id] (`yt:…` or `sc:…`) ready to play. */
    suspend fun prepare(id: String): ScxytStream
}

/**
 * Which way each call goes: YouTube to the phone's own extraction
 * ([youtube]), since YouTube turns the server's data-centre address away
 * with its bot check, and SoundCloud to trypt-hifi-scxyt ([server]), which
 * takes the Tryptify sign-in. So YouTube works signed out, and SoundCloud
 * signed out stays off, with nothing sent. Every failure comes back as the
 * [ScxytError] it means.
 */
class ScxytRouter(
    private val server: ScxytBackend,
    private val youtube: ScxytBackend,
) {
    private fun backendFor(source: ScxytSource?): ScxytBackend =
        if (source == ScxytSource.YOUTUBE) youtube else server

    /** The first [limit] songs [source] finds for [query]; a pasted link finds that one song. */
    suspend fun search(query: String, source: ScxytSource, limit: Int): Result<List<UnifiedTrack>> = call {
        backendFor(source).search(query, source, limit).mapNotNull { it.toUnifiedTrack() }
    }

    /**
     * Makes [mediaId] ready to play: a YouTube song fetched whole into the
     * phone's cache, a SoundCloud one fetched by the server, which hands back
     * its six-hour link. An id neither service hands out goes to the server,
     * as it always did, which answers that it is not found.
     */
    suspend fun prepare(mediaId: String): Result<ScxytStream> = call {
        backendFor(ScxytSource.ofId(mediaId)).prepare(mediaId)
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: ScxytError) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(ScxytError.Network(e))
        }
}
