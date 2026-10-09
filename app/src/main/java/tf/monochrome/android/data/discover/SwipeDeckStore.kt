package tf.monochrome.android.data.discover

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.DeckCard
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Today's swipe deck, where the listener is in it, and what they skipped.
 *
 * A file of its own rather than preferences: the deck is fifteen whole tracks
 * and changes on every swipe, and the preferences file is read and rewritten
 * whole on every setting in the app. In `filesDir`, not the cache directory —
 * the skips are a promise ("not for thirty days"), and a cache the system may
 * clear would break it.
 */
@Singleton
class SwipeDeckStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class State(
        /** The epoch day this stack was dealt for; -1 before the first. */
        val day: Long = -1,
        /** The service it was dealt from, as an ApiService name. */
        val service: String = "",
        val cards: List<DeckCard> = emptyList(),
        /** Index of the card on top; equal to the size once the stack is done. */
        val position: Int = 0,
        /** Ids of the tracks kept today, across every round. */
        val kept: List<String> = emptyList(),
        /** Keys of every card dealt today, so "go again" deals new ones. */
        val dealtToday: List<String> = emptyList(),
        /** How many stacks have been dealt today. */
        val round: Int = 0,
        /** Song key to the epoch day it was skipped. */
        val skipped: Map<String, Long> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<State?>(null)

    /** The stored deck; null until first read. */
    val state: StateFlow<State?> = _state.asStateFlow()

    suspend fun load(): State = mutex.withLock {
        _state.value ?: readFile().also { _state.value = it }
    }

    suspend fun update(change: (State) -> State): State = mutex.withLock {
        val next = change(_state.value ?: readFile())
        _state.value = next
        writeFile(next)
        next
    }

    private suspend fun readFile(): State = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) State() else json.decodeFromString(State.serializer(), file.readText())
        }.getOrDefault(State())
    }

    private suspend fun writeFile(state: State) = withContext(Dispatchers.IO) {
        // Through a temporary and a rename: a half-written file would read as
        // an empty deck and lose every skip with it.
        runCatching {
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(State.serializer(), state))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private companion object {
        const val FILE_NAME = "discover-deck.json"
    }
}
