package tf.monochrome.android.data.local.scanner

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import tf.monochrome.android.data.local.db.LocalAlbumEntity
import tf.monochrome.android.data.local.db.LocalArtistEntity
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.data.local.db.LocalGenreEntity
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.db.ScanStateEntity
import tf.monochrome.android.data.local.db.TrackPath
import tf.monochrome.android.data.local.db.TrackScanInfo
import tf.monochrome.android.data.local.tags.MusicalTags
import tf.monochrome.android.data.local.tags.TagReader
import tf.monochrome.android.data.preferences.PreferencesManager
import java.io.File
import java.text.Normalizer
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

sealed class ScanProgress {
    data class Started(val totalFiles: Int) : ScanProgress()
    data class Processing(val current: Int, val total: Int, val currentFile: String = "") : ScanProgress()
    data class Grouping(val message: String = "Building library...") : ScanProgress()
    data class Complete(val scanned: Int, val added: Int, val removed: Int) : ScanProgress()
    data class Error(val message: String) : ScanProgress()
}

@Singleton
class MediaScanner @Inject constructor(
    private val mediaStoreSource: MediaStoreSource,
    private val tagReader: TagReader,
    private val localMediaDao: LocalMediaDao,
    private val musicDatabase: tf.monochrome.android.data.db.MusicDatabase,
    private val preferences: PreferencesManager,
    private val localLibraryRevision: tf.monochrome.android.data.local.LocalLibraryRevision,
    private val genreGraph: tf.monochrome.android.data.repository.GenreGraphRepository,
    private val artworkStore: tf.monochrome.android.data.local.tags.ArtworkStore,
    private val folderIndexer: FolderIndexer,
    private val packs: tf.monochrome.android.glyph.pack.GlyphPackLibrary,
    private val ownPack: tf.monochrome.android.glyph.pack.OwnPackStore,
) {
    /**
     * Linked StepMania packs' songs by their music's path ([GlyphPackLibrary.libraryTags]):
     * read with the chart's title, artist and picture and the pack as the
     * album. Empty when the packs cannot be read; the songs then keep their
     * files' tags.
     */
    private suspend fun packTags(): Map<String, tf.monochrome.android.glyph.pack.GlyphPackLibrary.LibraryTags> =
        try {
            packs.libraryTags()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("MediaScanner", "could not read the StepMania packs", e)
            emptyMap()
        }

    /**
     * Trypt Mania's own pack folder (`<chosen folder>/Tryptify`), left out of
     * the library: every chart made in the app is written there with a copy
     * of its music, which the library already has where it came from, so the
     * copy was every such song a second time.
     */
    private fun ownPackPaths(): Set<String> {
        val tree = ownPack.folder.value ?: return emptySet()
        val root = tf.monochrome.android.util.safTreeUriToPath(tree) ?: return emptySet()
        return setOf("$root/${tf.monochrome.android.glyph.pack.StepManiaPack.OWN_PACK}")
    }


    fun fullScan(
        excludedPaths: Set<String> = emptySet()
    ): Flow<ScanProgress> = flow {
        try {
            // User-chosen library roots restrict every MediaStore query in
            // this scan (discovery AND pruning, so tracks outside the roots
            // are removed consistently). Empty set = whole-device scan —
            // the behavior for users who never picked folders in onboarding.
            val folderRoots = preferences.userFolderRoots.first()
            // Read the stored exclusions here rather than trusting the caller.
            // They were a parameter only, and the one real caller —
            // ScanCoordinator.runFullScan — has nothing to pass, so the setting
            // was written by the UI and then ignored by every scan.
            val excluded = excludedPaths + preferences.excludedPaths.first() + ownPackPaths()
            // A scan sees only what Android has indexed, and not every way of
            // putting a file in a folder tells it: a new song stayed missing
            // through any number of rescans (#136). Ask for the ones it has
            // not indexed, and wait, before reading. A failure here costs only
            // those files, never the scan.
            val unindexed = try {
                folderIndexer.findUnindexed(folderRoots, excluded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("MediaScanner", "could not check the folders for unindexed files", e)
                emptyList()
            }
            if (unindexed.isNotEmpty()) {
                emit(ScanProgress.Grouping("Indexing ${unindexed.size} new file${if (unindexed.size == 1) "" else "s"}..."))
                folderIndexer.index(unindexed)
            }
            val mediaStoreFiles =
                mediaStoreSource.queryAllAudio(excluded, folderRoots)
            emit(ScanProgress.Started(totalFiles = mediaStoreFiles.size))

            // One projection query up front instead of a findByPath() SELECT
            // per file — the re-read decision then runs entirely in memory.
            val scanInfoByPath = localMediaDao.getAllTrackScanInfo().associateBy { it.filePath }

            // Titles are written here, at scan time, so switching between tag
            // and file-name titles has to re-read every file once. Nothing
            // about the files changed, so the usual heuristics would skip them.
            val titleFromFileName = preferences.localTitleFromFileName.first()
            val titleModeChanged = titleFromFileName != preferences.localTitleModeScanned.first()
            // Rows an older scan gave a number for an artist are healed by one
            // full scan, not by every one: see needsReRead.
            val healNumbering = preferences.localNumberingHealScanned.first() < NUMBERING_HEAL_VERSION
            val packTags = packTags()

            val addedCount = processFiles(
                files = mediaStoreFiles,
                shouldRead = {
                    val existing = scanInfoByPath[it.absolutePath]
                    titleModeChanged ||
                        needsPackRetag(existing, packTags[it.absolutePath]) ||
                        needsReRead(
                            existing, it.dateModified,
                            titleFromFileName = titleFromFileName,
                            healNumbering = healNumbering,
                        )
                },
                progressChunkSize = 50,
                titleFromFileName = titleFromFileName,
                packTags = packTags,
            )

            // Prune deleted files
            emit(ScanProgress.Grouping("Removing deleted tracks..."))
            val removedCount = pruneDeleted(mediaStoreFiles.mapTo(HashSet()) { it.absolutePath })

            // Rebuild groupings
            emit(ScanProgress.Grouping("Building album & artist library..."))
            rebuildGroupings()

            // Build folder structure
            emit(ScanProgress.Grouping("Building folder structure..."))
            rebuildFolders()

            // Update scan state
            updateScanState(full = true)
            preferences.setLocalTitleModeScanned(titleFromFileName)
            if (healNumbering) preferences.setLocalNumberingHealScanned(NUMBERING_HEAL_VERSION)

            // App data now, not a cache the OS bounds for us, and a scan is
            // the one moment we know which covers are still spoken for. Best
            // effort: a failed sweep is wasted disk, never a failed scan.
            runCatching {
                artworkStore.sweepOrphans(localMediaDao.getAllReferencedArtworkKeys())
            }

            emit(ScanProgress.Complete(
                scanned = mediaStoreFiles.size,
                added = addedCount,
                removed = maxOf(0, removedCount)
            ))
        } catch (e: Exception) {
            emit(ScanProgress.Error(e.message ?: "Unknown scan error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Brings the library up to date with MediaStore at a fraction of a full
     * scan's cost: what LibraryWatcher runs when Android's audio index
     * changes, and once at launch.
     *
     * A file is read when the library has nothing at its path, or when it was
     * modified since it was read ([needsIncrementalRead]). It used to take only
     * files modified since the last scan, by date, and copying or moving a file
     * keeps its date: a song moved between folders was pruned from the old one
     * and never read in the new (#136).
     *
     * Cheaper than a full scan where it can be. None of the artwork re-read
     * heuristics. Atmos music videos are listed by path, not opened: the
     * library keeps the ones it has while their files exist, and a new one
     * arrives with the next full scan. The prune is careful ([pruneLooksReal]),
     * since nobody asked for this scan and a MediaStore that answers empty,
     * before storage is mounted, would otherwise empty the library. And when
     * nothing changed, nothing is rebuilt or announced.
     */
    fun incrementalScan(): Flow<ScanProgress> = flow {
        try {
            val folderRoots = preferences.userFolderRoots.first()
            // Exclusions apply here too. They used to be read only by fullScan,
            // so adding or touching a file under an excluded folder let the
            // incremental scan import it straight back — the removal held only
            // until the next song landed in that folder.
            val excluded = preferences.excludedPaths.first() + ownPackPaths()
            val audio = mediaStoreSource.queryAllAudio(
                excluded, folderRoots, includeAtmosVideos = false,
            )
            val known = localMediaDao.getAllTrackScanInfo().associateBy { it.filePath }
            val packTags = packTags()
            val changed = audio.filter {
                val existing = known[it.absolutePath]
                needsIncrementalRead(existing, it.dateModified) ||
                    needsPackRetag(existing, packTags[it.absolutePath])
            }

            val addedCount = if (changed.isEmpty()) 0 else {
                emit(ScanProgress.Started(totalFiles = changed.size))
                processFiles(
                    files = changed,
                    shouldRead = { true },
                    progressChunkSize = 20,
                    titleFromFileName = preferences.localTitleFromFileName.first(),
                    packTags = packTags,
                )
            }

            // Same roots filter as fullScan so the prune diff never deletes
            // tracks a full scan would keep; videos by path, for the reason above.
            val present = audio.mapTo(HashSet()) { it.absolutePath }
            present += mediaStoreSource.queryAtmosVideoCandidatePaths(excluded)
            val removedCount = pruneDeleted(present, cautious = true)

            if (changed.isEmpty() && removedCount == 0) {
                emit(ScanProgress.Complete(scanned = 0, added = 0, removed = 0))
                return@flow
            }

            rebuildGroupings()
            rebuildFolders()
            updateScanState(full = false)

            emit(ScanProgress.Complete(scanned = changed.size, added = addedCount, removed = removedCount))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ScanProgress.Error(e.message ?: "Incremental scan error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Read tags for [files] with bounded parallelism and batch the resulting
     * rows into the database, emitting [ScanProgress.Processing] once per
     * [progressChunkSize] files (matching the old per-file loop's cadence).
     *
     * Tag extraction dominates scan time — each file costs a native
     * MediaMetadataRetriever.setDataSource + metadata + artwork read — and
     * the files are independent, so fan the reads out across cores. DB
     * writes stay on the collector coroutine: one insertTracks() per
     * [INSERT_BATCH_SIZE] rows instead of one transaction per file.
     *
     * Written as a FlowCollector extension so it can emit() from inside the
     * callers' flow {} builders; emissions happen only between chunks, on
     * the collector's own coroutine, because flow {} emit is not
     * thread-safe from concurrent children.
     *
     * @return the number of tracks actually (re-)read and written.
     */
    private suspend fun FlowCollector<ScanProgress>.processFiles(
        files: List<AudioFileInfo>,
        shouldRead: (AudioFileInfo) -> Boolean,
        progressChunkSize: Int,
        titleFromFileName: Boolean,
        packTags: Map<String, tf.monochrome.android.glyph.pack.GlyphPackLibrary.LibraryTags> = emptyMap(),
    ): Int {
        val parallelism = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val tagDispatcher = Dispatchers.IO.limitedParallelism(parallelism)
        // Memoize sidecar cover art lookups by parent directory so we don't
        // listFiles() once per track. A 500-track album → 1 directory scan.
        // synchronizedMap (not ConcurrentHashMap) because "no art in this
        // folder" is cached as a null value; a lost race just re-lists the
        // same folder once with an identical result.
        val folderArtCache = Collections.synchronizedMap(HashMap<String, String?>())

        val pending = ArrayList<LocalTrackEntity>(INSERT_BATCH_SIZE)
        var added = 0
        var processed = 0

        for (chunk in files.chunked(progressChunkSize)) {
            val entities = coroutineScope {
                chunk.map { audioFile ->
                    async(tagDispatcher) {
                        try {
                            if (!shouldRead(audioFile)) return@async null
                            val tags = tagReader.readTags(audioFile.absolutePath, folderArtCache)
                            buildTrackEntity(audioFile, tags, titleFromFileName)
                                .withPackTags(packTags[audioFile.absolutePath])
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Skip individual file errors
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            pending += entities
            added += entities.size
            if (pending.size >= INSERT_BATCH_SIZE) {
                insertKeepingDynamicRange(pending.toList())
                pending.clear()
            }

            processed += chunk.size
            emit(ScanProgress.Processing(
                current = processed,
                total = files.size,
                currentFile = chunk.last().displayName
            ))
        }
        if (pending.isNotEmpty()) {
            insertKeepingDynamicRange(pending)
        }
        return added
    }

    /**
     * Writes [tracks] (a re-read replaces its row whole), each keeping the
     * dynamic range its row holds now while its file is the same one
     * ([keptDynamicRange]): the player measures it, so a read cannot give it
     * back. The rows are read and written in one transaction, so a value the
     * player saves while the scan runs is kept too, where a copy read when the
     * scan began would have put back an empty one.
     */
    private suspend fun insertKeepingDynamicRange(tracks: List<LocalTrackEntity>) {
        musicDatabase.withTransaction {
            val now = localMediaDao.scanInfoFor(tracks.map { it.filePath }).associateBy { it.filePath }
            localMediaDao.insertTracks(
                tracks.map { t -> keptDynamicRange(now[t.filePath], t.lastModified)?.let { t.copy(dynamicRange = it) } ?: t },
            )
        }
    }

    /**
     * Delete DB rows whose files no longer exist in MediaStore. The diff is
     * computed in memory and deleted with positive IN-lists chunked under
     * SQLite's 999 bound-variable limit (the old NOT IN variant bound the
     * entire MediaStore path set into a single statement).
     *
     * [cautious], for a scan nobody asked for: skipped when it does not look
     * like files really went ([pruneLooksReal]).
     *
     * @return the number of rows removed.
     */
    private suspend fun pruneDeleted(mediaStorePaths: Set<String>, cautious: Boolean = false): Int {
        val library = localMediaDao.getAllTrackPaths()
        val toDelete = library.filterNot { it in mediaStorePaths }
        if (cautious && !pruneLooksReal(library.size, toDelete.size)) {
            // Left for a full scan, which someone asked for and which says so.
            android.util.Log.w(
                "MediaScanner",
                "not pruning ${toDelete.size} of ${library.size} tracks MediaStore did not list; a rescan will",
            )
            return 0
        }
        if (toDelete.isNotEmpty()) {
            musicDatabase.withTransaction {
                toDelete.chunked(DELETE_CHUNK_SIZE).forEach {
                    localMediaDao.deleteTracksByPaths(it)
                }
            }
        }
        return toDelete.size
    }

    private fun buildTrackEntity(
        audioFile: AudioFileInfo,
        tags: tf.monochrome.android.data.local.tags.AudioTags,
        titleFromFileName: Boolean,
    ): LocalTrackEntity {
        val title = if (titleFromFileName) titleFromPath(audioFile.absolutePath) else tags.title
        return LocalTrackEntity(
            filePath = audioFile.absolutePath,
            fileSizeBytes = audioFile.sizeBytes,
            lastModified = audioFile.dateModified,
            title = title,
            // Folded copy of the title that the on-device-copy lookup range
            // scans. Kept in lock-step with `title` here so it can never go
            // stale for a scanned row.
            titleSearchKey = title
                ?.let { tf.monochrome.android.player.LocalTrackMatching.searchKey(it) },
            artist = tags.artist,
            albumArtist = tags.albumArtist,
            album = tags.album,
            genre = tags.genre,
            year = tags.year,
            trackNumber = tags.trackNumber,
            trackTotal = tags.trackTotal,
            discNumber = tags.discNumber ?: 1,
            discTotal = tags.discTotal,
            composer = tags.composer,
            lyrics = tags.lyrics,
            codec = tags.codec.name,
            sampleRate = tags.sampleRate,
            bitDepth = tags.bitDepth,
            bitRate = tags.bitRate,
            channels = tags.channels,
            durationSeconds = tags.durationSeconds,
            rgTrackGain = tags.musical.rgTrackGain,
            rgAlbumGain = tags.musical.rgAlbumGain,
            r128TrackGain = tags.musical.r128TrackGain,
            r128AlbumGain = tags.musical.r128AlbumGain,
            rgTrackPeak = tags.musical.rgTrackPeak,
            rgAlbumPeak = tags.musical.rgAlbumPeak,
            bpm = tags.musical.bpm,
            camelotKey = tags.musical.camelotKey,
            hasEmbeddedArt = tags.hasEmbeddedArt,
            artworkCacheKey = tags.artworkCacheKey,
            isThxSpatialAudio = tags.isThxSpatialAudio,
            isDolbyAtmos = tags.isDolbyAtmos,
            lastScannedAt = System.currentTimeMillis()
        )
    }

    /**
     * A linked StepMania pack song as the library shows it: the chart's title
     * and artist over the file's tags, the pack as its album, and the song's
     * picture when it has one. A song of a pack that is no longer linked is
     * read as its file says, on the next scan that reads it.
     */
    private fun LocalTrackEntity.withPackTags(
        tags: tf.monochrome.android.glyph.pack.GlyphPackLibrary.LibraryTags?,
    ): LocalTrackEntity {
        if (tags == null) return this
        val art = tags.artPath?.takeIf { File(it).isFile }
        return copy(
            title = tags.title,
            titleSearchKey = tf.monochrome.android.player.LocalTrackMatching.searchKey(tags.title),
            artist = tags.artist,
            albumArtist = tags.albumArtist,
            album = tags.album,
            // One pack, one album: no disc or track numbers from the files.
            trackNumber = null,
            trackTotal = null,
            discNumber = 1,
            discTotal = null,
            artworkCacheKey = art ?: artworkCacheKey,
            hasEmbeddedArt = art != null || hasEmbeddedArt,
        )
    }

    private suspend fun rebuildGroupings() =
        regroup(musicDatabase, localMediaDao) { raw ->
            // Tags are free text, so "Hip-Hop", "Hip Hop" and "hip hop"
            // arrived as three separate rows in the genre list. Resolving
            // through the graph collapses them onto one canonical name;
            // anything the graph doesn't know keeps its tag verbatim, so
            // no genre is ever lost, only merged.
            genreGraph.graph.resolve(raw)?.name ?: raw
        }

    /**
     * Drop a folder from the library: its tracks go, and scans stop finding it.
     *
     * Nothing on disk is touched. The files stay where they are; this is the
     * library forgetting them.
     *
     * Deleting the rows is not enough on its own — the albums, artists and
     * genres they belonged to would linger, and the folder itself would keep
     * its row — so the two rebuild passes a scan runs are run here too. That is
     * much cheaper than a scan: no MediaStore query and no tag reading.
     */
    suspend fun excludeFolder(path: String) {
        val folder = path.trimEnd('/')
        // trimEnd matches what addUserFolderRoot stores, so re-adding the same
        // folder finds the exclusion it needs to clear.
        if (folder.isEmpty()) return
        preferences.addExcludedPath(folder)
        // A folder the user added by hand is also a scan root, and leaving it
        // there would have the next scan re-find what the exclusion just
        // removed — the two settings pulling against each other.
        preferences.removeUserFolderRoot(folder)
        localMediaDao.deleteTracksUnder(folder)
        rebuildGroupings()
        rebuildFolders()
        localLibraryRevision.bump()
    }

    /**
     * Reads the tempo, key and gain tags of every track after id [afterId] and
     * writes those columns alone, [BACKFILL_BATCH] tracks at a time, calling
     * [onBatch] with the last id done so a run cut short can resume there.
     *
     * Tags only: no retriever, no artwork, no audio, and no row rewrite, so
     * ids, groupings and a measured dynamic range stay as they are. Two
     * readers at a time, so it stays in the background.
     *
     * [onBatch] also gets the tracks whose file was not there (on a card not
     * mounted just then). The walk goes on past them and finishes; they are
     * read later by [retryMusicalTags].
     */
    suspend fun backfillMusicalTags(
        afterId: Long,
        onBatch: suspend (resumeAfter: Long, missing: Set<Long>) -> Unit,
    ) {
        var cursor = afterId
        var wrote = false
        while (true) {
            val batch = localMediaDao.trackPathsAfter(cursor, BACKFILL_BATCH)
            if (batch.isEmpty()) break
            val (batchWrote, missing) = readMusicalTagsOf(batch)
            wrote = wrote || batchWrote
            cursor = batch.last().id
            // Past a missing file too. Held there instead, the walk restarted
            // from it at every launch while an SD card was out, read every
            // track after it again, wrote the same values and rebuilt the
            // galaxy, and never finished. The missing ones are tried again on
            // their own ([retryMusicalTags]).
            onBatch(cursor, missing)
        }
        if (wrote) localLibraryRevision.bump()
    }

    /**
     * Reads the tags of tracks [ids], which the backfill found no file for:
     * those whose file is back are filled in. Returns the ones still missing;
     * a track no longer in the library is dropped.
     */
    suspend fun retryMusicalTags(ids: Set<Long>): Set<Long> {
        val stillMissing = HashSet<Long>()
        var wrote = false
        for (chunk in ids.chunked(BACKFILL_BATCH)) {
            val rows = localMediaDao.trackPathsOf(chunk)
            // Checked before reading: a card that is still out costs a stat
            // per file, not a parse.
            val (present, absent) = rows.partition { File(it.filePath).exists() }
            absent.mapTo(stillMissing) { it.id }
            if (present.isEmpty()) continue
            val (batchWrote, missing) = readMusicalTagsOf(present)
            wrote = wrote || batchWrote
            stillMissing += missing
        }
        if (wrote) localLibraryRevision.bump()
        return stillMissing
    }

    /**
     * Reads and writes the musical tags of [rows]. Returns whether anything
     * was written, and the ids whose file was not there.
     */
    private suspend fun readMusicalTagsOf(rows: List<TrackPath>): Pair<Boolean, Set<Long>> {
        val dispatcher = Dispatchers.IO.limitedParallelism(BACKFILL_PARALLELISM)
        val read = coroutineScope {
            rows.map { row -> async(dispatcher) { row to tagReader.readMusicalTags(row.filePath) } }
                .awaitAll()
        }
        val missing = read.filter { (row, tags) -> tags == null && !File(row.filePath).exists() }
            .mapTo(HashSet()) { it.first.id }
        // A file that could not be read, or says nothing, is left as it is.
        val found = read.mapNotNull { (row, tags) -> tags?.takeIf { it != MusicalTags() }?.let { row.filePath to it } }
        if (found.isNotEmpty()) {
            musicDatabase.withTransaction {
                for ((path, t) in found) {
                    localMediaDao.updateMusicalTags(
                        path, t.bpm, t.camelotKey, t.rgTrackGain, t.rgAlbumGain, t.r128TrackGain, t.r128AlbumGain,
                        t.rgTrackPeak, t.rgAlbumPeak,
                    )
                }
            }
        }
        return found.isNotEmpty() to missing
    }

    /** Whether the library has no tracks at all. */
    suspend fun isLibraryEmpty(): Boolean = localMediaDao.getTrackCount() == 0

    suspend fun rebuildFolders() {
        val folders = buildFolderTree(localMediaDao.getAllTrackPaths())

        // Clear + repopulate atomically so folder-tab observers never see an
        // empty list mid-scan, and the whole rebuild is one Room flush.
        musicDatabase.withTransaction {
            localMediaDao.clearFolders()
            if (folders.isNotEmpty()) {
                localMediaDao.upsertFolders(folders)
            }
        }
    }

    private suspend fun updateScanState(full: Boolean) {
        val trackCount = localMediaDao.getTrackCount()
        val existingState = localMediaDao.getScanState()
        val now = System.currentTimeMillis()
        localMediaDao.updateScanState(
            ScanStateEntity(
                id = 1,
                // Was `existingState?.lastFullScan ?: now`, which pinned this
                // to the install's first scan — recording when the library was
                // first indexed rather than when it was last rebuilt.
                lastFullScan = if (full) now else existingState?.lastFullScan ?: now,
                lastIncremental = now,
                totalTracks = trackCount,
                totalDuration = 0,
                totalSizeBytes = 0
            )
        )
        // Every scan ends here, so this is the one place that has to tell the
        // rest of the app "what's on disk just changed" — see LocalLibraryRevision.
        localLibraryRevision.bump()
    }

    companion object {
        /**
         * The numbering heal a full scan applies once (needsReRead). Raise it
         * when the numbering rule changes, so the next full scan heals again.
         */
        const val NUMBERING_HEAL_VERSION = 1

        /** Rows per insertTracks() call — one Room transaction per batch. */
        private const val INSERT_BATCH_SIZE = 500

        /** Paths per DELETE ... IN (...) — under SQLite's 999-variable limit. */
        private const val DELETE_CHUNK_SIZE = 500

        /** Tracks per backfill transaction, and per saved resume point. */
        private const val BACKFILL_BATCH = 200

        /** Files read at once by the tag backfill: background work, not a scan. */
        private const val BACKFILL_PARALLELISM = 2

        /**
         * Groups the library's tracks into albums, artists and genres, writes
         * them, and points every track at its album, artist and art.
         *
         * Each album, artist and genre keeps its row, and so its id, from one
         * scan to the next: it is matched by its key (the album's grouping key,
         * the artist's normalised name, the genre's name), written only when
         * something about it changed, and only the ones no track has any more
         * are deleted. They were cleared and inserted again, and the ids are
         * autoincrement, so every scan that found a change gave every album
         * and artist a new id: an album page left open, or the next list a
         * queue would carry on into (ListRef.LocalAlbum), pointed at an album
         * that was gone, and every track's row was written again to follow.
         * Now a track is written only when its own grouping changed.
         *
         * [canonicalGenre] is the name a genre tag is listed under.
         */
        internal suspend fun regroup(
            database: tf.monochrome.android.data.db.MusicDatabase,
            dao: LocalMediaDao,
            canonicalGenre: (String) -> String,
        ) {
            // Pull every track in one query (was: getAllTrackPaths() then per-path
            // findByPath, i.e. N+1 round-trips for an N-track library).
            val tracks = dao.getAllTracksSnapshot()

            // In-memory grouping — pure CPU work, no DB calls.
            val tracksByAlbumKey = HashMap<String, MutableList<LocalTrackEntity>>()
            val artistSet = HashMap<String, MutableList<LocalTrackEntity>>()
            val genreSet = HashMap<String, Int>()
            // A track missing a tag its album's other tracks have (a single
            // downloaded next to its album) joins that album; see AlbumGrouping.
            val albumKeys = AlbumGrouping.keys(
                tracks.map {
                    AlbumGrouping.Facts(it.album, it.albumArtist, it.artist, it.year, it.filePath.substringBeforeLast('/', ""))
                }
            )
            for ((index, track) in tracks.withIndex()) {
                val albumKey = albumKeys[index]
                tracksByAlbumKey.getOrPut(albumKey) { mutableListOf() }.add(track)

                val artistName = track.albumArtist ?: track.artist ?: "Unknown Artist"
                val normalizedArtist = normalizeText(artistName)
                artistSet.getOrPut(normalizedArtist) { mutableListOf() }.add(track)

                track.genre?.let { raw ->
                    val canonical = canonicalGenre(raw)
                    genreSet[canonical] = (genreSet[canonical] ?: 0) + 1
                }
            }

            // Wrap every DB write in a single transaction so Room flushes once and
            // observers (LocalLibraryViewModel's StateFlows) only get one
            // invalidation pass — and none for a table nothing was written to.
            database.withTransaction {
                // What is there now, read inside the transaction it is rewritten in.
                val albumsBefore = dao.getAlbumsOnce().associateBy { it.groupingKey }
                val artistsBefore = dao.getArtistsOnce().associateBy { it.normalizedName }
                val genresBefore = dao.getGenresOnce()

                // The gone go first. Keys are unique, so nothing below can
                // collide with them anyway; it keeps the order obvious.
                albumsBefore.values.filter { it.groupingKey !in tracksByAlbumKey }.map { it.id }
                    .chunked(DELETE_CHUNK_SIZE).forEach { dao.deleteAlbums(it) }
                artistsBefore.values.filter { it.normalizedName !in artistSet }.map { it.id }
                    .chunked(DELETE_CHUNK_SIZE).forEach { dao.deleteArtists(it) }

                val albumIdByTrackPath = HashMap<String, Long>(tracks.size)
                // Album cover propagated down to each track in the album, so
                // tracks without their own embedded art still render the album
                // cover in the song list instead of a music-note placeholder.
                val albumArtByTrackPath = HashMap<String, String?>(tracks.size)
                val albumsChanged = ArrayList<LocalAlbumEntity>()
                for ((key, albumTracks) in tracksByAlbumKey) {
                    // The album's title, artist and year from its most fully
                    // tagged track, not a single that joined it without them.
                    val representative = albumTracks.maxBy {
                        (if (!it.albumArtist.isNullOrBlank()) 2 else 0) + (if (it.year != null) 1 else 0)
                    }
                    // A "synthetic" album is the bucket every track with a null/
                    // blank `album` tag falls into — those tracks aren't actually
                    // an album together, just unidentified files that share the
                    // grouping key "unknown|unknown|0". If one of them happens to
                    // have embedded art, propagating that single cover onto all
                    // 1000+ unrelated tracks looks completely wrong (every song
                    // ends up branded with one stray file's cover). So: never
                    // store album art for the synthetic bucket, and never
                    // propagate it down to its tracks.
                    // So is a catch-all tag ("Various Artists", "Unknown Album",
                    // "Singles"): files from every folder share it and are no album
                    // together either. And a real album's cover reaches only the
                    // tracks in the folder it came from (AlbumArtShare).
                    val isSyntheticAlbum = AlbumArtShare.isPlaceholder(representative.album)
                    val albumArt = if (isSyntheticAlbum) null
                        else albumTracks.firstOrNull { it.hasEmbeddedArt }?.artworkCacheKey
                    val sharedCovers = AlbumArtShare.coversByPath(
                        albumTracks.map { AlbumArtShare.Member(it.filePath, it.hasEmbeddedArt, it.artworkCacheKey) },
                        placeholder = isSyntheticAlbum,
                    )
                    val albumEntity = LocalAlbumEntity(
                        id = albumsBefore[key]?.id ?: 0,
                        title = representative.album ?: "Unknown Album",
                        artist = representative.albumArtist ?: representative.artist ?: "Unknown Artist",
                        year = representative.year,
                        genre = representative.genre,
                        trackCount = albumTracks.size,
                        totalDuration = albumTracks.sumOf { it.durationSeconds },
                        artworkCacheKey = albumArt,
                        bestQuality = albumTracks.maxByOrNull { qualityScore(it) }?.let {
                            "${it.codec} ${it.bitDepth ?: ""}/${(it.sampleRate / 1000)}"
                        },
                        groupingKey = key
                    )
                    val albumId = when (val before = albumsBefore[key]) {
                        null -> dao.insertAlbum(albumEntity)
                        else -> before.id.also { if (albumEntity != before) albumsChanged += albumEntity }
                    }
                    for (track in albumTracks) {
                        albumIdByTrackPath[track.filePath] = albumId
                        albumArtByTrackPath[track.filePath] = sharedCovers[track.filePath]
                    }
                }
                if (albumsChanged.isNotEmpty()) dao.updateAlbums(albumsChanged)

                val artistIdByTrackPath = HashMap<String, Long>(tracks.size)
                val artistsChanged = ArrayList<LocalArtistEntity>()
                for ((normalizedName, artistTracks) in artistSet) {
                    val representative = artistTracks.first()
                    val displayName = representative.albumArtist ?: representative.artist ?: "Unknown Artist"
                    val uniqueAlbums = artistTracks.mapNotNull { it.album }.toSet().size
                    // Same logic as albums: the "Unknown Artist" bucket is a
                    // synthetic grouping of unrelated files with no artist tag.
                    // One stray file's embedded cover shouldn't appear next to
                    // every other anonymous track in the artist list.
                    val isSyntheticArtist = representative.albumArtist.isNullOrBlank() &&
                        representative.artist.isNullOrBlank()
                    val artistArt = if (isSyntheticArtist) null
                        else artistTracks.firstNotNullOfOrNull { it.artworkCacheKey }
                            ?: artistTracks.firstNotNullOfOrNull { albumArtByTrackPath[it.filePath] }
                    val artistEntity = LocalArtistEntity(
                        id = artistsBefore[normalizedName]?.id ?: 0,
                        name = displayName,
                        normalizedName = normalizedName,
                        albumCount = uniqueAlbums,
                        trackCount = artistTracks.size,
                        artworkCacheKey = artistArt
                    )
                    val artistId = when (val before = artistsBefore[normalizedName]) {
                        null -> dao.insertArtist(artistEntity)
                        else -> before.id.also { if (artistEntity != before) artistsChanged += artistEntity }
                    }
                    for (track in artistTracks) artistIdByTrackPath[track.filePath] = artistId
                }
                if (artistsChanged.isNotEmpty()) dao.updateArtists(artistsChanged)

                // Point every track at its album, artist and art, in this one
                // transaction. Trust hasEmbeddedArt (set by TagReader; covers both
                // an embedded picture atom and a folder sidecar) to decide
                // ownership. Tracks with their own art keep its path. Tracks
                // without get the cover of their album in their own folder — or
                // null if the album is synthetic or a catch-all tag, or that folder
                // has no cover (AlbumArtShare). Recomputing every row on
                // rebuild ensures stale propagation from a previous scan gets
                // cleaned up.
                //
                // Those three columns only, and only where they changed. The rows
                // were read before this transaction, so writing them back whole
                // put back whatever they held then: tags the backfill wrote
                // meanwhile, a dynamic range the player measured, gone.
                for (t in tracks) {
                    val albumId = albumIdByTrackPath[t.filePath]
                    val artistId = artistIdByTrackPath[t.filePath]
                    val art = if (t.hasEmbeddedArt) t.artworkCacheKey else albumArtByTrackPath[t.filePath]
                    if (albumId != t.albumId || artistId != t.artistId || art != t.artworkCacheKey) {
                        dao.updateTrackGrouping(t.id, albumId, artistId, art)
                    }
                }

                // Genres the same way, by name. The table has no unique name,
                // so a name found twice keeps its first row.
                val genreByName = LinkedHashMap<String, LocalGenreEntity>()
                val genresGone = ArrayList<Long>()
                for (genre in genresBefore) {
                    if (genre.name in genreSet && genre.name !in genreByName) genreByName[genre.name] = genre
                    else genresGone += genre.id
                }
                genresGone.chunked(DELETE_CHUNK_SIZE).forEach { dao.deleteGenres(it) }
                val genresChanged = ArrayList<LocalGenreEntity>()
                val genresNew = ArrayList<LocalGenreEntity>()
                for ((genre, count) in genreSet) {
                    val before = genreByName[genre]
                    when {
                        before == null -> genresNew += LocalGenreEntity(name = genre, trackCount = count)
                        before.trackCount != count -> genresChanged += before.copy(trackCount = count)
                    }
                }
                if (genresChanged.isNotEmpty()) dao.updateGenres(genresChanged)
                if (genresNew.isNotEmpty()) dao.insertGenres(genresNew)
            }
        }

        private fun qualityScore(track: LocalTrackEntity): Int {
            val codecScore = when (track.codec) {
                "FLAC" -> 100
                "ALAC" -> 95
                "WAV" -> 90
                "AIFF" -> 90
                "APE" -> 85
                "OGG_VORBIS" -> 60
                "OPUS" -> 55
                "AAC" -> 50
                "MP3" -> 40
                "WMA" -> 30
                else -> 0
            }
            val bitDepthScore = (track.bitDepth ?: 16) * 2
            val sampleRateScore = track.sampleRate / 1000
            return codecScore + bitDepthScore + sampleRateScore
        }

        /**
         * The dynamic range to keep for a file read again: what the player
         * measured, if the file is unchanged since ([existing]'s modified time
         * is [mediaStoreDateModified]). A file rewritten is new audio, so it
         * starts without one. A re-read happens for other reasons too (missing
         * art, a title setting), and a rescan replaces the row, so without this
         * every measurement went with it.
         */
        fun keptDynamicRange(existing: TrackScanInfo?, mediaStoreDateModified: Long): Float? =
            existing?.takeIf { it.lastModified == mediaStoreDateModified }?.dynamicRange

        /**
         * Decide whether a MediaStore file needs its tags (re-)read or the
         * existing DB row can be kept as-is. Pure function so it's unit
         * testable; [artworkFileExists] is injectable for the same reason.
         */
        fun needsReRead(
            existing: TrackScanInfo?,
            mediaStoreDateModified: Long,
            artworkFileExists: (String) -> Boolean = { File(it).exists() },
            titleFromFileName: Boolean = false,
            healNumbering: Boolean = false,
        ): Boolean {
            if (existing == null) return true
            if (existing.lastModified < mediaStoreDateModified) return true
            // Android reaps the app cache/ directory under storage
            // pressure, leaving Room rows pointing at vanished
            // artwork files. mtime hasn't changed, so without this
            // extra check the refresh button would never repopulate
            // them and the user is stuck with empty cards.
            if (existing.artworkCacheKey != null &&
                !artworkFileExists(existing.artworkCacheKey)
            ) return true
            // Re-tag tracks whose previous scan came up artwork-less.
            // The TagReader sidecar matcher has been extended over
            // time (per-track stem match was added later), so a
            // null cache key on an existing row may just mean "the
            // older scan logic missed it" — re-read so freshly-
            // installed cover detection logic gets a chance.
            if (!existing.hasEmbeddedArt && existing.artworkCacheKey == null) return true
            // Carried over from the old cache-keyed store: one unscaled copy
            // per track. Re-reading replaces it with a downscaled copy shared
            // across the album, so the store compacts over the user's rescans.
            if (tf.monochrome.android.data.local.tags.ArtworkKeys
                    .isLegacyKey(existing.artworkCacheKey)
            ) return true
            // Re-read rows that were indexed before artist-from-title
            // recovery could split them: no artist tag, but an "Artist - Title"
            // or "Artist ~ Title" shaped title. Self-heals (artist gets
            // populated, so the next scan skips them) and stays cheap
            // by only targeting files that can actually benefit.
            // Not with file-name titles: the title is then the file name by
            // choice, an "Artist - Title" file name stays one, and the heal
            // would re-read those files on every scan.
            if (!titleFromFileName && existing.artist == null &&
                existing.title?.let { tf.monochrome.android.data.local.tags.splitArtistTitle(it) } != null
            ) return true
            // Re-read rows an older scan gave a track or disc number for an
            // artist ("01 - Song" split at its dash into "01" and "Song"). The
            // split now takes the numbering off first, so the re-read heals the
            // row. Once, behind [healNumbering]: a tagged artist really named
            // "311" or "112" reads back the same, so as a per-scan rule it was
            // re-read on every full scan, and each re-read replaced its row
            // under a new id, losing it from the queue.
            if (healNumbering &&
                tf.monochrome.android.data.local.tags.TrackNames.looksLikeNumbering(existing.artist)
            ) return true
            return false
        }

        /**
         * Whether a linked StepMania pack song ([tags]) has to be read again
         * to take the chart's title and artist: its row was read before the
         * pack was linked, or the chart was renamed. Never for a song of no
         * pack. Its date says nothing here, since the file did not change.
         */
        fun needsPackRetag(
            existing: TrackScanInfo?,
            tags: tf.monochrome.android.glyph.pack.GlyphPackLibrary.LibraryTags?,
        ): Boolean = tags != null && existing != null &&
            (existing.title != tags.title || existing.artist != tags.artist)

        /**
         * Whether the incremental scan reads a MediaStore file: the library has
         * nothing at its path, or the file changed since it was read. By path
         * first because a copied or moved file keeps its old date.
         */
        fun needsIncrementalRead(existing: TrackScanInfo?, mediaStoreDateModified: Long): Boolean =
            existing == null || existing.lastModified < mediaStoreDateModified

        /**
         * Whether a prune of [toDelete] of a [libraryTracks]-track library looks
         * like files really went, rather than MediaStore answering short. Never
         * the whole library: that is a MediaStore with nothing to say yet, as at
         * boot before storage is mounted. Never more than half of one of 20 or
         * more tracks. A full scan prunes regardless; this is for the one that
         * runs on its own.
         */
        fun pruneLooksReal(libraryTracks: Int, toDelete: Int): Boolean = when {
            toDelete == 0 -> true
            toDelete >= libraryTracks -> false
            libraryTracks >= 20 && toDelete * 2 > libraryTracks -> false
            else -> true
        }

        /** A file's name without its folder or extension: the title it is saved under. */
        fun titleFromPath(path: String): String =
            path.substringAfterLast('/').substringBeforeLast('.')

        fun normalizeText(text: String): String {
            return Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFC)
        }

        fun buildAlbumGroupingKey(album: String?, artist: String?, year: Int?): String {
            val normalizedAlbum = normalizeText(album ?: "unknown")
            val normalizedArtist = normalizeText(artist ?: "unknown")
            return "$normalizedAlbum|$normalizedArtist|${year ?: 0}"
        }
    }
}
