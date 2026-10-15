package tf.monochrome.android.data.local.galaxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import tf.monochrome.android.data.db.dao.PlayEventDao
import tf.monochrome.android.data.local.db.LocalAlbumEntity
import tf.monochrome.android.data.local.db.LocalMediaDao
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.scanner.MediaScanner
import tf.monochrome.android.data.local.tags.TrackNames
import tf.monochrome.android.domain.model.LibraryAlbum
import tf.monochrome.android.domain.model.LibraryArtist
import tf.monochrome.android.domain.model.LibraryCollab
import tf.monochrome.android.domain.model.LibraryTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library as the library galaxy reads it: every artist, every album and
 * track, and how much each is played. Read from the device's own library and
 * play history, and nothing else — no charts, no MusicBrainz, no network — so
 * the galaxy works with the phone in airplane mode.
 *
 * The artists come from a few grouped queries, never the whole track table,
 * and are read again whenever a scan changes the library. An artist's albums
 * are read when their star is visited ([music]). Laying them out as stars and
 * planets is the galaxy's own business (`LibraryStarField`, `LocalSystems`).
 */
@Singleton
class LocalGalaxyRepository @Inject constructor(
    private val dao: LocalMediaDao,
    private val playEvents: PlayEventDao,
) {

    /**
     * The library's artists, read again each time a scan changes it — waiting
     * for the scan's writes to settle first, so one scan is one read.
     */
    @OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun artists(): Flow<LibraryArtists> = dao.observeGalaxyStamp()
        .distinctUntilChanged()
        .debounce(REBUILD_SETTLE_MS)
        .mapLatest { build() }
        .flowOn(Dispatchers.Default)

    private suspend fun build(): LibraryArtists {
        val rows = dao.getArtistsOnce().filter { it.trackCount > 0 }
        if (rows.isEmpty()) return LibraryArtists.EMPTY
        val years = dao.galaxyFirstYears().associate { it.artistId to it.year }
        val tags = dao.galaxyGenreTags().groupBy { it.artistId }
        val keyOf = rows.associate { it.id to it.normalizedName }

        val artists = rows.map { row ->
            LibraryArtist(
                key = row.normalizedName,
                name = row.name,
                trackCount = row.trackCount,
                firstYear = years[row.id],
                genreTags = tags[row.id].orEmpty().associate { it.tag to it.tracks },
                kind = kindOf(row.normalizedName),
            )
        }
        val collabs = dao.galaxyCollabs().mapNotNull { c ->
            val a = keyOf[c.artistId] ?: return@mapNotNull null
            LibraryCollab(a, MediaScanner.normalizeText(c.other), c.tracks)
        }
        return LibraryArtists(artists, collabs, rows.associate { it.normalizedName to it.id })
    }

    /**
     * Artist [artistId]'s albums with their tracks and plays, or null for an
     * artist with none.
     */
    suspend fun music(artistId: Long): ArtistMusic? {
        val tracks = dao.getTracksByArtistOnce(artistId)
        if (tracks.isEmpty()) return null
        val byAlbum = tracks.groupBy { it.albumId }
        val albums: Map<Long, LocalAlbumEntity> = byAlbum.keys.filterNotNull()
            .chunked(SQL_IN_CHUNK)
            .flatMap { dao.getAlbumsByIds(it) }
            .associateBy { it.id }

        val since = System.currentTimeMillis() - RECENT_MILLIS
        val titles = byAlbum.mapNotNull { (id, t) -> id?.let { albums[it]?.title } ?: t.firstNotNullOfOrNull { it.album } }.distinct()
        val plays = titles.chunked(SQL_IN_CHUNK)
            .flatMap { playEvents.playsOnAlbums(it, since) }
            .groupBy { fold(it.albumTitle.orEmpty()) to fold(it.title) }
            .mapValues { (_, rows) -> rows.sumOf { it.plays } to rows.sumOf { it.recent } }

        val albumTracks = LinkedHashMap<String, List<LocalTrackEntity>>()
        val albumIds = HashMap<String, Long>()
        val library = byAlbum.map { (id, own) ->
            val row = id?.let { albums[it] }
            val title = row?.title ?: own.firstNotNullOfOrNull { it.album } ?: UNTITLED
            val key = row?.groupingKey ?: "$UNGROUPED$artistId"
            albumTracks[key] = own
            if (id != null) albumIds[key] = id
            val folded = fold(title)
            val items = own.map { t ->
                val (ever, recent) = plays[folded to fold(t.displayTitle)] ?: (0 to 0)
                LibraryTrack(
                    id = t.id,
                    key = t.filePath,
                    title = t.displayTitle,
                    discNumber = t.discNumber,
                    trackNumber = t.trackNumber,
                    durationSeconds = t.durationSeconds,
                    plays = ever,
                    bpm = t.bpm,
                    camelotKey = t.camelotKey,
                    label = t.moonLabel(),
                ) to recent
            }
            // Tempo, key, loudness and dynamic range, from the rows already
            // here: no query of its own.
            val sums = AlbumAggregates.of(own)
            LibraryAlbum(
                key = key,
                title = title,
                year = row?.year ?: own.mapNotNull { it.year }.minOrNull(),
                artworkUri = row?.artworkCacheKey ?: own.firstNotNullOfOrNull { it.artworkCacheKey },
                tracks = items.map { it.first },
                plays = items.sumOf { it.first.plays },
                recentPlays = items.sumOf { it.second },
                bpm = sums.bpm,
                camelotKey = sums.camelotKey,
                lufs = sums.lufs,
                dynamicRange = sums.dynamicRange,
            )
        }
        return ArtistMusic(library, albumTracks, albumIds)
    }

    /** Every track of artist [artistId], in release and running order: what "play this artist" queues. */
    suspend fun tracksOf(artistId: Long): List<LocalTrackEntity> =
        dao.getTracksByArtistOnce(artistId).sortedWith(
            compareBy<LocalTrackEntity>({ it.year ?: Int.MAX_VALUE }, { it.album.orEmpty().lowercase() }, { it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }),
        )

    private fun kindOf(normalized: String): LibraryArtist.Kind = when (normalized) {
        in COMPILATION_NAMES -> LibraryArtist.Kind.COMPILATIONS
        UNKNOWN_NAME -> LibraryArtist.Kind.UNKNOWN
        else -> LibraryArtist.Kind.ARTIST
    }

    private fun fold(text: String): String = MediaScanner.normalizeText(text)

    private companion object {
        /** How long a scan's writes are let settle before the galaxy is rebuilt. */
        const val REBUILD_SETTLE_MS = 400L

        /** "Lately", for a planet's glow: the last thirty days. */
        const val RECENT_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Under SQLite's limit on bound parameters, with room to spare. */
        const val SQL_IN_CHUNK = 500

        /** The album artist compilations are filed under, normalised as the scanner does. */
        val COMPILATION_NAMES = setOf("various artists", "various", "va", "v.a.")

        /** The artist the scanner files untagged tracks under, normalised. */
        const val UNKNOWN_NAME = "unknown artist"

        const val UNTITLED = "Untitled"
        const val UNGROUPED = "ungrouped|"
    }
}

/** The library's artists, who works with whom, and each artist's row. */
class LibraryArtists(
    val artists: List<LibraryArtist>,
    val collabs: List<LibraryCollab>,
    /** Each artist's `local_artists` row, by their key. */
    val ids: Map<String, Long>,
) {
    companion object {
        val EMPTY = LibraryArtists(emptyList(), emptyList(), emptyMap())
    }
}

/** An artist's albums, and the files behind each: what a tap on one plays. */
class ArtistMusic(
    val albums: List<LibraryAlbum>,
    /** Each album's tracks, by the album's key, as stored. */
    val albumTracks: Map<String, List<LocalTrackEntity>>,
    /** Each album's `local_albums` row, by the album's key, where it has one. */
    val albumIds: Map<String, Long>,
)

/**
 * What this track's moon is labelled ([TrackNames.label]). Only a row with no
 * title at all has lost its numbering already, in [LocalTrackEntity.displayTitle];
 * a stored title, from its tag or from its file name by the scan setting that
 * names tracks so, is checked against the track's own number.
 */
internal fun LocalTrackEntity.moonLabel(): String = TrackNames.label(
    displayTitle,
    trackNumber = trackNumber.takeIf { title != null },
    discNumber = discNumber,
    artists = listOf(artist, albumArtist),
)
