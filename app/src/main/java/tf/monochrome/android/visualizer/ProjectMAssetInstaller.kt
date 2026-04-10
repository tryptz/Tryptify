package tf.monochrome.android.visualizer

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
    private val assetVersion = "v4"

    fun ensureInstalled(): InstalledProjectMAssets {
        val rootDir = File(context.filesDir, "projectm/$assetVersion")
        val versionFile = File(rootDir, ".asset-version")
        val presetDir = File(rootDir, "presets")
        val textureDir = File(rootDir, "textures")
        val catalogFile = File(rootDir, "catalog.json")

        val needsInstall = !rootDir.exists()
                || versionFile.readTextOrNull() != assetVersion
                || !presetDir.exists()

        if (needsInstall) {
            Log.d(TAG, "Installing projectM assets ($assetVersion)…")
            rootDir.deleteRecursively()
            rootDir.mkdirs()
            copyAssetTree("projectm", rootDir)
            versionFile.writeText(assetVersion)
        }

        presetDir.mkdirs()
        textureDir.mkdirs()

        // Auto-generate catalog from installed presets if missing or stale
        if (!catalogFile.exists() || needsInstall) {
            generateCatalog(presetDir, catalogFile)
        }

        return InstalledProjectMAssets(
            rootDir = rootDir,
            presetDir = presetDir,
            textureDir = textureDir,
            catalogFile = catalogFile,
            version = assetVersion
        )
    }

    /**
     * Walk the preset directory and build catalog.json from the discovered .milk files.
     * Each preset gets an id derived from its relative path, a human-readable display name
     * parsed from the filename, and tags inferred from the parent folder hierarchy.
     */
    private fun generateCatalog(presetDir: File, catalogFile: File) {
        val presets = mutableListOf<VisualizerPreset>()
        val rootPath = presetDir.absolutePath

        val usedIds = mutableSetOf<String>()

        presetDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("milk", ignoreCase = true) }
            .sortedBy { it.absolutePath }
            .forEach { file ->
                val relativePath = file.absolutePath
                    .removePrefix(rootPath)
                    .trimStart(File.separatorChar, '/')
                    .replace(File.separatorChar, '/')

                // Build a stable id from the relative path
                val baseId = "preset:" + relativePath
                    .removeSuffix(".milk")
                    .lowercase()
                    .replace(Regex("[^a-z0-9/]"), "_")
                    .replace(Regex("_+"), "_")
                    .trimEnd('_')

                // Ensure ID is unique by appending a counter if needed
                var id = baseId
                var counter = 1
                while (usedIds.contains(id)) {
                    id = "${baseId}_${counter++}"
                }
                usedIds.add(id)

                // Human-readable name from filename
                val displayName = file.nameWithoutExtension
                    .replace("_", " ")
                    .trim()

                // Tags from parent folder(s) relative to presetDir
                val tagParts = relativePath.split("/").dropLast(1)
                val tags = tagParts.map { folder ->
                    VisualizerTag(
                        id = folder.lowercase().replace(Regex("[^a-z0-9]"), "_"),
                        label = folder
                    )
                }

                presets.add(
                    VisualizerPreset(
                        id = id,
                        displayName = displayName,
                        filePath = "presets/$relativePath",
                        tags = tags,
                        intensity = 50
                    )
                )
            }

        val json = Json { prettyPrint = true }
        catalogFile.writeText(json.encodeToString(presets))
        Log.d(TAG, "Generated catalog.json with ${presets.size} presets")
    }

    private fun copyAssetTree(assetPath: String, outputDir: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            outputDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                outputDir.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        outputDir.mkdirs()
        entries.forEach { child ->
            val childAssetPath = if (assetPath.isBlank()) child else "$assetPath/$child"
            copyAssetTree(childAssetPath, File(outputDir, child))
        }
    }

    private fun File.readTextOrNull(): String? = if (exists()) readText() else null

    companion object {
        private const val TAG = "ProjectMAssetInstaller"
    }
}
