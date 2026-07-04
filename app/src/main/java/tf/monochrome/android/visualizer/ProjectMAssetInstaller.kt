package tf.monochrome.android.visualizer

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.domain.model.VisualizerTag

data class InstalledProjectMAssets(
    val rootDir: File,
    val presetDir: File,
    val textureDir: File,
    val catalogFile: File,
    val version: String
)

@Singleton
class ProjectMAssetInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val assetVersion = "v5"

    fun ensureInstalled(): InstalledProjectMAssets {
        val baseDir = File(context.filesDir, "projectm")
        val rootDir = File(baseDir, assetVersion)
        val versionFile = File(rootDir, ".asset-version")
        val presetDir = File(rootDir, "presets")
        val textureDir = File(rootDir, "textures")
        val catalogFile = File(rootDir, "catalog.json")

        val needsInstall = versionFile.readTextOrNull() != assetVersion
                || !presetDir.exists()
                || !catalogFile.exists()

        if (needsInstall) {
            Log.d(TAG, "Installing projectM assets ($assetVersion)…")
            val startMs = System.currentTimeMillis()
            rootDir.deleteRecursively()
            presetDir.mkdirs()
            val relativePaths = extractPresetZip(presetDir)
            writeCatalog(relativePaths.sorted(), catalogFile)
            // Marker last: a process kill mid-install leaves the version file
            // absent, so the next ensureInstalled() redoes the install cleanly.
            versionFile.writeText(assetVersion)
            cleanupStaleVersions(baseDir)
            Log.d(
                TAG,
                "Installed ${relativePaths.size} presets in ${System.currentTimeMillis() - startMs} ms"
            )
        }

        textureDir.mkdirs()

        return InstalledProjectMAssets(
            rootDir = rootDir,
            presetDir = presetDir,
            textureDir = textureDir,
            catalogFile = catalogFile,
            version = assetVersion
        )
    }

    /**
     * Extract the bundled preset archive (a single stored-in-APK zip built at
     * compile time) into [presetDir] in one streaming pass. Returns the
     * preset-relative paths of every extracted file. Vastly faster than the
     * old per-file AssetManager copy of ~10k individually compressed assets.
     */
    private fun extractPresetZip(presetDir: File): List<String> {
        val relativePaths = mutableListOf<String>()
        ZipInputStream(BufferedInputStream(context.assets.open(PRESET_ZIP_ASSET))).use { zip ->
            val buffer = ByteArray(64 * 1024)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && !name.split('/').contains("..")) {
                    val outFile = File(presetDir, name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        var read = zip.read(buffer)
                        while (read >= 0) {
                            output.write(buffer, 0, read)
                            read = zip.read(buffer)
                        }
                    }
                    relativePaths.add(name)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return relativePaths
    }

    /**
     * Build catalog.json from the extracted entries' relative paths. Each preset
     * gets an id derived from its relative path, a human-readable display name
     * parsed from the filename, and tags inferred from the parent folder
     * hierarchy. Input must be sorted so ids (including dedup counters) stay
     * stable across installs and asset-version bumps.
     */
    private fun writeCatalog(relativePaths: List<String>, catalogFile: File) {
        val usedIds = mutableSetOf<String>()
        val presets = relativePaths
            .filter { it.endsWith(".milk", ignoreCase = true) }
            .map { relativePath ->
                val baseId = "preset:" + relativePath
                    .removeSuffix(".milk")
                    .lowercase()
                    .replace(Regex("[^a-z0-9/]"), "_")
                    .replace(Regex("_+"), "_")
                    .trimEnd('_')

                var id = baseId
                var counter = 1
                while (usedIds.contains(id)) {
                    id = "${baseId}_${counter++}"
                }
                usedIds.add(id)

                val displayName = relativePath
                    .substringAfterLast('/')
                    .substringBeforeLast('.')
                    .replace("_", " ")
                    .trim()

                val tags = relativePath.split("/").dropLast(1).map { folder ->
                    VisualizerTag(
                        id = folder.lowercase().replace(Regex("[^a-z0-9]"), "_"),
                        label = folder
                    )
                }

                VisualizerPreset(
                    id = id,
                    displayName = displayName,
                    filePath = "presets/$relativePath",
                    tags = tags,
                    intensity = 50
                )
            }

        catalogFile.writeText(Json.encodeToString(presets))
        Log.d(TAG, "Generated catalog.json with ${presets.size} presets")
    }

    /**
     * Reclaim storage held by previous asset versions (e.g. the v4 tree copied
     * file-by-file by older builds). Runs after a successful install so an
     * interrupted upgrade never deletes the only working copy.
     */
    private fun cleanupStaleVersions(baseDir: File) {
        baseDir.listFiles()
            ?.filter { it.isDirectory && it.name != assetVersion }
            ?.forEach { stale ->
                Log.d(TAG, "Removing stale projectM assets: ${stale.name}")
                stale.deleteRecursively()
            }
    }

    private fun File.readTextOrNull(): String? = if (exists()) readText() else null

    companion object {
        private const val TAG = "ProjectMAssetInstaller"
        private const val PRESET_ZIP_ASSET = "projectm/presets.zip"
    }
}
