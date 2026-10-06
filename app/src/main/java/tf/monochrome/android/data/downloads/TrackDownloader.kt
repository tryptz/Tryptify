package tf.monochrome.android.data.downloads

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.util.Locale
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readBytes
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.preferences.AppleQuality
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.buildCoverUrl
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads one track: resolves a stream URL, streams it to disk, tags it,
 * saves lyrics and artwork, and records it in the database.
 *
 * This was the body of a `CoroutineWorker`, one instance per track. It is a
 * plain injectable now so [DownloadQueueWorker] can run several at a time under
 * one job and one notification — see [DownloadQueue] for why one job per track
 * was the wrong shape.
 */
@Singleton
class TrackDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: HiFiApiClient,
    private val lrcLibClient: tf.monochrome.android.data.api.LrcLibClient,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val downloadDao: DownloadDao,
    private val qobuzIdRegistry: tf.monochrome.android.data.api.QobuzIdRegistry,
    private val localLibraryRevision: tf.monochrome.android.data.local.LocalLibraryRevision,
) {

    /** What became of one attempt. */
    enum class Outcome {
        SUCCESS,
        /** Failed in a way another attempt might survive — a dropped connection. */
        RETRYABLE,
        /** Failed for a reason retrying cannot fix — no instance, 404, wrong catalogue. */
        PERMANENT,
    }

    private companion object {
        const val TAG = "TrackDownloader"
        const val COVER_STEM = "cover"
        const val COVER_FILE = "cover.jpg"
    }

    private val folderLock = Any()

    /**
     * Runs one download to completion, reporting 0..1 through [onProgress].
     * A PERMANENT failure the track's service explained is passed to
     * [onFailure], for the Download Center to show.
     *
     * Cancellation propagates as it would anywhere else — the caller's scope
     * cancelling mid-transfer throws out of the read loop and the temp file is
     * cleaned up in the finally below.
     */
    suspend fun download(
        item: DownloadItem,
        onFailure: (String) -> Unit = {},
        onProgress: (Float) -> Unit,
    ): Outcome {
        val trackId = item.trackId
        val trackTitle = item.title
        val artistName = item.artistName
        val albumTitle = item.albumTitle
        val albumCover = item.albumCover
        val duration = item.duration
        val version = item.version
        val isThxSpatialAudio = item.isThxSpatialAudio
        // Apple identity travels separately from the track id. The id captured
        // at enqueue is authoritative; the registry lookup remains only as a
        // fallback for queue entries written before the field existed.
        val explicitAppleId = item.appleId.takeIf { it > 0L }
        val isApple = explicitAppleId != null || qobuzIdRegistry.isAppleTrack(trackId)
        val appleId = explicitAppleId ?: trackId
        val deezerId = item.deezerId.takeIf { it > 0L }
            ?: trackId.takeIf { qobuzIdRegistry.isDeezerTrack(it) && !qobuzIdRegistry.isQobuzTrack(it) }

        return try {
            // Each service downloads in its own quality setting. Apple's ladder
            // is its own (getAppleStreamUrl reads appleQuality), so its tier is
            // only what gets recorded.
            val service = when {
                deezerId != null -> ApiService.DEEZER
                isApple -> ApiService.APPLE
                qobuzIdRegistry.isQobuzTrack(trackId) -> ApiService.QOBUZ
                else -> ApiService.TIDAL
            }
            val quality = if (service == ApiService.APPLE) {
                appleTier(preferences.appleQuality.first())
            } else {
                preferences.downloadQuality(service).first()
            }

            // Resolve the download URL from the track's own service, and only
            // that one. Apple tracks go through getAppleStreamUrl, which streams
            // straight from the home wrapper/agent over Tailscale when an Apple
            // Wrapper URL is configured, else from the cloud
            // /api/apple/download-music. Deezer, Qobuz and TIDAL each use their
            // own server's download route.
            // Set when TIDAL's Dolby Atmos mix is what downloads (TIDAL Dolby Atmos on).
            var isAtmosDownload = false
            val streamUrl = if (deezerId != null) {
                // A Deezer pick downloads from Deezer, the same way a Qobuz
                // pick downloads from Qobuz: /api/deezer/download in the
                // chosen quality. No other catalogue stands in — the bare
                // Deezer id handed to Qobuz or TIDAL would fetch whatever
                // other song has that number, and the 30-second preview is
                // not a download.
                Log.i(TAG, "\"$trackTitle\" is Deezer (deezerId=$deezerId) - downloading from Deezer")
                apiClient.getDeezerDownloadUrl(deezerId, quality) ?: run {
                    Log.w(TAG, "Deezer could not serve \"$trackTitle\" (deezerId=$deezerId, q=$quality) - not falling back to another catalog")
                    return Outcome.PERMANENT
                }
            } else if (isApple) {
                // An Apple pick is wrapper-only. No Qobuz/TIDAL fallback and no
                // metadata bridge — those would hand back a different recording
                // than the one chosen in search. If the wrapper can't serve it,
                // the download fails and says so.
                Log.i(TAG, "\"$trackTitle\" is Apple (adamId=$appleId) - routing to the wrapper only")
                apiClient.getAppleStreamUrl(appleId, quality, atmos = isThxSpatialAudio) ?: run {
                    Log.w(TAG, "wrapper could not serve Apple adamId=$appleId (\"$trackTitle\", q=$quality, atmos=$isThxSpatialAudio) - not falling back to another catalog")
                    return Outcome.PERMANENT
                }
            } else {
                // A Qobuz or TIDAL pick downloads from its own catalogue only:
                // getTrackStream sends a Qobuz id to the Qobuz server and any
                // other id to the TIDAL server. Nothing stands in for it — a
                // title-and-artist match in another catalogue can be a
                // different master or version than the one chosen — so if its
                // own service can't serve it, the download fails and says so.
                val stream = runCatching {
                    apiClient.getTrackStream(trackId, quality, forDownload = true, expectAtmos = item.isDolbyAtmos)
                }.getOrElse { e ->
                    Log.w(TAG, "${service.label} could not serve \"$trackTitle\" (id=$trackId, q=$quality): ${e.message} - not falling back to another catalog")
                    e.message?.let(onFailure)
                    return Outcome.PERMANENT
                }
                isAtmosDownload = stream.isDolbyAtmos
                stream.streamUrl
            }

            // Stream the audio into a temp FILE with progress. Never hold the
            // whole payload in memory: a plain httpClient.get() in Ktor 3
            // saves the entire body into a byte array (SavedCall) before the
            // channel is even read — 30-60 MB per FLAC — and the previous
            // ByteArrayOutputStream added a second full copy. Together they
            // OOM-crashed devices near the 256 MB art heap limit.
            onProgress(0.05f)
            val tempAudio = File.createTempFile("dl_audio", ".dl", context.cacheDir)
            try {
            val fetched = httpClient.prepareGet(streamUrl).execute { response ->
                if (!response.status.isSuccess()) return@execute false
                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                // A Deezer file comes off Deezer's CDN striped with Blowfish;
                // decrypt it on the way to disk (same length, so the
                // truncation check below still holds).
                val decryptor = deezerId?.let { tf.monochrome.android.data.cache.DeezerStripeDecryptor(it) }
                tempAudio.outputStream().use { out ->
                    val sink: (ByteArray, Int, Int) -> Unit = { b, o, l -> out.write(b, o, l) }
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        if (decryptor != null) decryptor.feed(buffer, 0, read, sink) else out.write(buffer, 0, read)
                        totalRead += read
                        if (contentLength > 0) {
                            onProgress((totalRead.toFloat() / contentLength).coerceIn(0.05f, 0.95f))
                        }
                    }
                    decryptor?.finish(sink)
                }
                // A short read means a truncated file, and for M4A that is
                // silently fatal: the container still opens but the audio is cut
                // mid-mdat, so it lands on disk looking fine and won't play.
                // Reject it here rather than saving a corrupt track.
                if (contentLength > 0 && totalRead != contentLength) {
                    Log.w(
                        TAG,
                        "truncated download for \"$trackTitle\": got $totalRead of $contentLength bytes",
                    )
                    return@execute false
                }
                true
            }
            if (!fetched) return Outcome.RETRYABLE

            // Detect what the backend actually delivered (not just what was
            // requested): the download instance can downgrade HI_RES→LOSSLESS,
            // and LOW/HIGH come back as MP3. Basing the extension/mime and the
            // stored record on the real bytes keeps lossy files from being
            // mislabelled .flac (breaks MediaStore + other players) and makes the
            // saved quality accurate. Only the 22-byte header is read.
            val customFolderUri = preferences.downloadFolderUri.first()
            // Apple delivers an MP4/M4A container (ALAC/AAC/EC-3 Atmos) and is
            // left untagged: an Atmos file must reach players byte-for-byte.
            // TIDAL's Atmos mix is an E-AC-3 JOC .m4a that TrypT HiFi has
            // already tagged in its header. Everything else is sniffed —
            // TIDAL's lossy tiers are AAC in MP4, Qobuz's and Deezer's MP3,
            // lossless is FLAC.
            val actualQuality: AudioQuality
            val format: DownloadFormat
            if (isApple || isAtmosDownload) {
                actualQuality = quality
                format = DownloadFormat.M4A
            } else {
                val header = ByteArray(22)
                val headerRead = tempAudio.inputStream().use { it.read(header) }
                val bytes = if (headerRead > 0) header.copyOf(headerRead) else ByteArray(0)
                actualQuality = detectActualQuality(bytes, quality)
                format = DownloadFormat.sniff(bytes)
            }

            // Fetched once and used twice: embedded in the file below and saved
            // as the folder's cover.jpg afterwards. Losing the cover never fails
            // the download.
            val artBytes = albumCover?.takeIf { it.isNotBlank() }
                ?.let { runCatching { fetchAlbumArt(it) }.getOrNull() }

            // The Qobuz CDN FLACs and TIDAL's AAC files arrive with no embedded
            // metadata, so without this every download lands on disk anonymous,
            // and strict offline players (Auxio, Symfonium, MediaStore) sort and
            // group purely on embedded tags. So every FLAC gets Vorbis comments
            // and every AAC .m4a iTunes atoms, with the cover. Tagging works on
            // the temp file (JAudioTagger is file-based): in place for FLAC, on
            // a copy for .m4a (see tagAudioFile). Best-effort: a tagging
            // failure never fails the download (the bytes are good).
            if (format == DownloadFormat.FLAC) repairFlacHeader(tempAudio, trackTitle)
            if (!isApple && !isAtmosDownload && format != DownloadFormat.MP3) {
                tagAudioFile(
                    file = tempAudio,
                    format = format,
                    item = item,
                    title = EmbeddedTags.baseTitle(trackTitle, version),
                    artwork = artBytes,
                )
            }
            val audioSizeBytes = tempAudio.length()

            val fileExt = format.extension
            val audioMime = format.mimeType
            // Artist / Album / "01. Title" — see DownloadLayout.
            val target = DownloadLayout.target(
                title = trackTitle,
                artistName = artistName,
                albumArtist = item.albumArtist,
                albumTitle = albumTitle,
                trackNumber = item.trackNumber,
                discNumber = item.discNumber,
            )
            val stem = target.stem
            val filePath: String
            // The folder the audio went into, when it went to the user's folder.
            var targetDir: DocumentFile? = null
            var inAlbumFolder = false

            val rootDoc = customFolderUri
                ?.let { DocumentFile.fromTreeUri(context, it.toUri()) }
                ?.takeIf { it.canWrite() }
            if (rootDoc != null) {
                val placed = folderFor(rootDoc, target)
                val dir = placed.dir
                dir.findChild("$stem.$fileExt")?.delete()
                val newFile = dir.createFile(audioMime, stem)
                if (newFile != null) {
                    context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                        tempAudio.inputStream().use { input -> input.copyTo(out) }
                    }
                    filePath = newFile.uri.toString()
                    targetDir = dir
                    inAlbumFolder = placed.isAlbumFolder
                } else {
                    filePath = saveToInternal(trackId, fileExt, tempAudio)
                }
            } else {
                filePath = saveToInternal(trackId, fileExt, tempAudio)
            }

            // Save lyrics if enabled. TIDAL is preferred (best quality
            // synced LRC); LRCLib fills in for anything TIDAL 404s on,
            // which is most older / niche / non-Western catalog.
            val downloadLyricsEnabled = preferences.downloadLyrics.first()
            if (downloadLyricsEnabled) {
                try {
                    val lyrics = apiClient.getLyrics(trackId)
                        ?: lrcLibClient.lookup(
                            title = trackTitle,
                            artist = artistName,
                            album = albumTitle,
                            durationSeconds = duration.takeIf { it > 0 },
                        )
                    if (lyrics != null && lyrics.isSynced) {
                        val lrcContent = StringBuilder()
                        lyrics.lines.forEach { line ->
                            val minutes = line.timeMs / 1000 / 60
                            val seconds = (line.timeMs / 1000.0) % 60
                            val timeStr = String.format(Locale.US, "[%02d:%05.2f]", minutes, seconds)
                            lrcContent.append("$timeStr${line.text}\n")
                        }

                        val dir = targetDir
                        if (dir != null) {
                            // Beside the audio, with the same stem, which is how
                            // players pair the two. Created as octet-stream with the
                            // extension in the name: as text/plain, Android's storage
                            // provider appends its own ".txt" to a name with no
                            // matching extension, so the file came out as
                            // "<name>.txt", which no player reads as lyrics, and the
                            // findFile(".lrc") meant to replace it never matched.
                            val lrcName = "$stem.lrc"
                            dir.findChild(lrcName)?.delete()
                            dir.createFile("application/octet-stream", lrcName)?.let {
                                context.contentResolver.openOutputStream(it.uri)?.use { out ->
                                    out.write(lrcContent.toString().toByteArray())
                                }
                            }
                        } else if (rootDoc == null) {
                            val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
                            val lrcFile = File(downloadsDir, "$trackId.lrc")
                            lrcFile.writeText(lrcContent.toString())
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // One cover.jpg per album folder, and per disc folder. The system MediaScanner picks it
            // up as the album image for the audio beside it, which is the only
            // art an MP3 or M4A download has (only FLAC gets it embedded), and
            // the Downloads screen uses it for files it finds with no database
            // row. Errors here are non-fatal: losing the cover shouldn't fail
            // the whole download.
            // Only into the album's own folders: a fallback to the artist's folder
            // would let the first album there claim it for every other.
            val albumDir = targetDir
            if (artBytes != null && albumDir != null && inAlbumFolder) {
                runCatching { saveAlbumCover(albumDir, artBytes) }
            }

            // A re-download that lands somewhere new (the old flat layout, or
            // app storage before a folder was chosen) would otherwise leave the
            // old copy behind. Its database row is about to be replaced, so the
            // Downloads screen would list the old file again as a stray.
            downloadDao.getDownloadedTrack(trackId)?.filePath
                ?.takeIf { !sameFile(it, filePath) }
                ?.let { runCatching { deleteFile(it) } }

            // Tell MediaStore about the new audio + cover so the Local tab
            // sees them without a manual rescan. Only meaningful when the
            // file is in shared storage (SAF folder); for app-private
            // downloads MediaStore won't index regardless.
            notifyMediaScanner(filePath)

            // Insert into database
            downloadDao.insertDownloadedTrack(
                DownloadedTrackEntity(
                    id = trackId,
                    title = trackTitle,
                    duration = duration,
                    artistName = artistName,
                    albumTitle = albumTitle,
                    albumCover = albumCover,
                    filePath = filePath,
                    quality = actualQuality.name,
                    sizeBytes = audioSizeBytes,
                    downloadedAt = System.currentTimeMillis(),
                    version = version,
                    isThxSpatialAudio = isThxSpatialAudio,
                    isDolbyAtmos = isAtmosDownload,
                )
            )
            // A new file is on disk — let the player stop streaming this song.
            localLibraryRevision.bump()

            onProgress(1f)
            Outcome.SUCCESS
            } finally {
                tempAudio.delete()
            }
        } catch (e: Exception) {
            // Never swallow this silently. A throw here puts the request back to
            // ENQUEUED for the backoff window, which the download list renders as
            // "Queued" — so a repeatedly-failing download is indistinguishable
            // from one the scheduler hasn't started, and there was nothing in
            // logcat to tell them apart.
            // A missing endpoint is a setup problem retries can't fix — fail
            // now so the row flips to "Failed" instead of faking three more
            // minutes of "Queued".
            // A missing endpoint is a setup problem no retry can fix; anything
            // else gets another go from the queue, which counts the attempts.
            val permanent = e is tf.monochrome.android.data.api.NoInstancesConfiguredException
            Log.w(
                TAG,
                "download failed for \"$trackTitle\" (id=$trackId, apple=$isApple) — " +
                    if (permanent) "not retryable" else "will retry",
                e,
            )
            if (permanent) Outcome.PERMANENT else Outcome.RETRYABLE
        }
    }

    /**
     * Downloads the album cover. [cover] is either a full URL (Qobuz, Apple)
     * or a bare TIDAL cover id, which isn't fetchable as-is — it is expanded
     * with [buildCoverUrl], largest size first, since the same bytes end up
     * embedded in the file and shown full-screen by offline players.
     */
    private suspend fun fetchAlbumArt(cover: String): ByteArray? {
        val urls = if (cover.contains("://")) listOf(cover)
            else listOf(1280, 640).map { buildCoverUrl(cover, it) }
        for (url in urls) {
            val bytes = runCatching {
                val response = httpClient.get(url)
                if (response.status.isSuccess()) response.readBytes() else null
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    /**
     * The folder [target] belongs in under [root], made if it is missing:
     * the artist's folder, the album's inside it when there is an album, and
     * a disc folder inside that from disc 2 on. Falls back to the nearest
     * folder that could be had.
     *
     * Locked because downloads run several at a time, and two tracks of one
     * album would otherwise both find no "Artist" folder and both create one:
     * the provider names the second "Artist (1)" and splits the album in two.
     */
    private fun folderFor(root: DocumentFile, target: DownloadLayout.Target): Placement =
        synchronized(folderLock) {
            val artist = root.childDirectory(target.artistFolder) ?: return Placement(root, false)
            val albumName = target.albumFolder ?: return Placement(artist, false)
            val album = artist.childDirectory(albumName) ?: return Placement(artist, false)
            val disc = target.discFolder ?: return Placement(album, true)
            Placement(album.childDirectory(disc) ?: album, true)
        }

    /**
     * Where [folderFor] put a track, and whether that folder is the album's
     * own (or one of its disc folders), which is where a cover.jpg belongs.
     */
    private class Placement(val dir: DocumentFile, val isAlbumFolder: Boolean)

    /**
     * Writes the album's cover.jpg into [albumDir] unless it already has one.
     * Under the same lock as [folderFor], for the same race: two tracks would
     * both find none, and the second would write "cover (1).jpg".
     */
    private fun saveAlbumCover(albumDir: DocumentFile, bytes: ByteArray) {
        synchronized(folderLock) {
            if (albumDir.findChild(COVER_FILE) != null) return
            albumDir.createFile("image/jpeg", COVER_STEM)?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                notifyMediaScanner(file.uri.toString())
            }
        }
    }

    private fun DocumentFile.childDirectory(name: String): DocumentFile? =
        findChild(name)?.takeIf { it.isDirectory } ?: createDirectory(name)

    /**
     * [DocumentFile.findFile], ignoring case. Shared storage does not tell
     * "Abba" from "ABBA", so a case-sensitive miss followed by a create gets
     * "ABBA (1)" from the provider rather than the folder already there.
     */
    private fun DocumentFile.findChild(name: String): DocumentFile? =
        listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Whether two stored locations are the same file. Two content URIs can
     * name one document through different folder grants (the download folder
     * picked again, or a parent of it), so they are compared by provider and
     * document id, not as strings. Deleting the "old" copy of a re-download on
     * a string mismatch would otherwise delete the file just written.
     */
    private fun sameFile(a: String, b: String): Boolean {
        if (a == b) return true
        if (!a.startsWith("content://") || !b.startsWith("content://")) return false
        return runCatching {
            val ua = a.toUri()
            val ub = b.toUri()
            ua.authority == ub.authority &&
                DocumentsContract.getDocumentId(ua) == DocumentsContract.getDocumentId(ub)
        }.getOrDefault(true) // unsure: keep the old file rather than risk the new one
    }

    /** Removes a download's file, by content URI or by path. */
    private fun deleteFile(pathOrUri: String) {
        if (pathOrUri.startsWith("content://")) {
            DocumentFile.fromSingleUri(context, pathOrUri.toUri())?.delete()
        } else {
            File(pathOrUri).delete()
        }
    }

    /**
     * Triggers MediaScannerConnection.scanFile for files in shared storage
     * so the Local tab picks them up. content:// SAF URIs are mapped back
     * to their on-disk path via DocumentFile / DocumentsContract; raw file
     * paths are scanned directly. Failures are silent — the download
     * succeeded regardless.
     */
    private fun notifyMediaScanner(pathOrUri: String) {
        runCatching {
            val resolved = when {
                pathOrUri.startsWith("content://") -> {
                    // Best effort: we only need the path for MediaScanner,
                    // and not all SAF backings expose one. If we can't
                    // resolve, skip — the user's MediaStore cache will
                    // catch it on the next general scan.
                    null
                }
                pathOrUri.startsWith("file://") -> pathOrUri.removePrefix("file://")
                else -> pathOrUri
            } ?: return
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(resolved),
                null,
                null,
            )
        }
    }

    /**
     * Marks a FLAC's last metadata block as last when it is not (see
     * [FlacMetadata]), so JAudioTagger can tag the file. One header bit, in
     * place; best-effort like the tagging it is for.
     */
    private fun repairFlacHeader(file: File, title: String) {
        runCatching {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val head = ByteArray(minOf(raf.length(), 65_536L).toInt())
                raf.readFully(head)
                FlacMetadata.unmarkedLastBlock(head)?.let { at ->
                    raf.seek(at.toLong())
                    raf.write(head[at].toInt() or 0x80)
                    Log.i(TAG, "tag: marked the last FLAC metadata block of \"$title\"")
                }
            }
        }.onFailure { Log.w(TAG, "tag: FLAC header check failed for \"$title\": ${it.message}") }
    }

    /**
     * Infer the format actually delivered from the file bytes, so the saved record
     * reflects reality rather than the requested tier:
     *  - "fLaC" magic → FLAC; read bits-per-sample from STREAMINFO to tell
     *    HI_RES (≥24-bit) from LOSSLESS (16-bit), catching a HI_RES→LOSSLESS downgrade.
     *  - anything else → lossy (MP3) → report as HIGH.
     * Falls back to [requested] if the bytes are too short to classify.
     */
    /** The tier an Apple download is recorded as, from Apple's own ladder. */
    private fun appleTier(quality: AppleQuality): AudioQuality = when (quality) {
        AppleQuality.HIRES_LOSSLESS -> AudioQuality.HI_RES
        AppleQuality.ALAC -> AudioQuality.LOSSLESS
        AppleQuality.AAC -> AudioQuality.HIGH
    }

    private fun detectActualQuality(data: ByteArray, requested: AudioQuality): AudioQuality {
        if (data.size < 4) return requested
        val isFlac = data[0] == 'f'.code.toByte() && data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() && data[3] == 'C'.code.toByte()
        if (!isFlac) return AudioQuality.HIGH
        // STREAMINFO begins at byte 8 (4 magic + 4 block header). Bits-per-sample
        // is the 5-bit field straddling bytes 20 (low bit) and 21 (high 4 bits),
        // stored as value-1.
        if (data.size < 22) return AudioQuality.LOSSLESS
        val b20 = data[20].toInt() and 0xFF
        val b21 = data[21].toInt() and 0xFF
        val bitsPerSample = (((b20 and 0x01) shl 4) or (b21 shr 4)) + 1
        return if (bitsPerSample >= 24) AudioQuality.HI_RES else AudioQuality.LOSSLESS
    }

    /** Streamed copy from the download temp file — never a whole-file byte array. */
    private fun saveToInternal(trackId: Long, ext: String, source: File): String {
        val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val targetFile = File(downloadsDir, "$trackId.$ext")
        source.inputStream().use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        return targetFile.absolutePath
    }

    /**
     * Embed tags and the front cover into a FLAC (Vorbis comments) or AAC
     * .m4a (iTunes atoms) file in place (JAudioTagger is file-based, so no
     * byte-array round trip). The download temp carries a ".dl" extension and
     * JAudioTagger picks its reader by extension, so the file is renamed to an
     * alias with the format's extension for the tagging and renamed back.
     * Best-effort: on any failure the file is left playable and the download
     * still succeeds.
     */
    private fun tagAudioFile(
        file: File,
        format: DownloadFormat,
        item: DownloadItem,
        title: String,
        artwork: ByteArray?,
    ) {
        val alias = File(file.parentFile, "${file.nameWithoutExtension}_tag.${format.extension}")
        // An .m4a is tagged on a copy that replaces the download only once
        // JAudioTagger finishes. Tagging MP4 rewrites box offsets, and on a
        // fragmented MP4 (a DASH track assembled into one file) JAudioTagger
        // writes the tags, then finds the offsets wrong and throws — a file
        // that must not replace the good one. A FLAC's tags sit in front of
        // the audio, so it is tagged in place.
        val onCopy = format == DownloadFormat.M4A
        val staged = if (onCopy) runCatching { file.copyTo(alias, overwrite = true) }.isSuccess else file.renameTo(alias)
        if (!staged) {
            Log.w(TAG, "tag: staging the file for tagging failed for \"$title\"")
            return
        }
        var committed = false
        try {
            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(alias)
            val tag = audioFile.tagOrCreateAndSetDefault ?: return
            fun write(key: org.jaudiotagger.tag.FieldKey, value: String?) {
                value?.takeIf { it.isNotBlank() }?.let { tag.setField(key, it) }
            }
            write(org.jaudiotagger.tag.FieldKey.TITLE, title)
            write(org.jaudiotagger.tag.FieldKey.ARTIST, item.artistName)
            write(org.jaudiotagger.tag.FieldKey.ALBUM, item.albumTitle)
            // ALBUMARTIST keeps a record with featured guests together as one
            // album instead of splitting it across every ARTIST credit.
            write(org.jaudiotagger.tag.FieldKey.ALBUM_ARTIST, item.albumArtist)
            // Without these, players fall back to sorting an album by title.
            write(org.jaudiotagger.tag.FieldKey.TRACK, item.trackNumber?.takeIf { it > 0 }?.toString())
            write(org.jaudiotagger.tag.FieldKey.DISC_NO, item.discNumber?.takeIf { it > 0 }?.toString())
            // FieldKey.YEAR is the Vorbis DATE comment.
            write(org.jaudiotagger.tag.FieldKey.YEAR, EmbeddedTags.releaseDate(item.releaseDate))
            write(org.jaudiotagger.tag.FieldKey.GENRE, item.genre)
            // COMMENT marker as belt-and-braces for players that ignore VERSION.
            if (item.isThxSpatialAudio) tag.setField(org.jaudiotagger.tag.FieldKey.COMMENT, "THX Spatial Audio")
            when (tag) {
                is org.jaudiotagger.tag.flac.FlacTag -> {
                    // Raw VERSION comment — the field Qobuz itself uses for the release.
                    item.version?.takeIf { it.isNotBlank() }?.let { tag.setField("VERSION", it) }
                    // A picture the source already embedded is the label's own — keep it.
                    if (artwork != null && tag.images.isEmpty()) embedCover(tag, artwork, title)
                }
                is org.jaudiotagger.tag.mp4.Mp4Tag -> {
                    if (artwork != null && !tag.hasField(org.jaudiotagger.tag.mp4.Mp4FieldKey.ARTWORK)) {
                        embedCover(tag, artwork, title)
                    }
                }
            }
            audioFile.commit()
            committed = true
        } catch (e: Exception) {
            Log.w(TAG, "tag: ${format.name} tagging failed for \"$title\": ${e.message}")
        } finally {
            when {
                !onCopy -> alias.renameTo(file)
                // rename(2) replaces the untagged file in one step.
                committed && alias.renameTo(file) -> Unit
                // Not tagged, or not swapped in: keep the download as it came.
                else -> alias.delete()
            }
        }
    }

    /**
     * Adds [bytes] as an .m4a's `covr` atom. Built from the bytes alone
     * ([org.jaudiotagger.tag.mp4.field.Mp4TagCoverField] reads the image type
     * from its header), for the same reason as the FLAC overload: JAudioTagger's
     * `Artwork` type needs `javax.imageio`, which Android lacks.
     */
    private fun embedCover(tag: org.jaudiotagger.tag.mp4.Mp4Tag, bytes: ByteArray, title: String) {
        val mime = EmbeddedTags.imageMime(bytes)
        if (mime == null || bytes.size > EmbeddedTags.MAX_EMBEDDED_ART_BYTES) {
            Log.i(TAG, "tag: not embedding cover for \"$title\" (type=$mime, ${bytes.size} bytes)")
            return
        }
        tag.setField(org.jaudiotagger.tag.mp4.field.Mp4TagCoverField(bytes))
    }

    /**
     * Adds [bytes] as the front-cover METADATA_BLOCK_PICTURE. Built straight
     * from the bytes with [org.jaudiotagger.tag.flac.FlacTag.createArtworkField]
     * rather than JAudioTagger's `Artwork` type, which decodes the image
     * through `javax.imageio` — absent on Android. The picture block records
     * the image's dimensions, read here from its header alone.
     */
    private fun embedCover(tag: org.jaudiotagger.tag.flac.FlacTag, bytes: ByteArray, title: String) {
        val mime = EmbeddedTags.imageMime(bytes)
        if (mime == null || bytes.size > EmbeddedTags.MAX_EMBEDDED_ART_BYTES) {
            Log.i(TAG, "tag: not embedding cover for \"$title\" (type=$mime, ${bytes.size} bytes)")
            return
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        tag.setField(
            tag.createArtworkField(
                bytes,
                org.jaudiotagger.tag.reference.PictureTypes.DEFAULT_ID,
                mime,
                "",
                bounds.outWidth.coerceAtLeast(0),
                bounds.outHeight.coerceAtLeast(0),
                24,
                0,
            )
        )
    }
}
