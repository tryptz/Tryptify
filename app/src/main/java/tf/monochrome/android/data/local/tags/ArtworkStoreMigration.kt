package tf.monochrome.android.data.local.tags

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.preferences.PreferencesManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves cover art out of `cacheDir` and into the durable store, once.
 *
 * A move and a key rewrite, not a rescan. Re-extracting would be the tidier end
 * state, but it is a full library scan — the exact thing this change exists to
 * stop at launch — behind a wall of placeholders. `renameTo` within a partition
 * is metadata only, so this costs one listing and three UPDATEs at any size.
 *
 * The trade: carried-over files keep their one-copy-per-track naming and
 * original size. They land in [ArtworkStore.legacyRoot] so
 * `MediaScanner.needsReRead` recognises them and the next *manual* rescan
 * replaces them a row at a time, with nothing forced on the user meanwhile.
 */
@Singleton
class ArtworkStoreMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artworkStore: ArtworkStore,
    private val localMediaDao: LocalMediaDao,
    private val preferences: PreferencesManager,
) {

    suspend fun migrateIfNeeded() {
        if (preferences.artworkStoreMigrated.first()) return

        val oldDir = File(context.cacheDir, ArtworkKeys.DIR_NAME)
        val destination = artworkStore.legacyRoot

        // Fresh install, or a cache already reaped. Mark done either way.
        val files = oldDir.listFiles()
        if (files == null || files.isEmpty()) {
            oldDir.delete()
            preferences.setArtworkStoreMigrated(true)
            return
        }

        destination.mkdirs()
        var moved = 0
        for (file in files) {
            if (!file.isFile) continue
            val target = File(destination, file.name)
            // Taken means an interrupted run already got this one across.
            // The old copy is redundant, not newer.
            if (target.exists()) {
                if (file.delete()) moved++
                continue
            }
            if (file.renameTo(target)) moved++
        }

        // After the files have landed. The other order points every row at a
        // path that does not exist yet, and a crash in between leaves the whole
        // library on placeholders with no scan able to explain why.
        val oldPrefix = oldDir.absolutePath.trimEnd('/') + "/"
        val newPrefix = destination.absolutePath.trimEnd('/') + "/"
        val tracks = localMediaDao.repointTrackArtwork(oldPrefix, newPrefix)
        localMediaDao.repointAlbumArtwork(oldPrefix, newPrefix)
        localMediaDao.repointArtistArtwork(oldPrefix, newPrefix)

        oldDir.delete()
        preferences.setArtworkStoreMigrated(true)
        Log.i(TAG, "Moved $moved cover file(s) out of cacheDir; repointed $tracks track row(s)")
    }

    private companion object {
        const val TAG = "ArtworkStoreMigration"
    }
}
