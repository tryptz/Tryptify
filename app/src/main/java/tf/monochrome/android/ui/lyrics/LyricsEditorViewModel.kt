// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.LrcFormat
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.data.repository.TimedLyricsStore
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.player.NowPlayingLyricsHolder
import tf.monochrome.android.player.QueueManager

/**
 * The lyrics editor's working copy of the playing song's lyrics, and every
 * move on it ([LyricsEdit]) with undo. Saving hands the copy to
 * [TimedLyricsStore.publish]: kept for good, shown in the player at once,
 * and written beside the download as an `.lrc`.
 *
 * The copy starts from whatever the player shows (the race's answer, a
 * server's, or an earlier edit) the first time a song is on, and from a
 * server's answer arriving while nothing has been changed.
 */
@HiltViewModel
class LyricsEditorViewModel @Inject constructor(
    queueManager: QueueManager,
    private val holder: NowPlayingLyricsHolder,
    private val store: TimedLyricsStore,
    private val repository: MusicRepository,
    private val preferences: PreferencesManager,
) : ViewModel() {

    val track: StateFlow<Track?> = queueManager.currentTrack
    val positionMs: StateFlow<Long> = holder.positionMs
    val lyricsFx: StateFlow<LyricsFxSettings> = preferences.lyricsFx
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsFxSettings())

    private val _draft = MutableStateFlow<Lyrics?>(null)
    val draft: StateFlow<Lyrics?> = _draft.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val _selectedLine = MutableStateFlow(0)
    val selectedLine: StateFlow<Int> = _selectedLine.asStateFlow()

    private val _selectedWord = MutableStateFlow(0)
    val selectedWord: StateFlow<Int> = _selectedWord.asStateFlow()

    /** Stamps go to the selected word, not the whole line. */
    val wordMode = MutableStateFlow(false)

    /** How far a nudge moves, in ms. */
    val stepMs = MutableStateFlow(50L)

    private val undo = ArrayDeque<Lyrics>()
    private val redo = ArrayDeque<Lyrics>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** One-line notes for the screen: saved, shared, nothing to save. Cleared by the screen. */
    private val _note = MutableStateFlow<EditorNote?>(null)
    val note: StateFlow<EditorNote?> = _note.asStateFlow()

    private var loadedFor: Long? = null

    init {
        // The player's lyrics for the song, once, and again for each new song.
        viewModelScope.launch {
            combine(track, holder.lyrics) { t, lyrics -> t to lyrics }.collect { (t, lyrics) ->
                if (t == null) return@collect
                if (loadedFor != t.id) {
                    loadedFor = t.id
                    reset(lyrics ?: Lyrics(isSynced = true))
                } else if (!_dirty.value && _draft.value?.lines.isNullOrEmpty() && lyrics != null) {
                    reset(lyrics)
                }
            }
        }
        // Lyrics kept for the song while nothing has been changed are worth more than an empty page.
        viewModelScope.launch {
            store.results.collect { (trackId, lyrics) ->
                if (trackId == track.value?.id && !_dirty.value) reset(lyrics)
            }
        }
    }

    private fun reset(lyrics: Lyrics) {
        _draft.value = lyrics
        _dirty.value = false
        undo.clear()
        redo.clear()
        _canUndo.value = false
        _canRedo.value = false
        _selectedLine.value = 0
        _selectedWord.value = 0
    }

    fun select(line: Int, word: Int = 0) {
        val (at, w) = LyricsEdit.clamp(_draft.value ?: return, line, word)
        _selectedLine.value = at
        _selectedWord.value = w
    }

    /** One move, undoable. */
    fun apply(change: (Lyrics) -> Lyrics) {
        val before = _draft.value ?: return
        val after = change(before)
        if (after == before) return
        undo.addLast(before)
        if (undo.size > 100) undo.removeFirst()
        redo.clear()
        _draft.value = after
        _dirty.value = true
        _canUndo.value = true
        _canRedo.value = false
        select(_selectedLine.value, _selectedWord.value)
    }

    fun undo() {
        val before = undo.removeLastOrNull() ?: return
        _draft.value?.let { redo.addLast(it) }
        _draft.value = before
        _dirty.value = true
        _canUndo.value = undo.isNotEmpty()
        _canRedo.value = true
        select(_selectedLine.value, _selectedWord.value)
    }

    fun redo() {
        val after = redo.removeLastOrNull() ?: return
        _draft.value?.let { undo.addLast(it) }
        _draft.value = after
        _dirty.value = true
        _canUndo.value = true
        _canRedo.value = redo.isNotEmpty()
        select(_selectedLine.value, _selectedWord.value)
    }

    /**
     * The song's moment, [ms], given to the selected line (or word), and the
     * selection moved on to the next: the classic way to time lyrics, one
     * tap per line as the song plays.
     */
    fun stamp(ms: Long) {
        val lyrics = _draft.value ?: return
        val line = _selectedLine.value
        if (line !in lyrics.lines.indices) return
        if (wordMode.value && lyrics.lines[line].words.isNotEmpty()) {
            val word = _selectedWord.value
            apply { LyricsEdit.setWordTime(it, line, word, ms) }
            if (word + 1 < lyrics.lines[line].words.size) select(line, word + 1)
            else if (line + 1 < lyrics.lines.size) select(line + 1, 0)
        } else {
            apply { LyricsEdit.setLineTime(it, line, ms) }
            if (line + 1 < lyrics.lines.size) select(line + 1, 0)
        }
    }

    fun nudgeSelected(deltaMs: Long) {
        val line = _selectedLine.value
        if (wordMode.value) {
            val lyrics = _draft.value ?: return
            val word = lyrics.lines.getOrNull(line)?.words?.getOrNull(_selectedWord.value) ?: return
            apply { LyricsEdit.setWordTime(it, line, _selectedWord.value, word.startMs + deltaMs) }
        } else {
            apply { LyricsEdit.nudgeLine(it, line, deltaMs) }
        }
    }

    fun shiftAll(deltaMs: Long) = apply { LyricsEdit.shiftAll(it, deltaMs) }

    fun setText(line: Int, text: String) = apply { LyricsEdit.setText(it, line, text) }

    fun insertAfter(line: Int, text: String, ms: Long) {
        apply { LyricsEdit.insertLine(it, line, text, ms) }
        select(line + 1)
    }

    fun deleteLine(line: Int) {
        apply { LyricsEdit.deleteLine(it, line) }
        select(line.coerceAtMost((_draft.value?.lines?.size ?: 1) - 1))
    }

    fun splitWords(line: Int) {
        val lyrics = _draft.value ?: return
        val end = lyrics.lines.getOrNull(line + 1)?.timeMs ?: (lyrics.lines.getOrNull(line)?.timeMs?.plus(4_000) ?: return)
        apply { LyricsEdit.splitIntoWords(it, line, end) }
        wordMode.value = true
        select(line, 0)
    }

    fun clearWords(line: Int) = apply { LyricsEdit.clearWords(it, line) }

    /** Pasted LRC replaces the copy; pasted plain words replace it untimed, three seconds a line. */
    fun paste(text: String) {
        val parsed = LrcFormat.parse(text) ?: LyricsEdit.fromPlainText(text)
        if (parsed.lines.isEmpty()) return
        apply { parsed }
        select(0)
    }

    fun lrcText(): String? {
        val lyrics = _draft.value?.takeIf { it.lines.isNotEmpty() } ?: return null
        val t = track.value
        return LrcFormat.write(LyricsEdit.sorted(lyrics), t?.title, t?.artist?.name, t?.duration?.takeIf { it > 0 }?.let { it * 1000L })
    }

    fun save() {
        val t = track.value ?: return
        val lyrics = _draft.value?.takeIf { it.lines.isNotEmpty() } ?: run {
            _note.value = EditorNote.NOTHING_TO_SAVE
            return
        }
        viewModelScope.launch {
            val sorted = LyricsEdit.sorted(lyrics)
            store.publish(t.id, sorted, lrcText())
            repository.forgetLyrics(t.id)
            _draft.value = sorted
            _dirty.value = false
            _note.value = EditorNote.SAVED
        }
    }

    /** Drops what was saved for the song, so the next lookup races the catalogues again. */
    fun forgetSaved() {
        val t = track.value ?: return
        viewModelScope.launch {
            store.forget(t.id)
            repository.forgetLyrics(t.id)
            _note.value = EditorNote.FORGOTTEN
        }
    }

    fun clearNote() {
        _note.value = null
    }
}

enum class EditorNote { SAVED, NOTHING_TO_SAVE, FORGOTTEN }
