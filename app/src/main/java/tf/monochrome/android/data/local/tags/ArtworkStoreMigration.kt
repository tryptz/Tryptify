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
 * Deliberately a move and a key rewrite, not a rescan. Re-extracting the art
 * would be the tidier end state — every cover deduplicated and downscaled in one
 * pass — but it is also a full library scan, which is the exact thing this whole
 * change exists to stop happening at launch, and it would show a wall of
 * placeholders until it finished. `renameTo` within the same partition is a
 * metadata operation, so this costs one directory listing and three UPDATEs
 * however large the library is.
 *
 * What that trades away: the files carried over keep their old
 * one-copy-per-track naming and their original size. They land in
 * [ArtworkStore.legacyRoot] rather than the store root, which is what lets
 * `MediaScanner.needsReRead` recognise them, so the next *manual* rescan
 * replaces them with deduplicated, downscaled copies a row at a time. Nothing is
 * forced on the user in the meantime and nothing renders a placeholder.
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

        // Nothing to carry over — a fresh install, or a device whose cache was
        // already reaped. Mark it done either way so this never runs again.
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
            // A name already taken means a previous interrupted run got this
            // one across. The old copy is redundant, not newer.
            if (target.exists()) {
                if (file.delete()) moved++
                continue
            }
            if (file.renameTo(target)) moved++
        }

        // Key rewrite comes after the files have actually landed. The other
        // order would point every row at a path that does not exist yet, and a
        // crash in between would leave the whole library showing placeholders
        // with no scan able to explain why.
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
