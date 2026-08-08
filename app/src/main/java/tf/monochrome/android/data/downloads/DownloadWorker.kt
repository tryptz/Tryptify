package tf.monochrome.android.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import java.util.Locale
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tf.monochrome.android.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.readBytes
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import tf.monochrome.android.data.api.HiFiApiClient
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.entity.DownloadedTrackEntity
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.AudioQuality
import kotlinx.coroutines.flow.first
import java.io.File

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: HiFiApiClient,
    private val lrcLibClient: tf.monochrome.android.data.api.LrcLibClient,
    private val httpClient: HttpClient,
    private val preferences: PreferencesManager,
    private val downloadDao: DownloadDao,
    private val qobuzIdRegistry: tf.monochrome.android.data.api.QobuzIdRegistry,
    private val localLibraryRevision: tf.monochrome.android.data.local.LocalLibraryRevision,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_APPLE_ID = "apple_id"
        const val KEY_TRACK_TITLE = "track_title"
        const val KEY_ARTIST_NAME = "artist_name"
        const val KEY_ALBUM_TITLE = "album_title"
        const val KEY_ALBUM_COVER = "album_cover"
        const val KEY_DURATION = "duration"
        const val KEY_VERSION = "version"
        const val KEY_IS_THX = "is_thx_spatial_audio"
        const val KEY_PROGRESS = "progress"
        private const val TAG = "DownloadWorker"
        private const val CHANNEL_ID = "downloads"
        // Per-track offset so two concurrent downloads don't overwrite each
        // other's notification (WorkManager posts one per foreground worker).
        private const val NOTIFICATION_ID_BASE = 8100
    }

    override suspend fun doWork(): Result {
        val trackId = inputData.getLong(KEY_TRACK_ID, -1L)
        if (trackId == -1L) return Result.failure()

        // Promote to a foreground service for the duration of the transfer.
        // Expedited scheduling (see DownloadManager) gets the job *started*
        // promptly, but on API 31+ an expedited job is only guaranteed a short
        // slice of runtime — a 60 MB FLAC over a slow link outlives it. Running
        // in the foreground also stops OEM battery managers (OxygenOS et al)
        // from freezing the transfer the moment the user leaves the app.
        // Best-effort: Android 14+ refuses a background FGS start, and a
        // download that merely loses its notification is better than one that
        // fails outright.
        runCatching {
            setForeground(buildForegroundInfo(inputData.getString(KEY_TRACK_TITLE) ?: "Track", trackId))
        }

        val trackTitle = inputData.getString(KEY_TRACK_TITLE) ?: "Unknown"
        val artistName = inputData.getString(KEY_ARTIST_NAME) ?: "Unknown Artist"
        val albumTitle = inputData.getString(KEY_ALBUM_TITLE)
        val albumCover = inputData.getString(KEY_ALBUM_COVER)
        val duration = inputData.getInt(KEY_DURATION, 0)
        val version = inputData.getString(KEY_VERSION)
        val isThxSpatialAudio = inputData.getBoolean(KEY_IS_THX, false)
        // Apple identity travels separately from the track id. The explicit
        // KEY_APPLE_ID (set at enqueue from Track.appleId) is authoritative;
        // the registry lookup remains only as a fallback for work that was
        // enqueued before the key existed.
        val explicitAppleId = inputData.getLong(KEY_APPLE_ID, -1L).takeIf { it > 0L }
        val isApple = explicitAppleId != null || qobuzIdRegistry.isAppleTrack(trackId)
        val appleId = explicitAppleId ?: trackId

        return try {
            // Get download quality preference
            val quality = preferences.downloadQuality.first()

            // Resolve the download URL. Apple tracks go through getAppleStreamUrl,
            // which streams straight from the home wrapper/agent over Tailscale when
            // an Apple Wrapper URL is configured, else falls back to the cloud
            // /api/apple/download-music. Everything else uses the Qobuz instance.
            // Apple first when the track carries an Apple identity. Otherwise
            // try the native (Qobuz/TIDAL) path, and if that yields nothing,
            // bridge to Apple by metadata — a track whose catalog id is a
            // synthetic hash has no usable native id, but the same recording is
            // almost always in the Apple catalog and the wrapper can decrypt it.
            var usedApple = isApple
            val streamUrl = if (isApple) {
                // An Apple pick is wrapper-only. No Qobuz/TIDAL fallback and no
                // metadata bridge — those would hand back a different recording
                // than the one chosen in search. If the wrapper can't serve it,
                // the download fails and says so.
                Log.i(TAG, "\"$trackTitle\" is Apple (adamId=$appleId) - routing to the wrapper only")
                apiClient.getAppleStreamUrl(appleId, quality, atmos = isThxSpatialAudio) ?: run {
                    Log.w(TAG, "wrapper could not serve Apple adamId=$appleId (\"$trackTitle\", q=$quality, atmos=$isThxSpatialAudio) - not falling back to another catalog")
                    return Result.failure()
                }
            } else {
                val native = runCatching {
                    apiClient.getTrackStream(trackId, quality, forDownload = true).streamUrl
                }.getOrNull()
                native ?: run {
                    // A Qobuz pick is Qobuz-only, on the same principle as the
                    // Apple branch above: the metadata bridge matches by title
                    // and artist, so it can hand back a different master or
                    // version than the one chosen in search. If Qobuz can't
                    // serve it, the download fails and says so.
                    if (qobuzIdRegistry.isQobuzTrack(trackId)) {
                        Log.w(TAG, "Qobuz could not serve \"$trackTitle\" (id=$trackId, q=$quality) - not falling back to another catalog")
                        return Result.failure()
                    }
                    val bridged = apiClient.findAppleIdFor(
                        trackId = trackId,
                        title = trackTitle,
                        artist = artistName,
                        durationSeconds = duration,
                    )
                    if (bridged == null) {
                        Log.w(TAG, "no stream url for \"$trackTitle\" (id=$trackId, q=$quality) and no Apple match")
                        return Result.failure()
                    }
                    Log.i(TAG, "bridged \"$trackTitle\" (id=$trackId) to Apple adamId=$bridged")
                    usedApple = true
                    apiClient.getAppleStreamUrl(bridged, quality, atmos = isThxSpatialAudio) ?: run {
                        Log.w(TAG, "Apple bridge found adamId=$bridged but no stream url for \"$trackTitle\"")
                        return Result.failure()
                    }
                }
            }

            // Stream the audio into a temp FILE with progress. Never hold the
            // whole payload in memory: a plain httpClient.get() in Ktor 3
            // saves the entire body into a byte array (SavedCall) before the
            // channel is even read — 30-60 MB per FLAC — and the previous
            // ByteArrayOutputStream added a second full copy. Together they
            // OOM-crashed devices near the 256 MB art heap limit.
            setProgress(workDataOf(KEY_PROGRESS to 0.05f))
            val tempAudio = File.createTempFile("dl_audio", ".dl", context.cacheDir)
            try {
            val fetched = httpClient.prepareGet(streamUrl).execute { response ->
                if (!response.status.isSuccess()) return@execute false
                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(8192)
                var totalRead = 0L
                tempAudio.outputStream().use { out ->
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        totalRead += read
                        if (contentLength > 0) {
                            val progress = (totalRead.toFloat() / contentLength).coerceIn(0.05f, 0.95f)
                            setProgress(workDataOf(KEY_PROGRESS to progress))
                        }
                    }
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
            if (!fetched) return Result.failure()

            // Detect what the backend actually delivered (not just what was
            // requested): the download instance can downgrade HI_RES→LOSSLESS,
            // and LOW/HIGH come back as MP3. Basing the extension/mime and the
            // stored record on the real bytes keeps lossy files from being
            // mislabelled .flac (breaks MediaStore + other players) and makes the
            // saved quality accurate. Only the 22-byte header is read.
            val customFolderUri = preferences.downloadFolderUri.first()
            // Apple delivers an MP4/M4A container (ALAC/AAC/EC-3 Atmos), never
            // FLAC/MP3 — skip header sniffing + FLAC tagging for it.
            val actualQuality: AudioQuality
            val isFlac: Boolean
            if (usedApple) {
                actualQuality = quality
                isFlac = false
            } else {
                val header = ByteArray(22)
                val headerRead = tempAudio.inputStream().use { it.read(header) }
                actualQuality =
                    detectActualQuality(if (headerRead > 0) header.copyOf(headerRead) else ByteArray(0), quality)
                isFlac = actualQuality == AudioQuality.LOSSLESS || actualQuality == AudioQuality.HI_RES
            }

            // The Qobuz CDN FLACs arrive with no embedded metadata, so a THX
            // download would land on disk anonymous. Embed Vorbis comments now
            // (only for THX/versioned FLACs, so ordinary downloads keep their
            // current fast path) — TITLE without the version suffix, the raw
            // VERSION string Qobuz uses, and a COMMENT marker for players that
            // ignore VERSION. Tagging happens in place on the temp file
            // (JAudioTagger is file-based). Best-effort: a tagging failure
            // never fails the download (the bytes are good).
            if (isFlac && (isThxSpatialAudio || !version.isNullOrBlank())) {
                val baseTitle = if (!version.isNullOrBlank()) {
                    trackTitle.removeSuffix(" — $version").trim()
                } else trackTitle
                tagFlacFile(
                    file = tempAudio,
                    title = baseTitle,
                    artist = artistName,
                    album = albumTitle,
                    version = version,
                    isThxSpatialAudio = isThxSpatialAudio,
                )
            }
            val audioSizeBytes = tempAudio.length()

            val fileExt = if (usedApple) "m4a" else if (isFlac) "flac" else "mp3"
            val audioMime = if (usedApple) "audio/mp4" else if (isFlac) "audio/flac" else "audio/mpeg"
            val sanitizedTitle = "${artistName} - ${trackTitle}".replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val fileName = "$sanitizedTitle.$fileExt"
            val filePath: String

            if (customFolderUri != null) {
                // Save to user-selected folder via SAF
                val treeUri = customFolderUri.toUri()
                val docFile = DocumentFile.fromTreeUri(context, treeUri)
                if (docFile != null && docFile.canWrite()) {
                    val existing = docFile.findFile(fileName)
                    existing?.delete()
                    val newFile = docFile.createFile(audioMime, sanitizedTitle)
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                            tempAudio.inputStream().use { input -> input.copyTo(out) }
                        }
                        filePath = newFile.uri.toString()
                    } else {
                        // Fallback to internal
                        filePath = saveToInternal(trackId, fileExt, tempAudio)
                    }
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

                        val lrcFileName = "$sanitizedTitle.lrc"
                        if (customFolderUri != null) {
                            val treeUri = customFolderUri.toUri()
                            val docFile = DocumentFile.fromTreeUri(context, treeUri)
                            if (docFile != null && docFile.canWrite()) {
                                val existing = docFile.findFile(lrcFileName)
                                existing?.delete()
                                val lrcFile = docFile.createFile("text/plain", sanitizedTitle)
                                lrcFile?.let {
                                    context.contentResolver.openOutputStream(it.uri)?.use { out ->
                                        out.write(lrcContent.toString().toByteArray())
                                    }
                                }
                            }
                        } else {
                            val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
                            val lrcFile = File(downloadsDir, "$trackId.lrc")
                            lrcFile.writeText(lrcContent.toString())
                        }
                    }
                } catch (_: Exception) {
                }
            }

            // Save the album art alongside the track. Two reasons:
            //   1. The system MediaScanner picks up `cover.jpg` /
            //      `albumart.jpg` in the same folder as audio files and
            //      attaches them as the album image automatically — that's
            //      what makes downloaded albums show their cover in the
            //      Local tab on a fresh install.
            //   2. Other Android players (and our own DownloadsScreen) can
            //      load the cover off-line.
            // Errors here are non-fatal — losing the cover shouldn't fail
            // the whole download.
            if (!albumCover.isNullOrBlank()) {
                runCatching { saveAlbumArt(albumCover, sanitizedTitle, customFolderUri) }
            }

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
                    isThxSpatialAudio = isThxSpatialAudio
                )
            )
            // A new file is on disk — let the player stop streaming this song.
            localLibraryRevision.bump()

            Result.success()
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
            val giveUp = runAttemptCount >= 3 ||
                e is tf.monochrome.android.data.api.NoInstancesConfiguredException
            Log.w(
                TAG,
                "download failed for \"$trackTitle\" (id=$trackId, apple=$isApple, " +
                    "attempt=${runAttemptCount + 1}) — ${if (giveUp) "giving up" else "retrying"}",
                e,
            )
            if (giveUp) Result.failure() else Result.retry()
        }
    }

    /**
     * Required for expedited work below API 31, where WorkManager satisfies an
     * expedited request by running the worker as a foreground service and needs
     * a notification up front. On API 31+ this is unused at schedule time — the
     * job runs as a JobScheduler expedited job — but [doWork] still calls
     * [setForeground] with the same notification once the transfer starts.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(
            inputData.getString(KEY_TRACK_TITLE) ?: "Track",
            inputData.getLong(KEY_TRACK_ID, 0L),
        )

    private fun buildForegroundInfo(title: String, trackId: Long): ForegroundInfo {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        val id = NOTIFICATION_ID_BASE + (kotlin.math.abs(trackId) % 1000).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    private fun createChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress of track downloads."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    /**
     * Fetches the album cover URL and saves it both as `<sanitizedTitle>.jpg`
     * (per-track sidecar, matched by some MP3-style players) and as
     * `cover.jpg` in the same folder (the Android MediaScanner convention).
     * Skipped silently on network or SAF failure.
     */
    private suspend fun saveAlbumArt(
        coverUrl: String,
        sanitizedTitle: String,
        customFolderUri: String?,
    ) {
        val response = httpClient.get(coverUrl)
        if (!response.status.isSuccess()) return
        val bytes = response.readBytes()
        if (bytes.isEmpty()) return

        if (customFolderUri != null) {
            val treeUri = customFolderUri.toUri()
            val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return
            if (!docFile.canWrite()) return
            // Per-track sidecar.
            val perTrackName = "$sanitizedTitle.jpg"
            docFile.findFile(perTrackName)?.delete()
            docFile.createFile("image/jpeg", sanitizedTitle)?.let { file ->
                context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                notifyMediaScanner(file.uri.toString())
            }
            // Folder-level cover.jpg — MediaScanner reads this for the
            // album thumbnail without needing to embed the picture in
            // each FLAC's METADATA_BLOCK_PICTURE.
            if (docFile.findFile("cover.jpg") == null) {
                docFile.createFile("image/jpeg", "cover")?.let { file ->
                    context.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                    notifyMediaScanner(file.uri.toString())
                }
            }
        } else {
            val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            File(downloadsDir, "$sanitizedTitle.jpg").writeBytes(bytes)
            // App-private storage isn't MediaStore-visible, so cover.jpg
            // is purely for the in-app off-line cover lookup.
            val coverFile = File(downloadsDir, "cover.jpg")
            if (!coverFile.exists()) coverFile.writeBytes(bytes)
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
     * Infer the format actually delivered from the file bytes, so the saved record
     * reflects reality rather than the requested tier:
     *  - "fLaC" magic → FLAC; read bits-per-sample from STREAMINFO to tell
     *    HI_RES (≥24-bit) from LOSSLESS (16-bit), catching a HI_RES→LOSSLESS downgrade.
     *  - anything else → lossy (MP3) → report as HIGH.
     * Falls back to [requested] if the bytes are too short to classify.
     */
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
     * Embed Vorbis comments into a FLAC file in place (JAudioTagger is
     * file-based, so no byte-array round trip). The download temp carries a
     * ".dl" extension and JAudioTagger picks its reader by extension, so the
     * file is renamed to a ".flac" alias for the tagging and renamed back.
     * Best-effort: on any failure the file is left playable and the download
     * still succeeds.
     */
    private fun tagFlacFile(
        file: File,
        title: String,
        artist: String,
        album: String?,
        version: String?,
        isThxSpatialAudio: Boolean,
    ) {
        val alias = File(file.parentFile, "${file.nameWithoutExtension}_tag.flac")
        if (!file.renameTo(alias)) {
            Log.w(TAG, "THX tag: rename for tagging failed for \"$title\"")
            return
        }
        try {
            val audioFile = org.jaudiotagger.audio.AudioFileIO.read(alias)
            val tag = audioFile.tagOrCreateAndSetDefault as? org.jaudiotagger.tag.flac.FlacTag
                ?: return
            if (title.isNotBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, title)
            if (artist.isNotBlank()) tag.setField(org.jaudiotagger.tag.FieldKey.ARTIST, artist)
            album?.takeIf { it.isNotBlank() }?.let { tag.setField(org.jaudiotagger.tag.FieldKey.ALBUM, it) }
            // Raw VERSION comment — the field Qobuz itself uses for the release.
            version?.takeIf { it.isNotBlank() }?.let { tag.setField("VERSION", it) }
            // COMMENT marker as belt-and-braces for players that ignore VERSION.
            if (isThxSpatialAudio) tag.setField(org.jaudiotagger.tag.FieldKey.COMMENT, "THX Spatial Audio")
            audioFile.commit()
        } catch (e: Exception) {
            Log.w(TAG, "THX tag: FLAC tagging failed for \"$title\": ${e.message}")
        } finally {
            alias.renameTo(file)
        }
    }
}
