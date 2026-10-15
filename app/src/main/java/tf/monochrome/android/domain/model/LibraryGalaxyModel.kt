package tf.monochrome.android.domain.model

// The library as the library galaxy reads it: summaries of the device's own
// music, built by `LocalGalaxyRepository` and laid out by `LibraryStarField`
// (artists as stars) and `LocalSystems` (albums as planets, tracks as moons).

/**
 * One artist in the library, as the galaxy needs them: a summary, not their
 * tracks. Built by the repository from a few grouped queries.
 */
data class LibraryArtist(
    /** The artist's normalised name: what the star is keyed by, and what keeps it in place. */
    val key: String,
    val name: String,
    val trackCount: Int,
    /** Their earliest album's year, where any is tagged. */
    val firstYear: Int?,
    /** Each genre tag on their tracks, as written, and how many tracks carry it. */
    val genreTags: Map<String, Int> = emptyMap(),
    val kind: Kind = Kind.ARTIST,
) {
    enum class Kind {
        ARTIST,
        /** Every compilation in the library, as one star near the core. */
        COMPILATIONS,
        /** Tracks with no artist tag at all, as one dim star in the halo. */
        UNKNOWN,
    }
}

/** Two artists on the same tracks — a feature, a collaboration — by key, and on how many. */
data class LibraryCollab(val a: String, val b: String, val tracks: Int)

/** An album in the library, with its tracks, as an artist's system needs it. */
data class LibraryAlbum(
    /** The album's grouping key (`local_albums.groupingKey`): what keeps its look the same between scans. */
    val key: String,
    val title: String,
    val year: Int?,
    val artworkUri: String?,
    val tracks: List<LibraryTrack>,
    /** Plays of its tracks, ever, and lately. */
    val plays: Int = 0,
    val recentPlays: Int = 0,
    /** Its tracks' median tempo, beats per minute, where any is tagged. */
    val bpm: Float? = null,
    /** Its commonest key, as a Camelot code (1..12 minor, 13..24 major), where any is tagged. */
    val camelotKey: Int? = null,
    /** How loud it was mastered, LUFS, from its ReplayGain or R128 tags. */
    val lufs: Float? = null,
    /** Its tracks' median dynamic range, dB, where any has been measured. */
    val dynamicRange: Float? = null,
) {
    /** Its running time, seconds: every track's, added up. */
    val durationSeconds: Int get() = tracks.sumOf { it.durationSeconds.coerceAtLeast(0) }
}

data class LibraryTrack(
    /** Its `local_tracks` row: what a tap plays. */
    val id: Long,
    /** Its file's path: what keeps its moon's place the same between scans. */
    val key: String,
    /**
     * As the library has it: what the track playing is matched to its moon
     * by, and its running order's last tie-break.
     */
    val title: String,
    val discNumber: Int?,
    val trackNumber: Int?,
    val durationSeconds: Int,
    val plays: Int = 0,
    /** Its tempo, beats per minute, where tagged. */
    val bpm: Float? = null,
    /** Its key as a Camelot code, where tagged. */
    val camelotKey: Int? = null,
    /** What its moon is labelled: [title] without numbering or its artist's name before it. */
    val label: String = title,
)
