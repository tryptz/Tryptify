package tf.monochrome.android.data.local.watcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.scanner.ScanCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the local library in step with the files, with nobody pressing
 * Rescan (#136: songs added, deleted or moved stayed as they were).
 *
 * It listens to Android's audio index, not the folders: MediaStore is what the
 * library reads ([tf.monochrome.android.data.local.scanner.MediaStoreSource]),
 * and it says when it changes. The FileObserver this replaces was never
 * started; it also watched one folder level where libraries nest Artist/Album,
 * and it fired when a file appeared, before Android had indexed it, so the
 * scan it started could not see the file yet.
 *
 * On a change, and once at launch for whatever changed while the app was
 * closed, it runs ScanCoordinator.runBackgroundRefresh: the incremental scan,
 * quiet. Changes come in bursts (a folder copied is one notification a file),
 * so it waits for them to settle first. Nothing runs without permission to
 * read audio, or before the library has been scanned once.
 */
@Singleton
class LibraryWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanCoordinator: ScanCoordinator,
    private val localMediaDao: LocalMediaDao,
) {
    private val started = AtomicBoolean(false)

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            // The app scope has no handler, so anything escaping here would
            // take the app down with it, not just the watching.
            try {
                audioIndexChanges()
                    // What changed while the app was closed: the observer only
                    // hears what happens while the process lives.
                    .onStart { emit(Unit) }
                    .debounce(SETTLE_MS)
                    .collect { refreshIfReady() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "stopped watching the audio index; Rescan still works", e)
            }
        }
    }

    private fun audioIndexChanges(): Flow<Unit> = callbackFlow {
        // Null handler: onChange arrives on a binder thread, and trySend is
        // safe from any thread.
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(AUDIO_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    private suspend fun refreshIfReady() {
        try {
            if (!canReadAudio()) return
            // No library yet: onboarding's scan, or the first folder added,
            // builds it. Streaming-only listeners never get a scan from here.
            if (localMediaDao.getScanState() == null) return
            scanCoordinator.runBackgroundRefresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "library refresh failed; the next change tries again", e)
        }
    }

    private fun canReadAudio(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "LibraryWatcher"

        /** Quiet this long before a refresh: a copy of many files is many changes. */
        const val SETTLE_MS = 3_000L

        val AUDIO_URI = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }
}
