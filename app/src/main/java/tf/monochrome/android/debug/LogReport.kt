package tf.monochrome.android.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.data.auth.SupabaseAuthManager
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One report as the `log_reports` table takes it (docs/supabase/log_reports.sql). No defaults: a default is left out of the insert. */
@Serializable
internal data class LogReportRow(
    val user_id: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val android_release: String,
    val android_sdk: Int,
    val app_version: String,
    val app_version_code: Int,
    val git_commit: String,
    val note: String,
    val context: JsonObject,
    val previous_exits: JsonArray,
    val log: String,
)

/**
 * Settings › System › Send logs: the app's log, sent once to the developer's
 * Supabase table `log_reports`, with the phone, the build and how the last
 * processes ended. A crash is not in the log, which starts with this
 * process, so the last crash ([CrashLogger.lastCrash]) and the system's
 * record of the last exits go with it.
 *
 * Nothing is sent until the listener has read what leaves the phone and
 * pressed Send, and never by itself. It needs a sign-in: the table takes a
 * report only from an account, ten a day. Only what [DebugLogBuffer] holds is
 * sent, so every line has been through [LogRedactor]; the rest goes through
 * it here. The table analyses the log as it arrives; nothing is read back.
 */
@Singleton
class LogReport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val buffer: DebugLogBuffer,
    private val authManager: SupabaseAuthManager,
    private val crashLogger: CrashLogger,
    private val http: HttpClient,
) {
    enum class State { IDLE, SENDING, SENT, SIGNED_OUT, TOO_MANY, FAILED }

    // Its own scope: a send carries on when the settings screen is left.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    fun signedIn(): Boolean = authManager.userProfile.value?.id != null

    /** Sends the log with what the listener wrote about the fault; [state] says how it went. */
    fun send(note: String) {
        if (_state.value == State.SENDING) return
        val uid = authManager.userProfile.value?.id
        if (uid == null) {
            _state.value = State.SIGNED_OUT
            return
        }
        _state.value = State.SENDING
        scope.launch {
            _state.value = runCatching { post(row(uid, note)) }.getOrElse {
                Log.w(TAG, "the log was not sent: ${it.message}")
                State.FAILED
            }
        }
    }

    private fun row(uid: String, note: String) = LogReportRow(
        user_id = uid,
        manufacturer = Build.MANUFACTURER.orEmpty().take(100),
        model = Build.MODEL.orEmpty().take(100),
        device = Build.DEVICE.orEmpty().take(100),
        android_release = Build.VERSION.RELEASE.orEmpty().take(40),
        android_sdk = Build.VERSION.SDK_INT,
        app_version = BuildConfig.VERSION_NAME,
        app_version_code = BuildConfig.VERSION_CODE,
        git_commit = BuildConfig.GIT_COMMIT.take(64),
        note = logReportNote(note),
        context = context(),
        previous_exits = exits(),
        log = logReportLog(buffer.snapshot()),
    )

    private fun context(): JsonObject = buildJsonObject {
        put("locale", Locale.getDefault().toLanguageTag())
        put("abis", Build.SUPPORTED_ABIS.joinToString(","))
        put("product", Build.PRODUCT.orEmpty())
        runCatching {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
            put("ram_mb", memory.totalMem / (1024 * 1024))
        }
        // Where the app was when the process before this one ended.
        putJsonObject("previous_crumbs") {
            crashLogger.previousCrumbs().forEach { (key, value) -> put(key.take(40), value.sendable(200)) }
        }
        crashLogger.lastCrash()?.let { crash ->
            putJsonObject("last_crash") {
                put("at_ms", crash.atMs)
                put("thread", crash.thread.sendable(100))
                put("stack", crash.stack.sendable(LAST_CRASH_CHARS))
            }
        }
    }

    /**
     * How the last few processes ended, newest first, as the system remembers
     * it (Android 11+). The newest native crash or freeze carries its trace:
     * a tombstone's readable strings, or the ANR's thread dump.
     */
    private fun exits(): JsonArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return JsonArray(emptyList())
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, EXITS)
        }.getOrNull().orEmpty()
        var traced = false
        return buildJsonArray {
            for (exit in exits) {
                addJsonObject {
                    put("at_ms", exit.timestamp)
                    put("reason", exitReasonName(exit.reason))
                    put("status", exit.status)
                    put("description", exit.description.orEmpty().sendable(500))
                    val crashed = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                        exit.reason == ApplicationExitInfo.REASON_ANR
                    if (crashed && !traced) {
                        traced = true
                        val bytes = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
                        if (bytes != null) {
                            val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR) String(bytes, Charsets.UTF_8)
                                else crashLogger.readableStrings(bytes).joinToString("\n")
                            put("trace", trace.sendable(TRACE_CHARS))
                        }
                    }
                }
            }
        }
    }

    /** One insert through the table's REST address, with the time a slow uplink needs for a megabyte or two. */
    private suspend fun post(row: LogReportRow): State {
        val supabase = authManager.supabase
        val body = TextContent(Json.encodeToString(LogReportRow.serializer(), row), ContentType.Application.Json)
        suspend fun attempt(token: String): Int = try {
            http.post("${supabase.supabaseHttpUrl}/rest/v1/$TABLE") {
                header("apikey", supabase.supabaseKey)
                bearerAuth(token)
                header("Prefer", "return=minimal")
                setBody(body)
                timeout { requestTimeoutMillis = TIMEOUT_MS; socketTimeoutMillis = TIMEOUT_MS }
            }.status.value
        } catch (e: ResponseException) {
            e.response.status.value
        }
        var status = attempt(supabase.auth.currentAccessTokenOrNull() ?: return State.SIGNED_OUT)
        if (status == 401) {
            // The session ran out while the phone slept: a new one, once.
            supabase.auth.refreshCurrentSession()
            status = attempt(supabase.auth.currentAccessTokenOrNull() ?: return State.SIGNED_OUT)
        }
        return when (status) {
            in 200..299 -> State.SENT
            429 -> State.TOO_MANY
            else -> {
                Log.w(TAG, "the log was not taken: HTTP $status")
                State.FAILED
            }
        }
    }

    companion object {
        const val TAG = "LogReport"
        private const val TABLE = "log_reports"
        private const val TIMEOUT_MS = 180_000L
        private const val EXITS = 5
        private const val TRACE_CHARS = 60_000
        const val LAST_CRASH_CHARS = 16_000
    }
}

/** The most of the log that is sent, in characters; the table takes a little more. */
internal const val LOG_REPORT_LIMIT = 2_000_000
private const val NOTE_LIMIT = 2_000

/** Text as it may leave the phone: no sign-in, no NUL (Postgres takes none in text), no longer than [limit]. */
private fun String.sendable(limit: Int): String = LogRedactor.redact(this).replace("\u0000", "").take(limit)

/** What the listener wrote, as it is sent: a pasted address may carry a sign-in. */
internal fun logReportNote(note: String): String = note.trim().sendable(NOTE_LIMIT)

/**
 * [entries] as the text that is sent: the lines as logcat printed them,
 * newest kept when they are more than [limit] characters, with a first line
 * saying how many older ones were left out.
 */
internal fun logReportLog(entries: List<DebugLogEntry>, limit: Int = LOG_REPORT_LIMIT): String {
    var size = 0
    var first = entries.size
    while (first > 0) {
        val length = entries[first - 1].raw.length + 1
        if (size + length > limit) break
        size += length
        first--
    }
    val text = StringBuilder(size + 80)
    if (first > 0) text.append("($first earlier lines left out: the log was too long to send whole)\n")
    for (i in first until entries.size) text.append(entries[i].raw).append('\n')
    return text.toString().replace("\u0000", "")
}

/** [ApplicationExitInfo]'s reason as the name the table's analysis reads. */
internal fun exitReasonName(reason: Int): String = when (reason) {
    1 -> "EXIT_SELF"
    2 -> "SIGNALED"
    3 -> "LOW_MEMORY"
    4 -> "CRASH"
    5 -> "CRASH_NATIVE"
    6 -> "ANR"
    7 -> "INITIALIZATION_FAILURE"
    8 -> "PERMISSION_CHANGE"
    9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"
    12 -> "DEPENDENCY_DIED"
    13 -> "OTHER"
    14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"
    16 -> "PACKAGE_UPDATED"
    else -> "UNKNOWN_$reason"
}
