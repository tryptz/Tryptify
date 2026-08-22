// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.glyph.chart.GlyphSimfile
import tf.monochrome.android.glyph.chart.SscParser

/**
 * The songs the mode can play, and the charts already generated for them.
 *
 * Only MP3 and FLAC are offered. Not a limitation of the chart generator — it
 * takes whatever the platform decoder does — but of honesty about the flow:
 * these are the two formats the conversion service is specified for, and a
 * listing that offers a file the next screen refuses is worse than one that
 * does not offer it.
 *
 * Generated simfiles live beside the mode rather than next to the audio: the
 * music may sit on a volume the app cannot write to, and a chart is Tryptify's
 * own artefact rather than something to scatter through someone's library.
 */
@Singleton
class GlyphSongRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localMedia: LocalMediaRepository,
) {

    private val chartRoot: File
        get() = File(context.filesDir, CHART_DIRECTORY).apply { mkdirs() }

    /**
     * Every local MP3 or FLAC, each with whatever chart it already has.
     *
     * A flow because the library scanner writes into the same database and a
     * newly imported song should appear without the player leaving the screen.
     */
    fun songs(): Flow<List<GlyphSong>> = localMedia.getAllTracks().map { tracks ->
        tracks.filter { it.isConvertible }.map { it.toGlyphSong() }
    }

    suspend fun song(trackId: String): GlyphSong? =
        songs().first().firstOrNull { it.trackId == trackId }

    /**
     * [song] with its chart fields re-read from disk.
     *
     * The song list is a Room flow over the library, and generating a chart
     * writes a file into app storage — which Room knows nothing about, so the
     * flow does not re-emit and every cached row keeps saying "No chart" after
     * a successful generation. Anything that needs the current answer asks
     * here instead of trusting the cached row.
     */
    suspend fun withCurrentChart(song: GlyphSong): Pair<GlyphSong, GlyphSimfile?> {
        val simfile = simfile(song.trackId)
        val exists = withContext(Dispatchers.IO) { simfileFile(song.trackId).exists() }
        return song.copy(
            bpm = simfile?.timing?.startBpm ?: song.bpm,
            difficulties = simfile?.availableDifficulties.orEmpty(),
            chartState = when {
                simfile != null -> GlyphChartState.READY
                exists -> GlyphChartState.UNREADABLE
                else -> GlyphChartState.NOT_GENERATED
            },
        ) to simfile
    }

    /** Where the simfile for [trackId] is, whether or not it exists yet. */
    fun simfileFile(trackId: String): File = File(chartRoot, "${chartId(trackId)}.ssc")

    /**
     * A stable id for a track's chart.
     *
     * Derived from the track id rather than from the file path, so a song moved
     * on disk keeps its charts and its history. Hashed because the id can hold
     * anything a filesystem can.
     */
    fun chartId(trackId: String): String = trackId.hashCode().toUInt().toString(16)

    /**
     * Load and parse the chart for [trackId].
     *
     * Returns null for "no chart yet" and for "the chart on disk is unusable"
     * alike, because the player's next step is the same either way: generate
     * one. The reason is logged rather than surfaced as an error state nobody
     * can act on.
     */
    suspend fun simfile(trackId: String): GlyphSimfile? = withContext(Dispatchers.IO) {
        val file = simfileFile(trackId)
        if (!file.exists()) return@withContext null
        runCatching { SscParser.parse(file.readText()) }
            .onFailure { Log.w(TAG, "chart for $trackId is unreadable: ${it.message}") }
            .getOrNull()
    }

    private val UnifiedTrack.isConvertible: Boolean
        get() = sourceType == tf.monochrome.android.domain.model.SourceType.LOCAL &&
            (codec == AudioCodec.MP3 || codec == AudioCodec.FLAC)

    private suspend fun UnifiedTrack.toGlyphSong(): GlyphSong {
        val path = (source as? PlaybackSource.LocalFile)?.filePath.orEmpty()
        val chartFile = simfileFile(id)
        val simfile = if (chartFile.exists()) simfile(id) else null
        return GlyphSong(
            trackId = id,
            chartId = chartId(id),
            title = title,
            artist = artistName,
            filePath = path,
            artworkUri = artworkUri,
            durationSeconds = durationSeconds,
            codec = codec ?: AudioCodec.UNKNOWN,
            // BPM is only known once a chart exists — it comes out of the stem
            // analysis, not out of the file's tags.
            bpm = simfile?.timing?.startBpm,
            difficulties = simfile?.availableDifficulties.orEmpty(),
            chartState = when {
                simfile != null -> GlyphChartState.READY
                chartFile.exists() -> GlyphChartState.UNREADABLE
                else -> GlyphChartState.NOT_GENERATED
            },
        )
    }

    private companion object {
        const val TAG = "GlyphSongs"
        const val CHART_DIRECTORY = "glyph/charts"
    }
}

/** One row in the song list. */
data class GlyphSong(
    val trackId: String,
    val chartId: String,
    val title: String,
    val artist: String,
    val filePath: String,
    val artworkUri: String?,
    val durationSeconds: Int,
    val codec: AudioCodec,
    val bpm: Float?,
    val difficulties: List<StepManiaDifficulty>,
    val chartState: GlyphChartState,
) {
    val hasChart: Boolean get() = chartState == GlyphChartState.READY

    val formattedDuration: String
        get() = "%d:%02d".format(durationSeconds / 60, durationSeconds % 60)

    /** "128 BPM" or a dash — never a fabricated number. */
    val bpmLabel: String get() = bpm?.let { "${it.toInt()} BPM" } ?: "—"
}

/**
 * Whether a song has a playable chart.
 *
 * [UNREADABLE] is kept distinct from [NOT_GENERATED] so the UI can say "this
 * one needs regenerating" rather than silently offering to make a chart that is
 * already there.
 */
enum class GlyphChartState(val label: String) {
    NOT_GENERATED("No chart"),
    READY("Ready"),
    UNREADABLE("Needs regenerating"),
}
