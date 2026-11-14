package tf.monochrome.android.debug

import android.os.Build
import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.auth.SupabaseAuthManager
import javax.inject.Inject
import javax.inject.Singleton

/** One line of the log as the `debug_live_log` table takes it. No defaults: a default is left out of the insert. */
@Serializable
internal data class LiveLogRow(
    val user_id: String,
    val device: String,
    val build: String,
    val logged: String,
    val level: String,
    val tag: String,
    val message: String,
)

/**
 * The app's log, sent as it is written to the listener's own rows of the
 * Supabase table `debug_live_log`, so someone helping them can read it while
 * the fault happens instead of from a file afterwards.
 *
 * It is off until the listener turns it on in the debug log screen, off
 * again at every start of the app, and stops by itself after [RUNS_FOR]: a
 * log is not left leaving the phone because a switch was forgotten. Only
 * what [DebugLogBuffer] holds is sent, so every line has been through
 * [LogRedactor] first. The table lets an account read and write its own
 * lines only, and drops lines older than a day.
 */
@Singleton
class LiveLog @Inject constructor(
    private val buffer: DebugLogBuffer,
    private val authManager: SupabaseAuthManager,
) {
    enum class State { OFF, ON, SIGNED_OUT, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        val uid = authManager.userProfile.value?.id
        if (uid == null) {
            _state.value = State.SIGNED_OUT
            return
        }
        _state.value = State.ON
        // With the log, every moment the app stops drawing: what a stutter looks like from here.
        FrameStallLog.start()
        job = scope.launch {
            _state.value = try { send(uid) } finally { FrameStallLog.stop() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.OFF
    }

    /** Sends until the time is up ([State.OFF]) or the table refuses too often in a row ([State.FAILED]). */
    private suspend fun send(uid: String): State {
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val build = "${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})"
        // From a little before now, so what led up to turning it on is there too.
        var next = (buffer.appended() - LEAD_IN).coerceAtLeast(0)
        var refused = 0
        val until = System.nanoTime() + RUNS_FOR * 1_000_000_000L
        while (System.nanoTime() < until) {
            delay(EVERY_MS)
            val lines = buffer.since(next, BATCH)
            next = lines.next
            val rows = liveLogRows(lines, uid, device, build)
            if (rows.isEmpty()) continue
            val sent = runCatching { authManager.supabase.postgrest[TABLE].insert(rows) }
            if (sent.isSuccess) {
                refused = 0
            } else {
                Log.w(TAG, "a batch was not taken: ${sent.exceptionOrNull()?.message}")
                if (++refused == GIVES_UP_AFTER) return State.FAILED
            }
        }
        return State.OFF
    }

    companion object {
        const val TAG = "LiveLog"
        const val RUNS_FOR = 30 * 60L        // seconds
        private const val TABLE = "debug_live_log"
        private const val EVERY_MS = 2_000L
        private const val BATCH = 300         // lines a send: a burst beyond it waits for the next
        private const val LEAD_IN = 200L
        private const val GIVES_UP_AFTER = 5
    }
}

/**
 * [lines] as rows for the table: not this class's own lines (each send would
 * make more to send), nor the Verbose ones, which are most of a log and
 * least of its use. Lines the buffer dropped before they could be sent are
 * one row saying how many.
 */
internal fun liveLogRows(lines: DebugLogBuffer.Lines, uid: String, device: String, build: String): List<LiveLogRow> {
    val rows = ArrayList<LiveLogRow>(lines.entries.size + 1)
    if (lines.missed > 0) {
        rows += LiveLogRow(uid, device, build, "", "W", LiveLog.TAG, "${lines.missed} lines were written faster than they could be sent")
    }
    for (entry in lines.entries) {
        if (entry.level == 'V' || entry.tag == LiveLog.TAG) continue
        rows += LiveLogRow(uid, device, build, entry.timestamp, entry.level.toString(), entry.tag, entry.message.take(MESSAGE_LIMIT))
    }
    return rows
}

private const val MESSAGE_LIMIT = 4_000
