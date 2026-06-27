package tf.monochrome.android.devedit

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the DevEdit layout. Per-device user edits live in internal
 * storage ([Context.getFilesDir]); when the user has not edited anything, the
 * baseline falls back to a layout bundled with the app (assets), which is the
 * shipped default for all users. Failures are swallowed so a corrupt/missing
 * file simply yields the next fallback.
 */
@Singleton
class DevEditRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val versionFile: File get() = File(context.filesDir, VERSION_FILE_NAME)

    fun load(): DevEditLayout {
        val bundled = loadBundled()

        // 0. Forced default: when the bundled layout's version is newer than the
        //    one this device last applied, push it to EVERYONE — overwriting any
        //    local edits — exactly once. Bump `version` in the asset to re-force.
        if (bundled != null && bundled.version > lastForcedVersion()) {
            save(bundled)
            writeForcedVersion(bundled.version)
            return bundled
        }

        // 1. Per-device user edits take priority once they exist.
        if (file.exists()) {
            runCatching {
                return json.decodeFromString(DevEditLayout.serializer(), file.readText())
            }
        }
        // 2. Otherwise the shipped default.
        if (bundled != null) return bundled
        // 3. Nothing bundled → empty (pure code layout).
        return DevEditLayout()
    }

    private fun loadBundled(): DevEditLayout? = runCatching {
        context.assets.open(DEFAULT_ASSET).bufferedReader().use { reader ->
            json.decodeFromString(DevEditLayout.serializer(), reader.readText())
        }
    }.getOrNull()

    private fun lastForcedVersion(): Int =
        runCatching { versionFile.readText().trim().toInt() }.getOrDefault(0)

    private fun writeForcedVersion(version: Int) {
        runCatching { versionFile.writeText(version.toString()) }
    }

    fun save(layout: DevEditLayout) {
        try {
            file.writeText(json.encodeToString(DevEditLayout.serializer(), layout))
        } catch (_: Exception) {
            // Best-effort; ignore IO failures.
        }
    }

    /** Serialize a layout to pretty JSON (for exporting / bundling as default). */
    fun exportJson(layout: DevEditLayout): String =
        runCatching { json.encodeToString(DevEditLayout.serializer(), layout) }.getOrDefault("{}")

    private companion object {
        const val FILE_NAME = "devedit_layout.json"
        const val VERSION_FILE_NAME = "devedit_forced_version"
        const val DEFAULT_ASSET = "devedit_default_layout.json"
    }
}
