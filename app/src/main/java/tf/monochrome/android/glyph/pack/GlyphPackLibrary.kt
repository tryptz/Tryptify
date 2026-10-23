// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.glyph.data.GlyphChartState
import tf.monochrome.android.glyph.data.GlyphSong
import tf.monochrome.android.util.safDocumentUriToPath
import tf.monochrome.android.util.safTreeUriToPath

/**
 * The StepMania packs imported into the app, as songs the mode can play.
 *
 * Packs live in `files/glyph/packs/<pack>/<song>/`, unpacked by
 * [PackImporter], which also wrote each pack's index — so listing a hundred
 * songs reads a hundred small JSON files' worth, not a hundred charts.
 *
 * A pack song's id is `pack:<pack>/<song folder>`. Its chart is the pack's
 * own, read in place; once edited in the map editor, the edit is saved as the
 * app's own copy and wins, and the pack's file is never written to.
 *
 * A pack read in place (songs already unpacked on the phone, linked rather
 * than copied) keeps only its index and charts here; its music and art are
 * documents in the folder the player picked, read through the grant kept for
 * it, and removing the pack gives the grant back.
 */
@Singleton
class GlyphPackLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * One imported pack, for the list under Settings. [source] is the folder
     * a pack read in place is read from (its tree URI), null for one copied in.
     */
    data class Pack(val name: String, val songs: Int, val bytes: Long, val inPlace: Boolean = false, val source: String? = null)

    /**
     * What the library shows for a linked pack song's music file: the chart's
     * title and artist, the pack as the album, and the song's own picture.
     */
    data class LibraryTags(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        /** The song's picture on the phone, or null to keep what the file has. */
        val artPath: String?,
    )

    val root: File get() = File(context.filesDir, "glyph/packs")

    private val _songs = MutableStateFlow<List<GlyphSong>>(emptyList())
    val songs: StateFlow<List<GlyphSong>> = _songs.asStateFlow()

    private val _packs = MutableStateFlow<List<Pack>>(emptyList())
    val packs: StateFlow<List<Pack>> = _packs.asStateFlow()

    @Volatile private var loaded = false

    /** An importer writing into this library, measuring each song's length as it goes. */
    fun importer(): PackImporter = PackImporter(root, durationOf = ::durationOf, durationOfUri = ::durationOfUri)

    /** Re-read every pack's index. Cheap; called after an import or a removal. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val folders = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        val songs = ArrayList<GlyphSong>()
        val packs = ArrayList<Pack>()
        for (folder in folders.sortedBy { it.name.lowercase() }) {
            val index = PackImporter.readIndex(folder) ?: continue
            packs += Pack(
                name = index.name,
                songs = index.songs.size,
                bytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                inPlace = index.source != null,
                source = index.source,
            )
            for (song in index.songs) songs += song.toGlyphSong(folder, index.name)
        }
        _songs.value = songs
        _packs.value = packs
        loaded = true
        // After the list is up: covers for the songs that had none in their folder.
        fillEmbeddedArt(songs)
    }

    /**
     * Every song in [listed] with no picture of its own takes the cover inside
     * its music, as the library's songs do: a chart made in the app and saved
     * to the player's own pack names no picture. Each is taken out once and
     * kept ([artCache]); a song whose music has none is remembered so it is not
     * opened again. The list is shown first and the covers come after it.
     */
    private fun fillEmbeddedArt(listed: List<GlyphSong>) {
        val found = HashMap<String, String>()
        for (song in listed) {
            if (song.artworkUri != null || song.trackId in noArt()) continue
            val music = if (song.filePath.startsWith("content:")) Uri.parse(song.filePath) else Uri.fromFile(File(song.filePath))
            val bytes = SongArt.embedded(context, music)
            if (bytes == null) {
                runCatching { File(artCache, "${artKey(song.trackId)}$NO_ART").createNewFile() }
                continue
            }
            val file = File(artCache, artKey(song.trackId) + "." + SongArt.jacketName(bytes).substringAfterLast('.'))
            if (runCatching { file.writeBytes(bytes) }.isSuccess) found[song.trackId] = Uri.fromFile(file).toString()
        }
        if (found.isEmpty()) return
        _songs.value = _songs.value.map { song -> found[song.trackId]?.let { song.copy(artworkUri = it) } ?: song }
    }

    /** Where the covers taken out of pack songs' music are kept. */
    private val artCache: File get() = File(context.cacheDir, "glyph/art").apply { mkdirs() }

    private fun artKey(trackId: String): String = trackId.hashCode().toUInt().toString(16)

    /** A cover already taken out of [trackId]'s music, or null. */
    private fun cachedArt(trackId: String): String? {
        val key = artKey(trackId)
        return listOf("jpg", "png").map { File(artCache, "$key.$it") }.firstOrNull { it.isFile }?.let { Uri.fromFile(it).toString() }
    }

    /** The pack songs whose music is known to hold no cover. */
    private fun noArt(): Set<String> {
        val marked = artCache.listFiles { file -> file.name.endsWith(NO_ART) }?.map { it.name.removeSuffix(NO_ART) }?.toSet().orEmpty()
        return _songs.value.filter { artKey(it.trackId) in marked }.mapTo(HashSet()) { it.trackId }
    }

    /** Load once, the first time anything asks. */
    suspend fun ensureLoaded() {
        if (!loaded) refresh()
    }

    /** Delete an imported pack and everything in it. */
    suspend fun remove(name: String) {
        withContext(Dispatchers.IO) {
            val folder = File(root, name)
            if (!folder.canonicalPath.startsWith(root.canonicalPath + File.separator)) return@withContext
            val source = PackImporter.readIndex(folder)?.source
            folder.deleteRecursively()
            // The folder's grant goes back once no pack is read from it.
            if (source != null) {
                val stillUsed = root.listFiles()?.any { PackImporter.readIndex(it)?.source == source } == true
                if (!stillUsed) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(source),
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
        refresh()
    }

    /**
     * Where [song]'s music is on the phone: the file of a pack read in place,
     * through its document, or of one copied in. Null for a document that is
     * not on the phone's own storage (a cloud folder).
     */
    fun musicPath(song: GlyphSong): String? =
        if (song.filePath.startsWith("content:")) safDocumentUriToPath(Uri.parse(song.filePath)) else song.filePath

    /**
     * The folders the linked packs are read from, as paths: what Settings ›
     * Library › Music folders adds for "Add StepMania packs".
     */
    suspend fun linkedFolders(): List<String> {
        ensureLoaded()
        return _packs.value.mapNotNull { it.source }.distinct().mapNotNull { safTreeUriToPath(Uri.parse(it)) }.distinct()
    }

    /**
     * The library's view of every linked pack song, by its music's path: the
     * scanner shows these songs with the chart's title and artist instead of
     * the file's tags (StepMania's music is often an untagged OGG), each pack
     * as one album. A copied pack's music is in the app's own storage, where
     * the library never looks, so only linked packs are here.
     */
    suspend fun libraryTags(): Map<String, LibraryTags> {
        ensureLoaded()
        val linked = _songs.value.filter { it.filePath.startsWith("content:") }
        val tags = HashMap<String, LibraryTags>()
        for ((pack, songs) in linked.groupBy { it.pack.orEmpty() }) {
            val albumArtist = packAlbumArtist(songs.map { it.artist })
            for (song in songs) {
                val path = musicPath(song) ?: continue
                tags[path] = LibraryTags(
                    title = song.title,
                    artist = song.artist,
                    album = pack,
                    albumArtist = albumArtist,
                    artPath = song.artworkUri
                        ?.takeIf { it.startsWith("content:") }
                        ?.let { safDocumentUriToPath(Uri.parse(it)) },
                )
            }
        }
        return tags
    }

    /** The pack's own chart for a pack song's [trackId], or null for any other song. */
    fun chartFile(trackId: String): File? {
        val (pack, song) = parse(trackId) ?: return null
        val folder = File(root, pack)
        val entry = PackImporter.readIndex(folder)?.songs?.firstOrNull { it.folder == song } ?: return null
        return File(folder, "$song/${entry.chart}")
    }

    private fun PackIndex.Song.toGlyphSong(packFolder: File, packName: String): GlyphSong {
        val songFolder = File(packFolder, folder)
        val musicFile = File(songFolder, music)
        val levels = difficulties.mapNotNull { name -> StepManiaDifficulty.entries.firstOrNull { it.name == name } }
        val id = "$PREFIX${packFolder.name}/$folder"
        return GlyphSong(
            trackId = id,
            chartId = id.hashCode().toUInt().toString(16),
            title = title,
            artist = artist,
            // Read in place: the document in the player's folder.
            filePath = musicUri ?: musicFile.absolutePath,
            // The pack's own picture, else the cover once taken out of the music.
            artworkUri = artUri ?: art?.let { Uri.fromFile(File(songFolder, it)).toString() } ?: cachedArt(id),
            durationSeconds = durationSeconds,
            codec = codecOf(music),
            bpm = bpm,
            difficulties = levels,
            chartState = if (levels.isNotEmpty()) GlyphChartState.READY else GlyphChartState.UNREADABLE,
            pack = packName,
            backgroundUri = backgroundUri
                ?: (background ?: headerBackground(songFolder, chart, music))
                    ?.let { Uri.fromFile(File(songFolder, it)).toString() },
        )
    }

    /**
     * The `#BACKGROUND` of a pack imported before the index kept it, read from
     * the chart's header and found among the song's files. Only for a pack
     * copied in: one read in place has its pictures behind a folder grant, and
     * shows its jacket until it is linked again. The header comes before the
     * notes, so the start of the file is all that is read.
     */
    private fun headerBackground(songFolder: File, chart: String, music: String): String? {
        if (!File(songFolder, music).exists()) return null
        val header = runCatching {
            File(songFolder, chart).bufferedReader().use { reader ->
                val buffer = CharArray(HEADER_CHARS)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
        }.getOrNull() ?: return null
        return StepManiaPack.background(songFolder, header)?.name
    }

    private fun durationOf(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun durationOfUri(uri: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        const val PREFIX = "pack:"
        private const val NO_ART = ".none"

        /** How much of a chart is read for its header's tags: they all come before the notes. */
        private const val HEADER_CHARS = 16 * 1024

        fun isPackSong(trackId: String): Boolean = trackId.startsWith(PREFIX)

        /**
         * A pack's album artist in the library: its one artist when every
         * song shares it, else "Various Artists", so a pack is one album and
         * not one per artist in it.
         */
        internal fun packAlbumArtist(artists: List<String>): String {
            val distinct = artists.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            return distinct.singleOrNull() ?: VARIOUS_ARTISTS
        }

        const val VARIOUS_ARTISTS = "Various Artists"

        private fun parse(trackId: String): Pair<String, String>? {
            if (!isPackSong(trackId)) return null
            val rest = trackId.removePrefix(PREFIX)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            return rest.substring(0, slash) to rest.substring(slash + 1)
        }

        private fun codecOf(name: String): AudioCodec = when (StepManiaPack.extension(name)) {
            "ogg", "oga" -> AudioCodec.OGG_VORBIS
            "opus" -> AudioCodec.OPUS
            "mp3" -> AudioCodec.MP3
            "flac" -> AudioCodec.FLAC
            "wav" -> AudioCodec.WAV
            "m4a" -> AudioCodec.AAC
            else -> AudioCodec.UNKNOWN
        }
    }
}
