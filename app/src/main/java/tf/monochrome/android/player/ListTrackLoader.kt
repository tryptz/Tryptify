package tf.monochrome.android.player

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.data.local.scanner.folderBrowseRoots
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.data.subsonic.SubsonicRepository
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.library.FolderSort
import tf.monochrome.android.ui.library.SubfolderOrder
import tf.monochrome.android.ui.library.folderPlayOrder
import tf.monochrome.android.ui.library.sortFolderTracks
import tf.monochrome.android.ui.library.sortFolders
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches one list's songs by its [ListRef], in the order its own screen
 * plays them, for [ListContinuation]. Nothing is cached: a list is loaded when
 * playback reaches it and the queue is all that holds it after.
 *
 * Off the main thread, whoever calls: a list is up to [MAX_TRACKS] songs to
 * group, sort and map, and [ListContinuation] runs on the main thread.
 */
@Singleton
class ListTrackLoader @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val musicRepository: MusicRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val subsonicRepository: SubsonicRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val preferences: PreferencesManager,
) {
    /**
     * [list]'s songs, at most [MAX_TRACKS]: empty when it has none, null when
     * it could not be reached. The two are kept apart because [ListSearch]
     * passes over an empty list but tries one that failed again later.
     */
    suspend fun load(list: ListRef): List<Track>? = withContext(Dispatchers.Default) {
        try {
            when (list) {
                is ListRef.Playlist -> playlist(list.id)
                is ListRef.Album -> album(list.id)
                is ListRef.LocalAlbum -> legacy(localMediaRepository.getTracksByAlbum(list.id).first())
                is ListRef.Folder -> legacy(folderTracks(list))
                is ListRef.SubsonicPlaylist -> legacy(subsonicRepository.playlistTracks(list.id))
                is ListRef.SubsonicAlbum -> legacy(subsonicRepository.albumTracks(list.id))
            }.take(MAX_TRACKS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, a server down, a playlist deleted since.
            Log.w(TAG, "Could not load the next list", e)
            null
        }
    }

    /** The folder tree as the folder browser shows it, for [FolderWalk]; null when it could not be read. */
    suspend fun folders(): FolderView? = withContext(Dispatchers.Default) {
        try {
            folderView(
                rows = localMediaRepository.getAllFolders().first(),
                sort = FolderSort.decode(preferences.folderSort.first()),
                userRoots = preferences.userFolderRoots.first(),
                newestUnder = localMediaRepository::newestUnder,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the folders", e)
            null
        }
    }

    private suspend fun playlist(id: String): List<Track> {
        val isLocal = libraryRepository.getAllPlaylists().first().any { it.id == id }
        return if (isLocal) {
            libraryRepository.getPlaylistTracks(id).first()
        } else {
            musicRepository.getPlaylist(id).getOrThrow().tracks
        }
    }

    /** From the catalogue the album belongs to, and only there, as its own screen does. */
    private suspend fun album(id: Long): List<Track> {
        // The registry's sets are empty until it has read them back from
        // disk, and a list played on right after a restart would send a
        // Deezer, Apple or Qobuz id to TIDAL: some other album, or an error.
        qobuzIdRegistry.awaitLoaded()
        val slug = qobuzIdRegistry.albumSlugFor(id)
        val result = when {
            qobuzIdRegistry.isDeezerAlbum(id) -> musicRepository.getDeezerAlbum(id)
            qobuzIdRegistry.isAppleAlbum(id) -> musicRepository.getAppleAlbum(id)
            slug != null -> musicRepository.getQobuzAlbum(slug)
            else -> musicRepository.getAlbum(id)
        }
        return result.getOrThrow().tracks
    }

    private suspend fun folderTracks(folder: ListRef.Folder): List<UnifiedTrack> {
        val sort = FolderSort.decode(preferences.folderSort.first())
        return if (folder.withSubfolders) {
            folderPlayOrder(folder.path, localMediaRepository.getTracksUnder(folder.path), sort)
        } else {
            sortFolderTracks(localMediaRepository.getTracksInFolder(folder.path).first(), sort.tracks, sort.tracksAscending)
        }
    }

    /** As PlayerViewModel.playUnifiedTrack: the registry is how each one finds its source. */
    private fun legacy(tracks: List<UnifiedTrack>): List<Track> = tracks.take(MAX_TRACKS).map { unified ->
        unified.toLegacyTrack().also { unifiedTrackRegistry.put(it.id, unified) }
    }

    private companion object {
        const val TAG = "ListTrackLoader"

        /** The most of one list a queue takes, as the music server's lists already do. */
        const val MAX_TRACKS = 1_000
    }
}

/**
 * The folder tree from the scanner's folder [rows], in the folder [sort]. Its
 * Folders list is the listener's hand-added [userRoots] and what the scan
 * found, as `LocalLibraryViewModel.displayRootFolders` builds it.
 *
 * One view serves one walk, and a walk asks about the same folders again and
 * again: the folders beside each one it climbs out of, every branch it looks
 * down. So each folder's subfolders are sorted once for the life of the view,
 * and under the date sort each folder's newest file is asked for once
 * ([newestUnder], a database query). It used to be one query per subfolder on
 * every look. A walk asks one thing at a time, so plain maps do.
 */
internal suspend fun folderView(
    rows: List<LocalFolderEntity>,
    sort: FolderSort,
    userRoots: Set<String>,
    newestUnder: suspend (String) -> Long?,
): FolderView {
    val count = rows.associate { it.path to it.trackCount }
    val childrenOf = rows.groupBy({ it.parentPath.orEmpty() }, { it.path })
    val newest = HashMap<String, Long?>()
    val sortedChildren = HashMap<String, List<String>>()

    suspend fun sorted(paths: List<String>): List<String> {
        // One folder or none needs no order, and no query.
        if (sort.folders == SubfolderOrder.DATE_MODIFIED && paths.size > 1) {
            for (path in paths) if (path !in newest) newest[path] = newestUnder(path)
        }
        return sortFolders(
            paths, sort.folders, sort.foldersAscending,
            name = { it.substringAfterLast('/') },
            songCount = { count[it] ?: 0 },
            newest = { newest[it] },
        )
    }

    val roots = sorted(userRoots.toList() + folderBrowseRoots(rows).map { it.path }.filter { it !in userRoots })
    return object : FolderView {
        override suspend fun roots() = roots
        override suspend fun children(path: String) =
            sortedChildren[path] ?: sorted(childrenOf[path].orEmpty()).also { sortedChildren[path] = it }
        override suspend fun hasOwnSongs(path: String): Boolean {
            // trackCount is the whole branch: what the subfolders do not
            // account for sits in this folder itself.
            val below = childrenOf[path].orEmpty().sumOf { count[it] ?: 0 }
            return (count[path] ?: 0) - below > 0
        }
    }
}
