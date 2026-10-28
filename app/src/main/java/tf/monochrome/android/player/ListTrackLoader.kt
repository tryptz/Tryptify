package tf.monochrome.android.player

import android.util.Log
import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.api.QobuzIdRegistry
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
    /** [list]'s songs, at most [MAX_TRACKS]; empty when it has none or cannot be reached. */
    suspend fun load(list: ListRef): List<Track> = try {
        when (list) {
            is ListRef.Playlist -> playlist(list.id)
            is ListRef.Album -> album(list.id)
            is ListRef.LocalAlbum -> legacy(localMediaRepository.getTracksByAlbum(list.id).first())
            is ListRef.Folder -> legacy(folderTracks(list))
            is ListRef.SubsonicPlaylist -> legacy(subsonicRepository.playlistTracks(list.id))
            is ListRef.SubsonicAlbum -> legacy(subsonicRepository.albumTracks(list.id))
        }.take(MAX_TRACKS)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Offline, a server gone, a playlist deleted since: skipped, not fatal.
        Log.w(TAG, "Could not load the next list", e)
        emptyList()
    }

    /** The folder tree as the folder browser shows it, for [FolderWalk]. */
    suspend fun folders(): FolderView {
        val rows = localMediaRepository.getAllFolders().first()
        val sort = FolderSort.decode(preferences.folderSort.first())
        val userRoots = preferences.userFolderRoots.first()
        val count = rows.associate { it.path to it.trackCount }
        val childrenOf = rows.groupBy({ it.parentPath.orEmpty() }, { it.path })

        suspend fun sorted(paths: List<String>): List<String> {
            val newest = if (sort.folders == SubfolderOrder.DATE_MODIFIED) {
                paths.associateWith { localMediaRepository.newestUnder(it) }
            } else {
                emptyMap()
            }
            return sortFolders(
                paths, sort.folders, sort.foldersAscending,
                name = { it.substringAfterLast('/') },
                songCount = { count[it] ?: 0 },
                newest = { newest[it] },
            )
        }

        // The Folders list: hand-added roots first, then what the scan found,
        // as LocalLibraryViewModel.displayRootFolders builds it.
        val roots = sorted(userRoots.toList() + folderBrowseRoots(rows).map { it.path }.filter { it !in userRoots })
        return object : FolderView {
            override suspend fun roots() = roots
            override suspend fun children(path: String) = sorted(childrenOf[path].orEmpty())
            override suspend fun hasOwnSongs(path: String): Boolean {
                // trackCount is the whole branch: what the subfolders do not
                // account for sits in this folder itself.
                val below = childrenOf[path].orEmpty().sumOf { count[it] ?: 0 }
                return (count[path] ?: 0) - below > 0
            }
        }
    }

    private suspend fun playlist(id: String): List<Track> {
        val isLocal = libraryRepository.getAllPlaylists().first().any { it.id == id }
        return if (isLocal) {
            libraryRepository.getPlaylistTracks(id).first()
        } else {
            musicRepository.getPlaylist(id).getOrNull()?.tracks.orEmpty()
        }
    }

    /** From the catalogue the album belongs to, and only there, as its own screen does. */
    private suspend fun album(id: Long): List<Track> {
        val slug = qobuzIdRegistry.albumSlugFor(id)
        val result = when {
            qobuzIdRegistry.isDeezerAlbum(id) -> musicRepository.getDeezerAlbum(id)
            qobuzIdRegistry.isAppleAlbum(id) -> musicRepository.getAppleAlbum(id)
            slug != null -> musicRepository.getQobuzAlbum(slug)
            else -> musicRepository.getAlbum(id)
        }
        return result.getOrNull()?.tracks.orEmpty()
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
