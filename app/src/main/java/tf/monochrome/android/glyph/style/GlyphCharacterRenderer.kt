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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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
 * What it dances is [DancerChoreography]'s: warming up, then dancing from the
 * chart's first measure, resting once the run fails, every move on the song's
 * beat ([beat]), blended into the next, the camera turning about it as
 * StepMania's does. Moves are read as they are needed, a few at a time, never
 * all of them at once. Nothing is drawn while [paused]: the picture holds.
 */
class GlyphCharacterRenderer(
    private val characterDir: File,
    private val sharedDance: (String) -> File?,
    private val scope: CoroutineScope,
    /** The song's beat now, stops and tempo changes included. */
    private val beat: () -> Float,
    /** Where the chart's notes begin, in beats. */
    private val firstNoteBeat: () -> Float,
    /** Whether the run has failed: the dancer rests. */
    private val failed: () -> Boolean,
    /** Whether the run is paused: the dancer holds. */
    private val paused: () -> Boolean,
) {
    // Three, so the one being drawn is never the one the screen is reading.
    private val buffers = Array(BUFFERS) { Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888) }
    private var drawing = 0

    private val _frame = MutableStateFlow<Bitmap?>(null)
    /** The last finished picture; null until the first. Read on the main thread. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private var job: Job? = null

    /** The moves read so far, by name; read on the render thread, filled by the loader. */
    private val loaded = ConcurrentHashMap<String, Move>()
    @Volatile private var wanted: Set<String> = emptySet()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) {
            val scene = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() } ?: return@launch
            val choreography = DancerChoreography(scene.moves, { name -> loaded[name]?.beats })
            launch(Dispatchers.IO) { loadMoves(scene) }
            var last = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.25f)
                last = now
                if (!paused() || _frame.value == null) {
                    val step = choreography.update(beat(), dt, firstNoteBeat(), failed())
                    wanted = choreography.wanted()
                    render(scene, step)
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

    /** A move put on this skeleton, and how many beats it lasts. */
    private class Move(val bones: Array<Milkshape.Bone?>, val beats: Float)

    private class Scene(
        val pose: CharacterPose,
        val moves: Map<DancerChoreography.Phase, List<String>>,
        val paints: Array<Paint?>,
        val textureSizes: Array<IntArray?>,
        /** Every triangle across the meshes: its three vertices (counted across meshes) and its material. */
        val triangles: IntArray,
        val triangleMaterial: IntArray,
        /** Every vertex's texture coordinate, across meshes. */
        val uvs: FloatArray,
        /** The dancer's middle and size, framed once on its first move, so it stays still on screen. */
        val centreX: Float, val centreY: Float, val centreZ: Float, val scale: Float,
    )

    private fun load(): Scene? {
        val model = Milkshape.parse(File(characterDir, StepManiaStyleBaker.MODEL).readText())
        val pose = CharacterPose(model)
        val byFolder = runCatching {
            JSON.decodeFromString<StepManiaStyleBaker.CharacterMoves>(File(characterDir, StepManiaStyleBaker.MOVES_INFO).readText()).moves
        }.getOrDefault(emptyMap())
        val moves = mapOf(
            DancerChoreography.Phase.WARMUP to byFolder["warmup"].orEmpty().distinct().take(MAX_MOVES),
            DancerChoreography.Phase.DANCE to byFolder["dance"].orEmpty().distinct().take(MAX_MOVES),
            DancerChoreography.Phase.REST to byFolder["rest"].orEmpty().distinct().take(MAX_MOVES),
        )
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
        // Framed standing as it warms up (the model's own rest pose can be folded up),
        // wide enough for the camera to go round it.
        var first: Move? = null
        for (name in moves.getValue(DancerChoreography.Phase.WARMUP) + moves.getValue(DancerChoreography.Phase.REST) +
            moves.getValue(DancerChoreography.Phase.DANCE)) {
            first = readMove(pose, name) ?: continue
            loaded[name] = first
            break
        }
        val positions = FloatArray(pose.vertexCount * 3)
        pose.pose(first?.bones, 0f, positions)
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var sumX = 0.0; var sumZ = 0.0
        for (v in 0 until pose.vertexCount) {
            minY = minOf(minY, positions[v * 3 + 1]); maxY = maxOf(maxY, positions[v * 3 + 1])
            sumX += positions[v * 3]; sumZ += positions[v * 3 + 2]
        }
        if (maxY <= minY || pose.vertexCount == 0) return null
        val cx = (sumX / pose.vertexCount).toFloat()
        val cz = (sumZ / pose.vertexCount).toFloat()
        var reach = 0.001f
        for (v in 0 until pose.vertexCount) {
            val dx = positions[v * 3] - cx
            val dz = positions[v * 3 + 2] - cz
            reach = maxOf(reach, sqrt(dx * dx + dz * dz))
        }
        val scale = minOf(HEIGHT * FILL / (maxY - minY), WIDTH * FILL / (2 * reach))
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
        return Scene(pose, moves, paints, sizes, triangles, triangleMaterial, uvs, cx, (minY + maxY) / 2, cz, scale)
    }

    /** The move named [name] (the character's own, or a shared one by file name) on this skeleton; null when unreadable. */
    private fun readMove(pose: CharacterPose, name: String): Move? {
        val file = if (name.startsWith(StepManiaStyleBaker.OWN_PREFIX)) {
            File(characterDir, "${StepManiaStyleBaker.MOVES}/${name.removePrefix(StepManiaStyleBaker.OWN_PREFIX)}")
        } else {
            sharedDance(name)
        } ?: return null
        val parsed = runCatching { Milkshape.parse(file.readText()) }.getOrNull() ?: return null
        return Move(pose.bind(parsed), DancerChoreography.beatsOf(parsed.length))
    }

    /** Reads the moves the choreography wants, one at a time, and lets go of the rest. */
    private suspend fun loadMoves(scene: Scene) {
        val unreadable = HashSet<String>()
        while (currentCoroutineContext().isActive) {
            val want = wanted
            val missing = want.firstOrNull { !loaded.containsKey(it) && it !in unreadable }
            if (missing != null) {
                readMove(scene.pose, missing)?.let { loaded[missing] = it } ?: unreadable.add(missing)
                continue
            }
            if (loaded.size > KEEP_MOVES) loaded.keys.filter { it !in want }.forEach { loaded.remove(it) }
            delay(LOAD_POLL_MS)
        }
    }

    // ── drawing ─────────────────────────────────────────────────────────

    private var order = IntArray(0)
    private var depths = FloatArray(0)
    private var screen = FloatArray(0)
    private var posed = FloatArray(0)
    private var leaving = FloatArray(0)
    private var turnedDepth = FloatArray(0)
    private val triVerts = FloatArray(6)
    private val triTex = FloatArray(6)

    private fun render(scene: Scene, step: DancerChoreography.Step) {
        val pose = scene.pose
        val count = pose.vertexCount
        if (posed.size < count * 3) {
            posed = FloatArray(count * 3)
            leaving = FloatArray(count * 3)
            turnedDepth = FloatArray(count)
            screen = FloatArray(count * 2)
        }
        pose.pose(step.move?.let { loaded[it] }?.bones, step.frame, posed)
        // Into the new move from the last, over half a beat.
        val from = step.previous?.let { loaded[it] }
        if (from != null && step.blend < 1f) {
            pose.pose(from.bones, step.previousFrame, leaving)
            val w = step.blend
            for (i in 0 until count * 3) posed[i] = leaving[i] + (posed[i] - leaving[i]) * w
        }
        // The camera about the dancer's middle. Characters face MilkShape's -Z:
        // turned to face the player (x mirrored), deeper is +z once turned round.
        val yaw = Math.toRadians(step.yawDegrees.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        val triangles = scene.triangleMaterial.size
        if (order.size != triangles) {
            order = IntArray(triangles) { it }
            depths = FloatArray(triangles)
        }
        val p = posed
        for (v in 0 until count) {
            val x = p[v * 3] - scene.centreX
            val z = p[v * 3 + 2] - scene.centreZ
            screen[v * 2] = WIDTH / 2f - (x * c + z * s) * scene.scale
            screen[v * 2 + 1] = HEIGHT / 2f - (p[v * 3 + 1] - scene.centreY) * scene.scale
            turnedDepth[v] = -x * s + z * c
        }
        val tri = scene.triangles
        for (i in 0 until triangles) {
            depths[i] = turnedDepth[tri[i * 3]] + turnedDepth[tri[i * 3 + 1]] + turnedDepth[tri[i * 3 + 2]]
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

    companion object {
        const val WIDTH = 240
        const val HEIGHT = 320
        const val FPS = 30
        private const val BUFFERS = 3
        private const val FILL = 0.92f
        private const val MAX_MOVES = 32
        /** Moves kept read beyond those wanted now; a DDR PC dance is about a megabyte of text. */
        private const val KEEP_MOVES = 6
        private const val LOAD_POLL_MS = 250L
        private val JSON = Json { ignoreUnknownKeys = true }
        private val FALLBACK = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
    }
}
