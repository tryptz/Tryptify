// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.data.api.LrcFormat
import tf.monochrome.android.data.db.dao.DownloadDao
import tf.monochrome.android.data.db.dao.LyricsCacheDao
import tf.monochrome.android.data.db.entity.CachedLyricsEntity
import tf.monochrome.android.data.downloads.LrcSidecar
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.util.RomajiConverter

/**
 * Word-timed lyrics kept for a song for good: the lyrics editor's
 * ([publish]), and what a lyrics server timed before the app stopped asking
 * one (2026-10-29). Either beats any lyrics race: nothing times this
 * recording better. [MusicRepository.getLyrics] reads [cached] before a
 * race, [results] tells the player when a song's lyrics changed under it,
 * and a download gets its `.lrc` beside it when downloads carry lyrics.
 *
 * The rows ([LyricsCacheDao]) hold the shape a server answered in, so the
 * ones a server wrote still read; the editor writes the same shape.
 */
@Singleton
class TimedLyricsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: LyricsCacheDao,
    private val downloadDao: DownloadDao,
    private val preferences: PreferencesManager,
    private val json: Json,
) {
    private val _results = MutableSharedFlow<Pair<Long, Lyrics>>(extraBufferCapacity = 8)

    /** Each song's lyrics as the editor finishes them, by track id. */
    val results: SharedFlow<Pair<Long, Lyrics>> = _results.asSharedFlow()

    /** The lyrics kept for [trackId] before, if any. */
    suspend fun cached(trackId: Long, convertToRomaji: Boolean): Lyrics? =
        cache.get(trackId)?.let { fromCached(json, it.lyricsJson, convertToRomaji)?.lyrics }

    /** Lyrics made by hand (the editor) for [trackId]: kept, and shown. */
    suspend fun publish(trackId: Long, lyrics: Lyrics, lrc: String?) {
        cache.put(CachedLyricsEntity(trackId = trackId, lyricsJson = toData(lyrics, lrc), isSynced = true))
        _results.emit(trackId to lyrics)
        if (lrc != null) sidecar(trackId, lrc)
    }

    /** Forgets what was kept for [trackId], so the next lookup races the catalogues again. */
    suspend fun forget(trackId: Long) = cache.delete(trackId)

    /** The LRC the cache holds for [trackId], or one written from its lines. */
    suspend fun lrcOf(trackId: Long, title: String?, artist: String?, lengthMs: Long?): String? {
        val kept = cache.get(trackId) ?: return null
        val back = fromCached(json, kept.lyricsJson, convertToRomaji = false) ?: return null
        return back.lrc ?: LrcFormat.write(back.lyrics, title, artist, lengthMs)
    }

    /** The `.lrc` beside the song's download, when downloads carry lyrics and the song has one. */
    private suspend fun sidecar(trackId: Long, lrc: String) {
        if (!preferences.downloadLyrics.first()) return
        val downloaded = runCatching { downloadDao.getDownloadedTrack(trackId) }.getOrNull() ?: return
        if (!LrcSidecar.write(context, downloaded.filePath, lrc)) Log.w(TAG, "could not write the .lrc beside ${downloaded.filePath}")
    }

    /** What a row holds: the lyrics, and the LRC kept with them when there was one. */
    class Kept(val lyrics: Lyrics, val lrc: String?)

    companion object {
        private const val TAG = "TimedLyrics"

        /**
         * Lyrics in the shape of a server's answer (`lines` of `words`, times
         * in seconds), so the cache holds one kind of thing and [fromCached]
         * reads the editor's and a server's alike.
         */
        fun toData(lyrics: Lyrics, lrc: String?): String {
            val lines = JsonArray(
                lyrics.lines.map { line ->
                    val words = line.words.ifEmpty {
                        // A line with no words is one word for the whole line's time.
                        listOf(LyricWord(line.timeMs, line.timeMs, line.text))
                    }
                    JsonObject(
                        mapOf(
                            "start" to JsonPrimitive(line.timeMs / 1000.0),
                            "end" to JsonPrimitive(words.last().endMs / 1000.0),
                            "estimated" to JsonPrimitive(false),
                            "words" to JsonArray(
                                words.mapIndexed { i, w ->
                                    JsonObject(
                                        mapOf(
                                            "text" to JsonPrimitive(if (i < words.lastIndex) w.text + " " else w.text),
                                            "start" to JsonPrimitive(w.startMs / 1000.0),
                                            "end" to JsonPrimitive(w.endMs / 1000.0),
                                        ),
                                    )
                                },
                            ),
                        ),
                    )
                },
            )
            val data = mutableMapOf<String, JsonElement>(
                "mode" to JsonPrimitive("edited"),
                "model" to JsonPrimitive("the lyrics editor"),
                "lines" to lines,
            )
            lrc?.let { data["lrc"] = JsonPrimitive(it) }
            return JsonObject(data).toString()
        }

        /** A row's data back as lyrics; null when it is not that shape or has no lines. */
        fun fromCached(json: Json, dataJson: String, convertToRomaji: Boolean): Kept? {
            val data = runCatching { json.parseToJsonElement(dataJson).jsonObject }.getOrNull() ?: return null
            val lines = (data["lines"] as? JsonArray)
                ?.mapNotNull { element -> (element as? JsonObject)?.let { lineOf(it, convertToRomaji) } }
                ?.takeIf { it.isNotEmpty() } ?: return null
            return Kept(Lyrics(lines = lines, isSynced = true), data["lrc"]?.jsonPrimitive?.contentOrNull)
        }

        /** One line: its words in ms, its text as the words read; null when it has no words. */
        private fun lineOf(line: JsonObject, convertToRomaji: Boolean): LyricLine? {
            val words = (line["words"] as? JsonArray)?.mapNotNull { element ->
                val word = element as? JsonObject ?: return@mapNotNull null
                val raw = word["text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: start
                raw to LyricWord(
                    startMs = (start * 1000).roundToLong(),
                    endMs = (end * 1000).roundToLong().coerceAtLeast((start * 1000).roundToLong()),
                    // The karaoke view spaces words itself; the space after a word goes.
                    text = raw.trim().let { if (convertToRomaji) RomajiConverter.convert(it) else it },
                )
            }?.filter { it.second.text.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: return null
            // The line as written: the words with their own spacing, which is
            // none between the characters of an unspaced script.
            val text = words.joinToString("") { it.first }.trim().let { if (convertToRomaji) RomajiConverter.convert(it) else it }
            val start = line["start"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).roundToLong() } ?: words.first().second.startMs
            return LyricLine(timeMs = start, text = text, words = words.map { it.second })
        }
    }
}
