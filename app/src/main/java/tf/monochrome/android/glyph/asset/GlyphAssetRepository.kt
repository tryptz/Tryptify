// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.asset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.PlayerGlassSettings

/**
 * Loads the Glyph pack and hands gameplay ready-made bitmaps.
 *
 * The contract the playfield relies on is narrow and worth stating: after
 * [prewarm] returns, every id it was given can be answered by [image] from a
 * map lookup, with no I/O, no parsing and no allocation. That is what makes a
 * dense chart affordable at 165 Hz — the expensive half of drawing an SVG has
 * already happened by the time the first note is on screen.
 *
 * Parsed vectors are cached separately from rasterized bitmaps because the two
 * have different lifetimes: a vector is small and worth keeping for the whole
 * process, while the bitmaps for a 96 px lane are worthless once the player
 * rotates the device and the lane becomes 120 px.
 */
@Singleton
class GlyphAssetRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    private val vectors = ConcurrentHashMap<String, GlyphVector>()
    private val images = ConcurrentHashMap<ImageKey, ImageBitmap>()
    private val manifestLock = Mutex()

    @Volatile
    private var manifest: GlyphManifest? = null

    /** Assets that failed to load, so a broken file is reported once, not per frame. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private data class ImageKey(
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
        val tintArgb: Int,
    )

    suspend fun manifest(): GlyphManifest? = manifest ?: manifestLock.withLock {
        manifest ?: withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("$ROOT/manifest.json").use { stream ->
                    json.decodeFromString<GlyphManifest>(stream.readBytes().decodeToString())
                }
            }.onFailure {
                Log.w(TAG, "Glyph manifest could not be read: ${it.message}")
            }.getOrNull()
        }.also { manifest = it }
    }

    /**
     * The pack's palette, or a built-in copy if the manifest is unreadable.
     *
     * The fallback exists so a corrupt manifest degrades the mode's colours
     * rather than stopping it from starting; the values match the shipped pack
     * and `GlyphAssetCatalogTest` pins them to it.
     */
    suspend fun palette(): GlyphPalette = manifest()?.palette ?: FALLBACK_PALETTE

    /**
     * Parse and rasterize [ids] at [widthPx] × [heightPx].
     *
     * Failures are collected, not thrown: one unreadable file should cost that
     * one glyph, not the session. The count of failures is returned so a caller
     * can tell the difference between "the pack is fine" and "the pack is
     * half-missing" without inspecting every id.
     */
    suspend fun prewarm(
        ids: List<GlyphAssetId>,
        widthPx: Int,
        heightPx: Int,
        tint: Color? = null,
    ): PrewarmResult = withContext(Dispatchers.Default) {
        var loaded = 0
        val missing = ArrayList<String>()
        for (id in ids) {
            currentCoroutineContext().ensureActive()
            if (load(id, widthPx, heightPx, tint) != null) loaded += 1 else missing += id.name
        }
        PrewarmResult(loaded = loaded, missing = missing)
    }

    data class PrewarmResult(val loaded: Int, val missing: List<String>) {
        val allPresent: Boolean get() = missing.isEmpty()
    }

    /**
     * A prewarmed bitmap, or null.
     *
     * Null is a legitimate answer and every call site handles it by drawing a
     * shape-only fallback. A missing asset must never take playback down with
     * it — the audio is still playing and the notes are still scoreable.
     */
    fun image(
        id: GlyphAssetId,
        widthPx: Int,
        heightPx: Int,
        tint: Color? = null,
    ): ImageBitmap? = images[ImageKey(id.name, widthPx, heightPx, tint.argbKey())]

    /**
     * Fetch, rasterizing on the spot if it is not already warm.
     *
     * For menus, never for the playfield: this can open a file and parse XML,
     * which is exactly what gameplay must not do between frames.
     */
    suspend fun load(
        id: GlyphAssetId,
        widthPx: Int,
        heightPx: Int,
        tint: Color? = null,
    ): ImageBitmap? {
        val key = ImageKey(id.name, widthPx, heightPx, tint.argbKey())
        images[key]?.let { return it }
        val generation = skinGeneration

        // The skin's own image, then the vector pack's, then the same two for
        // whatever the catalog says stands in for this name.
        val drawn = render(id, widthPx, heightPx, tint)
            ?: GlyphAssetCatalog.fallback(id)?.let { render(it, widthPx, heightPx, tint) }
            ?: return null
        // A skin change while this was being drawn makes it stale; it is
        // returned for this call but not kept.
        if (generation == skinGeneration) images[key] = drawn
        return drawn
    }

    private suspend fun render(
        id: GlyphAssetId,
        widthPx: Int,
        heightPx: Int,
        tint: Color?,
    ): ImageBitmap? {
        skinImage(id, widthPx, heightPx)?.let { return it }
        if (id.name in failed) return null
        // Asked before vector(), which treats a missing name as a broken pack:
        // a name only some skins provide is expected to be missing here.
        if (manifest()?.asset(id.name) == null) return null
        val vector = vector(id) ?: return null
        return runCatching {
            GlyphRasterizer.rasterize(vector, widthPx, heightPx, tint)
        }.onFailure { failure ->
            if (failed.add(id.name)) {
                Log.w(TAG, "Glyph '${id.name}' could not be rasterized: ${failure.message}")
            }
        }.getOrNull()
    }

    // ── skins ───────────────────────────────────────────────────────────

    @Volatile
    var skin: GlyphSkin = GlyphSkin.DEFAULT
        private set

    @Volatile
    private var glassLook: GlyphGlassLook? = null

    @Volatile
    private var skinGeneration = 0

    private val skinManifests = ConcurrentHashMap<String, GlyphSkinManifest>()
    private val skinSources = ConcurrentHashMap<String, Bitmap>()
    private val missingSkins = ConcurrentHashMap.newKeySet<String>()

    /**
     * Draw with [skin], its glass taken from [glassSettings] (the UI-panel
     * glass). Returns true when that changes what is drawn, so the caller warms
     * the playfield again; the old rasters are dropped here.
     */
    fun configure(skin: GlyphSkin, glassSettings: PlayerGlassSettings): Boolean {
        val look = if (skin.isGlass) GlyphGlassLook.from(glassSettings) else null
        if (skin == this.skin && look == glassLook) return false
        this.skin = skin
        glassLook = look
        skinGeneration += 1
        images.clear()
        return true
    }

    private suspend fun skinManifest(root: String): GlyphSkinManifest? {
        skinManifests[root]?.let { return it }
        if (root in missingSkins) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("$root/manifest.json").use { stream ->
                    json.decodeFromString<GlyphSkinManifest>(stream.readBytes().decodeToString())
                }
            }.onFailure {
                if (missingSkins.add(root)) Log.w(TAG, "Skin '$root' could not be read: ${it.message}")
            }.getOrNull()?.also { skinManifests[root] = it }
        }
    }

    private suspend fun skinImage(id: GlyphAssetId, widthPx: Int, heightPx: Int): ImageBitmap? {
        val root = skin.root ?: return null
        val manifest = skinManifest(root) ?: return null
        val entry = manifest.asset(id.name) ?: return null
        val source = skinSource(root, entry) ?: return null
        val look = glassLook
        return withContext(Dispatchers.Default) {
            // Effects are light, not a surface; they are drawn as they come.
            val prepared = if (look != null && !entry.category.startsWith("feedback/")) {
                glassed(source, look, tile = isTiledBody(entry.name), openBottom = isEndCap(entry.name))
            } else {
                source
            }
            scaled(prepared, widthPx, heightPx, manifest.pixelArt).asImageBitmap()
        }
    }

    private suspend fun skinSource(root: String, entry: GlyphSkinAsset): Bitmap? {
        val path = "$root/${entry.path}"
        skinSources[path]?.let { return it }
        if (path in failed) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(path).use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }) ?: error("not a decodable image")
                }
            }.onFailure {
                if (failed.add(path)) Log.w(TAG, "Skin image '$path' is unusable: ${it.message}")
            }.getOrNull()?.also { skinSources[path] = it }
        }
    }

    private fun glassed(source: Bitmap, look: GlyphGlassLook, tile: Boolean, openBottom: Boolean): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val glass = GlyphGlass.apply(pixels, w, h, look, tileVertically = tile, openBottom = openBottom)
        return Bitmap.createBitmap(glass, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Pixel art is enlarged by whole pixels first, then smoothed the last
     * fraction of the way, so a 64-pixel arrow at 210 pixels keeps square
     * pixels without the uneven ones a straight nearest-neighbour stretch to
     * a non-multiple leaves.
     */
    private fun scaled(source: Bitmap, widthPx: Int, heightPx: Int, pixelArt: Boolean): Bitmap {
        if (source.width == widthPx && source.height == heightPx) return source
        if (!pixelArt) return Bitmap.createScaledBitmap(source, widthPx, heightPx, true)
        val factor = maxOf(
            1,
            minOf(widthPx / source.width, heightPx / source.height),
        )
        val crisp = if (factor > 1) {
            Bitmap.createScaledBitmap(source, source.width * factor, source.height * factor, false)
        } else {
            source
        }
        return if (crisp.width == widthPx && crisp.height == heightPx) {
            crisp
        } else {
            Bitmap.createScaledBitmap(crisp, widthPx, heightPx, true)
        }
    }

    private fun isTiledBody(name: String): Boolean =
        name.startsWith("hold_body") || name.startsWith("roll_body")

    private fun isEndCap(name: String): Boolean =
        name.startsWith("hold_tail") || name.startsWith("roll_tail")

    /** The parsed vector, cached for the process. Null when the pack is broken. */
    suspend fun vector(id: GlyphAssetId): GlyphVector? {
        vectors[id.name]?.let { return it }
        if (id.name in failed) return null

        val entry = manifest()?.asset(id.name)
        if (entry == null) {
            if (failed.add(id.name)) {
                Log.w(TAG, "Glyph '${id.name}' is not in the manifest")
            }
            return null
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("$ROOT/${entry.path}").use(GlyphSvgParser::parse)
            }.onFailure { failure ->
                if (failed.add(id.name)) {
                    val reason = when (failure) {
                        is IOException -> "missing from the APK"
                        else -> failure.message ?: failure::class.java.simpleName
                    }
                    Log.w(TAG, "Glyph '${id.name}' (${entry.path}) is unusable: $reason")
                }
            }.getOrNull()?.also { vectors[id.name] = it }
        }
    }

    /**
     * Drop rasterized bitmaps, keeping parsed vectors.
     *
     * Called when the playfield's lane width changes. Re-rasterizing is cheap
     * next to re-parsing, and keeping stale sizes around is how a rotation leak
     * turns into an OutOfMemoryError three songs later.
     */
    fun releaseImages() {
        images.clear()
    }

    private fun Color?.argbKey(): Int =
        this?.let { (it.value shr 32).toInt() } ?: NO_TINT

    companion object {
        private const val TAG = "GlyphAssets"
        const val ROOT = "stepmania/glyph"

        // A sentinel outside the ARGB range, so "no tint" cannot collide with a
        // real colour the way 0 (transparent black) would.
        private const val NO_TINT = 1

        val FALLBACK_PALETTE = GlyphPalette(
            ink = "#0B1020",
            paper = "#F8FAFF",
            muted = "#78839C",
            beatColors = mapOf(
                "4th" to "#FF5F6D",
                "8th" to "#58D9FF",
                "12th" to "#A77BFF",
                "16th" to "#FFD95A",
                "24th" to "#FF74C8",
                "32nd" to "#FF9659",
                "48th" to "#52E6D8",
                "64th" to "#63F2A2",
            ),
            laneAccents = mapOf(
                "left" to "#FF74C8",
                "down" to "#58D9FF",
                "up" to "#63F2A2",
                "right" to "#FFD95A",
            ),
        )
    }
}
