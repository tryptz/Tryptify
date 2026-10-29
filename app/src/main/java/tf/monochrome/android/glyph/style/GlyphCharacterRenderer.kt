// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A StepMania character dancing, drawn off the game's frame: a loop on a
 * background thread poses it ([CharacterPose]) [FPS] times a second and
 * paints its textured triangles, far to near, into a small picture of its
 * own ([WIDTH]×[HEIGHT]), three of them taken in turn. The playfield only ever
 * draws the finished picture, one bitmap, so a character costs the field's
 * frame nothing however many triangles it has.
 *
 * The dances are the character's own or the shared ones it names (found by
 * [sharedDance]); one is picked at random, played through once, then another,
 * faster or slower with the song's tempo ([tempo]), as StepMania's dancers
 * keep time. Nothing is drawn while [playing] is false: the picture holds.
 */
class GlyphCharacterRenderer(
    private val characterDir: File,
    private val sharedDance: (String) -> File?,
    private val scope: CoroutineScope,
    /** The song's tempo now, in BPM; 0 when unknown. */
    private val tempo: () -> Float,
    /** Whether the run is moving: the dancer stops with it. */
    private val playing: () -> Boolean,
) {
    // Three, so the one being drawn is never the one the screen is reading.
    private val buffers = Array(BUFFERS) { Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888) }
    private var drawing = 0

    private val _frame = MutableStateFlow<Bitmap?>(null)
    /** The last finished picture; null until the first. Read on the main thread. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val scene = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } ?: return@launch
            var dance = scene.dances.randomOrNull()
            var frameAt = 1f
            var last = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.25f)
                last = now
                if (playing() || _frame.value == null) {
                    if (dance != null) {
                        val bpm = tempo().takeIf { it > 0f } ?: REFERENCE_BPM
                        frameAt += dt * ANIMATION_FPS * (bpm / REFERENCE_BPM).coerceIn(MIN_RATE, MAX_RATE)
                        if (frameAt > dance.length) {
                            frameAt = 1f
                            dance = scene.dances.randomOrNull()
                        }
                    }
                    render(scene, dance, frameAt)
                }
                delay(1000L / FPS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // ── the scene ───────────────────────────────────────────────────────

    private class Dance(val bones: Array<Milkshape.Bone?>, val length: Float)

    private class Scene(
        val model: Milkshape.Model,
        val pose: CharacterPose,
        val dances: List<Dance>,
        val paints: Array<Paint?>,
        val textureSizes: Array<IntArray?>,
        val positions: FloatArray,
        /** Every triangle across the meshes: its three vertices (counted across meshes) and its material. */
        val triangles: IntArray,
        val triangleMaterial: IntArray,
        /** Every vertex's texture coordinate, across meshes. */
        val uvs: FloatArray,
        /** The posed model's middle and size at rest, so it is framed once and stays still. */
        val centreX: Float, val centreY: Float, val scale: Float,
    )

    private fun load(): Scene? {
        val model = Milkshape.parse(File(characterDir, StepManiaStyleBaker.MODEL).readText())
        val pose = CharacterPose(model)
        val moves = runCatching {
            JSON.decodeFromString<StepManiaStyleBaker.CharacterMoves>(File(characterDir, StepManiaStyleBaker.MOVES_INFO).readText()).moves
        }.getOrDefault(emptyMap())
        // Dancing during a run; the warm-up or the rest only when it has no dance.
        val names = moves["dance"].orEmpty().ifEmpty { moves["warmup"].orEmpty() }.ifEmpty { moves["rest"].orEmpty() }
        val dances = names.distinct().take(MAX_DANCES).mapNotNull { name ->
            val file = if (name.startsWith(StepManiaStyleBaker.OWN_PREFIX)) {
                File(characterDir, "${StepManiaStyleBaker.MOVES}/${name.removePrefix(StepManiaStyleBaker.OWN_PREFIX)}")
            } else {
                sharedDance(name)
            } ?: return@mapNotNull null
            val parsed = runCatching { Milkshape.parse(file.readText()) }.getOrNull() ?: return@mapNotNull null
            Dance(pose.bind(parsed), parsed.length.coerceAtLeast(1f))
        }
        val paints = arrayOfNulls<Paint>(model.materials.size)
        val sizes = arrayOfNulls<IntArray>(model.materials.size)
        model.materials.forEachIndexed { i, material ->
            val name = material.texture.substringAfterLast('/').substringBeforeLast('.') + ".png"
            val bitmap = File(characterDir, "${StepManiaStyleBaker.TEXTURES}/$name").takeIf { it.isFile }
                ?.let { BitmapFactory.decodeFile(it.path) } ?: return@forEachIndexed
            paints[i] = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                alpha = (material.alpha * 255).toInt()
            }
            sizes[i] = intArrayOf(bitmap.width, bitmap.height)
        }
        // Framed on the first dance's first frame (the rest pose can be folded up).
        val positions = FloatArray(pose.vertexCount * 3)
        pose.pose(dances.firstOrNull()?.bones, 1f, positions)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (v in 0 until pose.vertexCount) {
            minX = minOf(minX, positions[v * 3]); maxX = maxOf(maxX, positions[v * 3])
            minY = minOf(minY, positions[v * 3 + 1]); maxY = maxOf(maxY, positions[v * 3 + 1])
        }
        if (maxY <= minY) return null
        val scale = minOf(HEIGHT * FILL / (maxY - minY), WIDTH * FILL / (maxX - minX).coerceAtLeast(0.001f))
        val triangleCount = model.meshes.sumOf { it.triangles.size / 3 }
        val triangles = IntArray(triangleCount * 3)
        val triangleMaterial = IntArray(triangleCount)
        val uvs = FloatArray(pose.vertexCount * 2)
        var t = 0
        var base = 0
        for (mesh in model.meshes) {
            mesh.uvs.copyInto(uvs, base * 2)
            for (i in 0 until mesh.triangles.size / 3) {
                for (k in 0..2) triangles[t * 3 + k] = base + mesh.triangles[i * 3 + k]
                triangleMaterial[t] = mesh.material
                t += 1
            }
            base += mesh.vertexCount
        }
        return Scene(model, pose, dances, paints, sizes, positions, triangles, triangleMaterial, uvs, (minX + maxX) / 2, (minY + maxY) / 2, scale)
    }

    // ── drawing ─────────────────────────────────────────────────────────

    private var order = IntArray(0)
    private var depths = FloatArray(0)
    private var screen = FloatArray(0)
    private val triVerts = FloatArray(6)
    private val triTex = FloatArray(6)

    private fun render(scene: Scene, dance: Dance?, frameAt: Float) {
        val pose = scene.pose
        pose.pose(dance?.bones, frameAt, scene.positions)
        val p = scene.positions
        if (screen.size < pose.vertexCount * 2) screen = FloatArray(pose.vertexCount * 2)
        // Characters face MilkShape's -Z: turned to face the player (x and z mirrored).
        for (v in 0 until pose.vertexCount) {
            screen[v * 2] = WIDTH / 2f - (p[v * 3] - scene.centreX) * scene.scale
            screen[v * 2 + 1] = HEIGHT / 2f - (p[v * 3 + 1] - scene.centreY) * scene.scale
        }
        val triangles = scene.triangleMaterial.size
        if (order.size != triangles) {
            order = IntArray(triangles) { it }
            depths = FloatArray(triangles)
        }
        // Far to near: the deepest triangle first. Deeper is +z once turned round.
        val tri = scene.triangles
        for (i in 0 until triangles) {
            depths[i] = p[tri[i * 3] * 3 + 2] + p[tri[i * 3 + 1] * 3 + 2] + p[tri[i * 3 + 2] * 3 + 2]
        }
        sortByDepth(triangles)

        val bitmap = buffers[drawing]
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (k in 0 until triangles) drawTriangle(canvas, scene, order[k])
        _frame.value = bitmap
        drawing = (drawing + 1) % BUFFERS
    }

    /** Triangle [index], textured by its material, flat grey without one. */
    private fun drawTriangle(canvas: Canvas, scene: Scene, index: Int) {
        val material = scene.triangleMaterial[index]
        val paint = scene.paints.getOrNull(material) ?: FALLBACK
        val size = scene.textureSizes.getOrNull(material)
        for (k in 0..2) {
            val v = scene.triangles[index * 3 + k]
            triVerts[k * 2] = screen[v * 2]
            triVerts[k * 2 + 1] = screen[v * 2 + 1]
            if (size != null) {
                triTex[k * 2] = scene.uvs[v * 2] * size[0]
                triTex[k * 2 + 1] = scene.uvs[v * 2 + 1] * size[1]
            }
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, 6, triVerts, 0, if (size != null) triTex else null, 0, null, 0, null, 0, 0, paint)
    }

    /** Insertion sort on the last frame's order: a dance moves little between frames, so it is nearly sorted. */
    private fun sortByDepth(count: Int) {
        for (i in 1 until count) {
            val item = order[i]
            val d = depths[item]
            var j = i - 1
            while (j >= 0 && depths[order[j]] < d) {
                order[j + 1] = order[j]
                j -= 1
            }
            order[j + 1] = item
        }
    }

    private fun <T> List<T>.randomOrNull(): T? = if (isEmpty()) null else this[Random.nextInt(size)]

    companion object {
        const val WIDTH = 240
        const val HEIGHT = 320
        const val FPS = 30
        private const val BUFFERS = 3
        /** MilkShape's frames a second, at StepMania's reference tempo. */
        private const val ANIMATION_FPS = 30f
        private const val REFERENCE_BPM = 140f
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 2f
        private const val FILL = 0.92f
        private const val MAX_DANCES = 32
        private val JSON = Json { ignoreUnknownKeys = true }
        private val FALLBACK = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
    }
}
