package tf.monochrome.android.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.LyricLine
import tf.monochrome.android.domain.model.LyricWord
import tf.monochrome.android.domain.model.Lyrics

/**
 * Made-up lyrics for the previews (Player Visuals Studio and a theme's page in
 * the Theme Store), timed word by word like a karaoke source, so the real
 * lyric view can sing them: the intro with the first line waiting, the words
 * lighting up, the god rays following them, their fade and "Keep shining"
 * between words, and the glide to the next line. The words are the app's own,
 * no song's. Kept apart from the drawing so the timing is held to numbers
 * ([PreviewLyricsTest]).
 */
internal object PreviewLyrics {
    /** Before the first line: long enough to see it wait at the top. */
    const val INTRO_MS = 2_400L

    /** After the last word, before the song starts over. */
    const val OUTRO_MS = 2_000L

    /** Between the last word of a line and the first of the next. */
    const val LINE_GAP_MS = 700L

    /** A breath after a word that ends in a comma: where Fade out and Keep shining differ. */
    const val PAUSE_MS = 320L

    /** A word is sung for longer the more letters it has, within a sung word's range. */
    fun wordMs(word: String): Long = (180L + 60L * word.length).coerceIn(260L, 760L)

    private val COMMAS = charArrayOf(',', '，', '、')

    /**
     * [texts] as word-timed lines: words split at spaces, each sung for
     * [wordMs], the next starting as it ends, but [PAUSE_MS] later after a
     * comma; each line starts at its first word, [LINE_GAP_MS] after the line
     * before, the first after [INTRO_MS]. A blank text is a ♪ break of the
     * gap's length, with no words.
     */
    fun timed(texts: List<String>): List<LyricLine> {
        var t = INTRO_MS
        return texts.map { text ->
            val words = text.split(' ').filter { it.isNotEmpty() }
            val start = t
            if (words.isEmpty()) {
                t += LINE_GAP_MS * 2
                return@map LyricLine(start, "")
            }
            val timed = words.map { w ->
                val word = LyricWord(t, t + wordMs(w), w)
                t = word.endMs + if (w.trimEnd().last() in COMMAS) PAUSE_MS else 0L
                word
            }
            t = timed.last().endMs + LINE_GAP_MS
            LyricLine(start, text, timed)
        }
    }

    /** How long [lines] run, intro to outro: one turn of the preview's loop. */
    fun lengthMs(lines: List<LyricLine>): Long {
        val end = lines.maxOfOrNull { line -> line.words.maxOfOrNull { it.endMs } ?: line.timeMs } ?: 0L
        return end + OUTRO_MS
    }

    /** Where the loop is [elapsedMs] after it started: back to the intro at each turn. */
    fun positionAt(elapsedMs: Long, lengthMs: Long): Long =
        if (lengthMs <= 0L) 0L else Math.floorMod(elapsedMs, lengthMs)
}

/** The preview's lyrics, in the app's language. */
@Composable
internal fun rememberPreviewLyricLines(): List<LyricLine> {
    val texts = listOf(
        stringResource(R.string.fx_preview_lyric_1),
        stringResource(R.string.fx_preview_lyric_2),
        stringResource(R.string.fx_preview_lyric_3),
        stringResource(R.string.fx_preview_lyric_4),
        stringResource(R.string.fx_feel_the_beat_tonight),
    )
    return remember(texts) { PreviewLyrics.timed(texts) }
}

/**
 * The preview song's position, looping, moved as the player moves its own:
 * four times a second, and on time for each word's start and end
 * ([LyricClock.nextPollDelayMs]), so the preview's highlight steps as the
 * player's does. [outputSyncMs] is the delay the lyric view takes off the
 * position (Bluetooth), so the clock lands on the words as it hears them.
 */
@Composable
internal fun rememberPreviewLyricsClock(lines: List<LyricLine>, outputSyncMs: Float): StateFlow<Long> {
    val position = remember { MutableStateFlow(0L) }
    LaunchedEffect(lines, outputSyncMs) {
        val lyrics = Lyrics(lines = lines, isSynced = true)
        val length = PreviewLyrics.lengthMs(lines)
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val now = PreviewLyrics.positionAt(SystemClock.elapsedRealtime() - start, length)
            position.value = now
            delay(LyricClock.nextPollDelayMs(now, 1f, outputSyncMs, lyrics))
        }
    }
    return position
}
