// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import android.content.Context
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.LrcFormat
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.local.tags.TagReader
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.Lyrics
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A song's own lyrics, from the file it plays from: a local file, or the
 * song's download. The `.lrc` beside it (which is how players pair them, and
 * what a download, the lyrics editor and a lyrics server write) and the
 * lyrics stored in its tags, whichever is timed better; the `.lrc` on a tie.
 *
 * Read before any catalogue is asked, so a library someone timed by hand
 * shows its own lyrics, offline too.
 */
@Singleton
class LocalLyrics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagReader: TagReader,
    private val downloadDao: DownloadDao,
) {
    /** [filePath] when the song plays from one, else its download's; null when it has neither, or no lyrics. */
    suspend fun find(trackId: Long, filePath: String?): Lyrics? = withContext(Dispatchers.IO) {
        val path = filePath ?: try {
            downloadDao.getDownloadedTrack(trackId)?.filePath
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val beside = runCatching { besideText(path) }.getOrNull()?.let(::parse)
        val inside = if (path.startsWith("content://")) null else tagReader.readEmbeddedLyrics(path.removePrefix("file://"))?.let(::parse)
        listOfNotNull(beside, inside).maxByOrNull { LyricsRace.tier(it) }
    }

    /** The `.lrc` with [path]'s own stem, in its folder. */
    private fun besideText(path: String): String? {
        if (path.startsWith("content://")) return besideDocument(path)
        val audio = File(path.removePrefix("file://"))
        val folder = audio.parentFile ?: return null
        val lrc = sequenceOf("lrc", "LRC").map { File(folder, "${audio.nameWithoutExtension}.$it") }.firstOrNull { it.isFile }
            ?: return null
        if (lrc.length() > MAX_SIDECAR_BYTES) return null
        return lrc.readText()
    }

    /**
     * The same for a document under a granted tree, found as [tf.monochrome.android.data.downloads.LrcSidecar]
     * writes it: the external storage provider's ids are paths, so the folder
     * is the id up to its last slash.
     */
    private fun besideDocument(uriString: String): String? {
        val uri = uriString.toUri()
        val documentId = DocumentsContract.getDocumentId(uri)
        val name = documentId.substringAfterLast('/')
        val folderId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
        if (folderId.isEmpty() || name.isEmpty()) return null
        val folder = DocumentFile.fromTreeUri(context, DocumentsContract.buildDocumentUriUsingTree(uri, folderId)) ?: return null
        val stem = name.substringBeforeLast('.')
        val lrc = folder.findFile("$stem.lrc") ?: folder.findFile("$stem.LRC") ?: return null
        if (lrc.length() > MAX_SIDECAR_BYTES) return null
        return context.contentResolver.openInputStream(lrc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        /** A lyrics file this big is not one. */
        private const val MAX_SIDECAR_BYTES = 512L * 1024

        /** [text] as lyrics: LRC when it is, plain lines when it is not; null when it has no words. */
        fun parse(text: String): Lyrics? {
            LrcFormat.parse(text)?.let { return it }
            val lines = text.removePrefix("﻿").lines().map { it.trimEnd() }.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            if (lines.none { it.isNotBlank() }) return null
            return Lyrics(lines = lines.map { LyricLine(timeMs = 0, text = it) }, isSynced = false)
        }
    }
}
