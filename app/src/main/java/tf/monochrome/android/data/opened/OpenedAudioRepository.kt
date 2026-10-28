package tf.monochrome.android.data.opened

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.data.local.scanner.ScanCoordinator
import tf.monochrome.android.data.local.tags.AudioTags
import tf.monochrome.android.data.local.tags.TagReader
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.TrackLyrics
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.player.PlaybackStateRepository

/**
 * Songs another app opens with Tryptify.
 *
 * The file is copied into the cache while the opening app's permission lasts
 * (it is usually only good while the activity is up), played from there, and
 * left out of the library: the cache keeps the last [OpenedAudio.KEEP_COPIES]
 * and Android may clear it. "Add to library" copies it into Music/Tryptify,
 * where the library finds it like any other song.
 *
 * A file that is already a library song is played as that song, so its
 * stats, likes and measurements stay on one row.
 */
@Singleton
class OpenedAudioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagReader: TagReader,
    private val localMediaDao: LocalMediaDao,
    private val playbackState: PlaybackStateRepository,
    private val scanCoordinator: ScanCoordinator,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Failure {
        NOT_AUDIO, NO_ACCESS, NOT_FOUND, TOO_BIG, EMPTY, NO_SPACE, UNREADABLE,
        /** Add to library only: Android would not let Tryptify write to Music. */
        NO_WRITE,
    }

    sealed interface Opened {
        /** Ready to play. [inLibrary] when it is a library song, so there is nothing to add. */
        data class Ready(val track: UnifiedTrack, val inLibrary: Boolean) : Opened
        data class Failed(val failure: Failure) : Opened
    }

    sealed interface Added {
        /** Saved in Music/[folder]; the library picks it up on its next refresh, started here. */
        data class Saved(val folder: String) : Added
        data class Failed(val failure: Failure) : Added
    }

    private val _opened = MutableStateFlow<Opened?>(null)

    /** The latest opened file, until the player has taken it ([taken]). */
    val opened: StateFlow<Opened?> = _opened.asStateFlow()

    private val _saved = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of opened songs already added to the library this run: the menu item goes once one is. */
    val saved: StateFlow<Set<String>> = _saved.asStateFlow()

    private val copiesRoot get() = File(context.cacheDir, "opened")

    fun taken(item: Opened) {
        _opened.compareAndSet(item, null)
    }

    /**
     * Checks and copies [uri], then publishes it on [opened]. Called while the
     * activity that received it is up, which is what keeps the permission to
     * read it; the copy is made before anything else so that permission is
     * not needed afterwards.
     */
    fun open(uri: Uri, type: String?) {
        scope.launch {
            val result = try {
                openNow(uri, type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.w(TAG, "no permission to read the opened file")
                Opened.Failed(Failure.NO_ACCESS)
            } catch (e: FileNotFoundException) {
                // A file:// path Android will not let the app read arrives
                // as "not found" too, with EACCES in its message.
                Opened.Failed(if (e.isPermissionDenied()) Failure.NO_ACCESS else Failure.NOT_FOUND)
            } catch (e: TooBig) {
                Opened.Failed(Failure.TOO_BIG)
            } catch (e: IOException) {
                Log.w(TAG, "could not copy the opened file", e)
                Opened.Failed(if (copiesRoot.usableSpace < OpenedAudio.SPARE_BYTES) Failure.NO_SPACE else Failure.UNREADABLE)
            } catch (e: Exception) {
                Log.w(TAG, "could not open the file", e)
                Opened.Failed(Failure.UNREADABLE)
            }
            // A cold start restores the last session's queue on its own
            // schedule; landing after it keeps that restore from replacing
            // the song just opened.
            playbackState.awaitRestored()
            _opened.value = result
        }
    }

    private suspend fun openNow(uri: Uri, intentType: String?): Opened {
        val resolver = context.contentResolver
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val path = if (scheme == "file") uri.path?.let { runCatching { File(it).canonicalPath }.getOrNull() } else null
        val type = typeOf(uri, intentType, path)
        val refusal = OpenedAudio.refusal(
            action = ACTION_VIEW,
            scheme = scheme,
            mime = type,
            authority = uri.authority,
            canonicalPath = path,
            privateRoots = privateRoots(),
            ownPackage = context.packageName,
        )
        when (refusal) {
            null -> Unit
            OpenedAudio.Refusal.NOT_AUDIO -> return Opened.Failed(Failure.NOT_AUDIO)
            else -> return Opened.Failed(Failure.NO_ACCESS)
        }

        // Already a library song: play that row.
        libraryPathOf(uri, path)?.let { known ->
            localMediaDao.findByPath(known)?.let { row ->
                return Opened.Ready(with(LocalMediaRepository) { row.toUnifiedTrack() }, inLibrary = true)
            }
        }

        var name: String? = null
        var size = -1L
        if (scheme == "content") {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } else if (path != null) {
            val file = File(path)
            if (!file.isFile) throw FileNotFoundException(path)
            name = file.name
            size = file.length()
        }
        if (size == 0L) return Opened.Failed(Failure.EMPTY)
        val root = copiesRoot.apply { mkdirs() }
        if (!OpenedAudio.fits(size, root.usableSpace)) {
            return Opened.Failed(if (size > OpenedAudio.MAX_BYTES) Failure.TOO_BIG else Failure.NO_SPACE)
        }

        val key = OpenedAudio.copyKey(uri.toString(), size)
        val fileName = OpenedAudio.safeName(name, extensionFor(type))
        val dir = File(root, key)
        val copy = File(dir, fileName)
        if (!(copy.isFile && (size < 0 || copy.length() == size))) {
            dir.deleteRecursively()
            dir.mkdirs()
            val partial = File(dir, "$fileName.part")
            try {
                val input = if (scheme == "file") File(path!!).inputStream() else resolver.openInputStream(uri)
                    ?: throw FileNotFoundException(uri.toString())
                input.use { source -> partial.outputStream().use { copyCapped(source, it) } }
                if (partial.length() == 0L) {
                    partial.delete()
                    return Opened.Failed(Failure.EMPTY)
                }
                if (!partial.renameTo(copy)) throw IOException("could not name the copy")
            } catch (e: Throwable) {
                dir.deleteRecursively()
                throw e
            }
        }
        dir.setLastModified(System.currentTimeMillis())
        dropOldCopies(keep = key)

        val tags = tagReader.readTags(copy.absolutePath)
        if (!looksLikeAudio(copy, tags)) {
            dir.deleteRecursively()
            return Opened.Failed(Failure.NOT_AUDIO)
        }
        return Opened.Ready(trackFor(key, copy, tags), inLibrary = false)
    }

    /**
     * Copies the opened song [track] into Music/Tryptify and starts a library
     * refresh. Its cache copy is the source, so the opening app's permission
     * is no longer needed.
     */
    suspend fun addToLibrary(track: UnifiedTrack): Added = withContext(Dispatchers.IO) {
        val source = (track.source as? PlaybackSource.LocalFile)?.filePath?.let(::File)
            ?.takeIf { it.isFile && track.id.startsWith(OpenedAudio.ID_PREFIX) }
            ?: return@withContext Added.Failed(Failure.NOT_FOUND)
        try {
            val music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val folder = File(music, OpenedAudio.LIBRARY_FOLDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) insertIntoMediaStore(source) else writeToMusic(source, folder)
            _saved.update { it + track.id }
            includeInLibrary(folder)
            Added.Saved(OpenedAudio.LIBRARY_FOLDER)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "no permission to write to Music", e)
            Added.Failed(Failure.NO_WRITE)
        } catch (e: IOException) {
            Log.w(TAG, "could not write the song to Music", e)
            Added.Failed(
                when {
                    e.isPermissionDenied() -> Failure.NO_WRITE
                    source.length() + OpenedAudio.SPARE_BYTES > Environment.getExternalStorageDirectory().usableSpace -> Failure.NO_SPACE
                    else -> Failure.UNREADABLE
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not add the opened file to the library", e)
            Added.Failed(Failure.UNREADABLE)
        }
    }

    private fun insertIntoMediaStore(source: File) {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relative = "${Environment.DIRECTORY_MUSIC}/${OpenedAudio.LIBRARY_FOLDER}/"
        // Saved once: a second tap, or the same song opened again, does not
        // leave "song (1).flac" beside it.
        resolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
            arrayOf(relative, source.name, source.length().toString()), null,
        )?.use { if (it.moveToFirst()) return }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, audioTypeFor(source))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values) ?: throw IOException("Music refused the file")
        try {
            (resolver.openOutputStream(target) ?: throw IOException("no stream for $target")).use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
    }

    /** Android 8 and 9, before MediaStore took files: needs the storage permission. */
    private fun writeToMusic(source: File, folder: File) {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("could not make $folder")
        val target = File(folder, source.name)
        if (target.isFile && target.length() == source.length()) return
        val partial = File(folder, ".${source.name}.part")
        try {
            source.inputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(target)) throw IOException("could not name $target")
        } catch (e: Throwable) {
            partial.delete()
            throw e
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
    }

    /**
     * Makes sure the library reads Music/Tryptify: a listener who picked
     * library folders gets it added as one (a full scan), anyone else gets
     * the quiet refresh that reads new songs.
     */
    private suspend fun includeInLibrary(folder: File) {
        val roots = preferences.userFolderRoots.first()
        val path = folder.absolutePath
        val covered = roots.isEmpty() || roots.any { path == it || path.startsWith(it.trimEnd('/') + "/") }
        if (!covered) {
            scanCoordinator.addFolderAndScan(path)
        } else {
            scope.launch { runCatching { scanCoordinator.runBackgroundRefresh() } }
        }
    }

    private fun trackFor(key: String, copy: File, tags: AudioTags): UnifiedTrack {
        val title = tags.title?.takeIf { it.isNotBlank() } ?: OpenedAudio.titleFromName(copy.name)
        val artist = tags.albumArtist ?: tags.artist
        return UnifiedTrack(
            id = OpenedAudio.trackId(key),
            title = title,
            durationSeconds = tags.durationSeconds,
            trackNumber = tags.trackNumber,
            discNumber = tags.discNumber,
            artistName = artist ?: "Unknown Artist",
            artistNames = listOfNotNull(tags.artist, tags.albumArtist).distinct(),
            albumArtistName = tags.albumArtist,
            albumTitle = tags.album,
            releaseYear = tags.year,
            // The cover TagReader cached as a picture, never the audio file:
            // Media3 reads an artwork URI whole into memory.
            artworkUri = tags.artworkCacheKey,
            codec = tags.codec,
            sampleRate = tags.sampleRate,
            bitDepth = tags.bitDepth,
            bitRate = tags.bitRate,
            channelCount = tags.channels,
            replayGainTrack = tags.musical.rgTrackGain,
            replayGainAlbum = tags.musical.rgAlbumGain,
            r128TrackGain = tags.musical.r128TrackGain,
            r128AlbumGain = tags.musical.r128AlbumGain,
            replayGainTrackPeak = tags.musical.rgTrackPeak,
            replayGainAlbumPeak = tags.musical.rgAlbumPeak,
            lyrics = tags.lyrics?.let { TrackLyrics(basic = it) },
            genre = tags.genre,
            source = PlaybackSource.LocalFile(
                filePath = copy.absolutePath,
                codec = tags.codec,
                sampleRate = tags.sampleRate,
                bitDepth = tags.bitDepth,
            ),
            sourceType = SourceType.LOCAL,
            dateModified = copy.lastModified(),
            fileSizeBytes = copy.length(),
        )
    }

    /** The library path [uri] names, when it names a file on shared storage at all. */
    private fun libraryPathOf(uri: Uri, filePath: String?): String? {
        if (filePath != null) return filePath
        if (uri.authority != MediaStore.AUTHORITY) return null
        @Suppress("DEPRECATION")
        val data = MediaStore.MediaColumns.DATA
        return runCatching {
            context.contentResolver.query(uri, arrayOf(data), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun typeOf(uri: Uri, intentType: String?, filePath: String?): String? {
        intentType?.takeIf { OpenedAudio.isAudioType(it) }?.let { return it }
        val resolved = if (uri.scheme == "content") runCatching { context.contentResolver.getType(uri) }.getOrNull() else null
        return resolved ?: filePath?.let { mimeFromExtension(File(it).extension) } ?: intentType
    }

    private fun audioTypeFor(file: File): String {
        val guess = mimeFromExtension(file.extension)
        return when {
            guess == null -> "audio/mpeg"
            guess.startsWith("audio/") -> guess
            guess.contains("ogg") -> "audio/ogg"
            guess.contains("flac") -> "audio/flac"
            else -> "audio/mpeg"
        }
    }

    private fun mimeFromExtension(ext: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))

    private fun extensionFor(type: String?): String? =
        type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it.substringBefore(';').trim()) }

    private fun privateRoots(): List<String> = listOfNotNull(
        context.dataDir, context.filesDir, context.cacheDir, context.getExternalFilesDir(null)?.parentFile,
        context.externalCacheDir,
    ).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.distinct()

    /** Streams [input] into [output], stopping past [OpenedAudio.MAX_BYTES] for a source that gave no size. */
    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > OpenedAudio.MAX_BYTES) throw TooBig()
            output.write(buffer, 0, read)
        }
    }

    /**
     * Whether the copy is something the player can try: Android finds an
     * audio stream in it, or the tag reader recognised its format and length.
     * Neither, and it is not audio whatever its name said.
     */
    private fun looksLikeAudio(file: File, tags: AudioTags): Boolean {
        val extractor = MediaExtractor()
        val hasAudioTrack = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } catch (e: Exception) {
            false
        } finally {
            extractor.release()
        }
        return hasAudioTrack || (tags.codec != AudioCodec.UNKNOWN && tags.durationSeconds > 0)
    }

    private fun dropOldCopies(keep: String) {
        val dirs = copiesRoot.listFiles { f -> f.isDirectory }?.associateBy { it.name } ?: return
        OpenedAudio.copiesToDrop(dirs.mapValues { it.value.lastModified() }, keepKey = keep)
            .forEach { dirs[it]?.deleteRecursively() }
    }

    private fun IOException.isPermissionDenied(): Boolean =
        message?.let { "EACCES" in it || "Permission denied" in it } == true

    private class TooBig : IOException("larger than ${OpenedAudio.MAX_BYTES} bytes")

    private companion object {
        const val TAG = "OpenedAudio"
        const val ACTION_VIEW = "android.intent.action.VIEW"
    }
}
