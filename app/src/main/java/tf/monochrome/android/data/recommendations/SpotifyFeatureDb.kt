package tf.monochrome.android.data.recommendations

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One row of the bundled Spotify audio-features dataset
 * (assets/spotify_features.db, 113,999 tracks across 114 genres).
 *
 * Features are the Spotify Audio Features in their native ranges: the seven
 * 0..1 attributes plus [tempo] (BPM) and [loudness] (dBFS, typically -60..0).
 * [normKey] is `normalize(firstArtist)|normalize(trackName)` and is how a
 * now-playing track is matched back to its dataset row — the normaliser here
 * MUST stay byte-identical to the one used when the .db was built.
 */
data class FeatureRow(
    val id: Long,
    val title: String,
    val artist: String,
    val genre: String,
    val popularity: Int,
    val danceability: Double,
    val energy: Double,
    val valence: Double,
    val acousticness: Double,
    val instrumentalness: Double,
    val tempo: Double,
    val loudness: Double,
    val normKey: String,
)

/**
 * Read-only accessor over the pre-built Spotify features SQLite asset.
 *
 * The asset cannot be opened from the APK directly (assets aren't a file
 * path), so on first use it is copied once into filesDir and opened
 * read-only. A version stamp next to it triggers a re-copy when the bundled
 * dataset is bumped. All queries are cheap, indexed lookups (idx_genre,
 * idx_norm); callers run them off the main thread.
 */
@Singleton
class SpotifyFeatureDb @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var db: SQLiteDatabase? = null

    private fun open(): SQLiteDatabase? {
        db?.let { return it }
        synchronized(this) {
            db?.let { return it }
            return runCatching {
                val target = File(context.filesDir, DB_NAME)
                val stamp = File(context.filesDir, "$DB_NAME.v")
                if (!target.exists() || stamp.readTextOrNull() != DB_VERSION.toString()) {
                    context.assets.open(ASSET_NAME).use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    stamp.writeText(DB_VERSION.toString())
                }
                SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
                    .also { db = it }
            }.getOrNull()
        }
    }

    /** Exact dataset row for a normalized `artist|title` key, or null. */
    fun lookupByKey(normKey: String): FeatureRow? =
        query("SELECT $COLS FROM tracks WHERE norm_key = ? LIMIT 1", arrayOf(normKey)).firstOrNull()

    /**
     * Rows whose normalized artist matches [artistKey] (prefix match on the
     * indexed norm_key) — the fallback when a track's exact title isn't in the
     * dataset but the artist is. Capped so a prolific artist can't blow up.
     */
    fun lookupByArtist(artistKey: String, limit: Int = 50): List<FeatureRow> =
        query(
            "SELECT $COLS FROM tracks WHERE norm_key LIKE ? ORDER BY popularity DESC LIMIT ?",
            arrayOf("$artistKey|%", limit.toString()),
        )

    /** Every dataset row in a genre (a few hundred to ~1k) for similarity scoring. */
    fun rowsByGenre(genre: String, limit: Int = 1200): List<FeatureRow> =
        query(
            "SELECT $COLS FROM tracks WHERE genre = ? LIMIT ?",
            arrayOf(genre, limit.toString()),
        )

    /** Most-popular rows in a genre, for discovery/genre browse seeds. */
    fun topByGenre(genre: String, limit: Int = 60): List<FeatureRow> =
        query(
            "SELECT $COLS FROM tracks WHERE genre = ? ORDER BY popularity DESC LIMIT ?",
            arrayOf(genre, limit.toString()),
        )

    /** All distinct genres in the dataset (114 of them). */
    fun genres(): List<String> {
        val out = ArrayList<String>(120)
        open()?.rawQuery("SELECT DISTINCT genre FROM tracks ORDER BY genre", null)?.use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    private fun query(sql: String, args: Array<String>): List<FeatureRow> {
        val cursor = open()?.rawQuery(sql, args) ?: return emptyList()
        return cursor.use { c ->
            val out = ArrayList<FeatureRow>(c.count)
            while (c.moveToNext()) {
                out.add(
                    FeatureRow(
                        id = c.getLong(0),
                        title = c.getString(1),
                        artist = c.getString(2),
                        genre = c.getString(3),
                        popularity = c.getInt(4),
                        danceability = c.getDouble(5),
                        energy = c.getDouble(6),
                        valence = c.getDouble(7),
                        acousticness = c.getDouble(8),
                        instrumentalness = c.getDouble(9),
                        tempo = c.getDouble(10),
                        loudness = c.getDouble(11),
                        normKey = c.getString(12),
                    )
                )
            }
            out
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    companion object {
        const val ASSET_NAME = "spotify_features.db"
        private const val DB_NAME = "spotify_features.db"
        // Bump when the bundled .db is rebuilt so devices re-copy it.
        private const val DB_VERSION = 1
        private const val COLS =
            "id, track_name, artist, genre, popularity, danceability, energy, valence, " +
                "acousticness, instrumentalness, tempo, loudness, norm_key"

        /**
         * Normalises a string to `[a-z0-9]` only — MUST match the Python
         * normaliser used to build spotify_features.db (`re.sub('[^a-z0-9]','')`
         * over `str.lower()`), or norm_key lookups will silently miss.
         */
        fun normalize(s: String): String = buildString(s.length) {
            for (ch in s.lowercase()) if (ch in 'a'..'z' || ch in '0'..'9') append(ch)
        }
    }
}
