// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.R

/**
 * Imports one StepMania pack in the background: downloaded from StepMania
 * Online (or any https link to a pack ZIP), read from a ZIP the player
 * picked, or copied from songs already unpacked in a folder they picked.
 *
 * Packs run hundreds of megabytes, so this is a foreground job with the
 * download in a notification, like the app's own downloads. Jobs queue: tap
 * three packs and they import one after another ([WORK_NAME], appended).
 *
 * The ZIP is streamed straight from the connection into [PackImporter] — it
 * is never saved whole first, so a pack costs its unpacked size on the phone
 * and no more.
 */
@HiltWorker
class PackImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val library: GlyphPackLibrary,
) : CoroutineWorker(context, params) {

    private var lastShownAt = 0L
    private var name: String = inputData.getString(KEY_NAME).orEmpty()


    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { setForeground(foregroundInfo(0f, "Starting")) }
        val url = inputData.getString(KEY_URL)
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse)
        val tree = inputData.getString(KEY_TREE)?.let(Uri::parse)
        try {
            if (tree != null) return@withContext importFolder(tree)
            val opened = when {
                url != null -> download(url)
                uri != null -> open(uri)
                else -> return@withContext Result.failure()
            }
            if (opened.name.isNotBlank()) name = opened.name
            val result = opened.stream.use { stream ->
                library.importer().import(
                    input = stream,
                    nameHint = name.ifBlank { "Imported pack" },
                    onBytes = { read -> report(read, opened.length) },
                    isActive = { !isStopped },
                )
            }
            library.refresh()
            val packs = result.packs.joinToString(", ") { it.name }
            val skipped = if (result.skippedSongs > 0) " ${result.skippedSongs} without a dance or pop'n chart were left out." else ""
            notifyFinished("Pack imported", "$packs: ${result.songs} songs.$skipped")
            Result.success(workDataOf(KEY_NAME to packs, KEY_SONGS to result.songs, KEY_SKIPPED to result.skippedSongs))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message ?: "The pack could not be imported."
            notifyFinished("Pack not imported", "${name.ifBlank { "Pack" }}: $reason")
            Result.failure(workDataOf(KEY_NAME to name, KEY_FAILURE to reason))
        } finally {
            // A folder's grant stays while any installed pack reads from it,
            // a partial import's included; otherwise it goes back.
            val keepGrant = tree != null && library.root.listFiles().orEmpty()
                .any { PackImporter.readIndex(it)?.source == tree.toString() }
            (uri ?: tree?.takeUnless { keepGrant })?.let { picked ->
                runCatching {
                    applicationContext.contentResolver.releasePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f, "Starting")

    /** Songs already unpacked in [tree]: listed, then copied pack by pack. */
    private suspend fun importFolder(tree: Uri): Result {
        report(0, 0, "Reading the folder")
        val listing = TreePackSource.list(applicationContext, tree, isActive = { !isStopped })
        name = listing.name
        // Read in place: nothing but the charts is copied, and the grant on
        // the folder is kept for as long as a pack is read from it.
        val result = library.importer().importFolder(
            files = listing.files,
            folderName = listing.name,
            onProgress = { done, total -> report(done, total, "$done of $total songs", throttle = true) },
            isActive = { !isStopped },
            source = tree.toString(),
        )
        library.refresh()
        val packs = result.packs.joinToString(", ") { it.name }
        val skipped = if (result.skippedSongs > 0) " ${result.skippedSongs} without a dance or pop'n chart were left out." else ""
        notifyFinished("Songs imported", "$packs: ${result.songs} songs.$skipped")
        return Result.success(workDataOf(KEY_NAME to packs, KEY_SONGS to result.songs, KEY_SKIPPED to result.skippedSongs))
    }

    private class Opened(val stream: InputStream, val length: Long, val name: String)

    /** Open an https download, following its redirects, and read its name from the response. */
    private fun download(url: String): Opened {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            if (current.protocol != "https") throw IOException("Only secure (https) downloads are imported.")
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: throw IOException("The download went nowhere.")
                connection.disconnect()
                current = URL(current, next)
                return@repeat
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IOException("The download failed (HTTP $code).")
            }
            val fileName = fileNameOf(connection.getHeaderField("Content-Disposition"))
                ?: current.path.substringAfterLast('/').takeIf { it.endsWith(".zip", ignoreCase = true) }
            return Opened(
                stream = connection.inputStream,
                length = connection.contentLengthLong,
                name = fileName?.removeSuffix(".zip")?.removeSuffix(".ZIP") ?: name,
            )
        }
        throw IOException("The download redirected too many times.")
    }

    private fun open(uri: Uri): Opened {
        val resolver = applicationContext.contentResolver
        var length = -1L
        var display: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    if (!cursor.isNull(0)) length = cursor.getLong(0)
                    if (!cursor.isNull(1)) display = cursor.getString(1)
                }
            }
        }
        val stream = resolver.openInputStream(uri) ?: throw IOException("That file could not be opened.")
        return Opened(stream, length, display?.substringBeforeLast('.') ?: name)
    }

    /** Progress a few times a second at most; the bytes come far faster. */
    private fun report(read: Long, total: Long, label: String? = null, throttle: Boolean = label == null) {
        val now = SystemClock.elapsedRealtime()
        if (throttle && now - lastShownAt < UPDATE_MS) return
        lastShownAt = now
        val fraction = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
        val stage = label ?: if (total > 0) "${formatMb(read)} of ${formatMb(total)}" else "${formatMb(read)} read"
        setProgressAsync(workDataOf(KEY_NAME to name, KEY_FRACTION to fraction, KEY_STAGE to stage))
        runCatching { setForegroundAsync(foregroundInfo(fraction, stage)) }
    }

    private fun foregroundInfo(fraction: Float, stage: String): ForegroundInfo {
        createChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (name.isNotBlank()) "Importing $name" else "Importing a pack")
            .setContentText(stage)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, (fraction * 100).toInt(), fraction <= 0f)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyFinished(heading: String, text: String) {
        val context = applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(DONE_NOTIFICATION_ID + (id.hashCode() and 0xff), notification)
    }

    private fun openApp(): PendingIntent? {
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        return PendingIntent.getActivity(applicationContext, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val WORK_NAME = "trypt_mania_pack_import"
        const val KEY_NAME = "name"
        const val KEY_FRACTION = "fraction"
        const val KEY_STAGE = "stage"
        const val KEY_SONGS = "songs"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILURE = "failure"
        private const val KEY_URL = "url"
        private const val KEY_URI = "uri"
        private const val KEY_TREE = "tree"

        private const val CHANNEL_ID = "trypt_mania_packs"
        private const val NOTIFICATION_ID = 8210
        private const val DONE_NOTIFICATION_ID = 8300
        private const val UPDATE_MS = 500L
        private const val MAX_REDIRECTS = 6
        private const val USER_AGENT = "Tryptify (Trypt Mania pack import)"

        /** Queue a pack download from [url], an https link to a pack ZIP. */
        fun enqueueDownload(context: Context, url: String, name: String) =
            enqueue(context, workDataOf(KEY_URL to url, KEY_NAME to name))

        /** Queue a pack ZIP the player picked. */
        fun enqueueFile(context: Context, uri: Uri) {
            // The picker's grant may not outlive the process; the job may.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            enqueue(context, workDataOf(KEY_URI to uri.toString()))
        }

        /** Queue songs already unpacked in a folder the player picked: one song, a pack, or StepMania's Songs. */
        fun enqueueFolder(context: Context, tree: Uri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            enqueue(context, workDataOf(KEY_TREE to tree.toString()))
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun enqueue(context: Context, data: androidx.work.Data) {
            val work = OneTimeWorkRequestBuilder<PackImportWorker>().setInputData(data).build()
            // Appended, so packs import one at a time in the order they were
            // asked for; a failed one does not take the rest of the queue down.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        /** `attachment; filename="Pack.zip"` (or RFC 5987's `filename*=UTF-8''Pack.zip`) to `Pack.zip`. */
        internal fun fileNameOf(disposition: String?): String? {
            if (disposition.isNullOrBlank()) return null
            Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") }.getOrNull()
            }
            return Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)?.trim()
        }

        private fun formatMb(bytes: Long): String = "%.0f MB".format(bytes / (1024.0 * 1024.0))

        private fun createChannel(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trypt Mania packs", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "StepMania packs being downloaded and imported"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
    }
}
