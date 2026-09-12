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

        val files = oldDir.listFiles().orEmpty()
        var moved = 0
        if (files.isNotEmpty()) {
            destination.mkdirs()
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
        }

        // After the files have landed. The other order points every row at a
        // path that does not exist yet, and a crash in between leaves the whole
        // library on placeholders with no scan able to explain why.
        //
        // Runs even when the old directory is empty, and that is the point. An
        // empty directory used to return early as "nothing to migrate" — but it
        // is also exactly what a run that moved every file and then died before
        // repointing leaves behind. The next launch took that branch, marked
        // the migration done, and left every row pointing at a file that had
        // moved: covers gone for good, short of a manual rescan. A prefix
        // rewrite that matches nothing is three no-op UPDATEs, which is a small
        // price for the case where it matches everything.
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
